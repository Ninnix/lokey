package org.lokey.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

class SetupActivity:AppScreen() {
    private var step=0
    private var textChoice="lfm"
    private var voiceChoice="parakeet"
    private var manualModels=false
    private var textState:DownloadProgress?=null
    private var voiceState:DownloadProgress?=null
    private var next:TextView?=null
    private val main=Handler(Looper.getMainLooper())
    private val modelChoices=ArrayList<()->Unit>()
    private var layoutPreview:PresetPreview?=null
    private var layoutTitle:TextView?=null
    private val poll=object:Runnable { override fun run() { syncModels();updateDownloads();main.postDelayed(this,500) } }
    private val language get()=ModelLanguages.selected(this,prefs)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(prefs.setupLanguage.isEmpty()) prefs.setupLanguage=ModelLanguages.forLocale(this,KeyboardLayouts.deviceLocale()).name
        val pair=Models.recommended(language)
        step=state?.getInt("step") ?: 0;textChoice=state?.getString("text") ?: pair.first;voiceChoice=state?.getString("voice") ?: pair.second.orEmpty()
        manualModels=state?.getBoolean("manual_models") ?: false
        if(state==null && intent.getBooleanExtra("downloads",false)) {
            val chosen=getSharedPreferences("setup_downloads",MODE_PRIVATE)
            step=1;textChoice=chosen.getString("text",textChoice)!!;voiceChoice=chosen.getString("voice",voiceChoice)!!
            manualModels=true
        }
        if(android.os.Build.VERSION.SDK_INT>=33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        render()
    }
    private fun chooseLanguage() { startActivityForResult(Intent(this,LanguageActivity::class.java).putExtra("select",true),21) }
    private fun syncModels() {
        if(!manualModels) {
            val pair=Models.recommended(language);textChoice=pair.first;voiceChoice=pair.second.orEmpty()
        }
        modelChoices.forEach { it() }
    }
    private fun back() { if(step>0) { step--;render() } else finish() }
    private fun box(parent:LinearLayout)=vertical().apply { background=shape();setPadding(dp(16),dp(14),dp(16),dp(14));parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) }) }
    override fun render() {
        textState=null;voiceState=null
        layoutPreview=null;layoutTitle=null
        modelChoices.clear()
        val root=frame(tr("Welcome to Lokey")) { back() }
        root.addView(text("${step+1} / 2   ·   "+if(step==0) tr("Your keyboard") else tr("Your models"),12f,accent,true).apply { setPadding(dp(24),dp(8),dp(24),dp(8)) })
        val content=vertical().apply { setPadding(dp(24),dp(12),dp(24),dp(12)) }
        val scroll=ScrollView(this).apply { addView(content) };root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        if(step==0) {
            val languageButton=button(tr("Language · %1\$s",tr(language.name))) { chooseLanguage() }
            content.addView(languageButton,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
            val suggested=language.layout?.let { tr(KeyboardLayouts.find(it).title) } ?: tr("Choose a keyboard below")
            val suggestion=text(tr("Suggested · %1\$s",tr(suggested)),13f,accent,true).apply { setPadding(0,0,0,dp(12)) }
            content.addView(suggestion)
            if(language.mapping?.status!="available") content.addView(text(tr(language.keyboardDescription)+"\n"+tr(language.mapping?.note.orEmpty()),12f,muted))
            content.addView(LayoutPicker(this,prefs,automaticChoice=false) {
                val id=prefs.layout
                val code=when(id) {
                    "spanish" -> "es";"portuguese" -> "pt";"turkish" -> "tr";"vietnamese" -> "vi";"azerty" -> "fr";"qwertz" -> "de"
                    "cyrillic" -> "ru";"ukrainian" -> "uk";"arabic" -> "ar";"persian" -> "fa";"greek" -> "el";"hindi","hindi_phonetic","bolnagri" -> "hi"
                    "korean" -> "ko";"japanese" -> "ja";"pinyin" -> "zh";"jyutping" -> "yue"
                    "cangjie","sucheng" -> if(language.base in listOf("zh","yue")) language.base else "yue"
                    "thai" -> "th";else -> ""
                }
                if(prefs.automaticLayout || code.isNotEmpty() && language.layout!=id) {
                    prefs.setupLanguage=ModelLanguages.forLocale(this,if(prefs.automaticLayout) KeyboardLayouts.deviceLocale() else java.util.Locale.forLanguageTag(code)).name
                    manualModels=false;syncModels();languageButton.text=tr("Language · %1\$s",tr(language.name))
                    suggestion.text=tr("Suggested · ")+(language.layout?.let { tr(KeyboardLayouts.find(it).title) } ?: tr("Choose a keyboard below"))
                }
                layoutTitle?.text=layoutName(prefs.currentLayout)
                layoutPreview?.refresh()
            })
            box(content).apply {
                addView(text(tr("Two ways to use QWERTY"),16f,bold=true))
                addView(text(tr("Intl · hold L for ' or \"; hold Q for ` or ~. Dead keys: ' + e → é, ` + e → è, ' + Space → '.\nAccent · shared accent menus, with extra letters for your language."),12f,muted))
            }
        } else {
            content.addView(text(tr("Choose your models."),26f,bold=true))
            content.addView(text(tr("One for text, one for voice. Suggested for %1\$s.",tr(language.name)),13f,muted).apply { setPadding(0,dp(8),0,dp(16)) })
            content.addView(button(tr("Language · %1\$s",tr(language.name))) { chooseLanguage() },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
            modelChoice(content,false);modelChoice(content,true)
            content.addView(text(tr("Model downloads connect to Hugging Face, which receives your IP address. Typing and audio stay on your device."),12f,muted).apply { setPadding(0,dp(8),0,dp(8)) })
            content.addView(text(tr("Suggested for your language and device. Downloads stay on your device. Qwen ASR includes its audio encoder."),12f,muted).apply { setPadding(0,dp(8),0,dp(8)) })
            content.addView(button(tr("See all supported languages")) { startActivity(Intent(this,LanguageActivity::class.java)) })
        }
        content.addView(button(tr("Privacy policy")) { startActivity(Intent(this,PrivacyActivity::class.java)) })
        if(step==0) {
            val preview=vertical().apply { setPadding(dp(24),dp(8),dp(24),0) }
            layoutTitle=text(layoutName(prefs.currentLayout),12f,muted).apply { setPadding(0,0,0,dp(6));contentDescription=tr("Setup preview layout") }
            preview.addView(layoutTitle)
            layoutPreview=PresetPreview(this,prefs,prefs.keyboardTheme,(resources.configuration.screenHeightDp*.34f).toInt()) { }.apply { contentDescription=tr("Selected keyboard preview") }
            preview.addView(layoutPreview);root.addView(preview)
        }
        val bottom=vertical().apply { setPadding(dp(24),dp(10),dp(24),dp(16)) }
        next=button(if(step==0) tr("Choose models") else tr("Download models"),true) {
            if(step==0) { syncModels();step=1;render() } else downloadOrFinish()
        }.apply { contentDescription=tr("Setup continue") }
        bottom.addView(next,LinearLayout.LayoutParams(-1,-2))
        if(step==1 && chosen().any { !Models.ready(this,it) }) bottom.addView(button(tr("Use keyboard without downloading")) { finishSetup() }.apply { contentDescription=tr("Skip model downloads") })
        root.addView(bottom);updateDownloads()
    }
    private fun modelChoice(parent:LinearLayout,voice:Boolean) {
        box(parent).apply {
            addView(text(if(voice) tr("Voice") else tr("Prediction & correction"),18f,bold=true))
            val models=if(voice) Models.voices else Models.text.filter { it.id in listOf("lfm","qwen") }
            val choices=LinearLayout(this@SetupActivity)
            models.forEachIndexed { index,model ->
                val id=if(voice) voiceChoice else textChoice
                val supported=if(voice) if(model.id=="parakeet") language.parakeet else language.qwenVoice else model.id=="qwen" || language.lfm
                val pair=Models.recommended(language)
                val recommended=model.id==if(voice) pair.second else pair.first
                val count=when(model.id) { "lfm" -> "9";"qwen" -> "39";"parakeet" -> "25";else -> "30" }
                val option=vertical().apply {
                    gravity=android.view.Gravity.CENTER;setPadding(dp(8),dp(10),dp(8),dp(10));minimumHeight=dp(88)
                    background=shape(selected=model.id==id);isSelected=model.id==id;isClickable=true;isFocusable=true;isSoundEffectsEnabled=false
                    isEnabled=supported;alpha=if(supported) 1f else .45f;contentDescription=tr("Setup model: %1\$s",model.id)
                    setOnClickListener { manualModels=true;if(voice) voiceChoice=model.id else textChoice=model.id;modelChoices.forEach { it() };updateDownloads() }
                    val title=text((if(model.id==id) "✓  " else "")+model.title,14f,bold=true);addView(title)
                    modelChoices.add {
                        val selected=model.id==if(voice) voiceChoice else textChoice
                        if(isSelected!=selected) {
                            isSelected=selected;background=shape(selected=selected)
                            title.text=(if(selected) "✓  " else "")+model.title
                        }
                    }
                    val badge=text(if(recommended) tr("Suggested") else if(supported) tr("Alternative") else tr("Not listed"),11f,if(recommended) accent else muted);addView(badge)
                    modelChoices.add {
                        val pair=Models.recommended(language);val preferred=model.id==if(voice) pair.second else pair.first
                        val label=if(preferred) tr("Suggested") else if(supported) tr("Alternative") else tr("Not listed")
                        if(badge.text.toString()!=label) { badge.text=label;badge.setTextColor(if(preferred) accent else muted) }
                    }
                    addView(text(tr("%1\$s languages",count),11f,muted))
                    addView(text(Models.downloadSize(model),11f,muted))
                }
                choices.addView(option,LinearLayout.LayoutParams(0,-2,1f).apply { if(index==0) marginEnd=dp(6) })
            }
            addView(choices,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
            val info=DownloadProgress(this@SetupActivity,accent,muted).apply { setPadding(0,dp(10),0,0) }
            addView(info);if(voice) voiceState=info else textState=info
            if(voice && language.voiceModel==null) addView(text(tr("Neither speech model lists %1\$s. You can continue with text only.",tr(language.name)),12f,muted))
        }
    }
    private fun chosen()=listOfNotNull(Models.text.first { it.id==textChoice },Models.voices.firstOrNull { it.id==voiceChoice })
    private fun updateDownloads() {
        if(step!=1) return
        fun update(view:DownloadProgress?,model:Models.Model) {
            val ready=Models.ready(this,model)
            view?.update(Models.downloadState(this,model)?.takeIf { it.active || !ready },if(ready) tr("Ready on this device") else model.detail+tr(" · Not downloaded"))
        }
        val models=chosen();update(textState,models.first())
        models.getOrNull(1)?.let { update(voiceState,it) } ?: voiceState?.update(null,tr("Voice is not available for this language"))
        val busy=models.any { Models.downloading(this,it) };val ready=models.all { Models.ready(this,it) }
        next?.text=when { ready -> tr("Use these models");busy -> tr("Downloading…");else -> tr("Download ")+if(models.size==2) tr("2 models") else tr("text model") }
        next?.isEnabled=!busy;next?.alpha=if(busy) .6f else 1f
    }
    private fun downloadOrFinish() {
        if(chosen().all { Models.ready(this,it) }) { finishSetup();return }
        getSharedPreferences("setup_downloads",MODE_PRIVATE).edit().putString("text",textChoice).putString("voice",voiceChoice).apply()
        for(model in chosen()) if(!Models.ready(this,model)) Models.download(applicationContext,model) { }
        updateDownloads()
    }
    private fun finishSetup() {
        prefs.model=textChoice
        if(voiceChoice.isNotEmpty()) prefs.speechModel=voiceChoice
        prefs.setupComplete=true
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish()
    }
    override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request==21 && result==RESULT_OK) {
            val selected=data?.getStringExtra("language") ?: return
            prefs.setupLanguage=selected;manualModels=false;syncModels()
            language.layout?.let { prefs.layout=it };render()
        }
    }
    override fun onStart() { super.onStart();main.post(poll) }
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() { back() } // Android 9–12 fallback; newer versions use the dispatcher.
    override fun onStop() { main.removeCallbacks(poll);super.onStop() }
    override fun onSaveInstanceState(state:Bundle) { state.putInt("step",step);state.putString("text",textChoice);state.putString("voice",voiceChoice);state.putBoolean("manual_models",manualModels);super.onSaveInstanceState(state) }
}
