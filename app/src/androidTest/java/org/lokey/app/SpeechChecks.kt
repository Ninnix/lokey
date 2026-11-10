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
        val result=JSONArray()
        try {
            val jfk=audio(File(context.filesDir,"jfk.wav"))
            val models=if(selected==null) Models.voices else if(selected=="selected") listOf(Models.selectedVoice(context,Prefs(context))) else Models.voices.filter { it.id==selected }
            check(models.isNotEmpty()) { "Unknown speech model: $selected" }
            for(model in models) {
                check(Models.ready(context,model)) { "Missing ${model.title}" }
                val encoder=model.parts.firstOrNull()?.let { Models.file(context,it).path } ?: ""
                val start=SystemClock.elapsedRealtime()
                check(Native.loadSpeech(Models.file(context,model).path,encoder,accelerator)) { "Load failed: ${model.title}" }
                val row=JSONObject().put("model",model.id).put("load_ms",SystemClock.elapsedRealtime()-start).put("backend",Native.speechBackend())
                val cases=JSONArray();row.put("cases",cases);result.put(row)
                for(pass in 0..1) {
                    val now=SystemClock.elapsedRealtime();val text=Native.transcribe(jfk)
                    cases.put(JSONObject().put("fixture","jfk").put("pass",pass).put("ms",SystemClock.elapsedRealtime()-now).put("text",text))
                    File(context.filesDir,"speech-details.json").writeText(result.toString(2))
                    check(text.contains("country",true) && text.contains("ask",true)) { "Unexpected ${model.id}: $text" }
                }
                check(Native.transcribe(FloatArray(16000)).isEmpty())
                if(model.id=="qwen_asr") {
                    for((name,expected) in listOf("asr-zh.wav" to "交易","asr-en.wav" to "")) {
                        val samples=audio(File(context.filesDir,name));val now=SystemClock.elapsedRealtime();val text=Native.transcribe(samples)
                        cases.put(JSONObject().put("fixture",name).put("ms",SystemClock.elapsedRealtime()-now).put("audio_seconds",samples.size/16000.0).put("text",text))
                        File(context.filesDir,"speech-details.json").writeText(result.toString(2))
                        check(text.isNotBlank() && !text.contains("<asr_text>") && !text.startsWith("language "))
                        if(expected.isNotEmpty()) check(expected in text && "停滞" in text) { "Unexpected Chinese: $text" }
                    }
                }
            }
        } finally { Native.release() }
        return JSONObject().put("models",result).put("silence",true).put("qwen_quantization","Q8_0")
    }
}
