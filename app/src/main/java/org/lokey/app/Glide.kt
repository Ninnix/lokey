package org.lokey.app

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

object Glide {
    data class Point(val x: Float, val y: Float)
    data class Match(val word: String, val score: Double)
    fun fold(word:String)=Languages.fold(word).replace("'","").replace('ı','i').replace("ß","ss")
    class Lexicon(source:List<Languages.Entry>) {
        val entries=source.map { it.copy(folded=fold(it.word)) }.distinctBy { it.word }
        private val buckets=Array(26*26) { ArrayList<Languages.Entry>() }
        init {
            for(entry in entries) {
                val word=entry.folded
                if(word.length in 2..24 && word.all { it in 'a'..'z' }) buckets[(word.first()-'a')*26+(word.last()-'a')].add(entry)
            }
        }
        fun candidates(trace:List<Point>,centers:Map<Char,Point>):List<Languages.Entry> {
            val starts=centers.filter { distance(trace.first(),it.value)<=1.05f }.keys.mapNotNull { fold(it.toString()).firstOrNull() }.toSet()
            val ends=centers.filter { distance(trace.last(),it.value)<=1.05f }.keys.mapNotNull { fold(it.toString()).lastOrNull() }.toSet()
            val result=ArrayList<Languages.Entry>()
            for(a in starts) for(b in ends) if(a in 'a'..'z' && b in 'a'..'z') result.addAll(buckets[(a-'a')*26+(b-'a')])
            return result
        }
    }
    fun decode(trace:List<Point>,centers:Map<Char,Point>,lexicon:Lexicon):List<Match> =
        if(trace.size<2 || centers.isEmpty()) emptyList() else decode(trace,centers,lexicon.candidates(trace,centers))
    fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun length(points: List<Point>) = points.zipWithNext().sumOf { (a, b) -> distance(a, b).toDouble() }.toFloat()
    private fun sample(points: List<Point>): List<Point> {
        if (points.size < 2) return List(32) { points.first() }
        val total = length(points)
        if (total < 0.01f) return List(32) { points.first() }
        val out = ArrayList<Point>(32)
        var segment = 1; var walked = 0f
        for (i in 0 until 32) {
            val target = total * i / 31f
            while (segment < points.lastIndex && walked + distance(points[segment - 1], points[segment]) < target) {
                walked += distance(points[segment - 1], points[segment]); segment++
            }
            val a = points[segment - 1]; val b = points[segment]
            val t = ((target - walked) / distance(a, b).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            out.add(Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
        return out
    }
    fun decode(trace: List<Point>, centers: Map<Char, Point>, entries: List<Languages.Entry>): List<Match> {
        if (trace.size < 2 || centers.isEmpty()) return emptyList()
        val input = sample(trace)
        val travel = length(trace)
        val matches = ArrayList<Match>()
        for (entry in entries) {
            val word = entry.folded
            if (word.length !in 2..24 || word.any { it !in 'a'..'z' }) continue
            val raw=Languages.lower(entry.word)
            val start=centers[raw.first()] ?: fold(raw.first().toString()).firstOrNull()?.let { centers[it] } ?: continue
            val end=centers[raw.last()] ?: fold(raw.last().toString()).lastOrNull()?.let { centers[it] } ?: continue
            val startError=distance(trace.first(),start);val endError=distance(trace.last(),end)
            if(startError>1.05f || endError>1.05f) continue
            val path=ArrayList<Point>()
            var valid=true
            for(c in raw) {
                if(c in "'’") continue
                val letters=if(c in centers) c.toString() else fold(c.toString())
                for(letter in letters) {
                    val point=centers[letter]
                    if(point==null) { valid=false;break }
                    if(path.lastOrNull()!=point) path.add(point)
                }
                if(!valid) break
            }
            if(!valid || path.isEmpty()) continue
            val shape = sample(path)
            var error = 0.0
            // Resampling removes timing differences; a small alignment window allows rounded turns.
            for (i in input.indices) {
                var best = Float.MAX_VALUE
                for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(shape.lastIndex)) best = minOf(best, distance(input[i], shape[j]))
                error += best * best
            }
            val cost = error / input.size + 0.8 * (startError * startError + endError * endError) + 0.12 * abs(travel - length(path))
            matches.add(Match(entry.word, -cost * 8 + ln(entry.count.toDouble()) * 0.13))
        }
        return matches.sortedByDescending { it.score }.take(16)
    }
}
