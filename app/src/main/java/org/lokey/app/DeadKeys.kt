package org.lokey.app

import java.text.Normalizer

class DeadKeys {
    var pending=""
        private set
    fun clear() { pending="" }
    fun delete():Boolean { if(pending.isEmpty()) return false;clear();return true }
    fun input(value:String):String {
        val previous=pending;clear()
        if(previous.isNotEmpty()) {
            if(value==" " || value==previous) return previous
            val mark=marks[previous]!!
            val combined=if(value.length==1) Normalizer.normalize(value+mark,Normalizer.Form.NFC) else ""
            return if(combined.length==1) combined else previous+value
        }
        if(value in marks) { pending=value;return "" }
        return value
    }
    companion object {
        private val marks=mapOf("'" to "\u0301","`" to "\u0300","\"" to "\u0308","^" to "\u0302","~" to "\u0303")
    }
}
