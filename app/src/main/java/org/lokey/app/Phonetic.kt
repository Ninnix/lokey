package org.lokey.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln

class Phonetic(private val context:Context,private val asset:String) {
    private data class Entry(val reading:String,val text:String,val frequency:Int)
    private data class Reading(val key:String,val boundaries:Set<Int>,val tones:Map<Int,Char>)
    private fun reading(value:String):Reading {
        val key=StringBuilder();val boundaries=HashSet<Int>();val tones=HashMap<Int,Char>()
        for(c in value.lowercase().replace('ü','v')) when {
            c=='\'' -> boundaries.add(key.length)
            asset=="jyutping.tsv" && c in '1'..'6' -> { tones[key.length]=c;boundaries.add(key.length) }
            else -> key.append(c)
        }
        return Reading(key.toString(),boundaries,tones)
    }
    private data class Path(val text:String,val score:Double)
    private val dictionary=TreeMap<String,MutableList<Entry>>()
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"lokey-phonetic") }
    private val main=Handler(Looper.getMainLooper())
    private val version=AtomicInteger()
    private var loaded=false
    fun request(input:String,callback:(List<String>)->Unit) {
        val ticket=version.incrementAndGet()
        worker.execute {
            try {
                if(!loaded) {
                    context.assets.open(asset).bufferedReader().useLines { rows -> rows.forEach { row ->
                        val fields=row.split('\t')
                        val entry=Entry(fields[0],fields[1],fields[2].toInt())
                        val key=if(asset=="jyutping.tsv") entry.reading.filter { it!='\'' && it !in '1'..'6' } else entry.reading.replace("'","")
                        dictionary.getOrPut(key) { ArrayList() }.add(entry)
                    } }
                    dictionary.values.forEach { entries -> entries.sortByDescending { it.frequency } };loaded=true
                }
            } catch(_:Exception) {
                dictionary.clear();loaded=true
                main.post { if(ticket==version.get()) callback(listOf(input)) };return@execute
            }
            if(ticket!=version.get()) return@execute
            val choices=lookup(input)
            main.post { if(ticket==version.get()) callback(choices) }
        }
    }
    private fun lookup(input:String):List<String> {
        val query=reading(input);val key=query.key
        if(key.isEmpty()) return emptyList()
        fun fits(entry:Entry,start:Int,end:Int):Boolean {
            if(query.boundaries.isEmpty() && query.tones.isEmpty()) return true
            val parsed=reading(entry.reading)
            return query.boundaries.filter { it>start && it<end }.all { it-start in parsed.boundaries } &&
                query.tones.filterKeys { it>start && it<=end }.all { (at,tone) -> parsed.tones[at-start]==tone }
        }
        val exact=dictionary[key].orEmpty().filter { fits(it,0,key.length) }.map { Path(it.text,ln(it.frequency.toDouble()/60000000)) }
        val paths=Array(key.length+1) { ArrayList<Path>() };paths[0].add(Path("",0.0))
        if(asset!="hindi.tsv") for(start in key.indices) {
            val previous=paths[start].sortedByDescending { it.score }.distinctBy { it.text }.take(8)
            if(previous.isEmpty()) continue
            for(end in start+1..minOf(key.length,start+40)) {
                val entries=dictionary[key.substring(start,end)].orEmpty().filter { fits(it,start,end) }.take(8)
                for(entry in entries) for(path in previous) paths[end].add(Path(path.text+entry.text,path.score+ln(entry.frequency.toDouble()/60000000)))
                if(paths[end].size>128) {
                    val kept=paths[end].sortedByDescending { it.score }.distinctBy { it.text }.take(32)
                    paths[end].clear();paths[end].addAll(kept)
                }
            }
        }
        val complete=(exact+paths.last()).sortedByDescending { it.score }.map { it.text }.distinct()
        val prefixes=ArrayList<Entry>()
        for((reading,entries) in dictionary.tailMap(key)) {
            if(!reading.startsWith(key)) break
            if(reading.length<=key.length+6) prefixes.addAll(entries.filter { fits(it,0,key.length) })
        }
        val fallback=if(asset=="hindi.tsv") listOf(Hindi.render(input,true)) else emptyList()
        val limit=if(asset in listOf("cangjie.tsv","sucheng.tsv")) maxOf(96,complete.size) else 96
        return (complete+fallback+prefixes.sortedByDescending { it.frequency }.map { it.text }).distinct().take(limit)+input
    }
    companion object {
        private val instances=HashMap<String,Phonetic>()
        fun shared(context:Context,asset:String="pinyin.tsv")=synchronized(this) {
            instances.getOrPut(asset) { Phonetic(context.applicationContext,asset) }
        }
    }
}
