package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object AutomaticChecks {
    fun run(test:Instrumentation,context:Context,ui:Boolean=true):JSONObject {
        val npu=Acceleration.await()
        val languages=ModelLanguages.all(context)
        val cases=JSONArray()
        val exceptions=setOf("bg","de","en","es","fr","hr","et","lv","lt","mt","pl","pt","ru","sk","sl","uk")
        check(languages.size==39)
        for(language in languages) {
            val cpu=Models.recommended(language,false);val accelerated=Models.recommended(language,true)
            val expected=if(language.base in exceptions) "parakeet" else "qwen_asr"
            check(cpu.first==if(language.lfm && language.base!="zh") "lfm" else "qwen")
            check(cpu.second==if(language.parakeet) "parakeet" else "qwen_asr")
            check(accelerated==("qwen" to expected)) { "${language.name}: $accelerated" }
            check(if(expected=="parakeet") language.parakeet else language.qwenVoice)
            cases.put(JSONObject().put("language",language.name).put("cpu_text",cpu.first).put("cpu_speech",cpu.second)
                .put("npu_text",accelerated.first).put("npu_speech",accelerated.second))
        }
        val prefs=Prefs(context);val model=prefs.model;val speech=prefs.speechModel;val language=prefs.setupLanguage;val layout=prefs.layoutSelection
        val shortcuts=prefs.quickLayouts
        fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
        fun await(check:()->Boolean) {
            val until=SystemClock.uptimeMillis()+8000
            while(SystemClock.uptimeMillis()<until) { if(check()) return;SystemClock.sleep(50) }
            error("Automatic model cards did not update")
        }
        try {
            prefs.model="auto"
            prefs.setupLanguage="English"
            prefs.layout="pinyin"
            val mandarin=ModelLanguages.dictationLanguage(context,prefs)
            check(mandarin.base=="zh" && ModelLanguages.dictationHint(mandarin,true)=="Chinese" && ModelLanguages.dictationHint(mandarin,false).isEmpty())
            prefs.layout="hindi_phonetic"
            val hindi=ModelLanguages.dictationLanguage(context,prefs)
            check(hindi.base=="hi" && ModelLanguages.dictationHint(hindi,false)=="Hindi")
            prefs.layout="spanish"
            val spanish=ModelLanguages.dictationLanguage(context,prefs)
            check(spanish.base=="es" && ModelLanguages.dictationHint(spanish,true)=="Spanish")
            check(Models.selectedVoice(context,prefs,spanish).id=="parakeet")
            prefs.layout="arabic"
            val arabic=ModelLanguages.dictationLanguage(context,prefs)
            check(arabic.base=="ar" && ModelLanguages.dictationHint(arabic,true)=="Arabic" && ModelLanguages.dictationHint(arabic,false).isEmpty())
            prefs.setupLanguage="Indonesian";prefs.layout="intl"
            val indonesian=ModelLanguages.dictationLanguage(context,prefs)
            check(indonesian.base=="id" && ModelLanguages.dictationHint(indonesian,true).isEmpty())
            prefs.setupLanguage="English"
            for((id,code) in listOf("azerty" to "fr","portuguese" to "pt")) {
                prefs.layout=id
                val active=ModelLanguages.dictationLanguage(context,prefs)
                check(active.base==code && Models.selectedVoice(context,prefs,active).id=="parakeet")
            }
            prefs.modelPair("lfm","qwen_asr")
            check(Models.selectedVoice(context,prefs,spanish).id=="qwen_asr")
            prefs.model="auto"
            prefs.layout="auto"
            for(code in listOf("en","es","fr","pt","it","pl","zh","bg","uk","mt","ar","de","id","ru")) {
                val selected=ModelLanguages.forLocale(context,Locale.forLanguageTag(code));prefs.setupLanguage=selected.name
                val pair=Models.recommended(selected,npu)
                for(id in listOfNotNull(pair.first,pair.second)) {
                    val entry=(Models.text+Models.voices).first { it.id==id }
                    check(Models.ready(context,entry)) { "Install $id before Automatic device checks" }
                }
                check(Models.selectedText(context,prefs).id==pair.first && Models.selectedVoice(context,prefs).id==pair.second)
                if(!ui) continue
                val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","models")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                fun buttons():List<TextView> = views(activity.window.decorView).filterIsInstance<TextView>().filter { it.contentDescription?.startsWith("Model action: ")==true }
                try {
                    await {
                        var ready=false
                        test.runOnMainSync {
                            val chosen=buttons().filter { it.isSelected }
                            ready=activity.hasWindowFocus() && chosen.size==2 && chosen.all { it.text.toString()=="Selected automatically" }
                        };ready
                    }
                    test.runOnMainSync {
                        val chosen=buttons().filter { it.isSelected }
                        check(chosen.map { it.contentDescription.toString().substringAfter(": ") }.toSet()==setOf(pair.first,pair.second))
                        chosen.forEach { it.performClick() }
                        check(prefs.model=="auto")
                    }
                    if(code in listOf("en","bg")) {
                        SystemClock.sleep(300)
                        val bitmap=test.uiAutomation.takeScreenshot() ?: error("Cannot capture Automatic")
                        File(context.filesDir,"automatic-$code.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                    }
                    if(code=="en") {
                        val other=if(pair.first=="qwen") "lfm" else "qwen"
                        test.runOnMainSync { buttons().first { it.contentDescription=="Model action: $other" }.performClick() }
                        check(prefs.model==other && prefs.speechModel==pair.second)
                        test.runOnMainSync { views(activity.window.decorView).first { it.contentDescription=="Select automatic model" }.performClick() }
                        check(prefs.model=="auto")
                    }
                } finally { test.runOnMainSync { activity.finish() } }
            }
            prefs.setupLanguage=""
            check(ModelLanguages.selected(context,prefs)==ModelLanguages.forLocale(context,KeyboardLayouts.deviceLocale()))
            val report=JSONObject().put("npu_available",npu).put("languages",cases).put("configured_language_controls_both_models",true)
                .put("dictation_follows_active_layout",true).put("device_language_fallback",true)
            if(ui) report.put("selected_cards_preserve_automatic",true).put("manual_selection_preserves_other_model",true)
            return report
        } finally {
            prefs.modelPair(model,speech);prefs.setupLanguage=language;prefs.layout=layout;prefs.quickLayouts=shortcuts
        }
    }
}
