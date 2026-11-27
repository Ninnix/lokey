package org.lokey.app

import android.content.Context
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

internal class EmojiPanel(context:Context,prefs:Prefs,back:()->Unit,insert:(String)->Unit):LinearLayout(context) {
    init {
        val style=ToolStyle(context,prefs)
        orientation=VERTICAL
        setBackgroundColor(style.colors.background)
        setPadding(style.dp(6),style.dp(4),style.dp(6),style.dp(8))
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(ToolButton(context,"back",context.tr("Back to keyboard"),style.colors.ink,back),LayoutParams(style.dp(44),style.dp(44)))
        header.addView(style.text(context.tr("Emoji"),18f))
        addView(header)
        val grid=LinearLayout(context).apply { orientation=VERTICAL }
        val columns=(resources.configuration.screenWidthDp/44).coerceIn(5,12)
        val font=Paint()
        for(items in EmojiHistory.catalog.filter(font::hasGlyph).chunked(columns)) {
            val row=LinearLayout(context)
            for(emoji in items) row.addView(style.text(emoji,23f).apply {
                gravity=Gravity.CENTER
                isFocusable=true;isSoundEffectsEnabled=false
                background=style.shape(style.colors.special)
                setOnClickListener { insert(emoji) }
            },LayoutParams(0,style.dp(46),1f).apply { setMargins(style.dp(2),style.dp(2),style.dp(2),style.dp(2)) })
            repeat(columns-items.size) { row.addView(View(context),LayoutParams(0,style.dp(46),1f).apply { setMargins(style.dp(2),0,style.dp(2),0) }) }
            grid.addView(row)
        }
        addView(ScrollView(context).apply { addView(grid) },LayoutParams(-1,0,1f))
    }
}
