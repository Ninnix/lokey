package org.lokey.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

object KeyboardTheme {
    data class Colors(val background:Int, val keys:Int, val special:Int, val space:Int, val ink:Int, val muted:Int, val accent:Int, val shadow:Int)
    fun light(color:Int):Boolean {
        fun linear(value:Int):Double { val v=value/255.0;return if(v<=.04045) v/12.92 else Math.pow((v+.055)/1.055,2.4) }
        return .2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color))>.179
    }
    fun ink(color:Int) = if(light(color)) Color.rgb(28,29,30) else Color.rgb(245,240,231)
    fun blend(a:Int,b:Int,amount:Float):Int = Color.rgb((Color.red(a)*(1-amount)+Color.red(b)*amount).toInt(),(Color.green(a)*(1-amount)+Color.green(b)*amount).toInt(),(Color.blue(a)*(1-amount)+Color.blue(b)*amount).toInt())
    fun colors(prefs:Prefs,selected:String=prefs.keyboardTheme):Colors = colors(selected,prefs.themeBackground,prefs.themeKeys,prefs.themeAccent)
    fun colors(settings:org.json.JSONObject):Colors=colors(settings.getString("keyboard_theme"),settings.getInt("theme_background"),settings.getInt("theme_keys"),settings.getInt("theme_accent"))
    private fun colors(selected:String,bg:Int,keys:Int,accent:Int):Colors = when(selected) {
        "light" -> Colors(Color.rgb(245,245,243),Color.WHITE,Color.WHITE,Color.WHITE,Color.rgb(28,29,30),Color.rgb(112,113,110),Color.rgb(255,90,0),Color.rgb(210,210,207))
        "wave" -> waveColors
        "custom","image" -> {
            Colors(bg,keys,keys,keys,ink(bg),blend(ink(bg),bg,.38f),accent,blend(bg,Color.BLACK,.3f))
        }
        else -> Colors(Color.rgb(23,24,25),Color.rgb(48,49,51),Color.rgb(37,38,40),Color.rgb(66,65,64),Color.rgb(245,240,231),Color.rgb(155,155,153),Color.rgb(240,92,94),Color.rgb(12,13,14))
    }
    private val waveColors=Colors(Color.rgb(239,236,228),Color.rgb(214,225,226),Color.rgb(199,214,217),Color.rgb(214,225,226),Color.rgb(26,47,60),Color.rgb(91,112,119),Color.rgb(144,169,177),Color.rgb(193,194,184))
    fun palette(bitmap:Bitmap):Colors {
        val sample=Bitmap.createScaledBitmap(bitmap,80,80,true)
        val counts=IntArray(4096);val red=IntArray(4096);val green=IntArray(4096);val blue=IntArray(4096)
        for(y in 0 until sample.height) for(x in 0 until sample.width) {
            val c=sample.getPixel(x,y)
            if(Color.alpha(c)<128) continue
            val r=Color.red(c);val g=Color.green(c);val b=Color.blue(c)
            val key=(r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            counts[key]++;red[key]+=r;green[key]+=g;blue[key]+=b
        }
        if(sample!==bitmap) sample.recycle()
        val groups=counts.indices.filter { counts[it]>0 }.sortedByDescending { counts[it] }.take(48)
        if(groups.isEmpty()) return waveColors
        fun color(i:Int)=Color.rgb(red[i]/counts[i],green[i]/counts[i],blue[i]/counts[i])
        fun distance(a:Int,b:Int):Double = kotlin.math.sqrt(((Color.red(a)-Color.red(b))*(Color.red(a)-Color.red(b))+(Color.green(a)-Color.green(b))*(Color.green(a)-Color.green(b))+(Color.blue(a)-Color.blue(b))*(Color.blue(a)-Color.blue(b))).toDouble())
        val background=color(groups.first())
        val hsv=FloatArray(3)
        val accent=color(groups.maxBy { i ->
            val c=color(i);Color.colorToHSV(c,hsv)
            (hsv[1]+.2)*kotlin.math.sqrt(counts[i].toDouble()/counts[groups.first()]) + distance(background,c)/441.0*.8
        })
        val key=groups.firstOrNull { distance(color(it),background)>45 && distance(color(it),accent)>35 }?.let { color(it) } ?: blend(background,ink(background),.12f)
        return Colors(background,key,blend(key,background,.4f),blend(key,background,.15f),ink(background),blend(ink(background),background,.38f),accent,blend(background,Color.BLACK,.2f))
    }
    fun applyPalette(prefs:Prefs,bitmap:Bitmap) {
        val palette=palette(bitmap)
        prefs.themeBackground=palette.background;prefs.themeKeys=palette.keys;prefs.themeAccent=palette.accent
    }
    val io=Executors.newSingleThreadExecutor { r -> Thread(r,"lokey-theme") }
    private data class Image(val key:String,val width:Int,val height:Int,val sample:Int,val bitmap:Bitmap?)
    @Volatile private var cached:Image?=null
    internal fun sampleSize(width:Int,height:Int,w:Int,h:Int):Int {
        var sample=1
        // Both dimensions must cover the view because the keyboard center-crops the image.
        while(width/2/sample>=w && height/2/sample>=h) sample*=2
        return sample
    }
    fun imageFile(context:Context)=File(context.filesDir,"keycap-image.png")
    fun uploadFile(context:Context)=File(context.filesDir,"keycap-upload.png")
    fun importImage(context:Context,uri:Uri):Bitmap {
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)) { decoder,info,_ ->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val scale=1600f/max(info.size.width,info.size.height).coerceAtLeast(1600)
            decoder.setTargetSize((info.size.width*scale).roundToInt().coerceAtLeast(1),(info.size.height*scale).roundToInt().coerceAtLeast(1))
        }
        val file=imageFile(context);val partial=File(file.path+".part")
        partial.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        check(partial.renameTo(file)) { context.tr("Could not save image") }
        file.copyTo(uploadFile(context),true)
        return bitmap
    }
    fun image(context:Context,version:Long,width:Int,height:Int,builtin:Boolean=false,file:File=imageFile(context),callback:(Bitmap?)->Unit) {
        val w=width.coerceAtLeast(1);val h=height.coerceAtLeast(1)
        val key=if(builtin) "wave" else "${file.path}:$version"
        fun fits(image:Image)=image.key==key && image.sample==sampleSize(image.width,image.height,w,h)
        val ready=cached
        if(ready!=null && fits(ready)) { callback(ready.bitmap);return }
        io.execute {
            val current=cached
            if(current!=null && fits(current)) { callback(current.bitmap);return@execute }
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true;inSampleSize=1 }
            fun decode()=if(builtin) context.assets.open("themes/great-wave.png").use { BitmapFactory.decodeStream(it,null,options) } else BitmapFactory.decodeFile(file.path,options)
            decode()
            val sourceWidth=options.outWidth;val sourceHeight=options.outHeight
            var bitmap:Bitmap?=null
            if(options.outWidth>0 && options.outHeight>0) {
                options.inSampleSize=sampleSize(sourceWidth,sourceHeight,w,h)
                options.inJustDecodeBounds=false
                bitmap=decode()
            }
            cached=Image(key,sourceWidth,sourceHeight,options.inSampleSize,bitmap);callback(bitmap)
        }
    }
}
