package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File

object CustomizationChecks {
    fun images(context:Context):JSONObject {
        fun load(width:Int,height:Int,builtin:Boolean=true,file:File=KeyboardTheme.imageFile(context),version:Long=0):Bitmap? {
            val done=java.util.concurrent.CountDownLatch(1);var result:Bitmap?=null
            KeyboardTheme.image(context,version,width,height,builtin,file) { result=it;done.countDown() }
            check(done.await(10,java.util.concurrent.TimeUnit.SECONDS)) { "Image decoding timed out" }
            return result
        }
        val small=checkNotNull(load(400,260))
        check(small.width==400 && small.height==276)
        check(load(400,260)===small)
        check(load(390,250)===small)
        val full=checkNotNull(load(1200,700))
        check(full.width==1600 && full.height==1104 && full!==small)
        val palette=checkNotNull(load(80,80))
        check(palette.width>=80 && palette.height>=80 && palette.allocationByteCount<full.allocationByteCount/16)
        val file=File(context.cacheDir,"image-sampling-check.png")
        fun photo(width:Int,height:Int,color:Int) {
            val source=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            source.eraseColor(color)
            try { file.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { source.recycle() }
        }
        try {
            photo(3200,2400,0xffff0000.toInt())
            val reduced=checkNotNull(load(600,400,false,file,1))
            check(reduced.width==800 && reduced.height==600 && reduced.getPixel(0,0)==0xffff0000.toInt())
            check(reduced.allocationByteCount<=3200*2400*4/16)
            photo(3200,400,0xff0000ff.toInt())
            val wide=checkNotNull(load(600,600,false,file,2))
            check(wide.width==3200 && wide.height==400)
            check(wide.getPixel(0,0)==0xff0000ff.toInt())
            photo(8,8,0xff00ff00.toInt())
            val tiny=checkNotNull(load(600,400,false,file,3))
            check(tiny.width==8 && tiny.height==8 && tiny.getPixel(0,0)==0xff00ff00.toInt())
            file.writeText("invalid image")
            check(load(600,400,false,file,4)==null)
            file.delete()
            check(load(600,400,false,file,5)==null)
            val score=JSONObject().put("wpm",61.5).put("accuracy",98.2).put("combo",42).put("score",1234).put("prediction_taps",2)
            val card=ScoreCard.create(context,score,false)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(card.path,bounds)
            check(bounds.outWidth==1080 && bounds.outHeight==1350 && card.length()>1000)
            return JSONObject().put("builtin_full_bytes",full.allocationByteCount).put("builtin_preview_bytes",small.allocationByteCount)
                .put("palette_bytes",palette.allocationByteCount).put("large_file_full_bytes",3200*2400*4).put("large_file_sampled_bytes",reduced.allocationByteCount)
                .put("size_aware_cache",true).put("center_crop_coverage",true).put("no_upscaling",true).put("invalid_and_missing_images",true).put("score_card_export",true)
        } finally { file.delete() }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val report=JSONObject()
        val settings=prefs.designSettings();val designs=prefs.designs;val layouts=prefs.customLayouts
        val layout=if(prefs.automaticLayout) "auto" else prefs.layout
        val dirty=prefs.designDirty;val active=prefs.activeDesign;val chosen=prefs.userImageChosen;val version=prefs.themeImageVersion
        val file=KeyboardTheme.imageFile(context);val image=if(file.exists()) file.readBytes() else null
        val created=ArrayList<String>()
        val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun photo(color:Int) {
            val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        try {
            prefs.layout="intl";prefs.keyboardTheme="image";prefs.themePicture="user"
            prefs.themeBackground=0xff123456.toInt();prefs.themeKeys=0xffabcdef.toInt();prefs.themeAccent=0xffaabbcc.toInt()
            prefs.rowColor(2,0xff223344.toInt());prefs.keyColor("q",0xff556677.toInt());photo(0xffff0000.toInt())
            val first=DesignPresets.save(prefs,"Test original");created.add(first.id)
            prefs.rowColor(2,null);prefs.keyColor("q",null);prefs.themeBackground=0xff000000.toInt();photo(0xff0000ff.toInt())
            val second=DesignPresets.save(prefs,"Test copy");created.add(second.id)
            check(first.id!=second.id && first.image!=second.image)
            check(DesignPresets.apply(prefs,first.id))
            check(prefs.themeBackground==0xff123456.toInt() && prefs.rowColor(2)==0xff223344.toInt() && prefs.keyColor("q")==0xff556677.toInt())
            check(BitmapFactory.decodeFile(file.path).getPixel(0,0)==0xffff0000.toInt())
            check(DesignPresets.apply(prefs,second.id) && prefs.rowColor(2)==null && prefs.keyColor("q")==null)
            check(BitmapFactory.decodeFile(file.path).getPixel(0,0)==0xff0000ff.toInt())
            check(!DesignPresets.delete(prefs,"dark") && !DesignPresets.delete(prefs,"wave"))
            check(DesignPresets.delete(prefs,first.id) && !first.imageFile(prefs).exists() && second.imageFile(prefs).exists())
            report.put("independent_image_copies",true).put("palette_row_and_key_round_trip",true).put("save_creates_new_preset",true).put("built_in_designs_protected",true)
            for(builtin in KeyboardLayouts.all) {
                prefs.layout=builtin.id
                val clone=CustomLayouts.create(prefs).copy(name="Clone ${builtin.id}")
                CustomLayouts.save(prefs,clone)
                check(clone.layout().composer==builtin.composer && clone.layout().shifted==builtin.shifted)
                check(CustomLayouts.delete(prefs,clone.id))
            }
            report.put("all_default_layouts_can_be_cloned",KeyboardLayouts.all.size)
            prefs.layout="intl"
            val source=CustomLayouts.create(prefs)
            check(source.base=="intl" && source.rows[1][8].primary=="o")
            val rows=source.rows.map { it.toMutableList() }.toMutableList()
            val q=rows[1].removeAt(0).copy(primary="§",secondary=listOf("😅","😭","new symbol"))
            rows[0].add(0,q)
            rows[1].add(CustomLayouts.Key(primary="hello",secondary=listOf("world")))
            rows[1].add(CustomLayouts.Key(primary="enter"))
            val custom=source.copy(name="Test symbols",rows=rows)
            CustomLayouts.save(prefs,custom);prefs.layout=custom.id
            check(Prefs(context).currentLayout.id==custom.id && prefs.currentLayout.composer=="dead")
            check(CustomLayouts.parse(prefs.customLayouts).first { it.id==custom.id }==custom)
            check(!CustomLayouts.delete(prefs,"intl") && !CustomLayouts.delete(prefs,"qwerty"))
            check(runCatching { CustomLayouts.save(prefs,custom.copy(rows=listOf(listOf(CustomLayouts.Key())))) }.isFailure)
            report.put("custom_layout_storage_and_reordering",true).put("built_in_layouts_protected",true).put("empty_keys_rejected",true).put("dead_key_rules_preserved",true)
            lateinit var view:KeyboardView;val typed=StringBuilder();var enter=0;var deleted=0
            test.runOnMainSync {
                view=KeyboardView(activity,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) { typed.append(text) }
                    override fun delete() { deleted++ }
                    override fun enter() { enter++ }
                    override fun cursor(delta:Int) { }
                    override fun voice(start:Boolean)=false
                    override fun cancelVoice() { }
                    override fun nextKeyboard() { }
                    override fun candidate(word:String) { }
                    override fun feedback(key:String,down:Boolean) { }
                })
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,1080,view.measuredHeight);view.editor(false,"Done",false)
                val nodes=view.accessibilityNodeProvider
                nodes.performAction(0,AccessibilityNodeInfo.ACTION_CLICK,null);check(typed.toString()=="§")
                nodes.performAction(0,AccessibilityNodeInfo.ACTION_LONG_CLICK,null);check(typed.toString()=="§😅")
                val hello=custom.rows.flatten().indexOfFirst { it.primary=="hello" }
                nodes.performAction(hello,AccessibilityNodeInfo.ACTION_CLICK,null);nodes.performAction(hello,AccessibilityNodeInfo.ACTION_LONG_CLICK,null)
                check(typed.toString()=="§😅helloworld")
                nodes.performAction(custom.rows.flatten().indexOfFirst { it.primary=="enter" },AccessibilityNodeInfo.ACTION_CLICK,null)
                check(typed.toString()=="§😅helloworldenter" && enter==0)
                val keys=custom.rows.flatten()
                nodes.performAction(keys.indexOfFirst { it.action=="enter" },AccessibilityNodeInfo.ACTION_CLICK,null)
                nodes.performAction(keys.indexOfFirst { it.action=="delete" },AccessibilityNodeInfo.ACTION_CLICK,null)
                check(enter==1 && deleted==1)
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File(context.filesDir,"custom-layout.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            // A fast upward flick must emit a full emoji, including its surrogate pair.
            val bounds=android.graphics.Rect()
            test.runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(0)!!.getBoundsInParent(bounds) }
            val time=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) test.runOnMainSync {
                val y=bounds.exactCenterY()-if(action==MotionEvent.ACTION_DOWN) 0f else 16*context.resources.displayMetrics.density
                val event=MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),y,0)
                view.onTouchEvent(event);event.recycle()
            }
            check(typed.endsWith("😅"))
            report.put("custom_primary_secondary_and_action_input",true).put("emoji_flick_preserves_unicode",true)
            CustomLayouts.save(prefs,custom.copy(rows=listOf((0 until 24).map { CustomLayouts.Key(primary="x",width=if(it==23) .5f else 8f) })))
            test.runOnMainSync {
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,1080,view.measuredHeight);view.editor(false,"Done",false)
                for(i in 0 until 24) {
                    val rect=android.graphics.Rect();view.accessibilityNodeProvider.createAccessibilityNodeInfo(i)!!.getBoundsInParent(rect)
                    check(rect.width()>0 && rect.left>=0 && rect.right<=1080) { "Custom key has invalid geometry" }
                }
            }
            report.put("extreme_custom_widths_stay_inside_keyboard",true)
            prefs.layout="qwerty"
            test.runOnMainSync {
                view.editor(false,"Done",false)
                val comma=39
                val description=view.accessibilityNodeProvider.createAccessibilityNodeInfo(comma)!!.contentDescription.toString()
                check("☺" !in description && "😊" in description)
                view.accessibilityNodeProvider.performAction(comma,AccessibilityNodeInfo.ACTION_LONG_CLICK,null)
                check(typed.endsWith("😊"))
            }
            check(CustomLayouts.emojis.containsAll(listOf("😅","😭")))
            check(CustomLayouts.delete(prefs,custom.id) && Prefs(context).layouts().none { it.id==custom.id })
            report.put("default_emoji_order",true).put("custom_layout_deletion",true)
        } finally {
            for(id in created) DesignPresets.delete(prefs,id)
            prefs.designs=designs;prefs.customLayouts=layouts;prefs.layout=layout;prefs.applyDesign(settings)
            prefs.designDirty=dirty;prefs.activeDesign=active;prefs.userImageChosen=chosen;prefs.themeImageVersion=version+10
            if(image==null) file.delete() else file.writeBytes(image)
            test.runOnMainSync { activity.finish() }
        }
        return report
    }
}
