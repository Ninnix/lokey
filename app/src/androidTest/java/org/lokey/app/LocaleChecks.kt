package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object LocaleChecks {
    private val format=Regex("%(\\d+)\\$(?:\\.\\d+)?([sdf])|%%")
    fun run(test:Instrumentation,context:Context):JSONObject {
        val codes=ModelLanguages.all(context).map { it.code }+"zh-Hant"
        check(codes.size==40 && codes.distinct().size==40)
        val rows=JSONArray();val prefs=Prefs(context);val original=if(prefs.automaticLayout) "auto" else prefs.layout
        fun locale(code:String):Context {
            val config=Configuration(context.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(code));config.setLayoutDirection(Locale.forLanguageTag(code))
            return ContextThemeWrapper(context.createConfigurationContext(config),R.style.AppTheme)
        }
        val english=locale("en")
        for((source,id) in Strings.ids) check(english.getString(id)==source) { "English changed: $source -> ${english.getString(id)}" }
        try {
            prefs.layout="qwerty"
            for(code in codes) {
                val localized=locale(code)
                check(localized.getString(R.string.ui_language_tag)==code) { "Resource fallback for $code" }
                var unchanged=0
                for((source,id) in Strings.ids) {
                    val value=localized.getString(id)
                    check(value.isNotBlank()) { "Empty $code: $source" }
                    check(format.findAll(value).map { it.value }.toList().sorted()==format.findAll(source).map { it.value }.toList().sorted()) { "Format changed in $code: $source" }
                    if(value==source) unchanged++
                    val substitutions=format.findAll(source).filter { it.groupValues[1].isNotEmpty() }.toList()
                    if(substitutions.isNotEmpty()) {
                        val args=Array<Any>(substitutions.maxOf { it.groupValues[1].toInt() }) { "value" }
                        for(item in substitutions) args[item.groupValues[1].toInt()-1]=when(item.groupValues[2]) { "d" -> 7;"f" -> 1.5;else -> "value" }
                        check(localized.getString(id,*args).isNotBlank())
                    }
                }
                if(code!="en") check(localized.tr("Welcome to Lokey")!="Welcome to Lokey") { "English welcome in $code" }
                test.runOnMainSync {
                    val view=KeyboardView(localized,prefs,object:KeyboardView.Listener {
                        override fun text(text:String) { }
                        override fun delete() { }
                        override fun enter() { }
                        override fun cursor(delta:Int) { }
                        override fun voice(start:Boolean)=false
                        override fun cancelVoice() { }
                        override fun nextKeyboard() { }
                        override fun candidate(word:String) { }
                        override fun feedback(key:String,down:Boolean) { }
                    })
                    view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight);view.editor(false,localized.tr("Done"),false)
                    check(view.layoutDirection==View.LAYOUT_DIRECTION_LTR)
                    check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(37)!!.contentDescription==localized.tr("Backspace, hold to keep deleting"))
                    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));bitmap.recycle()
                    val history=ClipboardHistory(localized,"localization-check")
                    history.clearRecent();history.add("Models")
                    val panel=ClipboardPanel(localized,prefs,history,{},{})
                    fun texts(view:View):List<String> = (if(view is TextView) listOf(view.text.toString()) else emptyList())+
                        if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
                    check("Models" in texts(panel)) { "Clipboard text translated in $code" }
                    history.clearRecent()
                    val custom=CustomLayouts.create(prefs).copy(name="Light")
                    check(localized.layoutName(custom.layout())=="Light") { "Custom layout name translated" }
                }
                rows.put(JSONObject().put("locale",code).put("rtl",localized.resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL)
                    .put("strings",Strings.ids.size).put("unchanged",unchanged).put("welcome",localized.tr("Welcome to Lokey")))
                File(context.filesDir,"localization-details.json").writeText(rows.toString(2))
            }
            check(locale("sw").getString(R.string.ui_language_tag)=="en")
        } finally { prefs.layout=original }
        return JSONObject().put("languages",39).put("locales",rows).put("english_fallback",true).put("formatted_strings_valid",true)
            .put("keyboard_geometry_preserved",true).put("user_text_preserved",true)
    }
}
