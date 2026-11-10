package org.lokey.app

import android.content.Context
import kotlin.math.ln

/** Public dictionary statistics only; no user text is retained. */
class LocalPrediction(private val context:Context,private val languages:Languages) {
    private class Continuations(val counts:Map<String,Int>) { val total=counts.values.sum().toDouble() }
    private class Vocabulary(entries:List<Languages.Entry>,val contexts:Map<String,Continuations>) {
        val words=entries.associateBy { Languages.lower(it.word) }
        val total=entries.sumOf { it.count }.toDouble()
        val folded=entries.groupBy { it.folded.replace("'","") }
        val ordered=folded.keys.sorted()
        val frequent=entries.sortedByDescending { it.count }.take(96)
        fun complete(prefix:String):List<Languages.Entry> {
            val query=key(prefix)
            if(query.isEmpty()) return frequent
            val at=ordered.binarySearch(query).let { if(it<0) -it-1 else it }
            val matches=ArrayList<Languages.Entry>()
            for(i in at until ordered.size) {
                if(!ordered[i].startsWith(query)) break
                matches.addAll(folded.getValue(ordered[i]))
            }
            return matches.sortedByDescending { it.count }.take(96)
        }
        fun nearby(prefix:String):List<Languages.Entry> {
            val query=key(prefix);val found=HashMap<String,Languages.Entry>()
            fun add(word:String) { for(entry in folded[word].orEmpty()) if(entry.word!=prefix) found[entry.word]=entry }
            for(i in query.indices) {
                add(query.removeRange(i,i+1))
                if(i+1<query.length) add(query.take(i)+query[i+1]+query[i]+query.drop(i+2))
                for(c in "abcdefghijklmnopqrstuvwxyzßæœı") add(query.take(i)+c+query.drop(i+1))
            }
            for(i in 0..query.length) for(c in "abcdefghijklmnopqrstuvwxyzßæœı") add(query.take(i)+c+query.drop(i))
            return found.values.filter { Languages.distance(query,key(it.word))<=1 }.sortedByDescending { it.count }
        }
    }
    private val vocabularies=object:LinkedHashMap<String,Vocabulary>(8,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Vocabulary>)=size>3
    }
    private fun vocabulary(language:Languages.Language)=vocabularies.getOrPut(language.code) {
        val rows=HashMap<String,MutableMap<String,Int>>()
        try { context.assets.open("context/${language.code}.tsv").bufferedReader().useLines { lines -> lines.forEach { line ->
            val fields=line.split('\t');rows.getOrPut(fields[0]) { HashMap() }[fields[1]]=fields[2].toInt()
        } } } catch(_:java.io.FileNotFoundException) { }
        Vocabulary(languages.words(language),rows.mapValues { Continuations(it.value) })
    }
    fun correction(before:String,language:Languages.Language):String? {
        val prefix=Languages.prefix(before)
        languages.contraction(language,prefix)?.let { return Languages.casing(it.word,prefix,language.code) }
        if(prefix.length !in 3..24 || prefix.first().isUpperCase() || languages.all.any { Languages.lower(prefix) in it.words }) return null
        val words=vocabulary(language)
        if(Languages.lower(prefix) in words.words || key(prefix).none { it in "aeiouyı" }) return null
        val nearby=words.nearby(prefix)
        val best=nearby.firstOrNull() ?: return null
        // Keep prefixes/suffixes of unfamiliar identifiers and names under user control.
        val typed=key(prefix);val fixed=key(best.word)
        if(typed.first()!=fixed.first() || typed.last()!=fixed.last()) return null
        if(best.word !in language.words || best.count<1000 || best.count<8*(nearby.getOrNull(1)?.count ?: 1)) return null
        return Languages.casing(best.word,prefix,language.code)
    }
    fun request(before:String,language:Languages.Language,autocorrect:Boolean=false):Prediction.Result {
        val prefix=Languages.prefix(before);val stem=before.dropLast(prefix.length)
        val dictionary=vocabulary(language)
        val complete=prefix.length>=2 && Languages.lower(prefix) in language.words && languages.contraction(language,prefix)==null
        val sentence=if(complete) before else stem
        val typed=if(complete) "" else prefix
        val entries=dictionary.complete(typed).associateBy { it.word }.toMutableMap()
        val history=Languages.tokens(sentence).takeLast(2).map { Languages.lower(it) }
        val rows=(1..minOf(2,history.size)).mapNotNull { dictionary.contexts[history.takeLast(it).joinToString(" ")] }
        val query=key(typed)
        for(row in rows) for(word in row.counts.keys) dictionary.words[word]?.let { if(it.folded.replace("'","").startsWith(query)) entries[it.word]=it }
        val ranked=entries.values.associate { entry ->
            var probability=entry.count/dictionary.total
            for(row in rows) {
                val weight=row.total/(row.total+10)
                probability=(1-weight)*probability+weight*(row.counts[Languages.lower(entry.word)] ?: 0)/row.total
            }
            entry.word to ln(probability)
        }.toMutableMap()
        for(choice in languages.phraseChoices(language,sentence,typed)) ranked[choice.word]=choice.prior-6.1
        val suggested=ranked.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
        if(!complete && prefix.length in 3..24 && entries.keys.none { !it.equals(prefix,true) }) suggested.addAll(dictionary.nearby(prefix).take(3).map { it.word })
        val fixed=if(autocorrect) correction(before,language) else null
        val words=(listOfNotNull(fixed)+suggested).distinctBy { Languages.lower(it) }.take(3).map { Languages.casing(it,prefix,language.code,complete) }.toMutableList()
        if(words.isEmpty() && prefix.isNotEmpty()) words.add(prefix)
        val append=if(complete) words.toMutableSet() else mutableSetOf()
        if(complete) dictionary.complete(prefix).firstOrNull { !it.word.equals(prefix,true) && it.word !in words }?.let {
            if(words.size==3) append.remove(words.removeAt(2))
            words.add(Languages.casing(it.word,prefix,language.code))
        }
        return Prediction.Result(before,words,AdaptiveKeys.probabilities(typed,ranked.map { it.key to it.value }),language.code,false,fixed,append)
    }
    companion object {
        private fun key(word:String)=Languages.fold(word).replace("'","")
        // The small vocabulary is already resident while a richer index loads on the worker.
        fun basic(languages:Languages,before:String,language:Languages.Language,autocorrect:Boolean):Prediction.Result {
            val prefix=Languages.prefix(before)
            val alias=languages.contraction(language,prefix)
            val complete=prefix.length>=2 && alias==null && Languages.lower(prefix) in language.words
            val typed=if(complete) "" else prefix
            val sentence=if(complete) before else before.dropLast(prefix.length)
            val choices=languages.choices(language,sentence,typed,24).sortedByDescending { it.prior }
            val fixed=if(autocorrect) alias?.word?.let { Languages.casing(it,prefix,language.code) } else null
            val words=(listOfNotNull(fixed)+choices.map { Languages.casing(it.word,prefix,language.code,complete) }).distinct().take(3).toMutableList()
            if(words.isEmpty() && prefix.isNotEmpty()) words.add(prefix)
            val append=if(complete) words.toMutableSet() else mutableSetOf()
            if(complete) languages.choices(language,"",prefix).firstOrNull { it.word !in words }?.let {
                if(words.size==3) append.remove(words.removeAt(2))
                words.add(Languages.casing(it.word,prefix,language.code))
            }
            val letters=AdaptiveKeys.probabilities(typed,choices.take(24).map { it.word to it.prior })
            return Prediction.Result(before,words,letters,language.code,false,fixed,append)
        }
    }
}
