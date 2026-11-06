package org.lokey.app

import android.app.Application

class LokeyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Acceleration.start()
        getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel("downloads")
        Downloads.cancel(this,"model:onejev")
        Models.io.execute {
            for(suffix in listOf("",".part",".part.validator")) java.io.File(filesDir,"models/OneJev1.6-0.8B.Q4_K_M.gguf$suffix").delete()
        }
        Downloads.resume(this)
        Thread({ Languages.shared(this) }, "lokey-vocabulary").apply { priority = Thread.MIN_PRIORITY; start() }
    }
}
