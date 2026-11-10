package org.lokey.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

class Translation(private val context:Context) {
    fun interface Sink { fun text(value:String) }
    data class Result(val source:String,val target:String,val text:String="",val busy:Boolean=false,val error:String?=null)
    private val main=Handler(Looper.getMainLooper())
    private val worker=ModelMemory.Worker("lokey-translation",ModelMemory.TRANSLATION)
    private val version=AtomicInteger()
    private var pending:Runnable?=null
    private var loaded=""
    private var loadedGeneration=-1L
    private var active=false
    private var lastSource=""
    private var lastTarget=""
    private var lastText=""
    private var lastModel=""
    fun request(source:String,target:String,callback:(Result)->Unit) {
        val ticket=version.incrementAndGet()
        pending?.let { main.removeCallbacks(it) }
        if(active) Native.cancelTranslation()
        active=false
        if(source.isBlank()) { callback(Result(source,target));return }
        if(source.length>2000) { callback(Result(source,target,error=context.tr("Choose up to 2,000 characters.")));return }
        val model=Models.selectedText(context,Prefs(context)).takeIf { Models.ready(context,it) }
        val file=model?.let { Models.file(context,it) }
        val identity=if(model!=null && file!=null) "${model.id}:${file.length()}:${file.lastModified()}" else ""
        if(source==lastSource && target==lastTarget && identity==lastModel && lastText.isNotEmpty()) { callback(Result(source,target,lastText));return }
        active=true
        val epoch=Native.cancelTranslation()
        callback(Result(source,target,busy=true))
        fun deliver(value:Result) { main.post { if(ticket==version.get()) { if(!value.busy) active=false;callback(value) } } }
        val task=Runnable {
            worker.execute {
                if(ticket!=version.get()) return@execute
                try {
                    check(model!=null && file!=null) { context.tr("Add a text model in Lokey → Models to translate.") }
                    if(loaded!=identity || loadedGeneration!=Native.modelGeneration(ModelMemory.TRANSLATION)) {
                        loaded=""
                        check(Native.loadTranslator(file.path)) { context.tr("Could not load %1\$s for translation.",model.title) }
                        loaded=identity
                        loadedGeneration=Native.modelGeneration(ModelMemory.TRANSLATION)
                    }
                    if(ticket!=version.get()) return@execute
                    val text=Native.translate(source,target,epoch,Sink { partial -> deliver(Result(source,target,partial,busy=true)) }).trim()
                    if(ticket!=version.get()) return@execute
                    check(text.isNotEmpty()) { context.tr("Could not translate this passage. Try again.") }
                    main.post {
                        if(ticket==version.get()) {
                            lastSource=source;lastTarget=target;lastText=text;lastModel=identity;active=false
                            callback(Result(source,target,text))
                        }
                    }
                } catch(error:Exception) { deliver(Result(source,target,error=error.message ?: context.tr("Translation failed. Try again."))) }
            }
        }
        pending=task;main.postDelayed(task,600)
    }
    fun cancel() { version.incrementAndGet();pending?.let { main.removeCallbacks(it) };if(active) Native.cancelTranslation();active=false }
    fun close() { cancel();worker.execute { if(loaded.isNotEmpty()) Native.closeTranslator() };worker.shutdown() }
    companion object {
        fun languages(context:Context)=context.assets.open("translation-languages.txt").bufferedReader().useLines { rows -> rows.filter { it.isNotBlank() && !it.startsWith('#') }.toList() }
    }
}
