package org.lokey.app

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*

class LanguageActivity:AppScreen() {
    private var languages=emptyList<ModelLanguages.Language>()
    private var query=""
    private var filter="all"
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);languages=ModelLanguages.all(this)
        query=state?.getString("query").orEmpty();filter=state?.getString("filter") ?: "all";render()
    }
    override fun render() {
        val root=frame(tr("Languages")) { finish() }
        val compact=resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val note=vertical().apply { setPadding(dp(24),dp(8),dp(24),dp(8)) }
        val intro=vertical().apply {
            addView(text(tr("Find your language."),26f,bold=true))
            addView(text(tr("39 languages covered by Parakeet v3 or Qwen3 ASR. Keyboard availability is shown separately."),12f,muted).apply { setPadding(0,dp(8),0,dp(10)) })
        }
        if(!compact) note.addView(intro)
        val search=EditText(this).apply {
            hint=tr("Search languages");contentDescription=tr("Search supported languages");textSize=16f;setTextColor(ink);setHintTextColor(muted)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine=true;setPadding(dp(14),dp(10),dp(14),dp(10));background=shape();setText(query)
        }
        note.addView(search,LinearLayout.LayoutParams(-1,-2))
        val filters=LinearLayout(this)
        for((label,id) in listOf(tr("All languages") to "all",tr("Key gaps") to "gaps")) filters.addView(button(label) {
            filter=id;render()
        }.apply { background=shape(selected=filter==id);contentDescription=tr("Language filter: %1\$s",label) },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(0,dp(8),dp(6),0) })
        note.addView(filters);root.addView(note)
        val count=text("",12f,muted).apply { setPadding(dp(24),dp(4),dp(24),dp(8)) };root.addView(count)
        val list=ListView(this).apply { divider=android.graphics.drawable.ColorDrawable(bg);dividerHeight=dp(6);isFastScrollEnabled=true;clipToPadding=false;setPadding(dp(24),0,dp(24),dp(8)) }
        fun update() {
            val rows=languages.filter { language ->
                val included=when(filter) { "voice" -> language.voiceModel!=null;"gaps" -> language.mapping?.status!="available";else -> true }
                included && (query.isBlank() || (language.searchText.contains(query,true) || tr(language.name).contains(query,true)))
            }
            count.text=if(rows.isEmpty() && filter=="gaps" && query.isBlank()) tr("All 39 languages have an input method") else tr("%1\$s languages & dialects",rows.size)
            list.adapter=object:BaseAdapter() {
                override fun getCount()=rows.size
                override fun getItem(position:Int)=rows[position]
                override fun getItemId(position:Int)=position.toLong()
                override fun getView(position:Int,convert:View?,parent:ViewGroup):View {
                    val language=rows[position]
                    return vertical().apply {
                        setPadding(dp(14),dp(12),dp(14),dp(12));background=shape()
                        addView(text(tr(language.name),16f,bold=true))
                        addView(text(tr("Text · %1\$s",tr(language.textDescription)),12f,muted))
                        addView(text(tr("Voice · %1\$s",tr(language.voiceDescription)),12f,muted))
                        addView(text(tr("Keys · %1\$s",tr(language.keyboardDescription)),12f,if(language.mapping?.status=="available") muted else accent))
                        language.mapping?.note?.takeIf { it.isNotEmpty() }?.let { addView(text(tr(it),11f,muted)) }
                        contentDescription=tr("Language: %1\$s",tr(language.name))
                    }
                }
            }
        }
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { }
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s.toString();update() }
            override fun afterTextChanged(s:Editable?) { }
        })
        list.setOnItemClickListener { _,_,position,_ ->
            if(intent.getBooleanExtra("select",false)) {
                val language=list.adapter.getItem(position) as ModelLanguages.Language
                setResult(RESULT_OK,Intent().putExtra("language",language.name));finish()
            }
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val footer=if(compact) vertical().also { list.addHeaderView(intro,null,false);list.addFooterView(it,null,false) } else root
        footer.addView(text(tr("Lokey’s language list follows its voice models. Native character coverage and model quality differ."),11f,muted).apply { setPadding(if(compact) 0 else dp(24),dp(8),if(compact) 0 else dp(24),0) })
        footer.addView(button(tr("Coverage sources")) {
            val sources=listOf("LFM 2.5" to "https://huggingface.co/LiquidAI/LFM2.5-350M","Qwen 3.5" to "https://huggingface.co/Qwen/Qwen3.5-0.8B","Parakeet v3" to "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3","Qwen3 ASR" to "https://huggingface.co/Qwen/Qwen3-ASR-0.6B")
            AlertDialog.Builder(this).setTitle(tr("Language coverage")).setItems(sources.map { it.first }.toTypedArray()) { _,index -> startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(sources[index].second))) }.show()
        },LinearLayout.LayoutParams(-1,-2).apply { setMargins(if(compact) 0 else dp(24),dp(8),if(compact) 0 else dp(24),dp(16)) })
        update()
    }
    override fun onSaveInstanceState(state:Bundle) { state.putString("query",query);state.putString("filter",filter);super.onSaveInstanceState(state) }
}
