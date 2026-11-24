package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.view.WindowInsets
import android.widget.LinearLayout

class ImeLayout(context:Context):LinearLayout(context) {
    private val fill=Paint()
    private var split=false
    init { orientation=VERTICAL;setWillNotDraw(false) }
    fun background(color:Int,split:Boolean) {
        fill.color=color;this.split=split;invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        if(!split) canvas.drawColor(fill.color)
        else {
            val bottom=(height-paddingBottom).toFloat()
            canvas.drawRect(0f,0f,paddingLeft.toFloat(),bottom,fill)
            canvas.drawRect((width-paddingRight).toFloat(),0f,width.toFloat(),bottom,fill)
            canvas.drawRect(0f,bottom,width.toFloat(),height.toFloat(),fill)
        }
    }
    override fun onApplyWindowInsets(insets:WindowInsets):WindowInsets {
        val left:Int;val right:Int;val bottom:Int
        if(Build.VERSION.SDK_INT>=30) {
            val safe=insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.displayCutout())
            left=safe.left;right=safe.right;bottom=safe.bottom
        } else {
            val cutout=insets.displayCutout
            left=maxOf(insets.systemWindowInsetLeft,cutout?.safeInsetLeft ?: 0)
            right=maxOf(insets.systemWindowInsetRight,cutout?.safeInsetRight ?: 0)
            bottom=maxOf(insets.systemWindowInsetBottom,cutout?.safeInsetBottom ?: 0)
        }
        // Ancestors may already consume navigation insets on older Android versions.
        if(paddingLeft!=left || paddingRight!=right || paddingBottom!=bottom) setPadding(left,0,right,bottom)
        return insets
    }
}
