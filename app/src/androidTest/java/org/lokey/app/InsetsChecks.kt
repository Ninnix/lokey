package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Insets
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object InsetsChecks {
    fun activities(test:Instrumentation,context:Context):JSONObject {
        check(context.packageName=="org.lokey.app.features")
        val store=context.getSharedPreferences("lokey",0);val original=store.all;val prefs=Prefs(context)
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val originalIme=shell("settings get secure default_input_method")
        val rows=JSONArray();var synthetic=0
        try {
            prefs.setupComplete=true;prefs.layout="qwerty";prefs.setupLanguage="English"
            shell("ime enable ${context.packageName}/org.lokey.app.LokeyService");shell("ime set ${context.packageName}/org.lokey.app.LokeyService")
            data class Screen(val type:Class<out android.app.Activity>,val page:String="")
            val screens=listOf("home","feel","models","lab","theme","layout").map { Screen(MainActivity::class.java,it) }+
                listOf(SetupActivity::class.java,LanguageActivity::class.java,LayoutEditorActivity::class.java,CreditsActivity::class.java,PrivacyActivity::class.java).map { Screen(it) }
            for(light in listOf(false,true)) for(screen in screens) {
                prefs.appLight=light
                val activity=test.startActivitySync(Intent().setClass(context,screen.type).putExtra("page",screen.page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try {
                    test.runOnMainSync {
                        activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).apply { isFocusableInTouchMode=true;requestFocus() }
                    }
                    var ready=false;var state="";val until=SystemClock.uptimeMillis()+15000
                    while(!ready && SystemClock.uptimeMillis()<until) {
                        test.runOnMainSync {
                            val content=activity.findViewById<ViewGroup>(android.R.id.content);val root=content.getChildAt(0)
                            activity.currentFocus?.let { activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0) }
                            ready=activity.hasWindowFocus() && root.width>0 && root.rootWindowInsets!=null && !root.isLayoutRequested
                            if(Build.VERSION.SDK_INT>=30 && ready) ready=!root.rootWindowInsets.isVisible(WindowInsets.Type.ime())
                            state="focus=${activity.hasWindowFocus()}, attached=${root.isAttachedToWindow}, size=${root.width}x${root.height}, insets=${root.rootWindowInsets}, finishing=${activity.isFinishing}"
                        }
                        if(!ready) SystemClock.sleep(40)
                    }
                    check(ready) { "Insets screen not ready: $screen; $state" }
                    SystemClock.sleep(80)
                    var failure:Throwable?=null
                    test.runOnMainSync {
                        try {
                        val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                        val position=IntArray(2);root.getLocationInWindow(position)
                        check(position[0]==0 && position[1]==0 && root.width==activity.window.decorView.width && root.height==activity.window.decorView.height) { "Content is not edge-to-edge: $screen" }
                        if(Build.VERSION.SDK_INT>=30) {
                            val safe=root.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                            check(root.paddingLeft==safe.left && root.paddingTop==safe.top && root.paddingRight==safe.right && root.paddingBottom==safe.bottom) { "Wrong safe padding: $screen; actual=${listOf(root.paddingLeft,root.paddingTop,root.paddingRight,root.paddingBottom)}, expected=$safe" }
                            val mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                            check(activity.window.insetsController!!.systemBarsAppearance and mask==if(light) mask else 0)
                        }
                        check(root.getChildAt(0).top>=root.paddingTop)
                        check(root.getChildAt(root.childCount-1).bottom<=root.height-root.paddingBottom) { "Footer outside safe area: $screen; height=${root.height}, padding=${root.paddingTop}/${root.paddingBottom}, children=${(0 until root.childCount).map { root.getChildAt(it).let { child -> listOf(child.top,child.bottom) } }}" }
                        rows.put(JSONObject().put("activity",screen.type.simpleName).put("page",screen.page).put("light",light)
                            .put("size",JSONArray(listOf(root.width,root.height))).put("padding",JSONArray(listOf(root.paddingLeft,root.paddingTop,root.paddingRight,root.paddingBottom))))
                        if(synthetic==0 && Build.VERSION.SDK_INT>=30) {
                            val sample=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(3,5,7,11);setBackgroundColor(0xff171819.toInt());addView(View(activity),LinearLayout.LayoutParams(-1,200)) }
                            AppWindow.frame(activity.window,sample,light)
                            fun apply(nav:android.graphics.Insets,caption:android.graphics.Insets=android.graphics.Insets.NONE,cutout:android.graphics.Insets=android.graphics.Insets.NONE,ime:android.graphics.Insets=android.graphics.Insets.NONE) {
                                val status=android.graphics.Insets.of(0,80,0,0)
                                sample.dispatchApplyWindowInsets(WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(),status).setInsets(WindowInsets.Type.navigationBars(),nav)
                                    .setInsets(WindowInsets.Type.captionBar(),caption).setInsets(WindowInsets.Type.displayCutout(),cutout).setInsets(WindowInsets.Type.ime(),ime).build())
                                check(sample.paddingLeft==3+maxOf(nav.left,caption.left,cutout.left,ime.left) && sample.paddingTop==5+maxOf(80,caption.top,cutout.top,ime.top) &&
                                    sample.paddingRight==7+maxOf(nav.right,caption.right,cutout.right,ime.right) && sample.paddingBottom==11+maxOf(nav.bottom,caption.bottom,cutout.bottom,ime.bottom))
                                sample.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                                check(sample.measuredHeight==200+sample.paddingTop+sample.paddingBottom);synthetic++
                            }
                            apply(android.graphics.Insets.of(0,0,0,24));apply(android.graphics.Insets.of(0,0,0,24));apply(android.graphics.Insets.of(0,0,0,150))
                            apply(android.graphics.Insets.of(0,0,80,0),android.graphics.Insets.of(0,120,0,0),android.graphics.Insets.of(90,0,60,0))
                            apply(android.graphics.Insets.of(0,0,0,150),ime=android.graphics.Insets.of(0,0,0,600));apply(android.graphics.Insets.of(0,0,0,24))
                            sample.dispatchApplyWindowInsets(WindowInsets.CONSUMED);check(sample.paddingLeft==3 && sample.paddingTop==5 && sample.paddingRight==7 && sample.paddingBottom==11);synthetic++
                        }
                        } catch(error:Throwable) { failure=error }
                    }
                    failure?.let { throw it }
                    test.sendStatus(0,android.os.Bundle().apply { putString("progress","activity insets ${rows.length()}/${screens.size*2}") })
                } finally { test.runOnMainSync { activity.finish() } }
            }
            prefs.appLight=true;prefs.keyboardTheme="dark"
            val live=run(test,context)
            return JSONObject().put("screens",rows).put("synthetic_inset_checks",synthetic).put("keyboard",live)
        } finally {
            if(originalIme.isNotBlank() && originalIme!="null") shell("ime set $originalIme")
            val edit=store.edit().clear()
            for((key,value) in original) when(value) {
                is Boolean -> edit.putBoolean(key,value);is String -> edit.putString(key,value);is Int -> edit.putInt(key,value)
                is Long -> edit.putLong(key,value);is Float -> edit.putFloat(key,value)
            }
            edit.commit()
        }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val result=JSONObject()
        if(Build.VERSION.SDK_INT>=30) test.runOnMainSync {
            val root=ImeLayout(context)
            val key=View(context)
            root.addView(key,LinearLayout.LayoutParams(-1,200))
            fun apply(nav:Int,caption:Int,left:Int=0,right:Int=0) {
                root.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.navigationBars(),Insets.of(left,0,right,nav))
                    .setInsets(WindowInsets.Type.captionBar(),Insets.of(0,0,0,caption)).build())
                check(root.paddingBottom==maxOf(nav,caption) && root.paddingLeft==left && root.paddingRight==right)
                root.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                root.layout(0,0,root.measuredWidth,root.measuredHeight)
                check(root.measuredHeight==200+maxOf(nav,caption) && key.height==200 && key.width==1000-left-right)
            }
            apply(56,168);apply(56,168);apply(32,0);apply(0,0,80,40);apply(0,0)
            root.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
            check(root.paddingBottom==0)
            result.put("insets_change_without_resizing_keys",true).put("consumed_insets_not_added_twice",true)
        }
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","lab")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun editor(view:View):EditText? {
            if(view is EditText) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
            return null
        }
        var nodes=listOf<JSONObject>()
        var visible=0L
        val until=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<until) {
            test.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                editor(activity.window.decorView)?.let { input ->
                    input.requestFocus();activity.getSystemService(InputMethodManager::class.java).showSoftInput(input,0)
                }
            }
            if(Build.VERSION.SDK_INT>=34) test.uiAutomation.clearCache()
            val found=ArrayList<JSONObject>()
            fun walk(node:AccessibilityNodeInfo) {
                if(!node.refresh()) return
                val label=node.contentDescription?.toString() ?: node.text?.toString().orEmpty()
                if(label.isNotEmpty()) {
                    val rect=Rect();node.getBoundsInScreen(rect)
                    found.add(JSONObject().put("label",label).put("id",node.viewIdResourceName)
                        .put("bounds",JSONArray(listOf(rect.left,rect.top,rect.right,rect.bottom))))
                }
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
            }
            test.uiAutomation.windows.filter { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach { it.root?.let { node -> walk(node) } }
            nodes=found
            if(nodes.any { it.getString("label").startsWith("space",true) }) {
                if(visible==0L) visible=SystemClock.uptimeMillis()
                if(SystemClock.uptimeMillis()-visible>=1000) break
            }
            SystemClock.sleep(100)
        }
        check(nodes.any { it.getString("label").startsWith("space",true) }) { "Keyboard did not open" }
        fun rect(row:JSONObject):Rect { val b=row.getJSONArray("bounds");return Rect(b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)) }
        val keys=nodes.filter { it.getString("label").substringBefore(',').lowercase() in listOf("space","?123","done","enter") }
        val navigation=nodes.filter { row ->
            val text=row.getString("label").lowercase()
            row.optString("id").startsWith("android:id/input_method_nav_") || text.contains("hide keyboard") || text.contains("switch input") || text.contains("change keyboard") || text.contains("switch keyboard")
        }
        check(keys.size>=2)
        for(key in keys) for(button in navigation) check(!Rect.intersects(rect(key),rect(button))) { "${key.getString("label")} overlaps ${button.getString("label")}" }
        if(Build.VERSION.SDK_INT>=29) test.runOnMainSync {
            val layouts=JSONArray()
            fun inspect(view:View) {
                if(view is ImeLayout) {
                    val position=IntArray(2);view.getLocationOnScreen(position)
                    val row=JSONObject().put("bounds",JSONArray(listOf(position[0],position[1],position[0]+view.width,position[1]+view.height)))
                        .put("padding",JSONArray(listOf(view.paddingLeft,view.paddingTop,view.paddingRight,view.paddingBottom)))
                    if(Build.VERSION.SDK_INT>=30) for((name,type) in listOf("navigation" to WindowInsets.Type.navigationBars(),"caption" to WindowInsets.Type.captionBar(),"cutout" to WindowInsets.Type.displayCutout())) {
                        val inset=view.rootWindowInsets.getInsets(type)
                        row.put(name,JSONArray(listOf(inset.left,inset.top,inset.right,inset.bottom)))
                    }
                    layouts.put(row)
                }
                if(view is ViewGroup) for(i in 0 until view.childCount) inspect(view.getChildAt(i))
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { inspect(it) }
            result.put("windows",layouts)
        }
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            var sampleX=0;var sampleY=0
            test.runOnMainSync {
                val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0);val position=IntArray(2);root.getLocationOnScreen(position)
                sampleX=position[0]+root.width/2;sampleY=position[1]+root.paddingTop+(8*context.resources.displayMetrics.density).toInt()
                if(Build.VERSION.SDK_INT>=30) check(root.paddingBottom>=root.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom)
            }
            check(KeyboardTheme.light(bitmap.getPixel(sampleX,sampleY))==Prefs(context).appLight) { "IME window covers the host screen" }
            File(context.filesDir,"insets-live.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        return result.put("keys",JSONArray(keys)).put("navigation",JSONArray(navigation)).put("nodes",JSONArray(nodes)).put("no_navigation_overlap",true)
    }
}
