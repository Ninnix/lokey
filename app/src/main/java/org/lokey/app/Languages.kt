package org.lokey.app

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

class Languages(private val context: Context) {
    data class Entry(val word: String, val count: Long, val folded: String)
    data class Language(val code: String, val name: String, val entries: List<Entry>) {
        val words = entries.associateBy { it.word }
        val total = entries.sumOf { it.count }.toDouble()
    }
    data class Choice(val word: String, val prior: Double, val distance: Int = 0)
    data class Spelling(val source:String,val word:String,val automatic:Boolean)
    private class Vocabulary(val entries:List<Entry>) {
        val swipe by lazy { Glide.Lexicon(entries) }
    }
    private val vocabularies=object:LinkedHashMap<String,Vocabulary>(8,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Vocabulary>)=size>5
    }
    @Synchronized private fun vocabulary(language:Language):Vocabulary=vocabularies.getOrPut(language.code) {
        val extra=try { context.assets.open("swipe-words/${language.code}.txt").bufferedReader().useLines { lines ->
            lines.map { line ->
                val first=line.indexOf(' ');val last=line.lastIndexOf(' ')
                Entry(line.substring(0,first),line.substring(first+1,last).toLong(),line.substring(last+1))
            }.toList()
        } } catch(_:java.io.FileNotFoundException) { emptyList() }
        Vocabulary((language.entries+extra).distinctBy { it.word })
    }
    fun words(language:Language)=vocabulary(language).entries
    fun swipe(language:Language)=vocabulary(language).swipe
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
            if(previous!="en" && word in englishAliases && all.first { it.code==previous }.words.containsKey(word)) return all.first { it.code==previous }
            if(word in englishAliases && (previous=="en" || previous!="de" && word in listOf("im","ive"))) return all.first { it.code=="en" }
        }
        if(previous=="it" && raw.size<=3 && raw.any { lower(it) in italianAliases || lower(it) in italianAlternatives } &&
            raw.all { lower(it) in all.first { language -> language.code=="it" }.words || lower(it) in italianAliases })
            return all.first { it.code=="it" }
        val tokens = raw.map { lower(it) }.filter { token -> token in englishAliases || token in italianAliases || all.any { it.words.containsKey(token) } }
        if (tokens.isEmpty()) return all.first { it.code == previous }
        val scores = all.map { language ->
            var score = if (language.code == previous) 0.65 else 0.0
            tokens.forEachIndexed { index, token ->
                val form=when(language.code) { "en" -> englishAliases[token];"it" -> italianAliases[token];else -> null }
                val count = language.words[form ?: token]?.count ?: 0
                val recency = Math.pow(0.76, (tokens.lastIndex - index).toDouble())
                score += recency * ln((count + 0.2) / language.total)
            }
            language to score
        }.sortedByDescending { it.second }
        return scores.first().first
    }
    fun choices(language: Language, context: String, prefix: String,limit:Int=0): List<Choice> {
        val key = fold(prefix)
        val entries = language.entries.asSequence().filter { key.isEmpty() || it.folded.startsWith(key) }
            .filter { !it.word.equals(prefix, true) }.take(if(limit>0) limit else if (key.isEmpty()) 512 else 96)
        return (phraseChoices(language,context,prefix)+entries.map { Choice(it.word, ln(it.count.toDouble()) * 0.24) }).distinctBy { lower(it.word) }
    }
    fun phraseChoices(language:Language,context:String,prefix:String):List<Choice> {
        val key=fold(prefix);val result=ArrayList<Choice>()
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
        if(language.code=="en" && capitals(prefix)) return null
        val word=Normalizer.normalize(lower(prefix),Normalizer.Form.NFC)
        val fixed=when(language.code) { "en" -> englishAliases[word];"it" -> italianAliases[word];else -> null } ?: return null
        return Choice(fixed,ln((language.words[fixed]?.count ?: 10000).toDouble())*.24,1)
    }
    fun spelling(language:Language,before:String):Spelling? {
        val prefix=prefix(before)
        if(prefix.isEmpty() || language.code !in listOf("en","it")) return null
        val matches=wordMatches(before)
        if(matches.lastOrNull()?.range?.last!=before.lastIndex) return null
        // A late result can repair a missed safe form, but never cross punctuation or a line break.
        for(index in matches.lastIndex downTo (matches.lastIndex-2).coerceAtLeast(0)) {
            val match=matches[index]
            if(matches.drop(index).zipWithNext().any { (a,b) -> before.substring(a.range.last+1,b.range.first).any { it!=' ' && it!='\t' } }) break
            contraction(language,match.value)?.let { alias ->
                return Spelling(before.substring(match.range.first),casing(alias.word,match.value,language.code)+before.substring(match.range.last+1),true)
            }
        }
        val current=matches.last();val previous=matches.getOrNull(matches.lastIndex-1)
        val earlier=matches.getOrNull(matches.lastIndex-2)
        if(previous!=null && earlier!=null && matches.takeLast(3).zipWithNext().all { (a,b) ->
            before.substring(a.range.last+1,b.range.first).let { it.isNotEmpty() && it.all { c -> c==' ' || c=='\t' } }
        }) {
            val first=lower(earlier.value);val middle=lower(previous.value);val last=lower(current.value)
            val fixed=when {
                language.code=="en" && first=="it's" && middle in englishPossessions && last in englishPossessionVerbs -> "its"
                language.code=="it" && first=="c'è" && (middle=="ne" && last in italianPluralVerbs || middle in listOf("lo","la","li","le") && last in italianCliticVerbs) -> "ce"
                language.code=="it" && first=="ce" && middle in listOf("lo","la") && last in italianNouns -> "c'è"
                else -> null
            }
            if(fixed!=null) return Spelling(before.substring(earlier.range.first),casing(fixed,earlier.value,language.code)+before.substring(earlier.range.last+1),true)
        }
        if(previous!=null) {
            val gap=before.substring(previous.range.last+1,current.range.first)
            if(gap.isNotEmpty() && gap.all { it==' ' || it=='\t' }) {
                val first=lower(previous.value);val last=lower(current.value)
                val fixed=when {
                    language.code=="en" && first=="its" && !capitals(previous.value) && last in englishPredicates -> "it's"
                    language.code=="en" && first=="it's" && !capitals(previous.value) && last in englishPossessions -> "its"
                    language.code=="it" && first=="ce" && !capitals(previous.value) && last in italianExistentials -> "c'è"
                    else -> null
                }
                if(fixed!=null) return Spelling(before.substring(previous.range.first),casing(fixed,previous.value,language.code)+gap+current.value,true)
                if(language.code=="it" && first=="un" && last=="po") return Spelling(prefix,casing("po'",prefix,language.code),true)
            }
        }
        if(capitals(prefix)) return if(language.code=="en" && (lower(prefix) in englishAliases || lower(prefix) in englishAlternatives)) Spelling(prefix,prefix,false) else null
        return alternative(language,prefix)
    }
    fun alternative(language:Language,word:String):Spelling? {
        if(capitals(word)) return null
        val fixed=when(language.code) { "en" -> englishAlternatives[lower(word)];"it" -> italianAlternatives[lower(word)];else -> null } ?: return null
        return Spelling(word,casing(fixed,word,language.code),false)
    }
    fun spelling(result:Prediction.Result,autocorrect:Boolean):Prediction.Result {
        val language=all.firstOrNull { it.code==result.language } ?: return result
        val form=result.spelling ?: spelling(language,result.before) ?: return result
        val rest=result.words.filterNot { it.equals(form.word,true) }
        val words=if(form.automatic) (listOf(form.word)+rest).take(3) else rest.take(2)+form.word
        return result.copy(words=words,correction=if(autocorrect && form.automatic) form.word else null,
            appendWords=result.appendWords.intersect(words.toSet())-form.word,spelling=form)
    }
    companion object {
        private val marks = Regex("\\p{M}+")
        private val englishForms="i'm i've i'll i'd you're you've you'll you'd we're we've we'll we'd they're they've they'll they'd he's he'll he'd she's she'll she'd it's it'll that's that'll that'd there's there'll there'd here's what's what'll who's who'll who'd who've how's where's where'd when's why's isn't aren't wasn't weren't don't doesn't didn't can't couldn't shouldn't wouldn't won't hasn't haven't hadn't mustn't needn't oughtn't mightn't shan't ain't would've should've could've might've must've let's".split(' ')
        private val englishAliases=englishForms.filter { it !in listOf("i'll","i'd","we're","we'll","we'd","he'll","she'll","she'd","it's","let's","can't","won't") }.associateBy { it.replace("'","") }
        private val englishAlternatives=englishForms.filter { it.replace("'","") !in englishAliases }.associateBy { it.replace("'","") }
        private val italianAliases=buildMap {
            for(word in "perché poiché affinché benché finché purché nonché giacché sicché cosicché può più già così cioè perciò lunedì martedì mercoledì giovedì venerdì laggiù quaggiù lassù quassù ventitré".split(' ')) {
                put(fold(word),word)
                if(word.endsWith('é')) put(word.dropLast(1)+"è",word)
            }
            put("c'e","c'è");put("c'é","c'è");put("com'e","com'è");put("comé","com'è");put("comè","com'è")
            put("dov'e","dov'è");put("dové","dov'è");put("dovè","dov'è");put("qual'è","qual è")
        }
        private val italianAlternatives=mapOf("ce" to "c'è","e" to "è","si" to "sì","ne" to "né","se" to "sé","la" to "là","li" to "lì","da" to "dà","te" to "tè","po" to "po'","pero" to "però")
        private val englishPredicates="a an the not been raining snowing drizzling thundering wrong right true false fine okay ok good bad great nice important possible impossible necessary clear obvious easy hard ready safe working running happening getting going".split(' ').toSet()
        private val italianExistentials="un uno una il bisogno qualcuno qualcosa nessuno niente nulla".split(' ').toSet()
        private val englishPossessions="own tail paws fur battery screen owner name color colour weight size shape price cost engine wheels doors cover handle purpose contents surface design performance behaviour behavior results job head body eyes legs roof location history title address".split(' ').toSet()
        private val englishPossessionVerbs="is was has does needs works looks seems will can".split(' ').toSet()
        private val italianPluralVerbs="sono siamo siete erano eravamo eravate".split(' ').toSet()
        private val italianCliticVerbs="so sai sa sappiamo sapete sanno porto porti porta dico dici dice faccio fai fa fanno metto metti mette prendo prendi prende".split(' ').toSet()
        private val italianNouns="pizza macchina chiave porta possibilità connessione rete zaino stanza casa strada".split(' ').toSet()
        private fun capitals(word:String)=word.length>1 && word.filter { it.isLetter() }.let { it.isNotEmpty() && it.all(Char::isUpperCase) }
        @Volatile private var shared: Languages? = null
        @Synchronized fun shared(context: Context): Languages = shared ?: Languages(context.applicationContext).also { shared = it }
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
        internal fun wordMatches(text:String)=wordPattern.findAll(text).toList()
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
