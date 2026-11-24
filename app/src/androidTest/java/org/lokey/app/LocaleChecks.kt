package org.lokey.app

import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object LocaleChecks {
    private val format=Regex("%(\\d+)\\$(?:\\.\\d+)?([sdf])|%%")
    fun feel(test:Instrumentation,context:Context,locale:String?=null):JSONObject {
        val manager=context.getSystemService(LocaleManager::class.java)
        val original=manager.applicationLocales
        val prefs=Prefs(context);val setup=prefs.setupComplete
        val strength=prefs.vibration;val weight=prefs.hapticWeight;val release=prefs.returnHaptics
        val soundEnabled=prefs.soundEnabled;val volume=prefs.volume;val releaseVolume=prefs.returnVolume
        val labels=listOf("Swipe to type","Autocorrect","Adaptive letter sizes","One-tap speech","Learn from typing")
        val rows=JSONArray()
        prefs.setupComplete=true
        prefs.vibration=45;prefs.returnHaptics=true
        prefs.soundEnabled=false
        try {
            val locales=locale?.let(::listOf) ?: (ModelLanguages.all(context).map { it.code }+"zh-Hant")
            for(code in locales) {
                manager.applicationLocales=LocaleList.forLanguageTags(code)
                val activity=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("page","feel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try { test.runOnMainSync {
                    check(activity.getString(R.string.ui_language_tag)==code) { "Feel did not open in $code" }
                    fun views(view:View):Sequence<View> = sequence {
                        yield(view)
                        if(view is ViewGroup) for(i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
                    }
                    val controls=views(activity.window.decorView).filterIsInstance<Switch>().toList()
                    fun control(name:String)=controls.single { it.text.toString()==activity.tr(name) }
                    val features=labels.map(::control)
                    val card=features.first().parent as View
                    val sound=control("Recorded switch sounds")
                    val returned=control("Haptic return on release")
                    val master=control("Haptics")
                    val soundCard=sound.parent as View
                    val hapticsCard=returned.parent as View
                    fun label(group:View,name:String)=views(group).filterIsInstance<TextView>().single { it.text.toString()==activity.tr(name) }
                    val iconLabels=features+listOf(sound,returned,label(soundCard,"Sound volume"),label(soundCard,"Release sound volume"),master)
                    val width=(272*activity.resources.displayMetrics.density).toInt()
                    for(group in listOf(card,soundCard,hapticsCard)) {
                        group.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                        group.layout(0,0,width,group.measuredHeight)
                    }
                    for(control in iconLabels) {
                        check(control.compoundDrawablesRelative[0]!=null)
                        val textLayout=checkNotNull(control.layout)
                        check(textLayout.height<=control.height-control.totalPaddingTop-control.totalPaddingBottom &&
                            (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it)==0 && textLayout.getLineMax(it)<=textLayout.width+1f }) {
                            "Clipped Feel label in $code: ${control.text}"
                        }
                    }
                    if(code in listOf("en","ar")) {
                        val bitmap=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888);card.draw(Canvas(bitmap))
                        File(context.filesDir,"feel-$code.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                    }
                    if(code=="en") {
                        fun setProgress(bar:SeekBar,value:Int)=bar.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                            android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value.toFloat()) })
                        val soundBars=views(soundCard).filterIsInstance<SeekBar>().toList()
                        val saved=soundBars.map { it.progress }
                        check(soundBars.size==2 && !sound.isChecked && soundBars.all { !it.isEnabled && !setProgress(it,12) })
                        check(soundBars.map { it.progress }==saved && prefs.volume==volume && prefs.returnVolume==releaseVolume)
                        sound.performClick()
                        check(soundBars.all { it.isEnabled } && soundBars.map { it.progress }==saved)
                        sound.performClick()
                        check(!prefs.soundEnabled && soundBars.all { !it.isEnabled })
                        val slider=views(activity.window.decorView).filterIsInstance<SeekBar>().firstOrNull { it.contentDescription==activity.tr("Haptic strength") }
                        val selected=if(slider==null) 45 else 63
                        if(slider!=null) check(setProgress(slider,selected))
                        check(master.isChecked && returned.isEnabled && returned.isChecked)
                        master.performClick()
                        check(prefs.vibration==0 && !returned.isEnabled && !returned.isChecked && prefs.returnHaptics)
                        check(slider==null || !slider.isEnabled && !setProgress(slider,10))
                        master.performClick()
                        check(prefs.vibration==selected && returned.isEnabled && returned.isChecked)
                        check(slider==null || slider.isEnabled && slider.progress==selected)
                    }
                    rows.put(code)
                } } finally { test.runOnMainSync { activity.finish() } }
            }
        } finally {
            manager.applicationLocales=original
            prefs.setupComplete=setup;prefs.vibration=strength;prefs.hapticWeight=weight;prefs.returnHaptics=release
            prefs.soundEnabled=soundEnabled;prefs.volume=volume;prefs.returnVolume=releaseVolume
        }
        return JSONObject().put("locales",rows).put("screen_width_dp",320).put("release_haptics_toggle",true).put("disabled_sound_sliders",true)
    }
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
