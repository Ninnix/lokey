package org.lokey.app

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

class EditorSheet(context:Context,prefs:Prefs,title:String):Dialog(context) {
    val ink=if(prefs.appLight) 0xff1c1d1e.toInt() else 0xfff5f0e7.toInt()
    val muted=if(prefs.appLight) 0xff6e6f6c.toInt() else 0xffa0a19e.toInt()
    val accent=if(prefs.appLight) 0xffff5a00.toInt() else 0xfff05c5e.toInt()
    val bg=if(prefs.appLight) 0xfff8f8f5.toInt() else 0xff171819.toInt()
    val surface=if(prefs.appLight) 0xffedede9.toInt() else 0xff292a2c.toInt()
    val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private val footer=LinearLayout(context)
    fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun shape(color:Int=surface,radius:Int=12)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    fun text(value:String,size:Float=14f,color:Int=ink)=TextView(context).apply { text=value;textSize=size;setTextColor(color) }
    fun button(label:String,primary:Boolean=false,action:()->Unit)=text(label,14f,if(primary) KeyboardTheme.ink(accent) else ink).apply {
        gravity=Gravity.CENTER;minimumHeight=dp(48);setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(if(primary) accent else surface)
        isClickable=true;isFocusable=true;isSoundEffectsEnabled=false;setOnClickListener { action() }
    }
    fun action(label:String,primary:Boolean=false,run:()->Unit) {
        footer.addView(button(label,primary,run),LinearLayout.LayoutParams(0,-2,1f).apply { if(footer.childCount>0) marginStart=dp(8) })
    }
    init {
        val panel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;background=shape(bg,24);setPadding(dp(20),dp(10),dp(20),dp(16)) }
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(text(title,21f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("×") { dismiss() }.apply { contentDescription=context.tr("Close %1\$s",title);background=null;textSize=24f;setTextColor(muted) },LinearLayout.LayoutParams(dp(44),dp(48)))
        panel.addView(header)
        val scroll=object:ScrollView(context) {
            override fun onMeasure(widthSpec:Int,heightSpec:Int) {
                val frame=Rect();window?.decorView?.getWindowVisibleDisplayFrame(frame)
                val available=(if(frame.height()>0) frame.height() else resources.displayMetrics.heightPixels)-dp(150)
                val limit=if(MeasureSpec.getMode(heightSpec)==MeasureSpec.UNSPECIFIED) available else minOf(available,MeasureSpec.getSize(heightSpec)-dp(64))
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(minOf(dp(560),limit.coerceAtLeast(dp(100))),MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport=false;clipToPadding=false;setPadding(0,dp(8),0,dp(8));addView(body) }
        panel.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        panel.addView(footer,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val wrapper=FrameLayout(context).apply { setPadding(dp(12),0,dp(12),dp(12));addView(panel) }
        setContentView(wrapper)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT));setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes=attributes.apply { dimAmount=.5f }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
    }
    override fun show() { super.show();window?.setLayout(-1,-2) }
}
