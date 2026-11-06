package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.widget.EditText

open class SpeedEditor(context:Context,private val passage:String,private val ink:Int,private val accent:Int):EditText(context) {
    private var shown:String?=null
    private var available=0
    private var preview:StaticLayout?=null
    private var caret=-1
    private var cursorOn=true
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    private val blink=object:Runnable {
        override fun run() { cursorOn=!cursorOn;invalidate();postDelayed(this,500) }
    }
    init { hint=passage;isCursorVisible=false;contentDescription="Speed test typing area" }
    private fun layoutText():StaticLayout? {
        val width=width-compoundPaddingLeft-compoundPaddingRight
        if(width<=0) return null
        val typed=text.toString()
        if(shown!=typed || available!=width) {
            val display=SpannableString(typed+passage.drop(typed.length))
            var start=0
            while(start<typed.length) {
                val correct=start<passage.length && typed[start]==passage[start]
                var end=start+1
                while(end<typed.length && (end<passage.length && typed[end]==passage[end])==correct) end++
                display.setSpan(ForegroundColorSpan(if(correct) ink else accent),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(!correct) display.setSpan(BackgroundColorSpan((accent and 0xffffff) or 0x22000000),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                start=end
            }
            val paint=TextPaint(this.paint).apply { color=(ink and 0xffffff) or 0x44000000 }
            preview=StaticLayout.Builder.obtain(display,0,display.length,paint,width)
                .setTextDirection(if(layoutDirection==LAYOUT_DIRECTION_RTL) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(includeFontPadding).setLineSpacing(lineSpacingExtra,lineSpacingMultiplier)
                .setBreakStrategy(breakStrategy).setHyphenationFrequency(hyphenationFrequency).build()
            shown=typed;available=width;caret=-1
        }
        return preview
    }
    override fun onDraw(canvas:Canvas) {
        val lines=layoutText() ?: return
        val position=selectionEnd.coerceIn(0,text.length)
        val line=lines.getLineForOffset(position)
        val oldScroll=scrollY
        if(caret!=position) {
            val room=(height-compoundPaddingTop-compoundPaddingBottom).coerceAtLeast(1)
            val top=lines.getLineTop(line);val bottom=lines.getLineBottom(line)
            val next=when { top<scrollY -> top;bottom>scrollY+room -> bottom-room;else -> scrollY }
            scrollTo(0,next.coerceAtLeast(0));caret=position
        }
        canvas.save()
        canvas.clipRect(scrollX+compoundPaddingLeft,oldScroll+compoundPaddingTop,scrollX+width-compoundPaddingRight,oldScroll+height-compoundPaddingBottom)
        canvas.translate(compoundPaddingLeft.toFloat(),(compoundPaddingTop+oldScroll-scrollY).toFloat())
        lines.draw(canvas)
        if(cursorOn && hasFocus() && isEnabled && selectionStart==selectionEnd) {
            pen.color=accent;pen.strokeWidth=resources.displayMetrics.density*1.5f
            val x=lines.getPrimaryHorizontal(position)
            canvas.drawLine(x,lines.getLineTop(line).toFloat(),x,lines.getLineBottom(line).toFloat(),pen)
        }
        canvas.restore()
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        val handled=super.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP) layoutText()?.let { lines ->
            val line=lines.getLineForVertical((event.y+scrollY-compoundPaddingTop).toInt())
            val offset=lines.getOffsetForHorizontal(line,event.x+scrollX-compoundPaddingLeft)
            setSelection(offset.coerceIn(0,text.length))
        }
        return handled
    }
    override fun onSelectionChanged(start:Int,end:Int) {
        super.onSelectionChanged(start,end);cursorOn=true
        if(isAttachedToWindow) { removeCallbacks(blink);postDelayed(blink,500);invalidate() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();postDelayed(blink,500) }
    override fun onDetachedFromWindow() { removeCallbacks(blink);super.onDetachedFromWindow() }
}
