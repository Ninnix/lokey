package org.lokey.app

import android.content.res.Resources
import java.util.Locale

object KeyboardLayouts {
    data class Layout(
        val id:String,val title:String,val rows:List<List<String>>,val language:String?=null,val latin:Boolean=true,
        val family:String=id,val variant:String="",val composer:String="",val cased:Boolean=true,
        val shifted:Map<String,String> = emptyMap(),val alternates:Map<String,List<String>> = emptyMap(),
        val behaviorId:String=id,val keyRows:List<List<CustomLayouts.Key>> = emptyList()
    ) {
        val dictionary get()=when(composer) {
            "pinyin","japanese","jyutping","cangjie","sucheng" -> "$composer.tsv"
            "hindi_phonetic" -> "hindi.tsv"
            else -> ""
        }
        val phonetic get()=dictionary.isNotEmpty()
        val flow get()=latin && composer in listOf("","dead")
        val wordSpaces get()=family!="pinyin" && behaviorId !in listOf("japanese","thai")
        val automaticCaps get()=cased && !phonetic
        val shiftLabel get()=when { behaviorId in listOf("arabic","persian") -> "marks";behaviorId=="japanese" -> "kana";cased -> "caps";else -> "shift" }
        fun punctuation(value:String):String=when(behaviorId) {
            "arabic","persian" -> when(value) { "?" -> "؟";"," -> "،";";" -> "؛";else -> value }
            "pinyin","jyutping","cangjie","sucheng","japanese" -> when(value) { "." -> "。";"," -> if(behaviorId=="japanese") "、" else "，";"?" -> "？";"!" -> "！";":" -> "：";";" -> "；";"-" -> if(behaviorId=="japanese") "ー" else value;else -> value }
            "greek" -> if(value=="?") ";" else value
            "hindi","bolnagri","hindi_phonetic" -> if(value==".") "।" else value
            else -> value
        }
        fun output(code:String,shift:Boolean):String {
            if(!shift) return punctuation(code)
            shifted[code]?.let { return it }
            return if(cased) code.uppercase(Locale.forLanguageTag(if(behaviorId=="turkish") "tr" else "und")) else punctuation(code)
        }
        fun label(code:String,shift:Boolean)=display(output(code,shift))
        fun variants(code:String):List<String> = alternates[code] ?: if(!cased) shifted[code]?.let { listOf(it) }.orEmpty() else emptyList()
    }
    data class Family(val id:String,val title:String,val note:String)
    private fun rows(vararg values:String)=values.map { row -> row.map { it.toString() } }
    private fun variants(vararg entries:Pair<String,String>)=entries.associate { it.first to it.second.map { c -> c.toString() } }
    private val latinRows=rows("qwertyuiop","asdfghjkl","zxcvbnm")
    private val radicals="日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜重"
    fun radical(code:String)=code.singleOrNull()?.takeIf { it in 'a'..'z' }?.let { radicals[it-'a'].toString() }.orEmpty()
    private val arabicMarks=mapOf("ض" to "َ","ص" to "ً","ث" to "ُ","ق" to "ٌ","ف" to "ِ","غ" to "ٍ","ع" to "ْ","ه" to "ّ","ا" to "أ","ل" to "إ","لا" to "لآ","ى" to "ٰ","ج" to "]","د" to "[","ح" to "؛","ك" to ":","ط" to "\"")
    private val cyrillic=variants("е" to "ёєѐ","и" to "іїѝ","г" to "ґѓ","к" to "ќ","с" to "ѕ","н" to "њ","л" to "љ","д" to "џ","й" to "ј","ь" to "ʼ","ы" to "і","э" to "є","ъ" to "ї","і" to "їиы","ї" to "іъ","є" to "эе","ґ" to "г")
    private val arabic=variants("ا" to "أإآٱ","و" to "ؤ","ي" to "یئى","ه" to "ة","ك" to "کگ","ة" to "ه","ب" to "پ","ج" to "چ","ز" to "ژ")
    private fun native(id:String,title:String,language:String,keys:NativeLayouts.Keys,family:String=id,variant:String="")=
        Layout(id,title,keys.rows,language,false,family,variant,cased=false,shifted=keys.shift,alternates=keys.alternates)
    val all=listOf(
        Layout("intl","QWERTY Intl",latinRows,family="qwerty",variant="Intl · dead keys",composer="dead",
            alternates=variants("l" to "'\"","k" to ":;","m" to "<>/","p" to "|\\","o" to "=+","9" to "[{","0" to "]}","q" to "`~")),
        Layout("qwerty","QWERTY Accent",latinRows,family="qwerty",variant="Accent",alternates=variants("p" to "'")),
        Layout("spanish","Español · Spanish",rows("qwertyuiop","asdfghjklñ","zxcvbnm"),family="qwerty",variant="Spanish",alternates=variants("a" to "áàäâã","e" to "éèêë","i" to "íìïî","o" to "óòôöõ","u" to "úüùû")),
        Layout("portuguese","Português · Portuguese",rows("qwertyuiop","asdfghjklç","zxcvbnm"),family="qwerty",variant="Portuguese",alternates=variants("a" to "áàâãä","e" to "éêèë","o" to "óôõòö")),
        Layout("turkish","Türkçe · Turkish",rows("qwertyuıopğü","asdfghjklşi","zxcvbnmöç"),family="qwerty",variant="Turkish"),
        Layout("vietnamese","Tiếng Việt · Vietnamese",latinRows,"Vietnamese",family="qwerty",variant="Vietnamese Telex",composer="vietnamese"),
        Layout("azerty","AZERTY",rows("azertyuiop","qsdfghjklm","wxcvbn")),
        Layout("qwertz","QWERTZ",rows("qwertzuiopü","asdfghjklöä","yxcvbnm")),
        Layout("cyrillic","Русский · Russian",rows("йцукенгшщзхъ","фывапролджэ","ячсмитьбю"),"Russian",false,"cyrillic","Russian",alternates=cyrillic),
        Layout("ukrainian","Українська · Ukrainian",rows("йцукенгшщзхї","фівапролджє","ячсмитьбюґ"),"Ukrainian",false,"cyrillic","Ukrainian",alternates=cyrillic),
        Layout("arabic","العربية · Arabic",rows("ضصثقفغعهخحجد","شسيبلاتنمكط")+listOf(listOf("ئ","ء","ؤ","ر","لا","ى","ة","و","ز","ظ")),"Arabic",false,"arabic","Arabic",cased=false,shifted=arabicMarks,alternates=arabic),
        native("persian","فارسی · Persian","Persian",NativeLayouts.persian,"arabic","Persian"),
        Layout("greek","Ελληνικά · Greek",rows(";ςερτυθιοπ","ασδφγηξκλ","ζχψωβνμ"),"Greek",false,shifted=mapOf(";" to ":"),alternates=variants("α" to "ά","ε" to "έ","η" to "ή","ι" to "ίϊΐ","ο" to "ό","υ" to "ύϋΰ","ω" to "ώ","σ" to "ς")),
        native("hindi","हिन्दी · InScript","Hindi",NativeLayouts.hindi,"hindi","InScript"),
        Layout("hindi_phonetic","हिन्दी · Roman transliteration",latinRows,"Hindi",family="hindi",variant="Roman transliteration",composer="hindi_phonetic"),
        native("bolnagri","हिन्दी · Bolnagri","Hindi",NativeLayouts.bolnagri,"hindi","Bolnagri"),
        Layout("korean","한국어 · Korean",rows("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ","ㅁㄴㅇㄹㅎㅗㅓㅏㅣ","ㅋㅌㅊㅍㅠㅜㅡ"),"Korean",false,composer="korean",cased=false,shifted=mapOf("ㅂ" to "ㅃ","ㅈ" to "ㅉ","ㄷ" to "ㄸ","ㄱ" to "ㄲ","ㅅ" to "ㅆ","ㅐ" to "ㅒ","ㅔ" to "ㅖ")),
        Layout("japanese","日本語 · Japanese",latinRows,"Japanese",composer="japanese",alternates=variants("a" to "あぁ","i" to "いぃ","u" to "うぅ","e" to "えぇ","o" to "おぉ","n" to "ん")),
        Layout("pinyin","中文 · Chinese Pinyin",latinRows,"Chinese",family="pinyin",variant="Pinyin",composer="pinyin"),
        Layout("jyutping","粵語 · Jyutping",latinRows,"Cantonese",family="pinyin",variant="Jyutping",composer="jyutping"),
        Layout("cangjie","倉頡 · Cangjie",latinRows,"Cantonese",family="pinyin",variant="Cangjie 5",composer="cangjie"),
        Layout("sucheng","速成 · Sucheng",latinRows,"Cantonese",family="pinyin",variant="Sucheng",composer="sucheng"),
        native("thai","ไทย · Thai","Thai",NativeLayouts.thai)
    )
    val families=listOf(
        Family("qwerty","QWERTY","Multilingual Latin"),Family("azerty","AZERTY","French"),Family("qwertz","QWERTZ","German & Central Europe"),
        Family("cyrillic","Cyrillic","Russian, Ukrainian & more"),Family("arabic","Arabic script","Arabic & Persian"),Family("greek","Greek","Ελληνικά"),
        Family("hindi","Hindi","InScript · Roman · Bolnagri"),Family("korean","Korean","한국어 · Hangul"),Family("japanese","Japanese","日本語 · Romaji"),
        Family("pinyin","Chinese & Cantonese","Pinyin · Jyutping · Shape"),Family("thai","Thai","ไทย · Kedmanee")
    )
    fun find(id:String)=all.firstOrNull { it.id==id } ?: all.first { it.id=="qwerty" }
    fun variants(family:String)=all.filter { it.family==family }
    fun forLocale(locale:Locale):Layout {
        if(LanguageLayouts.forCode(locale.toLanguageTag())==null) return find("intl")
        val id=when(locale.language) {
            "fr" -> when(locale.country) { "CA" -> "qwerty";"CH" -> "qwertz";else -> "azerty" }
            "de","cs","sk","sl","hr","hu","bs" -> "qwertz"
            "es" -> "spanish";"pt" -> "portuguese";"tr" -> "turkish";"vi" -> "vietnamese"
            "ru","bg","be","mk" -> "cyrillic";"uk" -> "ukrainian"
            "sr" -> if(locale.script=="Latn") "qwertz" else "cyrillic"
            "ar" -> "arabic";"fa" -> "persian";"el" -> "greek";"hi" -> "hindi"
            "ko" -> "korean";"ja" -> "japanese";"zh" -> "pinyin";"yue" -> "jyutping";"th" -> "thai"
            "nl" -> if(locale.country=="BE") "azerty" else "qwerty"
            else -> LanguageLayouts.forCode(locale.toLanguageTag())?.layout ?: "qwerty"
        }
        return find(id)
    }
    fun deviceLocale():Locale {
        val locales=Resources.getSystem().configuration.locales
        return if(locales.isEmpty) Locale.getDefault() else locales[0]
    }
    fun automatic():Layout=forLocale(deviceLocale())
    fun predictionLanguage(before:String,hint:String?):String? {
        val letter=before.lastOrNull { it.isLetter() } ?: return hint
        return when(Character.UnicodeScript.of(letter.code)) {
            Character.UnicodeScript.CYRILLIC -> if(before.takeLast(120).any { it.lowercaseChar() in "іїєґ" }) "Ukrainian" else if(hint in listOf("Ukrainian","Bulgarian","Macedonian")) hint else "Russian"
            Character.UnicodeScript.ARABIC -> if(hint=="Persian" || before.takeLast(120).any { it in "پچژگکی" }) "Persian" else "Arabic"
            Character.UnicodeScript.GREEK -> "Greek"
            Character.UnicodeScript.DEVANAGARI -> "Hindi"
            Character.UnicodeScript.HANGUL -> "Korean"
            Character.UnicodeScript.HIRAGANA,Character.UnicodeScript.KATAKANA -> "Japanese"
            Character.UnicodeScript.HAN -> if(hint=="Japanese" || before.takeLast(120).any { it in 'ぁ'..'ヿ' }) "Japanese" else if(hint=="Cantonese") "Cantonese" else "Chinese"
            Character.UnicodeScript.THAI -> "Thai"
            Character.UnicodeScript.LATIN -> if(hint=="Vietnamese" || before.takeLast(120).any { it in 'Ạ'..'ỹ' || it in "ơưƠƯ" }) "Vietnamese" else hint?.takeIf { it !in Languages.names.values && ModelLanguages.latinLanguage(it) }
            else -> null
        }
    }
    fun display(value:String):String {
        if(value=="\u200c") return "½"
        if(value=="\u200d") return "↔"
        val type=value.firstOrNull()?.let { Character.getType(it) }
        return if(type==Character.NON_SPACING_MARK.toInt() || type==Character.COMBINING_SPACING_MARK.toInt()) "◌$value" else value
    }
}
