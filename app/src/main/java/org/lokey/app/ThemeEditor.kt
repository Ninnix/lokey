package org.lokey.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

class ThemeEditor(private val activity:Activity,private val prefs:Prefs):LinearLayout(activity) {
    private val ink get()=if(prefs.appLight) Color.rgb(28,29,30) else Color.rgb(245,240,231)
    private val accent get()=if(prefs.appLight) Color.rgb(255,90,0) else Color.rgb(240,92,94)
    private val surface get()=if(prefs.appLight) Color.rgb(237,237,233) else Color.rgb(35,36,37)
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun shape(color:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(14).toFloat() }
    private fun text(value:String,size:Float=14f)=TextView(context).apply { text=value;textSize=size;setTextColor(ink);setPadding(0,dp(5),0,dp(5)) }
    private fun card():LinearLayout=LinearLayout(context).apply {
        orientation=VERTICAL;background=shape(surface);setPadding(dp(14),dp(12),dp(14),dp(12))
        this@ThemeEditor.addView(this,LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
    }
    private fun button(value:String,action:()->Unit)=text(value).apply {
        gravity=Gravity.CENTER;setTextColor(accent);setPadding(dp(8),dp(12),dp(8),dp(12));isClickable=true;setOnClickListener { if(!busy) action() }
    }
    init { orientation=VERTICAL;render() }
    private fun custom() {
        prefs.designDirty=true;prefs.activeDesign=""
        if(prefs.keyboardTheme !in listOf("custom","image")) {
            val colors=KeyboardTheme.colors(prefs)
            prefs.themeBackground=colors.background;prefs.themeKeys=colors.keys;prefs.themeAccent=colors.accent
            if(prefs.keyboardTheme=="wave") { prefs.themePicture="wave";prefs.keyboardTheme="image" } else prefs.keyboardTheme="custom"
        }
    }
    private fun image() { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*";addCategory(Intent.CATEGORY_OPENABLE) },11) }
    private fun render() {
        removeAllViews()
        card().apply {
            addView(text(context.tr("Keyboard themes"),18f))
            addView(text(context.tr("Choose a starting point, then edit its colors below."),12f))
            val saved=DesignPresets.all(prefs)
            val presets=mutableListOf("dark" to context.tr("Dark"),"light" to context.tr("Light"),"wave" to context.tr("Great Wave"))
            presets.addAll(saved.map { it.id to it.name })
            presets.add("image" to context.tr("Your image"))
            for(pair in presets.chunked(2)) {
                val row=LinearLayout(context)
                for((id,name) in pair) {
                    val design=saved.firstOrNull { it.id==id }
                    val selected=if(prefs.activeDesign.isNotEmpty()) prefs.activeDesign==id else if(prefs.keyboardTheme=="image" && prefs.themePicture=="wave") id=="wave" else prefs.keyboardTheme==id
                    fun select() {
                        if(busy) return
                        if(design!=null) { work { check(DesignPresets.apply(prefs,id)); };return }
                        if(id=="image") { image();return }
                        prefs.useTheme(id);render()
                    }
                    val tile=LinearLayout(context).apply {
                        orientation=VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(6))
                        background=shape(if(selected) KeyboardTheme.blend(surface,accent,.12f) else surface)
                        contentDescription=context.tr("%1\$s keyboard theme",name);isClickable=true;isFocusable=true;setOnClickListener { select() }
                    }
                    val preview=if(id=="image" && (!prefs.userImageChosen || !KeyboardTheme.imageFile(context).exists()))
                        UploadPreview(context,accent) { select() }
                    else PresetPreview(context,prefs,id,design=design) { select() }
                    tile.addView(preview,LayoutParams(-1,-2))
                    tile.addView(text(name+if(selected) " ●" else "",13f).apply { gravity=Gravity.CENTER })
                    if(design!=null) tile.addView(button(context.tr("Delete")) {
                        AlertDialog.Builder(context).setTitle(context.tr("Delete %1\$s?",design.name)).setMessage(context.tr("Your current keyboard design will stay as it is."))
                            .setNegativeButton(context.tr("Cancel"),null).setPositiveButton(context.tr("Delete")) { _,_ -> work { DesignPresets.delete(prefs,id) } }.show()
                    }.apply { contentDescription=context.tr("Delete design: %1\$s",design.name) })
                    row.addView(tile,LayoutParams(0,-2,1f).apply { setMargins(0,dp(6),dp(6),dp(6)) })
                }
                if(pair.size==1) row.addView(View(context),LayoutParams(0,1,1f))
                addView(row,LayoutParams(-1,-2))
            }
            if(prefs.designDirty) addView(button(context.tr("Save as new preset")) { save() }.apply { background=shape(accent);setTextColor(KeyboardTheme.ink(accent)) },LayoutParams(-1,-2).apply { topMargin=dp(8) })
        }
        card().apply {
            addView(text(context.tr("Your palette"),18f))
            addView(text(context.tr("Keys applies one color to every key and resets row and individual colors. Enter and active Caps use Accent."),12f))
            val colors=KeyboardTheme.colors(prefs)
            val choices=mutableListOf(Triple(context.tr("Background"),colors.background,"background"),Triple(context.tr("Accent"),colors.accent,"accent"))
            choices.add(1,Triple(context.tr("Keys"),colors.keys,"keys"))
            choices.forEach { (label,color,id) ->
                addView(button(label) {
                    pick(label,color,{ chosen ->
                        if(id=="keys") prefs.uniformKeys(chosen)
                        else { custom();if(id=="background") prefs.themeBackground=chosen else prefs.themeAccent=chosen }
                        render()
                    })
                }.apply { background=shape(color);setTextColor(KeyboardTheme.ink(color)) },LayoutParams(-1,dp(44)).apply { topMargin=dp(6) })
            }
            if(prefs.keyboardTheme in listOf("image","wave")) addView(button(context.tr("Match colors to image")) {
                val builtin=prefs.keyboardTheme=="wave" || prefs.themePicture=="wave"
                KeyboardTheme.image(context,prefs.themeImageVersion,builtin) { bitmap ->
                    if(bitmap!=null) { custom();KeyboardTheme.applyPalette(prefs,bitmap);post { render() } }
                }
            })
        }
        card().apply {
            addView(text(context.tr("Row colors"),18f))
            addView(text(context.tr("Color a whole row. Action keys keep their accent."),12f))
            val layout=prefs.currentLayout
            val count=layout.rows.size
            val names=if(layout.keyRows.isNotEmpty()) layout.keyRows.indices.map { context.tr("Row %1\$s",it+1) } else listOf(context.tr("Numbers"))+(if(count==3) listOf(context.tr("Top letters"),context.tr("Middle letters"),context.tr("Bottom letters")) else (1..count).map { context.tr("Letter row %1\$s",it) })+context.tr("Spacebar row")
            for(pair in names.withIndex().toList().chunked(2)) {
                val row=LinearLayout(context)
                pair.forEach { (index,name) ->
                    val color=prefs.rowColor(index) ?: KeyboardTheme.colors(prefs).keys
                    row.addView(button(name) {
                        pick(name,color,{ chosen -> custom();prefs.rowColor(index,chosen);render() },{ custom();prefs.rowColor(index,null);render() })
                    }.apply { background=shape(color);setTextColor(KeyboardTheme.ink(color)) },LayoutParams(0,dp(48),1f).apply { setMargins(0,dp(5),dp(6),dp(5)) })
                }
                if(pair.size==1) row.addView(View(context),LayoutParams(0,1,1f))
                addView(row)
            }
            addView(button(context.tr("Reset row colors")) { custom();prefs.clearRowColors();render() })
        }
        card().apply {
            addView(text(context.tr("Individual keys"),18f));addView(text(context.tr("Tap a key below to set its color. Enter and active Caps use the accent unless you override them."),12f))
            val preview=KeyboardView(context,prefs,object:KeyboardView.Listener {
                override fun text(text:String) { }
                override fun delete() { }
                override fun enter() { }
                override fun cursor(delta:Int) { }
                override fun voice(start:Boolean)=false
                override fun cancelVoice() { }
                override fun nextKeyboard() { }
                override fun candidate(word:String) { }
                override fun feedback(key:String,down:Boolean) { }
            })
            preview.editor(false,context.tr("Done"),false)
            preview.chooseKey={ code ->
                val action=prefs.currentLayout.keyRows.flatten().firstOrNull { it.id==code }?.action ?: code
                val color=prefs.keyColor(code) ?: if(action=="enter" || action=="shift") KeyboardTheme.colors(prefs).accent else KeyboardTheme.colors(prefs).keys
                pick(context.tr("Key: %1\$s",code),color,{ chosen -> custom();prefs.keyColor(code,chosen);render() },{ custom();prefs.keyColor(code,null);render() })
            }
            addView(preview,LayoutParams(-1,dp(300)))
            addView(button(context.tr("Reset individual key colors")) { custom();prefs.clearKeyColors();render() })
        }
    }
    private var busy=false
    private fun work(action:()->Unit) {
        if(busy) return
        busy=true;alpha=.6f
        KeyboardTheme.io.execute {
            val result=runCatching(action)
            post {
                busy=false;alpha=1f
                result.exceptionOrNull()?.let { Toast.makeText(context,context.tr(it.message ?: "Could not save design"),Toast.LENGTH_LONG).show() }
                render()
            }
        }
    }
    private fun save() {
        val sheet=EditorSheet(context,prefs,context.tr("Save as new preset"))
        sheet.body.addView(sheet.text(context.tr("Preset name"),12f,sheet.muted).apply { setPadding(0,0,0,dp(6)) })
        val name=EditText(context).apply {
            hint=context.tr("Preset name");contentDescription=context.tr("Preset name");isSingleLine=true;textSize=16f;setTextColor(sheet.ink);setHintTextColor(sheet.muted)
            background=sheet.shape();setPadding(dp(14),dp(12),dp(14),dp(12));filters=arrayOf(android.text.InputFilter.LengthFilter(80))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        sheet.body.addView(name)
        sheet.action(context.tr("Cancel")) { sheet.dismiss() }
        sheet.action(context.tr("Save"),true) {
            if(name.text.isBlank()) { name.error=context.tr("Enter a name");return@action }
            val label=name.text.toString();sheet.dismiss();work { DesignPresets.save(prefs,label) }
        }
        sheet.show()
    }
    private fun pick(title:String,initial:Int,apply:(Int)->Unit,reset:(()->Unit)?=null) {
        ColorPicker.show(activity,prefs,title,initial,apply,reset)
    }
}
