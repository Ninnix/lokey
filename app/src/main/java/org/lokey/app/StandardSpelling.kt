package org.lokey.app

internal object StandardSpelling {
    private val forms=mapOf(
        "Arabic" to mapOf(
            "انا" to "أنا", "انت" to "أنت", "انتم" to "أنتم", "انتما" to "أنتما",
            "الى" to "إلى", "اين" to "أين", "ايضا" to "أيضا", "هاذا" to "هذا", "هاذه" to "هذه",
            "استطيع" to "أستطيع", "اريد" to "أريد", "اتوقع" to "أتوقع", "اخذت" to "أخذت", "باخي" to "بأخي"
        ),
        "Indonesian" to mapOf(
            "mengunakan" to "menggunakan", "mengirimm" to "mengirim", "bermaiin" to "bermain",
            "ijin" to "izin", "resiko" to "risiko", "aktifitas" to "aktivitas", "antri" to "antre",
            "apotik" to "apotek", "nafas" to "napas", "sekedar" to "sekadar", "praktek" to "praktik",
            "terimakasih" to "terima kasih", "kerjasama" to "kerja sama", "bekerjasama" to "bekerja sama"
        ),
        "Russian" to mapOf(
            "зделаю" to "сделаю", "зделаешь" to "сделаешь", "зделает" to "сделает", "зделаем" to "сделаем",
            "зделают" to "сделают", "зделал" to "сделал", "зделала" to "сделала", "зделали" to "сделали",
            "хочит" to "хочет", "учишся" to "учишься", "пришол" to "пришёл", "севодня" to "сегодня",
            "пожалуста" to "пожалуйста", "спасиба" to "спасибо", "прийду" to "приду", "прийдёт" to "придёт"
        ),
        "German" to mapOf(
            "mussst" to "musst", "hatt" to "hat", "jetz" to "jetzt",
            "vieleicht" to "vielleicht", "warscheinlich" to "wahrscheinlich"
        )
    )
    private val scoped=setOf("Arabic","Indonesian","Russian")

    fun scoped(language:String?)=language in scoped

    fun form(before:String,language:String):Languages.Spelling? {
        val prefix=Languages.prefix(before)
        if(prefix.length !in 2..24 || prefix.drop(1).any(Char::isUpperCase)) return null
        val fixed=forms[language]?.get(Languages.lower(prefix)) ?: return null
        val word=Languages.casing(fixed,prefix,ModelLanguages.code(language))
        return Languages.Spelling(prefix,word,true)
    }

    fun correction(before:String,language:String):Prediction.Result? = form(before,language)?.let {
        Prediction.Result(before,listOf(it.word),FloatArray(26),ModelLanguages.code(language),false,it.word,spelling=it)
    }
}
