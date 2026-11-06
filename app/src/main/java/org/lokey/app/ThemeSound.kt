package org.lokey.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

class ThemeSound(context:Context) {
    private val audio=context.getSystemService(AudioManager::class.java)
    private val pool=SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    @Volatile var ready=false
        private set
    private var pending:Float?=null
    private val sample:Int
    init {
        pool.setOnLoadCompleteListener { _,_,status -> if(status==0) { ready=true;pending?.let { play(it) };pending=null } }
        sample=context.assets.openFd("ui/light-switch.wav").use { pool.load(it,1) }
    }
    private fun play(pitch:Float):Int = if(audio.ringerMode==AudioManager.RINGER_MODE_NORMAL && audio.getStreamVolume(AudioManager.STREAM_SYSTEM)>0)
        pool.play(sample,.65f,.65f,1,0,pitch) else 0
    fun click(light:Boolean):Int {
        val pitch=if(light) 1.05f else .95f
        if(ready) return play(pitch)
        pending=pitch;return 0
    }
    fun close() { pending=null;pool.release() }
}
