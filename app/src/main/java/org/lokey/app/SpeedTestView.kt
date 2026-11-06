package org.lokey.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import java.util.Locale

class SpeedTestView(context:Context):LinearLayout(context) {
    var run=SpeedRun()
        private set
    private val store=context.getSharedPreferences("speedtest",Context.MODE_PRIVATE)
    private val light=Prefs(context).appLight
    private val ink=if(light) Color.rgb(28,29,30) else Color.rgb(245,240,231)
    private val accent=if(light) Color.rgb(255,90,0) else Color.rgb(240,92,94)
    private val muted=if(light) Color.rgb(110,111,108) else Color.rgb(160,161,158)
    private val surface=if(light) Color.rgb(237,237,233) else Color.rgb(35,36,37)
    private val control=if(light) Color.rgb(223,223,219) else Color.rgb(53,53,54)
    private val stats=label("",13f,muted)
    private val outcome=label("",12f,muted)
    private val pulse=label("",12f,accent)
    private val streak=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal)
    private val share=action(context.tr("Share result"),true) { shareResult() }
    private var record=false
    private var predictionCommit=false
    val editor:EditText=object:SpeedEditor(context,SpeedRun.PASSAGE,ink,accent) {
        override fun onPrivateIMECommand(action:String?,data:Bundle?):Boolean {
            if(command(action,data)) return true
            return super.onPrivateIMECommand(action,data)
        }
        override fun onTextContextMenuItem(id:Int):Boolean {
            if(id==android.R.id.paste || id==android.R.id.pasteAsPlainText) run.disqualify()
            return super.onTextContextMenuItem(id)
        }
        override fun onCreateInputConnection(info:EditorInfo):InputConnection? {
            val base=super.onCreateInputConnection(info) ?: return null
            info.privateImeOptions="org.lokey.app.speedtest"
            info.hintLocales=android.os.LocaleList(Locale.ENGLISH)
            return object:InputConnectionWrapper(base,false) {
                override fun commitText(text:CharSequence?,newCursorPosition:Int):Boolean {
                    if(!predictionCommit && Languages.tokens(text?.toString().orEmpty()).size>1) run.disqualify()
                    val result=super.commitText(text,newCursorPosition)
                    predictionCommit=false
                    return result
                }
                override fun performPrivateCommand(action:String?,data:Bundle?)=command(action,data)
            }
        }
    }
    private val tick=object:Runnable {
        override fun run() { updateStats();if(!run.finished) postDelayed(this,100) }
    }
    init {
        orientation=VERTICAL
        val box=FrameLayout(context).apply { background=shape(surface,20);clipToOutline=true }
        editor.apply {
            id=generateViewId();textSize=20f;setTextColor(ink);gravity=Gravity.TOP
            textDirection=android.view.View.TEXT_DIRECTION_LTR;layoutDirection=android.view.View.LAYOUT_DIRECTION_LTR
            setTextLocale(Locale.ENGLISH)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(20),dp(20),dp(20),dp(38));background=null
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { }
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { }
                override fun afterTextChanged(value:Editable?) {
                    if(run.finished) return
                    val previous=run.score
                    val events=run.update(value.toString(),SystemClock.elapsedRealtime())
                    if(events.isNotEmpty()) {
                        pulse.text=if(events.last()) context.tr("+%1\$s · %2\$s combo ×%3\$s     %4\$s pts",run.score-previous,run.combo,run.multiplier,run.score) else context.tr("Miss · combo reset     %1\$s pts",run.score)
                        pulse.animate().cancel();pulse.alpha=.5f;pulse.animate().alpha(1f).setDuration(160).start()
                        streak.progress=if(run.multiplier==4) 10 else run.combo%10
                    }
                    updateStats();invalidate()
                    if(run.finished) complete()
                }
            })
        }
        box.addView(editor,FrameLayout.LayoutParams(-1,-1))
        pulse.setPadding(dp(20),0,dp(20),0)
        box.addView(pulse,FrameLayout.LayoutParams(-1,dp(28),Gravity.BOTTOM).apply { bottomMargin=dp(3) })
        streak.max=10;streak.progressTintList=ColorStateList.valueOf(accent)
        streak.progressBackgroundTintList=ColorStateList.valueOf(surface)
        box.addView(streak,FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM))
        addView(box,LayoutParams(-1,dp(140)))
        addView(stats,LayoutParams(-1,dp(30)))
        addView(outcome,LayoutParams(-1,-2))
        val buttons=LinearLayout(context).apply { setPadding(0,dp(12),0,0) }
        buttons.addView(action(context.tr("Restart")) { restart() },LayoutParams(0,dp(48),1f).apply { marginEnd=dp(8) })
        buttons.addView(share,LayoutParams(0,dp(48),1f));addView(buttons)
        share.visibility=GONE;pulse.text=context.tr("Every character counts · ×1")
        updateStats()
    }
    private fun command(action:String?,data:Bundle?):Boolean {
        if(action=="org.lokey.app.PREDICTION" && !run.finished) {
            predictionCommit=true;run.predictionTaps++;run.assistedWords+=data?.getInt("words",1) ?: 1
            return true
        }
        if(action=="org.lokey.app.VOICE" || action=="org.lokey.app.PASTE") { run.disqualify();return true }
        return false
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun shape(color:Int,radius:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun label(value:String,size:Float,color:Int=ink)=TextView(context).apply { text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL }
    private fun action(value:String,primary:Boolean=false,clicked:()->Unit)=label(value,14f,if(primary) (if(light) Color.rgb(248,248,245) else Color.rgb(23,24,25)) else ink).apply {
        gravity=Gravity.CENTER;background=shape(if(primary) accent else control,13);typeface=Typeface.create("sans-serif",Typeface.BOLD)
        isClickable=true;isFocusable=true;isSoundEffectsEnabled=false;setOnClickListener { clicked() }
    }
    private fun best():JSONObject?=runCatching { JSONObject(store.getString("best-characters",null) ?: return null).takeIf { it.optInt("version")==2 } }.getOrNull()
    private fun updateStats() {
        val summary=String.format(context.uiLocale(),context.tr("%1\$.0f WPM · %2\$.0f%% accuracy · %3\$d/%4\$d"),run.wpm(SystemClock.elapsedRealtime()),run.accuracy,run.index,run.passage.length)
        if(stats.text.toString()!=summary) stats.text=summary
        val best=best()
        if(!run.finished) {
            val note=when {
            !run.eligible -> context.tr("Practice run · paste or voice used")
            run.started==0L && best!=null -> String.format(context.uiLocale(),context.tr("Personal best %1\$.1f WPM · %2\$d character combo"),best.getDouble("wpm"),best.getInt("combo"))
            else -> context.tr("Dante · Inferno I · English translation")
            }
            if(outcome.text.toString()!=note) outcome.text=note
        }
    }
    private fun complete() {
        val now=SystemClock.elapsedRealtime();val score=run.snapshot(now)
        record=run.eligible && run.accuracy>=90 && run.wpm(now)>(best()?.optDouble("wpm") ?: 0.0)
        store.edit().putString("last",score.toString()).apply()
        if(record) store.edit().putString("best-characters",score.toString()).apply()
        outcome.text=when { !run.eligible -> context.tr("Practice complete · %1\$s points",run.score);record -> context.tr("New personal best · %1\$s points",run.score);else -> context.tr("Finished · %1\$s best combo",run.bestCombo) }
        pulse.text=context.tr("%1\$s points · %2\$s best combo",run.score,run.bestCombo)
        share.visibility=VISIBLE;editor.clearFocus();editor.isEnabled=false
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken,0)
        removeCallbacks(tick)
    }
    fun restart() {
        run=SpeedRun();predictionCommit=false;record=false
        editor.isEnabled=true;editor.setText("");share.visibility=GONE;streak.progress=0
        pulse.text=context.tr("Every character counts · ×1");updateStats();removeCallbacks(tick);post(tick)
        editor.requestFocus()
        editor.post { context.getSystemService(InputMethodManager::class.java).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun shareResult() {
        if(!run.finished) return
        ScoreCard.create(context,run.snapshot(SystemClock.elapsedRealtime()),record)
        val uri=Uri.parse("content://${context.packageName}.scores/record.png")
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri(context.tr("Lokey speed record"),uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent,context.tr("Share your Lokey record")))
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick);pulse.animate().cancel();super.onDetachedFromWindow() }
}
