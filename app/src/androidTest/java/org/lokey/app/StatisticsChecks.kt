package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object StatisticsChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        check(context.packageName=="org.lokey.app.features")
        val name="typing_statistics_fixture"
        context.deleteSharedPreferences(name)
        val fixture=TypingStats(context,name)
        try {
            for(ms in 1..67) fixture.sample("lfm",ms*1_000_000L)
            fixture.sample("qwen",25_000_000L);fixture.suggestion(4);fixture.correction();fixture.dictation()
            fixture.speechSample("parakeet",32_000,800_000_000L);fixture.speechSample("parakeet",16_000,400_000_000L)
            fixture.flush()
            val saved=TypingStats(context,name).snapshot()
            check(saved.suggestions==1L && saved.characters==4L && saved.corrections==1L && saved.dictations==1L)
            check(saved.models["lfm"]==TypingStats.Timing(64,35.5,64.0) && saved.models["qwen"]==TypingStats.Timing(1,25.0,25.0))
            check(saved.models["parakeet"]==TypingStats.Timing(2,400.0,400.0))
        } finally { context.deleteSharedPreferences(name) }

        val prefs=Prefs(context);val stats=TypingStats.shared(context);val before=stats.snapshot()
        val layout=prefs.layoutSelection;val shortcuts=prefs.quickLayouts;val language=prefs.setupLanguage
        val correction=prefs.autocorrect;val modelId=prefs.model
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        fun start(page:String)=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("page",page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun find(view:View,label:String):TextView? {
            if(view is TextView && view.text.toString()==label) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
            return null
        }
        fun checkButtonIcons(root:View,vararg labels:String) {
            for(label in labels) {
                val button=find(root,context.tr(label)) ?: error("Missing $label button")
                check(button.isClickable && button.compoundDrawablesRelative[0] is FeelIcon) { "Missing icon on $label" }
            }
        }
        fun await(label:String,condition:()->Boolean) {
            val end=SystemClock.uptimeMillis()+15_000
            while(SystemClock.uptimeMillis()<end) { if(condition()) return;SystemClock.sleep(30) }
            error("Timed out waiting for $label")
        }
        fun node(matches:(String)->Boolean):AccessibilityNodeInfo? {
            if(android.os.Build.VERSION.SDK_INT>=34) test.uiAutomation.clearCache()
            fun walk(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                if(node.packageName==context.packageName && matches(node.contentDescription?.toString().orEmpty())) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }?.let { return it }
                return null
            }
            for(window in test.uiAutomation.windows) if(window.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { walk(it) }?.let { return it }
            return null
        }
        fun type(text:String) {
            for(c in text) {
                val name=if(c==' ') "Space" else c.toString()
                val key=node { it.substringBefore(',').equals(name,true) } ?: error("Missing key $name")
                check(key.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
        }
        val links=ArrayList<File>()
        val fixtures=File(context.getExternalFilesDir(null),"statistics-fixtures")
        fun stage(model:Models.Model) {
            for(part in listOf(model)+model.parts) {
                val installed=Models.file(context,part)
                val source=File(fixtures,part.filename)
                if(!installed.exists() && source.isFile) { android.system.Os.symlink(source.path,installed.path);links.add(installed) }
            }
        }
        var activity:android.app.Activity?=null
        try {
            prefs.layout="qwerty";prefs.setupLanguage="English";prefs.autocorrect=true
            val typing=start("lab").also { activity=it }
            await("editor window") { typing.hasWindowFocus() }
            fun editor(private:Boolean=false):EditText {
                lateinit var editor:EditText
                val manager=typing.getSystemService(InputMethodManager::class.java)
                test.runOnMainSync {
                    editor=EditText(typing).apply {
                        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                        if(private) imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    }
                    typing.setContentView(editor);editor.requestFocus();manager.restartInput(editor);manager.showSoftInput(editor,0)
                }
                await("keyboard") {
                    test.runOnMainSync { manager.showSoftInput(editor,0) }
                    node { it.startsWith("Space,") }!=null
                }
                SystemClock.sleep(150)
                return editor
            }
            fun value(editor:EditText):String {
                var text="";test.runOnMainSync { text=editor.text.toString() };return text
            }
            val first=editor()
            val prefix="tomor"
            type(prefix)
            fun completion()=node { it.startsWith("Suggestion: $prefix",true) && it.length>"Suggestion: $prefix".length }
            await("longer suggestion") { completion()!=null }
            val suggestion=completion()!!;val word=suggestion.contentDescription.toString().removePrefix("Suggestion: ")
            check(suggestion.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("accepted suggestion") { value(first)=="$word " && stats.snapshot().suggestions==before.suggestions+1 }
            check(stats.snapshot().characters==before.characters+word.codePointCount(prefix.length,word.length))
            val second=editor();type("teh ")
            await("applied autocorrection") { value(second)=="the " && stats.snapshot().corrections==before.corrections+1 }
            val normal=stats.snapshot()
            val secret=editor(private=true);type("teh ")
            await("private text") { value(secret)=="teh " }
            check(stats.snapshot()==normal)
            test.runOnMainSync { typing.finish() };activity=null

            val timed=JSONObject()
            for(model in Models.text) {
                stage(model)
                if(!Models.ready(context,model)) continue
                prefs.model=model.id
                val done=CountDownLatch(1)
                val engine=Prediction(context,prefs)
                try {
                    test.runOnMainSync { engine.request("See you ",trackTiming=true) { if(it.neural && it.words.isNotEmpty()) done.countDown() } }
                    check(done.await(45,TimeUnit.SECONDS)) { "No autocomplete result for ${model.id}" }
                    val timing=stats.snapshot().models.getValue(model.id)
                    check(timing.count>0 && timing.medianMs>0)
                    timed.put(model.id,JSONObject().put("samples",timing.count).put("median_ms",timing.medianMs))
                } finally { engine.close() }
            }
            val speech=JSONObject()
            val wav=File(fixtures,"jfk.wav")
            if(wav.isFile) {
                val audio=SpeechChecks.audio(wav)
                for(model in Models.voices) {
                    stage(model)
                    if(!Models.ready(context,model)) continue
                    val encoder=model.parts.firstOrNull()?.let { Models.file(context,it).path }.orEmpty()
                    check(Native.loadSpeech(Models.file(context,model).path,encoder)) { "Could not load ${model.id}" }
                    val transcript=Voice.transcribe(audio) { stats.speechSample(model.id,audio.size,it) }
                    check(transcript.contains("country",true) && transcript.contains("ask",true)) { "Voice fixture failed for ${model.id}" }
                    val timing=stats.snapshot().models.getValue(model.id)
                    check(timing.count>0 && timing.medianMs>0)
                    check(Voice.transcribe(FloatArray(16_000)) { error("Silence recorded a speech sample") }.isEmpty())
                    speech.put(model.id,JSONObject().put("samples",timing.count).put("median_ms_per_audio_second",timing.medianMs))
                }
            }
            val feel=start("feel").also { activity=it }
            await("Feel window") { feel.hasWindowFocus() }
            test.runOnMainSync { checkButtonIcons(feel.window.decorView,"Input & design credits","Erase learned data") }
            val models=start("models").also { activity=it }
            await("Models window") { models.hasWindowFocus() }
            test.runOnMainSync {
                val root={ models.window.decorView }
                checkButtonIcons(root(),"Supported languages","Statistics for nerds","Allow microphone","Models & inference credits","Privacy policy")
                find(root(),context.tr("Statistics for nerds"))!!.performClick()
                for(label in listOf("Suggestions accepted","Characters saved","Autocorrections applied","Dictations inserted")) {
                    val row=find(root(),context.tr(label))!!.parent as ViewGroup
                    check((row.getChildAt(0) as android.widget.ImageView).drawable is FeelIcon)
                }
                for(label in listOf("Autocomplete speed","Dictation speed")) check(find(root(),context.tr(label))!=null)
                for(model in Models.text+Models.voices) {
                    val title=find(root(),model.title)
                    check((title!=null)==(model.id in stats.snapshot().models))
                    if(title!=null) {
                        val logo=(title.parent as ViewGroup).getChildAt(0) as android.widget.ImageView
                        check(logo.drawable!=null && logo.importantForAccessibility==View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                    }
                }
            }
            test.waitForIdleSync()
            test.runOnMainSync {
                val view=models.window.decorView
                fun capture(name:String) {
                    val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image))
                    File(context.filesDir,name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                }
                capture("typing-statistics.png")
                val body=models.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                val scroll=(0 until body.childCount).map(body::getChildAt).filterIsInstance<android.widget.ScrollView>().single()
                scroll.scrollTo(0,scroll.getChildAt(0).height)
                capture("dictation-statistics.png")
            }
            return JSONObject().put("persisted_bounded_timings",true).put("accepted_suggestion_and_saved_characters",true)
                .put("committed_autocorrection",true).put("private_field_excluded",true).put("metric_icons_and_model_logos",true).put("button_icons",7)
                .put("models",timed).put("speech_models",speech)
        } finally {
            activity?.let { current -> test.runOnMainSync { current.finish() } }
            Native.release();links.forEach { it.delete() }
            prefs.layout=layout;prefs.quickLayouts=shortcuts;prefs.setupLanguage=language;prefs.autocorrect=correction;prefs.model=modelId
            stats.flush()
        }
    }
}
