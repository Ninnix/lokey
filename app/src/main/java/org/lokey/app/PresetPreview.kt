package org.lokey.app

import android.content.Context
import android.view.View
import android.widget.FrameLayout

class PresetPreview(context:Context,prefs:Prefs,theme:String,private val maxHeightDp:Int=Int.MAX_VALUE,design:DesignPresets.Design?=null,selected:()->Unit):FrameLayout(context) {
    private val fullWidth=resources.displayMetrics.widthPixels
    private val fullHeight=(325*resources.displayMetrics.density).toInt()
    private val keyboard=KeyboardView(context,prefs,object:KeyboardView.Listener {
        override fun text(text:String) { }
        override fun delete() { }
        override fun enter() { }
        override fun cursor(delta:Int) { }
        override fun voice(start:Boolean)=false
        override fun cancelVoice() { }
        override fun nextKeyboard() { }
        override fun candidate(word:String) { }
        override fun feedback(key:String,down:Boolean) { }
    }).apply {
        previewTheme=theme;previewDesign=design;selectPreview=selected;pivotX=0f;pivotY=0f
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        editor(false,"Done",false)
    }
    init { clipChildren=true;addView(keyboard,LayoutParams(fullWidth,fullHeight)) }
    fun refresh() { keyboard.editor(false,"Done",false);requestLayout();invalidate() }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        keyboard.measure(MeasureSpec.makeMeasureSpec(fullWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(fullHeight,MeasureSpec.EXACTLY))
        val limit=if(maxHeightDp==Int.MAX_VALUE) Int.MAX_VALUE else (maxHeightDp*resources.displayMetrics.density).toInt()
        setMeasuredDimension(width,minOf(limit,(fullHeight*width.toFloat()/fullWidth).toInt()))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        keyboard.layout(0,0,fullWidth,fullHeight)
        val scale=minOf(width.toFloat()/fullWidth,height.toFloat()/fullHeight)
        keyboard.scaleX=scale;keyboard.scaleY=scale;keyboard.translationX=(width-fullWidth*scale)/2
    }
}
