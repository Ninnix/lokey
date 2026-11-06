package org.lokey.app

import org.json.JSONObject

class SpeedRun(val passage: String = PASSAGE) {
    private val marks = ArrayList<Boolean>()
    var started = 0L
        private set
    var ended = 0L
        private set
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var score = 0
        private set
    var predictionTaps = 0
    var assistedWords = 0
    var eligible = true
        private set
    val index get() = marks.size
    val finished get() = index == passage.length
    val correct get() = marks.count { it }
    val accuracy get() = if (marks.isEmpty()) 100.0 else correct * 100.0 / marks.size
    val multiplier get() = minOf(4, 1 + combo / 10)
    fun elapsed(now: Long) = if (started == 0L) 0L else ((if (ended == 0L) now else ended) - started).coerceAtLeast(1)
    fun wpm(now: Long): Double = if (started == 0L) 0.0 else correct * 12000.0 / elapsed(now).coerceAtLeast(1000)
    fun update(text: String, now: Long): List<Boolean> {
        if (finished) return emptyList()
        if (started == 0L && text.isNotEmpty()) started = now
        // Earlier text may be edited, but hits cannot be farmed by deleting and retyping.
        val events = ArrayList<Boolean>()
        for (i in marks.size until minOf(text.length, passage.length)) {
            val hit = text[i] == passage[i]
            marks.add(hit); events.add(hit)
            if (hit) { combo++; bestCombo = maxOf(bestCombo, combo); score += 10 * multiplier }
            else combo = 0
        }
        if (finished) ended = now
        return events
    }
    fun disqualify() { eligible = false }
    fun snapshot(now: Long) = JSONObject().put("version",2).put("passage", ID).put("score", score).put("wpm", wpm(now)).put("accuracy", accuracy)
        .put("combo", bestCombo).put("prediction_taps", predictionTaps).put("assisted_words", assistedWords).put("elapsed_ms", elapsed(now)).put("eligible", eligible)
    companion object {
        const val ID = "inferno-i-longfellow-1"
        const val PASSAGE = "Midway upon the journey of our life I found myself within a forest dark, For the straightforward pathway had been lost. Ah me! how hard a thing it is to say What was this forest savage, rough, and stern, Which in the very thought renews the fear."
        const val SOURCE = "Dante · Inferno I · Henry Wadsworth Longfellow (1867)"
    }
}
