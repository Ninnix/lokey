package org.lokey.app

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ModelMemory {
    const val TEXT=0
    const val SPEECH=1
    const val TRANSLATION=2
    const val IDLE_MS=120_000L
    private val timer=ScheduledThreadPoolExecutor(1) { task ->
        Thread(task,"lokey-model-memory").apply { priority=Thread.MIN_PRIORITY }
    }.apply { removeOnCancelPolicy=true }
    private var pending:ScheduledFuture<*>?=null
    private var generation=0L
    @Volatile internal var sweepCount=0L
        private set
    @Synchronized fun changed() {
        val delay=Native.nextModelIdleMillis(IDLE_MS)
        if(delay<0) { generation++;pending?.cancel(false);pending=null;return }
        if(pending?.getDelay(TimeUnit.MILLISECONDS)?.let { it<=delay }==true) return
        pending?.cancel(false)
        val ticket=++generation
        pending=timer.schedule({
            val current=synchronized(this) {
                if(ticket!=generation) false else { pending=null;sweepCount++;true }
            }
            if(current) { release(IDLE_MS);changed() }
        },maxOf(1,delay),TimeUnit.MILLISECONDS)
    }
    private fun release(age:Long) {
        val freed=Native.trimModels(age)
        if(freed!=0) android.util.Log.i("Lokey","Released model memory: mask=$freed, idle_ms=$age")
    }
    fun trim() { timer.execute { release(0);changed() } }
    class Worker(name:String,private val kind:Int,priority:Int=Thread.NORM_PRIORITY) {
        private val executor=Executors.newSingleThreadExecutor { task ->
            Thread(task,name).apply { this.priority=priority }
        }
        val isShutdown get()=executor.isShutdown
        @Volatile var executions=0L
            private set
        // Protect the whole load/infer sequence, including the gaps between JNI calls.
        fun execute(retain:Boolean=true,task:()->Unit) { executor.execute {
            executions++
            if(retain) Native.beginModelUse(kind)
            try { task() } finally { if(retain) { Native.endModelUse(kind);changed() } }
        } }
        fun shutdown() { executor.shutdown() }
    }
}
