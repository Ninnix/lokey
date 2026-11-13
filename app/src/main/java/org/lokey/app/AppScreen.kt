package org.lokey.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object AppWindow {
    @Suppress("DEPRECATION")
    fun icons(window:Window,light:Boolean,status:Boolean=true) {
        if(Build.VERSION.SDK_INT>=30) {
            val mask=WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or if(status) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0
            window.insetsController?.setSystemBarsAppearance(if(light) mask else 0,mask)
        } else {
            val mask=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or if(status) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
            window.decorView.systemUiVisibility=(window.decorView.systemUiVisibility and mask.inv()) or if(light) mask else 0
        }
    }
    @Suppress("DEPRECATION")
    fun frame(window:Window,root:View,light:Boolean) {
        // Android 15 enforces edge-to-edge; earlier versions need an explicit opt-in.
        if(Build.VERSION.SDK_INT in 30..34) window.setDecorFitsSystemWindows(false)
        else if(Build.VERSION.SDK_INT<30) window.decorView.systemUiVisibility=window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.attributes=window.attributes.apply {
            layoutInDisplayCutoutMode=if(Build.VERSION.SDK_INT>=30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setBackgroundDrawable(root.background?.constantState?.newDrawable())
        val padding=Rect(root.paddingLeft,root.paddingTop,root.paddingRight,root.paddingBottom)
        root.setOnApplyWindowInsetsListener { view,insets ->
            val safe=if(Build.VERSION.SDK_INT>=30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()).let { Rect(it.left,it.top,it.right,it.bottom) }
            else insets.displayCutout.let { cutout -> Rect(maxOf(insets.systemWindowInsetLeft,cutout?.safeInsetLeft ?: 0),maxOf(insets.systemWindowInsetTop,cutout?.safeInsetTop ?: 0),maxOf(insets.systemWindowInsetRight,cutout?.safeInsetRight ?: 0),maxOf(insets.systemWindowInsetBottom,cutout?.safeInsetBottom ?: 0)) }
            view.setPadding(padding.left+safe.left,padding.top+safe.top,padding.right+safe.right,padding.bottom+safe.bottom)
            insets
        }
        icons(window,light)
        root.post { icons(window,light);root.requestApplyInsets() }
    }
    fun keyboard(window:Window,color:Int) {
        window.setBackgroundDrawable(ColorDrawable(color));icons(window,KeyboardTheme.light(color),false)
    }
}

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
        val root=vertical().apply { setBackgroundColor(bg) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12)) }
        header.addView(BackButton(this,ink,back).apply { contentDescription=tr("Back") },LinearLayout.LayoutParams(dp(40),dp(40)))
        header.addView(text(title,22f,bold=true),LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(8) })
        header.addView(ThemeToggle(this,prefs.appLight) {
            sound?.click(!prefs.appLight);prefs.appLight=!prefs.appLight;setTheme(if(prefs.appLight) R.style.AppTheme_Light else R.style.AppTheme);render()
        },LinearLayout.LayoutParams(dp(44),dp(40)))
        root.addView(header);setContentView(root);AppWindow.frame(window,root,prefs.appLight);return root
    }
    override fun onCreate(state:Bundle?) {
        prefs=Prefs(this);setTheme(if(prefs.appLight) R.style.AppTheme_Light else R.style.AppTheme)
        super.onCreate(state);sound=ThemeSound(this)
    }
    protected abstract fun render()
    override fun onDestroy() { sound?.close();super.onDestroy() }
}
