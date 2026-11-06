package org.lokey.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object Downloads {
    data class State(val message:String,val bytes:Long=0,val total:Long=0,val active:Boolean=false,val resumedBytes:Long=0) {
        val percent get()=if(total>0) (bytes*100/total).toInt().coerceIn(0,100) else 0
    }
    internal val states=ConcurrentHashMap<String,State>()
    internal val stops=ConcurrentHashMap<String,()->Unit>()
    internal val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"lokey-downloads") }
    private val callbacks=HashMap<String,MutableList<(String?)->Unit>>()
    private fun store(context:Context)=context.getSharedPreferences("downloads",Context.MODE_PRIVATE)
    @Synchronized internal fun pending(context:Context)=store(context).getString("queue","").orEmpty().split('\n').filter { it.isNotBlank() }
    @Synchronized internal fun remove(context:Context,key:String) { store(context).edit().putString("queue",pending(context).filter { it!=key }.joinToString("\n")).commit() }
    fun state(context:Context,key:String)=states[key] ?: if(key in pending(context)) State("Queued",active=true) else null
    private fun schedule(context:Context,key:String) {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(key.hashCode())!=null) return
        val job=JobInfo.Builder(key.hashCode(),ComponentName(context,DownloadService::class.java))
            .setExtras(PersistableBundle().apply { putString("key",key) })
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
        if(Build.VERSION.SDK_INT>=31) job.setExpedited(true) else job.setImportantWhileForeground(true)
        if(scheduler.schedule(job.build())==JobScheduler.RESULT_SUCCESS) return
        if(Build.VERSION.SDK_INT>=31) job.setExpedited(false)
        check(scheduler.schedule(job.build())==JobScheduler.RESULT_SUCCESS) { "Could not schedule download" }
    }
    @Synchronized fun resume(context:Context) {
        for(key in pending(context)) try { schedule(context,key) }
        catch(_:Exception) { states[key]=State("Waiting to resume",active=true) }
    }
    @Synchronized fun start(context:Context,key:String,complete:(String?)->Unit={}) {
        callbacks.getOrPut(key) { ArrayList() }.add(complete)
        if(key !in pending(context)) {
            states[key]=State("Queued",active=true)
            store(context).edit().putString("queue",(pending(context)+key).joinToString("\n")).commit()
        }
        try { schedule(context,key) }
        catch(error:Exception) { remove(context,key);finish(key,error.message ?: "Could not start download") }
    }
    @Synchronized internal fun finish(key:String,error:String?) {
        states[key]=State(error ?: "Ready")
        callbacks.remove(key)?.forEach { it(error) }
    }
    fun cancel(context:Context,key:String) {
        val stop=synchronized(this) {
            remove(context,key)
            stops[key].also { if(it!=null) states[key]=State("Cancelling…",active=true) }
        }
        stop?.invoke()
        context.getSystemService(JobScheduler::class.java).cancel(key.hashCode())
        if(stop==null) finish(key,"Cancelled")
    }
}

class DownloadService:JobService() {
    private val main=Handler(Looper.getMainLooper())
    private val transfers=HashMap<Int,Transfer>()
    override fun onStartJob(params:JobParameters):Boolean {
        val key=params.extras.getString("key") ?: return false
        if(key !in Downloads.pending(this)) return false
        val transfer=Transfer(applicationContext,params.network)
        val stop={ transfer.stop() }
        Downloads.stops[key]=stop
        transfers[params.jobId]=transfer
        Downloads.states[key]=Downloads.State("Connecting…",active=true)
        Downloads.worker.execute {
            var error:String?=null
            try { transfer.download(key) } catch(failure:Exception) { error="Download failed · "+(failure.message ?: "Please retry") }
            Downloads.stops.remove(key,stop)
            main.post {
                if(transfer.stopped) {
                    if(key !in Downloads.pending(this)) Downloads.finish(key,"Cancelled")
                    return@post
                }
                if(transfers[params.jobId]!==transfer) return@post
                transfers.remove(params.jobId)
                if(key in Downloads.pending(this)) { Downloads.remove(this,key);Downloads.finish(key,error) }
                jobFinished(params,false)
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        transfers.remove(params.jobId)?.stop()
        val key=params.extras.getString("key") ?: return false
        val pending=key in Downloads.pending(this)
        if(pending) Downloads.states[key]=Downloads.state(this,key)?.copy(message="Waiting to resume",active=true) ?: Downloads.State("Queued",active=true)
        return pending
    }
    override fun onDestroy() { transfers.values.forEach { it.stop() };transfers.clear();super.onDestroy() }

    private class Transfer(private val context:Context,private val network:Network?) {
        private data class Part(val title:String,val url:String,val file:File,val model:Boolean)
        @Volatile var stopped=false
            private set
        @Volatile private var connection:HttpURLConnection?=null
        fun stop() { stopped=true;connection?.disconnect() }
        private fun parts(key:String):List<Part> {
            val id=key.substringAfter(':')
            if(key.startsWith("model:")) {
                val model=(Models.text+Models.voices+Models.qwenEncoder).first { it.id==id }
                return (listOf(model)+model.parts).map { Part(it.title,it.url,Models.file(context,it),true) }
            }
            // Old installations may still have a queued switch download.
            return emptyList()
        }
        fun download(key:String) {
            val parts=parts(key)
            for((index,part) in parts.withIndex()) {
                if(part.file.length()>if(part.model) 1024 else 100) continue
                check(!stopped && key in Downloads.pending(context)) { "Cancelled" }
                part.file.parentFile!!.mkdirs()
                val partial=File(part.file.path+".part")
                val validator=File(partial.path+".validator")
                var offset=partial.length()
                if(offset>0 && !validator.exists()) offset=0
                val url=URL(part.url)
                val link=(network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                connection=link
                try {
                    link.connectTimeout=20000;link.readTimeout=30000
                    if(offset>0) { link.setRequestProperty("Range","bytes=$offset-");link.setRequestProperty("If-Range",validator.readText()) }
                    val code=link.responseCode
                    if(code==416 && link.getHeaderField("Content-Range")=="bytes */$offset" && offset>0) {
                        Models.validate(partial)
                        check(partial.renameTo(part.file)) { "Could not save download" };validator.delete();continue
                    }
                    check(code==200 || code==206) { "HTTP $code" }
                    if(code==200) offset=0
                    else check(link.getHeaderField("Content-Range")?.startsWith("bytes $offset-")==true) { "Invalid resumed response" }
                    val tag=link.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: link.getHeaderField("Last-Modified")
                    if(tag!=null) validator.writeText(tag) else validator.delete()
                    val total=if(link.contentLengthLong>0) offset+link.contentLengthLong else 0
                    var bytes=offset;var reported=0L
                    link.inputStream.use { input -> FileOutputStream(partial,offset>0).use { output ->
                        val buffer=ByteArray(262144)
                        while(true) {
                            check(!stopped && key in Downloads.pending(context)) { "Cancelled" }
                            val size=input.read(buffer)
                            if(size<0) break
                            output.write(buffer,0,size);bytes+=size
                            val now=System.currentTimeMillis()
                            if(now-reported>=250) {
                                val progress=if(total>0) "${bytes*100/total}%" else "${bytes/1048576} MB"
                                val phase=if(parts.size>1) " · ${index+1}/${parts.size}" else ""
                                val state=Downloads.State("$progress$phase",bytes,total,true,offset)
                                Downloads.states[key]=state;reported=now
                            }
                        }
                        check(total<=0 || bytes==total) { "Incomplete download; tap Download to resume" }
                    } }
                    check(!stopped && key in Downloads.pending(context)) { "Cancelled" }
                    try { Models.validate(partial) }
                    catch(error:Exception) { partial.delete();validator.delete();throw error }
                    check(partial.renameTo(part.file)) { "Could not save download" }
                    validator.delete()
                } finally { link.disconnect();connection=null }
            }
        }
    }
}
