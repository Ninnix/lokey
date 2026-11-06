package org.lokey.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

object DownloadChecks {
    private fun find(view:View,label:String):View? {
        if(view.contentDescription?.toString()==label || view is TextView && view.text.toString()==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    private fun scroll(view:View):ScrollView? {
        if(view is ScrollView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) scroll(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun icon(view:View):SwitchIcon? {
        if(view is SwitchIcon) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) icon(view.getChildAt(i))?.let { return it }
        return null
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val originalModel=prefs.model;val originalSwitch=prefs.switchId;val originalSound=prefs.soundEnabled
        val notifications=context.getSystemService(NotificationManager::class.java)
        var activity:Activity?=null
        val model=Models.text.firstOrNull { !Models.ready(context,it) } ?: Models.text.first()
        val key="model:${model.id}";val partial=File(Models.file(context,model).path+".part");val existed=partial.exists()

        fun await(message:String,condition:()->Boolean) {
            val until=SystemClock.uptimeMillis()+60000
            while(SystemClock.uptimeMillis()<until) { if(condition()) return;SystemClock.sleep(100) }
            error(message+": "+Downloads.state(context,key))
        }
        fun open(page:String):Activity {
            val next=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page",page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            var ready=false
            await("Lost app focus") { test.runOnMainSync { ready=next.hasWindowFocus() };ready }
            activity=next;return next
        }
        fun root()=(activity!!.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        fun capture(name:String) { test.runOnMainSync {
            val view=activity!!.window.decorView
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
            File(context.filesDir,name+".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        } }
        try {
            for(switch in Switches.profiles) check(context.assets.list("switches/${switch.id}")!!.size==switch.samples)
            prefs.switchId="cherry_red";open("feel")
            SystemClock.sleep(500)
            test.runOnMainSync {
                val original=root();val position=scroll(original)!!;position.scrollTo(0,200);val top=position.scrollY
                find(original,Switches.profiles.first { it.id=="cherry_brown" }.let { "${it.name}, ${it.kind}" })!!.performClick()
                check(root()===original && position.scrollY==top) { "Switch selection rebuilt the page" }
                find(original,"Cherry MX Red, Linear")!!.performClick()
                check(root()===original && position.scrollY==top)
                check(icon(find(original,"Cherry MX Brown, Tactile")!!)!!.available)
            }
            capture("downloads-switches")
            open("models")
            test.runOnMainSync {
                val original=root();val position=scroll(original)!!;position.scrollTo(0,200);val top=position.scrollY
                find(original,"Select automatic model")!!.performClick()
                check(root()===original && position.scrollY==top) { "Automatic selection rebuilt the page" }
                val installed=Models.text.first { Models.ready(context,it) }
                find(original,"Model action: ${installed.id}")!!.performClick()
                check(root()===original && position.scrollY==top) { "Model selection rebuilt the page" }
            }
            activity=test.startActivitySync(Intent().setClass(context,SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            test.runOnMainSync {
                find(root(),"Setup continue")!!.performClick()
                val original=root();val position=scroll(original)!!;position.scrollTo(0,100);val top=position.scrollY
                find(original,"Setup model: qwen")!!.performClick()
                check(root()===original && position.scrollY==top) { "Setup model selection rebuilt the page" }
                find(original,"Setup model: lfm")!!.performClick()
                check(root()===original && position.scrollY==top)
            }
            open("models")
            var background=false;var resumed=false
            if(!Models.ready(context,model)) {
                test.runOnMainSync { find(root(),"Model action: ${model.id}")!!.performClick() }
                await("No download progress") { Downloads.state(context,key)?.bytes?.let { it>0 }==true }
                check(notifications.activeNotifications.isEmpty()) { "Downloads must not show notifications" }
                test.runOnMainSync {
                    val card=find(root(),"Model action: ${model.id}")!!.parent.parent as View
                    card.requestRectangleOnScreen(android.graphics.Rect(0,0,card.width,card.height),true)
                }
                SystemClock.sleep(500)
                capture("downloads-model")
                val before=Downloads.state(context,key)!!.bytes
                test.runOnMainSync { find(root(),"Feel")!!.performClick() }
                await("Page change stopped download") { Models.ready(context,model) || (Downloads.state(context,key)?.bytes ?: 0)>before }
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val leaving=Downloads.state(context,key)?.bytes ?: 0
                await("Backgrounding stopped download") { Models.ready(context,model) || (Downloads.state(context,key)?.bytes ?: 0)>leaving }
                background=true
                if(Downloads.state(context,key)?.active==true) {
                    Downloads.cancel(context,key);await("Cancellation did not finish") { Downloads.state(context,key)?.active==false }
                    val kept=partial.length();check(kept>0)
                    open("models")
                    test.runOnMainSync { find(root(),"Model action: ${model.id}")!!.performClick() }
                    await("Partial download did not resume") { Models.ready(context,model) || Downloads.state(context,key)?.let { it.resumedBytes==kept && it.bytes>kept }==true }
                    resumed=true
                    if(Downloads.state(context,key)?.active==true) { Downloads.cancel(context,key);await("Resume cancellation failed") { Downloads.state(context,key)?.active==false } }
                }
            }
            var feedback:Feedback?=null
            test.runOnMainSync { feedback=Feedback(context,prefs);prefs.soundEnabled=true }
            try {
                await("Recordings did not decode") { listOf("cherry_red","cherry_brown","cherry_blue").all { feedback!!.loadedSamples(it)>=Switches.profiles.first { p -> p.id==it }.samples } }
                test.runOnMainSync { prefs.switchId="cherry_red";check(feedback!!.tap("a")>0) { "Red playback did not start; check Silent / DND" } }
            } finally { test.runOnMainSync { feedback?.close() } }
            return JSONObject().put("all_eight_switches_bundled",true).put("switch_decoding",true).put("selection_preserves_views_and_scroll",true)
                .put("model_progress",true).put("no_notifications",true).put("continues_across_pages_and_background",background).put("partial_retry",resumed)
        } finally {
            if(Downloads.state(context,key)?.active==true) { Downloads.cancel(context,key);await("Could not stop test download") { Downloads.state(context,key)?.active==false } }
            if(!existed && !Models.ready(context,model)) { partial.delete();File(partial.path+".validator").delete() }
            prefs.model=originalModel;prefs.switchId=originalSwitch;prefs.soundEnabled=originalSound
            activity?.let { a -> test.runOnMainSync { a.finish() } }
        }
    }
}
