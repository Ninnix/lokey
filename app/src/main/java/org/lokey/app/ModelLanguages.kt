package org.lokey.app

import android.content.Context
import java.util.Locale

object ModelLanguages {
    data class Language(val name:String,val code:String,val layout:String?) {
        val mapping get()=LanguageLayouts.all[name]
        val keyboardDescription get()=mapping?.let { "${it.target} · "+when(it.status) {
            "available" -> "Available"
            "partial" -> "Some characters missing"
            "review" -> "Character coverage needs review"
            "excluded" -> "Not included"
            else -> "Not available yet"
        } } ?: "No keyboard mapping"
        val searchText get()=name+" "+code+" "+Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }+if(name in listOf("Tagalog","Filipino")) " Filipino Tagalog" else ""
        val lfm get()=base in lfmCodes
        val parakeet get()=base in parakeetCodes
        val qwenVoice get()=base in qwenVoiceCodes
        val base get()=code.substringBefore('-')
        val textModel get()=if(lfm && base!="zh") "lfm" else "qwen"
        val voiceModel get()=when { parakeet -> "parakeet";qwenVoice -> "qwen_asr";else -> null }
        val textDescription get()=if(lfm) "LFM 2.5 · Qwen 3.5" else "Qwen 3.5"
        val voiceDescription get()=listOfNotNull(if(parakeet) "Parakeet v3" else null,if(qwenVoice) "Qwen3 ASR" else null).joinToString(" · ").ifEmpty { "Not listed by either voice model" }
    }
    val lfmCodes="en ar zh fr de ja ko pt es".split(' ').toSet()
    val parakeetCodes="bg hr cs da nl en et fi fr de el hu it lv lt mt pl pt ro sk sl es sv ru uk".split(' ').toSet()
    val qwenVoiceCodes="zh en yue ar de fr es pt id it ko ru th vi ja tr hi ms nl sv da fi pl cs fil fa el hu mk ro".split(' ').toSet()
    private val aliases=mapOf("Simplified Chinese" to "zh","Traditional Chinese" to "zh-Hant","Standard Arabic" to "ar","Cantonese" to "yue","Tagalog" to "fil","Norwegian Bokmål" to "nb","Norwegian Nynorsk" to "nn","Tosk Albanian" to "sq","Halh Mongolian" to "mn","Northern Uzbek" to "uz","North Azerbaijani" to "az")
    private val iso=Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }
    fun code(name:String)=LanguageLayouts.all[name]?.code ?: aliases[name] ?: iso[name] ?: ""
    fun latinLanguage(name:String)=LanguageLayouts.all[name]?.script=="Latn"
    fun layout(code:String)=LanguageLayouts.forCode(code)?.layout
    @Volatile private var catalog:List<Language>?=null
    fun all(context:Context):List<Language> = catalog ?: synchronized(this) {
        catalog ?: Translation.languages(context).map { name ->
            val mapping=checkNotNull(LanguageLayouts.all[name]) { "Missing keyboard mapping: $name" }
            Language(name,mapping.code,mapping.layout)
        }.also { catalog=it }
    }
    fun inputHint(context:Context,prefs:Prefs):String? {
        val layout=prefs.currentLayout;val primary=selected(context,prefs)
        if(layout.id in listOf("cangjie","sucheng") && primary.base in listOf("zh","yue")) return primary.name
        if(layout.id=="cyrillic" && primary.base in listOf("bg","mk")) return primary.name
        if(primary.base !in Languages.names && primary.layout!=null && KeyboardLayouts.find(primary.layout).latin && layout.latin && !layout.phonetic)
            return primary.name
        return layout.language
    }
    fun forLocale(context:Context,locale:Locale):Language {
        val code=if(locale.language=="tl") "fil" else locale.language
        val mapping=LanguageLayouts.forCode(locale.toLanguageTag())
        return all(context).firstOrNull { if(mapping!=null) it.mapping===mapping else it.base==code } ?: all(context).first { it.code=="en" }
    }
    fun selected(context:Context,prefs:Prefs):Language {
        val language=prefs.setupLanguage
        return all(context).firstOrNull { it.name==language } ?: forLocale(context,KeyboardLayouts.deviceLocale())
    }
    fun recommendedText(code:String)=if(code.substringBefore('-') in lfmCodes && code.substringBefore('-')!="zh") "lfm" else "qwen"
}
