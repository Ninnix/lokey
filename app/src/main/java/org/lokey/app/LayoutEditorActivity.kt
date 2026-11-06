package org.lokey.app

import android.app.AlertDialog
import android.content.ClipData
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*

class LayoutEditorActivity:AppScreen() {
    private lateinit var draft:CustomLayouts.Saved
    private var selected:String?=null
    private var dirty=false
    private var scrollY=0
    private lateinit var scroll:ScrollView
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        draft=state?.getString("draft")?.let { CustomLayouts.decode(org.json.JSONObject(it)) }
            ?: prefs.layouts().firstOrNull { it.id==intent.getStringExtra("layout") }
            ?: CustomLayouts.create(prefs,intent.getBooleanExtra("blank",false))
        dirty=state?.getBoolean("dirty") ?: false;render()
        if(android.os.Build.VERSION.SDK_INT>=33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
    }
    private fun changed(rows:List<List<CustomLayouts.Key>>) { draft=draft.copy(rows=rows);dirty=true;render() }
    private fun back() {
        if(!dirty) { finish();return }
        AlertDialog.Builder(this).setTitle(tr("Discard layout changes?")).setNegativeButton(tr("Keep editing"),null).setPositiveButton(tr("Discard")) { _,_ -> finish() }.show()
    }
    @Deprecated("Uses the activity back callback")
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed()=back()
    override fun render() {
        if(::scroll.isInitialized) scrollY=scroll.scrollY
        val root=frame(tr("Edit layout")) { back() }
        scroll=ScrollView(this)
        val body=vertical().apply { setPadding(dp(20),dp(8),dp(20),dp(12)) }
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val name=EditText(this).apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            isSingleLine=true;hint=tr("Layout name");contentDescription=tr("Layout name");setTextColor(ink);setHintTextColor(muted)
            background=shape();setPadding(dp(14),dp(10),dp(14),dp(10));setText(draft.name)
            filters=arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        name.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { }
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { draft=draft.copy(name=s.toString());dirty=true }
            override fun afterTextChanged(s:Editable?) { }
        })
        body.addView(name)
        body.addView(text(tr("Tap a key to change its text, action, or secondary choices. Hold and drag to move it, including between rows."),13f,muted).apply { setPadding(0,dp(12),0,dp(12)) })
        body.addView(LayoutPreview(this,prefs) { draft.layout() },LinearLayout.LayoutParams(-1,dp(118)))
        body.addView(text(tr("%1\$s input rules",layoutName(KeyboardLayouts.find(draft.base))),12f,muted).apply { setPadding(0,dp(8),0,dp(8)) })
        draft.rows.forEachIndexed { rowIndex,keys ->
            val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            header.addView(text(tr("ROW %1\$s",rowIndex+1),11f,muted),LinearLayout.LayoutParams(0,-2,1f))
            header.addView(button(tr("Add key")) { edit(rowIndex,null) }.apply { contentDescription=tr("Add key to row %1\$s",rowIndex+1) })
            header.addView(button("×") {
                if(keys.isEmpty()) changed(draft.rows.filterIndexed { index,_ -> index!=rowIndex })
                else AlertDialog.Builder(this).setTitle(tr("Delete row %1\$s?",rowIndex+1)).setMessage(tr("This removes all its keys. Changes apply when you save."))
                    .setNegativeButton(tr("Cancel"),null).setPositiveButton(tr("Delete")) { _,_ -> changed(draft.rows.filterIndexed { index,_ -> index!=rowIndex }) }.show()
            }.apply { contentDescription=tr("Delete row %1\$s",rowIndex+1) })
            body.addView(header,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
            val row=LinearLayout(this).apply { layoutDirection=View.LAYOUT_DIRECTION_LTR;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(60);background=shape() }
            for((index,key) in keys.withIndex()) {
                val tile=vertical().apply {
                    gravity=Gravity.CENTER;setPadding(dp(7),dp(5),dp(7),dp(5));background=shape(if(selected==key.id) accent else surface)
                    contentDescription=tr("Edit key: %1\$s",key.label);isClickable=true;isFocusable=true
                    setOnClickListener { selected=key.id;edit(rowIndex,key) }
                    setOnLongClickListener {
                        selected=key.id
                        startDragAndDrop(ClipData.newPlainText(tr("Lokey key"),key.id),View.DragShadowBuilder(this),key.id,0)
                    }
                }
                tile.addView(text(key.label.ifEmpty { tr("Key") },16f,if(selected==key.id) bg else ink).apply { gravity=Gravity.CENTER;maxLines=1 })
                tile.addView(text(key.secondary.joinToString(" "),10f,muted).apply { gravity=Gravity.CENTER;maxLines=1 })
                drop(tile,rowIndex,index)
                row.addView(tile,LinearLayout.LayoutParams(dp((44*key.width).toInt().coerceAtMost(130)),dp(58)).apply { marginEnd=dp(4) })
            }
            val end=text("+",22f,muted).apply { gravity=Gravity.CENTER;contentDescription=tr("Drop at end of row %1\$s",rowIndex+1);setOnClickListener { edit(rowIndex,null) } }
            drop(end,rowIndex,keys.size);row.addView(end,LinearLayout.LayoutParams(dp(48),dp(58)))
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false;addView(row) })
        }
        body.addView(button(tr("Add row")) { changed(draft.rows+listOf(emptyList())) },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14) })
        val footer=vertical().apply { setPadding(dp(20),dp(8),dp(20),dp(16)) }
        footer.addView(button(tr("Save layout"),true) {
            runCatching {
                CustomLayouts.save(prefs,draft.copy(name=draft.name.trim()))
                prefs.layout=draft.id;dirty=false;finish()
            }.exceptionOrNull()?.let { Toast.makeText(this,it.message?.let { tr(it) },Toast.LENGTH_LONG).show() }
        }.apply { contentDescription=tr("Save custom layout") })
        root.addView(footer)
        scroll.post { scroll.scrollTo(0,scrollY) }
    }
    private fun drop(view:View,row:Int,index:Int) {
        view.setOnDragListener { target,event ->
            when(event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                DragEvent.ACTION_DRAG_ENTERED -> { target.alpha=.5f;true }
                DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED -> { target.alpha=1f;true }
                DragEvent.ACTION_DROP -> { target.alpha=1f;move(event.localState as String,row,index);true }
                else -> true
            }
        }
    }
    private fun move(id:String,row:Int,index:Int) {
        val sourceRow=draft.rows.indexOfFirst { keys -> keys.any { it.id==id } }
        if(sourceRow<0) return
        val sourceIndex=draft.rows[sourceRow].indexOfFirst { it.id==id }
        val rows=draft.rows.map { it.toMutableList() }
        val key=rows[sourceRow].removeAt(sourceIndex)
        rows[row].add((index-if(sourceRow==row && sourceIndex<index) 1 else 0).coerceIn(0,rows[row].size),key)
        changed(rows)
    }
    private fun edit(rowIndex:Int,original:CustomLayouts.Key?) {
        val key=original ?: CustomLayouts.Key()
        val sheet=EditorSheet(this,prefs,if(original==null) tr("Add key") else tr("Edit %1\$s",key.label))
        val box=sheet.body
        fun field(label:String,value:String,multiline:Boolean=false):EditText {
            box.addView(sheet.text(label,12f,sheet.muted).apply { setPadding(0,dp(12),0,dp(6)) })
            return EditText(this).apply {
                setText(value);hint=label;contentDescription=label;textSize=16f;setTextColor(sheet.ink);setHintTextColor(sheet.muted)
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or (if(multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                if(!multiline) isSingleLine=true
                background=sheet.shape();setPadding(dp(14),dp(12),dp(14),dp(12));box.addView(this)
            }
        }
        var action=key.action
        box.addView(sheet.text(tr("Primary function"),12f,sheet.muted))
        val actionChoice=sheet.button(tr(CustomLayouts.actions[action].orEmpty())+"  ›") { }.apply { gravity=Gravity.START or Gravity.CENTER_VERTICAL;contentDescription=tr("Key action") }
        box.addView(actionChoice,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        val primary=field(tr("Primary text"),key.primary)
        fun updateAction() {
            actionChoice.text=tr(CustomLayouts.actions[action].orEmpty())+"  ›"
            primary.isEnabled=action.isEmpty();primary.alpha=if(action.isEmpty()) 1f else .4f
        }
        actionChoice.setOnClickListener {
            val chooser=EditorSheet(this,prefs,tr("Primary function"))
            for((id,title) in CustomLayouts.actions) chooser.body.addView(chooser.button(tr(title)+if(action==id) "  ✓" else "") {
                action=id;updateAction();chooser.dismiss()
            }.apply { contentDescription=tr("Key action: %1\$s",tr(title)) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
            chooser.action(tr("Cancel")) { chooser.dismiss() };chooser.show()
        }
        updateAction()
        val secondary=field(tr("Secondary choices · one per line"),key.secondary.joinToString("\n"),true)
        secondary.minLines=2;secondary.maxLines=4
        box.addView(sheet.text(tr("Symbols, emoji, or text. Flick up for the first choice."),12f,sheet.muted).apply { setPadding(0,dp(6),0,dp(10)) })
        val widthRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        widthRow.addView(sheet.text(tr("Key width"),13f,sheet.muted),LinearLayout.LayoutParams(0,-2,1f))
        val widthLabel=sheet.text(key.width.toString(),13f);widthRow.addView(widthLabel);box.addView(widthRow)
        var width=key.width
        box.addView(SeekBar(this).apply {
            max=30;progress=((key.width-.5f)*4).toInt();contentDescription=tr("Key width")
            progressTintList=android.content.res.ColorStateList.valueOf(sheet.accent);thumbTintList=android.content.res.ColorStateList.valueOf(sheet.accent)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean) { if(user) { width=.5f+value*.25f;widthLabel.text=width.toString() } }
                override fun onStartTrackingTouch(bar:SeekBar) { }
                override fun onStopTrackingTouch(bar:SeekBar) { }
            })
        },LinearLayout.LayoutParams(-1,dp(44)))
        fun value():CustomLayouts.Key? {
            if(primary.text.isEmpty() && action.isEmpty()) { primary.error=tr("Enter text or choose an action");return null }
            val choices=secondary.text.toString().split('\n').filter { it.isNotEmpty() }
            if(choices.size>32) { secondary.error=tr("Use up to 32 choices");return null }
            return key.copy(primary=primary.text.toString(),secondary=choices,action=action,width=width)
        }
        fun replace(value:CustomLayouts.Key):List<List<CustomLayouts.Key>> {
            val rows=draft.rows.map { it.toMutableList() }
            if(original==null) rows[rowIndex].add(value) else rows[rowIndex][rows[rowIndex].indexOfFirst { it.id==key.id }]=value
            return rows
        }
        if(original!=null) {
            box.addView(sheet.text(tr("Move key"),12f,sheet.muted).apply { setPadding(0,dp(8),0,dp(6)) })
            val row=LinearLayout(this);val index=draft.rows[rowIndex].indexOfFirst { it.id==key.id }
            listOf(rowIndex to index-1,rowIndex to index+2,rowIndex-1 to index,rowIndex+1 to index).forEachIndexed { i,(r,c) ->
                val arrow=listOf("←","→","↑","↓")[i]
                row.addView(sheet.button(arrow) {
                    val value=value() ?: return@button
                    draft=draft.copy(rows=replace(value));sheet.dismiss();move(key.id,r,c)
                }.apply {
                    textSize=22f;contentDescription=tr("Move key %1\$s",arrow)
                    isEnabled=r in draft.rows.indices && (r!=rowIndex || (if(i==0) index>0 else index<draft.rows[r].lastIndex))
                    alpha=if(isEnabled) 1f else .3f
                },LinearLayout.LayoutParams(0,dp(48),1f).apply { if(i>0) marginStart=dp(6) })
            }
            box.addView(row)
        }
        sheet.action(tr("Cancel")) { sheet.dismiss() }
        if(original!=null) sheet.action(tr("Delete key")) { sheet.dismiss();changed(draft.rows.map { row -> row.filter { it.id!=key.id } }) }
        sheet.action(if(original==null) tr("Add key") else tr("Apply"),true) {
            val value=value() ?: return@action
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(primary.windowToken,0)
            sheet.dismiss();changed(replace(value))
        }
        sheet.show()
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putString("draft",draft.json().toString());state.putBoolean("dirty",dirty);super.onSaveInstanceState(state)
    }
}
