package org.lokey.app

import android.content.Context
import android.os.Handler
import android.os.Looper

internal class TypingStats(context:Context,name:String="typing_statistics") {
    data class Timing(val count:Int,val medianMs:Double,val p95Ms:Double)
    data class Snapshot(val suggestions:Long,val characters:Long,val corrections:Long,val dictations:Long,val models:Map<String,Timing>)

    private val store=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val save=Runnable { flush() }
    private var dirty=false
    private var suggestions=store.getLong("suggestions",0)
    private var characters=store.getLong("characters",0)
    private var corrections=store.getLong("corrections",0)
    private var dictations=store.getLong("dictations",0)
    private val samples=(Models.text+Models.voices).associate { model ->
        val saved=store.getString(model.id,"").orEmpty().split(',').mapNotNull(String::toLongOrNull).filter { it>0 }
        model.id to ArrayDeque(saved.takeLast(WINDOW))
    }

    @Synchronized fun suggestion(saved:Int) { suggestions++;characters+=saved;changed() }
    @Synchronized fun correction() { corrections++;changed() }
    @Synchronized fun dictation() { dictations++;changed() }
    fun speechSample(model:String,sampleCount:Int,nanos:Long) {
        // Voice captures 16 kHz PCM. Normalize to one second of recorded audio.
        if(sampleCount>0) sample(model,(nanos*16_000.0/sampleCount).toLong())
    }
    @Synchronized fun sample(model:String,nanos:Long) {
        val recent=samples[model] ?: return
        if(nanos<=0) return
        if(recent.size==WINDOW) recent.removeFirst()
        recent.addLast(nanos);changed()
    }
    @Synchronized fun snapshot():Snapshot {
        val models=samples.mapNotNull { (model,recent) ->
            if(recent.isEmpty()) return@mapNotNull null
            val sorted=recent.sorted();val n=sorted.size
            val median=(sorted[(n-1)/2]/2.0+sorted[n/2]/2.0)/1e6
            model to Timing(n,median,sorted[(95*n+99)/100-1]/1e6)
        }.toMap()
        return Snapshot(suggestions,characters,corrections,dictations,models)
    }
    private fun changed() {
        if(dirty) return
        dirty=true;main.postDelayed(save,5_000L)
    }
    @Synchronized fun flush() {
        if(!dirty) return
        main.removeCallbacks(save);dirty=false
        val edit=store.edit().putLong("suggestions",suggestions).putLong("characters",characters).putLong("corrections",corrections)
            .putLong("dictations",dictations)
        for((model,recent) in samples) if(recent.isNotEmpty()) edit.putString(model,recent.joinToString(","))
        edit.apply()
    }
    companion object {
        private const val WINDOW=64
        private var instance:TypingStats?=null
        @Synchronized fun shared(context:Context)=instance ?: TypingStats(context.applicationContext).also { instance=it }
    }
}
