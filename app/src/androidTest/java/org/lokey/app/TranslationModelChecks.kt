package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TranslationModelChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val directory=File(context.cacheDir,"translation-model-check").apply { deleteRecursively();mkdirs() }
        val sandbox=object:ContextWrapper(context) {
            override fun getFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("translation-model-check.$name",mode)
        }
        val prefs=Prefs(sandbox);prefs.model="qwen"
        val lfm=Models.text.first { it.id=="lfm" };val qwen=Models.text.first { it.id=="qwen" }
        check(Models.ready(context,lfm) && Models.ready(context,qwen)) { "Install both curated text models for this check" }
        fun install(model:Models.Model) { Os.symlink(Models.file(context,model).path,Models.file(sandbox,model).path) }
        val rows=JSONArray();lateinit var engine:Translation
        test.runOnMainSync { engine=Translation(sandbox) }
        fun translate(source:String,target:String,model:String,expected:String,cached:Boolean=false) {
            check(Models.selectedText(sandbox,prefs).id==model)
            val done=CountDownLatch(1);var answer:Translation.Result?=null;var busy=0
            val start=SystemClock.elapsedRealtime()
            test.runOnMainSync { engine.request(source,target) {
                if(it.busy) busy++ else { answer=it;done.countDown() }
            } }
            check(done.await(60,TimeUnit.SECONDS)) { "$model translation timed out" }
            val result=checkNotNull(answer)
            rows.put(JSONObject().put("model",model).put("source",source).put("target",target).put("text",result.text)
                .put("error",result.error ?: JSONObject.NULL).put("busy_updates",busy).put("ms",SystemClock.elapsedRealtime()-start))
            File(context.filesDir,"translation-model-details.json").writeText(rows.toString(2))
            check(result.error==null && Regex(expected,RegexOption.IGNORE_CASE).containsMatchIn(result.text)) { "$model: $result" }
            check(!result.text.contains("<think>") && !result.text.contains("<|im_"))
            check(if(cached) busy==0 else busy>0) { "Translation cache ignored model identity" }
        }
        try {
            check(!Models.ready(sandbox,Models.selectedText(sandbox,prefs)))
            install(qwen);prefs.model="lfm"
            check(Models.selectedText(sandbox,prefs).id=="qwen")
            Models.file(sandbox,qwen).delete();install(lfm);prefs.model="qwen"
            check(Models.selectedText(sandbox,prefs).id=="lfm")
            translate("Good morning.","French","lfm","bonjour|bonne matinée")
            translate("Good morning.","French","lfm","bonjour|bonne matinée",cached=true)
            install(qwen)
            translate("Good morning.","French","qwen","bonjour|bonne matinée")
            prefs.model="lfm"
            translate("Good morning.","French","lfm","bonjour|bonne matinée")
            translate("Good morning.","Spanish","lfm","buen(?:os)? d[ií]as?")
            translate("Thank you.","French","lfm","merci")
            return JSONObject().put("lfm_only_uses_lfm",true).put("qwen_only_uses_qwen",true)
                .put("shared_text_selection",true).put("manual_model_respected",true).put("cache_tracks_model",true).put("cases",rows)
        } finally {
            test.runOnMainSync { engine.close() }
            context.deleteSharedPreferences("translation-model-check.lokey")
            directory.deleteRecursively()
        }
    }
}
