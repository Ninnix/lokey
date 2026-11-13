package org.lokey.app

import android.view.inputmethod.InputConnection

class Autocorrect {
    private data class Undo(val before: String, val after: String, val position: Int, val inserted: String, val original: String)
    private data class Pending(val before:String,val after:String,val position:Int,val boundary:String)
    private var undo: Undo? = null
    private var pending:Pending?=null
    private var rejected = ""
    fun clear() { undo = null;pending=null }
    fun reset() { clear(); rejected = "" }
    fun defer(connection:InputConnection,text:String,enabled:Boolean):String? {
        if(!enabled || !boundary(text)) return null
        val before=connection.getTextBeforeCursor(320,0)?.toString() ?: return null
        val suffix=before.takeLastWhile { boundary(it.toString()) }
        if(!before.endsWith(text) || Languages.prefix(before.dropLast(suffix.length)).length !in 1..24) return null
        val after=connection.getTextAfterCursor(80,0)?.toString() ?: return null
        val position=Cursor.position(connection)
        if(position<0 || !connection.getSelectedText(0).isNullOrEmpty()) return null
        pending=Pending(before,after,position,suffix)
        return before.dropLast(suffix.length).takeLast(240)
    }
    fun finish(connection:InputConnection,result:Prediction.Result?):Boolean {
        val item=pending ?: return false
        pending=null
        if(Cursor.position(connection)!=item.position || connection.getTextBeforeCursor(320,0)?.toString()!=item.before ||
            connection.getTextAfterCursor(80,0)?.toString()!=item.after) return false
        return apply(connection,item.boundary,result,true,true)
    }
    fun commit(connection: InputConnection, text: String, result: Prediction.Result?, enabled: Boolean): Boolean {
        return apply(connection,text,result,enabled,false)
    }
    fun boundary(text:String)=text.isNotEmpty() && text.all { it in " \t.,!?;:\n…" }
    private fun apply(connection:InputConnection,text:String,result:Prediction.Result?,enabled:Boolean,insertedBoundary:Boolean):Boolean {
        clear()
        if (!enabled || !boundary(text) || result?.correction == null) return false
        val current = connection.getTextBeforeCursor(320, 0)?.toString() ?: return false
        if(insertedBoundary && !current.endsWith(text)) return false
        val before=if(insertedBoundary) current.dropLast(text.length) else current
        val after = connection.getTextAfterCursor(80, 0)?.toString() ?: return false
        val word = result.spelling?.takeIf { it.word==result.correction }?.source ?: Languages.prefix(before)
        if(!before.endsWith(word)) return false
        if (word == rejected) return false
        rejected = ""
        val start = before.length - word.length
        val caret = Cursor.position(connection)
        if (caret < 0 || !connection.getSelectedText(0).isNullOrEmpty() || before.takeLast(240) != result.before || word.isEmpty() ||
            start > 0 && !before[start - 1].isWhitespace() || after.firstOrNull()?.let { Languages.isWord(it) } == true) return false
        val inserted = result.correction + text
        connection.beginBatchEdit()
        connection.finishComposingText()
        val length=word.length+if(insertedBoundary) text.length else 0
        val removed = connection.deleteSurroundingText(length, 0)
        val committed = if (removed) connection.commitText(inserted, 1) else false
        connection.endBatchEdit()
        if (!committed) return false
        val expected = connection.getTextBeforeCursor(320, 0)?.toString() ?: return true
        undo = Undo(expected, after, caret - length + inserted.length, inserted, word)
        return true
    }
    fun revert(connection: InputConnection,reject:(String)->Unit={}): Boolean {
        val item = undo ?: return false
        clear()
        if (Cursor.position(connection) != item.position || connection.getTextBeforeCursor(320, 0)?.toString() != item.before ||
            connection.getTextAfterCursor(80, 0)?.toString() != item.after) return false
        connection.beginBatchEdit()
        connection.finishComposingText()
        connection.deleteSurroundingText(item.inserted.length, 0)
        connection.commitText(item.original, 1)
        rejected = item.original
        reject(item.original)
        connection.endBatchEdit()
        return true
    }
}
