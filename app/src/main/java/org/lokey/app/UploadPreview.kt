package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class UploadPreview(context:Context,color:Int,upload:()->Unit):View(context) {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color=color;style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    init {
        contentDescription=context.tr("Upload your image");isClickable=true;isFocusable=true
        setOnClickListener { upload() }
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width,(width*325f/resources.displayMetrics.widthPixels*resources.displayMetrics.density).toInt())
    }
    override fun onDraw(canvas:Canvas) {
        val unit=height/80f;val x=width/2f;val y=height/2f
        pen.strokeWidth=1.5f*unit
        canvas.drawLine(x,y+8*unit,x,y-15*unit,pen)
        val path=Path().apply {
            moveTo(x-8*unit,y-7*unit);lineTo(x,y-15*unit);lineTo(x+8*unit,y-7*unit)
            moveTo(x-17*unit,y+5*unit);lineTo(x-17*unit,y+17*unit);lineTo(x+17*unit,y+17*unit);lineTo(x+17*unit,y+5*unit)
        }
        canvas.drawPath(path,pen)
    }
}
