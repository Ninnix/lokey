package org.lokey.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView

internal fun TextView.setFeelIcon(kind:FeelIcon.Kind,color:Int) {
    val density=resources.displayMetrics.density
    compoundDrawablePadding=(10*density).toInt()
    setCompoundDrawablesRelativeWithIntrinsicBounds(FeelIcon(kind,(21*density).toInt(),color),null,null,null)
}

internal class FeelIcon(private val kind:Kind,private val size:Int,color:Int):Drawable() {
    enum class Kind { Swipe, Correct, Adapt, Speech, Learn, Sound, Volume, Haptics, Return, Speed, Trophy, NumberRow, SplitKeyboard, Suggestion, Characters, Trash, Globe, Statistics, Shield }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color=color
        style=Paint.Style.STROKE;strokeWidth=1.5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private val path=Path()
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity()=PixelFormat.TRANSLUCENT
    override fun setAlpha(alpha:Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(colorFilter:ColorFilter?) { paint.colorFilter=colorFilter;invalidateSelf() }

    override fun draw(canvas:Canvas) {
        val saved=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/24f,bounds.height()/24f)
        fun line(vararg points:Float) {
            path.rewind();path.moveTo(points[0],points[1])
            for(i in 2 until points.size step 2) path.lineTo(points[i],points[i+1])
            canvas.drawPath(path,paint)
        }
        when(kind) {
            Kind.Swipe -> {
                canvas.drawCircle(5f,17f,2f,paint)
                path.rewind();path.moveTo(7f,17f);path.cubicTo(16f,17f,9f,7f,20f,7f)
                canvas.drawPath(path,paint);line(16f,3f,20f,7f,16f,11f)
            }
            Kind.Correct -> {
                line(3f,15f,7.5f,5f,12f,15f);line(5f,11f,10f,11f)
                line(12f,17f,15f,20f,21f,13f)
            }
            Kind.Suggestion -> {
                line(5f,4f,19f,4f,21f,6f,21f,15f,19f,17f,10f,17f,5f,21f,5f,17f,3f,15f,3f,6f,5f,4f)
                line(8f,10f,11f,13f,16f,8f)
            }
            Kind.Characters -> {
                line(3f,17f,8.5f,5f,14f,17f);line(5.5f,12f,11.5f,12f)
                line(16f,10f,21f,10f);line(18.5f,7.5f,18.5f,12.5f)
            }
            Kind.Trash -> {
                line(4f,6f,20f,6f);line(9f,6f,9f,3.5f,15f,3.5f,15f,6f)
                line(6.5f,6f,8f,20.5f,16f,20.5f,17.5f,6f)
                line(10f,10f,10.5f,17f);line(14f,10f,13.5f,17f)
            }
            Kind.Globe -> {
                canvas.drawCircle(12f,12f,9f,paint)
                canvas.drawOval(8f,3f,16f,21f,paint);line(3f,12f,21f,12f)
            }
            Kind.Statistics -> {
                line(4f,4f,4f,20f,21f,20f)
                line(8f,16f,8f,12f);line(13f,16f,13f,9f);line(18f,16f,18f,5f)
            }
            Kind.Shield -> {
                line(12f,3f,20f,6f,20f,12f,18f,17f,12f,21f,6f,17f,4f,12f,4f,6f,12f,3f)
                line(8.5f,12f,11f,14.5f,15.5f,10f)
            }
            Kind.Adapt -> {
                canvas.drawRoundRect(2.5f,12f,9f,19f,1.5f,1.5f,paint)
                canvas.drawRoundRect(12f,5f,21.5f,19f,2f,2f,paint)
                line(15f,10f,18.5f,10f)
            }
            Kind.NumberRow -> {
                canvas.drawRoundRect(3f,5f,21f,19f,2f,2f,paint)
                line(3f,11f,21f,11f)
                line(9f,5f,9f,11f);line(15f,5f,15f,11f)
                line(8f,15f,16f,15f)
            }
            Kind.SplitKeyboard -> {
                canvas.drawRoundRect(2f,6f,10f,18f,2f,2f,paint)
                canvas.drawRoundRect(14f,6f,22f,18f,2f,2f,paint)
                line(5f,10f,7f,10f);line(5f,14f,7f,14f)
                line(17f,10f,19f,10f);line(17f,14f,19f,14f)
            }
            Kind.Speech -> {
                canvas.drawRoundRect(9f,3f,15f,14f,3f,3f,paint)
                canvas.drawArc(6f,7f,18f,18f,0f,180f,false,paint)
                line(12f,18f,12f,21f);line(9f,21f,15f,21f)
            }
            Kind.Learn -> {
                line(12f,7f,8f,5f,3f,5f,3f,17f,8f,17f,12f,19f,16f,17f,21f,17f,21f,5f,16f,5f,12f,7f,12f,19f)
                line(6f,9f,8.5f,9f);line(15.5f,9f,18f,9f)
            }
            Kind.Sound -> {
                line(3f,9f,7f,9f,12f,5f,12f,19f,7f,15f,3f,15f,3f,9f)
                canvas.drawArc(10f,8f,18f,16f,-60f,120f,false,paint)
                canvas.drawArc(7f,5f,21f,19f,-55f,110f,false,paint)
            }
            Kind.Volume -> {
                line(4f,15f,4f,19f);line(9f,11f,9f,19f);line(14f,7f,14f,19f);line(19f,3f,19f,19f)
            }
            Kind.Haptics -> {
                canvas.drawRoundRect(8f,4f,16f,20f,2f,2f,paint)
                line(11f,17f,13f,17f)
                line(4.5f,6f,3f,9f,4.5f,12f,3f,15f,4.5f,18f)
                line(19.5f,6f,21f,9f,19.5f,12f,21f,15f,19.5f,18f)
            }
            Kind.Return -> {
                line(20f,5f,20f,9f,18f,12f,4f,12f)
                line(9f,7f,4f,12f,9f,17f)
            }
            Kind.Speed -> {
                canvas.drawArc(3f,4f,21f,22f,145f,250f,false,paint)
                canvas.drawCircle(12f,13f,1.5f,paint)
                line(13f,12f,17f,8f);line(8f,19f,16f,19f)
            }
            Kind.Trophy -> {
                line(8f,5f,16f,5f,16f,10f,14f,14f,10f,14f,8f,10f,8f,5f)
                line(8f,6f,4f,6f,4f,9f,6f,11f,8f,11f)
                line(16f,6f,20f,6f,20f,9f,18f,11f,16f,11f)
                line(12f,14f,12f,19f);line(8f,19f,16f,19f)
            }
        }
        canvas.restoreToCount(saved)
    }
}
