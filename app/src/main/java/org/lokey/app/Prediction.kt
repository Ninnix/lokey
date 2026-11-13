package org.lokey.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class Prediction(private val context: Context, private val prefs: Prefs,private val learning:UsageLearning=UsageLearning.shared(context)) {
    data class Result(val before: String, val words: List<String>, val letters: FloatArray, val language: String, val neural: Boolean, val correction: String? = null, val appendWords: Set<String> = emptySet(),val wordScores:Map<String,Double> = emptyMap(),val spelling:Languages.Spelling?=null)
    private val main = Handler(Looper.getMainLooper())
    private val worker=ModelMemory.Worker("lokey-prediction",ModelMemory.TEXT,Thread.MIN_PRIORITY)
    private val version = AtomicInteger()
    private val correctionVersion=AtomicInteger()
    private val session=AtomicInteger()
    private val cleared=AtomicBoolean()
    internal val workCount get()=worker.executions
    private lateinit var languages: Languages
    private lateinit var local:LocalPrediction
    private fun scope(code:String)=UsageLearning.scope(prefs.layout,code)
    private fun primaryLanguage()=ModelLanguages.selected(context,prefs).base.takeIf { it in Languages.names } ?: "en"
    private var language = primaryLanguage()
    private var loaded = ""
    @Volatile private var loadedGeneration=-1L
    private var failed = ""
    private var checkedBefore:String?=null
    private var checkedCorrection:Result?=null
    private data class WordCache(val boundary:String,val language:String,val prefix:String,val words:Map<String,List<String>>,val model:String,val session:Int,val scores:Map<String,Double>,val letters:Map<String,FloatArray>)
    @Volatile private var wordCaches=emptyList<WordCache>()
    private fun wordContext(text:String)=text.trimEnd().takeLast(192)
    private fun key(word:String)=Languages.fold(word).replace("'","")
    private fun cached(boundary:String,prefix:String,language:String?=null):Pair<WordCache,List<String>>? {
        if(loadedGeneration!=Native.modelGeneration(ModelMemory.TEXT)) return null
        val typed=key(prefix)
        val model=Models.selectedText(context,prefs)
        val file=Models.file(context,model)
        val identity="${model.id}:${file.lastModified()}:${file.length()}"
        return wordCaches.filter { it.session==session.get() && it.boundary==wordContext(boundary) && (language==null || it.language==language) && it.model==identity && typed.startsWith(it.prefix) }
            .map { cache -> cache to cache.words[typed].orEmpty() }.maxByOrNull { it.second.size }
    }
    private fun remember(boundary:String,prefix:String,language:String,words:List<String>,epoch:Int,scores:Map<String,Double> = emptyMap()) {
        // Build all prefix states on the worker so a tap never scans or scores words.
        val index=HashMap<String,MutableList<String>>()
        for(word in words) {
            val folded=key(word)
            for(end in 0..folded.length) index.getOrPut(folded.take(end)) { ArrayList() }.add(word)
        }
        val letters=index.mapValues { (prefix,matches) -> AdaptiveKeys.probabilities(prefix,matches.mapNotNull { word -> scores[word]?.let { word to it } }) }
        val cache=WordCache(wordContext(boundary),language,key(prefix),index,loaded,epoch,scores,letters)
        wordCaches=(listOf(cache)+wordCaches.filter { it.boundary!=cache.boundary || it.language!=language || it.prefix!=cache.prefix }).take(4)
    }
    private var pending: Runnable? = null
    @Volatile var latest: Result? = null
        private set
    init { worker.execute(false) { languages = Languages.shared(context);local=LocalPrediction(context,languages) } }
    // Finishing a word must not cancel its correction while next-word suggestions refresh.
    fun correct(before:String,learningAllowed:Boolean=false,callback:(Result?)->Unit) {
        cleared.set(false)
        val ticket=correctionVersion.incrementAndGet()
        worker.execute {
            if(ticket!=correctionVersion.get()) return@execute
            var result:Result?=null
            try {
                val detected=languages.detect(before,language)
                val spelling=local.spelling(before,detected)
                val personal=if(learningAllowed) learning.correction(scope(detected.code),Languages.prefix(before)) else null
                result=if(spelling!=null) spelling.takeIf { it.automatic }?.let {
                    Result(before,listOf(it.word),FloatArray(26),detected.code,false,it.word,spelling=it)
                } else if(personal!=null) Languages.casing(personal,Languages.prefix(before),detected.code).let {
                    Result(before,listOf(it),FloatArray(26),detected.code,false,it)
                } else if(Models.text.none { Models.ready(context,it) }) local.correction(before,detected)?.let {
                    Result(before,listOf(it),FloatArray(26),detected.code,false,it)
                } else evaluateCorrection(before,detected)
            }
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
        val generation=Native.modelGeneration(ModelMemory.TEXT)
        if(loadedGeneration!=generation) failed=""
        if(failed==identity) return false
        if(loaded!=identity || loadedGeneration!=generation) {
            loaded="";wordCaches=emptyList()
            val ready=Native.loadText(file.path)
            loadedGeneration=Native.modelGeneration(ModelMemory.TEXT)
            if(!ready) { failed=identity;return false }
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
    fun request(before:String,neuralAllowed:Boolean=true,script:String?=null,learningAllowed:Boolean=false,callback:(Result)->Unit) {
        requestBase(before,neuralAllowed,script,learningAllowed) { result ->
            val personal=if(learningAllowed) learning.personalize(result,scope(result.language)) else result
            val value=if(::languages.isInitialized) languages.spelling(personal,prefs.autocorrect) else personal
            latest=value;callback(value)
        }
    }
    private fun requestBase(before: String, neuralAllowed:Boolean, script:String?,learningAllowed:Boolean,callback: (Result) -> Unit) {
        cleared.set(false)
        if(neuralAllowed) Native.touchModel(ModelMemory.TEXT)
        val detected=KeyboardLayouts.predictionLanguage(before,script)
        if(detected!=null) { requestScript(before,detected,neuralAllowed,callback);return }
        val localOnly=neuralAllowed && Models.text.none { Models.ready(context,it) }
        val ticket = version.incrementAndGet()
        val epoch=session.get()
        pending?.let { main.removeCallbacks(it) }
        val typed=Languages.prefix(before)
        val instant=if(neuralAllowed) cached(before.dropLast(typed.length).trimEnd(),typed) else null
        if(instant!=null && instant.second.isNotEmpty()) {
            val words=instant.second.take(3).map { Languages.casing(it,typed,instant.first.language) }
            val letters=instant.first.letters[key(typed)]?.copyOf() ?: FloatArray(26)
            val result=Result(before,words,letters,instant.first.language,true,wordScores=instant.first.scores.mapKeys { Languages.lower(it.key) })
            latest=result;callback(result)
        }
        val immediate=localOnly && ::languages.isInitialized
        if(immediate) {
            val previous=latest?.language ?: ModelLanguages.selected(context,prefs).base.takeIf { it in Languages.names } ?: "en"
            val detected=languages.detect(before,previous)
            val result=LocalPrediction.basic(languages,before,detected,prefs.autocorrect)
            latest=result;callback(result)
        }
        val task = Runnable {
            worker.execute(neuralAllowed) {
                if (ticket != version.get()) return@execute
                val prefix = Languages.prefix(before)
                val stem = wordContext(before.dropLast(prefix.length))
                val preferred=if(localOnly) latest?.language ?: ModelLanguages.selected(context,prefs).base.takeIf { it in Languages.names } ?: language else language
                val detected = languages.detect(before, preferred)
                language = detected.code
                if(localOnly) {
                    if(!immediate) {
                        val result=LocalPrediction.basic(languages,before,detected,prefs.autocorrect)
                        main.post { if(ticket==version.get()) { latest=result;callback(result) } }
                    }
                    val result=local.request(before,detected,prefs.autocorrect)
                    main.post { if(ticket==version.get()) { latest=result;callback(result) } }
                    return@execute
                }
                val completions = languages.choices(detected, stem, prefix)
                val contraction=languages.contraction(detected,prefix)
                val complete = prefix.length >= 2 && contraction==null && detected.words.containsKey(Languages.lower(prefix))
                val sentence = if (complete) wordContext(before) else stem
                val pool = languages.choices(detected, sentence, if (complete) "" else prefix).toMutableList()
                if(learningAllowed && !complete && prefix.isNotEmpty()) for((word,score) in learning.candidates(scope(detected.code),prefix)) {
                    if(pool.none { it.word.equals(word,true) }) pool.add(Languages.Choice(word,score.coerceAtMost(3.0)))
                }
                if ((complete || prefix.isEmpty()) && Languages.tokens(sentence).isNotEmpty() && sentence.lastOrNull() !in listOf('.', '?', '!'))
                    listOf("?", ".", "!").forEach { pool.add(Languages.Choice(it, 3.4)) }
                val fixes = if(prefs.autocorrect) contraction?.let { listOf(it) } ?: if(pool.isEmpty()) languages.corrections(detected,prefix) else emptyList() else emptyList()
                fun render(scores: FloatArray?, neural: Boolean, generated:List<String> = emptyList(),cachedScores:Map<String,Double> = emptyMap()): Result {
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
                    val letterScores=pool.mapIndexedNotNull { i,choice -> scores?.getOrNull(i)?.let { choice.word to it.toDouble() } }+
                        generated.mapIndexedNotNull { i,word -> (scores?.getOrNull(pool.size+fixes.size+i)?.toDouble() ?: cachedScores[word])?.let { word to it } }
                    val letters=AdaptiveKeys.probabilities(if(complete) "" else prefix,letterScores)
                    val wordScores=(cachedScores.toList()+letterScores).associate { Languages.lower(it.first) to it.second }
                    return local.spelling(Result(before, words, letters, detected.code, neural, correction?.let { casing(it) }, append,wordScores),detected,prefs.autocorrect)
                }
                fun deliver(result: Result) { main.post { if (ticket == version.get()) { latest = result; callback(result) } } }
                if(instant?.second.isNullOrEmpty()) deliver(render(null, false))
                if(!neuralAllowed) return@execute
                if (ticket != version.get()) return@execute
                try {
                    if(!load() || ticket!=version.get()) return@execute
                    val start=if(complete) "" else prefix
                    val cache=cached(sentence,start,detected.code)
                    val cached=cache?.second.orEmpty()
                    if(cached.isNotEmpty()) deliver(render(null,true,cached,cache?.first?.scores.orEmpty()))
                    if(cached.size>=3) return@execute
                    val generated=Native.complete(sentence.takeLast(240),detected.name,start,if(fixes.isNotEmpty() && contraction==null) 1 else 0).lines()
                        .filter { word ->
                            (Languages.valid(word) && (detected.code!="en" || word.length>1 || Languages.lower(word) in listOf("a","i"))) ||
                                (prefix.isEmpty() || complete) && word in listOf("?",".","!")
                        }.distinctBy { Languages.lower(it) }.take(24).ifEmpty { cached }
                    if(ticket!=version.get()) return@execute
                    val choices=(pool+fixes).map { Languages.casing(it.word,"",detected.code) }+generated.map { Languages.casing(it,"",detected.code) }
                    val unique=choices.distinct();val raw=Native.rank(sentence.takeLast(240),detected.name,unique.toTypedArray())
                    val scores=if(raw.size==unique.size) unique.zip(raw.toList()).toMap().let { values -> FloatArray(choices.size) { values.getValue(choices[it]) } } else raw
                    if(scores.size==choices.size && scores.all { it.isFinite() }) {
                        val fallback=pool.mapIndexed { i,choice -> choice.word to (choice.prior+scores[i]) }.sortedByDescending { it.second }
                            .map { it.first }.filter { word -> word.none(Char::isWhitespace) }
                        val retained=(generated+fallback).distinctBy { Languages.lower(it) }
                        val retainedScores=pool.mapIndexed { i,choice -> choice.word to scores[i].toDouble() }.toMap()+
                            generated.mapIndexed { i,word -> word to scores[pool.size+fixes.size+i].toDouble() }.toMap()
                        remember(sentence,start,detected.code,retained,epoch,retainedScores)
                        val value=render(scores,true,generated)
                        deliver(value)
                        if(prefs.autocorrect && fixes.isNotEmpty() && ticket==version.get()) {
                            val checked=evaluateCorrection(before,detected,generated)
                            if(checked?.correction!=value.correction) deliver(value.copy(correction=checked?.correction))
                        }
                    } else android.util.Log.w("Lokey","Prediction scores: ${scores.size}/${choices.size}; finite=${scores.all { it.isFinite() }}")
                } catch(error:Exception) { android.util.Log.w("Lokey","Prediction inference failed",error) }
            }
        }
        pending = task
        main.postDelayed(task, if(localOnly || prefs.adaptive && instant?.second.isNullOrEmpty()) 0 else 35)
    }
    fun glide(before: String, points: List<Glide.Point>, centers: Map<Char, Glide.Point>, callback: (List<String>) -> Unit) {
        cleared.set(false)
        val ticket = version.incrementAndGet()
        pending?.let { main.removeCallbacks(it) }
        worker.execute {
            if (ticket != version.get()) return@execute
            val detected = languages.detect(before, language)
            language = detected.code
            val candidates = if (Languages.tokens(before).size < 2) languages.all.filter { it.code in listOf(detected.code, "en", "it", "es", "fr") } else listOf(detected)
            val matches = candidates.flatMap { Glide.decode(points,centers,languages.swipe(it)) }.sortedByDescending { it.score }.distinctBy { it.word }.take(16)
            if (matches.isEmpty()) { main.post { if (ticket == version.get()) callback(emptyList()) }; return@execute }
            var ranked = matches
            try {
                val model = Models.selectedText(context,prefs)
                if (Models.ready(context, model)) {
                    if (loadModel(model) && ticket == version.get()) {
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
        session.incrementAndGet();version.incrementAndGet();correctionVersion.incrementAndGet();latest=null;language=primaryLanguage();wordCaches=emptyList()
        pending?.let { main.removeCallbacks(it) }
        // Editor cleanup must not keep otherwise idle weights resident.
        if(!cleared.getAndSet(true) && !worker.isShutdown) worker.execute(false) { wordCaches=emptyList();checkedBefore=null;checkedCorrection=null;Native.resetText() }
    }
    fun close() { clear(); worker.shutdown() }
}
