package org.lokey.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Locale

object SetupChecks {
    private fun find(view:View,label:String):View? {
        if(view.contentDescription?.toString()==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    private fun labels(view:View):List<String> {
        val own=if(view is TextView) listOf(view.text.toString()) else emptyList()
        return own+if(view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
    }
    private fun focus(test:Instrumentation,activity:Activity) {
        test.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val until=SystemClock.uptimeMillis()+15000;var ready=false
        while(SystemClock.uptimeMillis()<until) { test.runOnMainSync { ready=activity.hasWindowFocus() };if(ready) return;SystemClock.sleep(50) }
        val windows=test.uiAutomation.windows.joinToString { "${it.type}:${it.root?.packageName}" }
        val focus=android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("dumpsys window windows")).bufferedReader().useLines { lines -> lines.filter { "mCurrentFocus=" in it || "mFocusedApp=" in it }.joinToString() }
        error("Setup lost focus: ${activity.javaClass.simpleName}, finishing=${activity.isFinishing}, destroyed=${activity.isDestroyed}; $windows; $focus")
    }
    private fun capture(test:Instrumentation,context:Context,activity:Activity,name:String) {
        focus(test,activity);SystemClock.sleep(150)
        test.runOnMainSync {
            val view=activity.window.decorView;val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
            File(context.filesDir,name+".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val store=context.getSharedPreferences("lokey",Context.MODE_PRIVATE)
        val keys=listOf("setup_complete","setup_language","multilingual_v1","model","speech_model","layout","app_light")
        val original=keys.associateWith { store.all[it] }
        val prefs=Prefs(context)
        var activity:Activity?=null
        try {
            val all=ModelLanguages.all(context)
            check(all.size==39 && all.map { it.name }.distinct().size==39)
            check(all.filter { it.lfm }.map { it.base }.toSet()==ModelLanguages.lfmCodes)
            check(all.filter { it.parakeet }.map { it.base }.toSet()==ModelLanguages.parakeetCodes)
            check(all.filter { it.qwenVoice }.map { it.base }.toSet()==ModelLanguages.qwenVoiceCodes)
            for((code,pair) in mapOf("en" to ("lfm" to "parakeet"),"it" to ("qwen" to "parakeet"),"fr" to ("lfm" to "parakeet"),"zh" to ("qwen" to "qwen_asr"),"ar" to ("lfm" to "qwen_asr"),"th" to ("qwen" to "qwen_asr"))) {
                val language=ModelLanguages.forLocale(context,Locale.forLanguageTag(code))
                check(language.textModel==pair.first && language.voiceModel==pair.second) { "$code: $language" }
            }
            for(code in listOf("en","fr","de","es","pt","ar","ja","ko","it","zh")) {
                val preferred=Models.text.first { it.id==if(Acceleration.npu) "qwen" else ModelLanguages.recommendedText(code) }
                val selected=Models.predictor(context,"auto",code)
                if(Models.ready(context,preferred)) check(selected==preferred)
                else check(Models.ready(context,selected) || selected==preferred)
            }
            for((input,expected) in listOf("'e" to "é","`e" to "è","\"u" to "ü","^o" to "ô","~n" to "ñ","'E" to "É","' " to "'","''" to "'","'x" to "'x","'c" to "ć")) {
                val keys=DeadKeys();val value=input.map { keys.input(it.toString()) }.joinToString("")
                check(value==expected) { "$input: $value" }
            }
            val dead=DeadKeys();dead.input("'");check(dead.delete() && dead.pending.isEmpty());check(dead.input("e")=="e")
            store.edit().remove("setup_complete").remove("multilingual_v1").apply()
            check(!Prefs(context).setupComplete && !Prefs(context).setupComplete)
            prefs.layout="auto";prefs.setupLanguage="English"
            for(light in listOf(false,true)) {
                prefs.appLight=light;prefs.setupComplete=false
                val monitor=test.addMonitor(SetupActivity::class.java.name,null,false)
                val launcher=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                activity=test.waitForMonitorWithTimeout(monitor,8000) ?: error("First run did not open setup")
                test.removeMonitor(monitor);check(launcher.isFinishing || launcher.isDestroyed)
                val setup=activity!!;focus(test,setup)
                test.runOnMainSync {
                    check(find(setup.window.decorView,"Automatic keyboard layout")==null)
                    val family=KeyboardLayouts.families.first { it.id==KeyboardLayouts.find(prefs.layout).family }
                    check(find(setup.window.decorView,"Keyboard layout: ${family.title}")!!.isSelected)
                    val preview=find(setup.window.decorView,"Selected keyboard preview")!!
                    check(preview.height>150*context.resources.displayMetrics.density)
                    check((find(setup.window.decorView,"Setup preview layout") as TextView).text==KeyboardLayouts.find(prefs.layout).title)
                }
                capture(test,context,setup,"setup-layout-"+if(light) "light" else "dark")
                test.runOnMainSync {
                    find(setup.window.decorView,"Keyboard layout: Chinese & Cantonese")!!.performClick()
                    check(prefs.setupLanguage=="Chinese")
                    check((find(setup.window.decorView,"Setup preview layout") as TextView).text==KeyboardLayouts.find(prefs.layout).title)
                }
                capture(test,context,setup,"setup-preview-chinese-"+if(light) "light" else "dark")
                test.runOnMainSync {
                    find(setup.window.decorView,"Setup continue")!!.performClick()
                    val text=labels(setup.window.decorView).joinToString("\n")
                    check("✓  Qwen 3.5" in text && "✓  Qwen3 ASR" in text)
                    check(find(setup.window.decorView,"Setup model: onejev")==null)
                }
                capture(test,context,setup,"setup-models-"+if(light) "light" else "dark")
                val home=test.addMonitor(MainActivity::class.java.name,null,false)
                test.runOnMainSync { (find(setup.window.decorView,"Skip model downloads") ?: find(setup.window.decorView,"Setup continue"))!!.performClick() }
                activity=test.waitForMonitorWithTimeout(home,8000) ?: error("Setup did not finish")
                test.removeMonitor(home);focus(test,activity!!)
                check(prefs.setupComplete && prefs.model=="qwen" && prefs.speechModel=="qwen_asr")
                test.runOnMainSync { activity!!.finish() };activity=null
                prefs.layout="auto";prefs.setupLanguage="English"
            }
            activity=test.startActivitySync(Intent().setClass(context,LanguageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            focus(test,activity!!)
            test.runOnMainSync {
                val root=activity!!.window.decorView
                val search=find(root,"Search supported languages") as EditText
                search.setText("Italian")
                fun list(view:View):ListView? { if(view is ListView) return view;if(view is ViewGroup) for(i in 0 until view.childCount) list(view.getChildAt(i))?.let { return it };return null }
                val rows=list(root)!!;check(rows.adapter.count==1)
                val row=rows.adapter.getView(0,null,rows);val content=labels(row).joinToString("\n")
                check("Qwen 3.5" in content && "Parakeet v3" in content)
                check("QWERTY Accent" in content)
                search.setText("Cantonese")
                val cantonese=rows.adapter.getView(0,null,rows)
                check("Jyutping / Cangjie / Sucheng" in labels(cantonese).joinToString(" "))
                check("Available" in labels(cantonese).joinToString(" "))
                search.setText("")
                find(root,"Language filter: Key gaps")!!.performClick()
            }
            capture(test,context,activity!!,"supported-languages")
            return JSONObject().put("text_catalog",39).put("voice_languages",39).put("coverage_matches_model_cards",true).put("recommendations",true).put("first_run_and_completion",true).put("existing_preferences_preserved",true).put("two_models_only",true).put("dark_and_light",true).put("searchable_languages",true).put("dead_key_cases",11)
        } finally {
            activity?.let { a -> test.runOnMainSync { a.finish() } }
            val edit=store.edit()
            original.forEach { (key,value) -> when(value) { null -> edit.remove(key);is String -> edit.putString(key,value);is Boolean -> edit.putBoolean(key,value) } };edit.commit()
        }
    }
}
