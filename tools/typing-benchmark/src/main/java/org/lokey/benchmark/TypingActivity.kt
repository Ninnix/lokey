package org.lokey.benchmark

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class TypingActivity:Activity() {
    lateinit var field:View
    @Volatile var focused=false
    var value=""
    var changes=ArrayList<Pair<Long,String>>()
    val reads=HashMap<String,Int>()
    private var delay=0L
    private fun read(name:String) { reads[name]=(reads[name] ?: 0)+1;if(delay>0) SystemClock.sleep(delay) }
    fun clear() {
        if(field is EditText) (field as EditText).setText("")
        value="";changes.clear();reads.clear();field.invalidate()
    }
    private fun changed(text:String) { value=text;changes.add(SystemClock.uptimeMillis() to text) }
    override fun onWindowFocusChanged(value:Boolean) { super.onWindowFocusChanged(value);focused=value }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        delay=intent.getIntExtra("read_delay_ms",0).toLong()
        val terminal=intent.getBooleanExtra("terminal",false)
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16) }
        panel.addView(TextView(this).apply { text=if(terminal) "Terminal input contract" else "Cross-process editor" })
        field=if(terminal) object:View(this) {
            init { isFocusableInTouchMode=true }
            override fun onCheckIsTextEditor()=true
            override fun onCreateInputConnection(info:EditorInfo):InputConnection {
                info.inputType=InputType.TYPE_NULL;info.imeOptions=EditorInfo.IME_FLAG_NO_FULLSCREEN
                return object:BaseInputConnection(this,true) {
                    override fun commitText(text:CharSequence?,position:Int):Boolean { changed(value+(text ?: ""));return true }
                    override fun deleteSurroundingText(before:Int,after:Int):Boolean {
                        if(before>0 && value.isNotEmpty()) changed(value.dropLast(before.coerceAtMost(value.length)))
                        return true
                    }
                    override fun sendKeyEvent(event:KeyEvent):Boolean {
                        if(event.action==KeyEvent.ACTION_DOWN) when(event.keyCode) {
                            KeyEvent.KEYCODE_DEL -> if(value.isNotEmpty()) changed(value.dropLast(1))
                            KeyEvent.KEYCODE_ENTER -> changed(value+"\n")
                            else -> if(event.unicodeChar>0) changed(value+String(Character.toChars(event.unicodeChar)))
                        }
                        return true
                    }
                    override fun getTextBeforeCursor(n:Int,flags:Int):CharSequence { read("before");return "" }
                    override fun getTextAfterCursor(n:Int,flags:Int):CharSequence { read("after");return "" }
                    override fun getSelectedText(flags:Int):CharSequence? { read("selected");return null }
                    override fun getCursorCapsMode(types:Int):Int { read("caps");return 0 }
                }
            }
        } else object:EditText(this) {
            init {
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setSingleLine(false)
                addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(s.toString()) }
                    override fun afterTextChanged(s:Editable?) {}
                })
            }
            override fun onCreateInputConnection(info:EditorInfo):InputConnection? {
                val base=super.onCreateInputConnection(info) ?: return null
                info.imeOptions=info.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                return object:InputConnectionWrapper(base,false) {
                    override fun getTextBeforeCursor(n:Int,flags:Int):CharSequence? { read("before");return super.getTextBeforeCursor(n,flags) }
                    override fun getTextAfterCursor(n:Int,flags:Int):CharSequence? { read("after");return super.getTextAfterCursor(n,flags) }
                    override fun getSelectedText(flags:Int):CharSequence? { read("selected");return super.getSelectedText(flags) }
                    override fun getCursorCapsMode(types:Int):Int { read("caps");return super.getCursorCapsMode(types) }
                    override fun getSurroundingText(before:Int,after:Int,flags:Int):android.view.inputmethod.SurroundingText? {
                        read("surrounding");return super.getSurroundingText(before,after,flags)
                    }
                }
            }
        }
        panel.addView(field,LinearLayout.LayoutParams(-1,0,1f));setContentView(panel)
        field.requestFocus()
        field.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) },300)
    }
}
