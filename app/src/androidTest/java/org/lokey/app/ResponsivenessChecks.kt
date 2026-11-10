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
