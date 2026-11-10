package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

object LabelChecks {
    private data class Ink(val text:String,val bounds:RectF)
    private class RecordingCanvas(bitmap:Bitmap):Canvas(bitmap) {
        val labels=ArrayList<Ink>()
        override fun drawText(text:String,x:Float,y:Float,paint:Paint) {
            val rect=Rect();paint.getTextBounds(text,0,text.length,rect)
            val offset=when(paint.textAlign) { Paint.Align.RIGHT -> paint.measureText(text);Paint.Align.CENTER -> paint.measureText(text)/2;else -> 0f }
            if(!rect.isEmpty) labels.add(Ink(text,RectF(rect).apply { offset(x-offset,y) }))
            super.drawText(text,x,y,paint)
        }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val sandbox=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("label-check-$name",mode)
        }
        val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().commit()
        val prefs=Prefs(sandbox);prefs.layout="qwerty"
        val field=KeyboardView::class.java.getDeclaredField("keys").apply { isAccessible=true }
        val density=context.resources.displayMetrics.density
        val pixel=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        val errors=JSONArray();var failures=0;var cases=0;var labels=0;var pairs=0
        fun error(message:String) { failures++;if(errors.length()<20) errors.put(message) }
        val layouts=KeyboardLayouts.all.flatMap { layout ->
            prefs.layout=layout.id
            listOf(layout,CustomLayouts.create(prefs).layout())
        }
        lateinit var view:KeyboardView;var viewport=0
        fun createView(width:Int):KeyboardView {
            val config=Configuration(context.resources.configuration).apply {
                screenWidthDp=width;screenHeightDp=if(width==600) 320 else 800
                orientation=if(width==600) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            return KeyboardView(context.createConfigurationContext(config),prefs,object:KeyboardView.Listener {
                override fun text(text:String) {};override fun delete() {};override fun enter() {};override fun cursor(delta:Int) {}
                override fun voice(start:Boolean)=false;override fun cancelVoice() {};override fun nextKeyboard() {}
                override fun candidate(word:String) {};override fun feedback(key:String,down:Boolean) {}
            })
        }
        try {
            for(layout in layouts) for(width in listOf(320,600)) for(height in listOf(85,100,120))
                for(mode in listOf("letters","upper","symbols","symbols2","numeric")) for(adaptive in listOf(false,true)) for(suggestions in listOf(false,true)) test.runOnMainSync {
                    if(viewport!=width) { view=createView(width);viewport=width }
                    prefs.height=height;prefs.adaptive=adaptive;view.previewLayout=null;prefs.layout=layout.id
                    view.previewLayout=layout.takeIf { it.keyRows.isNotEmpty() };view.suggestionsVisible=suggestions
                    view.editor(mode=="numeric","Done",mode=="upper")
                    view.measure(View.MeasureSpec.makeMeasureSpec((width*density).roundToInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight)
                    @Suppress("UNCHECKED_CAST") fun keys()=(field.get(view) as List<KeyboardView.Key>).toList()
                    if(mode.startsWith("symbols")) {
                        view.accessibilityNodeProvider.performAction(keys().indexOfFirst { it.code=="symbols" },AccessibilityNodeInfo.ACTION_CLICK,null)
                        if(mode=="symbols2") view.accessibilityNodeProvider.performAction(keys().indexOfFirst { it.code=="page" },AccessibilityNodeInfo.ACTION_CLICK,null)
                        view.measure(View.MeasureSpec.makeMeasureSpec((width*density).roundToInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                        view.layout(0,0,view.measuredWidth,view.measuredHeight)
                    }
                    view.prediction(emptyList(),FloatArray(26).apply { this[4]=1f },true,"en")
                    val canvas=RecordingCanvas(pixel);view.draw(canvas)
                    val name="${layout.id}/$width/$height/$mode/$adaptive/$suggestions"
                    val keys=keys();val groups=HashMap<KeyboardView.Key,MutableList<Ink>>()
                    for(ink in canvas.labels) {
                        val key=keys.firstOrNull { it.rect.contains(ink.bounds.centerX(),ink.bounds.centerY()) } ?: continue
                        labels++
                        val outer=RectF(key.rect).apply { inset(-1f,-1f) }
                        if(!outer.contains(ink.bounds)) error("Outside key $name/${key.code}: ${ink.text} ${ink.bounds} vs ${key.rect}")
                        groups.getOrPut(key) { ArrayList() }.add(ink)
                    }
                    for((key,ink) in groups) for(i in ink.indices) for(j in i+1 until ink.size) {
                        pairs++
                        if(RectF.intersects(ink[i].bounds,ink[j].bounds)) error("Overlap $name/${key.code}: ${ink[i]} / ${ink[j]}")
                    }
                    for(key in keys) if(key.code !in listOf("shift","delete","enter","space") && key.label.isNotBlank()) {
                        if(groups[key].orEmpty().none { it.text==key.label }) error("Missing primary label $name/${key.code}: ${key.label}")
                    }
                    if(layout.id=="intl" && height in listOf(85,120) && mode=="letters" && !adaptive && suggestions ||
                        layout.id in listOf("turkish","thai","arabic") && width==320 && height==85 && mode=="upper" && !adaptive && suggestions) {
                        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
                        File(context.filesDir,"labels-${layout.id}-$width-$height.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                    }
                    cases++
                }
        } finally { test.runOnMainSync { view.cancelTouches() };pixel.recycle();store.edit().clear().commit() }
        val report=JSONObject().put("cases",cases).put("layouts",KeyboardLayouts.all.size).put("custom_clones",KeyboardLayouts.all.size)
            .put("labels",labels).put("label_pairs",pairs).put("failures",failures).put("examples",errors)
            .put("widths_dp",JSONArray(listOf(320,600))).put("heights_percent",JSONArray(listOf(85,100,120)))
            .put("orientations","portrait,landscape")
        File(context.filesDir,"label-fit-details.json").writeText(report.toString(2))
        check(failures==0) { "Key labels overlap or overflow: $report" }
        check(labels>10000 && pairs>1000) { "Label recording did not exercise keyboard drawing" }
        return report
    }
}
