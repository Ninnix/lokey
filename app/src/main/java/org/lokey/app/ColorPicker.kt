package org.lokey.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.Locale

object ColorPicker {
    fun show(activity:Activity,prefs:Prefs,title:String,initial:Int,apply:(Int)->Unit,reset:(()->Unit)?) {
        val sheet=EditorSheet(activity,prefs,title)
        fun dp(value:Int)=sheet.dp(value)
        val colors=listOf(activity.tr("Paper") to 0xffefece4.toInt(),activity.tr("White") to Color.WHITE,activity.tr("Gray") to 0xffb3b6b2.toInt(),activity.tr("Charcoal") to 0xff171819.toInt(),
            activity.tr("Coral") to 0xfff05c5e.toInt(),activity.tr("Orange") to 0xffff5a00.toInt(),activity.tr("Yellow") to 0xffffc43d.toInt(),activity.tr("Sand") to 0xffe9dcc0.toInt(),
            activity.tr("Green") to 0xff4daa7a.toInt(),activity.tr("Mint") to 0xff5dc8b0.toInt(),activity.tr("Teal") to 0xff427f8b.toInt(),activity.tr("Sea blue") to 0xff90a9b1.toInt(),
            activity.tr("Blue") to 0xff508dc2.toInt(),activity.tr("Navy") to 0xff183a5b.toInt(),activity.tr("Purple") to 0xff8269b1.toInt(),activity.tr("Pink") to 0xffd485a6.toInt())
        val preview=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(16),0) }
        val letters=sheet.text("Aa",28f)
        val colorName=sheet.text("",14f).apply { gravity=Gravity.END }
        preview.addView(letters,LinearLayout.LayoutParams(0,-2,1f));preview.addView(colorName)
        sheet.body.addView(preview,LinearLayout.LayoutParams(-1,dp(64)))
        val hexRow=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;background=sheet.shape();setPadding(dp(14),0,dp(12),0) }
        hexRow.addView(sheet.text("HEX",11f,sheet.muted),LinearLayout.LayoutParams(dp(48),-2))
        val hex=EditText(activity).apply {
            isSingleLine=true;hint="#RRGGBB";contentDescription=activity.tr("Hex color");setSelectAllOnFocus(true);textSize=16f;setTextColor(sheet.ink);setHintTextColor(sheet.muted)
            typeface=Typeface.MONOSPACE;background=null;setPadding(0,dp(10),0,dp(10));inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
        }
        hexRow.addView(hex,LinearLayout.LayoutParams(0,dp(48),1f))
        sheet.body.addView(hexRow,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12);bottomMargin=dp(12) })
        var color=initial;var updating=false
        val bars=ArrayList<SeekBar>();val labels=ArrayList<TextView>();val chips=ArrayList<Pair<TextView,Int>>()
        fun refresh(updateHex:Boolean=true) {
            updating=true
            preview.background=sheet.shape(color)
            letters.setTextColor(KeyboardTheme.ink(color));colorName.setTextColor(KeyboardTheme.ink(color))
            colorName.text=colors.firstOrNull { it.second==color }?.first ?: activity.tr("Custom color")
            if(updateHex) hex.setText(String.format(Locale.ROOT,"#%06X",color and 0xffffff))
            val values=listOf(Color.red(color),Color.green(color),Color.blue(color))
            bars.forEachIndexed { i,bar -> bar.progress=values[i];labels[i].text=values[i].toString() }
            for((chip,value) in chips) {
                chip.text=if(color==value) "✓" else "";chip.isSelected=color==value
                chip.background=sheet.shape(value,10).apply { if(color==value) setStroke(dp(2),KeyboardTheme.ink(value)) }
            }
            updating=false
        }
        for(group in colors.chunked(4)) {
            val row=LinearLayout(activity)
            for((name,value) in group) {
                val chip=sheet.text("",18f,KeyboardTheme.ink(value)).apply {
                    gravity=Gravity.CENTER;contentDescription=name;isClickable=true;isFocusable=true;isSoundEffectsEnabled=false
                    setOnClickListener { color=value;refresh() }
                }
                chips.add(chip to value)
                row.addView(chip,LinearLayout.LayoutParams(0,dp(44),1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
            }
            sheet.body.addView(row)
        }
        sheet.body.addView(sheet.text(activity.tr("Fine-tune"),12f,sheet.muted).apply { setPadding(0,dp(14),0,dp(4)) })
        listOf(activity.tr("Red"),activity.tr("Green"),activity.tr("Blue")).forEachIndexed { index,name ->
            val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
            row.addView(sheet.text(name,12f,sheet.muted),LinearLayout.LayoutParams(dp(44),-2))
            val bar=SeekBar(activity).apply {
                max=255;contentDescription=activity.tr("%1\$s channel",name);progressTintList=ColorStateList.valueOf(sheet.accent);thumbTintList=ColorStateList.valueOf(sheet.accent)
                progressBackgroundTintList=ColorStateList.valueOf(sheet.surface)
            }
            row.addView(bar,LinearLayout.LayoutParams(0,dp(44),1f))
            val value=sheet.text("",12f).apply { gravity=Gravity.END;typeface=Typeface.MONOSPACE }
            row.addView(value,LinearLayout.LayoutParams(dp(34),-2));labels.add(value);bars.add(bar)
            sheet.body.addView(row)
            bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view:SeekBar,value:Int,user:Boolean) {
                    if(!user || updating) return
                    val channels=intArrayOf(Color.red(color),Color.green(color),Color.blue(color));channels[index]=value
                    color=Color.rgb(channels[0],channels[1],channels[2]);refresh()
                }
                override fun onStartTrackingTouch(view:SeekBar) { }
                override fun onStopTrackingTouch(view:SeekBar) { }
            })
        }
        hex.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { }
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { }
            override fun afterTextChanged(value:Editable?) {
                if(updating) return
                val raw=value.toString().removePrefix("#")
                if(raw.length==6) raw.toIntOrNull(16)?.let { color=it or (0xff shl 24);refresh(false) }
            }
        })
        sheet.action(activity.tr("Cancel")) { sheet.dismiss() }
        if(reset!=null) sheet.action(activity.tr("Use palette")) { sheet.dismiss();reset() }
        sheet.action(activity.tr("Apply"),true) {
            val raw=hex.text.toString().removePrefix("#")
            if(raw.length!=6 || raw.toIntOrNull(16)==null) hex.error=activity.tr("Use six hex digits")
            else { sheet.dismiss();apply(color) }
        }
        refresh();sheet.show()
    }
}
