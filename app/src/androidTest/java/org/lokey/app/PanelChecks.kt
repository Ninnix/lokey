package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.LocaleList
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.Locale

object PanelChecks {
    private data class Node(val id:Int,val label:String,val bounds:Rect)

    fun run(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context)
        val originalLayout=if(prefs.automaticLayout) "auto" else prefs.layout
        val originalSplit=prefs.splitLandscape
        val originalCustom=prefs.customLayouts
        val originalAdaptive=prefs.adaptive
        val typed=StringBuilder();val symbols=StringBuilder();val selected=ArrayList<String>()
        var entered=0
        val listener=object:KeyboardView.Listener {
            override fun text(text:String) { typed.append(text) }
            override fun symbol(text:String) { symbols.append(text);text(text) }
            override fun delete() { if(typed.isNotEmpty()) typed.deleteCharAt(typed.lastIndex) }
            override fun enter() { entered++ }
            override fun cursor(delta:Int) {}
            override fun voice(start:Boolean)=false
            override fun cancelVoice() {}
            override fun nextKeyboard() {}
            override fun candidate(word:String) { selected.add(word) }
            override fun feedback(key:String,down:Boolean) {}
        }
        fun measure(view:KeyboardView,width:Int=view.width) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            view.layout(0,0,width,view.measuredHeight)
        }
        fun view(wide:Boolean):KeyboardView {
            val config=Configuration().apply {
                orientation=if(wide) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                setLocales(LocaleList(Locale.US))
            }
            val ctx=context.createConfigurationContext(config)
            val width=((if(wide) 780 else 385)*ctx.resources.displayMetrics.density).toInt()
            return KeyboardView(ctx,prefs,listener).also { measure(it,width) }
        }
        fun nodes(view:KeyboardView):List<Node> {
            val provider=view.accessibilityNodeProvider
            return generateSequence(0) { it+1 }.map { id -> provider.createAccessibilityNodeInfo(id)?.let { node ->
                Node(id,node.contentDescription.toString().substringBefore(", hold for ").substringBefore(", hold to "),Rect().apply { node.getBoundsInParent(this) })
            } }.takeWhile { it!=null }.filterNotNull().toList()
        }
        fun press(view:KeyboardView,label:String,hold:Boolean=false) {
            val node=nodes(view).firstOrNull { it.label==label } ?: error("Missing $label")
            val action=if(hold) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            view.accessibilityNodeProvider.performAction(node.id,action,null)
        }
        fun tap(view:KeyboardView,x:Float,y:Float) {
            val now=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(now,now,action,x,y,0)
                view.onTouchEvent(event);event.recycle()
            }
        }
        fun tap(view:KeyboardView,node:Node)=tap(view,node.bounds.exactCenterX(),node.bounds.exactCenterY())
        var result:Result<JSONObject>?=null
        test.runOnMainSync {
            result=runCatching {
                try {
                    prefs.splitLandscape=true
                    val portrait=view(false)
                    val landscape=view(true)
                    fun checkSplit(view:KeyboardView) {
                        val keys=nodes(view)
                        val center=view.width/2
                        val spaces=keys.filter { it.label=="Space" }
                        check(spaces.size==2 && spaces[0].bounds.right<center && spaces[1].bounds.left>center)
                        check(keys.none { center in it.bounds.left..it.bounds.right })
                        val before=typed.length
                        tap(view,center.toFloat(),spaces[0].bounds.exactCenterY())
                        check(typed.length==before) { "Center gap registered a key" }
                        spaces.forEach { tap(view,it) }
                        check(typed.substring(before)=="  ")
                    }
                    for(layout in KeyboardLayouts.all) {
                        prefs.layout=layout.id
                        portrait.editor(false,"Done",false)
                        press(portrait,"?123")
                        val question=if(layout.behaviorId=="greek") "?" else layout.punctuation("?")
                        val label=nodes(portrait).single { it.label==question }
                        symbols.clear();tap(portrait,label)
                        check(symbols.toString()==question) { "${layout.id} emitted $symbols" }
                        press(portrait,question,true)
                        check(symbols.toString()==question+when(question) { "?" -> if(layout.behaviorId=="greek") ";" else "¿";else -> "?" })
                        if(layout.behaviorId=="arabic") {
                            press(portrait,"1",true)
                            check(symbols.endsWith("١"))
                        }
                        press(portrait,"=/<");press(portrait,"¡")
                        check(symbols.endsWith("¡"))
                        press(portrait,"ABC");press(portrait,"?123")
                        check(nodes(portrait).any { it.label==question })
                        val labels=nodes(portrait).map { it.label }
                        check(labels.count { it=="Space" }==1 && labels.containsAll(listOf("$","€")))
                        landscape.editor(false,"Done",false);checkSplit(landscape)
                        press(landscape,"?123");checkSplit(landscape);press(landscape,question)
                        check(symbols.endsWith(question))
                        press(landscape,"=/<");checkSplit(landscape)
                    }
                    prefs.layout="intl"
                    portrait.editor(false,"Done",true)
                    press(portrait,"?123");symbols.clear()
                    listOf("'","\"").forEach { press(portrait,it) }
                    press(portrait,"*",true);press(portrait,"?")
                    check(symbols.toString()=="'\"^?")
                    val custom=CustomLayouts.create(prefs).copy(name="Panel test")
                    CustomLayouts.save(prefs,custom);prefs.layout=custom.id
                    landscape.editor(false,"Done",false);checkSplit(landscape)
                    press(landscape,"?123");checkSplit(landscape);press(landscape,"?")

                    prefs.layout="qwerty"
                    landscape.editor(false,"Done",false)
                    prefs.adaptive=true
                    val edge=nodes(landscape).first { it.label=="y" }.bounds.left
                    landscape.prediction(emptyList(),FloatArray(26).apply { this['y'-'a']=1f },true,"en")
                    check(nodes(landscape).first { it.label=="y" }.bounds.left==edge)
                    checkSplit(landscape)
                    val letters=nodes(landscape)
                    val q=letters.first { it.label=="q" }
                    val p=letters.first { it.label=="p" }
                    val pointers=listOf(q,p).map { node ->
                        MotionEvent.PointerCoords().apply { x=node.bounds.exactCenterX();y=node.bounds.exactCenterY();pressure=1f }
                    }.toTypedArray()
                    val ids=Array(2) { i -> MotionEvent.PointerProperties().apply { id=i;toolType=MotionEvent.TOOL_TYPE_FINGER } }
                    val now=SystemClock.uptimeMillis()
                    typed.clear()
                    for((action,count) in listOf(MotionEvent.ACTION_DOWN to 1,
                        (MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to 2,
                        (MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to 2,MotionEvent.ACTION_UP to 1)) {
                        val event=MotionEvent.obtain(now,now,action,count,ids,pointers,0,0,1f,1f,0,0,0,0)
                        landscape.onTouchEvent(event);event.recycle()
                    }
                    check(typed.toString()=="qp") { "Two-thumb taps registered $typed" }

                    val words=listOf("first","second","third","fourth","fifth")
                    for(panel in listOf(landscape,portrait)) {
                        val split=panel===landscape
                        panel.editor(false,"Done",false);panel.prediction(words,FloatArray(26),false)
                        val provider=panel.accessibilityNodeProvider
                        val limit=if(split) 4 else 3
                        check(provider.createAccessibilityNodeInfo(1000+limit)==null)
                        val bounds=(0 until limit).map { i ->
                            Rect().apply { provider.createAccessibilityNodeInfo(1000+i)!!.getBoundsInParent(this) }
                        }
                        val image=Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
                        panel.draw(Canvas(image))
                        if(split) {
                            val center=panel.width/2
                            check(bounds.take(2).all { it.right<center } && bounds.drop(2).all { it.left>center })
                            check((0 until image.height).all { y -> Color.alpha(image.getPixel(center,y))==0 })
                            check(bounds.all { Color.alpha(image.getPixel(it.centerX(),it.centerY()))==255 })
                            val density=panel.resources.displayMetrics.density
                            val edge=(minOf(340*density,panel.width*.39f)-3*density).toInt()
                            for(x in listOf(edge,panel.width-edge)) {
                                check(Color.alpha(image.getPixel(x,density.toInt()))==0)
                                check(Color.alpha(image.getPixel(x,image.height/2))==255)
                            }
                            tap(panel,center.toFloat(),bounds[0].exactCenterY())
                        } else check(Color.alpha(image.getPixel(panel.width/2,bounds[0].centerY()))==255)
                        image.recycle()
                        bounds.forEach { tap(panel,it.exactCenterX(),it.exactCenterY()) }
                        check(selected==words.take(limit));selected.clear()
                        check(provider.performAction(1000+limit-1,AccessibilityNodeInfo.ACTION_CLICK,null))
                        check(selected==listOf(words[limit-1]));selected.clear()
                    }
                    prefs.splitLandscape=false;landscape.updateGeometry()
                    check(nodes(landscape).count { it.label=="Space" }==1)
                    prefs.splitLandscape=true;landscape.updateGeometry();checkSplit(landscape)

                    val number=InputType.TYPE_CLASS_NUMBER
                    for(panel in listOf(portrait,landscape)) {
                        val textHeight=panel.height
                        for(type in listOf(number,number or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                            number or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL,InputType.TYPE_CLASS_PHONE)) {
                            panel.editor(type,"Done",false,',')
                            panel.prediction(words,FloatArray(26),false)
                            measure(panel)
                            check(panel.height<textHeight)
                            check(panel.accessibilityNodeProvider.createAccessibilityNodeInfo(1000)==null)
                            val keys=nodes(panel)
                            val digits=keys.filter { it.label.length==1 && it.label[0].isDigit() }
                            check(digits.first().bounds.top==0)
                            check(digits.groupBy { it.bounds.top }.values.map { row -> row.joinToString("") { it.label } }==listOf("123","456","789","0"))
                            check(digits[1].bounds.centerX()==digits[9].bounds.centerX())
                            check(keys.none { it.label=="Space" || it.label=="?123" })
                            typed.clear();digits.forEach { tap(panel,it) }
                            check(typed.toString()=="1234567890")
                            val delete=keys.single { it.label=="Backspace" }
                            val done=keys.single { it.label=="Done" }
                            check(delete.bounds.centerX()==done.bounds.centerX() && delete.bounds.top==digits[6].bounds.top)
                            tap(panel,delete);tap(panel,done)
                            check(typed.toString()=="123456789")
                            when(type) {
                                number,number or InputType.TYPE_NUMBER_VARIATION_PASSWORD ->
                                    check(keys.size==12)
                                InputType.TYPE_CLASS_PHONE -> {
                                    typed.clear();listOf("+","*","#").forEach { press(panel,it) }
                                    check(typed.toString()=="+*#")
                                }
                                else -> {
                                    typed.clear();press(panel,"-");press(panel,",");press(panel,".")
                                    check(typed.toString()=="-,.")
                                }
                            }
                        }
                    }
                    check(entered==8)
                    JSONObject().put("built_in_layouts",KeyboardLayouts.all.size).put("custom_layout",true)
                        .put("landscape_split_both_pages",true).put("two_thumb_events",true).put("numeric_field_types",4)
                        .put("transparent_gap",true).put("split_suggestions",4)
                } finally {
                    prefs.layout=originalLayout;prefs.customLayouts=originalCustom;prefs.splitLandscape=originalSplit;prefs.adaptive=originalAdaptive
                }
            }
        }
        return result!!.getOrThrow()
    }
}
