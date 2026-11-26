package org.lokey.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView

internal class LayoutTool(context:Context,private val prefs:Prefs,private val close:()->Unit):LinearLayout(context) {
    private companion object {
        val regions=mapOf(
            "intl" to "US GB","qwerty" to "EU 🌐","spanish" to "ES MX","portuguese" to "PT BR","turkish" to "TR","vietnamese" to "VN",
            "azerty" to "FR BE","qwertz" to "DE AT CH","cyrillic" to "RU","ukrainian" to "UA","arabic" to "SA EG","persian" to "IR",
            "greek" to "GR","hindi" to "IN","hindi_phonetic" to "IN","bolnagri" to "IN","korean" to "KR","japanese" to "JP",
            "pinyin" to "CN SG","jyutping" to "HK","cangjie" to "TW HK","sucheng" to "HK","thai" to "TH"
        )
    }
    private val style=ToolStyle(context,prefs)
    private val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
    private val grid=LinearLayout(context).apply { orientation=VERTICAL }
    private val scroll=ScrollView(context).apply { addView(grid) }
    private var adding=false

    init {
        orientation=VERTICAL
        setPadding(style.dp(6),style.dp(4),style.dp(6),style.dp(8))
        setBackgroundColor(style.colors.background)
        addView(header)
        addView(scroll,LayoutParams(-1,0,1f))
        render()
    }

    fun back() {
        if(adding) { adding=false;render() } else close()
    }

    private fun render(y:Int=0) {
        val choices=prefs.layoutChoices()
        val shortcuts=prefs.quickLayouts.filter { it in choices }
        val current=prefs.layoutSelection
        val available=choices.keys.filter { it!=current && it !in shortcuts }
        header.removeAllViews()
        header.addView(ToolButton(context,"back",context.tr(if(adding) "Back" else "Back to keyboard"),style.colors.ink,::back),LayoutParams(style.dp(44),style.dp(44)))
        header.addView(style.text(context.tr(if(adding) "Add layout" else "Keyboard layout"),18f),LayoutParams(0,-2,1f))
        if(!adding && available.isNotEmpty()) header.addView(style.action(context.tr("Add layout")) { adding=true;render() })
        grid.removeAllViews()
        val width=resources.configuration.screenWidthDp
        val columns=when { width>=600 -> 4;width>=500 -> 3;else -> 2 }
        val visible=if(adding) available else shortcuts-current
        for(ids in visible.chunked(columns)) {
            val row=LinearLayout(context)
            ids.forEachIndexed { index,id ->
                val tile=tile(id,choices.getValue(id),shortcuts)
                row.addView(tile,LayoutParams(0,-1,1f).apply { if(index<columns-1) marginEnd=style.dp(6) })
            }
            for(index in ids.size until columns) row.addView(View(context),LayoutParams(0,-1,1f).apply { if(index<columns-1) marginEnd=style.dp(6) })
            grid.addView(row,LayoutParams(-1,-2).apply { bottomMargin=style.dp(6) })
        }
        scroll.post { scroll.scrollTo(0,y) }
    }

    private fun tile(id:String,layout:KeyboardLayouts.Layout,shortcuts:List<String>):View {
        val name=context.layoutName(layout)
        val title=if(id=="auto") "${context.tr("Automatic")} · $name" else name
        val tile=LinearLayout(context).apply {
            orientation=VERTICAL;background=style.shape(style.colors.special);contentDescription=title
            isClickable=true;isFocusable=true;isSoundEffectsEnabled=false
            setOnClickListener {
                if(adding) { prefs.quickLayouts=shortcuts+id;adding=false;render() }
                else { prefs.selectLayout(id,rememberPrevious=true);close() }
            }
        }
        if(!adding) tile.setOnLongClickListener { anchor ->
            removeMenu(anchor) { prefs.quickLayouts=shortcuts-id;render(scroll.scrollY) }
            true
        }
        tile.addView(style.text(flags(id,layout),24f).apply {
            minimumHeight=style.dp(44);gravity=Gravity.CENTER_VERTICAL
            setPaddingRelative(style.dp(10),0,0,0);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        tile.addView(style.text(title,15f).apply {
            maxLines=2;ellipsize=TextUtils.TruncateAt.END;setPaddingRelative(style.dp(10),0,style.dp(10),style.dp(10))
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return tile
    }

    private fun removeMenu(anchor:View,remove:()->Unit) {
        val fill=style.colors.special
        val red=if(KeyboardTheme.light(fill)) Color.rgb(166,27,39) else Color.rgb(255,116,124)
        val label=context.tr("Remove shortcut")
        val action=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL;minimumHeight=style.dp(48);setPaddingRelative(style.dp(6),0,style.dp(12),0)
            contentDescription=label;isFocusable=true;isSoundEffectsEnabled=false
        }
        action.addView(object:View(context) {
            override fun onDraw(canvas:Canvas) { ToolIcons.draw(canvas,"delete",width/2f,height/2f,style.dp(20).toFloat(),red) }
        },LayoutParams(style.dp(32),style.dp(40)))
        action.addView(style.text(label,15f,red).apply { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO })
        val popup=PopupWindow(action,LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT,true).apply {
            setBackgroundDrawable(style.shape(fill).apply { setStroke(style.dp(1),KeyboardTheme.blend(fill,KeyboardTheme.ink(fill),.2f)) })
            elevation=style.dp(5).toFloat();isOutsideTouchable=true;inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        action.setOnClickListener { popup.dismiss();remove() }
        popup.showAsDropDown(anchor,0,style.dp(4),Gravity.START)
    }

    private fun flags(id:String,layout:KeyboardLayouts.Layout):String {
        val country=if(id=="auto") KeyboardLayouts.deviceLocale().country else ""
        val codes=country.takeIf { it.length==2 && it.all { letter -> letter in 'A'..'Z' } } ?: regions[layout.behaviorId] ?: "🌐"
        return codes.split(' ').joinToString("  ") { code ->
            if(code=="🌐") code else buildString { for(letter in code) appendCodePoint(0x1f1e6+(letter-'A')) }
        }
    }
}
