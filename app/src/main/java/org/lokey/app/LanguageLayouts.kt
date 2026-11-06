package org.lokey.app

// Generated from the model catalog and Unicode CLDR 49; see tools/prepare-language-layouts.py.
object LanguageLayouts {
    data class Mapping(val code:String,val script:String,val target:String,val layout:String?,val status:String,val note:String)
    val all=mapOf(
        "Arabic" to Mapping("ar","Arab","Arabic script","arabic","available",""),
        "Bulgarian" to Mapping("bg","Cyrl","Cyrillic","cyrillic","available",""),
        "Cantonese" to Mapping("yue","Hant","Jyutping / Cangjie / Sucheng","jyutping","available","Traditional characters; optional Jyutping tones, or shape codes"),
        "Chinese" to Mapping("zh","Hans","Chinese Pinyin","pinyin","available","Pinyin currently produces simplified characters"),
        "Croatian" to Mapping("hr","Latn","QWERTZ","qwertz","available",""),
        "Czech" to Mapping("cs","Latn","QWERTZ","qwertz","available",""),
        "Danish" to Mapping("da","Latn","QWERTY Accent","qwerty","available",""),
        "Dutch" to Mapping("nl","Latn","QWERTY Accent","qwerty","available",""),
        "English" to Mapping("en","Latn","QWERTY Intl","intl","available",""),
        "Estonian" to Mapping("et","Latn","QWERTY Accent","qwerty","available",""),
        "Filipino" to Mapping("fil","Latn","QWERTY Accent","qwerty","available",""),
        "Finnish" to Mapping("fi","Latn","QWERTY Accent","qwerty","available",""),
        "French" to Mapping("fr","Latn","AZERTY","azerty","available",""),
        "German" to Mapping("de","Latn","QWERTZ","qwertz","available",""),
        "Greek" to Mapping("el","Grek","Greek","greek","available",""),
        "Hindi" to Mapping("hi","Deva","Devanagari InScript","hindi","available","InScript, Roman transliteration, or Bolnagri; ृ is a flick/hold on र in InScript"),
        "Hungarian" to Mapping("hu","Latn","QWERTZ","qwertz","available",""),
        "Indonesian" to Mapping("id","Latn","QWERTY Intl","intl","available",""),
        "Italian" to Mapping("it","Latn","QWERTY Accent","qwerty","available",""),
        "Japanese" to Mapping("ja","Jpan","Japanese Romaji","japanese","available",""),
        "Korean" to Mapping("ko","Kore","Korean Hangul","korean","available",""),
        "Latvian" to Mapping("lv","Latn","QWERTY Accent","qwerty","available",""),
        "Lithuanian" to Mapping("lt","Latn","QWERTY Accent","qwerty","available",""),
        "Macedonian" to Mapping("mk","Cyrl","Cyrillic","cyrillic","available",""),
        "Malay" to Mapping("ms","Latn","QWERTY Intl","intl","available",""),
        "Maltese" to Mapping("mt","Latn","QWERTY Accent","qwerty","available",""),
        "Persian" to Mapping("fa","Arab","Arabic script","persian","available",""),
        "Polish" to Mapping("pl","Latn","QWERTY Accent","qwerty","available",""),
        "Portuguese" to Mapping("pt","Latn","Portuguese QWERTY","portuguese","available",""),
        "Romanian" to Mapping("ro","Latn","QWERTY Accent","qwerty","available",""),
        "Russian" to Mapping("ru","Cyrl","Cyrillic","cyrillic","available",""),
        "Slovak" to Mapping("sk","Latn","QWERTZ","qwertz","available",""),
        "Slovenian" to Mapping("sl","Latn","QWERTZ","qwertz","available",""),
        "Spanish" to Mapping("es","Latn","Spanish QWERTY","spanish","available",""),
        "Swedish" to Mapping("sv","Latn","QWERTY Accent","qwerty","available",""),
        "Thai" to Mapping("th","Thai","Thai Kedmanee","thai","available",""),
        "Turkish" to Mapping("tr","Latn","Turkish QWERTY","turkish","available",""),
        "Ukrainian" to Mapping("uk","Cyrl","Cyrillic","ukrainian","available",""),
        "Vietnamese" to Mapping("vi","Latn","Vietnamese Telex","vietnamese","available",""),
    )
    private val codes=all.values.associateBy { it.code }
    private val aliases=mapOf("tl" to "fil")
    fun forCode(code:String):Mapping? {
        val base=code.substringBefore('-')
        return codes[code] ?: codes[aliases[code]] ?: codes[base] ?: codes[aliases[base]]
    }
}
