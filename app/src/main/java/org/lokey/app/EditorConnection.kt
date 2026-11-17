package org.lokey.app

import android.os.Handler
import android.text.TextUtils
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText

// Share one atomic editor read within a main-thread turn, never across edits or callbacks.
@android.annotation.TargetApi(31)
class EditorConnection(val target:InputConnection,private val main:Handler):InputConnectionWrapper(target,false) {
    private var text:SurroundingText?=null
    private var supported=true
    private var readStart=-1
    private var readEnd=-1
    private val expire=Runnable { text=null }
    fun invalidate() { text=null;readStart=-1;readEnd=-1;main.removeCallbacks(expire) }
    fun selectionMatches(start:Int,end:Int)=readStart>=0 && start==readStart && end==readEnd
    private fun snapshot():SurroundingText? {
        if(!supported) return null
        text?.let { return it }
        val value=super.getSurroundingText(320,80,0)
        if(value==null) { supported=false;return null }
        if(value.offset>=0) { readStart=value.offset+value.selectionStart;readEnd=value.offset+value.selectionEnd }
        text=value;main.post(expire)
        return value
    }
    override fun getTextBeforeCursor(length:Int,flags:Int):CharSequence? {
        if(flags!=0 || length<0 || length>320) return super.getTextBeforeCursor(length,flags)
        val value=snapshot() ?: return super.getTextBeforeCursor(length,flags)
        val end=minOf(value.selectionStart,value.selectionEnd)
        return value.text.subSequence((end-length).coerceAtLeast(0),end)
    }
    override fun getTextAfterCursor(length:Int,flags:Int):CharSequence? {
        if(flags!=0 || length<0 || length>80) return super.getTextAfterCursor(length,flags)
        val value=snapshot() ?: return super.getTextAfterCursor(length,flags)
        val start=maxOf(value.selectionStart,value.selectionEnd)
        return value.text.subSequence(start,(start+length).coerceAtMost(value.text.length))
    }
    override fun getSelectedText(flags:Int):CharSequence? {
        if(flags!=0) return super.getSelectedText(flags)
        val value=snapshot() ?: return super.getSelectedText(flags)
        return value.text.subSequence(minOf(value.selectionStart,value.selectionEnd),maxOf(value.selectionStart,value.selectionEnd))
    }
    override fun getSurroundingText(before:Int,after:Int,flags:Int):SurroundingText? {
        if(flags!=0 || before<0 || after<0 || before>320 || after>80) return super.getSurroundingText(before,after,flags)
        val value=snapshot() ?: return null
        val start=(minOf(value.selectionStart,value.selectionEnd)-before).coerceAtLeast(0)
        val end=(maxOf(value.selectionStart,value.selectionEnd)+after).coerceAtMost(value.text.length)
        return SurroundingText(value.text.subSequence(start,end),value.selectionStart-start,value.selectionEnd-start,
            if(value.offset<0) -1 else value.offset+start)
    }
    override fun getCursorCapsMode(types:Int):Int {
        val value=snapshot() ?: return super.getCursorCapsMode(types)
        return TextUtils.getCapsMode(value.text,minOf(value.selectionStart,value.selectionEnd),types)
    }
    override fun commitText(value:CharSequence?,position:Int):Boolean { invalidate();return super.commitText(value,position) }
    override fun setComposingText(value:CharSequence?,position:Int):Boolean { invalidate();return super.setComposingText(value,position) }
    override fun finishComposingText():Boolean { invalidate();return super.finishComposingText() }
    override fun deleteSurroundingText(before:Int,after:Int):Boolean { invalidate();return super.deleteSurroundingText(before,after) }
    override fun deleteSurroundingTextInCodePoints(before:Int,after:Int):Boolean { invalidate();return super.deleteSurroundingTextInCodePoints(before,after) }
    override fun setSelection(start:Int,end:Int):Boolean { invalidate();return super.setSelection(start,end) }
}
