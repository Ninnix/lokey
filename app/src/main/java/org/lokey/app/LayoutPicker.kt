package org.lokey.app

import android.content.Context
import android.content.Intent
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

class LayoutPreview(context:Context,private val prefs:Prefs,private val selected:()->KeyboardLayouts.Layout):View(context) {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(value:Float)=value*resources.displayMetrics.density
    override fun onDraw(canvas:Canvas) {
        val layout=selected();val colors=KeyboardTheme.colors(prefs)
        pen.color=colors.background;canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),dp(8f),dp(8f),pen)
        val rows=if(layout.keyRows.isNotEmpty()) layout.keyRows.map { row -> row.map { it.label } } else listOf("1234567890".map { it.toString() })+layout.rows+listOf(listOf("↑","", "↵"))
        val h=(height-dp(7f))/rows.size;val gap=dp(1.5f)
        pen.typeface=Typeface.create("sans-serif",Typeface.NORMAL);pen.textAlign=Paint.Align.CENTER
        rows.forEachIndexed { index,row ->
            val inset=if(index in 2 until rows.lastIndex) dp(4f) else 0f
            val available=width-dp(7f)-inset*2;val cell=available/row.size
            row.forEachIndexed { key,value ->
                val x=dp(3.5f)+inset+key*cell;val y=dp(3.5f)+index*h
                val accent=index==rows.lastIndex && key==row.lastIndex
                pen.color=if(accent) colors.accent else colors.keys
                canvas.drawRoundRect(x,y,x+cell-gap,y+h-gap,dp(2f),dp(2f),pen)
                pen.color=if(accent) KeyboardTheme.ink(colors.accent) else colors.ink
                val label=KeyboardLayouts.display(value)
                pen.textSize=minOf(dp(6f),h*.65f)
                if(pen.measureText(label)>cell-gap) pen.textSize*=((cell-gap)/pen.measureText(label))
                canvas.drawText(label,x+(cell-gap)/2,y+(h-gap-pen.fontMetrics.ascent-pen.fontMetrics.descent)/2,pen)
            }
        }
    }
}

class LayoutPicker(context:Context,private val prefs:Prefs,ime:Boolean=false,private val automaticChoice:Boolean=true,private val selected:()->Unit={}):LinearLayout(context) {
    private val keyboardColors=KeyboardTheme.colors(prefs)
    private val ink=if(ime) keyboardColors.ink else if(prefs.appLight) 0xff1c1d1e.toInt() else 0xfff5f0e7.toInt()
    private val muted=if(ime) keyboardColors.muted else if(prefs.appLight) 0xff6e6f6c.toInt() else 0xffa0a19e.toInt()
    private val accent=if(ime) keyboardColors.accent else if(prefs.appLight) 0xffff5a00.toInt() else 0xfff05c5e.toInt()
    private val surface=if(ime) keyboardColors.special else if(prefs.appLight) 0xffededE9.toInt() else 0xff232425.toInt()
    private val choices=ArrayList<Pair<String,LinearLayout>>()
    private val checkmarks=HashMap<String,TextView>()
    private val variants=LinearLayout(context).apply { orientation=VERTICAL }
    private val custom=LinearLayout(context).apply { orientation=VERTICAL }
    private val editing=!ime && automaticChoice
    private lateinit var automatic:LinearLayout
    private lateinit var autoCheck:TextView
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float=14f,color:Int=ink)=TextView(context).apply { text=value;textSize=size;setTextColor(color) }
    private fun shape(checked:Boolean)=GradientDrawable().apply { setColor(surface);cornerRadius=dp(14).toFloat();setStroke(dp(1),if(checked) accent else surface) }
    init {
        orientation=VERTICAL
        automatic=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));isClickable=true;isFocusable=true
            contentDescription=context.tr("Automatic keyboard layout");isSoundEffectsEnabled=false
            setOnClickListener { choose("auto") }
        }
        val labels=LinearLayout(context).apply { orientation=VERTICAL }
        labels.addView(text(context.tr("Automatic"),16f).apply { typeface=Typeface.create("sans-serif",Typeface.BOLD) })
        labels.addView(text(context.tr("%1\$s · Device language",context.layoutName(KeyboardLayouts.automatic())),12f,muted).apply { setPadding(0,dp(4),0,0) })
        automatic.addView(labels,LayoutParams(0,-2,1f));autoCheck=text("✓",18f,accent)
        automatic.addView(autoCheck)
        if(automaticChoice) addView(automatic,LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        addView(custom)
        if(editing) {
            val actions=LinearLayout(context)
            for((title,blank) in listOf(context.tr("Customize current") to false,context.tr("Create blank") to true)) actions.addView(text(title,13f,accent).apply {
                gravity=Gravity.CENTER;setPadding(dp(8),dp(14),dp(8),dp(14));background=shape(false);isClickable=true;isFocusable=true
                setOnClickListener { context.startActivity(Intent(context,LayoutEditorActivity::class.java).putExtra("blank",blank)) }
            },LayoutParams(0,-2,1f).apply { marginEnd=dp(6) })
            addView(actions,LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
        }
        addView(variants)
        for(pair in KeyboardLayouts.families.chunked(2)) {
            val row=LinearLayout(context)
            pair.forEachIndexed { index,family ->
                val box=LinearLayout(context).apply {
                    orientation=VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(8));isClickable=true;isFocusable=true;isSoundEffectsEnabled=false
                    contentDescription=context.tr("Keyboard layout: %1\$s",context.tr(family.title))
                    setOnClickListener {
                        val current=prefs.currentLayout;val automatic=KeyboardLayouts.automatic()
                        val layout=when { current.family==family.id && current.keyRows.isEmpty() -> current;automatic.family==family.id -> automatic;else -> KeyboardLayouts.variants(family.id).first() }
                        choose(layout.id)
                    }
                }
                box.addView(LayoutPreview(context,prefs) {
                    val current=prefs.currentLayout
                    if(current.family==family.id && current.keyRows.isEmpty()) current else KeyboardLayouts.variants(family.id).first()
                },LayoutParams(-1,dp(64)))
                val title=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(7),0,0) }
                title.addView(text(context.tr(family.title),14f),LayoutParams(0,-2,1f))
                val check=text("✓",15f,accent);checkmarks[family.id]=check;title.addView(check);box.addView(title)
                box.addView(text(context.tr(family.note),11f,muted).apply { maxLines=2 })
                row.addView(box,LayoutParams(0,dp(128),1f).apply { if(index==0) marginEnd=dp(8) })
                choices.add(family.id to box)
            }
            if(pair.size==1) row.addView(View(context),LayoutParams(0,1,1f))
            addView(row,LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        refresh()
    }
    private fun choose(id:String) {
        prefs.layout=id
        val layout=prefs.currentLayout
        if(layout.phonetic) Phonetic.shared(context,layout.dictionary).request("") { }
        refresh();selected()
    }
    private fun refresh() {
        val current=prefs.currentLayout
        custom.removeAllViews()
        if(prefs.layouts().isNotEmpty()) custom.addView(text(context.tr("Your layouts"),14f).apply { setPadding(0,0,0,dp(8)) })
        for(saved in prefs.layouts()) {
            val tile=LinearLayout(context).apply {
                orientation=VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(current.id==saved.id)
                contentDescription=context.tr("Custom layout: %1\$s",saved.name);isClickable=true;isFocusable=true;setOnClickListener { choose(saved.id) }
            }
            tile.addView(LayoutPreview(context,prefs) { saved.layout() },LayoutParams(-1,dp(70)))
            val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            row.addView(text(saved.name+if(current.id==saved.id) " ✓" else "",15f),LayoutParams(0,-2,1f))
            if(editing) {
                for(title in listOf("Edit","Delete")) row.addView(text(context.tr(title),13f,accent).apply {
                    setPadding(dp(12),dp(12),dp(12),dp(12));isClickable=true;isFocusable=true;contentDescription=context.tr("%1\$s layout: %2\$s",context.tr(title),saved.name)
                    setOnClickListener {
                        if(title=="Edit") context.startActivity(Intent(context,LayoutEditorActivity::class.java).putExtra("layout",saved.id))
                        else AlertDialog.Builder(context).setTitle(context.tr("Delete %1\$s?",saved.name)).setNegativeButton(context.tr("Cancel"),null).setPositiveButton(context.tr("Delete")) { _,_ ->
                            CustomLayouts.delete(prefs,saved.id);refresh();selected()
                        }.show()
                    }
                })
            }
            tile.addView(row);custom.addView(tile,LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        }
        automatic.background=shape(prefs.automaticLayout);autoCheck.visibility=if(prefs.automaticLayout) VISIBLE else INVISIBLE
        for((family,box) in choices) {
            val chosen=current.keyRows.isEmpty() && (!prefs.automaticLayout || !automaticChoice) && current.family==family
            box.background=shape(chosen);box.isSelected=chosen;checkmarks[family]?.visibility=if(chosen) VISIBLE else INVISIBLE
            box.invalidate();(box.getChildAt(0) as? LayoutPreview)?.invalidate()
        }
        variants.removeAllViews()
        val layouts=KeyboardLayouts.variants(current.family)
        if(layouts.size<=1) return
        variants.addView(text(context.tr("%1\$s variants",context.tr(KeyboardLayouts.families.first { it.id==current.family }.title)),12f,muted).apply { setPadding(0,0,0,dp(6)) })
        val chips=LinearLayout(context)
        layouts.forEach { layout ->
            chips.addView(text(context.layoutVariant(layout),12f,if(layout.id==current.id) accent else ink).apply {
                setPadding(dp(12),dp(10),dp(12),dp(10));background=shape(layout.id==current.id)
                isClickable=true;isFocusable=true;isSoundEffectsEnabled=false;contentDescription=context.tr("Layout variant: %1\$s",context.tr(layout.variant))
                setOnClickListener { choose(layout.id) }
            },LayoutParams(-2,-2).apply { marginEnd=dp(6) })
        }
        variants.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false;addView(chips) },LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val help=when(current.id) {
            "hindi" -> context.tr("InScript positions. Flick up on र for ृ; hold for ऋ and other vowel forms.")
            "hindi_phonetic" -> context.tr("Type namaste → नमस्ते. Space accepts a word; tap a suggestion to choose another spelling.")
            "bolnagri" -> context.tr("Hindi characters in phonetic QWERTY positions. Shift or hold a key for its other forms.")
            "jyutping" -> context.tr("Type neihou → 你好. Optional tones: nei5hou2. Space accepts; the arrow shows more candidates.")
            "cangjie" -> context.tr("Cangjie 5 · Type character shape codes using the radical hints. Space accepts; the arrow shows more candidates.")
            "sucheng" -> context.tr("Sucheng · Type the first and last Cangjie codes of a character. Space accepts; the arrow shows more candidates.")
            else -> ""
        }
        if(help.isNotEmpty()) variants.addView(text(help,12f,muted).apply { setPadding(0,0,0,dp(12)) })
    }
}
