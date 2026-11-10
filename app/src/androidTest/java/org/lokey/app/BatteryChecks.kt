package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BatteryChecks {
    private fun waitFor(ready:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+15_000
        while(!ready()) { check(SystemClock.elapsedRealtime()<until);SystemClock.sleep(20) }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        Native.release()
        val sweeps=ModelMemory.sweepCount
        SystemClock.sleep(16_000)
        check(ModelMemory.sweepCount==sweeps) { "Empty model cache still wakes the memory sweeper" }
        check(Native.nextModelIdleMillis(ModelMemory.IDLE_MS)==-1L)
        lateinit var engine:Prediction
        test.runOnMainSync { engine=Prediction(context,Prefs(context));engine.clear() }
        try {
            waitFor { engine.workCount>=2 };SystemClock.sleep(100)
            val clean=engine.workCount
            test.runOnMainSync { repeat(100) { engine.clear() } }
            SystemClock.sleep(200)
            check(engine.workCount==clean) { "Repeated cleanup scheduled extra native resets" }
            val ready=CountDownLatch(1)
            test.runOnMainSync { engine.request("See you ",false) { check(!it.neural);ready.countDown() } }
            check(ready.await(15,TimeUnit.SECONDS))
            val dirty=engine.workCount
            test.runOnMainSync { repeat(100) { engine.clear() } }
            waitFor { engine.workCount>dirty };SystemClock.sleep(100)
            check(engine.workCount==dirty+1) { "Editor state was not reset exactly once" }
            test.runOnMainSync {
                // Hidden callbacks must not query an editor, even before the service is attached.
                val service=LokeyService()
                val refresh=LokeyService::class.java.getDeclaredMethod("refresh").apply { isAccessible=true }
                repeat(100) { refresh.invoke(service) }
            }
            return JSONObject().put("empty_idle_sweeps",ModelMemory.sweepCount-sweeps)
                .put("empty_idle_ms",16_000).put("redundant_cleanup_requests",100).put("redundant_cleanup_jobs",0)
                .put("dirty_cleanup_jobs",1).put("hidden_refresh_skipped",true)
        } finally { test.runOnMainSync { engine.close() };Native.release() }
    }
}
