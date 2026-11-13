package org.lokey.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class UsageLearning internal constructor(context:Context,private val alias:String="lokey-usage-v1",
    private val unlocked:()->Boolean={ context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked!=true }) {
    private val prefs=Prefs(context)
    private val file=AtomicFile(File(context.noBackupFilesDir,"usage-learning.bin"))
    private val worker=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"lokey-learning").apply { isDaemon=true } }
    private val model=UsageModel()
    private val lock=Any()
    private val storageLock=Any()
    private var ready=false
    private var loading=false
    private var scheduled=false
    private var epoch=0
    init { prepare() }
    private fun prepare() { synchronized(lock) {
        if(ready || loading) return
        loading=true
        worker.execute { load() }
    } }
    private fun load() {
        var deferred=false
        val rows=synchronized(storageLock) {
            if(synchronized(lock) { epoch==0 && prefs.learning }) {
                if(!unlocked()) { deferred=true;emptyList() }
                else try { read() } catch(_:Exception) {
                    if(!unlocked()) deferred=true else destroyFiles()
                    emptyList()
                }
            }
            else { destroyFiles();emptyList() }
        }
        synchronized(lock) {
            if(!deferred) {
                if(epoch==0 && prefs.learning) model.restore(rows)
                ready=true
            }
            loading=false
        }
    }
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUnlockedDeviceRequired(true).build())
        }.generateKey()
    }
    private fun read():List<UsageModel.Entry> {
        if(!file.baseFile.exists()) return emptyList()
        check(file.baseFile.length()<=262144)
        val data=file.readFully()
        check(data.size>=29 && data[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,data.copyOfRange(1,13)))
        cipher.updateAAD(alias.toByteArray())
        val plain=cipher.doFinal(data,13,data.size-13)
        try {
            val rows=JSONArray(String(plain,Charsets.UTF_8));check(rows.length()<=1024)
            return (0 until rows.length()).map { i -> rows.getJSONArray(i).let {
                UsageModel.Entry(it.getString(0),it.getString(1),it.getString(2),it.getInt(3),it.getInt(4))
            } }
        } finally { plain.fill(0);data.fill(0) }
    }
    private fun save(ticket:Int) {
        val snapshot=synchronized(lock) {
            if(ticket!=epoch || !scheduled) return
            scheduled=false
            if(!prefs.learning || !ready) return
            model.snapshot()
        }
        val rows=JSONArray()
        for(row in snapshot) rows.put(JSONArray().put(row.scope).put(row.typed).put(row.word).put(row.count).put(row.seen))
        val plain=rows.toString().toByteArray(Charsets.UTF_8)
        try {
            synchronized(storageLock) {
                if(synchronized(lock) { ticket!=epoch || !prefs.learning }) return
                var output:java.io.FileOutputStream?=null
                try {
                    val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(alias.toByteArray())
                    val encrypted=cipher.doFinal(plain)
                    output=file.startWrite();output.write(byteArrayOf(1));output.write(cipher.iv);output.write(encrypted)
                    file.finishWrite(output)
                } catch(_:Exception) { output?.let { file.failWrite(it) } }
            }
        }
        finally { plain.fill(0) }
    }
    private fun changed() {
        if(scheduled) return
        scheduled=true
        val ticket=epoch
        worker.schedule({ save(ticket) },2,TimeUnit.SECONDS)
    }
    internal fun awaitIdle() { worker.submit { }.get(10,TimeUnit.SECONDS) }
    internal fun flush() { worker.submit { save(synchronized(lock) { epoch }) }.get(10,TimeUnit.SECONDS) }
    internal fun close() { worker.shutdownNow() }
    fun learn(scope:String,word:String,typed:String="") { synchronized(lock) {
        if(prefs.learning && !ready) prepare()
        if(prefs.learning && ready && model.learn(scope,word,typed)) changed()
    } }
    fun reject(scope:String,typed:String) { synchronized(lock) {
        if(prefs.learning && !ready) prepare()
        if(prefs.learning && ready) { model.reject(scope,typed);changed() }
    } }
    fun correction(scope:String,typed:String):String?=synchronized(lock) {
        if(prefs.learning && !ready) prepare()
        if(prefs.learning && ready) model.correction(scope,typed) else null
    }
    fun candidates(scope:String,prefix:String):List<Pair<String,Double>> = synchronized(lock) {
        if(prefs.learning && !ready) prepare()
        if(prefs.learning && ready) model.candidates(scope,prefix).ifEmpty { model.nearby(scope,prefix) } else emptyList()
    }
    private fun promote(result:Prediction.Result,word:String,weight:Double):Boolean {
        if(!result.neural || result.appendWords.isNotEmpty() || result.correction!=null) return false
        val score=result.wordScores[Languages.lower(word)] ?: return false
        val best=result.words.firstOrNull()?.let { result.wordScores[Languages.lower(it)] } ?: return false
        val boost=(1.5*weight).coerceIn(0.0,3.0)
        return score.isFinite() && best.isFinite() && boost>0 && score+boost>=best
    }
    fun personalize(result:Prediction.Result,scope:String):Prediction.Result=synchronized(lock) {
        if(prefs.learning && !ready) prepare()
        if(!prefs.learning || !ready) return result
        val prefix=Languages.prefix(result.before)
        if(prefix.isEmpty()) return result
        val append=result.appendWords.isNotEmpty()
        val learned=model.candidates(scope,prefix)
        val nearby=if(learned.isEmpty() && !append) model.nearby(scope,prefix) else emptyList()
        val fixed=model.correction(scope,prefix)?.let { Languages.casing(it,prefix,result.language) }
        if(learned.isEmpty() && nearby.isEmpty() && fixed==null) return result
        val personal=(learned.firstOrNull() ?: nearby.firstOrNull())?.first?.let { Languages.casing(it,prefix,result.language) }
        // Keep the candidate set; only bounded, context-supported frequency can change neural order.
        var words=when {
            fixed!=null -> (listOf(fixed)+result.words).distinctBy { Languages.lower(it) }.take(3)
            personal==null -> result.words
            result.neural || append -> if(result.words.any { it.equals(personal,true) })
                result.words.map { if(it.equals(personal,true)) personal else it } else result.words.take(2)+personal
            else -> (listOf(personal)+result.words).distinctBy { Languages.lower(it) }.take(3)
        }
        if(fixed==null && personal!=null && learned.isNotEmpty() && promote(result,personal,learned.first().second))
            words=(listOf(personal)+words.filterNot { it.equals(personal,true) }).take(3)
        val prior=AdaptiveKeys.probabilities(prefix,learned)
        val hasPrior=prior.any { it>0 }
        val hasBase=result.letters.any { it>0 }
        val letters=if(!hasPrior) result.letters else if(!hasBase) prior else FloatArray(26) { .75f*result.letters[it]+.25f*prior[it] }
        result.copy(words=words,letters=letters,correction=if(prefs.autocorrect) fixed ?: result.correction else null,
            appendWords=result.appendWords.intersect(words.toSet())-listOfNotNull(fixed ?: personal).toSet())
    }
    fun erase() {
        synchronized(lock) { epoch++;scheduled=false;model.clear() }
        // Keystore and disk work cannot block lookups, but deletion must follow any older write.
        synchronized(storageLock) { destroyFiles() }
        prefs.learningErased()
    }
    private fun destroyFiles() {
        file.delete()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry(alias) } } catch(_:Exception) { }
    }
    companion object {
        @Volatile private var instance:UsageLearning?=null
        fun shared(context:Context):UsageLearning=instance ?: synchronized(this) {
            instance ?: UsageLearning(context.applicationContext).also { instance=it }
        }
        fun scope(layout:String,language:String)="$layout:$language"
        fun allowed(type:Int,options:Int):Boolean = AdaptiveKeys.allowed(type,options) &&
            type and android.text.InputType.TYPE_MASK_VARIATION !in listOf(android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS)
    }
}
