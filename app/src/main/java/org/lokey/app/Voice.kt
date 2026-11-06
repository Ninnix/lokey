package org.lokey.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.sqrt

class Voice(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lokey-transcription") }
    @Volatile private var active = false
    @Volatile private var processing = false
    @Volatile private var generation = 0
    private fun selected()=Models.selectedVoice(context,Prefs(context))
    private fun load(model:Models.Model) {
        check(Models.ready(context,model)) { context.tr("Add %1\$s in Lokey → Models",model.title) }
        val file=Models.file(context,model)
        val encoder=model.parts.firstOrNull()?.let { Models.file(context,it) }
        val identity="${model.id}:${file.length()}:${file.lastModified()}:${encoder?.length()}:${encoder?.lastModified()}"
        if(identity!=loaded) {
            loaded=""
            check(Native.loadSpeech(file.path,encoder?.path ?: "")) { context.tr("Could not load %1\$s",model.title) }
            Acceleration.observed(Native.speechBackend())
            loaded=identity
        }
    }
    private var audio: AudioRecord? = null
    fun prepare() {
        val model=selected()
        worker.execute { synchronized(speechLock) { try { if(Models.ready(context,model)) load(model) } catch(_:Exception) { } } }
    }
    @SuppressLint("MissingPermission")
    fun start(level: (Float, Long) -> Unit, complete: (String?, String?) -> Unit) {
        if (active) return
        active = true
        val ticket = ++generation
        val model=selected()
        Thread({
            var recorder: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0) { context.tr("Microphone format unavailable") }
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(8192, min))
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { context.tr("Cannot open microphone") }
                audio = recorder
                val samples = FloatArray(16000 * 60)
                val buffer = ShortArray(800)
                var count = 0
                if (active && ticket == generation) recorder.startRecording()
                while (active && ticket == generation && count < samples.size - buffer.size) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    check(n >= 0 || !active) { context.tr("Microphone interrupted") }
                    if (n <= 0) break
                    var energy = 0f
                    for (i in 0 until n) { val sample = buffer[i] / 32768f; samples[count++] = sample; energy += sample * sample }
                    val amplitude = sqrt(energy / n).coerceIn(0f, 1f)
                    val millis = count * 1000L / 16000
                    main.post { if (ticket == generation) level(amplitude, millis) }
                }
                if (ticket != generation) return@Thread
                active = false
                if (count < 1600) { main.post { complete(null, context.tr("Hold a little longer and speak")) }; return@Thread }
                worker.execute {
                    try {
                        val result=synchronized(speechLock) {
                            if(ticket!=generation) return@execute
                            processing=true;load(model)
                            if(ticket!=generation) return@execute
                            Native.transcribe(samples.copyOf(count)).trim()
                        }
                        main.post { if (ticket == generation) complete(result, null) }
                    } catch (error: Exception) { main.post { if (ticket == generation) complete(null, error.message ?: context.tr("Transcription failed")) } }
                    finally { processing=false }
                }
            } catch (error: Exception) {
                active = false
                main.post { if (ticket == generation) complete(null, error.message ?: context.tr("Recording failed")) }
            } finally {
                try { recorder?.stop() } catch (_: IllegalStateException) { }
                recorder?.release(); audio = null
            }
        }, "lokey-microphone").start()
    }
    fun stop() { active = false; try { audio?.stop() } catch (_: IllegalStateException) { } }
    fun cancel() { generation++; stop(); if(processing) Native.cancelSpeech() }
    fun close() { cancel(); worker.shutdown() }
    companion object {
        private val speechLock=Any()
        private var loaded=""
    }
}
