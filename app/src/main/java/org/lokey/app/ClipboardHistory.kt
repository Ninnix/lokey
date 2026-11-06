package org.lokey.app

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ClipboardHistory(context:Context,name:String="clipboard") {
    data class Clip(val id:Long,val text:String,val pinned:Boolean,val time:Long)
    private val store=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private val clips=ArrayList<Clip>()
    init {
        try {
            val data=JSONArray(store.getString("clips","[]"))
            for(i in 0 until data.length()) data.getJSONObject(i).let {
                clips.add(Clip(it.getLong("id"),it.getString("text"),it.optBoolean("pinned"),it.getLong("time")))
            }
        } catch(_:Exception) { clips.clear() }
        if(prune()) save()
    }
    private fun prune():Boolean {
        val count=clips.size
        val cutoff=System.currentTimeMillis()-60*60*1000
        clips.removeAll { !it.pinned && it.time<cutoff }
        while(clips.count { !it.pinned }>40) clips.removeAt(clips.indexOfLast { !it.pinned })
        return count!=clips.size
    }
    fun entries():List<Clip> { if(prune()) save();return clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time }) }
    fun add(text:String) {
        if(text.isBlank() || text.length>8000) return
        val now=System.currentTimeMillis()
        val old=clips.firstOrNull { it.text==text }
        if(old!=null) clips.remove(old)
        val id=old?.id ?: maxOf(now,(clips.maxOfOrNull { it.id } ?: 0)+1)
        clips.add(0,Clip(id,text,old?.pinned ?: false,now));save()
    }
    fun pin(id:Long):Boolean {
        val index=clips.indexOfFirst { it.id==id };if(index<0) return false
        val clip=clips[index]
        if(!clip.pinned && clips.count { it.pinned }>=100) return false
        clips[index]=clip.copy(pinned=!clip.pinned);save();return true
    }
    fun delete(id:Long) { clips.removeAll { it.id==id };save() }
    fun clearRecent() { clips.removeAll { !it.pinned };save() }
    private fun save() {
        prune()
        val data=JSONArray()
        clips.forEach { data.put(JSONObject().put("id",it.id).put("text",it.text).put("pinned",it.pinned).put("time",it.time)) }
        store.edit().putString("clips",data.toString()).apply()
    }
    companion object {
        fun text(clip:ClipData,allowSensitive:Boolean=false):String? =
            if(!allowSensitive && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true || clip.itemCount==0) null else clip.getItemAt(0).text?.toString()
        fun current(context:Context,allowSensitive:Boolean=false):String? {
            val clipboard=context.getSystemService(ClipboardManager::class.java)
            return try {
                val clip=clipboard.primaryClip ?: return null
                text(clip,allowSensitive)
            } catch(_:SecurityException) { null }
        }
    }
}
