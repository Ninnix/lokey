package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp

object MemoryChecks {
    private fun waitFor(message:String,timeout:Long=30_000,ready:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!ready()) { check(SystemClock.elapsedRealtime()<until) { message };SystemClock.sleep(50) }
    }
    private fun snapshot():JSONObject {
        Runtime.getRuntime().gc();SystemClock.sleep(100)
        val maps=File("/proc/self/maps").readLines().filter { ".gguf" in it }.map { it.substringAfterLast('/') }.distinct()
        return JSONObject().put("pss_kib",Debug.getPss()).put("native_heap_kib",Debug.getNativeHeapAllocatedSize()/1024)
            .put("resident",Native.residentModels()).put("mapped_models",JSONArray(maps))
    }
    private fun released(row:JSONObject,accelerator:Int) {
        val key=if(accelerator==1) "pss_kib" else "native_heap_kib"
        // DSP allocations are not fully charged to process PSS.
        check(row.getJSONObject("loaded").getLong(key)-row.getJSONObject("released").getLong(key)>32*1024) { "Model allocations remained resident: $row" }
    }
    private fun empty() {
        waitFor("Models still retained") { Native.trimModels(0);Native.residentModels()==0 }
        check(File("/proc/self/maps").readLines().none { ".gguf" in it })
    }
    private fun rank()=Native.rank("See you","English",arrayOf("tomorrow","today","tonight")).also {
        check(it.size==3 && it.all { score -> score.isFinite() })
    }
    private fun <T> using(kind:Int,task:()->T):T {
        Native.beginModelUse(kind)
        try { return task() } finally { Native.endModelUse(kind) }
    }
    private fun held(kind:Int) {
        val worker=ModelMemory.Worker("lokey-memory-check",kind)
        val entered=CountDownLatch(1);val leave=CountDownLatch(1);val finished=CountDownLatch(1)
        val generation=Native.modelGeneration(kind)
        try {
            worker.execute { entered.countDown();try { check(leave.await(10,TimeUnit.SECONDS)) } finally { finished.countDown() } }
            check(entered.await(10,TimeUnit.SECONDS))
            val start=SystemClock.elapsedRealtime()
            check(Native.trimModels(0) and (1 shl kind)==0)
            check(SystemClock.elapsedRealtime()-start<1000) { "Trim blocked on an active worker" }
            check(Native.residentModels() and (1 shl kind)!=0 && Native.modelGeneration(kind)==generation)
        } finally { leave.countDown();check(finished.await(10,TimeUnit.SECONDS));worker.shutdown() }
    }
    fun run(test:Instrumentation,context:Context,accelerator:Int):JSONObject {
        Native.release()
        val rows=JSONArray();val voices=JSONArray()
        val sandbox=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("memory-check-$name",mode)
        }
        val prefs=Prefs(sandbox);val store=sandbox.getSharedPreferences("lokey",0)
        store.edit().clear().commit();prefs.model="lfm";prefs.speechModel="qwen_asr"
        try {
            for(model in Models.text) {
                check(Models.ready(context,model))
                val row=JSONObject().put("model",model.id).put("before",snapshot());val path=Models.file(context,model).path
                val scores=using(ModelMemory.TEXT) {
                    val start=SystemClock.elapsedRealtime();check(Native.loadText(path,accelerator))
                    row.put("load_ms",SystemClock.elapsedRealtime()-start).put("backend",Native.textBackend())
                    check(Native.textBackend()==if(accelerator==1) "CPU" else "HTP0")
                    val value=rank();row.put("loaded",snapshot());val generation=Native.modelGeneration(ModelMemory.TEXT)
                    check(Native.trimModels(ModelMemory.IDLE_MS)==0 && generation==Native.modelGeneration(ModelMemory.TEXT));value
                }
                held(ModelMemory.TEXT);empty();row.put("released",snapshot())
                released(row,accelerator)
                val reload=SystemClock.elapsedRealtime();val fresh=using(ModelMemory.TEXT) { check(Native.loadText(path,accelerator));rank() }
                row.put("reload_ms",SystemClock.elapsedRealtime()-reload)
                row.put("initial_scores",JSONArray(scores.toList())).put("reloaded_scores",JSONArray(fresh.toList()))
                val scoreError=scores.indices.maxOf { abs(scores[it]-fresh[it]) }
                val probabilityError=scores.indices.maxOf { abs(exp(scores[it])-exp(fresh[it])) }
                row.put("max_logprob_error",scoreError).put("max_probability_error",probabilityError)
                File(context.filesDir,"memory-score-details.json").writeText(row.toString())
                check(scoreError<(if(accelerator==1) .001f else 1f) && probabilityError<(if(accelerator==1) .0001f else .05f)) {
                    "${model.id} reload scores: ${scores.toList()} -> ${fresh.toList()} (probability error=$probabilityError)"
                }
                empty();rows.put(row)
            }
            val samples=SpeechChecks.audio(File(context.filesDir,"jfk.wav"))
            for(model in Models.voices) {
                check(Models.ready(context,model))
                val path=Models.file(context,model).path;val encoder=model.parts.firstOrNull()?.let { Models.file(context,it).path } ?: ""
                val selected=if(accelerator==1) 1 else 3
                val row=JSONObject().put("model",model.id).put("before",snapshot());val start=SystemClock.elapsedRealtime()
                using(ModelMemory.SPEECH) {
                    check(Native.loadSpeech(path,encoder,selected));val backend=Native.speechBackend()
                    check(if(accelerator==1) backend=="CPU" else backend.startsWith("HTP0"))
                    row.put("load_ms",SystemClock.elapsedRealtime()-start).put("backend",backend)
                    val text=Native.transcribe(samples);check(text.contains("country",true) && text.contains("ask",true))
                    row.put("loaded",snapshot());check(Native.trimModels(ModelMemory.IDLE_MS)==0)
                }
                held(ModelMemory.SPEECH);empty();row.put("released",snapshot())
                released(row,accelerator)
                val reload=SystemClock.elapsedRealtime()
                using(ModelMemory.SPEECH) {
                    check(Native.loadSpeech(path,encoder,selected))
                    val again=Native.transcribe(samples);check(again.contains("country",true) && again.contains("ask",true))
                }
                row.put("reload_and_transcribe_ms",SystemClock.elapsedRealtime()-reload)
                if(model==Models.voices.last()) {
                    check(Native.loadText(Models.file(context,Models.text.first()).path,accelerator));rank()
                    SystemClock.sleep(150);Native.touchModel(ModelMemory.TEXT)
                    check(Native.trimModels(100)==2 && Native.residentModels()==1) { "Active prediction retained idle speech" }
                }
                empty();voices.put(row)
            }
            lateinit var prediction:Prediction
            test.runOnMainSync { prediction=Prediction(sandbox,prefs) }
            try {
                fun request(before:String):Prediction.Result {
                    val done=CountDownLatch(1);var result:Prediction.Result?=null
                    test.runOnMainSync { prediction.request(before) { if(it.neural) { result=it;done.countDown() } } }
                    check(done.await(45,TimeUnit.SECONDS));return result!!
                }
                check(request("See you ").words.isNotEmpty())
                val generation=Native.modelGeneration(ModelMemory.TEXT)
                check(request("See you tom").words.isNotEmpty())
                check(generation==Native.modelGeneration(ModelMemory.TEXT)) { "Warm prediction reloaded" }
                empty()
                test.runOnMainSync {
                    var cached=false
                    prediction.request("See you tom") { if(it.neural) cached=true }
                    check(!cached) { "Evicted model reused a stale candidate cache" }
                }
                check(request("See you ").words.isNotEmpty())
                check(generation!=Native.modelGeneration(ModelMemory.TEXT))
            } finally { test.runOnMainSync { prediction.close() };empty() }
            val voice=Voice(sandbox)
            try {
                voice.prepare();waitFor("Voice prepare failed") { Native.residentModels() and 2!=0 };SystemClock.sleep(100)
                val generation=Native.modelGeneration(ModelMemory.SPEECH)
                voice.prepare();SystemClock.sleep(300)
                check(generation==Native.modelGeneration(ModelMemory.SPEECH)) { "Warm speech reloaded" }
                empty();voice.prepare();waitFor("Speech identity survived eviction") { Native.residentModels() and 2!=0 }
                check(generation!=Native.modelGeneration(ModelMemory.SPEECH))
            } finally { voice.close();empty() }
            lateinit var translation:Translation
            test.runOnMainSync { translation=Translation(sandbox) }
            try {
                fun translate(source:String):String {
                    val done=CountDownLatch(1);var result:Translation.Result?=null
                    test.runOnMainSync { translation.request(source,"Italian") { if(!it.busy) { result=it;done.countDown() } } }
                    check(done.await(60,TimeUnit.SECONDS));check(result!!.error==null) { result!!.error!! };check(result!!.text.isNotBlank())
                    return result!!.text
                }
                translate("Good morning.");val generation=Native.modelGeneration(ModelMemory.TRANSLATION)
                translate("Good evening.");check(generation==Native.modelGeneration(ModelMemory.TRANSLATION))
                held(ModelMemory.TRANSLATION);empty()
                translate("Thank you.");check(generation!=Native.modelGeneration(ModelMemory.TRANSLATION))
            } finally { test.runOnMainSync { translation.close() };empty() }
            return JSONObject().put("text",rows).put("speech",voices).put("busy_workers_protected",true)
                .put("warm_reuse",true).put("independent_idle_models",true).put("kotlin_caches_reload",true).put("translation_reload",true)
        } finally { store.edit().clear().commit();Native.release() }
    }
    fun idle(test:Instrumentation,context:Context,accelerator:Int):JSONObject {
        lateinit var prediction:Prediction
        test.runOnMainSync { prediction=Prediction(context,Prefs(context)) }
        Native.release()
        try {
            val model=Models.text.first { it.id=="lfm" };check(Models.ready(context,model))
            val path=Models.file(context,model).path
            val loaded=using(ModelMemory.TEXT) { check(Native.loadText(path,accelerator));rank();snapshot() }
            val start=SystemClock.elapsedRealtime();val generation=Native.modelGeneration(ModelMemory.TEXT)
            SystemClock.sleep(1000);check(Native.residentModels()==1)
            val trace=JSONArray();var recorded=0L;var cleared=0L
            waitFor("Production timer did not free the idle model",ModelMemory.IDLE_MS+30_000) {
                val now=SystemClock.elapsedRealtime()
                if(now-cleared>1000) { test.runOnMainSync { prediction.clear() };cleared=now }
                if(now-recorded>10_000) {
                    trace.put(JSONObject().put("at_ms",now-start).put("idle_ms",Native.modelIdleMillis(ModelMemory.TEXT))
                        .put("users",Native.modelUsers(ModelMemory.TEXT)).put("resident",Native.residentModels()).put("generation",Native.modelGeneration(ModelMemory.TEXT)))
                    File(context.filesDir,"memory-idle-trace.json").writeText(trace.toString());recorded=now
                }
                Native.residentModels()==0
            }
            val elapsed=SystemClock.elapsedRealtime()-start
            File(context.filesDir,"memory-idle-details.json").writeText(JSONObject().put("idle_ms",elapsed).put("loaded",loaded).toString())
            check(elapsed>=ModelMemory.IDLE_MS-2000) { "Released after $elapsed ms; pressure or another user may have released the model" }
            check(File("/proc/self/maps").readLines().none { ".gguf" in it })
            val released=snapshot();val reload=SystemClock.elapsedRealtime()
            check(Native.loadText(path,accelerator));rank();check(generation!=Native.modelGeneration(ModelMemory.TEXT))
            return JSONObject().put("backend",Native.textBackend()).put("idle_ms",elapsed).put("loaded",loaded)
                .put("released",released).put("reload_and_rank_ms",SystemClock.elapsedRealtime()-reload).put("repeated_editor_cleanup",true)
        } finally { test.runOnMainSync { prediction.close() };Native.release() }
    }
}
