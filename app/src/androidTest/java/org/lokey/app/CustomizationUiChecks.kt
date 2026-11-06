package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONObject
import java.io.File

object CustomizationUiChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val settings=prefs.designSettings();val original=if(prefs.automaticLayout) "auto" else prefs.layout
        val designs=prefs.designs;val layouts=prefs.customLayouts;val active=prefs.activeDesign;val dirty=prefs.designDirty;val light=prefs.appLight
        val report=JSONObject();val created=ArrayList<String>()
        val suffix=SystemClock.uptimeMillis().toString(36)
        val layoutName="Test layout $suffix"
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        test.uiAutomation.serviceInfo=info
        val main=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","theme").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun press(value:String?=null,desc:String?=null) { test.runOnMainSync {
            val matches=views(main.window.decorView).filter { (value==null || it is TextView && it.text.toString()==value) && (desc==null || it.contentDescription==desc) }
            (matches.firstOrNull { it.isClickable } ?: error("Missing $value / $desc")).performClick()
        };test.waitForIdleSync() }
        fun node(value:String?=null,desc:String?=null,ignoreCase:Boolean=false,viewId:String?=null):AccessibilityNodeInfo {
            val until=SystemClock.uptimeMillis()+5000
            fun walk(root:AccessibilityNodeInfo,out:MutableList<AccessibilityNodeInfo>) {
                if((value==null || root.text?.toString()?.equals(value,true)==true) && (desc==null || root.contentDescription?.toString()?.equals(desc,ignoreCase)==true) && (viewId==null || root.viewIdResourceName==viewId)) out.add(root)
                for(i in 0 until root.childCount) root.getChild(i)?.let { walk(it,out) }
            }
            while(SystemClock.uptimeMillis()<until) {
                if(android.os.Build.VERSION.SDK_INT>=34) test.uiAutomation.clearCache()
                val matches=ArrayList<AccessibilityNodeInfo>()
                for(window in test.uiAutomation.windows.sortedByDescending { it.layer }) window.root?.let {
                    if(it.packageName==context.packageName || window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) walk(it,matches)
                }
                if(matches.isEmpty()) test.uiAutomation.rootInActiveWindow?.let { walk(it,matches) }
                (matches.firstOrNull { it.isClickable } ?: matches.firstOrNull())?.let { return it }
                SystemClock.sleep(50)
            }
            error("Missing visible $value / $desc")
        }
        fun click(value:String?=null,desc:String?=null,viewId:String?=null) { check(node(value,desc,viewId=viewId).performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync();SystemClock.sleep(80) }
        fun set(desc:String,text:String) { check(node(desc=desc).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text) }));test.waitForIdleSync() }
        fun await(check:()->Boolean) {
            val until=SystemClock.uptimeMillis()+5000
            while(SystemClock.uptimeMillis()<until) { if(check()) return;SystemClock.sleep(50) }
            error("UI change did not complete")
        }
        fun shot(name:String) {
            SystemClock.sleep(300)
            val bitmap=test.uiAutomation.takeScreenshot() ?: error("Screen capture unavailable")
            File(context.filesDir,"customization-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        fun save(name:String):DesignPresets.Design {
            press("Save as new preset");set("Preset name",name);shot("preset-name");click("Save")
            await { DesignPresets.all(prefs).any { it.name==name } }
            return DesignPresets.all(prefs).first { it.name==name }.also { created.add(it.id) }
        }
        fun keyboard()=views(main.window.decorView).filterIsInstance<KeyboardView>().first { it.chooseKey!=null }
        fun keyColor(index:Int) { test.runOnMainSync {
            val view=keyboard();val bounds=android.graphics.Rect()
            view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
            val event=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0)
            view.onTouchEvent(event);event.recycle()
        };test.waitForIdleSync() }
        fun fills(color:Int) { test.runOnMainSync {
            val view=keyboard();view.editor(false,"Done",false)
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            for(index in listOf(0,10,20,29,37,38,40,42)) {
                val bounds=android.graphics.Rect();view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(bounds)
                val expected=if(index==42) KeyboardTheme.colors(prefs).accent else color
                check(bitmap.getPixel(bounds.left+bounds.width()/5,bounds.top+bounds.height()/5)==expected) { "Key $index kept its old color" }
            }
            bitmap.recycle()
        } }
        try {
            prefs.layout="qwerty"
            if(prefs.appLight) press(desc="Switch to dark theme")
            press(desc="Light keyboard theme")
            keyColor(20);click(desc="Pink");click("Apply")
            press("Numbers");click(desc="Blue");click("Apply")
            check(prefs.keyColor("a")!=null && prefs.rowColor(0)!=null)
            press("Keys");click(desc="Green");click("Apply")
            check(prefs.keyColor("a")==null && prefs.rowColor(0)==null)
            fills(android.graphics.Color.rgb(77,170,122))
            report.put("global_keys_reset_individual_and_row_colors",true).put("global_keys_include_space_and_controls",true)
            keyColor(37);click(desc="Purple");click("Apply")
            press("Numbers");click(desc="Orange");click("Apply")
            press(desc="Light keyboard theme")
            check(prefs.keyColor("delete")==null && prefs.rowColor(0)==null)
            fills(android.graphics.Color.WHITE)
            report.put("light_preset_restores_white_keys_and_clears_overrides",true)
            press(desc="Light keyboard theme");press("Background");click(desc="Blue");shot("color-dark");click("Apply")
            val first=save("Test palette alpha $suffix")
            press("Accent");click(desc="Coral");click("Apply");val second=save("Test palette beta $suffix")
            check(first.id!=second.id && DesignPresets.all(prefs).first { it.id==first.id }.settings.toString()==first.settings.toString())
            press(desc=first.name+" keyboard theme");await { prefs.activeDesign==first.id }
            test.runOnMainSync {
                val tiles=views(main.window.decorView).mapNotNull { it.contentDescription?.toString() }.filter { it.endsWith("keyboard theme") }
                check(tiles.last()=="Your image keyboard theme")
            }
            shot("presets")
            press(desc="Delete design: ${second.name}");click("Delete",viewId="android:id/button1");await { DesignPresets.all(prefs).none { it.id==second.id } }
            report.put("preset_save_as_new_apply_delete_and_image_last",true)
            if(!prefs.appLight) press(desc="Switch to light theme")
            press("Background");shot("color-light");click("Cancel")
            report.put("color_panels_in_both_appearances",true)
            val editor=test.startActivitySync(Intent().setClass(context,LayoutEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            set("Layout name",layoutName)
            click(desc="Add key to row 2");shot("add-key");set("Primary text","§");set("Secondary choices · one per line","😅\n😭");click("Add key")
            click(desc="Edit key: 1");shot("edit-key");set("Primary text","x");set("Secondary choices · one per line","😅\n😭");click(desc="Move key →")
            val source=FloatArray(2);val target=FloatArray(2)
            test.runOnMainSync {
                val all=views(editor.window.decorView)
                for((desc,point) in listOf("Edit key: x" to source,"Edit key: q" to target)) {
                    val view=all.first { it.contentDescription==desc };val location=IntArray(2);view.getLocationOnScreen(location)
                    point[0]=location[0]+view.width/2f;point[1]=location[1]+view.height/2f
                }
            }
            val down=SystemClock.uptimeMillis()
            fun touch(action:Int,x:Float,y:Float) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(test.uiAutomation.injectInputEvent(event,true));event.recycle()
            }
            touch(MotionEvent.ACTION_DOWN,source[0],source[1]);SystemClock.sleep(700)
            for(i in 1..8) { touch(MotionEvent.ACTION_MOVE,source[0]+(target[0]-source[0])*i/8,source[1]+(target[1]-source[1])*i/8);SystemClock.sleep(35) }
            touch(MotionEvent.ACTION_UP,target[0],target[1]);test.waitForIdleSync();shot("layout-editor")
            click(desc="Save custom layout")
            await { prefs.layouts().any { it.name==layoutName } }
            val saved=prefs.layouts().first { it.name==layoutName }
            check(saved.rows[0].first().primary=="2" && saved.rows[1].first().primary=="x") { "Key drag did not move between rows" }
            check(saved.rows[1].first().secondary==listOf("😅","😭") && saved.rows[1].any { it.primary=="§" })
            check(prefs.layout==saved.id)
            report.put("visible_add_remap_secondary_move_drag_and_save",true)
            await { var focused=false;test.runOnMainSync { focused=main.hasWindowFocus() };focused }
            press("Try it")
            val primary=node(desc="x, hold for 😅 😭",ignoreCase=true)
            val expected=primary.contentDescription.toString().substringBefore(',')+"😅"
            check(primary.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync()
            check(node(desc="x, hold for 😅 😭",ignoreCase=true).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            test.waitForIdleSync();SystemClock.sleep(100);shot("live-keyboard")
            test.runOnMainSync {
                val input=views(main.window.decorView).filterIsInstance<android.widget.EditText>().first()
                check(input.text.toString()==expected) { "Live custom layout input: ${input.text}, expected $expected" }
            }
            report.put("custom_layout_types_into_live_android_editor",true)
            press("Feel");press(desc="Keyboard layout settings")
            press(desc="Delete layout: ${saved.name}");click("Delete",viewId="android:id/button1");await { prefs.layouts().none { it.id==saved.id } }
            check(prefs.automaticLayout);report.put("layout_delete_restores_automatic",true)
        } finally {
            for(id in created) DesignPresets.delete(prefs,id)
            prefs.designs=designs;prefs.customLayouts=layouts;prefs.layout=original;prefs.applyDesign(settings)
            prefs.activeDesign=active;prefs.designDirty=dirty;prefs.appLight=light
            test.runOnMainSync { main.finish() }
        }
        return report
    }
    fun models(test:Instrumentation,context:Context):JSONObject {
        val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","models").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val labels=ArrayList<String>();val actions=ArrayList<String>();val views=ArrayList<View>()
        fun walk(view:View) {
            views.add(view)
            if(view is TextView) labels.add(view.text.toString())
            view.contentDescription?.toString()?.takeIf { it.startsWith("Model action: ") }?.let { actions.add(it.removePrefix("Model action: ")) }
            if(view is ViewGroup) for(i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        try {
            test.waitForIdleSync();test.runOnMainSync { walk(activity.window.decorView) }
            check(actions.toSet()==setOf("lfm","qwen","parakeet","qwen_asr") && actions.size==4)
            check(labels.none { it in listOf("Set up language & models","Choose file","Choose audio encoder") })
            check("Chooses text and speech models for your language." in labels)
            check(labels.containsAll(listOf("Prediction","Speak","Models & inference credits")))
            check(views.filter { it.contentDescription?.toString()?.endsWith(" logo")==true }.size==4)
            val buttons=views.filterIsInstance<TextView>().filter { it.contentDescription?.toString()?.startsWith("Model action: ")==true }
            val selected=buttons.first { it.isSelected };val available=buttons.first { !it.isSelected }
            check(selected.currentTextColor!=available.currentTextColor)
            val ranks=views.filterIsInstance<TextView>().filter { it.text.toString() in listOf("Prediction","Speak") }
            check(ranks.all { it.textSize>=19*context.resources.displayMetrics.density })
            val credits=views.filterIsInstance<TextView>().first { it.text.toString()=="Models & inference credits" }
            val mic=views.filterIsInstance<TextView>().first { it.text.toString()=="Allow microphone" }
            check(views.indexOf(credits)>views.indexOf(mic))
            SystemClock.sleep(300)
            val bitmap=test.uiAutomation.takeScreenshot() ?: error("Cannot capture model page")
            File(context.filesDir,"models-clean.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            val pins=JSONObject(context.assets.open("runtimes.json").bufferedReader().use { it.readText() })
            check(pins.length()==3)
            for(name in pins.keys()) check(pins.getJSONObject(name).getString("revision").matches(Regex("[a-f0-9]{40}")))
            for(model in ModelCredits.all) check(context.assets.open("licenses/${model.file}").use { it.readBytes() }.size>1000)
            val creditsActivity=test.startActivitySync(Intent().setClass(context,CreditsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.waitForIdleSync();SystemClock.sleep(400)
            val providerLabels=ArrayList<String>()
            fun collect(view:View,labels:MutableList<String>) {
                if(view is TextView) labels.add(view.text.toString())
                if(view is ViewGroup) for(i in 0 until view.childCount) collect(view.getChildAt(i),labels)
            }
            test.runOnMainSync { collect(creditsActivity.window.decorView,providerLabels) }
            check(providerLabels.containsAll(listOf("Models","Inference","loina-core","llama.cpp","whisper.cpp")))
            check("Mechanical switch recordings" !in providerLabels)
            val bitmapCredits=test.uiAutomation.takeScreenshot() ?: error("Cannot capture credits")
            File(context.filesDir,"models-credits.png").outputStream().use { bitmapCredits.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmapCredits.recycle()
            test.runOnMainSync { creditsActivity.finish() }
            val inputActivity=test.startActivitySync(Intent().setClass(context,CreditsActivity::class.java).putExtra("section","input").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val inputLabels=ArrayList<String>();test.waitForIdleSync()
            test.runOnMainSync { collect(inputActivity.window.decorView,inputLabels) }
            check(inputLabels.containsAll(listOf("Mechanical switch recordings","Great Wave artwork","Keyboard & language data","lucide")))
            check("Inference" !in inputLabels && "Model license" !in inputLabels)
            SystemClock.sleep(400)
            val bitmapInput=test.uiAutomation.takeScreenshot() ?: error("Cannot capture input credits")
            File(context.filesDir,"input-credits.png").outputStream().use { bitmapInput.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmapInput.recycle()
            test.runOnMainSync { inputActivity.finish() }
            return JSONObject().put("four_curated_models",true).put("setup_and_import_controls_removed",true).put("concise_automatic_description",true)
                .put("selected_and_available_colors_differ",true).put("provider_logos",4).put("large_section_titles",true).put("credits_below_microphone",true)
                .put("pinned_runtime_sources",3).put("model_licenses_available_offline",4).put("input_and_design_credits_separate",true)
        } finally { test.runOnMainSync { activity.finish() } }
    }
}
