package org.lokey.app

import java.text.Normalizer

internal object AccentSpelling {
    private val codes=setOf("de","es","fr","pt")
    private val portuguese="não você vocês também estávamos tínhamos éramos íamos vínhamos pudéssemos fôssemos".split(' ').associateBy(Languages::fold)
    private val spanishPast=setOf("ayer","anteayer","anoche")
    private val spanishThirdPerson=setOf("él","ella","usted")
    private val frenchAvoir=setOf("j'ai","tu as","il a","elle a","on a","nous avons","vous avez","ils ont","elles ont")
    private val germanArticles=setOf("der","die","das","den","dem","des","ein","eine","einen","einem","einer")

    fun supported(code:String)=code in codes
    fun original(word:String)=Normalizer.normalize(Languages.lower(word),Normalizer.Form.NFC)
    fun sameForm(typed:String,word:String):Boolean=word.length==typed.length &&
        typed.indices.all { typed[it]==word[it] || typed[it] in 'a'..'z' && Languages.fold(word[it].toString())==typed[it].toString() }

    fun restore(before:String,prefix:String,language:Languages.Language,entry:Languages.Entry?,forms:List<Languages.Entry>):Languages.Spelling? {
        val typed=original(prefix)
        if(typed.length !in 2..24 || prefix.drop(1).any(Char::isUpperCase)) return null
        val stem=before.dropLast(prefix.length).takeLastWhile { it !in ",.!?;:\n\r" }
        val history=Languages.tokens(stem).takeLast(2).map(Languages::lower)
        val germanNoun=language.code=="de" && history.lastOrNull() in germanArticles
        if(prefix.first().isUpperCase() && stem.any(Char::isLetter) && !germanNoun) return null
        val candidates=forms.filter { it.word!=typed && sameForm(typed,it.word) }
        val fixed=when(language.code) {
            "es" -> spanish(typed,history,language,candidates)
            "fr" -> if(history.joinToString(" ") in frenchAvoir) candidates.singleOrNull { it.word.endsWith('é') &&
                it.word.dropLast(1)+"er" in language.words }?.word else null
            "pt" -> portuguese[typed]
            else -> null
        }
        if(fixed!=null) return Languages.Spelling(prefix,Languages.casing(fixed,prefix,language.code),true)
        if(typed.length<3 || typed in language.words) return null
        val only=candidates.singleOrNull() ?: return null
        // A rare copy without accents in the extended dictionary can be a noisy duplicate.
        if(only.count<80 || entry!=null && only.count<16*entry.count) return null
        return Languages.Spelling(prefix,Languages.casing(only.word,prefix,language.code),true)
    }

    private fun spanish(typed:String,history:List<String>,language:Languages.Language,forms:List<Languages.Entry>):String? {
        val previous=history.lastOrNull()
        if(previous=="tú" && typed !in language.words && (typed.endsWith("astes") || typed.endsWith("istes"))) {
            return typed.dropLast(1).takeIf { it in language.words }
        }
        if(history.size==2 && history.first() in spanishPast && previous in spanishThirdPerson &&
            typed.endsWith('o') && typed.dropLast(1)+"ar" in language.words) return forms.singleOrNull { it.word.endsWith('ó') }?.word
        return null
    }
}
