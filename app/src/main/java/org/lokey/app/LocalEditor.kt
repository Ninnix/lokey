package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.EditText

class LocalEditor(context:Context):EditText(context) {
    var changed:(()->Unit)?=null
    private var cursorOn=true
    private val cursorPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val blink=object:Runnable {
        override fun run() { cursorOn=!cursorOn;invalidate();postDelayed(this,500) }
    }
    // This draft belongs to the IME; it must never become Android's served editor.
    val connection:InputConnection=object:BaseInputConnection(this,true) {
        override fun getEditable():Editable=this@LocalEditor.text
        override fun getExtractedText(request:ExtractedTextRequest?,flags:Int)=ExtractedText().apply {
            text=this@LocalEditor.text.toString();startOffset=0
            selectionStart=this@LocalEditor.selectionStart;selectionEnd=this@LocalEditor.selectionEnd
        }
    }
    override fun onCheckIsTextEditor()=false
    override fun onCreateInputConnection(info:EditorInfo):InputConnection?=null
    init {
        showSoftInputOnFocus=false
        isCursorVisible=false
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { }
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { }
            override fun afterTextChanged(value:Editable?) { changed?.invoke() }
        })
    }
    private fun resetCursor() {
        cursorOn=true
        if(isAttachedToWindow) { removeCallbacks(blink);postDelayed(blink,500);invalidate() }
    }
    override fun onSelectionChanged(start:Int,end:Int) { super.onSelectionChanged(start,end);resetCursor();changed?.invoke() }
    override fun onAttachedToWindow() { super.onAttachedToWindow();resetCursor() }
    override fun onDetachedFromWindow() { removeCallbacks(blink);super.onDetachedFromWindow() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(!cursorOn || selectionStart!=selectionEnd || selectionEnd<0) return
        val lines=layout ?: return
        val offset=selectionEnd.coerceAtMost(text.length)
        val line=lines.getLineForOffset(offset)
        val x=compoundPaddingLeft+lines.getPrimaryHorizontal(offset)
        val y=totalPaddingTop
        cursorPaint.color=currentTextColor;cursorPaint.strokeWidth=resources.displayMetrics.density*1.5f
        canvas.save()
        canvas.clipRect(scrollX+compoundPaddingLeft,scrollY+extendedPaddingTop,scrollX+width-compoundPaddingRight,scrollY+height-extendedPaddingBottom)
        canvas.drawLine(x,y+lines.getLineTop(line).toFloat(),x,y+lines.getLineBottom(line).toFloat(),cursorPaint)
        canvas.restore()
    }
}
