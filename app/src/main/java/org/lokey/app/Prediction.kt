package org.lokey.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

class Prediction(private val context: Context, private val prefs: Prefs) {
    data class Result(val before: String, val words: List<String>, val letters: FloatArray, val language: String, val neural: Boolean, val correction: String? = null, val appendWords: Set<String> = emptySet())
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lokey-prediction").apply { priority = Thread.MIN_PRIORITY } }
    private val version = AtomicInteger()
    private val correctionVersion=AtomicInteger()
    private val session=AtomicInteger()
    private lateinit var languages: Languages
    private var language = "en"
    private var loaded = ""
    private var failed = ""
    private var checkedBefore:String?=null
    private var checkedCorrection:Result?=null
    private data class WordCache(val boundary:String,val language:String,val prefix:String,val words:List<String>,val model:String,val session:Int)
    @Volatile private var wordCaches=emptyList<WordCache>()
    private fun wordContext(text:String)=text.trimEnd().takeLast(192)
    private fun key(word:String)=Languages.fold(word).replace("'","")
    private fun cached(boundary:String,prefix:String,language:String?=null):Pair<WordCache,List<String>>? {
        val typed=key(prefix)
        val model=Models.selectedText(context,prefs)
        val file=Models.file(context,model)
        val identity="${model.id}:${file.lastModified()}:${file.length()}"
        return wordCaches.filter { it.session==session.get() && it.boundary==wordContext(boundary) && (language==null || it.language==language) && it.model==identity && typed.startsWith(it.prefix) }
            .map { cache -> cache to cache.words.filter { key(it).startsWith(typed) } }.maxByOrNull { it.second.size }
    }
    private fun remember(boundary:String,prefix:String,language:String,words:List<String>,epoch:Int) {
        val cache=WordCache(wordContext(boundary),language,key(prefix),words,loaded,epoch)
        wordCaches=(listOf(cache)+wordCaches.filter { it.boundary!=cache.boundary || it.language!=language || it.prefix!=cache.prefix }).take(4)
    }
    private var pending: Runnable? = null
    @Volatile var latest: Result? = null
        private set
    init { worker.execute { languages = Languages.shared(context) } }
    // Finishing a word must not cancel its correction while next-word suggestions refresh.
    fun correct(before:String,callback:(Result?)->Unit) {
        val ticket=correctionVersion.incrementAndGet()
        worker.execute {
            if(ticket!=correctionVersion.get()) return@execute
            var result:Result?=null
            try { result=evaluateCorrection(before,languages.detect(before,language)) }
            catch (_:Exception) { /* Keep the typed word when inference is unavailable. */ }
            main.post { if(ticket==correctionVersion.get()) callback(result) }
        }
    }
    private fun load():Boolean {
        return loadModel(Models.selectedText(context,prefs))
    }
    private fun loadModel(model:Models.Model):Boolean {
        if(!Models.ready(context,model)) return false
        val file=Models.file(context,model)
        val identity="${model.id}:${file.lastModified()}:${file.length()}"
        if(failed==identity) return false
        if(loaded!=identity) {
            if(!Native.loadText(file.path)) { failed=identity;return false }
            Acceleration.observed(Native.textBackend())
            loaded=identity;checkedBefore=null;checkedCorrection=null;wordCaches=emptyList()
        }
        return true
    }
    private fun evaluateCorrection(before:String,detected:Languages.Language,generated:List<String> = emptyList()):Result? {
        val prefix=Languages.prefix(before)
        val alias=languages.contraction(detected,prefix)
        fun result(word:String)=Languages.casing(word,prefix,detected.code).let { fixed ->
            Result(before,listOf(fixed),FloatArray(26),detected.code,true,fixed)
        }
        if(alias!=null) return result(alias.word)
        if(prefix.length !in 3..24 || prefix.first().isUpperCase() || languages.all.any { Languages.lower(prefix) in it.words } || !load()) return null
        if(checkedBefore==before) return checkedCorrection
        val stem=wordContext(before.dropLast(prefix.length))
        fun nearby(words:List<String>)=words.filter { Languages.valid(it) && !it.equals(prefix,true) && Languages.distance(Languages.fold(prefix),Languages.fold(it))<=1 }
            .distinctBy { Languages.lower(it) }.take(3)
        val known=nearby(generated+languages.corrections(detected,prefix).map { it.word })
        val choices=known.ifEmpty {
            val ending=if(prefix.length>=5) Native.complete(stem.takeLast(240),detected.name,prefix.dropLast(1)).lines() else emptyList()
            nearby(ending+Native.complete(stem.takeLast(240),detected.name,prefix,1).lines())
        }
        var answer:Result?=null
        if(choices.isNotEmpty()) {
            val scores=Native.score(stem.takeLast(240),detected.name,(listOf(prefix)+choices).toTypedArray())
            if(scores.size==choices.size+1 && scores.all { it.isFinite() }) {
                val ranked=choices.indices.sortedByDescending { scores[it+1] }
                val best=ranked.first();val margin=if(ranked.size>1) scores[best+1]-scores[ranked[1]+1] else 10f
                if(scores[best+1]-scores[0]>=2.5f && margin>=1f) answer=result(choices[best])
            }
        }
        checkedBefore=before;checkedCorrection=answer
        return answer
    }
    private fun requestScript(before:String,name:String,allowed:Boolean,callback:(Result)->Unit) {
        val ticket=version.incrementAndGet();pending?.let { main.removeCallbacks(it) }
        val code=when(name) {
            "Russian" -> "ru";"Ukrainian" -> "uk";"Bulgarian" -> "bg";"Macedonian" -> "mk";"Arabic" -> "ar";"Persian" -> "fa";"Greek" -> "el"
            "Hindi" -> "hi";"Korean" -> "ko";"Japanese" -> "ja";"Vietnamese" -> "vi";"Thai" -> "th";"Chinese" -> "zh";else -> ModelLanguages.code(name)
        }
        val unspaced=code in listOf("zh","yue","ja","th")
        val epoch=session.get()
        val prefix=if(unspaced) "" else Languages.prefix(before)
        val stem=wordContext(before.dropLast(prefix.length))
        fun result(words:List<String>)=words.take(3).map { Languages.casing(it,prefix,code) }.let {
            Result(before,it,FloatArray(26),code,true,appendWords=if(unspaced) it.toSet() else emptySet())
        }
        val cached=if(allowed) cached(stem,prefix,code)?.second.orEmpty() else emptyList()
        val immediate=if(cached.isNotEmpty()) result(cached) else Result(before,emptyList(),FloatArray(26),code,false)
        latest=immediate;callback(immediate)
        if(!allowed || before.isBlank() || cached.size>=3) return
        val task=Runnable { worker.execute {
            if(ticket!=version.get()) return@execute
            try {
                val model=Models.selectedText(context,prefs)
                if(!loadModel(model)) return@execute
                val words=Native.complete(stem.takeLast(240),name,prefix.lowercase()).lines()
                    .filter { it.isNotBlank() && it.any { c -> c.isLetter() } }.distinctBy { Languages.lower(it) }.take(24)
                if(ticket!=version.get()) return@execute
                remember(stem,prefix,code,words,epoch)
                val value=result(words)
                main.post { if(ticket==version.get()) { latest=value;callback(value) } }
            } catch(_:Exception) { }
        } }
        pending=task;main.postDelayed(task,150)
    }
    fun request(before: String, neuralAllowed:Boolean=true, script:String?=null,callback: (Result) -> Unit) {
        val detected=KeyboardLayouts.predictionLanguage(before,script)
        if(detected!=null) { requestScript(before,detected,neuralAllowed,callback);return }
        val ticket = version.incrementAndGet()
        val epoch=session.get()
        pending?.let { main.removeCallbacks(it) }
        val typed=Languages.prefix(before)
        val instant=if(neuralAllowed) cached(before.dropLast(typed.length).trimEnd(),typed) else null
        if(instant!=null && instant.second.isNotEmpty()) {
            val words=instant.second.take(3).map { Languages.casing(it,typed,instant.first.language) }
            val letters=FloatArray(26)
            val offset=key(typed).length
            instant.second.forEachIndexed { i,word ->
                val next=key(word).getOrNull(offset)
                if(next!=null && next in 'a'..'z') letters[next-'a']+=1f/(i+1)
            }
            val total=letters.sum()
            if(total>0) for(i in letters.indices) letters[i]/=total
            val result=Result(before,words,letters,instant.first.language,true)
            latest=result;callback(result)
        }
        val task = Runnable {
            worker.execute {
                if (ticket != version.get()) return@execute
                val prefix = Languages.prefix(before)
                val stem = wordContext(before.dropLast(prefix.length))
                val detected = languages.detect(before, language)
                language = detected.code
                val completions = languages.choices(detected, stem, prefix)
                val contraction=languages.contraction(detected,prefix)
                val complete = prefix.length >= 2 && contraction==null && detected.words.containsKey(Languages.lower(prefix))
                val sentence = if (complete) wordContext(before) else stem
                val pool = languages.choices(detected, sentence, if (complete) "" else prefix).toMutableList()
                if ((complete || prefix.isEmpty()) && Languages.tokens(sentence).isNotEmpty() && sentence.lastOrNull() !in listOf('.', '?', '!'))
                    listOf("?", ".", "!").forEach { pool.add(Languages.Choice(it, 3.4)) }
                val fixes = if(prefs.autocorrect) contraction?.let { listOf(it) } ?: if(pool.isEmpty()) languages.corrections(detected,prefix) else emptyList() else emptyList()
                fun render(scores: FloatArray?, neural: Boolean, generated:List<String> = emptyList()): Result {
                    fun score(choice: Languages.Choice, index: Int) = choice.prior + (scores?.getOrNull(index)?.toDouble() ?: 0.0)
                    val ranked = pool.mapIndexed { index, choice -> choice to score(choice, index) }
                        .filter { neural || it.first.word.firstOrNull()?.isLetter() == true }.sortedByDescending { it.second }
                    val corrections = fixes.mapIndexed { index, choice -> choice to score(choice, pool.size + index) }.sortedByDescending { it.second }
                    val correction=if(prefs.autocorrect) contraction?.word else null
                    fun casing(word:String)=Languages.casing(word,prefix,detected.code,complete)
                    val suggested=(corrections.take(if(ranked.isEmpty()) 3 else 1).map { it.first.word } + generated + ranked.map { it.first.word })
                    val words=suggested.map { casing(it) }.distinctBy { Languages.lower(it) }.take(3).toMutableList()
                    if(words.isEmpty() && prefix.isNotEmpty()) words.add(prefix)
                    val append = if (complete) words.toMutableSet() else mutableSetOf<String>()
                    if (complete) completions.firstOrNull { choice -> choice.word !in words }?.let { completion ->
                        if (words.size == 3) append.remove(words.removeAt(2))
                        val word = Languages.casing(completion.word,prefix,detected.code)
                        words.add(word)
                    }
                    val letters = FloatArray(26)
                    ranked.take(24).forEach { (choice, score) ->
                        val next = Languages.fold(choice.word).getOrNull(Languages.fold(prefix).length)
                        if (next != null && next in 'a'..'z') letters[next - 'a'] += exp((score - (ranked.firstOrNull()?.second ?: score)).coerceAtLeast(-18.0)).toFloat()
                    }
                    generated.forEachIndexed { i,word ->
                        val key=Languages.fold(word).replace("'","")
                        val next=key.getOrNull(Languages.fold(prefix).replace("'","").length)
                        if(next!=null && next in 'a'..'z') letters[next-'a']+=2f/(i+1)
                    }
                    val total = letters.sum()
                    if (total > 0) for (i in letters.indices) letters[i] /= total
                    return Result(before, words, letters, detected.code, neural, correction?.let { casing(it) }, append)
                }
                fun deliver(result: Result) { main.post { if (ticket == version.get()) { latest = result; callback(result) } } }
                if(instant?.second.isNullOrEmpty()) deliver(render(null, false))
                if(!neuralAllowed) return@execute
                if (ticket != version.get()) return@execute
                try {
                    if(!load() || ticket!=version.get()) return@execute
                    val start=if(complete) "" else prefix
                    val cached=cached(sentence,start,detected.code)?.second.orEmpty()
                    if(cached.isNotEmpty()) deliver(render(null,true,cached))
                    if(cached.size>=3) return@execute
                    val generated=Native.complete(sentence.takeLast(240),detected.name,start,if(fixes.isNotEmpty() && contraction==null) 1 else 0).lines()
                        .filter { word ->
                            (Languages.valid(word) && (detected.code!="en" || word.length>1 || Languages.lower(word) in listOf("a","i"))) ||
                                (prefix.isEmpty() || complete) && word in listOf("?",".","!")
                        }.distinctBy { Languages.lower(it) }.take(24).ifEmpty { cached }
                    if(ticket!=version.get()) return@execute
                    remember(sentence,start,detected.code,generated,epoch)
                    val scores=Native.rank(sentence.takeLast(240),detected.name,(pool+fixes).map { Languages.casing(it.word,"",detected.code) }.toTypedArray())
                    if(scores.size==pool.size+fixes.size && scores.all { it.isFinite() }) {
                        val value=render(scores,true,generated)
                        deliver(value)
                        if(prefs.autocorrect && fixes.isNotEmpty() && ticket==version.get()) {
                            val checked=evaluateCorrection(before,detected,generated)
                            if(checked?.correction!=value.correction) deliver(value.copy(correction=checked?.correction))
                        }
                    } else android.util.Log.w("Lokey","Prediction scores: ${scores.size}/${pool.size+fixes.size}; finite=${scores.all { it.isFinite() }}")
                } catch(error:Exception) { android.util.Log.w("Lokey","Prediction inference failed",error) }
            }
        }
        pending = task
        main.postDelayed(task, 35)
    }
    fun glide(before: String, points: List<Glide.Point>, centers: Map<Char, Glide.Point>, callback: (List<String>) -> Unit) {
        val ticket = version.incrementAndGet()
        pending?.let { main.removeCallbacks(it) }
        worker.execute {
            if (ticket != version.get()) return@execute
            val detected = languages.detect(before, language)
            language = detected.code
            val candidates = if (Languages.tokens(before).size < 2) languages.all.filter { it.code in listOf(detected.code, "en", "it", "es", "fr") } else listOf(detected)
            val matches = candidates.flatMap { Glide.decode(points, centers, it.entries) }.sortedByDescending { it.score }.distinctBy { it.word }.take(16)
            if (matches.isEmpty()) { main.post { if (ticket == version.get()) callback(emptyList()) }; return@execute }
            var ranked = matches
            try {
                val model = Models.selectedText(context,prefs)
                if (Models.ready(context, model)) {
                    val file = Models.file(context, model)
                    val identity = "${model.id}:${file.lastModified()}:${file.length()}"
                    if (loaded != identity && Native.loadText(file.path)) {
                        loaded = identity
                        Acceleration.observed(Native.textBackend())
                    }
                    if (loaded == identity && ticket == version.get()) {
                        val scores = Native.rank(wordContext(before), detected.name, matches.map { it.word }.toTypedArray())
                        if (scores.size == matches.size) ranked = matches.mapIndexed { i, match -> match.copy(score = match.score + scores[i] * 0.16) }.sortedByDescending { it.score }
                    }
                }
            } catch (_: Exception) { /* Geometry decoding remains available without a model. */ }
            val words = ranked.take(3).map { it.word }
            main.post { if (ticket == version.get()) callback(words) }
        }
    }
    fun clear() {
        session.incrementAndGet();version.incrementAndGet();correctionVersion.incrementAndGet();latest=null;language="en";wordCaches=emptyList()
        pending?.let { main.removeCallbacks(it) }
        if(!worker.isShutdown) worker.execute { wordCaches=emptyList();checkedBefore=null;checkedCorrection=null;Native.resetText() }
    }
    fun close() { clear(); worker.shutdown() }
}
