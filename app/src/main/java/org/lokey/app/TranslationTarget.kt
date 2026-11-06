package org.lokey.app

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

class TranslationTarget private constructor(
    private val before:String,private val after:String,private var start:Int,private var end:Int,
    private var current:String,val source:String
) {
    private var first=true
    fun matches(connection:InputConnection):Boolean {
        val selection=selection(connection) ?: return false
        if(selection.first!=start || selection.second!=end) return false
        val previous=connection.getTextBeforeCursor(before.length+(if(first) 0 else current.length),0)?.toString() ?: return false
        return previous==before+(if(first) "" else current) && connection.getTextAfterCursor(after.length,0)?.toString()==after &&
            connection.getSelectedText(0)?.toString().orEmpty()==(if(first) source else "")
    }
    fun write(connection:InputConnection,translation:String):Boolean {
        if(!matches(connection)) return false
        val value=if(translation.isEmpty()) "" else source.takeWhile { it.isWhitespace() }+translation.trim()+source.takeLastWhile { it.isWhitespace() }
        if(value==current && !first) return true
        connection.beginBatchEdit()
        val removed=first || current.isEmpty() || connection.deleteSurroundingText(current.length,0)
        val committed=removed && connection.commitText(value,1)
        connection.endBatchEdit()
        if(!committed) return false
        val origin=if(first) minOf(start,end) else start-current.length
        start=origin+value.length;end=start;current=value;first=false
        return true
    }
    companion object {
        private fun selection(connection:InputConnection):Pair<Int,Int>? {
            if(android.os.Build.VERSION.SDK_INT>=31) connection.getSurroundingText(0,0,0)?.let {
                if(it.offset>=0) return it.offset+it.selectionStart to it.offset+it.selectionEnd
            }
            val value=connection.getExtractedText(ExtractedTextRequest(),0) ?: return null
            return value.startOffset+value.selectionStart to value.startOffset+value.selectionEnd
        }
        fun capture(connection:InputConnection):TranslationTarget? {
            val selection=selection(connection) ?: return null
            val before=connection.getTextBeforeCursor(128,0)?.toString() ?: return null
            val after=connection.getTextAfterCursor(128,0)?.toString() ?: return null
            val selected=connection.getSelectedText(0)?.toString().orEmpty()
            return TranslationTarget(before,after,selection.first,selection.second,selected,selected)
        }
    }
}
