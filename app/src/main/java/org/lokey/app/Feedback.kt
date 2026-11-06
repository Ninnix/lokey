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
    val usesSystemTaps = Build.VERSION.SDK_INT >= 30 && !primitives.all { it } && vibrator?.areEffectsSupported(
        VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK
    )?.all { it == Vibrator.VIBRATION_EFFECT_SUPPORT_YES } == true
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
    @SuppressLint("WrongConstant")
    private fun effect(profile: String, down: Boolean, strength: Int): VibrationEffect {
        val identity = "$profile:$down:$strength"
        return effects.getOrPut(identity) {
            val pulses = Switches.pulses(profile, down)
            val gain = (strength / 50f).coerceIn(0f, 2f)
            if (Build.VERSION.SDK_INT >= 30 && usesSystemTaps) {
                val base = when { !down -> 0; profile in listOf("cherry_red","topre") -> 0; profile in listOf("cherry_blue","holy_panda","kailh_box_jade") -> 2; else -> 1 }
                val adjustment = if (!down) 0 else if (strength <= 20) -1 else if (strength >= 66) 1 else 0
                val preset = when ((base + adjustment).coerceIn(0, 2)) {
                    0 -> VibrationEffect.EFFECT_TICK
                    1 -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
                return@getOrPut VibrationEffect.createPredefined(preset)
            }
            val supported = pulses.all { primitives[when (it.primitive) {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK -> 0
                VibrationEffect.Composition.PRIMITIVE_TICK -> 1
                else -> 2
            }] }
            if (Build.VERSION.SDK_INT >= 31 && supported) {
                val composition = VibrationEffect.startComposition()
                pulses.forEach { composition.addPrimitive(it.primitive, (it.scale * gain).coerceAtMost(1f), it.delay) }
                composition.compose()
            } else {
                if (!amplitudeControl && Build.VERSION.SDK_INT >= 29) {
                    VibrationEffect.createPredefined(if (down && profile == "cherry_blue") VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
                } else {
                    val times: LongArray
                    val levels: IntArray
                    if (!down) {
                        times = longArrayOf(0, 1, 2, 2, 2); levels = intArrayOf(0, 0, 60, 24, 0)
                    } else if (profile.endsWith("brown")) {
                        times = longArrayOf(0, 1, 2, 1, 2, 2, 2, 2)
                        levels = if (profile == "gateron_brown") intArrayOf(0, 0, 150, 50, 0, 70, 35, 0)
                            else intArrayOf(0, 0, 200, 65, 0, 105, 45, 0)
                    } else if (profile == "cherry_blue") {
                        times = longArrayOf(0, 1, 2, 1, 1, 2, 2, 2); levels = intArrayOf(0, 0, 250, 80, 0, 170, 50, 0)
                    } else {
                        times = longArrayOf(0, 1, 2, 2, 2, 2); levels = intArrayOf(0, 0, 45, 115, 55, 0)
                    }
                    // Returning to zero lets the motor brake instead of ringing after each tap.
                    VibrationEffect.createWaveform(times, levels.map { (it * gain).roundToInt().coerceIn(0, 255) }.toIntArray(), -1)
                }
            }
        }
    }
    fun tap(key: String = "a", down: Boolean = true, audible: Boolean = true): Int {
        if (closed) return 0
        val profile = prefs.switchId
        if (prefs.vibration > 0 && (down || prefs.returnHaptics)) {
            val effect = effect(profile, down, prefs.vibration)
            if (Build.VERSION.SDK_INT >= 33) vibrator?.vibrate(effect,
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
            else vibrator?.vibrate(effect)
        }
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
