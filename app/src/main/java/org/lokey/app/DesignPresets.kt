package org.lokey.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object DesignPresets {
    data class Design(val id:String,val name:String,val settings:JSONObject,val image:String) {
        fun json()=JSONObject().put("id",id).put("name",name).put("settings",settings).put("image",image)
        fun imageFile(prefs:Prefs)=File(prefs.context.filesDir,"designs/$image")
    }
    fun all(prefs:Prefs):List<Design> {
        val array=JSONArray(prefs.designs)
        return (0 until array.length()).map { index ->
            val row=array.getJSONObject(index)
            Design(row.getString("id"),row.getString("name"),row.getJSONObject("settings"),row.optString("image"))
        }
    }
    fun save(prefs:Prefs,name:String):Design {
        require(name.isNotBlank()) { "Give your design a name" }
        val id=UUID.randomUUID().toString();val settings=prefs.designSettings()
        val image=if(prefs.keyboardTheme=="image" && prefs.themePicture=="user") "$id.png" else ""
        val design=Design(id,name.trim().take(80),settings,image)
        if(image.isNotEmpty()) {
            val file=design.imageFile(prefs);file.parentFile!!.mkdirs()
            KeyboardTheme.imageFile(prefs.context).copyTo(file)
        }
        prefs.designs=JSONArray((all(prefs)+design).map { it.json() }).toString()
        prefs.activeDesign=id;prefs.designDirty=false
        return design
    }
    fun apply(prefs:Prefs,id:String):Boolean {
        val design=all(prefs).firstOrNull { it.id==id } ?: return false
        if(design.image.isNotEmpty()) {
            val file=KeyboardTheme.imageFile(prefs.context);val part=File(file.path+".part")
            design.imageFile(prefs).copyTo(part,true)
            check(part.renameTo(file)) { "Could not restore image" };prefs.userImageChosen=true
        }
        prefs.applyDesign(design.settings)
        prefs.activeDesign=id;prefs.designDirty=false
        return true
    }
    fun delete(prefs:Prefs,id:String):Boolean {
        val designs=all(prefs);val design=designs.firstOrNull { it.id==id } ?: return false
        prefs.designs=JSONArray(designs.filter { it.id!=id }.map { it.json() }).toString()
        if(prefs.activeDesign==id) { prefs.activeDesign="";prefs.designDirty=true }
        if(design.image.isNotEmpty()) design.imageFile(prefs).delete()
        return true
    }
}
