package org.lokey.app

import android.Manifest
import android.content.Intent
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import android.widget.LinearLayout
import android.view.KeyEvent

class LokeyService : InputMethodService(), KeyboardView.Listener {
    private lateinit var prefs: Prefs
    private lateinit var feedback: Feedback
    private lateinit var prediction: Prediction
    private lateinit var voice: Voice
    private lateinit var floatingSpeech:FloatingSpeech
    private val keyboardLocation=IntArray(2)
    private fun updateFloatingSpeech() {
        if(!::floatingSpeech.isInitialized) return
        keyboard?.getLocationOnScreen(keyboardLocation)
        val show=inputVisible && prefs.oneTapSpeech && !privateField && toolMode !in listOf("languages","layouts","clipboard")
        keyboard?.floatingSpeech=floatingSpeech.update(show,recording,processing,keyboard?.recordingPaused==true,keyboardLocation[1])
    }
    private lateinit var translation:Translation
    private lateinit var history:ClipboardHistory
    private lateinit var learning:UsageLearning
    private val learningDraft=UsageDraft()
    private var learningCaret=-1
    private fun resetLearning() { learningDraft.clear();learningCaret=-1 }
    private fun learningField():Boolean {
        val info=currentInputEditorInfo ?: return false
        return prefs.learning && !privateField && toolMode.isEmpty() && !speedTestEditor() && inputLayout().wordSpaces &&
            inputLayout().composer in listOf("","dead") && UsageLearning.allowed(info.inputType,info.imeOptions)
    }
    private fun learningScope()=UsageLearning.scope(prefs.layout,displayedPrediction?.language ?: ModelLanguages.code(inputHint() ?: "English"))
    private lateinit var root:LinearLayout
    private var toolMode=""
    private var editorAction="↵"
    private var translationBar:TranslationBar?=null
    private var sourceEditor:LocalEditor?=null
    private var translationTarget:TranslationTarget?=null
    private var languagePicker:TranslationLanguages?=null
    private var outputPaused=false
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val localRefresh=Runnable { refresh() }
    private fun editor():LocalEditor?=if(toolMode=="languages") languagePicker?.search else sourceEditor
    private fun connection():InputConnection?=editor()?.connection ?: currentInputConnection
    private var translationResult:Translation.Result?=null
    private var translationSource:String?=null
    private var translationLanguage=""
    private var lastClipboardStamp=-1L
    private val deadKeys=DeadKeys()
    private var deadConnection:InputConnection?=null
    private var deadBefore=""
    private var deadAfter=""
    private var deadSelection=""
    private fun validDead():Boolean {
        val input=deadConnection ?: return false
        return input===connection() && input.getTextBeforeCursor(128,0)?.toString()==deadBefore &&
            input.getTextAfterCursor(80,0)?.toString()==deadAfter && input.getSelectedText(0)?.toString().orEmpty()==deadSelection
    }
    private fun clearDead(commit:Boolean=false) {
        if(commit && deadKeys.pending.isNotEmpty() && validDead()) deadConnection?.commitText(deadKeys.pending,1)
        deadKeys.clear();deadConnection=null
    }
    private val layoutListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key in listOf("learning","learning_revision","one_tap_speech","adaptive","autocorrect")) {
            resetLearning();prediction.clear();displayedPrediction=null;keyboard?.prediction(emptyList(),FloatArray(26),false);refresh();keyboard?.invalidate()
        }
        if(key=="layout" && keyboard!=null) {
            resetLearning()
            clearDead(true)
            resetComposition();clearGlide();correction.reset();prediction.clear()
            currentInputEditorInfo?.let { info ->
                val type=info.inputType and InputType.TYPE_MASK_CLASS
                keyboard?.editor(type==InputType.TYPE_CLASS_NUMBER || type==InputType.TYPE_CLASS_PHONE,editorAction,
                    inputLayout().automaticCaps && (connection()?.getCursorCapsMode(info.inputType) ?: 0)!=0)
            }
            keyboard?.requestLayout();refresh()
        }
    }
    private val clipboardListener=ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }
    private var composeDraft=""
    private var composeMethod=""
    private var composeBefore=""
    private var composeAfter=""
    private var composeConnection:InputConnection?=null
    private var composeChoices=emptyList<String>()
    private var composeReady=""
    private var composePage=0
    private var composeWaiting=false
    private val composeQueued=StringBuilder()
    private fun speedTestEditor()=currentInputEditorInfo?.let { it.packageName==packageName && it.privateImeOptions=="org.lokey.app.speedtest" }==true
    private fun inputLayout()=prefs.currentLayout
    private fun inputHint()=if(speedTestEditor()) ModelLanguages.selected(this,prefs).name else ModelLanguages.inputHint(this,prefs)
    private fun composeKind()=if(toolMode=="languages" || privateField) "" else inputLayout().composer
    private fun phonetic()=KeyboardLayouts.all.any { it.composer==composeMethod.ifEmpty { composeKind() } && it.phonetic }
    private fun unspaced()=toolMode!="languages" && !inputLayout().wordSpaces
    private fun displayComposition(final:Boolean=false)=Composers.display(composeMethod.ifEmpty { composeKind() },composeDraft,final)
    private fun compositionChoice()=composeChoices.getOrNull(composePage) ?: displayComposition(true)
    private fun dictionary()=Phonetic.shared(this,KeyboardLayouts.all.first { it.composer==composeMethod.ifEmpty { composeKind() } }.dictionary)
    private fun validComposition():Boolean {
        val input=composeConnection ?: return false
        val shown=displayComposition()
        return input===connection() && input.getTextBeforeCursor(composeBefore.length+shown.length,0)?.toString()==composeBefore+shown &&
            input.getTextAfterCursor(80,0)?.toString()==composeAfter && input.getSelectedText(0).isNullOrEmpty()
    }
    private fun resetComposition(finalize:Boolean=true) {
        if(composeConnection===connection()) {
            if(finalize && composeDraft.isNotEmpty() && validComposition()) {
                val final=displayComposition(true)
                if(final!=displayComposition()) composeConnection?.commitText(final,1)
            }
            composeConnection?.finishComposingText()
        }
        composeDraft="";composeMethod="";composeConnection=null;composeChoices=emptyList();composeReady="";composePage=0;composeWaiting=false;composeQueued.clear()
        keyboard?.moreCandidates=false
    }
    private fun showComposition() {
        displayedPrediction=null
        keyboard?.moreCandidates=composeChoices.size>3
        keyboard?.prediction(composeChoices.drop(composePage).take(3).ifEmpty { listOf(displayComposition(true)) },FloatArray(26),false)
    }
    private fun chooseComposition(value:String) {
        val queued=composeQueued.toString()
        if(validComposition()) composeConnection?.commitText(value,1)
        resetComposition(false);refresh()
        queued.forEach { text(it.toString()) }
    }
    private fun composeInput(value:String) {
        val input=connection() ?: return
        if(composeDraft.isNotEmpty() && !validComposition()) resetComposition(false)
        if(composeDraft.isEmpty()) {
            composeMethod=composeKind()
            clearGlide();correction.reset();prediction.clear()
            composeConnection=input;composeBefore=input.getTextBeforeCursor(128,0)?.toString().orEmpty();composeAfter=input.getTextAfterCursor(80,0)?.toString().orEmpty()
            if(toolMode=="translate" && phonetic()) { translation.cancel();translationSource=null }
        }
        if(composeDraft.length>=128) { resetComposition();composeInput(value);return }
        composeDraft+=value;composeReady="";composeChoices=emptyList();composePage=0
        if(!input.setComposingText(displayComposition(),1)) { resetComposition(false);return }
        if(phonetic()) requestComposition() else refresh()
    }
    private fun requestComposition() {
        val draft=composeDraft
        val japanese=composeMethod=="japanese"
        val converted=displayComposition(true)
        val query=if(japanese) Japanese.hiragana(converted) else draft
        showComposition()
        dictionary().request(query) { choices ->
            if(composeDraft!=draft || !validComposition()) return@request
            composeReady=draft;composePage=0
            composeChoices=if(japanese) {
                val first=if(draft.firstOrNull()?.isUpperCase()==true) converted else choices.firstOrNull() ?: converted
                val ordered=(listOf(first)+choices+converted+draft).distinct()
                (ordered.take(2)+converted+ordered.drop(2)).distinct()
            } else if(composeMethod=="hindi_phonetic") (choices.filter { it!=draft }+converted+draft).distinct() else choices
            if(composeWaiting) chooseComposition(compositionChoice()) else showComposition()
        }
    }
    override fun moreCandidates() {
        if(composeChoices.size>3) { composePage=(composePage+3).let { if(it>=composeChoices.size) 0 else it };showComposition() }
    }
    private var keyboard: KeyboardView? = null
    private val correction = Autocorrect()
    private data class PendingGlide(val connection: InputConnection, val before: String, val after: String, val caret: Int, val session: Int, var suffix: String = "")
    private var pendingGlide: PendingGlide? = null
    private data class QueuedGlide(val points: List<Glide.Point>, val centers: Map<Char, Glide.Point>, val caseMode: Int)
    private val glideQueue = java.util.ArrayDeque<QueuedGlide>()
    private var glideBefore = ""
    private var glideAfter = ""
    private var glideWords = emptyList<String>()
    private var glideInserted = ""
    private var autoSpace: String? = null
    private var displayedPrediction:Prediction.Result?=null
    private fun clearGlide() { pendingGlide = null; glideQueue.clear(); glideWords = emptyList(); autoSpace = null }
    private var privateField = false
    private var inputVisible=false
    private var recording = false
    private var processing = false
    private var session = 0
    private var voiceConnection: InputConnection? = null
    private var voiceSession = 0
    private var selectionStart = -1
    private var selectionEnd = -1
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this); feedback = Feedback(this, prefs); prediction = Prediction(this, prefs); voice = Voice(this)
        floatingSpeech=FloatingSpeech(this,prefs,{
            if(voice(true)) keyboard?.lockRecording()
        },{ voice(false) },{ pauseVoice(it) })
        translation=Translation(this);history=ClipboardHistory(this)
        learning=UsageLearning.shared(this)
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
        getSharedPreferences("lokey",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(layoutListener)
    }
    override fun onCreateInputView(): View {
        root=ImeLayout(this)
        keyboard=KeyboardView(this,prefs,this).apply { addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> updateFloatingSpeech() } };renderTool()
        return root
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun renderTool() {
        if(!::root.isInitialized) return
        root.setBackgroundColor(KeyboardTheme.colors(prefs).background)
        root.removeAllViews();translationBar=null;languagePicker=null
        keyboard?.suggestionsVisible=toolMode!="languages"
        keyboard?.forceLatin=toolMode=="languages"
        val height=dp((if(resources.configuration.screenWidthDp>resources.configuration.screenHeightDp) 260 else 325)*prefs.height/100)
        when(toolMode) {
            "layouts" -> {
                val colors=ToolStyle(this,prefs)
                val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(6),0,dp(6),0);setBackgroundColor(colors.colors.background) }
                val back={ toolMode=if(sourceEditor!=null) "translate" else "";renderTool();refresh() }
                val header=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
                header.addView(ToolButton(this,"back",tr("Back to keyboard"),colors.colors.ink,back),LinearLayout.LayoutParams(dp(40),dp(40)))
                header.addView(colors.text(tr("Keyboard layout"),18f));panel.addView(header)
                panel.addView(android.widget.ScrollView(this).apply { addView(LayoutPicker(this@LokeyService,prefs,true)) },LinearLayout.LayoutParams(-1,0,1f))
                root.addView(panel,LinearLayout.LayoutParams(-1,height))
            }
            "clipboard" -> root.addView(ClipboardPanel(this,prefs,history,{
                if(sourceEditor!=null) { toolMode="translate";renderTool();refresh() } else endTool()
            },{ paste(it) }),LinearLayout.LayoutParams(-1,height))
            "languages" -> {
                val picker=TranslationLanguages(this,prefs,{ toolMode="translate";renderTool();refresh() }) { language ->
                    prefs.translationTarget=language;toolMode="translate";translationSource=null;clearGlide();correction.reset();renderTool();refresh()
                }
                languagePicker=picker
                val filter=picker.search.changed
                picker.search.changed={ filter?.invoke();main.removeCallbacks(localRefresh);main.post(localRefresh) }
                root.addView(picker,LinearLayout.LayoutParams(-1,dp(190)+height*48/325))
                keyboard?.let { root.addView(it,LinearLayout.LayoutParams(-1,-2)) }
                picker.search.requestFocus()
            }
            else -> {
                sourceEditor?.takeIf { toolMode=="translate" }?.let { source ->
                    val bar=TranslationBar(this,prefs,source,{
                        if(!recording && !processing) { translation.cancel();translationSource=null;clearGlide();correction.reset();toolMode="languages";renderTool();refresh() }
                    },{ endTool() },{
                        if(translationResult?.error!=null && !Models.ready(this,Models.selectedText(this,prefs))) startActivity(Intent(this,MainActivity::class.java).putExtra("page","models").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else {
                            if(outputPaused) translationTarget=currentInputConnection?.let { TranslationTarget.capture(it) }
                            outputPaused=false;translationSource=null;updateTranslation()
                        }
                    })
                    translationBar=bar;root.addView(bar)
                    translationResult?.let { bar.update(it) }
                    source.requestFocus()
                }
                keyboard?.let { root.addView(it,LinearLayout.LayoutParams(-1,-2)) }
            }
        }
    }
    private fun endTool(update:Boolean=true) {
        resetLearning()
        clearDead()
        resetComposition()
        toolMode="";translation.cancel();sourceEditor?.changed=null;sourceEditor=null;translationTarget=null;translationResult=null;translationSource=null;outputPaused=false
        main.removeCallbacks(localRefresh);clearGlide();correction.reset()
        renderTool();if(update) refresh()
    }
    override fun tool(name:String) {
        if(privateField) { message(tr("Unavailable in private fields"));return }
        if(recording || processing) { message(tr("Finish recording first"));return }
        resetLearning()
        clearDead(true);resetComposition();clearGlide();correction.reset()
        if(name=="clipboard") { translation.cancel();translationSource=null;toolMode="clipboard";captureClipboard();renderTool() }
        else if(name=="layout") { translation.cancel();translationSource=null;toolMode="layouts";renderTool() }
        else if(name=="translate") {
            if(sourceEditor==null) {
                translationTarget=currentInputConnection?.let { TranslationTarget.capture(it) }
                if(translationTarget==null) { message(tr("This app does not support translation editing"));return }
                sourceEditor=LocalEditor(this).apply {
                    setText(translationTarget!!.source);setSelection(length())
                    changed={ main.removeCallbacks(localRefresh);main.post(localRefresh) }
                }
            }
            toolMode="translate";translationSource=null;renderTool();refresh()
        }
    }
    private fun captureClipboard() {
        if(privateField || !::history.isInitialized) return
        val manager=getSystemService(ClipboardManager::class.java)
        val stamp=try { manager.primaryClipDescription?.timestamp } catch(_:SecurityException) { null } ?: return
        if(stamp==lastClipboardStamp) return
        ClipboardHistory.current(this)?.let {
            history.add(it);lastClipboardStamp=stamp
            if(::root.isInitialized) (root.getChildAt(0) as? ClipboardPanel)?.refresh()
        }
    }
    private fun practice() {
        if(currentInputEditorInfo?.privateImeOptions=="org.lokey.app.speedtest") currentInputConnection?.performPrivateCommand("org.lokey.app.PASTE",null)
    }
    private fun paste(value:String) {
        if(privateField) return
        resetLearning();clearGlide();correction.reset();practice();connection()?.commitText(value,1);feedback.tap()
        if(sourceEditor!=null) { toolMode="translate";renderTool() }
        refresh()
    }
    private fun updateTranslation() {
        if(!inputVisible || toolMode!="translate" || privateField || recording || processing || composeDraft.isNotEmpty() && phonetic()) return
        val host=currentInputConnection ?: return
        val source=sourceEditor?.text?.toString() ?: return
        if(translationSource==source && translationLanguage==prefs.translationTarget) return
        translationSource=source;translationLanguage=prefs.translationTarget
        val token=session
        translation.request(source,prefs.translationTarget) { result ->
            if(token!=session || toolMode!="translate" || host!==currentInputConnection || source!=sourceEditor?.text?.toString()) return@request
            var shown=result
            if(result.error==null && (result.text.isNotEmpty() || source.isEmpty())) {
                if(!outputPaused) {
                    practice()
                    if(translationTarget?.write(host,result.text)!=true) outputPaused=true
                }
            }
            if(outputPaused) shown=result.copy(error=tr("App cursor moved. Retry to insert at the new position."))
            translationResult=shown;translationBar?.update(shown)
        }
    }
    override fun onEvaluateFullscreenMode() = false
    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        inputVisible=false;floatingSpeech.hide();keyboard?.floatingSpeech=false
        endTool(false)
        session++; displayedPrediction=null;clearGlide(); cancelVoice(); prediction.clear(); correction.reset()
        selectionStart = info.initialSelStart; selectionEnd = info.initialSelEnd
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val type = info.inputType and InputType.TYPE_MASK_CLASS
        privateField = (type == InputType.TYPE_CLASS_TEXT && variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        keyboard?.adaptiveInput=false
        keyboard?.speechAllowed=!privateField
    }
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputVisible=true
        val type = info.inputType and InputType.TYPE_MASK_CLASS
        val action = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "⌕"
            EditorInfo.IME_ACTION_GO -> tr("Go")
            EditorInfo.IME_ACTION_SEND -> tr("Send")
            EditorInfo.IME_ACTION_NEXT -> tr("Next")
            EditorInfo.IME_ACTION_DONE -> tr("Done")
            else -> "↵"
        }
        editorAction=action
        keyboard?.editor(type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE, action,
            inputLayout().automaticCaps && info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 && currentInputConnection?.getCursorCapsMode(info.inputType) != 0)
        keyboard?.requestLayout()
        val colors=KeyboardTheme.colors(prefs)
        window?.window?.let { w ->
            w.navigationBarColor=colors.background
            w.decorView.systemUiVisibility=if(KeyboardTheme.light(colors.background)) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
        if(!privateField) captureClipboard()
        if(phonetic()) dictionary().request("") { }
        refresh()
    }
    override fun text(text: String) {
        val numeric=editor()==null && currentInputEditorInfo?.inputType?.and(InputType.TYPE_MASK_CLASS) in listOf(InputType.TYPE_CLASS_NUMBER,InputType.TYPE_CLASS_PHONE)
        if(inputLayout().composer=="dead" && toolMode!="languages" && !numeric && text.length==1) {
            if(deadKeys.pending.isNotEmpty() && !validDead()) clearDead()
            val value=deadKeys.input(text)
            if(deadKeys.pending.isNotEmpty()) {
                val input=connection() ?: return
                deadSelection=input.getSelectedText(0)?.toString().orEmpty()
                deadConnection=input;deadBefore=input.getTextBeforeCursor(128,0)?.toString().orEmpty();deadAfter=input.getTextAfterCursor(80,0)?.toString().orEmpty()
                correction.reset();prediction.clear();refresh();return
            }
            deadConnection=null;typeText(value);return
        }
        clearDead(true);typeText(text)
    }
    private fun typeText(text:String) {
        if(composeWaiting) { composeQueued.append(text);return }
        val kind=composeKind()
        val tone=kind=="jyutping" && text.any { it in '1'..'6' }
        if(Composers.accepts(kind,text) && (!tone || composeDraft.lastOrNull()?.isLetter()==true)) { composeInput(text);return }
        if(composeDraft.isNotEmpty()) {
            if(phonetic()) {
                if(composeReady!=composeDraft) { composeWaiting=true;if(text!=" " || !unspaced()) composeQueued.append(text);return }
                val choice=compositionChoice()
                chooseComposition(choice)
                if(text==" " && unspaced() && choice.any { it.code>127 }) return
            } else resetComposition()
        }
        val connection = connection() ?: return
        val learn=learningField() && connection.getSelectedText(0).isNullOrEmpty() &&
            connection.getTextAfterCursor(1,0)?.firstOrNull()?.let { Languages.isWord(it) || it.isDigit() }!=true
        val caret=if(learn) Cursor.position(connection) else -1
        if(!learn || caret<0) resetLearning()
        else if(caret!=learningCaret) {
            resetLearning()
            if(connection.getTextBeforeCursor(1,0)?.lastOrNull()?.isWhitespace()==false) learningDraft.block()
        }
        if (pendingGlide != null) {
            pendingGlide!!.suffix += text
            connection.commitText(text, 1)
            return
        }
        glideWords = emptyList()
        if (text.length == 1 && text[0] in ".,!?;:،؛؟。，！？：；" && autoSpace != null && connection.getTextBeforeCursor(240, 0)?.toString() == autoSpace)
            connection.deleteSurroundingText(1, 0)
        autoSpace = null
        val enabled=prefs.autocorrect && !privateField && !restrictedCorrection()
        var observed:String?=null
        val scope=if(learn) learningScope() else ""
        var deferred=false
        val corrected=correction.commit(connection, text, prediction.latest, enabled)
        var committed=corrected
        if (!corrected) {
            if(connection.commitText(text,1)) {
                committed=true
                correction.defer(connection,text,enabled)?.let { before ->
                    deferred=true
                    val token=session
                    prediction.correct(before,learningField()) { result ->
                        if(token==session && connection===connection() && prefs.autocorrect && !privateField && !restrictedCorrection()) {
                            if(correction.finish(connection,result)) { resetLearning();refresh() }
                            else if(result?.correction==null && learningField()) observed?.let { learning.learn(scope,it) }
                        }
                    }
                }
            }
        }
        if(learn && committed && !corrected) {
            observed=learningDraft.input(text)
            if(!deferred) observed?.let { learning.learn(scope,it) }
            learningCaret=Cursor.position(connection)
        } else resetLearning()
        refresh()
    }
    override fun delete() {
        if(deadKeys.pending.isNotEmpty()) { clearDead();refresh();return }
        if(composeWaiting && composeQueued.isNotEmpty()) { composeQueued.deleteCharAt(composeQueued.lastIndex);return }
        if(composeDraft.isNotEmpty() && validComposition()) {
            composeWaiting=false;composeDraft=Composers.delete(composeMethod,composeDraft)
            if(composeDraft.isEmpty()) { composeConnection?.commitText("",1);resetComposition();refresh() }
            else {
                composeConnection?.setComposingText(displayComposition(),1);composeReady="";composeChoices=emptyList();composePage=0
                if(phonetic()) requestComposition() else refresh()
            }
            return
        }
        resetComposition();clearGlide()
        val connection = connection() ?: return
        if (correction.revert(connection) { if(learningField()) learning.reject(learningScope(),it) }) { resetLearning();refresh(); return }
        val learn=learningField() && Cursor.position(connection)==learningCaret && connection.getSelectedText(0).isNullOrEmpty()
        if (!connection.getSelectedText(0).isNullOrEmpty()) { connection.commitText("", 1);resetLearning() }
        else if(connection.deleteSurroundingTextInCodePoints(1, 0) && learn) { learningDraft.delete();learningCaret=Cursor.position(connection) }
        else resetLearning()
        refresh()
    }
    override fun enter() {
        resetLearning()
        clearDead(true)
        if(composeDraft.isNotEmpty() && phonetic()) {
            if(composeReady==composeDraft) chooseComposition(compositionChoice()) else composeWaiting=true
            return
        }
        resetComposition()
        clearGlide()
        if(toolMode=="languages") { languagePicker?.chooseFirst();return }
        if(sourceEditor!=null) { text("\n");return }
        val info = currentInputEditorInfo ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0)
            connection()?.performEditorAction(action)
        else text("\n")
    }
    override fun cursor(delta: Int) {
        resetLearning()
        clearDead(true)
        resetComposition();clearGlide(); correction.clear()
        val connection = connection() ?: return
        val position = Cursor.move(connection, delta, editor()?.selectionStart ?: selectionStart, editor()?.selectionEnd ?: selectionEnd) ?: return
        if(editor()==null) { selectionStart = position; selectionEnd = position }
    }
    override fun glide(points: List<Glide.Point>, centers: Map<Char, Glide.Point>, caseMode: Int) {
        resetLearning()
        clearDead(true)
        val connection = connection() ?: return
        if (privateField || !prefs.flow || recording || processing || !connection.getSelectedText(0).isNullOrEmpty()) return
        if (pendingGlide != null) { glideQueue.addLast(QueuedGlide(points, centers, caseMode)); return }
        correction.clear(); glideWords = emptyList(); autoSpace = null
        val pending = PendingGlide(connection, connection.getTextBeforeCursor(240, 0)?.toString().orEmpty(),
            connection.getTextAfterCursor(80, 0)?.toString().orEmpty(), Cursor.position(connection), session)
        if (pending.caret < 0) return
        pendingGlide = pending
        prediction.glide(pending.before, points, centers) { words ->
            if (pendingGlide !== pending) return@glide
            pendingGlide = null
            val expected = pending.before + pending.suffix
            if (words.isEmpty() || session != pending.session || connection !== connection() ||
                !connection.getSelectedText(0).isNullOrEmpty() || connection.getTextBeforeCursor(expected.length, 0)?.toString() != expected ||
                connection.getTextAfterCursor(80, 0)?.toString() != pending.after ||
                Cursor.position(connection) != pending.caret + pending.suffix.length) { glideQueue.clear(); refresh(); return@glide }
            val locale = java.util.Locale.forLanguageTag(prediction.latest?.language ?: "en")
            val options = words.map { word -> when (caseMode) { 2 -> word.uppercase(locale); 1 -> word.replaceFirstChar { it.toString().uppercase(locale) }; else -> word } }
            val separator = if (pending.before.isNotEmpty() && !pending.before.last().isWhitespace()) " " else ""
            connection.beginBatchEdit()
            if (pending.suffix.isNotEmpty()) connection.deleteSurroundingText(pending.suffix.length, 0)
            connection.commitText(separator + options.first() + " " + pending.suffix, 1)
            connection.endBatchEdit()
            if (pending.suffix.isEmpty()) {
                glideInserted = options.first() + " "
                glideBefore = connection.getTextBeforeCursor(240, 0)?.toString().orEmpty()
                glideAfter = pending.after; glideWords = options; autoSpace = glideBefore
            }
            refresh()
            glideQueue.pollFirst()?.let { glide(it.points, it.centers, it.caseMode) }
        }
    }
    override fun candidate(word: String) {
        clearDead(true)
        if(composeDraft.isNotEmpty()) {
            if(phonetic()) { if(word in composeChoices || word==displayComposition(true)) chooseComposition(word);return }
            resetComposition()
        }
        correction.clear()
        val connection = connection() ?: return
        if (word in glideWords && connection.getTextBeforeCursor(240, 0)?.toString() == glideBefore && connection.getTextAfterCursor(80, 0)?.toString() == glideAfter) {
            connection.beginBatchEdit(); connection.deleteSurroundingText(glideInserted.length, 0); connection.commitText("$word ", 1); connection.endBatchEdit()
            clearGlide(); autoSpace = connection.getTextBeforeCursor(240, 0)?.toString(); refresh(); return
        }
        clearGlide()
        if (currentInputEditorInfo?.privateImeOptions == "org.lokey.app.speedtest")
            connection.performPrivateCommand("org.lokey.app.PREDICTION", android.os.Bundle().apply { putInt("words", Languages.tokens(word).size) })
        val before = connection.getTextBeforeCursor(240, 0)?.toString().orEmpty()
        val shown=displayedPrediction
        if(shown==null || shown.before!=before || word !in shown.words) { refresh();return }
        val append=word in shown.appendWords
        val learned=if(learningField() && Cursor.position(connection)==learningCaret && learningDraft.value==Languages.prefix(before)) learningDraft.value else ""
        val punctuation = word.length == 1 && word[0] in "?!."
        val replacement=shown.spelling?.takeIf { it.word==word }?.source
        if(replacement!=null && (!before.endsWith(replacement) || !connection.getSelectedText(0).isNullOrEmpty() ||
            connection.getTextAfterCursor(1,0)?.firstOrNull()?.let { Languages.isWord(it) || it.isDigit() }==true)) { refresh();return }
        val prefix = if (append || punctuation) 0 else replacement?.length ?: Languages.prefix(before).length
        val separator = if (append && !unspaced() && !punctuation && before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        connection.beginBatchEdit()
        if (punctuation) connection.deleteSurroundingText(before.length - before.trimEnd(' ', '\t').length, 0)
        else connection.deleteSurroundingText(prefix, 0)
        val ending=if(unspaced()) "" else " "
        val committed=connection.commitText("$separator$word$ending",1)
        connection.endBatchEdit()
        if(committed && learningField()) learning.learn(learningScope(),word,if(append) "" else learned)
        resetLearning()
        autoSpace=if(ending.isEmpty()) null else connection.getTextBeforeCursor(240,0)?.toString()
        refresh()
    }
    private fun restrictedCorrection(): Boolean {
        if(toolMode=="languages") return true
        if(!inputLayout().latin || inputLayout().composer !in listOf("","dead")) return true
        if(inputHint() in listOf("Maltese","Indonesian","Malay","Filipino","Tagalog")) return true
        if(sourceEditor!=null) return false
        val info = currentInputEditorInfo ?: return true
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT ||
            type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 || variation in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER)
    }
    private fun adaptiveField():Boolean {
        if(privateField || recording || processing || toolMode in listOf("languages","layouts","clipboard")) return false
        if(inputLayout().composer !in listOf("","dead")) return false
        if(!connection()?.getSelectedText(0).isNullOrEmpty()) return false
        if(sourceEditor!=null) return true
        val info=currentInputEditorInfo ?: return false
        return AdaptiveKeys.allowed(info.inputType,info.imeOptions)
    }
    private fun refresh() {
        if(!inputVisible) return
        keyboard?.adaptiveInput=adaptiveField()
        keyboard?.speechAllowed=!privateField && toolMode !in listOf("languages","layouts","clipboard")
        updateFloatingSpeech()
        if(deadKeys.pending.isNotEmpty()) {
            if(!validDead()) clearDead()
            else {
                displayedPrediction=null;prediction.clear();keyboard?.prediction(emptyList(),FloatArray(26),false)
                keyboard?.status(tr("Accent %1\$s · Letter to compose · Space for literal",deadKeys.pending));return
            }
        }
        keyboard?.action(if(toolMode=="languages") "⌕" else if(sourceEditor!=null) "↵" else editorAction)
        currentInputEditorInfo?.let { info ->
            val connection=connection()
            val previous=connection?.getTextBeforeCursor(1,0)?.lastOrNull()
            val insideWord=previous?.let { Languages.isWord(it) || it.isDigit() }==true
            keyboard?.capitalization(inputLayout().automaticCaps && !insideWord && (connection?.getCursorCapsMode(editor()?.inputType ?: info.inputType) ?: 0)!=0)
        }
        if (recording || processing) { prediction.clear(); return }
        if(composeDraft.isNotEmpty()) {
            if(!validComposition()) resetComposition(false)
            else if(phonetic()) { showComposition();return }
        }
        if (pendingGlide != null) return
        updateTranslation()
        if (glideWords.isNotEmpty()) {
            if (connection()?.getTextBeforeCursor(240, 0)?.toString() == glideBefore && connection()?.getTextAfterCursor(80, 0)?.toString() == glideAfter) {
                keyboard?.prediction(glideWords, FloatArray(26), false); return
            }
            glideWords = emptyList()
        }
        if (privateField) { keyboard?.prediction(emptyList(), FloatArray(26), false); keyboard?.status(tr("PRIVATE FIELD · PREDICTION PAUSED")); return }
        if(toolMode in listOf("languages","layouts","clipboard")) { prediction.clear();displayedPrediction=null;keyboard?.prediction(emptyList(),FloatArray(26),false);return }
        val before = connection()?.getTextBeforeCursor(240, 0)?.toString() ?: ""
        if(displayedPrediction?.before!=before) { displayedPrediction=null;keyboard?.prediction(emptyList(),FloatArray(26),false) }
        prediction.request(before,toolMode!="translate" && toolMode!="languages",inputHint(),learningField()) { result ->
            displayedPrediction=result;keyboard?.prediction(result.words,result.letters,result.letters.any { it>0f },result.language)
        }
        keyboard?.status("")
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart; selectionEnd = newSelEnd
        if(inputVisible) {
            if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) keyboard?.activity()
            refresh()
        }
    }
    override fun voice(start: Boolean): Boolean {
        resetLearning()
        clearDead(true)
        if (!start) {
            if (recording) { recording = false; processing = true; keyboard?.voiceState = tr("Transcribing on device…"); voice.stop();updateFloatingSpeech() }
            return true
        }
        if (recording || processing || toolMode=="languages") return false
        if (privateField) { message(tr("Voice is disabled in private fields")); return false }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("microphone", true)); return false
        }
        val model=Models.selectedVoice(this,prefs)
        if(!Models.ready(this,model)) { message(tr("Add %1\$s in Lokey → Models",model.title));settings();return false }
        if(composeDraft.isNotEmpty()) chooseComposition(compositionChoice())
        clearGlide(); correction.clear()
        if (currentInputEditorInfo?.privateImeOptions == "org.lokey.app.speedtest")
            currentInputConnection?.performPrivateCommand("org.lokey.app.VOICE", null)
        voiceConnection = connection(); voiceSession = session; recording = true; prediction.clear()
        if(toolMode=="translate") { translation.cancel();translationSource=null;translationBar?.update(Translation.Result("",prefs.translationTarget,busy=true)) }
        keyboard?.voiceState = "Listening"
        updateFloatingSpeech()
        voice.start({ level, millis ->
            keyboard?.audioLevel(level, millis)
            if (millis >= 59000 && recording) voice(false)
        }, { text, error ->
            recording = false; processing = false; keyboard?.voiceState = ""
            updateFloatingSpeech()
            // Never put a finished transcript into an editor opened after recording began.
            if (voiceSession == session && voiceConnection === connection()) {
                if (error != null) message(error)
                else if (!text.isNullOrBlank()) {
                    val connection = voiceConnection
                    val before = connection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                    connection?.commitText((if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + text + " ", 1)
                } else message(tr("No speech detected"))
                refresh()
            }
        })
        return true
    }
    override fun cancelVoice() {
        val wasBusy=recording || processing
        recording=false;processing=false;voice.cancel();voiceConnection=null;keyboard?.voiceState=""
        updateFloatingSpeech()
        if(wasBusy && toolMode=="translate") { translationSource=null;updateTranslation() }
    }
    override fun pauseVoice(paused:Boolean):Boolean {
        if(!recording || !voice.pause(paused)) return false
        keyboard?.pauseRecording(paused);updateFloatingSpeech();return true
    }
    private fun settings() { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    override fun nextKeyboard() { switchToNextInputMethod(false) }
    override fun feedback(key: String, down: Boolean) { feedback.tap(key, down, !recording) }
    private fun message(text: String) { Toast.makeText(this, tr(text), Toast.LENGTH_LONG).show() }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(keyCode==KeyEvent.KEYCODE_BACK && toolMode.isNotEmpty()) {
            if(toolMode=="languages" || toolMode in listOf("clipboard","layouts") && sourceEditor!=null) { toolMode="translate";renderTool();refresh() } else endTool()
            return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun onWindowHidden() { inputVisible=false;resetLearning();cancelVoice();floatingSpeech.hide();super.onWindowHidden() }
    override fun onFinishInputView(finishingInput: Boolean) { inputVisible=false;session++; displayedPrediction=null;clearGlide(); cancelVoice(); prediction.clear(); correction.reset(); keyboard?.cancelTouches();endTool(false); super.onFinishInputView(finishingInput) }
    override fun onDestroy() { floatingSpeech.hide();getSharedPreferences("lokey",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(layoutListener);getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener);translation.close();voice.close(); prediction.close(); feedback.close(); super.onDestroy() }
}
