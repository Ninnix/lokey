package org.lokey.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Acceleration {
    @Volatile var npu=false
        private set
    @Volatile var checked=false
        private set
    private var started=false
    private val done=CountDownLatch(1)
    @Synchronized fun start() {
        if(started) return
        started=true
        Thread({
            try { npu=Native.npuAvailable() } catch(_:LinkageError) { npu=false }
            finally { checked=true;done.countDown() }
        },"lokey-acceleration").start()
    }
    fun await():Boolean { start();check(done.await(15,TimeUnit.SECONDS)) { "Acceleration probe timed out" };return npu }
    fun observed(backend:String) { if(checked && backend=="CPU") npu=false }
}
