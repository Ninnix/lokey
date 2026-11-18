package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ResponsivenessChecks {
    fun stable(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val oldModel=prefs.model;val oldCorrection=prefs.autocorrect
        prefs.model="qwen";prefs.autocorrect=false
        check(Models.ready(context,Models.selectedText(context,prefs)))
        lateinit var engine:Prediction
        test.runOnMainSync { engine=Prediction(context,prefs) }
        try {
            val warm=CountDownLatch(1)
            test.runOnMainSync { engine.request("See you ",neuralAllowed=false,stable=true) { warm.countDown() } }
            check(warm.await(10,TimeUnit.SECONDS))
            val before="See you "
            val published=ArrayList<Prediction.Result>()
            val done=CountDownLatch(1);val start=SystemClock.elapsedRealtime()
            test.runOnMainSync { engine.request(before,stable=true) { published.add(it);done.countDown() } }
            check(done.await(10,TimeUnit.SECONDS))
            val firstMs=SystemClock.elapsedRealtime()-start
            val deadline=SystemClock.uptimeMillis()+20_000L
            while(engine.latest?.neural!=true && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20)
            val ranked=checkNotNull(engine.latest?.takeIf { it.before==before && it.neural })
            var cachedMs=0.0
            test.runOnMainSync {
                check(published.size==1 && published.single().words.isNotEmpty()) { "Suggestions were republished: $published" }
                val typed=ranked.words.first { it.first().isLetter() }.first()
                val began=SystemClock.elapsedRealtimeNanos()
                var cached:Prediction.Result?=null
                engine.request(before+typed,stable=true) { cached=it }
                check(cached?.neural==true && cached.words.all { it.startsWith(typed,true) })
                cachedMs=(SystemClock.elapsedRealtimeNanos()-began)/1e6
            }
            return JSONObject().put("backend",Native.textBackend()).put("model",prefs.model)
                .put("first_ms",firstMs).put("first_neural",published.single().neural).put("cached_ms",cachedMs).put("publications",published.size)
        } finally { test.runOnMainSync { engine.close() };prefs.model=oldModel;prefs.autocorrect=oldCorrection }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val sandbox=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("response-check-$name",mode)
        }
        val prefs=Prefs(sandbox);val store=sandbox.getSharedPreferences("lokey",0)
        store.edit().clear().commit();prefs.model="qwen";prefs.autocorrect=false
        lateinit var engine:Prediction
        test.runOnMainSync { engine=Prediction(sandbox,prefs) }
        try {
            val ready=CountDownLatch(1);val start=SystemClock.elapsedRealtime()
            test.runOnMainSync { engine.request("See you ") { if(it.neural) ready.countDown() } }
            check(ready.await(45,TimeUnit.SECONDS))
            val cold=SystemClock.elapsedRealtime()-start
            val samples=JSONArray()
            // Cache misses remain valid responses; measure their completion separately.
            repeat(40) { iteration ->
                for(prefix in listOf("t","tomorr","tomorro")) {
                    var answer:Prediction.Result?=null;var elapsed=0.0;var returned=0.0;var synchronous=false
                    val done=CountDownLatch(1)
                    test.runOnMainSync {
                        val at=SystemClock.elapsedRealtimeNanos()
                        engine.request("See you $prefix") { if(it.neural && answer==null) {
                            elapsed=(SystemClock.elapsedRealtimeNanos()-at)/1e6;answer=it;done.countDown()
                        } }
                        returned=(SystemClock.elapsedRealtimeNanos()-at)/1e6;synchronous=answer!=null
                    }
                    check(done.await(15,TimeUnit.SECONDS))
                    val value=answer!!
                    check(value.words.isNotEmpty() && value.words.all { word -> word.startsWith(prefix,true) })
                    if(iteration>=20) samples.put(JSONObject().put("prefix",prefix).put("words",JSONArray(value.words))
                        .put("ms",returned).put("result_ms",elapsed).put("synchronous",synchronous))
                }
            }
            return JSONObject().put("model","qwen").put("cold_request_ms",cold).put("cached",samples)
        } finally { test.runOnMainSync { engine.close() };store.edit().clear().commit() }
    }
}
