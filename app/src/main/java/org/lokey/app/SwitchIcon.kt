package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class SwitchIcon(context:Context,private val id:String,private val light:Boolean):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var available=true
        set(value) {
            if(field==value) return
            field=value
            paint.colorFilter=if(value) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            invalidate()
        }
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas:Canvas) {
        val scale=minOf(width,height)/72f
        canvas.save();canvas.translate((width-72*scale)/2,(height-72*scale)/2);canvas.scale(scale,scale)
        fun rect(l:Float,t:Float,r:Float,b:Float,radius:Float,color:Int) { paint.color=color;canvas.drawRoundRect(l,t,r,b,radius,radius,paint) }
        val housing=if(light) Color.rgb(184,191,190) else Color.rgb(66,76,81)
        rect(5f,8f,67f,69f,9f,Color.rgb(17,23,27))
        rect(5f,4f,67f,65f,9f,housing)
        rect(10f,9f,62f,60f,5f,if(id=="gateron_brown") Color.rgb(187,192,180) else Color.rgb(110,125,130))
        rect(17f,16f,55f,54f,4f,Color.rgb(24,33,39))
        val stem=when(id) { "holy_panda" -> Color.rgb(224,161,103);"kailh_box_jade" -> Color.rgb(49,162,119);"razer_green" -> Color.rgb(82,185,53);"topre" -> Color.rgb(52,129,145);"cherry_red" -> Color.rgb(221,65,66);"cherry_blue" -> Color.rgb(50,139,202);"gateron_brown" -> Color.rgb(181,129,76);else -> Color.rgb(139,88,48) }
        rect(23f,22f,49f,48f,3f,stem)
        rect(26f,25f,46f,45f,2f,KeyboardTheme.blend(stem,Color.WHITE,.14f))
        val cross=Path().apply { moveTo(33f,27f);lineTo(39f,27f);lineTo(39f,32f);lineTo(44f,32f);lineTo(44f,38f);lineTo(39f,38f);lineTo(39f,43f);lineTo(33f,43f);lineTo(33f,38f);lineTo(28f,38f);lineTo(28f,32f);lineTo(33f,32f);close() }
        paint.color=Color.rgb(37,36,32);canvas.drawPath(cross,paint)
        rect(29f,4f,43f,11f,2f,Color.rgb(38,47,52));rect(29f,58f,43f,65f,2f,Color.rgb(38,47,52))
        paint.color=KeyboardTheme.blend(housing,Color.WHITE,.45f)
        for(x in listOf(13f,59f)) for(y in listOf(12f,57f)) canvas.drawCircle(x,y,1.8f,paint)
        canvas.restore()
    }
}

class BackButton(context:Context,private val color:Int,action:()->Unit):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;color=this@BackButton.color }
    init { contentDescription=context.tr("Back to Feel");isClickable=true;isFocusable=true;setOnClickListener { action() } }
    override fun onDraw(canvas:Canvas) {
        val d=resources.displayMetrics.density;val x=width/2f;val y=height/2f;paint.strokeWidth=1.6f*d
        val direction=if(layoutDirection==LAYOUT_DIRECTION_RTL) -1 else 1
        val path=Path().apply { moveTo(x+3*d*direction,y-7*d);lineTo(x-4*d*direction,y);lineTo(x+3*d*direction,y+7*d);moveTo(x-4*d*direction,y);lineTo(x+9*d*direction,y) }
        canvas.drawPath(path,paint)
    }
}
