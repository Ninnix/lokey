package org.lokey.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class Feedback(private val context: Context, private val prefs: Prefs) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true
    private val primitives = if (Build.VERSION.SDK_INT >= 31) vibrator?.arePrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_CLICK) ?: BooleanArray(3) else BooleanArray(3)
    private val nativeTaps = Build.VERSION.SDK_INT >= 30 && vibrator?.areEffectsSupported(
        VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK
    )?.all { it == Vibrator.VIBRATION_EFFECT_SUPPORT_YES } == true
    private val doubleClick = Build.VERSION.SDK_INT >= 30 &&
        vibrator?.areAllEffectsSupported(VibrationEffect.EFFECT_DOUBLE_CLICK)==Vibrator.VIBRATION_EFFECT_SUPPORT_YES
    private val samsung=Build.MANUFACTURER.equals("samsung",true)
    val usesSystemTaps=Build.VERSION.SDK_INT>=29 && primitives.none { it } && (nativeTaps || !amplitudeControl)
    private val touchAttributes=if(Build.VERSION.SDK_INT>=33) VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH) else null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val sounds = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val samples = ConcurrentHashMap<String, Int>()
    private val ready = ConcurrentHashMap.newKeySet<Int>()
    private val effects = ConcurrentHashMap<String, VibrationEffect>()
    @Volatile private var closed = false
    private var sequence=0
    private var lastTake=0
    init {
        sounds.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        Thread({
            val selected = prefs.switchId
            val order = Switches.profiles.sortedBy { if (it.id == selected) 0 else 1 }
            for (profile in order) {
                val path = "switches/${profile.id}"
                for (file in context.assets.list(path).orEmpty()) {
                    if (closed) return@Thread
                    loadAsset(profile.id, file)
                }
            }
        }, "lokey-switches").start()
    }
    @Synchronized private fun loadAsset(profile: String, file: String) {
        if (closed) return
        val asset="switches/$profile/$file"
        val id=if(file.endsWith(".mp3")) {
            val cached=java.io.File(context.cacheDir,"switches/$profile/$file")
            if(!cached.exists()) {
                cached.parentFile!!.mkdirs()
                context.assets.open(asset).use { input -> cached.outputStream().use { input.copyTo(it) } }
            }
            sounds.load(SwitchRecording.playable(cached).path,1)
        } else context.assets.openFd(asset).use { sounds.load(it,1) }
        samples["$profile/${file.substringBeforeLast('.')}"]=id
    }
    fun isReady(profile: String): Boolean = samples["$profile/a-down"]?.let { it in ready } == true
    fun loadedSamples(profile: String) = samples.entries.count { it.key.startsWith("$profile/") && it.value in ready }
    private fun keyName(key: String): String = when (key) {
        "." -> "period"; "," -> "comma"
        else -> if (key.length == 1 && key[0].isLetterOrDigit()) key.lowercase() else key.ifEmpty { "a" }
    }
    private fun preset(profile:String,down:Boolean,strength:Int):Int {
        if(down && doubleClick && profile=="kailh_box_jade" && strength>20) return VibrationEffect.EFFECT_DOUBLE_CLICK
        val base=when {
            !down || profile in listOf("cherry_red","topre") -> 0
            profile in listOf("cherry_blue","holy_panda","kailh_box_jade") -> 2
            else -> 1
        }
        val adjustment=when { !down -> 0;strength<=20 -> -1;strength>=66 -> 1;else -> 0 }
        return when((base+adjustment).coerceIn(0,2)) {
            0 -> VibrationEffect.EFFECT_TICK
            1 -> VibrationEffect.EFFECT_CLICK
            else -> VibrationEffect.EFFECT_HEAVY_CLICK
        }
    }
    private fun primitiveIndex(primitive:Int)=when(primitive) {
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK -> 0
        VibrationEffect.Composition.PRIMITIVE_TICK -> 1
        else -> 2
    }
    @SuppressLint("WrongConstant")
    private fun effect(profile: String, down: Boolean, strength: Int): VibrationEffect {
        val identity = "$profile:$down:$strength"
        return effects.getOrPut(identity) {
            val pulses = Switches.pulses(profile, down)
            val gain = (strength / 50f).coerceIn(0f, 2f)
            if(Build.VERSION.SDK_INT>=31 && pulses.all { primitives[primitiveIndex(it.primitive)] }) {
                // The Galaxy A54's low tick is faint at the default scale.
                val weight=strength/100f
                val min=if(down) .4f+.26f*weight else .08f+.22f*weight
                val max=if(down) .7f+.3f*weight else .30f+.25f*weight
                val composition = VibrationEffect.startComposition()
                for(pulse in pulses) {
                    val scale=if(samsung) min+(max-min)*pulse.scale else (pulse.scale*gain).coerceAtMost(1f)
                    composition.addPrimitive(pulse.primitive,scale,pulse.delay)
                }
                return@getOrPut composition.compose()
            }
            if(Build.VERSION.SDK_INT>=29 && (nativeTaps || !amplitudeControl))
                return@getOrPut VibrationEffect.createPredefined(preset(profile,down,strength))
            if(!amplitudeControl) {
                val times=LongArray(pulses.size*2)
                for((i,pulse) in pulses.withIndex()) {
                    times[2*i]=if(i==0) 0 else maxOf(9,pulse.delay).toLong()
                    times[2*i+1]=(8+strength/12+(pulse.scale*8).roundToInt()).toLong()
                }
                return@getOrPut VibrationEffect.createWaveform(times,-1)
            }
            val times=LongArray(pulses.size*3)
            val levels=IntArray(times.size)
            for((i,pulse) in pulses.withIndex()) {
                val index=i*3
                val level=(pulse.scale*gain*255).roundToInt().coerceIn(1,255)
                times[index]=pulse.delay.toLong()
                times[index+1]=when(primitiveIndex(pulse.primitive)) { 0 -> 6;1 -> 8;else -> 10 }
                times[index+2]=5
                levels[index+1]=level
                levels[index+2]=level/3
            }
            VibrationEffect.createWaveform(times,levels,-1)
        }
    }
    private fun vibrate(effect:VibrationEffect) {
        if(Build.VERSION.SDK_INT>=33 && touchAttributes!=null) vibrator?.vibrate(effect,touchAttributes)
        else vibrator?.vibrate(effect)
    }
    fun voiceStarted() {
        val strength=prefs.vibration
        if(closed || strength==0) return
        val effect=if(Build.VERSION.SDK_INT>=29) VibrationEffect.createPredefined(when {
            strength<=20 -> VibrationEffect.EFFECT_TICK
            strength>=66 -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> VibrationEffect.EFFECT_CLICK
        }) else VibrationEffect.createOneShot(20,VibrationEffect.DEFAULT_AMPLITUDE)
        vibrate(effect)
    }
    fun tap(key: String = "a", down: Boolean = true, audible: Boolean = true): Int {
        if (closed) return 0
        val profile = prefs.switchId
        val strength=prefs.vibration
        if(strength>0 && (down || prefs.returnHaptics)) vibrate(effect(profile,down,strength))
        if (key == "hold" || !audible || !prefs.soundEnabled || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return 0
        val name = keyName(key)
        val config=Switches.profiles.firstOrNull { it.id==profile }
        val phase=if(down) "down" else "up"
        val count=if(down) config?.down?.size ?: 0 else config?.up?.size ?: 0
        if(down) lastTake=sequence++
        val variant=if(count>0) lastTake%count else 0
        val id = if(config?.perKey==false) samples["$profile/a-$phase${if(variant==0) "" else "-$variant"}"] else samples["$profile/$name-$phase"]
            ?: if (down) samples["$profile/a-down"] else null
        if (id != null && id in ready) {
            val volume = (prefs.volume / 100f * Switches.gain(profile) * if (down) 1f else prefs.returnVolume / 100f).coerceAtMost(1f)
            if (volume > 0) return sounds.play(id, volume, volume, 1, 0, 1f)
        }
        return 0
    }
    fun preview(profile:String=prefs.switchId) {
        prefs.switchId=profile
        main.removeCallbacksAndMessages(null)
        var attempts=0
        val start=object:Runnable {
            override fun run() {
                if(closed) return
                if(!isReady(profile) && attempts++<30) { main.postDelayed(this,20);return }
                listOf("a","s","d","space","enter").forEachIndexed { i,key ->
                    main.postDelayed({ tap(key,true) },i*165L)
                    main.postDelayed({ tap(key,false) },i*165L+65)
                }
            }
        }
        main.post(start)
    }
    @Synchronized fun close() { closed = true; main.removeCallbacksAndMessages(null); sounds.release() }
}
