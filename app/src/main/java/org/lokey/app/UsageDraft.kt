package org.lokey.app

/** Only characters delivered by our own key events may become observations. */
class UsageDraft {
    var value=""
        private set
    private var blocked=false
    fun clear() { value="";blocked=false }
    fun block() { clear();blocked=true }
    fun delete() { if(value.isNotEmpty()) value=value.dropLast(1) else blocked=true }
    fun input(text:String):String? {
        if(text.length!=1) { block();return null }
        if(text[0].isWhitespace()) {
            val word=if(blocked) null else value.trimEnd('.',',','!','?',';',':','…')
            clear();return word?.takeIf { it.isNotEmpty() }
        }
        if(!blocked) { if(value.length<48) value+=text else block() }
        return null
    }
}
