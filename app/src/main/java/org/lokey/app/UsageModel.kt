package org.lokey.app

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Bounded word statistics, never sentences, editor contents or touch traces. */
class UsageModel(private val day:()->Int={ (System.currentTimeMillis()/86400000).toInt() }) {
    data class Entry(val scope:String,val typed:String,val word:String,var count:Int,var seen:Int)
    private val entries=LinkedHashMap<String,Entry>()
    private fun key(scope:String,typed:String,word:String)="$scope\t$typed\t$word"
    private fun word(value:String)=Languages.lower(value).takeIf { it.length in 2..24 && it.first().isLetter() &&
        it.last().isLetter() && it.all { c -> c.isLetter() || c=='\'' || c=='’' } }
    private fun weight(entry:Entry)=entry.count*2.0.pow(-(day()-entry.seen).coerceAtLeast(0)/30.0)
    fun learn(scope:String,value:String,original:String=""):Boolean {
        val fixed=word(value) ?: return false
        add(scope,"",fixed)
        val typed=word(original)
        if(typed!=null && !fixed.startsWith(typed) && oneEdit(typed,fixed)) add(scope,typed,fixed)
        prune()
        return true
    }
    private fun add(scope:String,typed:String,word:String) {
        val key=key(scope,typed,word)
        val entry=entries[key]
        if(entry==null) entries[key]=Entry(scope,typed,word,1,day())
        else { entry.count=(weight(entry).roundToInt()+1).coerceAtMost(255);entry.seen=day() }
    }
    fun reject(scope:String,typed:String) {
        val value=Languages.lower(typed)
        entries.values.removeAll { it.scope==scope && it.typed==value }
    }
    fun candidates(scope:String,prefix:String):List<Pair<String,Double>> {
        prune()
        val typed=Languages.lower(prefix)
        return entries.values.asSequence().filter { it.scope==scope && it.typed.isEmpty() && it.count>=2 && it.word.startsWith(typed) && it.word!=typed }
            .map { it.word to weight(it) }.filter { it.second>=1.0 }.sortedByDescending { it.second }.take(24)
            .map { it.first to ln(it.second) }.toList()
    }
    fun correction(scope:String,original:String):String? {
        prune()
        val typed=Languages.lower(original)
        val choices=entries.values.filter { it.scope==scope && it.typed==typed }.sortedByDescending { weight(it) }
        val best=choices.firstOrNull() ?: return null
        return best.word.takeIf { best.count>=3 && weight(best)>=2.0 && weight(best)>=3*(choices.getOrNull(1)?.let { weight(it) } ?: 0.0) }
    }
    fun nearby(scope:String,original:String):List<Pair<String,Double>> {
        prune()
        val typed=Languages.lower(original)
        if(typed.length !in 3..24) return emptyList()
        return entries.values.asSequence().filter { it.scope==scope && it.typed.isEmpty() && it.count>=2 && oneEdit(typed,it.word) }
            .map { it.word to weight(it) }.filter { it.second>=1.0 }.sortedByDescending { it.second }.take(3)
            .map { it.first to ln(it.second) }.toList()
    }
    fun snapshot():List<Entry> { prune();return entries.values.map { it.copy() } }
    fun restore(rows:List<Entry>) {
        entries.clear()
        for(row in rows.take(1024)) if(row.scope.length<=128 && row.scope.none { it=='\t' || it=='\n' } && word(row.word)==row.word &&
            (row.typed.isEmpty() || word(row.typed)==row.typed && oneEdit(row.typed,row.word)) && row.count in 1..255 && row.seen<=day())
            entries[key(row.scope,row.typed,row.word)]=row.copy()
        prune()
    }
    fun clear()=entries.clear()
    private fun prune() {
        entries.values.removeAll { day()-it.seen>90 }
        if(entries.size>1024) entries.values.sortedBy { weight(it) }.take(entries.size-1024).forEach { entries.remove(key(it.scope,it.typed,it.word)) }
    }
    companion object {
        private fun oneEdit(a:String,b:String):Boolean {
            if(kotlin.math.abs(a.length-b.length)>1 || a==b) return false
            var at=0
            while(at<minOf(a.length,b.length) && a[at]==b[at]) at++
            if(at==minOf(a.length,b.length)) return true
            return when {
                a.length>b.length -> a.regionMatches(at+1,b,at,b.length-at)
                b.length>a.length -> a.regionMatches(at,b,at+1,a.length-at)
                a.regionMatches(at+1,b,at+1,a.length-at-1) -> true
                else -> at+1<a.length && a[at]==b[at+1] && a[at+1]==b[at] && a.regionMatches(at+2,b,at+2,a.length-at-2)
            }
        }
    }
}
