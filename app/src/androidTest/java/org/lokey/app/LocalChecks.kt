package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import android.graphics.Rect
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LocalChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        Native.release()
        val fixture=JSONObject(File(context.filesDir,"model-free-fixture.json").readText())
        val empty=File(context.cacheDir,"model-free-check").apply { mkdirs() }
        val sandbox=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=empty
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("model-free-check-$name",mode)
        }
        val prefs=Prefs(sandbox);val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().commit();prefs.autocorrect=true;prefs.layout="qwerty";prefs.adaptive=true
        check(Models.text.none { Models.ready(sandbox,it) } && Models.voices.none { Models.ready(sandbox,it) })
        val languages=Languages.shared(sandbox);val local=LocalPrediction(sandbox,languages)
        lateinit var engine:Prediction
        test.runOnMainSync { engine=Prediction(sandbox,prefs) }
        val rows=JSONArray();val corrections=JSONArray();val coverage=JSONArray();val controls=JSONArray();val contexts=JSONArray()
        Runtime.getRuntime().gc();SystemClock.sleep(100)
        val startPss=Debug.getPss()
        val startHeap=(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024
        var adaptive:Prediction.Result?=null
        try {
            for(i in 0 until fixture.getJSONArray("prediction").length()) {
                val case=fixture.getJSONArray("prediction").getJSONObject(i)
                var answer:Prediction.Result?=null;var firstMs=0.0;var callbacks=0
                val done=CountDownLatch(1);val start=SystemClock.elapsedRealtimeNanos()
                test.runOnMainSync { engine.clear();engine.request(case.getString("before"),true,case.optString("script").ifEmpty { null }) {
                    if(callbacks++==0) firstMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                    if(case.has("script") || callbacks>=2) { answer=it;done.countDown() }
                } }
                check(done.await(15,TimeUnit.SECONDS))
                val baselineMs=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                val language=languages.detect(case.getString("before"));val at=SystemClock.elapsedRealtimeNanos()
                val value=if(case.has("script")) null else local.request(case.getString("before"),language,true)
                rows.put(JSONObject().put("id",case.getString("id")).put("language",case.getString("language")).put("kind",case.getString("kind"))
                    .put("expected",case.getJSONArray("expected")).put("service",JSONArray(answer!!.words)).put("service_ms",baselineMs).put("first_ms",firstMs)
                    .put("candidate",JSONArray(value?.words ?: emptyList<String>())).put("candidate_core_ms",(SystemClock.elapsedRealtimeNanos()-at)/1e6)
                    .put("detected",language.code).put("letters",JSONArray(value?.letters?.toList() ?: emptyList<Float>())))
                check(!answer!!.neural)
                if(case.getString("id")=="en-prefix-1") adaptive=answer
            }
            for(i in 0 until fixture.getJSONArray("correction").length()) {
                val case=fixture.getJSONArray("correction").getJSONObject(i);val before=case.getString("before")
                val done=CountDownLatch(1);var baseline:Prediction.Result?=null
                test.runOnMainSync { engine.correct(before) { baseline=it;done.countDown() } };check(done.await(15,TimeUnit.SECONDS))
                val language=languages.detect(before);val at=SystemClock.elapsedRealtimeNanos();val value=local.correction(before,language)
                corrections.put(JSONObject().put("id",case.getString("id")).put("kind",case.getString("kind")).put("before",before)
                    .put("expected",case.getJSONArray("expected")).put("service",baseline?.correction ?: JSONObject.NULL)
                    .put("candidate",value ?: JSONObject.NULL).put("candidate_core_ms",(SystemClock.elapsedRealtimeNanos()-at)/1e6).put("language",language.code))
            }
            for(language in languages.all) {
                val probes=fixture.getJSONArray("coverage")
                for(i in 0 until probes.length()) {
                    val case=probes.getJSONObject(i);if(case.getString("language")!=language.code) continue
                    val prefix=case.getString("prefix");val baseline=languages.choices(language,"",prefix).sortedByDescending { it.prior }.take(3).map { it.word }
                    val at=SystemClock.elapsedRealtimeNanos();val value=local.request(prefix,language)
                    coverage.put(JSONObject().put("language",language.code).put("group",case.getString("group")).put("prefix",prefix).put("expected",case.getString("expected"))
                        .put("baseline",JSONArray(baseline)).put("candidate",JSONArray(value.words)).put("ms",(SystemClock.elapsedRealtimeNanos()-at)/1e6))
                }
            }
            val en=languages.all.first { it.code=="en" }
            for(i in 0 until fixture.getJSONArray("keep_terms").length()) {
                val word=fixture.getJSONArray("keep_terms").getString(i);val value=local.correction("I use $word",en)
                controls.put(JSONObject().put("word",word).put("correction",value ?: JSONObject.NULL))
                check(value==null) { "Technical term changed: $word -> $value" }
            }
            val probes=fixture.getJSONArray("contexts")
            for(language in languages.all) for(i in 0 until probes.length()) {
                val case=probes.getJSONObject(i);if(case.getString("language")!=language.code) continue
                val before=case.getString("before")
                val baseline=languages.choices(language,before,"").sortedByDescending { it.prior }.take(3).map { it.word }
                val at=SystemClock.elapsedRealtimeNanos();val value=local.request(before,language)
                contexts.put(JSONObject().put("language",language.code).put("expected",case.getString("expected"))
                    .put("baseline",JSONArray(baseline)).put("candidate",JSONArray(value.words)).put("ms",(SystemClock.elapsedRealtimeNanos()-at)/1e6))
            }
            check(Models.text.none { Models.ready(sandbox,it) } && Models.voices.none { Models.ready(sandbox,it) })
            test.runOnMainSync {
                val view=KeyboardView(sandbox,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) {};override fun delete() {};override fun enter() {};override fun cursor(delta:Int) {}
                    override fun voice(start:Boolean)=false;override fun cancelVoice() {};override fun nextKeyboard() {}
                    override fun candidate(word:String) {};override fun feedback(key:String,down:Boolean) {}
                })
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,view.measuredWidth,view.measuredHeight);view.editor(false,"Done",false)
                val original=Rect();view.accessibilityNodeProvider.createAccessibilityNodeInfo(13)!!.getBoundsInParent(original)
                val result=adaptive!!;check(result.letters['r'-'a']>.5f)
                view.prediction(result.words,result.letters,result.letters.any { it>0f },result.language)
                val expanded=Rect();view.accessibilityNodeProvider.createAccessibilityNodeInfo(13)!!.getBoundsInParent(expanded)
                check(expanded.width()>original.width()) { "Dictionary prior did not enlarge likely R" }
            }
            Runtime.getRuntime().gc();SystemClock.sleep(100)
            val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
            val mappings=File("/proc/self/maps").readLines().filter { ".gguf" in it }.map { it.substringAfterLast('/') }.distinct()
            check(mappings.isEmpty()) { "A neural model was mapped during the no-model benchmark: $mappings" }
            val report=JSONObject().put("prediction",rows).put("correction",corrections).put("coverage",coverage).put("technical_controls",controls).put("context_test",contexts)
                .put("no_model_files",true).put("start_pss_kib",startPss).put("end_pss_kib",memory.totalPss)
                .put("start_java_heap_kib",startHeap).put("end_java_heap_kib",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024)
                .put("native_heap_kib",Debug.getNativeHeapAllocatedSize()/1024).put("mapped_neural_models",JSONArray(mappings))
            File(context.filesDir,"model-free-results.json").writeText(report.toString())
            return JSONObject().put("no_model_files",true).put("prediction_cases",rows.length()).put("correction_cases",corrections.length())
                .put("coverage_cases",coverage.length()).put("context_cases",contexts.length()).put("technical_controls",controls.length())
                .put("dictionary_adaptive_keys",true).put("details","model-free-results.json")
        } finally { test.runOnMainSync { engine.close() };store.edit().clear().commit();empty.deleteRecursively() }
    }
}
