package org.lokey.app

import android.content.Context

internal enum class SpacebarAction(val id:String,val title:String,val caption:String,val help:String) {
    Cursor("cursor","Cursor","Hold for cursor","Hold or swipe to move the cursor."),
    Prediction("prediction","Prediction","Tap for prediction","Tap to accept the first suggestion. Hold for a space."),
    Dictation("dictation","Dictation","Hold to dictate","Hold to dictate. Release to transcribe.")
}

class Prefs(context: Context) {
    val context:Context=context.applicationContext
    private val store = context.getSharedPreferences("lokey", Context.MODE_PRIVATE)
    internal val emojis=EmojiHistory(store)
    var designs:String
        get()=store.getString("designs","[]")!!
        set(value) { store.edit().putString("designs",value).apply() }
    var designDirty:Boolean
        get()=store.getBoolean("design_dirty",keyboardTheme in listOf("custom","image"))
        set(value) { store.edit().putBoolean("design_dirty",value).apply() }
    var activeDesign:String
        get()=store.getString("active_design","")!!
        set(value) { store.edit().putString("active_design",value).apply() }
    var customLayouts:String
        get()=store.getString("custom_layouts","[]")!!
        set(value) { store.edit().putString("custom_layouts",value).apply() }
    private var layoutCache=""
    private var savedLayouts=emptyList<CustomLayouts.Saved>()
    private var resolvedLayouts=emptyMap<String,KeyboardLayouts.Layout>()
    fun layouts():List<CustomLayouts.Saved> {
        val raw=customLayouts
        if(raw!=layoutCache) { savedLayouts=CustomLayouts.parse(raw);resolvedLayouts=savedLayouts.associate { it.id to it.layout() };layoutCache=raw }
        return savedLayouts
    }
    val currentLayout get():KeyboardLayouts.Layout { layouts();return resolvedLayouts[layout] ?: KeyboardLayouts.find(layout) }
    internal fun layoutChoices()=buildMap {
        put("auto",KeyboardLayouts.automatic())
        for(layout in KeyboardLayouts.all) put(layout.id,layout)
        layouts();putAll(resolvedLayouts)
    }
    internal val layoutSelection get()=store.getString("layout","auto") ?: "auto"
    var languageSwitchKey:Boolean
        get()=store.getBoolean("language_switch_key",false)
        set(value) { store.edit().putBoolean("language_switch_key",value).apply() }
    internal var quickLayouts:List<String>
        get()=savedQuickLayouts() ?: defaultQuickLayouts(layout)
        set(value) { store.edit().putString("quick_layouts",value.joinToString(",")).apply() }
    private fun savedQuickLayouts()=store.getString("quick_layouts",null)?.split(',')?.filter { it.isNotEmpty() }
    private fun defaultQuickLayouts(id:String)=if(id=="intl" || id=="qwerty") emptyList() else listOf("intl")

    internal fun cycleLayout():Boolean {
        val current=layoutSelection
        val choices=layoutChoices()
        // The picker keeps recent layouts first. Cycle in a stable order and skip aliases of the same layout.
        val ids=(listOf(current)+quickLayouts).filter { it in choices }
            .distinctBy { choices.getValue(it).id }.sortedBy { choices.getValue(it).id }
        if(ids.size<2) return false
        selectLayout(ids[(ids.indexOf(current)+1)%ids.size],rememberPrevious=true)
        return true
    }

    internal fun selectLayout(id:String,rememberPrevious:Boolean=false) {
        val recent=if(rememberPrevious) listOf(id,layoutSelection) else listOf(id)
        val saved=savedQuickLayouts() ?: defaultQuickLayouts(if(id=="auto") KeyboardLayouts.automatic().id else id)
        val edit=store.edit()
        if(id=="auto") edit.remove("layout") else edit.putString("layout",id)
        edit.putString("quick_layouts",(recent+saved).distinct().joinToString(",")).apply()
    }
    fun designSettings():org.json.JSONObject=org.json.JSONObject().apply {
        put("keyboard_theme",keyboardTheme);put("theme_background",themeBackground);put("theme_keys",themeKeys)
        put("theme_accent",themeAccent);put("theme_picture",themePicture)
        for((key,value) in store.all) if(key.startsWith("key_color.") || key.startsWith("row_color.")) put(key,value)
    }
    fun applyDesign(settings:org.json.JSONObject) {
        val edit=store.edit()
        store.all.keys.filter { it.startsWith("key_color.") || it.startsWith("row_color.") }.forEach { edit.remove(it) }
        for(key in settings.keys()) when(key) {
            "keyboard_theme","theme_picture" -> edit.putString(key,settings.getString(key))
            "theme_background","theme_keys","theme_accent" -> edit.putInt(key,settings.getInt(key))
            else -> if(key.startsWith("key_color.") || key.startsWith("row_color.")) edit.putInt(key,settings.getInt(key))
        }
        edit.putLong("theme_image_version",themeImageVersion+1).apply()
    }
    init {
        if(store.getString("model",null)=="onejev") store.edit().putString("model","auto").apply()
        if(!store.contains("setup_complete")) store.edit().putBoolean("setup_complete",store.contains("multilingual_v1")).apply()
        if (!store.getBoolean("multilingual_v1", false)) store.edit().putString("model", "auto").putBoolean("multilingual_v1", true).apply()
    }
    var switchId: String
        get() = store.getString("switch", "cherry_red")!!
        set(value) { store.edit().putString("switch", value).apply() }
    var soundEnabled: Boolean
        get() = store.getBoolean("sound_enabled", store.getInt("sound", 1) > 0)
        set(value) { store.edit().putBoolean("sound_enabled", value).apply() }
    var returnVolume: Int
        get() = store.getInt("return_volume", 45)
        set(value) { store.edit().putInt("return_volume", value).apply() }
    var returnHaptics: Boolean
        get() = store.getBoolean("return_haptics", false)
        set(value) { store.edit().putBoolean("return_haptics", value).apply() }
    var volume: Int
        get() = store.getInt("volume", 35)
        set(value) { store.edit().putInt("volume", value).apply() }
    var vibration: Int
        get() = store.getInt("vibration", 30)
        set(value) {
            val strength=if(value>0) value else vibration
            store.edit().putInt("vibration",value).apply {
                if(strength>0) {
                    putInt("haptic_weight",vibrationWeight(strength))
                    putInt("haptic_strength",strength)
                }
            }.apply()
        }
    val hapticStrength get()=vibration.takeIf { it>0 } ?: store.getInt("haptic_strength",30)
    var hapticWeight:Int
        get() = vibration.let { if(it>0) vibrationWeight(it) else store.getInt("haptic_weight",45) }
        set(value) { store.edit().putInt("haptic_weight",value).apply() }
    private fun vibrationWeight(strength:Int)=when {
        strength<=20 -> 15
        strength>=66 -> 85
        else -> 45
    }
    var appLight: Boolean
        get() = store.getBoolean("app_light", false)
        set(value) { store.edit().putBoolean("app_light", value).apply() }
    var keyboardTheme: String
        get() = store.getString("keyboard_theme", "dark")!!
        set(value) { store.edit().putString("keyboard_theme", value).apply() }
    var themeBackground: Int
        get() = store.getInt("theme_background", android.graphics.Color.rgb(23,24,25))
        set(value) { store.edit().putInt("theme_background", value).apply() }
    var themeKeys: Int
        get() = store.getInt("theme_keys", android.graphics.Color.rgb(48,49,51))
        set(value) { store.edit().putInt("theme_keys", value).apply() }
    var themeAccent: Int
        get() = store.getInt("theme_accent", android.graphics.Color.rgb(240,92,94))
        set(value) { store.edit().putInt("theme_accent", value).apply() }
    var themePicture: String
        get() = store.getString("theme_picture", "user")!!
        set(value) { store.edit().putString("theme_picture", value).apply() }
    var userImageChosen: Boolean
        get() = store.getBoolean("user_image_chosen", false)
        set(value) { store.edit().putBoolean("user_image_chosen", value).apply() }
    var themeImageVersion: Long
        get() = store.getLong("theme_image_version", 0L)
        set(value) { store.edit().putLong("theme_image_version", value).apply() }
    fun keyColor(code:String):Int? = if (store.contains("key_color.$code")) store.getInt("key_color.$code",0) else null
    fun keyColor(code:String,color:Int?) {
        val edit=store.edit()
        if(color==null) edit.remove("key_color.$code") else edit.putInt("key_color.$code",color)
        edit.apply()
    }
    fun rowColor(row:Int):Int? = if(store.contains("row_color.$row")) store.getInt("row_color.$row",0) else null
    fun rowColor(row:Int,color:Int?) { val edit=store.edit();if(color==null) edit.remove("row_color.$row") else edit.putInt("row_color.$row",color);edit.apply() }
    fun clearRowColors() { val edit=store.edit();store.all.keys.filter { it.startsWith("row_color.") }.forEach { edit.remove(it) };edit.apply() }
    fun clearKeyColors() { val edit=store.edit();store.all.keys.filter { it.startsWith("key_color.") }.forEach { edit.remove(it) };edit.apply() }
    fun useTheme(theme:String) {
        require(theme in KeyboardTheme.presets)
        val colors=KeyboardTheme.colors(this,theme)
        val edit=store.edit().putString("keyboard_theme",theme).putInt("theme_background",colors.background)
            .putInt("theme_keys",colors.keys).putInt("theme_accent",colors.accent).putBoolean("design_dirty",false).putString("active_design","")
        store.all.keys.filter { it.startsWith("key_color.") || it.startsWith("row_color.") }.forEach { edit.remove(it) }
        edit.apply()
    }
    fun uniformKeys(color:Int) {
        val colors=KeyboardTheme.colors(this)
        val edit=store.edit().putString("keyboard_theme","custom").putInt("theme_background",colors.background)
            .putInt("theme_keys",color).putInt("theme_accent",colors.accent).putBoolean("design_dirty",true).putString("active_design","")
        store.all.keys.filter { it.startsWith("key_color.") || it.startsWith("row_color.") }.forEach { edit.remove(it) }
        edit.apply()
    }
    var flow: Boolean
        get() = store.getBoolean("flow", false)
        set(value) { store.edit().putBoolean("flow", value).apply() }
    var autocorrect: Boolean
        get() = store.getBoolean("autocorrect", false)
        set(value) { store.edit().putBoolean("autocorrect", value).apply() }
    var adaptive: Boolean
        get() = store.getBoolean("adaptive", false)
        set(value) { store.edit().putBoolean("adaptive", value).apply() }
    var oneTapSpeech:Boolean
        get()=store.getBoolean("one_tap_speech",false)
        set(value) { store.edit().putBoolean("one_tap_speech",value).apply() }
    val speechX:Float get()=store.getFloat("speech_x",.9f).takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: .9f
    val speechY:Float get()=store.getFloat("speech_y",.35f).takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: .35f
    fun speechPosition(x:Float,y:Float) { store.edit().putFloat("speech_x",x.coerceIn(0f,1f)).putFloat("speech_y",y.coerceIn(0f,1f)).apply() }
    var learning:Boolean
        get()=store.getBoolean("learning",false)
        set(value) {
            val edit=store.edit().putBoolean("learning",value)
            if(!value) emojis.clearCounts(edit)
            edit.apply()
            if(!value) UsageLearning.shared(context).erase()
        }
    internal fun learningErased() { store.edit().putLong("learning_revision",System.nanoTime()).apply() }
    var height: Int
        get() = store.getInt("height", 100)
        set(value) { store.edit().putInt("height", value).apply() }
    var numberRow:Boolean
        get()=store.getBoolean("number_row",true)
        set(value) { store.edit().putBoolean("number_row",value).apply() }
    var splitLandscape:Boolean
        get()=store.getBoolean("split_landscape",false)
        set(value) { store.edit().putBoolean("split_landscape",value).apply() }
    internal var spacebarAction:SpacebarAction
        get() {
            val id=store.getString("spacebar_action",null)
            return SpacebarAction.entries.firstOrNull { it.id==id } ?: SpacebarAction.Cursor
        }
        set(value) { store.edit().putString("spacebar_action",value.id).apply() }
    var model: String
        get() = store.getString("model", "auto")!!
        set(value) { store.edit().putString("model", value).apply() }
    var translationTarget:String
        get()=supportedLanguage(store.getString("translation_target","English")!!,"English")
        set(value) { store.edit().putString("translation_target",value).apply() }
    var layout:String
        get()=layoutSelection.takeUnless { it=="auto" } ?: KeyboardLayouts.automatic().id
        set(value) { selectLayout(value) }
    val automaticLayout get()=layoutSelection=="auto"
    var setupComplete:Boolean
        get()=store.getBoolean("setup_complete",false)
        set(value) { store.edit().putBoolean("setup_complete",value).apply() }
    var setupLanguage:String
        get()=supportedLanguage(store.getString("setup_language","")!!,"")
        set(value) { store.edit().putString("setup_language",value).apply() }
    private fun supportedLanguage(value:String,fallback:String):String {
        val name=when(value) {
            "Simplified Chinese","Traditional Chinese" -> "Chinese"
            "Standard Arabic" -> "Arabic"
            "Tagalog" -> "Filipino"
            else -> value
        }
        return name.takeIf { it in LanguageLayouts.all } ?: fallback
    }
    var speechModel:String
        get()=store.getString("speech_model","parakeet")!!
        set(value) { store.edit().putString("speech_model",value).apply() }
    fun modelPair(text:String,speech:String) { store.edit().putString("model",text).putString("speech_model",speech).apply() }
}
