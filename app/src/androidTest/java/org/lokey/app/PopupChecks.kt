package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

object PopupChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val language=prefs.setupLanguage
        val density=context.resources.displayMetrics.density
        val typed=StringBuilder()
        lateinit var view:KeyboardView
        test.runOnMainSync {
            view=KeyboardView(context,prefs,object:KeyboardView.Listener {
                override fun text(text:String) { typed.append(text) }
                override fun delete() { }
                override fun enter() { }
                override fun cursor(delta:Int) { }
                override fun voice(start:Boolean)=false
                override fun cancelVoice() { }
                override fun nextKeyboard() { }
                override fun candidate(word:String) { }
                override fun feedback(key:String,down:Boolean) { }
            })
        }
        var selections=0
        fun capture(name:String) { test.runOnMainSync {
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File(context.filesDir,"popup-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
        } }
        fun select(id:Int,options:List<String>,index:Int,shot:String?=null) {
            val rect=Rect()
            test.runOnMainSync { typed.clear();view.accessibilityNodeProvider.createAccessibilityNodeInfo(id)!!.getBoundsInParent(rect) }
            val start=SystemClock.uptimeMillis();val x=rect.exactCenterX();val y=rect.exactCenterY()
            fun event(action:Int,at:Float=x) { test.runOnMainSync {
                check(at>=0f && at<view.width) { "Selection left the screen: $at" }
                val event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,at,y,0)
                view.onTouchEvent(event);event.recycle()
            } }
            event(MotionEvent.ACTION_DOWN);SystemClock.sleep(280)
            val direction=if(x>view.width/2f+density) -1 else 1
            val cell=min(41*density,(view.width-8*density)/options.size)
            val available=if(direction<0) x-density else view.width-density-x
            val step=if(options.size>1) min(cell,available/options.lastIndex) else cell
            val target=(x+direction*index*step).coerceIn(0f,view.width-1f)
            event(MotionEvent.ACTION_MOVE,target)
            if(shot!=null) capture(shot)
            event(MotionEvent.ACTION_UP,target)
            check(typed.toString()==options[index]) { "${view.previewLayout!!.id}/${prefs.setupLanguage}, key $id option $index at width ${view.width}, x=$x target=$target: $typed, expected ${options[index]}" }
            selections++
        }
        try {
            for(width in listOf(320,600)) {
                for(layout in KeyboardLayouts.all) {
                    test.runOnMainSync {
                        prefs.setupLanguage="English";view.previewLayout=layout;view.suggestionsVisible=width==320
                        view.measure(View.MeasureSpec.makeMeasureSpec((width*density).roundToInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                        view.layout(0,0,view.measuredWidth,view.measuredHeight);view.editor(false,"Done",false)
                    }
                    var id=10
                    for((rowIndex,row) in layout.rows.withIndex()) {
                        if(rowIndex==layout.rows.lastIndex) id++
                        for(code in row) {
                            val options=layout.variants(code).ifEmpty {
                                if(layout.latin && !layout.phonetic && layout.composer!="dead") code.singleOrNull()?.let { LatinAccents.options(it,"English") }.orEmpty() else emptyList()
                            }
                            if(options.isNotEmpty()) {
                                val indices=if(width==320) options.indices.toList() else listOf(options.lastIndex)
                                for(index in indices) select(id,options,index,if(width==320 && layout.id=="qwerty" && code=="o" && options[index]=="ó") "o-acute" else null)
                            }
                            id++
                        }
                    }
                    if(width==320 && layout.id=="intl") capture("intl-labels")
                }
            }
            test.runOnMainSync {
                view.previewLayout=KeyboardLayouts.find("qwerty");view.suggestionsVisible=true
                view.measure(View.MeasureSpec.makeMeasureSpec((320*density).roundToInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,view.measuredWidth,view.measuredHeight)
            }
            for(name in LanguageLayouts.all.keys) {
                test.runOnMainSync { prefs.setupLanguage=name;view.editor(false,"Done",false) }
                val options=LatinAccents.options('o',name)
                for(index in options.indices) select(18,options,index,if(name=="Vietnamese" && index==options.lastIndex) "vietnamese-o" else null)
                test.runOnMainSync { view.editor(false,"Done",true) }
                select(18,options.map { it.uppercase() },options.lastIndex)
            }
            return JSONObject().put("layouts",KeyboardLayouts.all.size).put("languages",LanguageLayouts.all.size)
                .put("selections",selections).put("widths_dp","320,600").put("uppercase",true).put("finger_stays_on_screen",true)
        } finally { test.runOnMainSync { view.cancelTouches();prefs.setupLanguage=language } }
    }
}
