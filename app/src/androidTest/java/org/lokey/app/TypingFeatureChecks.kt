package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TypingFeatureChecks {
    fun spelling(test:Instrumentation,context:Context):JSONObject {
        check(context.packageName=="org.lokey.app.features")
        data class Case(val language:String,val before:String,val fixed:String?,val suggestion:String?=fixed)
        val cases=listOf(
            Case("English","Im","I'm"),Case("English","Hello Im","I'm"),Case("English","Ive","I've"),
            Case("English","Please dont","don't"),Case("English","Theyre","They're"),Case("English","IM",null),
            Case("English","its",null,"it's"),Case("English","I think its",null,"it's"),
            Case("English","I guess its","it's"),Case("English","Yes its","it's"),
            Case("English","I guess. its",null,"it's"),
            Case("English","Look at its",null,"it's"),Case("English","The dog lost its",null,"it's"),
            Case("English","its a","it's a"),Case("English","Its raining","It's raining"),Case("English","its been","it's been"),
            Case("English","Its tail is",null),Case("English","I think it's tail is","its tail is"),
            Case("German","im",null),Case("German","Im Garten",null),
            Case("Italian","perche","perché"),Case("Italian","Non so perche","perché"),
            Case("Italian","Perchè","Perché"),Case("Italian","PERCHE","PERCHÉ"),Case("Italian","perche\u0300","perché"),
            Case("Italian","c'e","c'è"),Case("Italian","c’é","c'è"),Case("Italian","gia","già"),
            Case("Italian","piu","più"),Case("Italian","lunedi","lunedì"),Case("Italian","qual'è","qual è"),
            Case("Italian","ce",null,"c'è"),Case("Italian","ce un","c'è un"),Case("Italian","Ce qualcuno","C'è qualcuno"),
            Case("Italian","ce ne",null,"né"),Case("Italian","ce ne sono",null),Case("Italian","ce la faccio",null),
            Case("Italian","c'è la faccio","ce la faccio"),Case("Italian","ce la pizza","c'è la pizza"),
            Case("Italian","un po","po'"),Case("Italian","Il Po",null,"Po'"),Case("Italian","perché",null)
        )
        val mainPrefs=Prefs(context);val oldSetup=mainPrefs.setupComplete;val oldAuto=mainPrefs.autocorrect;val oldLanguage=mainPrefs.setupLanguage;val oldLayout=mainPrefs.layout
        mainPrefs.setupComplete=true
        val activity=test.startActivitySync(android.content.Intent().setClass(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var editor:android.widget.EditText
        lateinit var connection:android.view.inputmethod.InputConnection
        test.runOnMainSync {
            editor=android.widget.EditText(activity).apply { showSoftInputOnFocus=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;setText("Spelling checks") }
            activity.setContentView(editor);editor.requestFocus();connection=editor.onCreateInputConnection(EditorInfo())!!
        }
        val root=File(context.getExternalFilesDir(null),"spelling-check")
        val reports=org.json.JSONArray()
        try {
            for(path in listOf("local","lfm","qwen")) {
                val directory=File(context.cacheDir,"spelling-check-$path").apply { mkdirs() }
                val sandbox=object:ContextWrapper(context) {
                    override fun getApplicationContext():Context=this
                    override fun getFilesDir()=File(root,path).apply { mkdirs() }
                    override fun getNoBackupFilesDir()=directory
                    override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("spelling-check-$path-$name",mode)
                }
                val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().putBoolean("learning",true).commit()
                val prefs=Prefs(sandbox);prefs.model=if(path=="local") "auto" else path;prefs.layout="qwerty";prefs.adaptive=true;prefs.setupLanguage="English"
                check(Models.text.filter { Models.ready(sandbox,it) }.map { it.id }==if(path=="local") emptyList<String>() else listOf(path))
                val learning=UsageLearning(sandbox,"lokey-spelling-$path-v1");learning.awaitIdle();learning.erase()
                repeat(4) {
                    learning.learn("qwerty:en","important");learning.learn("qwerty:en","in","im")
                    learning.learn("qwerty:en","it's","its");learning.learn("qwerty:it","perchè","perche")
                }
                Native.release();lateinit var engine:Prediction
                test.runOnMainSync { engine=Prediction(sandbox,prefs,learning) }
                fun request(case:Case,on:Boolean,autocorrect:Boolean):Prediction.Result {
                    prefs.setupLanguage=case.language;prefs.autocorrect=autocorrect
                    store.edit().putBoolean("learning",on).commit()
                    val done=CountDownLatch(1);var answer:Prediction.Result?=null;var callbacks=0
                    test.runOnMainSync { engine.clear();engine.request(case.before,learningAllowed=on) {
                        callbacks++
                        if(path=="local" && callbacks>=2 || path!="local" && it.neural) { answer=it;done.countDown() }
                    } }
                    check(done.await(60,TimeUnit.SECONDS)) { "No prediction: $path ${case.before}" }
                    return answer!!
                }
                try {
                    for(on in listOf(false,true)) for(case in cases) {
                        val value=request(case,on,true)
                        check(value.correction==case.fixed) { "$path learning=$on '${case.before}' -> ${value.correction}; expected ${case.fixed}; language=${value.language}" }
                        case.suggestion?.let { word -> check(word in value.words && word !in value.appendWords) { "Missing replacement $word: $value" } }
                        val done=CountDownLatch(1);var fixed:Prediction.Result?=null
                        test.runOnMainSync { engine.correct(case.before,on) { fixed=it;done.countDown() } }
                        check(done.await(60,TimeUnit.SECONDS) && fixed?.correction==case.fixed) { "Boundary $path ${case.before}: $fixed" }
                        if(case.fixed!=null) test.runOnMainSync {
                            val source=value.spelling!!.source
                            val expected=case.before.dropLast(source.length)+case.fixed
                            for(boundary in listOf(" ","?")) {
                                val correction=Autocorrect();editor.setText(case.before);editor.setSelection(editor.length())
                                check(correction.commit(connection,boundary,value,true) && editor.text.toString()==expected+boundary)
                                check(correction.revert(connection) && editor.text.toString()==case.before)
                                check(!correction.commit(connection,boundary,value,true))
                                correction.reset()
                                editor.setText(case.before+boundary);editor.setSelection(editor.length())
                                check(correction.defer(connection,boundary,true)==case.before)
                                check(correction.finish(connection,value) && editor.text.toString()==expected+boundary)
                                check(correction.revert(connection) && editor.text.toString()==case.before)
                            }
                        }
                        reports.put(JSONObject().put("path",path).put("learning",on).put("before",case.before)
                            .put("language",value.language).put("correction",value.correction ?: JSONObject.NULL).put("words",org.json.JSONArray(value.words)))
                    }
                    for(on in listOf(false,true)) for(case in cases.filter { it.before in listOf("Im","its","I guess its","its a","perche","ce","ce un") }) {
                        val value=request(case,on,false)
                        check(value.correction==null && case.suggestion in value.words && case.suggestion !in value.appendWords)
                    }
                    if(path=="local") check(Native.residentModels() and 1==0)
                    File(root,"results-$path.json").writeText(reports.toString(2))
                } finally {
                    test.runOnMainSync { engine.close() };learning.erase();learning.close();store.edit().clear().commit();directory.deleteRecursively()
                }
            }
            fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
            val originalIme=shell("settings get secure default_input_method")
            fun nodes():List<AccessibilityNodeInfo> {
                test.uiAutomation.clearCache();val out=ArrayList<AccessibilityNodeInfo>()
                fun walk(node:AccessibilityNodeInfo) {
                    if(node.packageName==context.packageName) out.add(node)
                    for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
                }
                for(window in test.uiAutomation.windows) if(window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { walk(it) }
                return out
            }
            fun await(condition:()->Boolean) {
                val until=SystemClock.uptimeMillis()+12000
                while(SystemClock.uptimeMillis()<until) { if(condition()) return;SystemClock.sleep(40) }
                error("Live spelling check timed out")
            }
            try {
                val service=test.uiAutomation.serviceInfo
                service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                test.uiAutomation.serviceInfo=service
                mainPrefs.layout="qwerty";mainPrefs.setupLanguage="English";mainPrefs.autocorrect=false
                shell("ime enable ${context.packageName}/org.lokey.app.LokeyService")
                shell("ime set ${context.packageName}/org.lokey.app.LokeyService")
                fun put(text:String) {
                    test.runOnMainSync {
                        activity.getSystemService(android.view.autofill.AutofillManager::class.java).cancel()
                        editor.setText(text);editor.setSelection(editor.length());editor.requestFocus()
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(editor,0)
                    }
                    await { nodes().any { it.contentDescription?.toString()==context.tr("Suggestion: %1\$s","it's a") } }
                }
                put("its a")
                check(nodes().first { it.contentDescription?.toString()==context.tr("Suggestion: %1\$s","it's a") }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { editor.text.toString()=="it's a " }
                mainPrefs.autocorrect=true;put("its a")
                check(nodes().first { it.contentDescription?.toString()?.startsWith("Space,")==true }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { editor.text.toString()=="it's a " }
                check(nodes().first { it.contentDescription?.toString()?.startsWith("Backspace,")==true }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { editor.text.toString()=="its a" }
            } finally {
                mainPrefs.autocorrect=oldAuto;mainPrefs.setupLanguage=oldLanguage;mainPrefs.layout=oldLayout
                if(originalIme.isNotBlank() && originalIme!="null") shell("ime set $originalIme")
            }
        } finally { Native.release();test.runOnMainSync { activity.finish();mainPrefs.setupComplete=oldSetup } }
        return JSONObject().put("paths",3).put("cases",cases.size).put("learning_conditions",2).put("checks",reports.length())
            .put("space_punctuation_and_undo",true).put("suggestions_with_autocorrect_off",true).put("live_candidate_space_and_undo",true).put("details",File(root,"results-qwen.json").path)
    }
    fun neural(test:Instrumentation,context:Context):JSONObject {
        check(context.packageName=="org.lokey.app.features")
        val directory=File(context.cacheDir,"learning-neural-check").apply { mkdirs() }
        val sandbox=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(context.getExternalFilesDir(null),"neural")
            override fun getNoBackupFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("learning-neural-check-$name",mode)
        }
        val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().putBoolean("learning",true).commit()
        val prefs=Prefs(sandbox);prefs.model="lfm";prefs.setupLanguage="English";prefs.layout="qwerty"
        check(Models.ready(sandbox,Models.text.first())) { "Stage the LFM model in external files/neural/models" }
        val learning=UsageLearning(sandbox,"lokey-learning-neural-test");learning.awaitIdle()
        lateinit var engine:Prediction
        Native.release();test.runOnMainSync { engine=Prediction(sandbox,prefs,learning) }
        try {
            repeat(3) { learning.learn("qwerty:en","quokkalokey","quokkaloksy") }
            fun request():Prediction.Result {
                val done=CountDownLatch(1);var result:Prediction.Result?=null
                test.runOnMainSync { engine.request("I use quokk",learningAllowed=true) { if(it.neural) { result=it;done.countDown() } } }
                check(done.await(45,TimeUnit.SECONDS)) { "No neural result after adding a personal candidate" }
                return result!!
            }
            val learned=request();check("quokkalokey" in learned.words && learned.letters.all { it.isFinite() })
            val cached=request();check("quokkalokey" in cached.words && "quokkalokey" in cached.wordScores)
            learning.erase();test.runOnMainSync { engine.clear() }
            val erased=request();check("quokkalokey" !in erased.words && "quokkalokey" !in erased.wordScores) { "Erased profile remained in the neural candidate cache" }
            return JSONObject().put("model","LFM2.5-350M-Q4_K_M.gguf").put("backend",Native.textBackend())
                .put("learned",org.json.JSONArray(learned.words)).put("after_erase",org.json.JSONArray(erased.words))
                .put("personal_candidate_scored",true).put("erase_clears_neural_cache",true)
        } finally {
            test.runOnMainSync { engine.close() };learning.erase();learning.close();store.edit().clear().commit();directory.deleteRecursively()
        }
    }
    fun overlay(test:Instrumentation,context:Context):JSONObject {
        check(context.packageName=="org.lokey.app.features" && android.provider.Settings.canDrawOverlays(context))
        val prefs=Prefs(context);val x=prefs.speechX;val y=prefs.speechY
        var starts=0;var stops=0;var pauses=0;var resumes=0
        lateinit var bubble:FloatingSpeech
        test.runOnMainSync { bubble=FloatingSpeech(context,prefs,{ starts++ },{ stops++ },{ if(it) pauses++ else resumes++ }) }
        try {
            test.runOnMainSync { check(bubble.update(true,false,false,false,Int.MAX_VALUE)) }
            test.waitForIdleSync();SystemClock.sleep(150)
            test.runOnMainSync {
                val controls=bubble.controls
                val before=IntArray(2);controls.getLocationOnScreen(before)
                val now=SystemClock.uptimeMillis()
                fun event(action:Int,px:Float,py:Float) {
                    val event=MotionEvent.obtain(now,now+20,action,px,py,0);controls.dispatchTouchEvent(event);event.recycle()
                }
                val cx=controls.width/2f;val cy=controls.height/2f
                event(MotionEvent.ACTION_DOWN,cx,cy)
                event(MotionEvent.ACTION_MOVE,cx-300,cy-200)
                event(MotionEvent.ACTION_UP,cx-300,cy-200)
                check(starts==0 && (prefs.speechX!=x || prefs.speechY!=y)) { "Dragging activated recording or did not save position" }
                controls.getChildAt(1).performClick();check(starts==1)
                check(bubble.update(true,true,false,false,Int.MAX_VALUE))
                controls.getChildAt(0).performClick();check(pauses==1)
                bubble.update(true,true,false,true,Int.MAX_VALUE)
                check(controls.getChildAt(0).contentDescription==context.tr("Resume recording"))
                controls.getChildAt(0).performClick();check(resumes==1)
                controls.getChildAt(1).performClick();check(stops==1)
                bubble.hide()
                check(bubble.update(true,false,false,false,Int.MAX_VALUE))
            }
            return JSONObject().put("separate_screen_window",true).put("drag_does_not_record",true).put("position_saved",true)
                .put("start_pause_resume_stop",true).put("window_reattaches",true)
        } finally { test.runOnMainSync { bubble.hide();prefs.speechPosition(x,y) } }
    }
    fun live(test:Instrumentation,context:Context):JSONObject {
        check(context.packageName=="org.lokey.app.features") { "Use the isolated benchmark application" }
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        val original=shell("settings get secure default_input_method")
        val ime="${context.packageName}/org.lokey.app.LokeyService"
        val prefs=Prefs(context);prefs.setupComplete=true;prefs.layout="qwerty";prefs.learning=true;prefs.oneTapSpeech=true;prefs.autocorrect=false
        val learning=UsageLearning.shared(context);learning.awaitIdle();learning.erase()
        val activity=test.startActivitySync(android.content.Intent().setClass(context,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var input:android.widget.EditText
        val serviceInfo=test.uiAutomation.serviceInfo
        serviceInfo.flags=serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=serviceInfo
        fun await(condition:()->Boolean) {
            val until=SystemClock.uptimeMillis()+10000
            while(SystemClock.uptimeMillis()<until) { if(condition()) return;SystemClock.sleep(40) }
            val focus=shell("dumpsys window").lineSequence().filter { "mCurrentFocus=" in it || "mFocusedApp=" in it }.joinToString()
            error("Live feature check timed out: $focus")
        }
        fun nodes():List<AccessibilityNodeInfo> {
            test.uiAutomation.clearCache()
            val out=ArrayList<AccessibilityNodeInfo>()
            fun walk(node:AccessibilityNodeInfo) {
                if(node.packageName==context.packageName && !node.contentDescription.isNullOrEmpty()) out.add(node)
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
            }
            for(window in test.uiAutomation.windows) if(window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { walk(it) }
            return out
        }
        fun key(name:String) {
            test.runOnMainSync { activity.getSystemService(android.view.autofill.AutofillManager::class.java).cancel() }
            await { activity.hasWindowFocus() }
            val node=nodes().firstOrNull { it.contentDescription.toString().substringBefore(',').equals(name,true) } ?: error("Missing key $name")
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));test.waitForIdleSync()
        }
        fun type(word:String) { for(c in word) key(if(c==' ') "Space" else c.toString()) }
        fun editor(options:Int=0,type:Int=InputType.TYPE_CLASS_TEXT) {
            test.runOnMainSync { activity.getSystemService(android.view.autofill.AutofillManager::class.java).cancel() }
            await { activity.hasWindowFocus() }
            test.runOnMainSync {
                input=android.widget.EditText(activity).apply {
                    inputType=type;imeOptions=options;privateImeOptions=null
                    importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
                activity.setContentView(input);input.requestFocus()
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input,0)
            }
            await {
                test.runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input,0) }
                nodes().any { it.contentDescription.toString().startsWith("Space,") }
            }
            await { activity.hasWindowFocus() && input.hasFocus() }
            SystemClock.sleep(150)
        }
        val voice=Voice(context)
        try {
            shell("ime enable $ime");shell("ime set $ime")
            editor()
            repeat(2) { type("I like quokkalokey ") }
            check(learning.candidates("qwerty:en","quokk").firstOrNull()?.first=="quokkalokey") {
                val field=UsageLearning::class.java.getDeclaredField("model").apply { isAccessible=true }
                "Own key events were not learned: text=${input.text}, rows=${(field.get(learning) as UsageModel).snapshot()}"
            }
            editor();type("I like quokk")
            await { nodes().any { it.contentDescription.toString()=="Suggestion: quokkalokey" } }
            learning.erase();editor(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);type("privateword privateword ")
            check(learning.candidates("qwerty:en","private").isEmpty())
            check(nodes().none { it.contentDescription.toString()=="Start recording" })
            editor(type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);type("emailword emailword ")
            check(learning.candidates("qwerty:en","email").isEmpty())
            editor()
            test.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.RECORD_AUDIO)
            val samples=java.util.concurrent.atomic.AtomicLong()
            val done=CountDownLatch(1)
            test.runOnMainSync { voice.start({ _,millis -> samples.set(millis) },{ _,_ -> done.countDown() }) }
            await { samples.get()>=250 }
            check(voice.pause(true));SystemClock.sleep(150)
            val paused=samples.get();SystemClock.sleep(300);check(samples.get()==paused) { "Paused microphone still captured audio" }
            check(voice.pause(false));await { samples.get()>paused+100 }
            check(voice.pause(true));test.runOnMainSync { voice.stop() }
            check(done.await(10,TimeUnit.SECONDS)) { "Stopping while paused did not finish" }
            return JSONObject().put("own_keys_learned_without_models",true).put("personal_word_visible",true)
                .put("no_learning_flag_honored_live",true).put("email_field_excluded_live",true)
                .put("microphone_pause_stops_samples",true).put("microphone_resume",true).put("stop_while_paused",true)
        } finally {
            voice.close();prefs.learning=false;prefs.oneTapSpeech=false
            if(original.isNotEmpty() && original!="null") shell("ime set $original")
            shell("ime disable $ime")
            test.runOnMainSync { activity.finish() }
        }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val report=JSONObject()
        var day=100
        val model=UsageModel { day }
        val scope="qwerty:en"
        check(model.learn(scope,"quokkalokey","quokkaloksy"))
        check(model.candidates(scope,"quokk").isEmpty() && model.correction(scope,"quokkaloksy")==null)
        repeat(2) { model.learn(scope,"quokkalokey","quokkaloksy") }
        check(model.candidates(scope,"quokk").first().first=="quokkalokey")
        check(model.nearby(scope,"quokkalokxy").first().first=="quokkalokey" && model.correction(scope,"quokkalokxy")==null)
        check(model.correction(scope,"quokkaloksy")=="quokkalokey")
        check(model.candidates("azerty:en","quokk").isEmpty() && model.candidates("qwerty:it","quokk").isEmpty())
        for(word in listOf("user@example.com","https://secret","secret123","two words","123456","a")) check(!model.learn(scope,word))
        repeat(3) { model.learn(scope,"quokkalokey","quokk") }
        check(model.correction(scope,"quokk")==null) { "A completion became a typo" }
        model.reject(scope,"quokkaloksy");check(model.correction(scope,"quokkaloksy")==null)
        val old=model.candidates(scope,"quokk").first().second
        day+=30;check(model.candidates(scope,"quokk").first().second<old)
        day+=61;check(model.snapshot().isEmpty())
        repeat(1100) { i ->
            var n=i
            val suffix=buildString { repeat(4) { append('a'+n%26);n/=26 } }
            model.learn(scope,"word$suffix")
        }
        check(model.snapshot().size==1024)
        val draft=UsageDraft()
        "quokkalokey!".forEach { check(draft.input(it.toString())==null) }
        check(draft.input(" ")=="quokkalokey")
        draft.input("pasted text");check(draft.input(" ")==null)
        draft.block();"fragment".forEach { draft.input(it.toString()) };check(draft.input(" ")==null)
        report.put("bounded_aging_counts",true).put("language_layout_separation",true).put("confirmed_typos_only",true).put("no_paste_or_partial_words",true)
        val text=InputType.TYPE_CLASS_TEXT
        check(UsageLearning.allowed(text,0))
        for(type in listOf(InputType.TYPE_CLASS_NUMBER,InputType.TYPE_CLASS_PHONE,text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            text or InputType.TYPE_TEXT_VARIATION_URI,text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)) check(!UsageLearning.allowed(type,0))
        check(!UsageLearning.allowed(text,EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        report.put("sensitive_field_exclusions",true)

        val directory=File(context.cacheDir,"typing-feature-check").apply { mkdirs() }
        val sandbox=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=directory
            override fun getNoBackupFilesDir()=directory
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences("typing-feature-check-$name",mode)
        }
        val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().commit()
        val prefs=Prefs(sandbox)
        check(!prefs.learning && !prefs.oneTapSpeech)
        val alias="lokey-usage-test-v1"
        var learning=UsageLearning(sandbox,alias)
        learning.awaitIdle()
        var engine:Prediction?=null
        try {
            store.edit().putBoolean("learning",true).putBoolean("autocorrect",true).putString("layout","qwerty").commit()
            repeat(4) { learning.learn(scope,"quokkalokey","quokkaloksy") }
            learning.flush()
            val encrypted=File(directory,"usage-learning.bin")
            check(encrypted.isFile && !String(encrypted.readBytes(),Charsets.ISO_8859_1).contains("quokkalokey"))
            check(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            learning.close()
            var unlocked=false
            learning=UsageLearning(sandbox,alias) { unlocked };learning.awaitIdle()
            check(learning.candidates(scope,"quokk").isEmpty() && encrypted.exists())
            learning.awaitIdle()
            unlocked=true;learning.candidates(scope,"quokk");learning.awaitIdle()
            check(learning.correction(scope,"quokkaloksy")=="quokkalokey")
            report.put("locked_device_defers_decryption",true)
            check(Models.text.none { Models.ready(sandbox,it) })
            Native.release()
            test.runOnMainSync { engine=Prediction(sandbox,prefs,learning) }
            fun request(before:String,allowed:Boolean=true):Prediction.Result {
                val done=CountDownLatch(1)
                var answer:Prediction.Result?=null
                test.runOnMainSync { engine!!.clear();engine!!.request(before,learningAllowed=allowed) { answer=it;done.countDown() } }
                check(done.await(10,TimeUnit.SECONDS))
                test.waitForIdleSync()
                return answer!!
            }
            val baseline=request("I like quokk",false)
            val personal=request("I like quokk")
            check(!personal.neural && "quokkalokey" !in baseline.words && personal.words.first()=="quokkalokey") { "No-model personalization failed: ${personal.words}" }
            check(personal.letters['a'-'a']>baseline.letters['a'-'a'])
            val correction=CountDownLatch(1);var fixed:Prediction.Result?=null
            test.runOnMainSync { engine!!.correct("I like quokkaloksy",true) { fixed=it;correction.countDown() } }
            check(correction.await(10,TimeUnit.SECONDS) && fixed?.correction=="quokkalokey" && fixed?.neural==false)
            report.put("model_free",JSONObject().put("baseline",org.json.JSONArray(baseline.words)).put("learned",org.json.JSONArray(personal.words))
                .put("next_a_before",baseline.letters[0]).put("next_a_after",personal.letters[0]).put("correction",fixed!!.correction))
            report.put("encrypted_round_trip",true)
            val letters=FloatArray(26) { 1f/26 }
            fun neural(before:String,words:List<String>,append:Set<String> = emptySet())=
                Prediction.Result(before,words,letters,"en",true,appendWords=append)
            val boundary=neural("Please inspect the ",listOf("logs","metrics","trace"))
            check(learning.personalize(boundary,scope)===boundary) { "Unigrams replaced next-word context" }
            val ranked=neural("Please inspect quokk",listOf("quokka","quokkas","quokkalokey"))
            check(learning.personalize(ranked,scope).words==ranked.words) { "Frequency displaced neural ordering" }
            val missing=learning.personalize(ranked.copy(words=listOf("quokka","quokkas","quokk")),scope)
            check(missing.words==listOf("quokka","quokkas","quokkalokey"))
            val supported=ranked.copy(wordScores=mapOf("quokka" to -2.0,"quokkas" to -2.5,"quokkalokey" to -3.5))
            val promoted=learning.personalize(supported,scope)
            check(promoted.words.first()=="quokkalokey" && promoted.words.toSet()==ranked.words.toSet() && promoted.letters.contentEquals(learning.personalize(ranked,scope).letters))
            val confident=supported.copy(wordScores=supported.wordScores+("quokkalokey" to -10.0))
            check(learning.personalize(confident,scope).words==ranked.words) { "Frequency overrode strong model evidence" }
            val invalid=supported.copy(wordScores=supported.wordScores+("quokkalokey" to Double.NaN))
            check(learning.personalize(invalid,scope).words==ranked.words)
            val incomplete=supported.copy(wordScores=mapOf("quokkalokey" to -1.0))
            check(learning.personalize(incomplete,scope).words==ranked.words)
            check(learning.personalize(supported.copy(correction="quokka"),scope).words==ranked.words)
            repeat(100) { learning.learn(scope,"quokkalokey") }
            val frequent=supported.copy(wordScores=supported.wordScores+("quokkalokey" to -6.0))
            check(learning.personalize(frequent,scope).words==ranked.words) { "Unbounded frequency overrode context" }
            repeat(3) { learning.learn(scope,"noteworthy") }
            val complete=neural("Please write a note",listOf("about","on","notes"),setOf("about","on"))
            val extended=learning.personalize(complete,scope)
            check(extended.words==listOf("about","on","noteworthy") && extended.appendWords==setOf("about","on")) {
                "A personal completion changed append semantics: $extended"
            }
            check(learning.personalize(complete.copy(wordScores=mapOf("about" to -10.0,"noteworthy" to -1.0)),scope).words==extended.words)
            val capitals=learning.personalize(neural("Please write a NOTE",listOf("about","noteworthy","notes"),setOf("about","noteworthy")),scope)
            check(capitals.words==listOf("about","NOTEWORTHY","notes") && capitals.appendWords==setOf("about")) {
                "An uppercase completion retained an append flag: $capitals"
            }
            val typo=learning.personalize(neural("I like quokkaloksy",listOf("quokka","quokkas")),scope)
            check(typo.correction=="quokkalokey" && typo.words.first()=="quokkalokey")
            check(learning.correction(scope,"quokkalokxy")==null) { "A new typo gained automatic replacement permission" }
            report.put("context_preserved",true).put("completion_append_semantics",true).put("unconfirmed_typo_stays_manual",true)
                .put("bounded_context_promotion",true).put("promotion_preserves_top_three",true)
            test.runOnMainSync { engine!!.close();engine=null }
            learning.close()
            val corrupt=encrypted.readBytes();corrupt[corrupt.lastIndex]=(corrupt.last().toInt() xor 1).toByte();encrypted.writeBytes(corrupt)
            learning=UsageLearning(sandbox,alias);learning.awaitIdle()
            check(learning.candidates(scope,"quokk").isEmpty() && !encrypted.exists())
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            repeat(3) { learning.learn(scope,"quokkalokey","quokkaloksy") }
            learning.erase();learning.flush()
            SystemClock.sleep(2100);learning.awaitIdle()
            check(!encrypted.exists() && learning.candidates(scope,"quokk").isEmpty())
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            store.edit().putBoolean("learning",false).commit()
            learning.erase();learning.learn(scope,"quokkalokey");learning.flush()
            check(!encrypted.exists() && learning.candidates(scope,"quokk").isEmpty())
            report.put("tamper_rejected",true).put("erase_pending_write_and_key",true).put("off_by_default",true)

            test.runOnMainSync {
                var starts=0;var stops=0;var pauses=0;var resumes=0
                val view=KeyboardView(sandbox,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) {};override fun delete() {};override fun enter() {};override fun cursor(delta:Int) {}
                    override fun voice(start:Boolean):Boolean { if(start) starts++ else stops++;return true }
                    override fun pauseVoice(paused:Boolean):Boolean { if(paused) pauses++ else resumes++;return true }
                    override fun cancelVoice() {};override fun nextKeyboard() {};override fun candidate(word:String) {};override fun feedback(key:String,down:Boolean) {}
                })
                fun layout() {
                    view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight)
                    view.editor(false,"Done",false)
                }
                fun click(id:Int) {
                    val rect=Rect();view.accessibilityNodeProvider.createAccessibilityNodeInfo(id)!!.getBoundsInParent(rect)
                    val now=SystemClock.uptimeMillis()
                    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                        val event=MotionEvent.obtain(now,now+20,action,rect.exactCenterX(),rect.exactCenterY(),0)
                        view.onTouchEvent(event);event.recycle()
                    }
                }
                fun snapshot(name:String) {
                    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null),"speech-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                layout();check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(3000)==null)
                prefs.oneTapSpeech=true;layout()
                click(3000);check(starts==1 && view.recordingLocked && view.voiceState=="Listening")
                repeat(32) { view.audioLevel(.15f+(it%5)*.12f,it*125L) };snapshot("recording")
                click(3001);check(pauses==1 && view.recordingPaused && view.recordingLocked)
                snapshot("paused")
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(3001)!!.contentDescription==sandbox.tr("Resume recording"))
                view.audioLevel(.5f,1000)
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap));bitmap.recycle()
                check(view.accessibilityNodeProvider.performAction(3001,AccessibilityNodeInfo.ACTION_CLICK,null))
                check(resumes==1 && !view.recordingPaused)
                click(3000);check(stops==1 && !view.recordingLocked && !view.recordingPaused)
                view.voiceState="";view.speechAllowed=false
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(3000)==null)
                view.cancelTouches()
            }
            report.put("one_tap_locked_recording",true).put("pause_resume_stop",true).put("accessible_controls",true)
            return report
        } finally {
            test.runOnMainSync { engine?.close() }
            learning.erase();learning.close();store.edit().clear().commit();directory.deleteRecursively()
        }
    }
}
