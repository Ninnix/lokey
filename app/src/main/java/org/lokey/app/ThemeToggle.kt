package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class ThemeToggle(context:Context,private val light:Boolean,private val toggle:()->Unit):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=dp(1.6f);strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    private fun dp(value:Float)=value*resources.displayMetrics.density
    init {
        contentDescription=if(light) context.tr("Switch to dark theme") else context.tr("Switch to light theme")
        isClickable=true;isFocusable=true;isSoundEffectsEnabled=false
        setOnClickListener { toggle() }
    }
    override fun onDraw(canvas:Canvas) {
        val x=width/2f;val y=height/2f
        paint.color=if(light) Color.rgb(227,227,222) else Color.rgb(42,43,45);paint.style=Paint.Style.FILL
        canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),dp(14f),dp(14f),paint)
        paint.color=if(light) Color.rgb(38,39,40) else Color.rgb(245,240,231);paint.style=Paint.Style.STROKE
        if(!light) {
            canvas.drawCircle(x,y,dp(4f),paint)
            for(i in 0..7) {
                val angle=i*Math.PI/4
                canvas.drawLine(x+cos(angle).toFloat()*dp(7f),y+sin(angle).toFloat()*dp(7f),x+cos(angle).toFloat()*dp(9f),y+sin(angle).toFloat()*dp(9f),paint)
            }
        } else {
            val path=Path()
            path.moveTo(x+dp(6f),y+dp(4f))
            path.cubicTo(x-dp(2f),y+dp(6f),x-dp(6f),y-dp(2f),x-dp(1f),y-dp(7f))
            path.cubicTo(x-dp(10f),y-dp(6f),x-dp(11f),y+dp(7f),x-dp(2f),y+dp(9f))
            path.cubicTo(x+dp(2f),y+dp(10f),x+dp(5f),y+dp(7f),x+dp(6f),y+dp(4f))
            canvas.drawPath(path,paint)
        }
    }
}
