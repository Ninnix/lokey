package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object HomeSpeedChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val original=if(prefs.automaticLayout) "auto" else prefs.layout
        val report=JSONObject();val cases=JSONArray()
        val service=test.uiAutomation.serviceInfo
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=service
        fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
        fun await(check:()->Boolean) {
            val until=SystemClock.uptimeMillis()+8000
            while(SystemClock.uptimeMillis()<until) { if(check()) return;SystemClock.sleep(50) }
            error("Home / speed-test screen did not become ready")
        }
        fun keyboard():List<String> {
            val labels=ArrayList<String>()
            fun walk(node:android.view.accessibility.AccessibilityNodeInfo) {
                node.contentDescription?.toString()?.let { labels.add(it) }
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
            }
            if(android.os.Build.VERSION.SDK_INT>=34) test.uiAutomation.clearCache()
            for(window in test.uiAutomation.windows) if(window.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { walk(it) }
            return labels
        }
        fun shot(name:String) {
            SystemClock.sleep(300)
            val bitmap=test.uiAutomation.takeScreenshot() ?: error("Cannot capture $name")
            File(context.filesDir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        val home=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","home").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            await { var ready=false;test.runOnMainSync { ready=home.hasWindowFocus() };ready }
            var labels=emptyList<String>();var selected=false;var primaryColor=0;var chooseColor=0
            test.runOnMainSync {
                val all=views(home.window.decorView)
                labels=all.filterIsInstance<TextView>().map { it.text.toString() }
                val choose=all.filterIsInstance<TextView>().first { it.contentDescription=="Choose Lokey" }
                val enabled=all.filterIsInstance<TextView>().first { it.contentDescription=="Enable Lokey" }
                selected=choose.isSelected;primaryColor=enabled.currentTextColor;chooseColor=choose.currentTextColor
            }
            val id=android.provider.Settings.Secure.getString(context.contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            val active=id?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }==context.packageName
            check(selected==active && (!active || chooseColor==primaryColor))
            check(labels.any { it.contains("Private by design.") } && labels.any { it=="Type. Speak.\nOne flow." })
            check(labels.none { it.startsWith("A little intelligence") || it.startsWith("Hold Enter to speak") || it.startsWith("Text and audio stay") })
            shot("home-clean")
            report.put("choose_lokey_reflects_system_selection",true).put("selected_button_uses_accent",active).put("intelligence_and_privacy_header",true).put("footer_copy_removed",true)
        } finally { test.runOnMainSync { home.finish() } }
        try {
            for(id in listOf("pinyin","persian","cyrillic")) {
                prefs.layout=id
                val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","lab").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try {
                    await { var ready=false;test.runOnMainSync { ready=activity.hasWindowFocus() };ready }
                    test.runOnMainSync { views(activity.window.decorView).filterIsInstance<Switch>().first { it.text.toString()=="Speed test" }.performClick() }
                    await { "9, hold for [ {" in keyboard() }
                    check(prefs.layout==id)
                    var language="";var direction=0
                    test.runOnMainSync {
                        val game=views(activity.window.decorView).filterIsInstance<SpeedTestView>().first()
                        val info=android.view.inputmethod.EditorInfo();game.editor.onCreateInputConnection(info)
                        language=info.hintLocales?.get(0)?.language.orEmpty();direction=game.editor.textDirection
                    }
                    check(language=="en" && direction==View.TEXT_DIRECTION_LTR)
                    shot("speed-intl-$id")
                    test.runOnMainSync { views(activity.window.decorView).filterIsInstance<Switch>().first { it.text.toString()=="Speed test" }.performClick() }
                    val first=KeyboardLayouts.find(id).rows.first().first()
                    await {
                        val keys=keyboard()
                        keys.any { it.substringBefore(',').equals(first,true) } && "9, hold for [ {" !in keys
                    }
                    check(prefs.layout==id)
                    cases.put(JSONObject().put("preferred_layout",id).put("speed_layout","intl").put("english_hint",true).put("original_layout_restored",true))
                } finally { test.runOnMainSync { activity.finish() } }
            }
        } finally { prefs.layout=original }
        return report.put("speed_test_layout_cases",cases)
    }
}
