package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.SurroundingText
import android.widget.EditText
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object FastTypingChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val report=JSONObject()
        test.runOnMainSync {
            if(android.os.Build.VERSION.SDK_INT>=31) {
                val field=EditText(context).apply { setText("hello world");setSelection(5) }
                var reads=0
                val target=object:BaseInputConnection(field,true) {
                    override fun getEditable()=field.text
                    override fun getSurroundingText(before:Int,after:Int,flags:Int):SurroundingText? {
                        reads++;return super.getSurroundingText(before,after,flags)
                    }
                }
                val connection=EditorConnection(target,Handler(Looper.getMainLooper()))
                check(connection.getTextBeforeCursor(3,0).toString()=="llo")
                check(connection.getTextAfterCursor(3,0).toString()==" wo")
                check(connection.getSelectedText(0).isNullOrEmpty())
                check(Cursor.position(connection)==5)
                check(reads==1) { "One editor state required $reads remote reads" }
                check(connection.selectionMatches(5,5))
                val clipped=connection.getSurroundingText(1,2,0)!!
                check(clipped.text.toString()=="o w" && clipped.offset==4 && clipped.selectionStart==1)
                connection.commitText("!",1)
                check(!connection.selectionMatches(5,5))
                check(connection.getTextBeforeCursor(6,0).toString()=="hello!")
                check(connection.selectionMatches(6,6))
                check(reads==2) { "Mutation reused stale editor text" }
                connection.setSelection(0,5)
                check(connection.getSelectedText(0).toString()=="hello")
                check(Cursor.position(connection)==-1)
                check(reads==3)
                field.setText("external edit");field.setSelection(field.length());connection.invalidate()
                check(connection.getTextBeforeCursor(20,0).toString()=="external edit")
                connection.invalidate()
                report.put("atomic_reads_and_mutation_invalidation",true)
                field.setText("Ciao domnai");field.setSelection(field.length())
                connection.getTextBeforeCursor(240,0)
                field.setText("Ciao other");field.setSelection(field.length())
                val stale=Prediction.Result("Ciao domnai",listOf("domani"),FloatArray(26),"it",false,"domani")
                check(!Autocorrect().commit(connection," ",stale,true))
                check(field.text.toString()=="Ciao other") { "Cached validation replaced an external edit" }
                connection.invalidate();report.put("stale_correction_reads_fresh_editor",true)
                field.setText("typed ");field.setSelection(field.length())
                val correction=Autocorrect()
                check(correction.defer(connection," ",true)=="typed")
                val checked=reads
                check(!correction.finish(connection,null) && reads==checked) { "Empty correction queried the editor" }
                connection.invalidate();report.put("empty_correction_skips_editor_reads",true)
            }
            val sandbox=object:ContextWrapper(context) {
                override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("fast-typing-$name",mode)
            }
            val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().commit()
            try {
                val prefs=Prefs(sandbox).apply { layout="qwerty";flow=true }
                val typed=StringBuilder()
                val view=KeyboardView(sandbox,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) { typed.append(text) }
                    override fun delete() {};override fun enter() {};override fun cursor(delta:Int) {}
                    override fun voice(start:Boolean)=false;override fun cancelVoice() {};override fun nextKeyboard() {}
                    override fun candidate(word:String) {};override fun feedback(key:String,down:Boolean) {}
                })
                view.editor(false,"Done",false)
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,1080,view.measuredHeight)
                @Suppress("UNCHECKED_CAST")
                val keys=KeyboardView::class.java.getDeclaredField("keys").apply { isAccessible=true }.get(view) as List<KeyboardView.Key>
                prefs.adaptive=true
                view.prediction(emptyList(),FloatArray(26).apply { this[1]=1f },true,"en")
                check(keys.any { it.rect!=it.anchor })
                view.adaptiveInput=false
                check(keys.all { it.rect==it.anchor }) { "Disabling adaptation left expanded keys" }
                view.prediction(emptyList(),FloatArray(26),false);view.adaptiveInput=true
                report.put("adaptive_selection_gate_rebuilds_geometry",true)
                val down=SystemClock.uptimeMillis()
                fun event(action:Int,vararg chars:Char) {
                    val props=chars.map { ch -> MotionEvent.PointerProperties().apply { id=when(ch) { ' ' -> 26;'.' -> 27;else -> ch-'a' };toolType=MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                    val coords=chars.map { ch -> keys.first { it.code==if(ch==' ') "space" else ch.toString() }.anchor.let { rect ->
                        MotionEvent.PointerCoords().apply { x=rect.centerX();y=rect.centerY();pressure=1f;size=1f }
                    } }.toTypedArray()
                    val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,chars.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                    view.onTouchEvent(e);e.recycle()
                }
                val anchors=keys.map { android.graphics.RectF(it.anchor) }
                event(MotionEvent.ACTION_DOWN,'a')
                view.prediction(emptyList(),FloatArray(26).apply { this[1]=1f },true,"en")
                check(keys.map { it.rect }==anchors) { "Prediction moved keys under an active finger" }
                event(MotionEvent.ACTION_CANCEL,'a')
                check(keys.any { it.rect!=it.anchor }) { "Cancelled touch left adaptive geometry pending" }
                check(keys.map { it.anchor }==anchors) { "Prediction moved stationary letter anchors" }
                view.prediction(emptyList(),FloatArray(26),false)
                check(keys.all { it.rect==it.anchor }) { "Clearing priors left expanded geometry" }
                report.put("adaptive_geometry_freezes_and_flushes_after_cancel",true)
                repeat(100) {
                    event(MotionEvent.ACTION_DOWN,'a')
                    event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),'a','b')
                    event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),'a','b')
                    event(MotionEvent.ACTION_UP,'a')
                }
                check(typed.toString()=="ab".repeat(100)) { "Overlapping thumbs reversed or duplicated letters: $typed" }
                for(first in listOf(' ','.')) {
                    typed.clear()
                    repeat(100) {
                        event(MotionEvent.ACTION_DOWN,first)
                        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),first,'b')
                        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),first,'b')
                        event(MotionEvent.ACTION_UP,first)
                    }
                    check(typed.toString()=="${first}b".repeat(100)) { "Space or punctuation arrived after the next letter: $typed" }
                }
                view.cancelTouches();report.put("overlapping_thumb_pairs",100).put("overlapping_space_pairs",100).put("overlapping_punctuation_pairs",100)
            } finally { store.edit().clear().commit() }
        }
        localPredictions(test,context)
        report.put("local_predictions_yield_to_touches",true)
        report.put("terminal",terminal(test,context))
        return report
    }
    private fun localPredictions(test:Instrumentation,context:Context) {
        val sandbox=object:ContextWrapper(context) {
            override fun getFilesDir()=java.io.File(context.cacheDir,"fast-typing-model-free").apply { mkdirs() }
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("fast-prediction-$name",mode)
        }
        val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().commit()
        var engine:Prediction?=null
        try {
            val prefs=Prefs(sandbox).apply { autocorrect=true }
            val warm=CountDownLatch(2)
            test.runOnMainSync { engine=Prediction(sandbox,prefs);engine!!.request("See you ") { warm.countDown() } }
            check(warm.await(15,TimeUnit.SECONDS))
            val done=CountDownLatch(2)
            test.runOnMainSync {
                var synchronous=false
                engine!!.request("See you tom") { synchronous=true;error("Superseded prediction was delivered") }
                check(!synchronous) { "Warm local prediction ran on the touch dispatch thread" }
                engine!!.request("See you tomo") { check(it.before=="See you tomo");done.countDown() }
            }
            check(done.await(15,TimeUnit.SECONDS))
        } finally {
            test.runOnMainSync { engine?.close() }
            store.edit().clear().commit()
        }
    }
    private fun terminal(test:Instrumentation,context:Context):JSONObject {
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val originalIme=shell("settings get secure default_input_method")
        val prefs=Prefs(context);val originalLayout=prefs.layout
        val output=StringBuilder();var reads=0;var arrows=0
        var activity:android.app.Activity?=null
        var service:LokeyService?=null;var keyboard:KeyboardView?=null
        try {
            shell("ime enable ${context.packageName}/org.lokey.app.LokeyService")
            shell("ime set ${context.packageName}/org.lokey.app.LokeyService")
            test.runOnMainSync { prefs.layout="qwerty" }
            activity=test.startActivitySync(android.content.Intent(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            test.runOnMainSync {
                val host=activity!!
                val field=object:View(host) {
                    init { isFocusableInTouchMode=true }
                    override fun onCheckIsTextEditor()=true
                    override fun onCreateInputConnection(info:android.view.inputmethod.EditorInfo):android.view.inputmethod.InputConnection {
                        info.inputType=0;info.imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                        return object:BaseInputConnection(this,true) {
                            fun flush() { val content=editable ?: return;output.append(content);content.clear() }
                            override fun commitText(text:CharSequence?,position:Int):Boolean { super.commitText(text,position);flush();return true }
                            override fun finishComposingText():Boolean { super.finishComposingText();flush();return true }
                            override fun getTextBeforeCursor(n:Int,flags:Int):CharSequence? { reads++;return super.getTextBeforeCursor(n,flags) }
                            override fun getTextAfterCursor(n:Int,flags:Int):CharSequence? { reads++;return super.getTextAfterCursor(n,flags) }
                            override fun getSelectedText(flags:Int):CharSequence? { reads++;return super.getSelectedText(flags) }
                            override fun getCursorCapsMode(types:Int):Int { reads++;return super.getCursorCapsMode(types) }
                            override fun getSurroundingText(before:Int,after:Int,flags:Int):SurroundingText? { reads++;return super.getSurroundingText(before,after,flags) }
                            override fun sendKeyEvent(event:android.view.KeyEvent):Boolean {
                                if(event.action==android.view.KeyEvent.ACTION_DOWN) when(event.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DEL -> if(output.isNotEmpty()) output.setLength(output.length-Character.charCount(Character.codePointBefore(output,output.length)))
                                    android.view.KeyEvent.KEYCODE_ENTER -> output.append('\n')
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> arrows++
                                }
                                return true
                            }
                        }
                    }
                }
                host.setContentView(field);field.requestFocus()
                host.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field,0)
            }
            fun find(view:View):KeyboardView? {
                if(view is KeyboardView && view.isShown) return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val until=SystemClock.uptimeMillis()+8000
            while(service==null && SystemClock.uptimeMillis()<until) {
                test.runOnMainSync {
                    for(root in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                        val view=find(root) ?: continue
                        val listener=KeyboardView::class.java.getDeclaredField("listener").apply { isAccessible=true }.get(view) as? LokeyService ?: continue
                        if(listener.currentInputEditorInfo?.let { it.inputType==0 && it.packageName==context.packageName }==true) { service=listener;keyboard=view;break }
                    }
                }
                if(service==null) SystemClock.sleep(30)
            }
            check(service!=null) { "Raw terminal editor did not receive the IME" }
            test.runOnMainSync {
                val ime=service!!
                check(keyboard!!.rawInput && !keyboard!!.adaptiveInput)
                reads=0;ime.text("ab😀");ime.delete();check(output.toString()=="ab")
                ime.text("c");ime.enter();ime.cursor(-1);ime.cursor(1)
                check(output.toString()=="abc\n" && arrows==2)
                check(reads==0) { "Raw typing made $reads editor reads" }
                output.clear();prefs.layout="korean"
                ime.text("ㄱ");ime.text("ㅏ");ime.text(" ")
                check(output.toString()=="가 ") { "Raw input lost Hangul composition: $output" }
                ime.delete();ime.text("ㄱ");ime.delete()
                check(output.toString()=="가") { "Deleting composition removed committed terminal text: $output" }
                prefs.layout="intl";ime.text("'");ime.delete()
                check(output.toString()=="가")
                ime.text("'");ime.text("e")
                check(output.toString()=="가é") { "Raw input lost dead-key composition: $output" }
            }
            return JSONObject().put("plain_editor_reads",0).put("unicode_backspace",true).put("enter_and_arrows",true)
                .put("hangul_composition",true).put("dead_keys_and_pending_delete",true)
        } finally {
            test.runOnMainSync { prefs.layout=originalLayout;activity?.finish() }
            shell("ime set $originalIme")
        }
    }
}
