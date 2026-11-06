package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp

object PredictionChecks {
    fun languages(context:Context):JSONObject {
        data class Case(val language:String,val context:String,val prefix:String,val expected:List<String>)
        val prefixes=listOf(
            Case("English","Good","mor",listOf("morning")),
            Case("Italian","Ci vediamo","doma",listOf("domani")),
            Case("Spanish","Muchas","grac",listOf("gracias")),
            Case("French","Merci","beau",listOf("beaucoup")),
            Case("German","Vielen","Dan",listOf("Dank")),
            Case("Portuguese","Muito","obri",listOf("obrigado","obrigada")),
            Case("Russian","Большое","спаси",listOf("спасибо")),
            Case("Greek","Ευχαριστώ","πο",listOf("πολύ")),
            Case("Arabic","شكرا","جزي",listOf("جزيلا","جزيلًا","جزيلاً"))
        )
        val next=prefixes.map { case -> case.copy(prefix="",expected=case.expected) }.toMutableList()
        next[0]=Case("English","Thank you very","",listOf("much"))
        next[1]=Case("Italian","Ti voglio","",listOf("bene"))
        val cases=prefixes+next+listOf(
            Case("Italian","C'é un amico mio che ha una casa in costa","azzu",listOf("azzurra","azzurro")),
            Case("English","The mitochondria produce adenosine","triphos",listOf("triphosphate","triphosphates"))
        )
        val models=Models.text
        val rows=JSONArray();val profiles=JSONArray()
        for(model in models) for(accelerator in listOf(1,2)) {
            val loading=SystemClock.elapsedRealtimeNanos()
            check(Models.ready(context,model) && Native.loadText(Models.file(context,model).path,accelerator))
            profiles.put(JSONObject().put("model",model.filename).put("backend",Native.textBackend())
                .put("bytes",Models.file(context,model).length()).put("load_ms",(SystemClock.elapsedRealtimeNanos()-loading)/1e6))
            Native.complete("See you","English","tom")
            for(case in cases) {
                Native.resetText()
                val start=SystemClock.elapsedRealtimeNanos()
                val words=Native.complete(case.context,case.language,case.prefix).lines().filter { it.isNotBlank() }
                val matched=words.take(3).any { word -> case.expected.any { it.equals(word,true) } }
                val valid=words.all { word -> word.startsWith(case.prefix,true) &&
                    (word in listOf("?",".","!") || word.first().isLetter() && word.all { Languages.isWord(it) }) } &&
                    words.distinctBy { it.lowercase() }.size==words.size
                val row=JSONObject().put("model",model.filename).put("backend",Native.textBackend()).put("language",case.language)
                    .put("prompt","language_label").put("context",case.context).put("prefix",case.prefix)
                    .put("words",JSONArray(words)).put("expected",JSONArray(case.expected)).put("top3_hit",matched).put("valid",valid)
                    .put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
                rows.put(row)
                File(context.filesDir,"probability-language-details.json").writeText(rows.toString(2))
                check(valid) { "Malformed prediction: $row" }
            }
        }
        return JSONObject().put("cases",rows).put("fixture_count",cases.size).put("models",profiles)
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val report=JSONObject();val native=JSONArray()
        for(model in Models.text) {
            check(Models.ready(context,model)) { "Missing ${model.filename}" }
            for(accelerator in listOf(1,2)) {
                check(Native.loadText(Models.file(context,model).path,accelerator))
                val words=arrayOf("tomorrow","today","there","at","soon","later","?",".")
                fun rank(text:String)=Native.rank(text,"English",words).also { check(it.size==words.size && it.all(Float::isFinite)) }
                fun compare(a:FloatArray,b:FloatArray):Float {
                    val error=a.indices.maxOf { abs(a[it]-b[it]) }
                    val probabilityError=a.indices.maxOf { abs(exp(a[it])-exp(b[it])) }
                    check(error<(if(accelerator==1) .001f else 1f) && probabilityError<(if(accelerator==1) .0001f else .05f)) {
                        "Reused state differs from cold prefill: log=$error, probability=$probabilityError (${model.filename}, $accelerator)"
                    }
                    return error
                }
                val transitions=listOf(
                    listOf("I will meet you", "I will meet you at", "I will meet you at the station"),
                    listOf("I will meet you", "I will meet you tomorrow", "I will meet you"),
                    listOf("I will meet you", "I will meet you tomorrow", "I will meet you today"),
                    listOf("The evening was wonderful", "The evening was wonderfully warm", "The evening was wondrous"),
                    listOf("I will meet you tomorrow", "I will"),
                    listOf("We are discussing the weekend", "A different field starts here")
                )
                val reuse=JSONArray()
                for(chain in transitions) {
                    Native.resetText()
                    chain.dropLast(1).forEach { rank(it) }
                    val start=SystemClock.elapsedRealtimeNanos();val actual=rank(chain.last())
                    val reused=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                    Native.resetText()
                    val coldStart=SystemClock.elapsedRealtimeNanos();val expected=rank(chain.last())
                    val cold=(SystemClock.elapsedRealtimeNanos()-coldStart)/1e6
                    reuse.put(JSONObject().put("context",chain.last()).put("reused_ms",reused).put("cold_ms",cold).put("max_logprob_error",compare(actual,expected))
                        .put("max_probability_error",actual.indices.maxOf { abs(exp(actual[it])-exp(expected[it])) }))
                }
                val boundary="See you"
                Native.resetText();val original=rank(boundary)
                val start=SystemClock.elapsedRealtimeNanos()
                val pool=Native.complete(boundary,"English","").lines().filter { it.isNotBlank() }
                val elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                check(pool.size>3 && pool.distinctBy { it.lowercase() }.size==pool.size) { "Candidate pool too small: $pool" }
                Native.score(boundary,"English",arrayOf("tomorrow","today","later"))
                check(original.contentEquals(rank(boundary))) { "Speculative words changed boundary scores" }
                val next=rank("See you tomorrow")
                Native.resetText();compare(next,rank("See you tomorrow"))
                val completions=JSONArray()
                if(model.id=="qwen") for((prefix,expected) in listOf("azzu" to "azzurra","azzur" to "azzurra")) {
                    val result=Native.complete("C'é un amico mio che ha una casa in costa","Italian",prefix).lines().filter { it.isNotBlank() }
                    check(expected in result.take(3)) { "Missing $expected for $prefix: $result" }
                    check(result.all { it.startsWith(prefix,true) })
                    completions.put(JSONObject().put("prefix",prefix).put("words",JSONArray(result)))
                }
                native.put(JSONObject().put("model",model.filename).put("backend",Native.textBackend()).put("reuse",reuse)
                    .put("pool",JSONArray(pool)).put("pool_ms",elapsed).put("prefixes",completions).put("speculation_isolated",true))
                File(context.filesDir,"probability-details.json").writeText(native.toString(2))
            }
        }
        report.put("native",native)
        val prefs=Prefs(context);val oldModel=prefs.model;val oldCorrection=prefs.autocorrect
        prefs.model="qwen";prefs.autocorrect=false
        lateinit var engine:Prediction
        test.runOnMainSync { engine=Prediction(context,prefs) }
        try {
            val boundary="See you "
            val latch=CountDownLatch(1)
            test.runOnMainSync { engine.request(boundary) { if(it.neural) latch.countDown() } }
            check(latch.await(20,TimeUnit.SECONDS))
            val cached=JSONArray()
            for(prefix in listOf("t","to","tom")) {
                var immediate:Prediction.Result?=null
                val start=SystemClock.elapsedRealtimeNanos()
                test.runOnMainSync {
                    engine.request(boundary+prefix) { if(it.neural && immediate==null) immediate=it }
                    check(immediate!=null) { "No immediate cached suggestions for $prefix" }
                }
                val answer=immediate!!
                check(answer.before==boundary+prefix && answer.words.size<=3 && answer.words.all { it.startsWith(prefix,true) })
                check(abs(answer.letters.sum()-1f)<.0001f)
                cached.put(JSONObject().put("prefix",prefix).put("words",JSONArray(answer.words)).put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6))
            }
            report.put("cache",cached)
            val longBoundary="We can discuss the plan when we meet. ".repeat(8)+"See you "
            val longReady=CountDownLatch(1);var longWord=""
            test.runOnMainSync { engine.request(longBoundary.takeLast(240)) { value ->
                if(value.neural) { longWord=value.words.first { it.first().isLetter() };longReady.countDown() }
            } }
            check(longReady.await(20,TimeUnit.SECONDS))
            test.runOnMainSync {
                var immediate=false
                engine.request((longBoundary+longWord.first()).takeLast(240)) { if(it.neural) immediate=true }
                check(immediate) { "Sliding editor window lost the word-boundary cache" }
            }
            report.put("sliding_editor_window_cached",true)
            val stale=ArrayList<String>();val done=CountDownLatch(1)
            test.runOnMainSync {
                engine.request("This old request should be discarded ") { if(it.neural) stale.add(it.before) }
            }
            SystemClock.sleep(150)
            test.runOnMainSync {
                stale.clear()
                engine.request("The latest request is here ") { if(it.neural) { stale.add(it.before);done.countDown() } }
            }
            check(done.await(20,TimeUnit.SECONDS))
            check(stale==listOf("The latest request is here ")) { "Stale result: $stale" }
            test.runOnMainSync {
                engine.clear()
                var synchronous=false
                engine.request(boundary+"tom") { if(it.neural) synchronous=true }
                check(!synchronous) { "Candidate cache crossed an editor session" }
                engine.clear()
            }
            report.put("stale_results_discarded",true).put("session_cache_cleared",true)
        } finally { test.runOnMainSync { engine.close() };prefs.model=oldModel;prefs.autocorrect=oldCorrection }
        return report
    }
}
