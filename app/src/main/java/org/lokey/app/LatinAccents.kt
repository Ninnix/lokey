package org.lokey.app

// Audited main alphabets; the shared menu keeps common accents, with language-specific additions.
object LatinAccents {
    val common=mapOf(
        'a' to listOf("à","á","â","ä","ã","å","æ","ā","ą","ă"),
        'e' to listOf("è","é","ê","ë","ē","ė","ę","ě"),
        'i' to listOf("ì","í","î","ï","ī","į","ı","İ"),
        'o' to listOf("ò","ó","ô","ö","õ","ø","œ","ő"),
        'u' to listOf("ù","ú","û","ü","ū","ů","ű","ų"),
        'c' to listOf("ç","ć","č","ċ"),
        'n' to listOf("ñ","ń","ň","ņ"),
        's' to listOf("ß","ś","š","ș","ş"),
        'y' to listOf("ý","ÿ"),
        'd' to listOf("ď","đ"),
        'g' to listOf("ğ","ģ","ġ"),
        'h' to listOf("ħ"),
        'k' to listOf("ķ"),
        'l' to listOf("ł","ĺ","ľ","ļ"),
        'r' to listOf("ŕ","ř"),
        't' to listOf("ť","ț"),
        'z' to listOf("ž","ź","ż"),
    )
    private val languages=mapOf(
        "Croatian" to mapOf('c' to listOf("č","ć"),'z' to listOf("ž"),'d' to listOf("đ"),'s' to listOf("š")),
        "Czech" to mapOf('a' to listOf("á"),'c' to listOf("č"),'d' to listOf("ď"),'e' to listOf("é","ě"),'i' to listOf("í"),'n' to listOf("ň"),'o' to listOf("ó"),'r' to listOf("ř"),'s' to listOf("š"),'t' to listOf("ť"),'u' to listOf("ú","ů"),'y' to listOf("ý"),'z' to listOf("ž")),
        "Danish" to mapOf('a' to listOf("æ","å"),'o' to listOf("ø")),
        "Dutch" to mapOf('a' to listOf("á","ä"),'e' to listOf("é","ë"),'i' to listOf("í","ï"),'j' to listOf("j́"),'o' to listOf("ó","ö"),'u' to listOf("ú","ü")),
        "Estonian" to mapOf('s' to listOf("š"),'z' to listOf("ž"),'o' to listOf("õ","ö"),'a' to listOf("ä"),'u' to listOf("ü")),
        "Filipino" to mapOf('n' to listOf("ñ")),
        "Finnish" to mapOf('s' to listOf("š"),'z' to listOf("ž"),'a' to listOf("å","ä"),'o' to listOf("ö")),
        "French" to mapOf('a' to listOf("à","â","æ"),'c' to listOf("ç"),'e' to listOf("é","è","ê","ë"),'i' to listOf("î","ï"),'o' to listOf("ô","œ"),'u' to listOf("ù","û","ü"),'y' to listOf("ÿ")),
        "German" to mapOf('a' to listOf("ä"),'o' to listOf("ö"),'s' to listOf("ß"),'u' to listOf("ü")),
        "Hungarian" to mapOf('a' to listOf("á"),'e' to listOf("é"),'i' to listOf("í"),'o' to listOf("ó","ö","ő"),'u' to listOf("ú","ü","ű")),
        "Italian" to mapOf('a' to listOf("à"),'e' to listOf("é","è"),'i' to listOf("ì"),'o' to listOf("ò"),'u' to listOf("ù")),
        "Latvian" to mapOf('a' to listOf("ā"),'c' to listOf("č"),'e' to listOf("ē"),'g' to listOf("ģ"),'i' to listOf("ī"),'k' to listOf("ķ"),'l' to listOf("ļ"),'n' to listOf("ņ"),'s' to listOf("š"),'u' to listOf("ū"),'z' to listOf("ž")),
        "Lithuanian" to mapOf('a' to listOf("ą"),'c' to listOf("č"),'e' to listOf("ę","ė"),'i' to listOf("į"),'s' to listOf("š"),'u' to listOf("ų","ū"),'z' to listOf("ž")),
        "Maltese" to mapOf('a' to listOf("à"),'c' to listOf("ċ"),'e' to listOf("è"),'g' to listOf("ġ"),'h' to listOf("ħ"),'i' to listOf("ì"),'o' to listOf("ò"),'u' to listOf("ù"),'z' to listOf("ż")),
        "Polish" to mapOf('a' to listOf("ą"),'c' to listOf("ć"),'e' to listOf("ę"),'l' to listOf("ł"),'n' to listOf("ń"),'o' to listOf("ó"),'s' to listOf("ś"),'z' to listOf("ź","ż")),
        "Portuguese" to mapOf('a' to listOf("á","à","â","ã"),'c' to listOf("ç"),'e' to listOf("é","ê"),'i' to listOf("í"),'o' to listOf("ó","ô","õ"),'u' to listOf("ú")),
        "Romanian" to mapOf('a' to listOf("ă","â"),'i' to listOf("î"),'s' to listOf("ș"),'t' to listOf("ț")),
        "Slovak" to mapOf('a' to listOf("á","ä"),'c' to listOf("č"),'d' to listOf("ď"),'z' to listOf("ž"),'e' to listOf("é"),'i' to listOf("í"),'l' to listOf("ĺ","ľ"),'n' to listOf("ň"),'o' to listOf("ó","ô"),'r' to listOf("ŕ"),'s' to listOf("š"),'t' to listOf("ť"),'u' to listOf("ú"),'y' to listOf("ý")),
        "Slovenian" to mapOf('c' to listOf("č"),'s' to listOf("š"),'z' to listOf("ž")),
        "Spanish" to mapOf('a' to listOf("á"),'e' to listOf("é"),'i' to listOf("í"),'n' to listOf("ñ"),'o' to listOf("ó"),'u' to listOf("ú","ü")),
        "Swedish" to mapOf('a' to listOf("à","å","ä"),'e' to listOf("é"),'o' to listOf("ö")),
        "Turkish" to mapOf('c' to listOf("ç"),'g' to listOf("ğ"),'i' to listOf("ı","İ"),'o' to listOf("ö"),'s' to listOf("ş"),'u' to listOf("ü")),
        "Vietnamese" to mapOf('a' to listOf("à","ả","ã","á","ạ","ă","ằ","ẳ","ẵ","ắ","ặ","â","ầ","ẩ","ẫ","ấ","ậ"),'d' to listOf("đ"),'e' to listOf("è","ẻ","ẽ","é","ẹ","ê","ề","ể","ễ","ế","ệ"),'i' to listOf("ì","ỉ","ĩ","í","ị"),'o' to listOf("ò","ỏ","õ","ó","ọ","ô","ồ","ổ","ỗ","ố","ộ","ơ","ờ","ở","ỡ","ớ","ợ"),'u' to listOf("ù","ủ","ũ","ú","ụ","ư","ừ","ử","ữ","ứ","ự"),'y' to listOf("ỳ","ỷ","ỹ","ý","ỵ")),
    )
    private val names=LanguageLayouts.all.entries.associate { it.value.code to it.key }
    fun options(base:Char,language:String):List<String> {
        val name=if(language in languages) language else names[language] ?: names[language.substringBefore('-')]
        val usual=common[base].orEmpty()
        val local=languages[name]?.get(base).orEmpty()
        return (local.filter { it !in usual }+usual.filter { it in local }+usual).distinct()
    }
}
