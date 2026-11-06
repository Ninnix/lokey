package org.lokey.app

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

class Languages(context: Context) {
    data class Entry(val word: String, val count: Long, val folded: String)
    data class Language(val code: String, val name: String, val entries: List<Entry>) {
        val words = entries.associateBy { it.word }
        val total = entries.sumOf { it.count }.toDouble()
    }
    data class Choice(val word: String, val prior: Double, val distance: Int = 0)
    val all = names.map { (code, name) ->
        val common = context.assets.open("languages/$code.txt").bufferedReader().useLines { lines ->
            lines.map { line ->
                val first = line.indexOf(' '); val last = line.lastIndexOf(' ')
                Entry(line.substring(0, first), line.substring(first + 1, last).toLong(), line.substring(last + 1))
            }.toList()
        }
        val forms=when(code) { "en" -> englishForms;"it" -> listOf("c'è","com'è","dov'è");else -> emptyList() }
        val counts=common.associate { it.word to it.count }
        val additions=forms.map { word -> Entry(word,((counts[word.substringBefore('\'')] ?: 40000)/4).coerceAtLeast(1000),fold(word)) }
        val entries=(common+additions).distinctBy { it.word }.filter { valid(it.word) && (code!="en" || it.word.length>1 || it.word in listOf("a","i")) }.sortedByDescending { it.count }
        Language(code, name, entries)
    }
    private val phrases = mapOf(
        "en" to "thank you so much|see you tomorrow|how are you|on my way|let me know|good morning|I would like to|can you send me|I am going to|I found myself|within a forest dark|for the straightforward pathway",
        "it" to "come stai|ci vediamo domani|grazie mille|ti voglio bene|buona giornata|buongiorno a tutti|sono in arrivo|a che ora|non lo so|mi fai sapere|va bene|per favore|sto arrivando|andiamo a cena|ci sentiamo dopo",
        "es" to "cómo estás|muchas gracias|hasta mañana|buenos días|nos vemos mañana|te quiero mucho|por favor|estoy en camino|qué tal|no lo sé|que tengas un buen día",
        "fr" to "comment ça va|merci beaucoup|à demain|bonne journée|je suis en route|s'il vous plaît|je ne sais pas|à bientôt|je t'aime|est ce que|tu vas bien",
        "pt" to "bom dia|muito obrigado|até amanhã|como estás|por favor|estou a caminho|não sei|tudo bem",
        "de" to "guten Morgen|vielen Dank|bis morgen|wie geht es dir|ich bin unterwegs|ich weiß nicht|alles klar",
        "nl" to "goedemorgen allemaal|dank je wel|tot morgen|hoe gaat het|ik ben onderweg",
        "sv" to "god morgon|tack så mycket|vi ses imorgon|hur mår du",
        "pl" to "dzień dobry|dziękuję bardzo|do zobaczenia|jak się masz",
        "ro" to "bună dimineața|mulțumesc mult|pe mâine|ce mai faci"
    )
    fun detect(text: String, previous: String = "en"): Language {
        val raw=tokens(text).takeLast(9)
        if(raw.size==1) {
            val word=lower(raw.first())
            if(word in englishAliases && (previous=="en" || previous!="de" && word in listOf("im","ive"))) return all.first { it.code=="en" }
        }
        val tokens = raw.map { lower(it) }.filter { token -> all.any { it.words.containsKey(token) } }
        if (tokens.isEmpty()) return all.first { it.code == previous }
        val scores = all.map { language ->
            var score = if (language.code == previous) 0.65 else 0.0
            tokens.forEachIndexed { index, token ->
                val count = language.words[token]?.count ?: 0
                val recency = Math.pow(0.76, (tokens.lastIndex - index).toDouble())
                score += recency * ln((count + 0.2) / language.total)
            }
            language to score
        }.sortedByDescending { it.second }
        return scores.first().first
    }
    fun choices(language: Language, context: String, prefix: String): List<Choice> {
        val key = fold(prefix)
        val entries = language.entries.asSequence().filter { key.isEmpty() || it.folded.startsWith(key) }
            .filter { !it.word.equals(prefix, true) }.take(if (key.isEmpty()) 512 else 96)
        val result = entries.map { Choice(it.word, ln(it.count.toDouble()) * 0.24) }.toMutableList()
        val tail = lower(context.trimEnd())
        for (phrase in phrases[language.code].orEmpty().split('|')) {
            val words = phrase.split(' ')
            for (i in 1 until words.size) {
                val stem = lower(words.take(i).joinToString(" "))
                if ((tail == stem || tail.endsWith(" $stem")) && fold(words[i]).startsWith(key)) {
                    val rest = words.drop(i).take(3).joinToString(" ")
                    result.add(0, Choice(rest, 6.0))
                    result.add(0, Choice(words[i], 6.1))
                }
            }
        }
        return result.distinctBy { lower(it.word) }
    }
    fun corrections(language: Language, prefix: String): List<Choice> {
        contraction(language,prefix)?.let { return listOf(it) }
        val word = lower(prefix)
        if (word.length !in 3..24 || prefix.first().isUpperCase() || all.any { it.words.containsKey(word) }) return emptyList()
        val key = fold(word)
        return language.entries.asSequence().filter { kotlin.math.abs(it.folded.length - key.length) <= 1 }
            .mapNotNull { entry ->
                val distance = distance(key, entry.folded)
                if (distance > 1) null else Choice(entry.word, ln(entry.count.toDouble()) * 0.24, distance)
            }.sortedByDescending { it.prior }.take(12).toList()
    }
    fun contraction(language:Language,prefix:String):Choice? {
        if(prefix.length>1 && prefix.filter { it.isLetter() }.all { it.isUpperCase() }) return null
        val word=lower(prefix)
        val fixed=when(language.code) { "en" -> englishAliases[word];"it" -> if(word=="c'é") "c'è" else null;else -> null } ?: return null
        return Choice(fixed,ln((language.words[fixed]?.count ?: 10000).toDouble())*.24,1)
    }
    companion object {
        private val englishForms="i'm i've i'll i'd you're you've you'll you'd we're we've we'll we'd they're they've they'll they'd he's he'll he'd she's she'll she'd it's it'll that's there's here's what's who's how's isn't aren't wasn't weren't don't doesn't didn't can't couldn't shouldn't wouldn't won't hasn't haven't hadn't mustn't let's".split(' ')
        private val englishAliases=englishForms.filter { it !in listOf("i'll","i'd","we're","we'll","he'll","she'll","it's","let's","can't","won't") }.associateBy { it.replace("'","") }
        @Volatile private var shared: Languages? = null
        @Synchronized fun shared(context: Context): Languages = shared ?: Languages(context.applicationContext).also { shared = it }
        private val marks = Regex("\\p{M}+")
        private val wordPattern = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*")
        val names = linkedMapOf("en" to "English", "it" to "Italian", "es" to "Spanish", "fr" to "French", "pt" to "Portuguese", "de" to "German", "nl" to "Dutch", "sv" to "Swedish", "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech", "sk" to "Slovak", "ro" to "Romanian", "hu" to "Hungarian", "tr" to "Turkish", "hr" to "Croatian", "sl" to "Slovenian", "et" to "Estonian", "lt" to "Lithuanian", "lv" to "Latvian")
        fun lower(text: String) = text.lowercase(Locale.ROOT).replace('’', '\'')
        fun fold(text: String) = Normalizer.normalize(lower(text), Normalizer.Form.NFD).replace(marks, "").replace('ł', 'l').replace('ø', 'o').replace('đ', 'd')
        fun isWord(c: Char) = c.isLetter() || c in "'’\u200c\u200d" || Character.getType(c) in listOf(Character.NON_SPACING_MARK.toInt(),Character.COMBINING_SPACING_MARK.toInt())
        fun prefix(text: String) = text.takeLastWhile { isWord(it) }
        fun valid(word:String)=word.isNotEmpty() && word.first().isLetter() && word.last().isLetter() && word.all { isWord(it) || it==' ' }
        fun casing(word:String,prefix:String,code:String,complete:Boolean=false):String {
            val locale=Locale.forLanguageTag(code)
            return when {
                !complete && prefix.length>1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(locale)
                code=="en" && (word=="i" || word.startsWith("i'")) -> word.replaceFirstChar { it.uppercase(locale) }
                !complete && prefix.firstOrNull()?.isUpperCase()==true -> word.replaceFirstChar { it.titlecase(locale) }
                else -> word
            }
        }
        fun tokens(text: String) = wordPattern.findAll(text).map { it.value }.toList()
        fun distance(a: String, b: String): Int {
            val rows = Array(3) { IntArray(b.length + 1) }
            for (j in 0..b.length) rows[0][j] = j
            for (i in 1..a.length) {
                val row = rows[i % 3]; val prev = rows[(i - 1) % 3]
                row[0] = i
                for (j in 1..b.length) {
                    row[j] = minOf(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) row[j] = minOf(row[j], rows[(i - 2) % 3][j - 2] + 1)
                }
            }
            return rows[a.length % 3][b.length]
        }
    }
}
