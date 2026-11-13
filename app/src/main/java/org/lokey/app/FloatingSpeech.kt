package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

class SpeechControls(context:Context,private val prefs:Prefs,private val start:()->Unit,private val stop:()->Unit,private val pause:(Boolean)->Unit):LinearLayout(context) {
    private var recording=false
    private var processing=false
    private var paused=false
    private var color=0
    private var initialized=false
    var gesture:((MotionEvent)->Boolean)?=null
    private inner class Control(var icon:String):View(context) {
        init { isClickable=true;isFocusable=true;isSoundEffectsEnabled=false }
        override fun onDraw(canvas:Canvas) {
            ToolIcons.draw(canvas,icon,width/2f,height/2f,dp(25).toFloat(),KeyboardTheme.ink(KeyboardTheme.colors(prefs).accent))
        }
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Button" }
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).roundToInt()
    private val secondary=Control("pause").apply { setOnClickListener { pause(!paused) } }
    private val primary=Control("microphone").apply { setOnClickListener { if(recording) stop() else if(!processing) start() } }
    init {
        orientation=HORIZONTAL;layoutDirection=LAYOUT_DIRECTION_LTR;setPadding(dp(4),dp(4),dp(4),dp(4));elevation=dp(6).toFloat()
        addView(secondary,LayoutParams(dp(48),dp(48)));addView(primary,LayoutParams(dp(48),dp(48)))
        state(false,false,false)
    }
    fun state(recording:Boolean,processing:Boolean,paused:Boolean) {
        val accent=KeyboardTheme.colors(prefs).accent
        if(initialized && this.recording==recording && this.processing==processing && this.paused==paused && color==accent) return
        initialized=true;color=accent
        this.recording=recording;this.processing=processing;this.paused=paused
        background=GradientDrawable().apply { setColor(accent);cornerRadius=dp(28).toFloat() }
        secondary.visibility=if(recording) VISIBLE else GONE
        secondary.icon=if(paused) "resume" else "pause"
        secondary.contentDescription=context.tr(if(paused) "Resume recording" else "Pause recording")
        primary.icon=if(recording) "stop" else if(processing) "busy" else "microphone"
        primary.contentDescription=context.tr(if(recording) "Stop recording" else if(processing) "Transcribing" else "Start recording")
        primary.isEnabled=!processing
        secondary.invalidate();primary.invalidate()
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(gesture?.invoke(event)==true) {
            val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel);cancel.recycle();return true
        }
        return super.dispatchTouchEvent(event)
    }
}

class FloatingSpeech(private val context:Context,private val prefs:Prefs,start:()->Unit,stop:()->Unit,pause:(Boolean)->Unit) {
    private val windowContext by lazy { if(Build.VERSION.SDK_INT>=30) context.createDisplayContext(
        context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY))
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null) else context }
    private val manager by lazy { windowContext.getSystemService(WindowManager::class.java) }
    internal val controls by lazy { SpeechControls(windowContext,prefs,start,stop,pause).apply { gesture={ drag(it) } } }
    private val params=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
        gravity=Gravity.TOP or Gravity.LEFT;softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        title="Lokey speech"
    }
    private val area=Rect()
    private var attached=false
    private var downX=0f
    private var downY=0f
    private var originX=0
    private var originY=0
    private var dragging=false
    private var cancelled=false
    private var pointer=-1
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private fun dp(value:Int)=(value*context.resources.displayMetrics.density).roundToInt()
    fun update(show:Boolean,recording:Boolean,processing:Boolean,paused:Boolean,keyboardTop:Int):Boolean {
        if(!show || !Settings.canDrawOverlays(context)) { hide();return false }
        controls.state(recording,processing,paused)
        if(Build.VERSION.SDK_INT>=30) {
            val metrics=manager.maximumWindowMetrics
            val safe=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            area.set(metrics.bounds.left+safe.left,metrics.bounds.top+safe.top,metrics.bounds.right-safe.right,metrics.bounds.bottom-safe.bottom)
        } else {
            val metrics=android.util.DisplayMetrics();manager.defaultDisplay.getRealMetrics(metrics)
            area.set(0,dp(24),metrics.widthPixels,metrics.heightPixels-dp(24))
        }
        if(keyboardTop>area.top) area.bottom=minOf(area.bottom,keyboardTop)
        area.inset(dp(6),dp(6))
        val w=dp(if(recording) 104 else 56);val h=dp(56)
        area.right=maxOf(area.left,area.right-w);area.bottom=maxOf(area.top,area.bottom-h)
        val x=(area.left+prefs.speechX*area.width()).roundToInt();val y=(area.top+prefs.speechY*area.height()).roundToInt()
        val changed=params.width!=w || params.height!=h || pointer<0 && (params.x!=x || params.y!=y)
        if(pointer<0) { params.x=x;params.y=y }
        params.width=w;params.height=h
        try {
            if(!attached) { manager.addView(controls,params);attached=true }
            else if(changed) manager.updateViewLayout(controls,params)
        } catch(_:RuntimeException) { hide() }
        return attached
    }
    private fun drag(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer=event.getPointerId(0);downX=event.rawX;downY=event.rawY;originX=params.x;originY=params.y;dragging=false;cancelled=false
            }
            MotionEvent.ACTION_POINTER_DOWN -> { cancelled=true;return true }
            MotionEvent.ACTION_MOVE -> {
                if(cancelled || pointer<0) return cancelled
                val dx=event.rawX-downX;val dy=event.rawY-downY
                if(!dragging && (abs(dx)>slop || abs(dy)>slop)) dragging=true
                if(dragging) {
                    params.x=(originX+dx).roundToInt().coerceIn(area.left,area.right)
                    params.y=(originY+dy).roundToInt().coerceIn(area.top,area.bottom)
                    try { manager.updateViewLayout(controls,params) } catch(_:RuntimeException) { hide() }
                    return true
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                val consumed=dragging || cancelled
                if(dragging && event.actionMasked==MotionEvent.ACTION_UP && !cancelled)
                    prefs.speechPosition((params.x-area.left).toFloat()/area.width().coerceAtLeast(1),(params.y-area.top).toFloat()/area.height().coerceAtLeast(1))
                else if(consumed) {
                    params.x=originX;params.y=originY
                    if(attached) try { manager.updateViewLayout(controls,params) } catch(_:RuntimeException) { hide() }
                }
                pointer=-1;dragging=false;cancelled=false
                return consumed
            }
        }
        return false
    }
    fun hide() {
        if(attached) try { manager.removeViewImmediate(controls) } catch(_:RuntimeException) { }
        attached=false;pointer=-1;dragging=false;cancelled=false
    }
}
