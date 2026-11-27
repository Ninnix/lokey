package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SpeechChecks {
    fun corpus(context:Context,accelerator:Int):JSONObject {
        val root=File(context.filesDir,"speech-corpus")
        val fixtures=JSONArray(File(root,"fixtures.json").readText())
        val recordings=(0 until fixtures.length()).map { index ->
            val fixture=fixtures.getJSONObject(index)
            val name=fixture.optString("language","Polish")
            val language=ModelLanguages.all(context).first { it.name==name }
            fixture.getString("file") to language
        }
        val cases=JSONArray()
        try {
            for(model in Models.voices) {
                val supported=recordings.filter { (_,language) -> if(model.id=="parakeet") language.parakeet else language.qwenVoice }
                if(supported.isEmpty()) continue
                check(Models.ready(context,model))
                val encoder=model.parts.firstOrNull()?.let { Models.file(context,it).path }.orEmpty()
                check(Native.loadSpeech(Models.file(context,model).path,encoder,accelerator))
                val backend=Native.speechBackend()
                for((name,language) in supported) {
                    val samples=audio(File(root,name))
                    val hints=if(model.id=="qwen_asr") listOf("",language.name) else listOf("")
                    for(hint in hints) {
                        val start=SystemClock.elapsedRealtimeNanos()
                        val text=Native.transcribe(samples,hint)
                        cases.put(JSONObject().put("file",name).put("language",language.name).put("model",model.id).put("backend",backend)
                            .put("hint",hint).put("text",text).put("seconds",samples.size/16000.0)
                            .put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6))
                    }
                    File(root,"results.json").writeText(cases.toString(2))
                }
            }
        } finally { Native.release() }
        return JSONObject().put("recordings",fixtures.length()).put("decodes",cases.length())
    }

    fun audio(file:File):FloatArray {
        val data=file.readBytes();val bytes=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        check(String(data,0,4)=="RIFF" && String(data,8,4)=="WAVE")
        var position=12;var valid=false
        while(position+8<=data.size) {
            val kind=String(data,position,4);val size=bytes.getInt(position+4)
            check(size>=0 && position+8L+size<=data.size)
            if(kind=="fmt ") valid=bytes.getShort(position+8).toInt()==1 && bytes.getShort(position+10).toInt()==1 && bytes.getInt(position+12)==16000 && bytes.getShort(position+22).toInt()==16
            if(kind=="data") { check(valid);return FloatArray(size/2) { bytes.getShort(position+8+it*2)/32768f } }
            position+=8+size+(size and 1)
        }
        error("Missing WAV samples")
    }
    fun run(test:Instrumentation,context:Context,selected:String?=null,accelerator:Int=0):JSONObject {
        check(Voice.insertion("你好",'好',KeyboardLayouts.find("pinyin").wordSpaces)=="你好")
        check(Voice.insertion("hello",'d',KeyboardLayouts.find("qwerty").wordSpaces)==" hello ")
        val result=JSONArray()
        try {
            val source=File(context.filesDir,"qwen-source.gguf")
            if(source.exists()) Models.installQwen(context,source)
            val jfk=audio(File(context.filesDir,"jfk.wav"))
            val models=when(selected) {
                null -> Models.voices
                "selected" -> listOf(Models.selectedVoice(context,Prefs(context)))
                "qwen_asr_q8" -> listOf(Models.qwenAsr.copy(filename="Qwen3-ASR-0.6B-Q8_0.gguf",parts=listOf(Models.qwenEncoder.copy(filename="mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"))))
                else -> Models.voices.filter { it.id==selected }
            }
            check(models.isNotEmpty()) { "Unknown speech model: $selected" }
            for(model in models) {
                check(Models.ready(context,model)) { "Missing ${model.title}" }
                val encoder=model.parts.firstOrNull()?.let { Models.file(context,it).path } ?: ""
                val start=SystemClock.elapsedRealtime()
                check(Native.loadSpeech(Models.file(context,model).path,encoder,accelerator)) { "Load failed: ${model.title}" }
                val row=JSONObject().put("model",model.id).put("load_ms",SystemClock.elapsedRealtime()-start).put("backend",Native.speechBackend())
                    .put("filename",model.filename)
                    .put("model_bytes",(listOf(model)+model.parts).sumOf { Models.file(context,it).length() })
                val cases=JSONArray();row.put("cases",cases);result.put(row)
                for(pass in 0..1) {
                    val now=SystemClock.elapsedRealtime();val text=Native.transcribe(jfk)
                    cases.put(JSONObject().put("fixture","jfk").put("pass",pass).put("ms",SystemClock.elapsedRealtime()-now).put("text",text))
                    File(context.filesDir,"speech-details.json").writeText(result.toString(2))
                    check(text.contains("country",true) && text.contains("ask",true) && !text.startsWith("language ") && !text.contains("<asr_text>")) { "Unexpected ${model.id}: $text" }
                }
                check(Native.transcribe(FloatArray(16000)).isEmpty())
                row.put("pss_kb",android.os.Debug.getPss())
                if(model.id=="qwen_asr") {
                    for((name,expected) in listOf("asr-zh.wav" to listOf("交易","停滞"),"asr-en.wav" to listOf("solo music","writing","people"))) {
                        val samples=audio(File(context.filesDir,name));val now=SystemClock.elapsedRealtime();val text=Native.transcribe(samples)
                        cases.put(JSONObject().put("fixture",name).put("ms",SystemClock.elapsedRealtime()-now).put("audio_seconds",samples.size/16000.0).put("text",text))
                        File(context.filesDir,"speech-details.json").writeText(result.toString(2))
                        check(text.isNotBlank() && !text.contains("<asr_text>") && !text.startsWith("language ") && expected.all { text.contains(it,true) }) { "Unexpected $name: $text" }
                    }
                    val hindi=File(context.filesDir,"asr-hi-utf8.wav")
                    if(hindi.isFile) {
                        val text=Native.transcribe(audio(hindi),"Hindi")
                        check("चॉकलेट" in text && "जूस" in text && '\ufffd' !in text) { "Truncated Hindi transcription: $text" }
                        row.put("hindi_utf8",true)
                    }
                }
            }
        } finally { Native.release() }
        return JSONObject().put("models",result).put("silence",true).put("qwen_quantization",if(selected=="qwen_asr_q8") "Q8_0" else "Q4_K")
    }
}
