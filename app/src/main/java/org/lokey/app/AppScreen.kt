package org.lokey.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

abstract class AppScreen:Activity() {
    protected lateinit var prefs:Prefs
    private var sound:ThemeSound?=null
    protected val ink get()=if(prefs.appLight) 0xff1c1d1e.toInt() else 0xfff5f0e7.toInt()
    protected val muted get()=if(prefs.appLight) 0xff6e6f6c.toInt() else 0xffa0a19e.toInt()
    protected val accent get()=if(prefs.appLight) 0xffff5a00.toInt() else 0xfff05c5e.toInt()
    protected val bg get()=if(prefs.appLight) 0xfff8f8f5.toInt() else 0xff171819.toInt()
    protected val surface get()=if(prefs.appLight) 0xffedede9.toInt() else 0xff232425.toInt()
    protected fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    protected fun vertical()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    protected fun shape(color:Int=surface,selected:Boolean=false)=GradientDrawable().apply { setColor(color);cornerRadius=dp(16).toFloat();if(selected) setStroke(dp(1),accent) }
    protected fun text(value:String,size:Float=14f,color:Int=ink,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color);if(bold) typeface=Typeface.create("sans-serif",Typeface.BOLD)
    }
    protected fun button(label:String,primary:Boolean=false,action:()->Unit)=text(label,14f,if(primary) bg else ink,true).apply {
        gravity=Gravity.CENTER;setPadding(dp(14),dp(14),dp(14),dp(14));background=shape(if(primary) accent else surface)
        isClickable=true;isFocusable=true;isSoundEffectsEnabled=false;setOnClickListener { action() }
    }
    protected fun frame(title:String,back:()->Unit):LinearLayout {
        window.statusBarColor=bg;window.navigationBarColor=bg
        window.decorView.systemUiVisibility=if(prefs.appLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val root=vertical().apply { setBackgroundColor(bg);fitsSystemWindows=true }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12)) }
        header.addView(BackButton(this,ink,back).apply { contentDescription=tr("Back") },LinearLayout.LayoutParams(dp(40),dp(40)))
        header.addView(text(title,22f,bold=true),LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(8) })
        header.addView(ThemeToggle(this,prefs.appLight) {
            sound?.click(!prefs.appLight);prefs.appLight=!prefs.appLight;setTheme(if(prefs.appLight) R.style.AppTheme_Light else R.style.AppTheme);render()
        },LinearLayout.LayoutParams(dp(44),dp(40)))
        root.addView(header);setContentView(root);return root
    }
    override fun onCreate(state:Bundle?) {
        prefs=Prefs(this);setTheme(if(prefs.appLight) R.style.AppTheme_Light else R.style.AppTheme)
        super.onCreate(state);sound=ThemeSound(this)
    }
    protected abstract fun render()
    override fun onDestroy() { sound?.close();super.onDestroy() }
}
