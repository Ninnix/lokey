package org.lokey.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object CustomLayouts {
    val actions=linkedMapOf("" to "Type text","shift" to "Caps","delete" to "Delete","space" to "Space / cursor","enter" to "Enter / microphone","symbols" to "Symbols / tools")
    val emojis=listOf("😊","❤️","👍","😂","😅","😭")
    data class Key(val id:String=UUID.randomUUID().toString(),val primary:String="",val secondary:List<String> = emptyList(),val action:String="",val width:Float=1f) {
        val label get()=if(action.isEmpty()) primary else actions[action].orEmpty().substringBefore(" /")
        fun json()=JSONObject().put("id",id).put("primary",primary).put("secondary",JSONArray(secondary)).put("action",action).put("width",width)
    }
    data class Saved(val id:String,val name:String,val base:String,val rows:List<List<Key>>) {
        fun json()=JSONObject().put("id",id).put("name",name).put("base",base).put("rows",JSONArray(rows.map { row -> JSONArray(row.map { it.json() }) }))
        fun layout():KeyboardLayouts.Layout {
            val source=KeyboardLayouts.find(base)
            return source.copy(id=id,title=name,variant="Custom",rows=rows.drop(1).dropLast(1).map { row -> row.filter { it.action.isEmpty() }.map { it.primary } },keyRows=rows)
        }
    }
    fun decode(row:JSONObject):Saved {
        val rows=row.getJSONArray("rows")
        return Saved(row.getString("id"),row.getString("name"),row.getString("base"),(0 until rows.length()).map { index ->
            val keys=rows.getJSONArray(index)
            (0 until keys.length()).map { i ->
                val key=keys.getJSONObject(i);val secondary=key.getJSONArray("secondary")
                Key(key.getString("id"),key.getString("primary"),(0 until secondary.length()).map { secondary.getString(it) },key.getString("action"),key.getDouble("width").toFloat())
            }
        })
    }
    fun parse(raw:String):List<Saved> {
        val array=JSONArray(raw)
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }
    fun create(prefs:Prefs,blank:Boolean=false):Saved {
        val source=prefs.currentLayout
        fun key(value:String):Key {
            val alternates=source.variants(value).ifEmpty {
                if(source.latin && !source.phonetic && source.composer!="dead") value.singleOrNull()?.let { LatinAccents.options(it,prefs.setupLanguage) }.orEmpty() else emptyList()
            }
            return Key(primary=value,secondary=alternates)
        }
        fun action(value:String,width:Float=1.35f)=Key(action=value,width=width)
        val bottom=mutableListOf(action("symbols",1.5f),Key(primary=source.punctuation(","),secondary=emojis,width=.9f),action("space",4.9f),Key(primary=source.punctuation("."),secondary=listOf("!",",","?").map(source::punctuation),width=.9f),action("enter",1.5f))
        if(source.behaviorId=="persian") bottom.add(3,Key(primary="\u200c",width=.9f))
        val rows=if(blank) listOf(emptyList(),listOf(action("shift"),action("delete")),listOf(action("symbols"),action("space",4.9f),action("enter",1.5f)))
        else source.keyRows.ifEmpty {
            listOf("1234567890".mapIndexed { i,c -> key(c.toString()).let { if(it.secondary.isNotEmpty()) it else it.copy(secondary=listOf("!@#$%^&*()"[i].toString())) } })+
                source.rows.mapIndexed { i,row -> if(i==source.rows.lastIndex) listOf(action("shift"))+row.map(::key)+action("delete") else row.map(::key) }+
                listOf(bottom)
        }
        return Saved("custom_"+UUID.randomUUID(),"",if(blank) "qwerty" else source.behaviorId,rows.map { row -> row.map { it.copy(id=UUID.randomUUID().toString()) } })
    }
    fun save(prefs:Prefs,saved:Saved) {
        require(saved.id.startsWith("custom_") && saved.name.isNotBlank()) { "Give your layout a name" }
        require(saved.rows.size in 1..8 && saved.rows.all { it.size in 1..24 }) { "Use 1–8 rows, with 1–24 keys per row" }
        val keys=saved.rows.flatten()
        require(keys.map { it.id }.distinct().size==keys.size)
        require(keys.all { it.action in actions && (it.action.isNotEmpty() || it.primary.isNotEmpty()) && it.width in .5f..8f && it.secondary.size<=32 }) { "Each key needs text or an action" }
        prefs.customLayouts=JSONArray((prefs.layouts().filter { it.id!=saved.id }+saved).map { it.json() }).toString()
    }
    fun delete(prefs:Prefs,id:String):Boolean {
        if(prefs.layouts().none { it.id==id }) return false
        if(prefs.layout==id) prefs.layout="auto"
        prefs.customLayouts=JSONArray(prefs.layouts().filter { it.id!=id }.map { it.json() }).toString()
        return true
    }
}
