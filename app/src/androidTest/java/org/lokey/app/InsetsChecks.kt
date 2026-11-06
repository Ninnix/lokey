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
            File(context.filesDir,"insets-live.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        return result.put("keys",JSONArray(keys)).put("navigation",JSONArray(navigation)).put("nodes",JSONArray(nodes)).put("no_navigation_overlap",true)
    }
}
