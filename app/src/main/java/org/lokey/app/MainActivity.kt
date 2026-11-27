package org.lokey.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.text.NumberFormat

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private lateinit var content: LinearLayout
    private var page = "home"
    private var speedMode = false
    private var draft = ""
    private var feedback: Feedback? = null
    private var themeSound:ThemeSound?=null
    private val updates=ArrayList<()->Unit>()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val poll=object:Runnable { override fun run() { refresh();main.postDelayed(this,350) } }
    private val ink get()=if(prefs.appLight) Color.rgb(28,29,30) else Color.rgb(245,240,231)
    private val muted get()=if(prefs.appLight) Color.rgb(110,111,108) else Color.rgb(160,161,158)
    private val accent get()=if(prefs.appLight) Color.rgb(255,90,0) else Color.rgb(240,92,94)
    private val bg get()=if(prefs.appLight) Color.rgb(248,248,245) else Color.rgb(23,24,25)
    private val surface get()=if(prefs.appLight) Color.rgb(237,237,233) else Color.rgb(35,36,37)
    private val control get()=if(prefs.appLight) Color.rgb(223,223,219) else Color.rgb(53,53,54)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        prefs=Prefs(this);applyAppTheme()
        super.onCreate(state); feedback = Feedback(this, prefs);themeSound=ThemeSound(this)
        if(!prefs.setupComplete) { startActivity(Intent(this,SetupActivity::class.java));finish();return }
        if (intent.getBooleanExtra("microphone", false)) permission()
        val destination = intent.getStringExtra("page").takeIf { it in listOf("home", "feel", "models", "lab", "theme", "layout") } ?: "home"
        show(destination)
    }
    private fun shape(color: Int, radius: Int = 20, border: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (border != 0) setStroke(dp(1), border)
    }
    private fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun gap(height: Int) { content.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun heading(title: String, note: String) {
        content.addView(text(title, 30f, bold = true)); gap(8); content.addView(text(note, 14f, muted)); gap(25)
    }
    private fun card(): LinearLayout = vertical().apply {
        background = shape(surface); setPadding(dp(20), dp(20), dp(20), dp(20))
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun button(label: String, primary: Boolean = false, icon:FeelIcon.Kind?=null, action: () -> Unit): TextView = text(label, 14f, if (primary) bg else ink, true).apply {
        gravity = Gravity.CENTER; background = shape(if (primary) accent else control, 13)
        if(icon!=null) { setFeelIcon(icon,if(primary) bg else accent);gravity=Gravity.START or Gravity.CENTER_VERTICAL }
        setPadding(dp(18), dp(15), dp(18), dp(15)); isClickable = true; setOnClickListener { action() }
        isSoundEffectsEnabled = false; isHapticFeedbackEnabled = false
    }
    private fun show(destination: String, scrollY:Int=0) {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        page = destination
        updates.clear()
        body = vertical().apply { setBackgroundColor(bg) }
        setContentView(body)
        AppWindow.frame(window,body,prefs.appLight)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(14), dp(24), dp(14)) }
        if(destination in listOf("theme","layout","statistics")) {
            val parent=if(destination=="statistics") "models" else "feel"
            val back=BackButton(this,ink) { show(parent) }
            if(destination=="statistics") back.contentDescription=tr("Back")
            header.addView(back,LinearLayout.LayoutParams(dp(40),dp(40)))
            val title=when(destination) { "layout" -> "Keyboard layout";"statistics" -> "Statistics for nerds";else -> "Appearance" }
            header.addView(text(tr(title),22f,bold=true),LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(8) })
        } else {
            val logo=ImageView(this).apply { setImageResource(R.drawable.lokey_logo);clipToOutline=true;background=shape(bg,12) }
            header.addView(logo,LinearLayout.LayoutParams(dp(40),dp(40)))
            header.addView(text("Lokey",24f,bold=true),LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(10) })
        }
        header.addView(ThemeToggle(this,prefs.appLight) { themeSound?.click(!prefs.appLight);prefs.appLight=!prefs.appLight;applyAppTheme();show(page) },LinearLayout.LayoutParams(dp(44),dp(40)))
        body.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = vertical().apply { setPadding(dp(24), dp(16), dp(24), dp(20)) }
        scroll.addView(content); body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (destination) { "home" -> home(); "feel" -> feel(); "models" -> models(); "statistics" -> statistics(); "lab" -> lab(); "theme" -> { heading(tr("Make it yours."),tr("Customize your keyboard’s theme, colors, and keycaps."));content.addView(ThemeEditor(this,prefs)) }; "layout" -> {
                heading(tr("Make it familiar."),tr("Follow your device language, or choose the keys you know."))
                card().apply {
                    addView(setting(tr("Show language switch key"),prefs.languageSwitchKey,FeelIcon.Kind.Globe) { prefs.languageSwitchKey=it })
                    addView(text(tr("Tap the globe to cycle through layout shortcuts. Hold it to open the layout picker."),13f,muted))
                }
                content.addView(LayoutPicker(this,prefs))
            } }
        val tabs = LinearLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(16)); background = shape(bg, 0) }
        listOf("home" to tr("Keyboard"), "feel" to tr("Feel"), "models" to tr("Models"), "lab" to tr("Try it")).forEach { (id, label) ->
            val selected=id==page || id=="feel" && page in listOf("theme","layout") || id=="models" && page=="statistics"
            tabs.addView(text(label,12f,if(selected) accent else muted,selected).apply {
                gravity=Gravity.CENTER;maxLines=2;setPadding(dp(2),dp(6),dp(2),dp(6))
                setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                setOnClickListener { show(id) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        body.addView(tabs)
        if(scrollY>0) scroll.post { scroll.scrollTo(0,scrollY) }
        refresh()
    }
    private fun refresh() { updates.forEach { it() } }
    private fun applyAppTheme() { setTheme(if(prefs.appLight) R.style.AppTheme_Light else R.style.AppTheme) }
    private fun home() {
        heading(tr("Type. Speak.\nOne flow."),tr("Intelligent typing. Private by design."))
        val preview = card()
        preview.setPadding(dp(9), dp(6), dp(9), dp(10))
        val keyboard = KeyboardView(this, prefs, object : KeyboardView.Listener {
            override fun text(text: String) { feedback?.tap() }
            override fun delete() { }
            override fun enter() { }
            override fun cursor(delta: Int) { }
            override fun voice(start: Boolean): Boolean { show("lab"); return false }
            override fun cancelVoice() { }
            override fun nextKeyboard() { }
            override fun candidate(word: String) { show("lab") }
            override fun feedback(key: String, down: Boolean) { feedback?.tap(key, down) }
        })
        preview.addView(keyboard, LinearLayout.LayoutParams(-1, dp(300)))
        keyboard.prediction(listOf("you", "your", "you're"), FloatArray(26), false)
        gap(12)
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        val enableButton=button(if (enabled) tr("1   Lokey is enabled  ✓") else tr("1   Enable Lokey"), true) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }.apply { contentDescription=tr("Enable Lokey") }
        content.addView(enableButton,LinearLayout.LayoutParams(-1,-2))
        updates.add {
            val enabled=getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName==packageName }
            val label=if(enabled) tr("1   Lokey is enabled  ✓") else tr("1   Enable Lokey")
            if(enableButton.text.toString()!=label) enableButton.text=label
        }
        gap(10)
        val choose=button(tr("2   Choose Lokey")) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }.apply { contentDescription=tr("Choose Lokey") }
        content.addView(choose,LinearLayout.LayoutParams(-1,-2))
        updates.add {
            val id=Settings.Secure.getString(contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)
            val active=id?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }==packageName
            val label=if(active) tr("2   Lokey is selected  ✓") else tr("2   Choose Lokey")
            if(choose.text.toString()!=label) choose.text=label
            if(choose.tag!=active) {
                choose.tag=active;choose.isSelected=active;choose.background=shape(if(active) accent else control,13)
                choose.setTextColor(if(active) bg else ink)
            }
        }
    }
    private fun feel() {
        val layout=card()
        layout.addView(text(tr("Keyboard layout"),20f,bold=true))
        val selectedLayout=prefs.currentLayout
        layout.addView(text((if(prefs.automaticLayout) tr("Automatic · ") else "")+layoutName(selectedLayout),13f,muted).apply { setPadding(0,dp(5),0,dp(10)) })
        val layoutPreview=LayoutPreview(this,prefs) { selectedLayout }
        layout.addView(layoutPreview,LinearLayout.LayoutParams(-1,dp(76)))
        layout.addView(text(tr("Choose layout  →"),14f,accent,true).apply { setPadding(0,dp(10),0,0) })
        layout.isClickable=true;layout.isFocusable=true;layout.contentDescription=tr("Keyboard layout settings")
        layout.setOnClickListener { show("layout") }
        val appearance=card()
        appearance.addView(text(tr("Appearance"),20f,bold=true))
        appearance.addView(text(tr("Keyboard themes, images, and colors."),13f,muted))
        val swatches=LinearLayout(this)
        listOf(Triple(tr("Dark"),Color.rgb(23,24,25),Color.rgb(245,240,231)),Triple(tr("Light"),Color.rgb(248,248,245),Color.rgb(28,29,30)),Triple(tr("Great Wave"),Color.rgb(24,58,91),Color.rgb(240,231,202))).forEach { (name,fill,color) ->
            swatches.addView(text(name,12f,color,true).apply { gravity=Gravity.CENTER;background=shape(fill,10);setPadding(0,dp(12),0,dp(12)) },LinearLayout.LayoutParams(0,dp(48),1f).apply { setMargins(0,dp(14),dp(6),dp(14)) })
        }
        appearance.addView(swatches);appearance.addView(text(tr("Customize appearance  →"),14f,accent,true))
        appearance.isClickable=true;appearance.isFocusable=true;appearance.contentDescription=tr("Appearance and keyboard themes")
        appearance.setOnClickListener { show("theme") }
        section(tr("Sound & feedback"))
        content.addView(text(tr("Tap a switch to select and hear it."),13f,muted));gap(14)
        val switchRow=LinearLayout(this).apply { tag="switch-strip" }
        val switchScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false;clipToPadding=false;addView(switchRow)
            contentDescription=tr("Mechanical switches")
        }
        content.addView(switchScroll,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        for(profile in Switches.profiles) {
            val box=vertical().apply {
                gravity=Gravity.CENTER;setPadding(dp(10),dp(12),dp(10),dp(12))
                isClickable=true;isFocusable=true;setOnClickListener { selectSwitch(profile) }
            }
            val icon=SwitchIcon(this,profile.id,prefs.appLight)
            box.addView(icon,LinearLayout.LayoutParams(dp(72),dp(72)))
            box.addView(text(profile.name,14f,bold=true).apply { gravity=Gravity.CENTER;maxLines=2 })
            box.addView(text(tr(profile.kind)+"\n"+tr(profile.feel),11f,muted).apply { gravity=Gravity.CENTER;maxLines=3 },LinearLayout.LayoutParams(-1,dp(52)))
            var wasSelected:Boolean?=null
            updates.add {
                val selected=prefs.switchId==profile.id
                if(wasSelected!=selected) {
                    box.background=shape(if(selected) KeyboardTheme.blend(surface,accent,.13f) else surface,border=if(selected) accent else 0)
                    box.isSelected=selected;wasSelected=selected
                }
                box.contentDescription=profile.name+", "+tr(profile.kind)+if(selected) tr(", selected") else ""
            }
            switchRow.addView(box,LinearLayout.LayoutParams(dp(148),dp(188)).apply { marginEnd=dp(10) })
        }
        switchScroll.post {
            val index=Switches.profiles.indexOfFirst { it.id==prefs.switchId }.coerceAtLeast(0)
            switchScroll.scrollTo(index*dp(158),0)
        }
        val tryBox = card()
        tryBox.addView(text(tr("Try a few taps"), 15f, bold = true))
        tryBox.addView(text(tr("Touch the keys to compare press and return."), 12f, muted))
        val pads = LinearLayout(this)
        listOf("A" to "a", "S" to "s", tr("Space") to "space", "↵" to "enter").forEach { (label, key) ->
            pads.addView(button(label) { }.apply {
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> { feedback?.tap(key, true); alpha = .65f; true }
                        android.view.MotionEvent.ACTION_UP -> { feedback?.tap(key, false); alpha = 1f; performClick(); true }
                        android.view.MotionEvent.ACTION_CANCEL -> { alpha = 1f; true }
                        else -> true
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(56), if (key == "space") 1.7f else 1f).apply { setMargins(dp(2), dp(12), dp(2), 0) })
        }
        tryBox.addView(pads)
        val sound=card()
        val soundToggle=setting(tr("Recorded switch sounds"),prefs.soundEnabled,FeelIcon.Kind.Sound) { enabled ->
            if(prefs.soundEnabled!=enabled) { prefs.soundEnabled=enabled;refresh() }
        }
        sound.addView(soundToggle)
        val volume=slider(sound,tr("Sound volume"),prefs.volume,0,100,FeelIcon.Kind.Volume) { prefs.volume=it;feedback?.preview() }
        val releaseVolume=slider(sound,tr("Release sound volume"),prefs.returnVolume,0,100,FeelIcon.Kind.Return) { prefs.returnVolume=it;feedback?.preview() }
        val soundSliders=listOf(volume,releaseVolume)
        updates.add {
            val enabled=prefs.soundEnabled
            if(soundToggle.isChecked!=enabled) soundToggle.isChecked=enabled
            for (bar in soundSliders) {
                bar.isEnabled=enabled
                bar.alpha=if(enabled) 1f else .5f
            }
        }
        val soundStatus=text("",12f,muted);sound.addView(soundStatus)
        val audio=getSystemService(android.media.AudioManager::class.java)
        updates.add {
            val status=if(audio.ringerMode!=android.media.AudioManager.RINGER_MODE_NORMAL) tr("Muted by Silent / Do Not Disturb") else tr("Silent mode and Do Not Disturb silence recordings.")
            if(soundStatus.text.toString()!=status) soundStatus.text=status
        }
        val haptics=card()
        val systemTaps=feedback?.usesSystemTaps==true
        var strengthSlider:SeekBar?=null
        val returnToggle=setting(tr("Haptic return on release"),prefs.vibration>0 && prefs.returnHaptics,FeelIcon.Kind.Return) { enabled ->
            if(prefs.vibration>0 && enabled!=prefs.returnHaptics) { prefs.returnHaptics=enabled;feedback?.preview() }
        }
        fun syncHaptics() {
            val enabled=prefs.vibration>0
            returnToggle.isEnabled=enabled
            returnToggle.isChecked=enabled && prefs.returnHaptics
            strengthSlider?.let { it.isEnabled=enabled;it.alpha=if(enabled) 1f else .5f }
        }
        haptics.addView(setting(tr("Haptics"),prefs.vibration>0,FeelIcon.Kind.Haptics) { enabled ->
            prefs.vibration=if(!enabled) 0 else if(systemTaps) prefs.hapticWeight else prefs.hapticStrength
            syncHaptics()
            if(enabled) feedback?.preview()
        })
        if(systemTaps) {
            val values=listOf(15,45,85)
            val weight=prefs.hapticWeight
            haptics.addView(text(tr("Haptic weight"),15f,bold=true).apply { setPadding(0,0,0,dp(8)) })
            haptics.addView(segments(listOf(tr("Light"),tr("Balanced"),tr("Firm")),values.indexOf(weight)) { index ->
                if(prefs.vibration>0) { prefs.vibration=values[index];feedback?.preview() }
                else prefs.hapticWeight=values[index]
            })
        } else strengthSlider=slider(haptics,tr("Haptic strength"),prefs.hapticStrength,1,100) {
            prefs.vibration=it;feedback?.preview()
        }
        syncHaptics()
        haptics.addView(returnToggle)
        content.addView(button(tr("Input & design credits"),icon=FeelIcon.Kind.Learn) {
            startActivity(Intent(this,CreditsActivity::class.java).putExtra("section","input"))
        },LinearLayout.LayoutParams(-1,-2))
        section(tr("Keyboard size"))
        card().apply {
            slider(this,tr("Keyboard height"),prefs.height,85,120) { prefs.height=it }
            addView(text(tr("Hold the top edge of the keyboard and drag to resize while typing."),13f,muted))
            addView(setting(tr("Show number row"),prefs.numberRow,FeelIcon.Kind.NumberRow) { prefs.numberRow=it;layoutPreview.invalidate() })
            addView(setting(tr("Split keyboard in landscape"),prefs.splitLandscape,FeelIcon.Kind.SplitKeyboard) { prefs.splitLandscape=it })
        }
        section(tr("Typing features"))
        card().apply {
            addView(setting(tr("Swipe to type"),prefs.flow && prefs.currentLayout.flow,FeelIcon.Kind.Swipe) { prefs.flow=it }.apply { isEnabled=prefs.currentLayout.flow })
            addView(text(if(prefs.currentLayout.flow) tr("Slide across letters to write a word.") else tr("Swipe typing is available on Latin layouts without composition."),13f,muted))
            addView(setting(tr("Autocorrect"),prefs.autocorrect,FeelIcon.Kind.Correct) { prefs.autocorrect=it })
            addView(text(tr("Correct words on space or punctuation. Backspace restores the original."),13f,muted))
            addView(text(tr("Spacebar action"),15f,bold=true).apply { setPadding(0,dp(16),0,dp(8)) })
            val actions=SpacebarAction.entries
            val spacebarHelp=text(tr(prefs.spacebarAction.help),13f,muted).apply { setPadding(0,dp(8),0,dp(8)) }
            addView(segments(actions.map { tr(it.title) },prefs.spacebarAction.ordinal) { index ->
                prefs.spacebarAction=actions[index];spacebarHelp.text=tr(actions[index].help)
            })
            addView(spacebarHelp)
            addView(setting(tr("Adaptive letter sizes"),prefs.adaptive,FeelIcon.Kind.Adapt) { prefs.adaptive=it })
            addView(text(tr("Likely keys grow; letter positions stay fixed."),13f,muted))
            addView(setting(tr("One-tap speech"),prefs.oneTapSpeech,FeelIcon.Kind.Speech) {
                prefs.oneTapSpeech=it
                if(it && !Settings.canDrawOverlays(this@MainActivity)) {
                    val permission=Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:$packageName"))
                    if(permission.resolveActivity(packageManager)!=null) startActivityForResult(permission,13)
                }
            })
            addView(text(tr("Tap the floating microphone to record hands-free. Pause, resume or stop when ready."),13f,muted))
            addView(setting(tr("Learn from typing"),prefs.learning,FeelIcon.Kind.Learn) { prefs.learning=it })
            addView(text(tr("Learn frequent words and corrections on this device. Encrypted, never uploaded. Private fields are excluded. Turning this off erases learning."),13f,muted))
            addView(button(tr("Erase learned data"),icon=FeelIcon.Kind.Trash) {
                UsageLearning.shared(this@MainActivity).erase()
                Toast.makeText(this@MainActivity,tr("Learned data erased"),Toast.LENGTH_SHORT).show()
            },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16) })
        }
    }
    private fun section(title:String) {
        gap(20);content.addView(text(title,20f,bold=true));gap(14)
    }
    private fun setting(title:String,checked:Boolean,icon:FeelIcon.Kind?=null,changed:(Boolean)->Unit)=Switch(this).apply {
        text=title;textSize=15f;isChecked=checked;minHeight=dp(52);switchPadding=dp(10)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(muted,ink)))
        if(icon!=null) {
            setFeelIcon(icon,accent)
            setPaddingRelative(0,dp(7),0,dp(7))
        }
        val states=arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf())
        thumbTintList=android.content.res.ColorStateList(states,intArrayOf(accent,muted))
        trackTintList=android.content.res.ColorStateList(states,intArrayOf(KeyboardTheme.blend(surface,accent,.4f),control))
        setOnCheckedChangeListener { _,value -> changed(value) }
    }
    private fun selectSwitch(profile:Switches.Profile) {
        prefs.switchId=profile.id;prefs.soundEnabled=true
        if(page=="feel") feedback?.preview(profile.id)
        refresh()
    }
    private fun segments(labels:List<String>,selected:Int,changed:(Int)->Unit):LinearLayout {
        val row=LinearLayout(this).apply { background=shape(control,22);setPadding(dp(3),dp(3),dp(3),dp(3)) }
        val ripple=android.content.res.ColorStateList.valueOf(KeyboardTheme.blend(control,accent,.3f))
        fun select(index:Int) {
            for(i in labels.indices) (row.getChildAt(i) as TextView).apply {
                isSelected=i==index
                setTextColor(if(isSelected) ink else muted)
                background=RippleDrawable(ripple,shape(if(isSelected) bg else Color.TRANSPARENT,18,if(isSelected) accent else 0),shape(Color.WHITE,18))
            }
        }
        for((i,label) in labels.withIndex()) row.addView(text(label,13f,bold=true).apply {
            gravity=Gravity.CENTER;minHeight=dp(44);setPadding(dp(5),dp(6),dp(5),dp(6));isFocusable=true
            setOnClickListener { select(i);changed(i) }
        },LinearLayout.LayoutParams(0,-2,1f))
        select(selected)
        return row
    }
    private fun slider(row:LinearLayout, title: String, initial: Int, min: Int, max: Int, icon:FeelIcon.Kind?=null, changed: (Int) -> Unit):SeekBar {
        val number=java.text.NumberFormat.getPercentInstance(resources.configuration.locales[0])
        val value=text(number.format(initial/100.0),13f,muted,true)
        row.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            val heading=text(title,15f,bold=true).apply { if(icon!=null) setFeelIcon(icon,accent) }
            addView(heading,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(12) })
            addView(value)
        })
        val bar=SeekBar(this).apply {
            contentDescription=title
            this.min=min;this.max=max
            progressDrawable=LayerDrawable(arrayOf(shape(control,2),ClipDrawable(shape(accent,2),Gravity.START,ClipDrawable.HORIZONTAL))).apply {
                setId(0,android.R.id.background);setId(1,android.R.id.progress)
                for(i in 0..1) { setLayerHeight(i,dp(4));setLayerGravity(i,Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL) }
            }
            thumb=shape(ink,8).apply { setSize(dp(24),dp(14)) }
            progressTintList=null;thumbTintList=null;splitTrack=false
            setPaddingRelative(dp(12),0,dp(12),0);progress=initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) {
                    value.text=number.format(progress/100.0)
                    if(user && bar.isEnabled) changed(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { }
                override fun onStopTrackingTouch(bar: SeekBar) { }
            })
        }
        row.addView(bar,LinearLayout.LayoutParams(-1,dp(44)))
        return bar
    }
    private fun models() {
        heading(tr("Small models.\nClose to you."), tr("Prediction and dictation, running locally."))
        content.addView(text(tr("Model downloads connect to Hugging Face, which receives your IP address. Typing and audio stay on your device."),12f,muted).apply { setPadding(0,0,0,dp(12)) })
        content.addView(button(tr("Supported languages"),icon=FeelIcon.Kind.Globe) { startActivity(Intent(this,LanguageActivity::class.java)) },LinearLayout.LayoutParams(-1,-2))
        gap(10)
        content.addView(button(tr("Statistics for nerds"),icon=FeelIcon.Kind.Statistics) { show("statistics") },LinearLayout.LayoutParams(-1,-2))
        gap(16)
        val automatic = card()
        val autoTitle=text(tr("Automatic"),20f,bold=true)
        val language=button(tr(ModelLanguages.selected(this,prefs).name)) {
            startActivityForResult(Intent(this,LanguageActivity::class.java).putExtra("select",true),12)
        }.apply { textSize=12f;setTextColor(accent);contentDescription=tr("Automatic model language");setPadding(dp(10),dp(10),dp(10),dp(10)) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(autoTitle,LinearLayout.LayoutParams(0,-2,1f));header.addView(language)
        automatic.addView(header,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        automatic.addView(text(tr("Chooses text and speech models for your language."),13f,muted).apply { setPadding(0,0,0,dp(14));setLineSpacing(dp(3).toFloat(),1.1f) })
        val autoButton=button(tr("Use automatic"),true) { prefs.model="auto";refresh() };autoButton.contentDescription=tr("Select automatic model")
        automatic.addView(autoButton)
        updates.add {
            val selected=prefs.model=="auto"
            val name=tr("Automatic")+if(selected) "  ●" else ""
            if(autoTitle.text.toString()!=name) autoTitle.text=name
            val label=if(selected) tr("Selected") else tr("Use automatic")
            if(autoButton.text.toString()!=label) autoButton.text=label
            autoButton.isSelected=selected
            modelActionStyle(autoButton,selected)
            val nameLanguage=ModelLanguages.selected(this,prefs).name+"  ›"
            if(language.text.toString()!=nameLanguage) language.text=nameLanguage
        }
        section(tr("Prediction"))
        content.addView(text(tr("The selected text model handles prediction, autocorrection and translation."),12f,muted).apply { setPadding(0,0,0,dp(12)) })
        Models.text.forEach { modelCard(it) }
        section(tr("Speak"))
        Models.voices.forEach { modelCard(it,true) }
        gap(10)
        content.addView(button(tr("Allow microphone"),icon=FeelIcon.Kind.Speech) { permission() }, LinearLayout.LayoutParams(-1, -2))
        gap(10)
        content.addView(button(tr("Models & inference credits"),icon=FeelIcon.Kind.Learn) { startActivity(Intent(this,CreditsActivity::class.java)) },LinearLayout.LayoutParams(-1,-2))
        gap(10)
        content.addView(button(tr("Privacy policy"),icon=FeelIcon.Kind.Shield) { startActivity(Intent(this,PrivacyActivity::class.java)) },LinearLayout.LayoutParams(-1,-2))
    }
    private fun statistics() {
        val stats=TypingStats.shared(this).snapshot()
        val numbers=NumberFormat.getIntegerInstance(uiLocale())
        val decimals=NumberFormat.getNumberInstance(uiLocale()).apply { minimumFractionDigits=1;maximumFractionDigits=1 }
        card().apply {
            val metrics=listOf(
                Triple("Suggestions accepted",stats.suggestions,FeelIcon.Kind.Suggestion),
                Triple("Characters saved",stats.characters,FeelIcon.Kind.Characters),
                Triple("Autocorrections applied",stats.corrections,FeelIcon.Kind.Correct),
                Triple("Dictations inserted",stats.dictations,FeelIcon.Kind.Speech)
            )
            for((label,value,icon) in metrics) {
                val row=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(44) }
                row.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(FeelIcon(icon,dp(20),accent));scaleType=ImageView.ScaleType.CENTER_INSIDE
                    background=shape(KeyboardTheme.blend(surface,accent,.14f),10)
                    setPadding(dp(7),dp(7),dp(7),dp(7));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(34),dp(34)).apply { marginEnd=dp(12) })
                row.addView(text(tr(label),14f),LinearLayout.LayoutParams(0,-2,1f))
                row.addView(text(numbers.format(value),22f,bold=true).apply { setPaddingRelative(dp(8),0,0,0) })
                addView(row,LinearLayout.LayoutParams(-1,-2))
            }
        }
        fun timings(title:String,available:List<Models.Model>,empty:String,note:String) {
            section(tr(title))
            card().apply {
                val models=available.filter { it.id in stats.models }
                if(models.isEmpty()) addView(text(tr(empty),14f,muted))
                else {
                    fun row(label:String,cells:List<View>) {
                        val line=LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(7),0,dp(7)) }
                        line.addView(text(label,13f,muted),LinearLayout.LayoutParams(0,-2,1f))
                        for(cell in cells) line.addView(cell,LinearLayout.LayoutParams(0,-2,1.1f))
                        addView(line,LinearLayout.LayoutParams(-1,-2))
                    }
                    val timings=models.map { stats.models.getValue(it.id) }
                    row("",models.map { model ->
                        LinearLayout(this@MainActivity).apply {
                            gravity=Gravity.CENTER_VERTICAL or Gravity.END;setPaddingRelative(dp(4),0,0,0)
                            addView(ModelCredits.logo(this@MainActivity,prefs,model.id).apply {
                                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            },LinearLayout.LayoutParams(dp(16),dp(16)).apply { marginEnd=dp(4) })
                            addView(text(model.title,13f,bold=true).apply { gravity=Gravity.END })
                        }
                    })
                    val rows=listOf("Median" to timings.map { tr("%1\$s ms",decimals.format(it.medianMs)) },
                        "95th percentile" to timings.map { tr("%1\$s ms",decimals.format(it.p95Ms)) },"Samples" to timings.map { numbers.format(it.count) })
                    for((label,values) in rows) row(tr(label),values.map { value ->
                        text(value,13f).apply { gravity=Gravity.END;setPaddingRelative(dp(4),0,0,0) }
                    })
                    addView(text(tr(note),12f,muted).apply { setPadding(0,dp(10),0,0) })
                }
            }
        }
        timings("Autocomplete speed",Models.text,"Model timings appear after you type with a downloaded text model.",
            "Recent generation times vary with what you type. Loading and cached results are excluded.")
        timings("Dictation speed",Models.voices,"Speech timings appear after you dictate with a downloaded speech model.",
            "Processing times are per second of recorded audio. Model loading is excluded.")
        content.addView(text(tr("These statistics stay on this device."),12f,muted))
    }
    private fun modelActionStyle(action:TextView,selected:Boolean,cancel:Boolean=false) {
        val state=if(cancel) 2 else if(selected) 1 else 0
        if(action.tag==state) return
        action.tag=state;action.isSelected=state==1
        action.background=shape(when(state) { 1 -> KeyboardTheme.blend(surface,accent,.12f);2 -> control;else -> accent },13,if(state==1) accent else 0)
        action.setTextColor(when(state) { 1 -> accent;2 -> ink;else -> bg })
    }
    private fun modelCard(model: Models.Model,voice:Boolean=false) {
        val box = card()
        val title=text(model.title,19f,bold=true)
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(ModelCredits.logo(this,prefs,model.id),LinearLayout.LayoutParams(dp(40),dp(40)).apply { marginEnd=dp(12) })
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f));box.addView(header)
        box.addView(text(tr(model.detail),12f,muted).apply { setPadding(0,dp(6),0,0) })
        val progress=DownloadProgress(this,accent,muted);box.addView(progress,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        val action=button(tr("Download"),true) {
            if(Models.downloading(this,model)) Downloads.cancel(this,"model:${model.id}")
            else if(Models.ready(this,model)) {
                val selected=if(voice) Models.selectedVoice(this,prefs) else Models.selectedText(this,prefs)
                if(selected.id!=model.id) Models.select(this,prefs,model.id,voice)
            }
            else Models.download(applicationContext,model) { }
            refresh()
        }.apply { contentDescription=tr("Model action: %1\$s",model.id) }
        box.addView(action,LinearLayout.LayoutParams(-1,-2))
        updates.add {
            val ready=Models.ready(this,model);val state=Models.downloadState(this,model)
            val selected=(if(voice) Models.selectedVoice(this,prefs) else Models.selectedText(this,prefs)).id==model.id
            val name=model.title+if(selected) "  ●" else ""
            if(title.text.toString()!=name) title.text=name
            val label=if(state?.active==true) tr("Cancel download") else if(!ready) tr("Download") else if(selected) { if(prefs.model=="auto") tr("Selected automatically") else tr("Selected") } else tr("Use model")
            if(action.text.toString()!=label) action.text=label
            modelActionStyle(action,ready && selected,state?.active==true)
            progress.update(state?.takeIf { it.active || !ready },if(ready) tr("Ready on this device") else tr("Not downloaded"))
        }
    }
    private fun lab() {
        val toggle=setting(tr("Speed test"),speedMode,FeelIcon.Kind.Speed) { speedMode=it;show("lab") }.apply {
            typeface=Typeface.create("sans-serif",Typeface.BOLD)
            minHeight=dp(48);setPaddingRelative(dp(14),dp(8),dp(12),dp(8))
            val fill=if(speedMode) KeyboardTheme.blend(surface,accent,.12f) else surface
            val ripple=android.content.res.ColorStateList.valueOf(KeyboardTheme.blend(fill,accent,.24f))
            background=RippleDrawable(ripple,shape(fill,16,if(speedMode) accent else control),shape(Color.WHITE,16))
        }
        val editor:EditText
        if(speedMode) {
            val game=SpeedTestView(this,toggle)
            content.addView(game,LinearLayout.LayoutParams(-1,-2));editor=game.editor
        } else {
            content.addView(text(tr("Find your flow."),24f,bold=true));gap(6)
            content.addView(text(tr("Type or speak. Stay in your flow."),12f,muted));gap(12)
            editor=EditText(this).apply {
                id=View.generateViewId();hint=tr("Type something. Or say it.")
                setTextColor(ink);setHintTextColor(muted);textSize=20f
                gravity=Gravity.TOP;minLines=4;setPadding(dp(20),dp(20),dp(20),dp(20))
                background=shape(surface);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            editor.setText(draft);editor.setSelection(editor.length())
            editor.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) { }
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { }
                override fun afterTextChanged(value:android.text.Editable?) { draft=value.toString() }
            })
            content.addView(editor,LinearLayout.LayoutParams(-1,dp(140)))
            content.addView(toggle,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        }
        editor.requestFocus()
        editor.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT) },200)
    }
    private fun permission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        else Toast.makeText(this, tr("Microphone is allowed"), Toast.LENGTH_SHORT).show()
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if(request==13) { show("feel");return }
        if(request==12 && result==RESULT_OK) {
            data?.getStringExtra("language")?.takeIf { it in LanguageLayouts.all }?.let { prefs.setupLanguage=it;refresh() }
            return
        }
        if(request==11 && result==RESULT_OK) {
            val uri=data?.data ?: return
            Toast.makeText(this,tr("Preparing keycap image…"),Toast.LENGTH_SHORT).show()
            KeyboardTheme.io.execute {
                try {
                    val bitmap=KeyboardTheme.importImage(applicationContext,uri)
                    KeyboardTheme.applyPalette(prefs,bitmap);prefs.themePicture="user";prefs.userImageChosen=true
                    prefs.themeImageVersion=prefs.themeImageVersion+1;prefs.keyboardTheme="image";prefs.designDirty=true;prefs.activeDesign=""
                    runOnUiThread { show("theme") }
                } catch(error:Exception) { runOnUiThread { Toast.makeText(this,tr(error.message ?: "Could not open image"),Toast.LENGTH_LONG).show() } }
            }
            return
        }
    }
    override fun onDestroy() { main.removeCallbacks(poll);updates.clear();themeSound?.close();feedback?.close(); super.onDestroy() }
    override fun onResume() { super.onResume();if(page=="layout" || page=="statistics") show(page);main.removeCallbacks(poll);main.post(poll) }
    override fun onPause() { main.removeCallbacks(poll);super.onPause() }
}
