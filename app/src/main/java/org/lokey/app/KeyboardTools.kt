package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

object ToolIcons {
    fun draw(canvas:Canvas,name:String,x:Float,y:Float,size:Float,color:Int) {
        val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color;style=Paint.Style.STROKE;strokeWidth=size/16;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
        val u=size/24
        canvas.save();canvas.translate(x-12*u,y-12*u);canvas.scale(u,u)
        pen.strokeWidth=1.35f
        when(name) {
            "microphone" -> {
                canvas.drawRoundRect(9f,3f,15f,14f,3f,3f,pen)
                canvas.drawArc(6f,9f,18f,18f,0f,180f,false,pen)
                canvas.drawLine(12f,18f,12f,22f,pen);canvas.drawLine(8f,22f,16f,22f,pen)
            }
            "pause" -> { pen.style=Paint.Style.FILL;canvas.drawRect(6f,5f,10f,19f,pen);canvas.drawRect(14f,5f,18f,19f,pen) }
            "resume" -> {
                pen.style=Paint.Style.FILL
                canvas.drawPath(Path().apply { moveTo(7f,4f);lineTo(20f,12f);lineTo(7f,20f);close() },pen)
            }
            "stop" -> { pen.style=Paint.Style.FILL;canvas.drawRoundRect(5f,5f,19f,19f,2f,2f,pen) }
            "busy" -> canvas.drawArc(4f,4f,20f,20f,-60f,270f,false,pen)
            "clipboard","copy" -> {
                canvas.drawRoundRect(5f,5f,19f,22f,2f,2f,pen)
                canvas.drawRoundRect(9f,2f,15f,7f,1f,1f,pen)
                canvas.drawLine(9f,12f,15f,12f,pen);canvas.drawLine(9f,16f,15f,16f,pen)
            }
            "layout" -> {
                for(row in 0..2) for(column in 0..2) {
                    val x=3f+column*6.5f;val y=4f+row*6.5f
                    canvas.drawRoundRect(x,y,x+4f,y+4f,1f,1f,pen)
                }
            }
            "translate" -> {
                pen.style=Paint.Style.FILL;pen.textSize=15f;pen.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
                canvas.drawText("A",1f,15f,pen);canvas.drawText("文",10f,22f,pen)
            }
            "pin" -> {
                val path=Path().apply { moveTo(7f,3f);lineTo(17f,3f);lineTo(15f,7f);lineTo(15f,12f);lineTo(19f,16f);lineTo(5f,16f);lineTo(9f,12f);lineTo(9f,7f);close() }
                canvas.drawPath(path,pen);canvas.drawLine(12f,16f,12f,22f,pen)
            }
            "delete" -> {
                canvas.drawLine(5f,6f,19f,6f,pen);canvas.drawLine(9f,3f,15f,3f,pen)
                canvas.drawRoundRect(7f,6f,17f,21f,1f,1f,pen)
                canvas.drawLine(10f,10f,10f,17f,pen);canvas.drawLine(14f,10f,14f,17f,pen)
            }
            "back" -> { canvas.drawLine(16f,4f,8f,12f,pen);canvas.drawLine(8f,12f,16f,20f,pen) }
            else -> { canvas.drawLine(6f,6f,18f,18f,pen);canvas.drawLine(6f,18f,18f,6f,pen) }
        }
        canvas.restore()
    }
}
class ToolButton(context:Context,private val icon:String,label:String,private val color:Int,action:()->Unit):View(context) {
    init { contentDescription=label;isClickable=true;isFocusable=true;isSoundEffectsEnabled=false;setOnClickListener { action() } }
    override fun onDraw(canvas:Canvas) {
        canvas.save()
        if(icon=="back" && layoutDirection==LAYOUT_DIRECTION_RTL) canvas.scale(-1f,1f,width/2f,height/2f)
        ToolIcons.draw(canvas,icon,width/2f,height/2f,22*resources.displayMetrics.density,color)
        canvas.restore()
    }
}
class ToolStyle(val context:Context,prefs:Prefs) {
    val colors=KeyboardTheme.colors(prefs)
    fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun text(value:String,size:Float=14f,color:Int=colors.ink)=TextView(context).apply { text=value;textSize=size;setTextColor(color);setPadding(dp(6),dp(6),dp(6),dp(6)) }
    fun shape(color:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(10).toFloat() }
    fun action(value:String,run:()->Unit)=text(value,13f,colors.accent).apply { gravity=Gravity.CENTER;minHeight=dp(44);isClickable=true;isFocusable=true;isSoundEffectsEnabled=false;setOnClickListener { run() } }
}
class TranslationBar(context:Context,prefs:Prefs,editor:LocalEditor,target:()->Unit,close:()->Unit,retry:()->Unit):LinearLayout(context) {
    private val style=ToolStyle(context,prefs)
    private val language=style.action(context.tr("Auto → %1\$s",context.tr(prefs.translationTarget)),target).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;contentDescription=context.tr("Choose translation language") }
    private val status=style.text(context.tr("Translation appears in your app"),12f,style.colors.muted).apply { contentDescription=context.tr("Translation status");accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val retryButton=style.action(context.tr("Retry"),retry).apply { contentDescription=context.tr("Retry translation") }
    init {
        orientation=VERTICAL;setPadding(style.dp(6),0,style.dp(6),style.dp(4));setBackgroundColor(style.colors.background)
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(language,LayoutParams(0,style.dp(36),1f))
        row.addView(ToolButton(context,"close",context.tr("Close translation"),style.colors.ink,close),LayoutParams(style.dp(40),style.dp(36)))
        addView(row)
        (editor.parent as? ViewGroup)?.removeView(editor)
        editor.apply {
            hint=context.tr("Type or dictate in your language");contentDescription=context.tr("Translation source");textSize=16f
            setTextColor(style.colors.ink);setHintTextColor(style.colors.muted)
            setPadding(style.dp(10),style.dp(6),style.dp(10),style.dp(6));background=style.shape(style.colors.special)
            gravity=Gravity.TOP
        }
        addView(editor,LayoutParams(-1,style.dp(68)))
        val footer=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        footer.addView(status,LayoutParams(0,-2,1f));footer.addView(retryButton,LayoutParams(-2,style.dp(30)));addView(footer)
        update(Translation.Result("",prefs.translationTarget))
    }
    fun update(result:Translation.Result) {
        language.text=context.tr("Auto → %1\$s",context.tr(result.target))
        status.text=result.error?.let { context.tr(it) } ?: if(result.busy) context.tr("Translating…") else context.tr("Translation appears in your app")
        retryButton.visibility=if(result.error!=null) VISIBLE else GONE
    }
}
class ClipboardPanel(context:Context,prefs:Prefs,private val history:ClipboardHistory,close:()->Unit,paste:(String)->Unit):LinearLayout(context) {
    private val pasteAction=paste
    private val style=ToolStyle(context,prefs)
    private val list=LinearLayout(context).apply { orientation=VERTICAL }
    private val scroll=ScrollView(context)
    init {
        orientation=VERTICAL;setPadding(style.dp(6),style.dp(4),style.dp(6),style.dp(8));setBackgroundColor(style.colors.background)
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(ToolButton(context,"back",context.tr("Back to keyboard"),style.colors.ink,close),LayoutParams(style.dp(40),style.dp(40)))
        header.addView(style.text(context.tr("Clipboard"),18f),LayoutParams(0,-2,1f))
        header.addView(ToolButton(context,"delete",context.tr("Clear recent clips"),style.colors.ink) { history.clearRecent();render(paste) },LayoutParams(style.dp(40),style.dp(40)))
        addView(header)
        scroll.addView(list);addView(scroll,LayoutParams(-1,0,1f))
        render(paste)
    }
    fun refresh() { render(pasteAction) }
    private fun render(paste:(String)->Unit) {
        val y=scroll.scrollY;list.removeAllViews()
        val entries=history.entries()
        if(entries.isEmpty()) list.addView(style.text(context.tr("Copy a message to keep it here. Pin clips you want to keep."),14f,style.colors.muted))
        var pinned:Boolean?=null
        for(clip in entries) {
            if(clip.pinned!=pinned) { pinned=clip.pinned;list.addView(style.text(if(clip.pinned) context.tr("Pinned") else context.tr("Recent · 1 hour"),12f,style.colors.muted)) }
            val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;background=style.shape(style.colors.special) }
            row.addView(style.text(clip.text).apply {
                maxLines=3;minHeight=style.dp(52);isClickable=true;isFocusable=true;isSoundEffectsEnabled=false;contentDescription=context.tr("Paste: %1\$s",clip.text)
                setOnClickListener { paste(clip.text) }
            },LayoutParams(0,-2,1f))
            row.addView(ToolButton(context,"pin",if(clip.pinned) context.tr("Unpin clip") else context.tr("Pin clip"),if(clip.pinned) style.colors.accent else style.colors.muted) { history.pin(clip.id);render(paste) },LayoutParams(style.dp(36),style.dp(44)))
            row.addView(ToolButton(context,"delete",context.tr("Delete clip"),style.colors.muted) { history.delete(clip.id);render(paste) },LayoutParams(style.dp(36),style.dp(44)))
            list.addView(row,LayoutParams(-1,-2).apply { bottomMargin=style.dp(6) })
        }
        scroll.post { scroll.scrollTo(0,y) }
    }
}
class TranslationLanguages(context:Context,prefs:Prefs,close:()->Unit,private val selected:(String)->Unit):LinearLayout(context) {
    val search=LocalEditor(context)
    private val style=ToolStyle(context,prefs)
    private val common=listOf("English","Italian","Spanish","French","German","Portuguese","Japanese","Korean","Simplified Chinese","Standard Arabic")
    private val languages=Translation.languages(context).let { all -> common.filter { it in all }+all.filter { it !in common }.sorted() }
    private var filtered=languages
    private val adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,ArrayList(languages)) {
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View = (super.getView(position,convertView,parent) as TextView).apply {
            val name=getItem(position)!!;text=context.tr(name)+if(name==prefs.translationTarget) "  ✓" else "";textSize=15f;setTextColor(style.colors.ink);setBackgroundColor(style.colors.background)
        }
    }
    init {
        orientation=VERTICAL;setBackgroundColor(style.colors.background)
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(ToolButton(context,"back",context.tr("Back to translation"),style.colors.ink,close),LayoutParams(style.dp(44),style.dp(40)))
        header.addView(style.text(context.tr("Translate to"),18f));addView(header)
        search.apply {
            setSingleLine();hint=context.tr("Search languages");contentDescription=context.tr("Search languages");textSize=15f
            inputType=android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(style.colors.ink);setHintTextColor(style.colors.muted);background=style.shape(style.colors.special)
            setPadding(style.dp(12),0,style.dp(12),0)
        }
        addView(search,LayoutParams(-1,style.dp(42)))
        val list=ListView(context).apply { isFastScrollEnabled=true;dividerHeight=0;adapter=this@TranslationLanguages.adapter }
        list.setOnItemClickListener { _,_,position,_ -> selected(filtered[position]) }
        addView(list,LayoutParams(-1,0,1f))
        search.changed={
            val query=Languages.fold(search.text.toString().trim())
            filtered=languages.filter { (Languages.fold(it).contains(query) || Languages.fold(context.tr(it)).contains(query)) }
            adapter.clear();adapter.addAll(filtered);adapter.notifyDataSetChanged()
        }
    }
    fun chooseFirst() { filtered.firstOrNull()?.let(selected) }
}
