package org.lokey.app

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

object Models {
    data class Model(val id: String, val title: String, val detail: String, val filename: String, val url: String,val parts:List<Model> = emptyList())
    val text = listOf(
        Model("lfm", "LFM 2.5", "350M · compact prediction and translation", "LFM2.5-350M-Q4_K_M.gguf", "https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf"),
        Model("qwen", "Qwen 3.5", "0.8B · text prediction and translation", "Qwen3.5-0.8B-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf")
    )
    val speech = Model("parakeet", "Parakeet v3", "0.6B · on-device dictation", "parakeet-tdt-0.6b-v3-q4_k.gguf", "https://huggingface.co/cstr/parakeet-tdt-0.6b-v3-GGUF/resolve/dff69b0e675921ffd5a6133f5fbe2aac690d3ec3/parakeet-tdt-0.6b-v3-q4_k.gguf")
    val qwenEncoder=Model("qwen_asr_encoder","Qwen audio encoder","required for Qwen ASR","mmproj-Qwen3-ASR-0.6B-Q8_0.gguf","https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf")
    val qwenAsr=Model("qwen_asr","Qwen3 ASR","0.6B · 30 languages, including Chinese and Arabic","Qwen3-ASR-0.6B-Q8_0.gguf","https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/Qwen3-ASR-0.6B-Q8_0.gguf",listOf(qwenEncoder))
    val voices=listOf(speech,qwenAsr)
    fun downloadSize(model:Model)=when(model.id) { "lfm" -> "~230 MB";"qwen" -> "~533 MB";"parakeet" -> "~418 MB";"qwen_asr" -> "~1.02 GB";else -> "" }
    fun voice(id:String)=voices.firstOrNull { it.id==id } ?: speech
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lokey-models") }
    fun downloadState(context:Context,model:Model)=Downloads.state(context,"model:${model.id}")
    fun downloadStatus(context:Context,model:Model)=downloadState(context,model)?.message.orEmpty()
    fun downloading(context:Context,model:Model)=downloadState(context,model)?.active==true
    fun recommended(language:ModelLanguages.Language,npu:Boolean=Acceleration.npu)=
        (if(npu) "qwen" else language.textModel) to (if(npu && language.qwenVoice) "qwen_asr" else language.voiceModel)
    fun predictor(context: Context, selected: String, language: String): Model {
        if (selected != "auto") return text.firstOrNull { it.id == selected } ?: text.first()
        val id=if(Acceleration.npu) "qwen" else ModelLanguages.recommendedText(language)
        val preferred=text.first { it.id==id }
        return if(ready(context,preferred)) preferred else text.firstOrNull {
            (it.id=="qwen" || language.substringBefore('-') in ModelLanguages.lfmCodes) && ready(context,it)
        } ?: preferred
    }
    fun selectedText(context:Context,prefs:Prefs):Model {
        val preferred=predictor(context,prefs.model,ModelLanguages.selected(context,prefs).code)
        return preferred.takeIf { ready(context,it) } ?: text.firstOrNull { ready(context,it) } ?: preferred
    }
    fun selectedVoice(context:Context,prefs:Prefs):Model {
        if(prefs.model!="auto") return voice(prefs.speechModel)
        val language=ModelLanguages.selected(context,prefs)
        val preferred=voice(recommended(language).second ?: speech.id)
        return if(ready(context,preferred)) preferred else voices.firstOrNull {
            (if(it.id=="parakeet") language.parakeet else language.qwenVoice) && ready(context,it)
        } ?: preferred
    }
    fun select(context:Context,prefs:Prefs,id:String,voice:Boolean) {
        val text=selectedText(context,prefs).id;val speech=selectedVoice(context,prefs).id
        prefs.modelPair(if(voice) text else id,if(voice) id else speech)
    }
    fun directory(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context, model: Model) = File(directory(context), model.filename)
    fun ready(context: Context, model: Model) = (listOf(model)+model.parts).all { file(context,it).let { file -> file.exists() && file.length()>1024 } }
    fun download(context: Context, model: Model, progress: (String) -> Unit) {
        if(ready(context,model)) { progress("Ready");return }
        progress("Queued")
        Downloads.start(context,"model:${model.id}") { error -> progress(error ?: "Ready") }
    }
    fun validate(file: File) {
        val magic = ByteArray(4)
        file.inputStream().use { check(it.read(magic) == 4 && String(magic) == "GGUF") { "Choose a GGUF model file" } }
    }
}
