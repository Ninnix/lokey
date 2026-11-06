package org.lokey.app

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KeyboardTest : Instrumentation() {
    private var mode = "all"
    private var speechModel:String?=null
    private var accelerator=0
    private var speechAccelerator=0
    private val activities=java.util.concurrent.ConcurrentHashMap<String,android.app.Activity>()
    override fun startActivitySync(intent:android.content.Intent):android.app.Activity {
        val info=targetContext.packageManager.resolveActivity(intent,0)?.activityInfo ?: error("No activity for $intent")
        check(info.packageName==targetContext.packageName)
        val previous=activities[info.name]
        if(info.exported) {
            val uri=intent.toUri(android.content.Intent.URI_INTENT_SCHEME)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("am start $uri")).bufferedReader().use { it.readText() }
        } else targetContext.startActivity(intent)
        val fresh=previous==null || previous.isDestroyed || previous.isFinishing || intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK!=0
        val until=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<until) {
            val activity=activities[info.name]
            if(activity!=null && (!fresh || activity!==previous)) {
                runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                return activity
            }
            SystemClock.sleep(30)
        }
        error("Activity did not open: ${info.name}")
    }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        arguments?.getString("layout")?.let { Prefs(targetContext).layout=it }
        speechModel=arguments?.getString("speech_model")
        accelerator=when(arguments?.getString("accelerator")) { "cpu" -> 1;"hexagon" -> 2;else -> 0 }
        speechAccelerator=when(arguments?.getString("speech_accelerator")) { "cpu" -> 1;"decoder" -> 2;"hexagon" -> 3;else -> 0 }
        mode=arguments?.getString("mode") ?: "all";start()
    }
    override fun onStart() {
        (targetContext.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity:android.app.Activity,state:Bundle?) { activities[activity.javaClass.name]=activity }
            override fun onActivityStarted(activity:android.app.Activity) { }
            override fun onActivityResumed(activity:android.app.Activity) { activities[activity.javaClass.name]=activity }
            override fun onActivityPaused(activity:android.app.Activity) { }
            override fun onActivityStopped(activity:android.app.Activity) { }
            override fun onActivitySaveInstanceState(activity:android.app.Activity,state:Bundle) { }
            override fun onActivityDestroyed(activity:android.app.Activity) { }
        })
        val report = JSONObject()
        try {
            if (mode == "all" || mode == "launch") report.put("launch", launchTest())
            if (mode == "all" || mode == "cursor") report.put("cursor", cursorTest())
            if (mode == "all" || mode == "gestures") report.put("gestures", gestures())
            if (mode == "all" || mode == "models") report.put("models", benchmark())
            if(mode=="recordings") report.put("recordings",recordingsTest())
            if (mode == "all" || mode == "feedback") report.put("feedback", feedbackTest())
            if(mode=="feedbackready") report.put("feedbackready",feedbackTest())
            if (mode == "all" || mode == "multilingual") report.put("multilingual", multilingualTest())
            if (mode == "all" || mode == "correction") report.put("correction", correctionTest())
            if (mode == "all" || mode == "speed") report.put("speed", speedTest())
            if(mode=="speedui") report.put("speedui",speedUiTest())
            if(mode=="layouts") report.put("layouts",LayoutChecks.run(this,targetContext))
            if(mode=="intl") report.put("intl",LayoutChecks.intl(this,targetContext))
            if(mode=="downloads") report.put("downloads",DownloadChecks.run(this,targetContext))
            if(mode=="layoutui") report.put("layoutui",LayoutChecks.live(this,targetContext))
            if(mode=="insets") report.put("insets",InsetsChecks.run(this,targetContext))
            if(mode=="layoutpicker") report.put("layoutpicker",LayoutChecks.picker(this,targetContext))
            if(mode=="speech") report.put("speech",SpeechChecks.run(this,targetContext,speechModel,speechAccelerator))
            if(mode=="setup") report.put("setup",SetupChecks.run(this,targetContext))
            if(mode=="languagemap") report.put("languagemap",LanguageMapChecks.run(targetContext))
            if(mode=="localization") report.put("localization",LocaleChecks.run(this,targetContext))
            if(mode=="privacy") report.put("privacy",PrivacyChecks.run(this,targetContext))
            if (mode == "all" || mode == "interaction") report.put("interaction", interactionTest())
            if (mode == "all" || mode == "flow") report.put("flow", flowTest())
            if (mode == "imeflow") report.put("imeflow", imeFlowTest())
            if (mode == "sentence") report.put("sentence", sentenceTest())
            if(mode=="completion") report.put("completion",completionTest())
            if(mode=="probability") report.put("probability",PredictionChecks.run(this,targetContext))
            if(mode=="probabilitylanguages") report.put("probabilitylanguages",PredictionChecks.languages(targetContext))
            if(mode=="translation") report.put("translation",translationTest())
            if(mode=="translationmodels") report.put("translationmodels",TranslationModelChecks.run(this,targetContext))
            if(mode=="translationui") report.put("translationui",translationUiTest())
            if(mode=="tools") report.put("tools",toolsTest())
            if (mode == "themes") report.put("themes", themeTest())
            if(mode=="customization") report.put("customization",CustomizationChecks.run(this,targetContext))
            if(mode=="customizationui") report.put("customizationui",CustomizationUiChecks.run(this,targetContext))
            if(mode=="modelui") report.put("modelui",CustomizationUiChecks.models(this,targetContext))
            if(mode=="homespeed") report.put("homespeed",HomeSpeedChecks.run(this,targetContext))
            if(mode=="automatic") report.put("automatic",AutomaticChecks.run(this,targetContext))
            report.put("passed", true)
            targetContext.getSharedPreferences("lokey",android.content.Context.MODE_PRIVATE).edit().commit()
            File(targetContext.filesDir, "test-results.json").writeText(report.toString(2))
            finish(RESULT_OK, Bundle().apply { putString("results", report.toString()) })
        } catch (error: Throwable) {
            targetContext.getSharedPreferences("lokey",android.content.Context.MODE_PRIVATE).edit().commit()
            report.put("passed", false).put("error", error.stackTraceToString())
            File(targetContext.filesDir, "test-results.json").writeText(report.toString(2))
            finish(RESULT_CANCELED, Bundle().apply { putString("results", report.toString()) })
        }
    }
    private fun awaitFocus(activity:android.app.Activity) {
        runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val deadline=SystemClock.uptimeMillis()+8000
        while(SystemClock.uptimeMillis()<deadline) {
            var ready=false
            runOnMainSync { ready=activity.hasWindowFocus() && activity.window.decorView.isShown && !activity.isFinishing }
            if(ready) return
            SystemClock.sleep(50)
        }
        error("Lokey screen did not become ready")
    }
    private fun launchTest(): JSONObject {
        val activity = startActivitySync(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .setClass(targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitForIdleSync()
        awaitFocus(activity)
        runOnMainSync { activity.finish() }
        return JSONObject().put("launcher_home", true)
    }
    @android.annotation.TargetApi(31)
    private fun cursorTest(): JSONObject {
        val result = JSONObject()
        val activity = startActivitySync(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .setClass(targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            runOnMainSync {
                val root = android.widget.LinearLayout(activity)
                val editor = android.widget.EditText(activity).apply { id = View.generateViewId(); showSoftInputOnFocus = false }
                val other = android.widget.Button(activity).apply { id = View.generateViewId(); text = "Other control"; isFocusableInTouchMode = true }
                editor.nextFocusLeftId = other.id; editor.nextFocusRightId = other.id
                root.addView(editor); root.addView(other); activity.setContentView(root); editor.requestFocus()
                val base = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                var arrows = 0
                val connection = object : android.view.inputmethod.InputConnectionWrapper(base, false) {
                    override fun sendKeyEvent(event: android.view.KeyEvent): Boolean { arrows++; return super.sendKeyEvent(event) }
                }
                fun move(delta: Int, source: android.view.inputmethod.InputConnection = connection) = Cursor.move(source, delta, editor.selectionStart, editor.selectionEnd)
                fun checkPosition(position: Int) {
                    check(editor.selectionStart == position && editor.selectionEnd == position) { "Cursor is ${editor.selectionStart}, expected $position" }
                    check(editor.hasFocus() && !other.hasFocus()) { "Cursor movement escaped the editor" }
                }
                editor.setText("abc"); editor.setSelection(0)
                repeat(8) { move(-80); checkPosition(0) }
                move(80); checkPosition(3)
                repeat(8) { move(80); checkPosition(3) }
                move(-1); checkPosition(2)
                result.put("both_boundaries_keep_focus", true)
                editor.setText(""); editor.setSelection(0)
                move(-80); move(80); checkPosition(0)
                result.put("empty_editor", true)
                editor.setText("a😀b\ncd"); editor.setSelection(1)
                move(1); checkPosition(3)
                move(-1); checkPosition(1)
                move(80); checkPosition(editor.length())
                move(-80); checkPosition(0)
                result.put("unicode_and_multiline", true)
                editor.setSelection(1, 3); move(-1); checkPosition(1)
                editor.setSelection(3, 1); move(1); checkPosition(3)
                result.put("selection_collapse", true)
                editor.setText("x".repeat(5000)); editor.setSelection(2532)
                move(-3); checkPosition(2529)
                move(80); checkPosition(2609)
                result.put("surrounding_text_offsets", true)
                val extracted = object : android.view.inputmethod.InputConnectionWrapper(connection, false) {
                    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText? = null
                }
                move(-3, extracted); checkPosition(2606)
                editor.setSelection(5000); move(80, extracted); checkPosition(5000)
                result.put("extracted_text_fallback", true)
                val legacy = object : android.view.inputmethod.InputConnectionWrapper(extracted, false) {
                    override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest, flags: Int): android.view.inputmethod.ExtractedText? = null
                }
                editor.setText("a😀b"); editor.setSelection(1)
                move(1, legacy); checkPosition(3)
                move(80, legacy); checkPosition(4)
                move(80, legacy); checkPosition(4)
                move(-80, legacy); checkPosition(0)
                result.put("legacy_fallback", true)
                val unsupported = object : android.view.inputmethod.InputConnectionWrapper(legacy, false) {
                    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = null
                    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? = null
                }
                check(move(-80, unsupported) == null); checkPosition(0)
                val rejecting = object : android.view.inputmethod.InputConnectionWrapper(connection, false) {
                    override fun setSelection(start: Int, end: Int) = false
                }
                check(move(1, rejecting) == null); checkPosition(0)
                check(arrows == 0) { "Cursor navigation dispatched arrow-key events" }
                result.put("unsupported_editors_stay_put", true)
            }
        } finally { runOnMainSync { activity.finish() } }
        return result
    }
    private fun recordingsTest():JSONObject {
        check(Switches.profiles.size==8)
        val fresh=object:android.content.ContextWrapper(targetContext) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("switch-default-test",mode)
        }
        try { check(Prefs(fresh).switchId=="cherry_red") }
        finally { targetContext.deleteSharedPreferences("switch-default-test") }
        val report=JSONArray()
        val hashes=HashSet<String>()
        for(profile in Switches.profiles) {
            val files=targetContext.assets.list("switches/${profile.id}")!!.toList()
            check(files.size==profile.samples)
            val first=files.first { it.substringBeforeLast('.')=="a-down" }
            val bytes=targetContext.assets.open("switches/${profile.id}/$first").use { it.readBytes() }
            if(profile.id=="razer_green") for(file in files) {
                val wav=targetContext.assets.open("switches/${profile.id}/$file").use { it.readBytes() }
                check(String(wav,0,4)=="RIFF" && String(wav,8,4)=="WAVE")
                val data=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                var at=12;var valid=false
                while(at+24<=wav.size) {
                    val size=data.getInt(at+4)
                    if(String(wav,at,4)=="fmt ") { valid=data.getShort(at+8).toInt()==1 && data.getShort(at+22).toInt()==16;break }
                    check(size>=0);at+=8+size+(size and 1)
                }
                check(valid) { "Razer Green must use PCM16 for SoundPool compatibility" }
            }
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hashes.add(digest)) { "${profile.name} duplicates another switch recording" }
            report.put(JSONObject().put("id",profile.id).put("samples",profile.samples).put("sha256",digest))
        }
        return JSONObject().put("eight_distinct_switches",true).put("all_bundled",true).put("fresh_install_default","cherry_red").put("profiles",report)
    }
    private fun feedbackTest(): JSONObject {
        val prefs = Prefs(targetContext)
        val original = prefs.switchId
        val enabled = prefs.soundEnabled
        val strength = prefs.vibration
        val returnHaptics = prefs.returnHaptics
        var feedback: Feedback? = null
        val available=Switches.profiles
        runOnMainSync { feedback = Feedback(targetContext, prefs); prefs.soundEnabled = true; prefs.vibration = 45; prefs.returnHaptics = false }
        try {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (available.any { feedback!!.loadedSamples(it.id) < it.samples } && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(50)
            val profiles = JSONArray()
            for (profile in available) {
                val count = feedback!!.loadedSamples(profile.id)
                check(count >= profile.samples) { "${profile.name}: only $count samples decoded" }
                prefs.switchId = profile.id
                var stream = 0
                runOnMainSync { stream = feedback!!.tap("a", true) }
                val audible = targetContext.getSystemService(android.media.AudioManager::class.java).ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL && prefs.volume > 0
                if (audible) check(stream > 0) { "${profile.name}: audio stream did not start" }
                SystemClock.sleep(70)
                runOnMainSync { feedback!!.tap("a", false) }
                SystemClock.sleep(150)
                profiles.put(JSONObject().put("id", profile.id).put("decoded_samples", count).put("press_stream", stream))
            }
            val vibrator = targetContext.getSystemService(android.os.Vibrator::class.java)
            val primitives = if (android.os.Build.VERSION.SDK_INT >= 31)
                vibrator.arePrimitivesSupported(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    android.os.VibrationEffect.Composition.PRIMITIVE_TICK, android.os.VibrationEffect.Composition.PRIMITIVE_CLICK).toList() else emptyList()
            return JSONObject().put("profiles", profiles).put("low_tick_tick_click_supported", JSONArray(primitives))
                .put("system_taps", feedback!!.usesSystemTaps)
                .put("amplitude_control", vibrator.hasAmplitudeControl())
        } finally {
            prefs.switchId = original; prefs.soundEnabled = enabled; prefs.vibration = strength; prefs.returnHaptics = returnHaptics
            runOnMainSync { feedback?.close() }
        }
    }
    private fun gestures(): JSONObject {
        val prefs = Prefs(targetContext)
        val height = prefs.height
        val adaptive = prefs.adaptive
        val language=prefs.setupLanguage;prefs.setupLanguage="Italian"
        try {
            val result = JSONObject()
            val typed = StringBuilder()
            var cursor = 0
            val voice = ArrayList<Boolean>()
            var deletes = 0
            var cancellations = 0
            var allowVoice = true
            var keys: KeyboardView? = null
            runOnMainSync {
                prefs.height = 100; prefs.adaptive = true
                keys = KeyboardView(targetContext, prefs, object : KeyboardView.Listener {
                    override fun text(text: String) { typed.append(text) }
                    override fun delete() { deletes++ }
                    override fun enter() { typed.append('\n') }
                    override fun cursor(delta: Int) { cursor += delta }
                    override fun voice(start: Boolean): Boolean { voice.add(start); return !start || allowVoice }
                    override fun cancelVoice() { cancellations++ }
                    override fun nextKeyboard() { }
                    override fun candidate(word: String) { }
                    override fun feedback(key: String, down: Boolean) { }
                }).apply {
                    measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    layout(0, 0, measuredWidth, measuredHeight)
                }
            }
            val view = keys!!
            val density = targetContext.resources.displayMetrics.density
            val unit = view.height / 293f
            fun touch(action: Int, x: Float, y: Float) {
                runOnMainSync { val e = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0); view.onTouchEvent(e); e.recycle() }
            }
            fun tap(x: Float, y: Float) { touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y) }
            tap(54f, 70 * unit)
            tap(54f, 116 * unit)
            check(typed.toString() == "1q") { "Number row or QWERTY failed: $typed" }
            result.put("typing", true)
            val q = android.graphics.Rect()
            runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(10)!!.getBoundsInParent(q) }
            tap(q.right + density, q.centerY().toFloat())
            check(typed.toString() == "1qq") { "Key gutters dropped a tap: $typed" }
            result.put("key_gutters", true)
            touch(MotionEvent.ACTION_DOWN, 54f, 70 * unit); SystemClock.sleep(450); touch(MotionEvent.ACTION_UP, 54f, 70 * unit)
            check(typed.endsWith("!")) { "US alternate failed: $typed" }; result.put("US_alternate", true)
            fun bounds(id: Int): android.graphics.Rect {
                val rect = android.graphics.Rect()
                runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(id)!!.getBoundsInParent(rect) }
                return rect
            }
            fun holdKey(id: Int, slide: Float = 0f) {
                val rect = bounds(id)
                val x = rect.exactCenterX(); val y = rect.exactCenterY()
                touch(MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(450)
                touch(MotionEvent.ACTION_MOVE, x + slide * density, y)
                touch(MotionEvent.ACTION_UP, x + slide * density, y)
            }
            runOnMainSync {
                "!@#$%^&*()".forEachIndexed { i, symbol ->
                    check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(i)!!.contentDescription.endsWith("hold for $symbol"))
                }
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(12)!!.contentDescription.toString() == "e, hold for è")
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(10)!!.contentDescription.toString() == "q")
            }
            holdKey(20); check(typed.endsWith("à")) { "A did not default to à: $typed" }
            holdKey(12); check(typed.endsWith("è")) { "E did not default to è: $typed" }
            holdKey(12, 41f); check(typed.endsWith("é")) { "E accent slide did not select é: $typed" }
            holdKey(35); check(typed.endsWith("ñ")) { "N did not offer ñ: $typed" }
            holdKey(32); check(typed.endsWith("ç")) { "C did not offer ç: $typed" }
            result.put("letter_accents", true)
            val shift = bounds(29)
            tap(shift.exactCenterX(), shift.exactCenterY())
            holdKey(12); check(typed.endsWith("È")) { "Shift did not capitalize the accent: $typed" }
            tap(shift.exactCenterX(), shift.exactCenterY())
            holdKey(21); check(typed.endsWith("ẞ")) { "Uppercase sharp S was not preserved: $typed" }
            result.put("uppercase_accents", true)
            fun tapKey(id: Int) { val r = bounds(id); tap(r.exactCenterX(), r.exactCenterY()) }
            tapKey(29); tapKey(20); check(typed.endsWith("A"))
            tapKey(20); check(typed.endsWith("Aa")) { "Single-shift did not reset after one letter" }
            tapKey(29); tapKey(29)
            tapKey(20); tapKey(12); check(typed.endsWith("AE")) { "Caps lock did not persist" }
            holdKey(12); check(typed.endsWith("È")) { "Caps lock did not capitalize an accent" }
            tapKey(29); tapKey(20); check(typed.endsWith("Èa")) { "Third Shift tap did not turn caps off" }
            result.put("three_shift_modes", true)

            holdKey(10); check(typed.endsWith("q")) { "A letter without accents inserted a symbol: $typed" }
            tap(54f, 264 * unit)
            holdKey(10); check(typed.endsWith("!")) { "Symbol page changed its punctuation: $typed" }
            tap(54f, 264 * unit)
            result.put("symbol_page_preserved", true)
            val spaceX = view.width * 0.49f
            touch(MotionEvent.ACTION_DOWN, spaceX, 264 * unit)
            touch(MotionEvent.ACTION_MOVE, spaceX + 40 * density, 264 * unit)
            touch(MotionEvent.ACTION_UP, spaceX + 40 * density, 264 * unit)
            check(cursor >= 3 && voice.isEmpty()) { "Space cursor gesture started voice or did not move: $cursor $voice" }
            result.put("space_cursor", true)
            val oldCursor = cursor
            touch(MotionEvent.ACTION_DOWN, spaceX, 264 * unit); SystemClock.sleep(400)
            touch(MotionEvent.ACTION_MOVE, spaceX + 27 * density, 264 * unit)
            touch(MotionEvent.ACTION_UP, spaceX + 27 * density, 264 * unit)
            check(cursor > oldCursor && voice.isEmpty()) { "Holding space did not stay in cursor mode" }
            result.put("space_hold_cursor", true)
            val enter = bounds(42)
            val ex = enter.exactCenterX(); val ey = enter.exactCenterY()
            tap(ex, ey); check(typed.endsWith("\n") && voice.isEmpty())
            result.put("enter_tap", true)
            val beforeVoice = typed.toString()
            touch(MotionEvent.ACTION_DOWN, ex, ey); SystemClock.sleep(400)
            check(view.voiceState == "Listening" && voice == listOf(true))
            touch(MotionEvent.ACTION_UP, ex, ey)
            check(voice == listOf(true, false) && typed.toString() == beforeVoice)
            runOnMainSync { view.voiceState = "" }; voice.clear()
            result.put("enter_hold_voice", true)
            touch(MotionEvent.ACTION_DOWN, ex, ey); SystemClock.sleep(400)
            touch(MotionEvent.ACTION_MOVE, ex, ey - 60 * density)
            touch(MotionEvent.ACTION_UP, ex, ey - 60 * density)
            check(view.recordingLocked && view.voiceState == "Listening" && voice == listOf(true))
            tap(ex, ey)
            check(!view.recordingLocked && voice == listOf(true, false) && typed.toString() == beforeVoice)
            tap(ex, ey)
            check(typed.toString() == beforeVoice && voice == listOf(true, false)) { "Busy Enter submitted text" }
            runOnMainSync { view.voiceState = "" }; voice.clear()
            result.put("recording_lock_stop", true)
            touch(MotionEvent.ACTION_DOWN, ex, ey)
            touch(MotionEvent.ACTION_MOVE, ex, ey - 60 * density)
            touch(MotionEvent.ACTION_CANCEL, ex, ey - 60 * density)
            check(view.recordingLocked && voice == listOf(true)) { "Locked recording stopped on pointer cancellation" }
            tap(view.width - 30 * density, 24 * unit)
            check(cancellations == 1 && view.voiceState.isEmpty() && !view.recordingLocked && typed.toString() == beforeVoice)
            voice.clear()
            result.put("recording_lock_cancel", true)
            touch(MotionEvent.ACTION_DOWN, ex, ey); SystemClock.sleep(400)
            touch(MotionEvent.ACTION_CANCEL, ex, ey)
            check(cancellations == 2 && view.voiceState.isEmpty() && voice == listOf(true))
            voice.clear()
            touch(MotionEvent.ACTION_DOWN, ex, ey); SystemClock.sleep(400)
            touch(MotionEvent.ACTION_MOVE, ex - 80 * density, ey)
            touch(MotionEvent.ACTION_UP, ex - 80 * density, ey)
            check(cancellations == 3 && view.voiceState.isEmpty() && typed.toString() == beforeVoice)
            voice.clear()
            result.put("recording_cancel_gestures", true)
            allowVoice = false
            touch(MotionEvent.ACTION_DOWN, ex, ey); SystemClock.sleep(400); touch(MotionEvent.ACTION_UP, ex, ey)
            check(view.voiceState.isEmpty() && typed.toString() == beforeVoice) { "Rejected recording inserted Enter" }
            allowVoice = true; voice.clear()
            result.put("recording_rejection", true)
            val number = bounds(0); val letter = bounds(10)
            check(kotlin.math.abs(number.height() - letter.height()) <= 1) { "Number key height differs from letters" }
            check(number.top == (48 * unit).toInt()) { "Extra toolbar space remains above the keys" }
            result.put("equal_number_keys", true)
            val period = view.width * 0.797f
            touch(MotionEvent.ACTION_DOWN, period, 264 * unit); SystemClock.sleep(450)
            touch(MotionEvent.ACTION_MOVE, view.width - 200 * density, 260 * unit)
            touch(MotionEvent.ACTION_UP, view.width - 200 * density, 260 * unit)
            check(typed.endsWith("!")) { "Punctuation slide failed: $typed" }; result.put("punctuation_slide", true)
            touch(MotionEvent.ACTION_DOWN, period, 264 * unit); SystemClock.sleep(450)
            touch(MotionEvent.ACTION_MOVE, period + density, 264 * unit)
            touch(MotionEvent.ACTION_UP, period + density, 264 * unit)
            check(typed.endsWith("?")) { "Punctuation hold did not default to ?: $typed" }
            result.put("stationary_punctuation", true)
            val dot = bounds(41)
            tap(dot.exactCenterX(), dot.exactCenterY()); check(typed.endsWith("."))
            fun flick(id: Int, dx: Float, dy: Float) {
                val r = bounds(id); val before = typed.length
                touch(MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY())
                touch(MotionEvent.ACTION_UP, r.exactCenterX() + dx * density, r.exactCenterY() + dy * density)
                check(typed.length == before + 1) { "Flick emitted more than one character" }
            }
            flick(41, 18f, 0f); check(typed.endsWith("?"))
            flick(41, -18f, 0f); check(typed.endsWith("!"))
            flick(41, 0f, -18f); check(typed.endsWith(","))
            flick(12, 0f, -18f); check(typed.endsWith("è"))
            flick(0, 0f, -18f); check(typed.endsWith("!"))
            result.put("instant_secondary_flicks", true)

            touch(MotionEvent.ACTION_DOWN, view.width - 50f, 212 * unit); SystemClock.sleep(480); touch(MotionEvent.ACTION_UP, view.width - 50f, 212 * unit)
            check(deletes >= 3) { "Delete repeat failed: $deletes" }; result.put("delete_repeat", true)
            val before = typed.toString()
            touch(MotionEvent.ACTION_DOWN, 54f, 116 * unit); touch(MotionEvent.ACTION_CANCEL, 54f, 116 * unit)
            check(typed.toString() == before); result.put("touch_cancel", true)
            val original = android.graphics.Rect()
            val held = android.graphics.Rect()
            val resized = android.graphics.Rect()
            runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(12)!!.getBoundsInParent(original) }
            touch(MotionEvent.ACTION_DOWN, 54f, 116 * unit)
            runOnMainSync {
                view.prediction(listOf("hello"), FloatArray(26).apply { this[4] = 1f }, true)
                view.accessibilityNodeProvider.createAccessibilityNodeInfo(12)!!.getBoundsInParent(held)
            }
            check(held == original) { "Adaptive geometry moved during a touch" }
            touch(MotionEvent.ACTION_UP, 54f, 116 * unit)
            runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(12)!!.getBoundsInParent(resized) }
            check(resized.width() > original.width()) { "Likely letter did not grow" }
            result.put("adaptive_layout", true)
            runOnMainSync {
                val width = targetContext.resources.displayMetrics.widthPixels
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                view.editor(false, "Done", false)
                view.prediction(emptyList(), FloatArray(26), false)
            }
            fun capture(name: String) {
                runOnMainSync {
                    val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    File(targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            capture("compact-keyboard.png")
            tapKey(29); capture("compact-shift-once.png")
            tapKey(29); capture("compact-caps-lock.png")
            tapKey(29)

            val a = bounds(20)
            touch(MotionEvent.ACTION_DOWN, a.exactCenterX(), a.exactCenterY()); SystemClock.sleep(450)
            capture("compact-accents.png")
            touch(MotionEvent.ACTION_UP, a.exactCenterX(), a.exactCenterY())
            val record = bounds(42)
            touch(MotionEvent.ACTION_DOWN, record.exactCenterX(), record.exactCenterY()); SystemClock.sleep(400)
            touch(MotionEvent.ACTION_MOVE, record.exactCenterX(), record.exactCenterY() - 60 * density)
            touch(MotionEvent.ACTION_UP, record.exactCenterX(), record.exactCenterY() - 60 * density)
            runOnMainSync { repeat(60) { i -> view.audioLevel((i % 11 + 1) / 180f, i * 125L) } }
            capture("compact-recording.png")
            tap(record.exactCenterX(), record.exactCenterY())
            runOnMainSync { view.voiceState = "" }
            return result
        } finally {
            prefs.height = height; prefs.adaptive = adaptive;prefs.setupLanguage=language
        }
    }
    private fun imeFlowTest(): JSONObject {
        val prefs = Prefs(targetContext)
        val old = prefs.flow
        prefs.flow = true
        val activity = startActivitySync(android.content.Intent().setClass(targetContext,MainActivity::class.java).putExtra("page","lab")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val result = JSONObject()
        fun editor(view: View): android.widget.EditText? {
            if(view is android.widget.EditText) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
            return null
        }
        lateinit var input: android.widget.EditText
        fun guard() {
            var focused=false
            runOnMainSync { focused=activity.hasWindowFocus() && !activity.isFinishing }
            check(focused) { "Lokey lost focus; stopping injection" }
        }
        fun nodes(): Map<String,android.graphics.Rect> {
            if(android.os.Build.VERSION.SDK_INT>=34) uiAutomation.clearCache()
            val out=HashMap<String,android.graphics.Rect>()
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo) {
                val name=node.contentDescription?.toString()?.substringBefore(',')?.lowercase().orEmpty()
                if(name.isNotEmpty() && node.packageName=="org.lokey.app") {
                    val r=android.graphics.Rect(); node.getBoundsInScreen(r); out[name]=r
                }
                for(i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
            }
            for(window in uiAutomation.windows) if(window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { walk(it) }
            return out
        }
        val info=uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        uiAutomation.serviceInfo=info
        fun inject(action:Int,x:Float,y:Float,down:Long) {
            guard()
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        fun swipe(word:String) {
            val keys=nodes()
            val path=word.filterIndexed { i,c -> i==0 || c!=word[i-1] }.map { keys[it.toString()] ?: error("Missing keyboard key $it") }
            val down=SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN,path[0].exactCenterX(),path[0].exactCenterY(),down)
            for(i in 1..path.lastIndex) for(j in 1..8) {
                val a=path[i-1];val b=path[i];val t=j/8f
                inject(MotionEvent.ACTION_MOVE,a.exactCenterX()+(b.exactCenterX()-a.exactCenterX())*t,a.exactCenterY()+(b.exactCenterY()-a.exactCenterY())*t,down)
                SystemClock.sleep(6)
            }
            inject(MotionEvent.ACTION_UP,path.last().exactCenterX(),path.last().exactCenterY(),down)
        }
        fun value():String { var text="";runOnMainSync { text=input.text.toString() };return text }
        fun await(expected:String) { repeat(60) { if(value()==expected) return;SystemClock.sleep(50) };error("IME produced '${value()}', expected '$expected'") }
        try {
            awaitFocus(activity)
            runOnMainSync { input=editor(activity.window.decorView)!!;input.requestFocus();activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input,0) }
            val ready=SystemClock.uptimeMillis()+8000
            while(nodes()["h"]==null && SystemClock.uptimeMillis()<ready) {
                guard()
                runOnMainSync { activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input,0) }
                SystemClock.sleep(100)
            }
            check(nodes()["h"]!=null) { "Lokey IME did not become ready" }
            // Accessibility nodes can precede the IME's final on-screen bounds.
            SystemClock.sleep(500)
            swipe("hello");await("Hello ")
            result.put("real_swipe_commits_word",true)
            swipe("world");await("Hello world ")
            result.put("consecutive_swipes",true)
            val keys=nodes();val period=keys.entries.first { it.key=="." }.value;val down=SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN,period.exactCenterX(),period.exactCenterY(),down)
            inject(MotionEvent.ACTION_UP,period.exactCenterX(),period.exactCenterY(),down)
            await("Hello world.")
            result.put("punctuation_removes_auto_space",true)
            runOnMainSync { input.setText("Ciao ci vediamo ");input.setSelection(input.length()) }
            SystemClock.sleep(800)
            swipe("domani");await("Ciao ci vediamo domani ")
            result.put("italian_swipe",true)
            runOnMainSync { input.setText("") }; SystemClock.sleep(200)
            swipe("hello"); swipe("world")
            val q=nodes()["q"]!!;val press=SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN,q.exactCenterX(),q.exactCenterY(),press)
            inject(MotionEvent.ACTION_UP,q.exactCenterX(),q.exactCenterY(),press)
            await("Hello world q")
            result.put("fast_swipes_and_following_tap_preserved",true)
            fun choose(before: String, word: String, expected: String) {
                runOnMainSync { input.setText(before); input.setSelection(input.length()) }
                SystemClock.sleep(250)
                var rect:android.graphics.Rect?=null
                val deadline=SystemClock.uptimeMillis()+5000
                while(rect==null && SystemClock.uptimeMillis()<deadline) { rect=nodes()["suggestion: $word"];if(rect==null) SystemClock.sleep(50) }
                check(rect!=null) { "Missing suggestion $word after $before" }
                val down=SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN,rect!!.exactCenterX(),rect!!.exactCenterY(),down)
                inject(MotionEvent.ACTION_UP,rect!!.exactCenterX(),rect!!.exactCenterY(),down)
                await(expected)
            }
            choose("Ciao come stai","?","Ciao come stai? ")
            result.put("question_punctuation_preserves_last_word",true)
            choose("Ciao come","stai","Ciao come stai ")
            result.put("next_word_appends_to_completed_word",true)
            val oldHeight=prefs.height
            val originalText=value()
            val density=targetContext.resources.displayMetrics.density
            fun resizeTo(target:Int) {
                val keys=nodes();val number=keys["1"]!!;val q=keys["q"]!!;val zero=keys["0"]!!
                val top=2f*number.top-q.top;val x=(number.left+zero.right)/2f;val y=top+5*density
                val end=y+(prefs.height-target)*293*density/100f
                val down=SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN,x,y,down);SystemClock.sleep(450)
                inject(MotionEvent.ACTION_MOVE,x,end,down);SystemClock.sleep(120)
                inject(MotionEvent.ACTION_UP,x,end,down);SystemClock.sleep(150)
                check(prefs.height==target) { "IME height stayed ${prefs.height}, expected $target" }
            }
            try { resizeTo(if(oldHeight<110) 115 else 95);resizeTo(oldHeight);check(value()==originalText) }
            finally { prefs.height=oldHeight }
            result.put("actual_ime_window_resize",true)
        } finally { prefs.flow=old;runOnMainSync { activity.finish() } }
        return result
    }
    private fun themeTest(): JSONObject {
        val prefs=Prefs(targetContext)
        val oldTheme=prefs.keyboardTheme;val oldLight=prefs.appLight;val oldBg=prefs.themeBackground;val oldKeys=prefs.themeKeys;val oldAccent=prefs.themeAccent
        val oldVersion=prefs.themeImageVersion;val oldPicture=prefs.themePicture;val oldKey=prefs.keyColor("a");val oldUpload=prefs.userImageChosen;val oldSwitch=prefs.switchId;val oldSound=prefs.soundEnabled
        val oldDirty=prefs.designDirty;val oldActive=prefs.activeDesign
        val upload=KeyboardTheme.uploadFile(targetContext);val oldUploadBytes=if(upload.exists()) upload.readBytes() else null
        prefs.userImageChosen=false
        val themeStore=targetContext.getSharedPreferences("lokey",android.content.Context.MODE_PRIVATE)
        val oldOverrides=themeStore.all.filterKeys { it.startsWith("key_color.") || it.startsWith("row_color.") }
        val image=KeyboardTheme.imageFile(targetContext);val backup=File(targetContext.cacheDir,"theme-image-backup.png")
        if(image.exists()) image.copyTo(backup,true)
        val result=JSONObject()
        val activity=startActivitySync(android.content.Intent().setClass(targetContext,MainActivity::class.java).putExtra("page","theme").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var view:KeyboardView
        fun draw(name:String):android.graphics.Bitmap {
            lateinit var bitmap:android.graphics.Bitmap
            runOnMainSync {
                bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(targetContext.filesDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            }
            return bitmap
        }
        fun bounds(index:Int):android.graphics.Rect {
            val r=android.graphics.Rect();runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInParent(r) };return r
        }
        try {
            waitForIdleSync()
            awaitFocus(activity)
            runOnMainSync { check(!activity.isFinishing && activity.window.decorView.isShown) }
            result.put("theme_editor_opens",true)
            fun all(root:View):List<View> {
                val out=ArrayList<View>();out.add(root)
                if(root is android.view.ViewGroup) for(i in 0 until root.childCount) out.addAll(all(root.getChildAt(i)))
                return out
            }
            runOnMainSync { all(activity.window.decorView).filterIsInstance<BackButton>().first().performClick() }
            SystemClock.sleep(100);waitForIdleSync()
            var scrollPosition=0
            runOnMainSync {
                val scroll=all(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().first()
                scroll.scrollTo(0,(300*activity.resources.displayMetrics.density).toInt());scrollPosition=scroll.scrollY
                val profile=Switches.profiles.first { it.id!=prefs.switchId }
                all(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith(profile.name+",")==true }.performClick()
            }
            SystemClock.sleep(150);waitForIdleSync()
            runOnMainSync {
                val scroll=all(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().first()
                check(scrollPosition>0 && scroll.scrollY==scrollPosition) { "Switch selection moved Feel to ${scroll.scrollY}, expected $scrollPosition" }
                for(light in listOf(true,false)) {
                    if(prefs.appLight!=light) all(activity.window.decorView).filterIsInstance<ThemeToggle>().first().performClick()
                    val expected=if(light) android.graphics.Color.rgb(255,90,0) else android.graphics.Color.rgb(240,92,94)
                    check(all(activity.window.decorView).filterIsInstance<android.widget.Switch>().all { it.thumbTintList!!.getColorForState(intArrayOf(android.R.attr.state_checked),0)==expected })
                }
                if(prefs.appLight!=oldLight) all(activity.window.decorView).filterIsInstance<ThemeToggle>().first().performClick()
                all(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().first().scrollTo(0,0)
            }
            result.put("switch_selection_preserves_scroll",true).put("toggles_follow_app_accent",true)
            runOnMainSync {
                val labels=all(activity.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
                check(labels.containsAll(listOf("Appearance","Sound & feedback","Typing features","Keyboard size")))
                check("Follow your language" !in labels && "Voice & cursor" !in labels)
                result.put("feel_settings_grouped",true)
                val icons=all(activity.window.decorView).filterIsInstance<SwitchIcon>()
                check(icons.size==8)
                val positions=icons.map { v -> IntArray(2).also { v.getLocationOnScreen(it) } }
                check(positions.all { it[1]==positions[0][1] } && positions[2][0]>positions[1][0])
                check(all(activity.window.decorView).filterIsInstance<android.widget.TextView>().none { it.text.toString()==Switches.profiles.first { profile -> profile.id==prefs.switchId }.feel })
                val root=activity.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap));File(targetContext.filesDir,"feel-grid.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                val section=all(root).first { it.contentDescription=="Appearance and keyboard themes" }
                check(section.height>targetContext.resources.displayMetrics.density*130)
                val grid=all(root).first { it.tag=="switch-strip" }
                val switches=android.graphics.Bitmap.createBitmap(grid.width,grid.height,android.graphics.Bitmap.Config.ARGB_8888)
                val switchCanvas=android.graphics.Canvas(switches);switchCanvas.drawColor(if(prefs.appLight) android.graphics.Color.rgb(248,248,245) else android.graphics.Color.rgb(23,24,25));grid.draw(switchCanvas)
                File(targetContext.filesDir,"switch-grid.png").outputStream().use { switches.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };switches.recycle()
                section.performClick()
                check(all(activity.window.decorView).any { it is BackButton })
            }
            result.put("appearance_section_prominent",true).put("back_to_feel",true).put("horizontal_switch_cards",true)
            SystemClock.sleep(200);waitForIdleSync()
            runOnMainSync {
                val views=all(activity.window.decorView)
                check(views.filterIsInstance<PresetPreview>().size==3+DesignPresets.all(prefs).size)
                check(views.any { it is UploadPreview })
                check(views.filterIsInstance<ThemeToggle>().size==1)
                check(views.filterIsInstance<android.widget.TextView>().none { it.text.toString() in listOf("App appearance","An image across your keys","Choose image") })
                result.put("duplicate_theme_controls_removed",true)
                result.put("image_slot_starts_with_upload_icon",true)
                check(views.none { it is android.widget.TextView && it.text.toString()=="Custom colors" })
                val root=activity.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap));File(targetContext.filesDir,"theme-picker.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                views.first { it.contentDescription=="Dark keyboard theme" }.performClick()
                all(activity.window.decorView).filterIsInstance<android.widget.TextView>().first { it.text.toString()=="Accent" }.performClick()
            }
            waitForIdleSync()
            fun find(node:android.view.accessibility.AccessibilityNodeInfo,predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo? {
                if(predicate(node)) return node
                for(i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child,predicate)?.let { return it } }
                return null
            }
            fun rootWith(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo {
                val deadline=SystemClock.uptimeMillis()+4000
                while(SystemClock.uptimeMillis()<deadline) {
                    if(android.os.Build.VERSION.SDK_INT>=34) uiAutomation.clearCache()
                    val root=uiAutomation.rootInActiveWindow
                    if(root!=null && root.packageName=="org.lokey.app" && find(root,predicate)!=null) return root
                    SystemClock.sleep(50)
                }
                error("Color picker did not become ready in Lokey")
            }
            val dialog=rootWith { it.contentDescription=="Orange" }
            check(find(dialog) { it.contentDescription=="Orange" }!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitForIdleSync()
            val chosen=rootWith { it.text?.toString()=="#FF5A00" }
            check(chosen.findAccessibilityNodeInfosByText("#FF5A00").isNotEmpty())
            check(find(chosen) { it.text?.toString()?.equals("Apply",true)==true }!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitForIdleSync()
            check(prefs.themeAccent==android.graphics.Color.rgb(255,90,0) && prefs.keyboardTheme=="custom")
            result.put("visual_preset_previews",true).put("customize_any_preset",true).put("quick_color_swatches",true)
            fun toggle(root:View):ThemeToggle? {
                if(root is ThemeToggle) return root
                if(root is android.view.ViewGroup) for(i in 0 until root.childCount) toggle(root.getChildAt(i))?.let { return it }
                return null
            }
            runOnMainSync {
                val first=prefs.appLight
                toggle(activity.window.decorView)!!.performClick();check(prefs.appLight!=first)
                toggle(activity.window.decorView)!!.performClick();check(prefs.appLight==first)
            }
            result.put("sun_moon_toggles_app",true)
            lateinit var switchSound:ThemeSound
            runOnMainSync { switchSound=ThemeSound(targetContext) }
            val deadline=SystemClock.uptimeMillis()+3000
            while(!switchSound.ready && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20)
            check(switchSound.ready)
            runOnMainSync {
                val audio=targetContext.getSystemService(android.media.AudioManager::class.java)
                val expected=audio.ringerMode==android.media.AudioManager.RINGER_MODE_NORMAL && audio.getStreamVolume(android.media.AudioManager.STREAM_SYSTEM)>0
                check((switchSound.click(true)>0)==expected);switchSound.close()
            }
            result.put("recorded_switch_sound",true).put("sound_respects_silent_mode",true)
            runOnMainSync {
                view=KeyboardView(activity,prefs,object:KeyboardView.Listener {
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
                val width=targetContext.resources.displayMetrics.widthPixels
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,view.measuredWidth,view.measuredHeight);view.editor(false,"Done",false)
                prefs.keyboardTheme="dark"
            }
            val dark=draw("theme-dark.png");check(dark.getPixel(1,dark.height/2)==android.graphics.Color.rgb(23,24,25));dark.recycle()
            runOnMainSync { prefs.keyboardTheme="light" }
            val light=draw("theme-light.png")
            val enter=bounds(42)
            check(light.getPixel(enter.left+enter.width()/5,enter.top+enter.height()/5)==android.graphics.Color.rgb(255,90,0))
            check(KeyboardTheme.light(light.getPixel(1,light.height/2)));light.recycle()
            runOnMainSync { view.accessibilityNodeProvider.performAction(29,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null) }
            val caps=draw("theme-light-caps.png");val shift=bounds(29)
            check(caps.getPixel(shift.left+shift.width()/5,shift.top+shift.height()/5)==android.graphics.Color.rgb(255,90,0));caps.recycle()
            result.put("dark_default_palette",true).put("white_orange_preset",true).put("active_caps_orange",true)
            runOnMainSync { prefs.keyboardTheme="custom";prefs.themeBackground=android.graphics.Color.rgb(12,30,42);prefs.themeKeys=android.graphics.Color.rgb(25,63,83);prefs.themeAccent=android.graphics.Color.rgb(63,196,158);prefs.keyColor("a",android.graphics.Color.MAGENTA) }
            val custom=draw("theme-custom.png");val a=bounds(20)
            check(custom.getPixel(a.left+a.width()/5,a.top+a.height()/5)==android.graphics.Color.MAGENTA);custom.recycle()
            check(Prefs(targetContext).keyColor("a")==android.graphics.Color.MAGENTA)
            result.put("per_key_color_persists",true)
            prefs.rowColor(0,android.graphics.Color.rgb(81,131,162))
            val rows=draw("theme-rows.png")
            val first=bounds(0);val last=bounds(9)
            check(rows.getPixel(first.left+first.width()/5,first.top+first.height()/5)==android.graphics.Color.rgb(81,131,162))
            check(rows.getPixel(last.left+last.width()/5,last.top+last.height()/5)==android.graphics.Color.rgb(81,131,162))
            val unaffected=bounds(10)
            check(rows.getPixel(unaffected.left+unaffected.width()/5,unaffected.top+unaffected.height()/5)==prefs.themeKeys) { "Number row color leaked into letters" }
            rows.recycle()
            check(Prefs(targetContext).rowColor(0)==android.graphics.Color.rgb(81,131,162));prefs.rowColor(0,null)
            result.put("whole_row_color_persists",true)
            var selected=""
            runOnMainSync {
                view.chooseKey={ selected=it }
                val event=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,a.exactCenterX(),a.exactCenterY(),0)
                view.onTouchEvent(event);event.recycle()
            }
            check(selected=="a");result.put("tap_key_to_customize",true)
            val source=android.graphics.Bitmap.createBitmap(3200,2000,android.graphics.Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(source);val paint=android.graphics.Paint()
            paint.shader=android.graphics.LinearGradient(0f,0f,3200f,2000f,android.graphics.Color.rgb(12,46,85),android.graphics.Color.rgb(218,228,210),android.graphics.Shader.TileMode.CLAMP)
            canvas.drawPaint(paint)
            val fixture=File(targetContext.cacheDir,"theme-import.png")
            fixture.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };source.recycle()
            val imported=KeyboardTheme.importImage(targetContext,android.net.Uri.fromFile(fixture))
            check(imported.width==1600 && imported.height==1000)
            KeyboardTheme.applyPalette(prefs,imported)
            check(prefs.themeBackground!=oldBg || prefs.themeAccent!=oldAccent)
            prefs.themeAccent=android.graphics.Color.CYAN
            prefs.themePicture="user"
            result.put("automatic_image_palette",true)
            prefs.themeImageVersion=oldVersion+100;prefs.keyboardTheme="image";prefs.keyColor("a",null)
            val latch=java.util.concurrent.CountDownLatch(1)
            KeyboardTheme.image(targetContext,prefs.themeImageVersion) { check(it!=null);latch.countDown() }
            check(latch.await(10,java.util.concurrent.TimeUnit.SECONDS))
            draw("theme-image-loading.png").recycle();waitForIdleSync()
            val picture=draw("theme-image.png")
            val q=bounds(10);val p=bounds(19)
            check(picture.getPixel(q.left+q.width()/4,q.top+q.height()/5)!=picture.getPixel(p.left+p.width()/4,p.top+p.height()/5)) { "Image restarted on each key" }
            picture.recycle();result.put("image_import_bounded",true).put("image_spans_keycaps",true)
            check(prefs.themeAccent==android.graphics.Color.CYAN);result.put("automatic_palette_is_editable",true)
            prefs.keyboardTheme="wave"
            val waveLatch=java.util.concurrent.CountDownLatch(1)
            KeyboardTheme.image(targetContext,-1,true) { check(it!=null);waveLatch.countDown() }
            check(waveLatch.await(10,java.util.concurrent.TimeUnit.SECONDS))
            draw("theme-wave-loading.png").recycle();waitForIdleSync()
            val wave=draw("theme-wave.png")
            check(wave.getPixel(q.left+q.width()/4,q.top+q.height()/5)!=wave.getPixel(p.left+p.width()/4,p.top+p.height()/5))
            wave.recycle();result.put("bundled_great_wave",true)
            prefs.keyboardTheme="image"
            prefs.appLight=true
            val appLight=startActivitySync(android.content.Intent().setClass(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            SystemClock.sleep(150);waitForIdleSync()
            runOnMainSync {
                val root=appLight.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                check(KeyboardTheme.light(bitmap.getPixel(2,bitmap.height/3)))
                File(targetContext.filesDir,"theme-app-light.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                appLight.finish()
            }
            check(prefs.appLight && prefs.keyboardTheme=="image");result.put("app_and_keyboard_independent",true)
        } finally {
            prefs.designDirty=oldDirty;prefs.activeDesign=oldActive
            if(oldUploadBytes==null) upload.delete() else upload.writeBytes(oldUploadBytes)
            prefs.switchId=oldSwitch;prefs.soundEnabled=oldSound;prefs.userImageChosen=oldUpload;prefs.keyboardTheme=oldTheme;prefs.themePicture=oldPicture;prefs.appLight=oldLight;prefs.themeBackground=oldBg;prefs.themeKeys=oldKeys;prefs.themeAccent=oldAccent;prefs.keyColor("a",oldKey)
            if(backup.exists()) { backup.copyTo(image,true);backup.delete() } else image.delete()
            prefs.themeImageVersion=oldVersion+101
            val restore=themeStore.edit()
            themeStore.all.keys.filter { it.startsWith("key_color.") || it.startsWith("row_color.") }.forEach { restore.remove(it) }
            oldOverrides.forEach { (key,value) -> restore.putInt(key,value as Int) };restore.apply()
            runOnMainSync { activity.finish() }
        }
        return result
    }
    private fun translationUiTest():JSONObject {
        val prefs=Prefs(targetContext);val original=prefs.translationTarget;prefs.translationTarget="English"
        val activity=startActivitySync(android.content.Intent().setClass(targetContext,MainActivity::class.java).putExtra("page","lab")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val result=JSONObject()
        lateinit var input:android.widget.EditText
        fun find(view:View):android.widget.EditText? {
            if(view is android.widget.EditText) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        fun value():String { var text="";runOnMainSync { text=input.text.toString() };return text }
        fun node(label:String):android.view.accessibility.AccessibilityNodeInfo? {
            if(android.os.Build.VERSION.SDK_INT>=34) uiAutomation.clearCache()
            fun walk(n:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
                if(n.packageName=="org.lokey.app" && n.contentDescription?.toString()?.substringBefore(',')?.equals(label,true)==true) return n
                for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it)?.let { found -> return found } }
                return null
            }
            for(window in uiAutomation.windows) if(window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { walk(it)?.let { found -> return found } }
            return null
        }
        fun waitFor(test:()->Boolean) {
            val until=SystemClock.uptimeMillis()+60000
            while(SystemClock.uptimeMillis()<until) {
                var focused=false;runOnMainSync { focused=activity.hasWindowFocus() }
                check(focused) { "Lokey lost focus during translation test" }
                if(test()) return;SystemClock.sleep(100)
            }
            error("Translation UI timed out; app text: ${value()}, source: ${node("Translation source")?.text}")
        }
        fun action(label:String,action:Int=android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
            var focused=false;runOnMainSync { focused=activity.hasWindowFocus() };check(focused)
            waitFor { node(label)!=null };check(node(label)!!.performAction(action)) { "Could not activate $label" }
            SystemClock.sleep(150)
        }
        fun set(label:String,text:String) {
            waitFor { node(label)!=null }
            check(node(label)!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text) }))
        }
        val info=uiAutomation.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;uiAutomation.serviceInfo=info
        try {
            awaitFocus(activity)
            runOnMainSync {
                input=find(activity.window.decorView)!!;input.setText("Before Ciao, come stai? after");input.setSelection(7,23)
                input.requestFocus();activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input,0)
            }
            waitFor { node("?123")!=null }
            runOnMainSync { input.setSelection(7,23) };SystemClock.sleep(200)
            action("?123",android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK);action("Translate")
            waitFor { node("Translation source")?.text?.toString()=="Ciao, come stai?" }
            waitFor { value().contains("hello",true) && node("Translation status")?.text?.toString()=="Translation appears in your app" }
            check(value().startsWith("Before ") && value().endsWith(" after")) { value() }
            check(node("Translation source")!!.text.toString()=="Ciao, come stai?")
            result.put("source_inside_target_outside",true).put("selected_span_only",true)
            set("Translation source", "Buonasera.")
            waitFor { value().contains("evening",true) && node("Translation status")?.text?.toString()=="Translation appears in your app" }
            check(value().startsWith("Before ") && value().endsWith(" after"))
            action("Choose translation language")
            action("j");action("a");action("p")
            waitFor { node("Search languages")?.text?.toString()?.equals("jap",true)==true }
            uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.filesDir,"translation-languages.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            check(!value().contains("jap",true));result.put("language_search_stays_local",true)
            action("Back to translation")
            set("Translation source", "")
            waitFor { value()=="Before  after" };result.put("clearing_source_removes_only_translation",true)
            action("h");action("i")
            waitFor { node("Translation source")?.text?.toString()?.equals("hi",true)==true }
            check(!value().startsWith("Before hi",true));result.put("keyboard_keys_edit_source",true)
            set("Translation source","Ciao, come stai?")
            waitFor { value().contains("hello",true) && node("Translation status")?.text?.toString()=="Translation appears in your app" }
            var focused=false;runOnMainSync { focused=activity.hasWindowFocus() };check(focused)
            uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.filesDir,"translation-source.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            val unchanged=value()
            runOnMainSync { input.setSelection(0) };set("Translation source","Buonasera.")
            waitFor { node("Retry translation")!=null }
            check(value()==unchanged);result.put("moved_app_cursor_pauses_output",true)
            action("Close translation")
            runOnMainSync { input.setText("Keep this. ");input.setSelection(input.length()) }
            SystemClock.sleep(200)
            action("?123",android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK);action("Translate")
            waitFor { node("Translation source")?.let { it.isShowingHintText || it.text.isNullOrEmpty() }==true }
            set("Translation source","Buonasera.");waitFor { value().contains("evening",true) && node("Translation status")?.text?.toString()=="Translation appears in your app" }
            check(value().startsWith("Keep this. "));result.put("new_draft_inserts_at_caret",true)
        } finally { prefs.translationTarget=original;runOnMainSync { activity.finish() } }
        return result
    }
    private fun translationTest():JSONObject {
        check(Translation.languages(targetContext).size==39)
        lateinit var engine:Translation
        runOnMainSync { engine=Translation(targetContext) }
        val cases=listOf(
            Triple("Ciao, come stai?","English","how"),
            Triple("Hello, how are you?","Italian","come"),
            Triple("Good morning, my friend.","Spanish","buen"),
            Triple("Thank you very much.","French","merci"),
            Triple("Hello, how are you?","Japanese","")
        )
        val report=JSONArray()
        try {
            for((source,target,expected) in cases) {
                val done=java.util.concurrent.CountDownLatch(1)
                var answer:Translation.Result?=null;var updates=0
                val start=SystemClock.elapsedRealtime()
                runOnMainSync { engine.request(source,target) { result ->
                    if(result.busy && result.text.isNotEmpty()) updates++
                    if(!result.busy) { answer=result;done.countDown() }
                } }
                check(done.await(60,java.util.concurrent.TimeUnit.SECONDS)) { "Translation timed out: $target" }
                val value=answer!!
                report.put(JSONObject().put("source",source).put("target",target).put("text",value.text).put("error",value.error ?: JSONObject.NULL).put("updates",updates).put("ms",SystemClock.elapsedRealtime()-start))
                File(targetContext.filesDir,"translation-details.json").writeText(report.toString(2))
                check(value.error==null && value.text.isNotBlank()) { "Translation failed: $value" }
                check(!value.text.contains("<think>") && !value.text.contains("<|im_"))
                if(expected.isNotEmpty()) check(value.text.contains(expected,true)) { "Unexpected $target: ${value.text}" }
                if(target=="Japanese") check(value.text.any { it.code in 0x3040..0x9fff }) { "No Japanese output: ${value.text}" }
            }
            val done=java.util.concurrent.CountDownLatch(1);val completed=ArrayList<String>()
            runOnMainSync {
                engine.request("This request must be cancelled.","Italian") { if(!it.busy) completed.add(it.source) }
                engine.request("Bonjour","English") { if(!it.busy) { completed.add(it.source);done.countDown() } }
            }
            check(done.await(60,java.util.concurrent.TimeUnit.SECONDS))
            check(completed==listOf("Bonjour")) { "Stale translations were delivered: $completed" }
        } finally { runOnMainSync { engine.close() } }
        return JSONObject().put("languages",39).put("cases",report).put("stale_requests_cancelled",true)
    }
    private fun toolsTest():JSONObject {
        val activity=startActivitySync(android.content.Intent().setClass(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitFocus(activity)
        val result=JSONObject();val prefs=Prefs(targetContext);val typed=StringBuilder();val tools=ArrayList<String>();var switched=0
        lateinit var view:KeyboardView
        try {
            runOnMainSync {
                view=KeyboardView(activity,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) { typed.append(text) }
                    override fun delete() { }
                    override fun enter() { }
                    override fun cursor(delta:Int) { }
                    override fun voice(start:Boolean)=false
                    override fun cancelVoice() { }
                    override fun nextKeyboard() { switched++ }
                    override fun candidate(word:String) { }
                    override fun feedback(key:String,down:Boolean) { }
                    override fun tool(name:String) { tools.add(name) }
                })
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,view.measuredWidth,view.measuredHeight)
                view.editor(false,"Done",true)
            }
            fun rect(id:Int):android.graphics.Rect {
                val out=android.graphics.Rect();runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(id)!!.getBoundsInParent(out) };return out
            }
            fun event(action:Int,ids:IntArray,positions:List<android.graphics.Rect>) {
                val props=ids.map { id -> MotionEvent.PointerProperties().apply { this.id=id;toolType=MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                val coords=positions.map { r -> MotionEvent.PointerCoords().apply { x=r.exactCenterX();y=r.exactCenterY();pressure=1f;size=1f } }.toTypedArray()
                runOnMainSync {
                    val touch=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,ids.size,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                    view.onTouchEvent(touch);touch.recycle()
                }
            }
            val h=rect(25);val e=rect(12)
            event(MotionEvent.ACTION_DOWN,intArrayOf(0),listOf(h))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),intArrayOf(0,1),listOf(h,e))
            event(MotionEvent.ACTION_POINTER_UP,intArrayOf(0,1),listOf(h,e))
            event(MotionEvent.ACTION_UP,intArrayOf(1),listOf(e))
            check(typed.toString()=="He") { "Overlapping touches repeated Shift: $typed" };result.put("fast_overlapping_typing_uses_one_capital",true)
            runOnMainSync {
                view.accessibilityNodeProvider.performAction(38,android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK,null)
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap));File(targetContext.filesDir,"tools-menu.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(2000)!!.contentDescription=="Clipboard")
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(2001)!!.contentDescription=="Translate")
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(2002)==null)
                view.accessibilityNodeProvider.performAction(2000,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null)
            }
            check(tools==listOf("clipboard") && switched==0);result.put("symbols_hold_tools_keeps_keyboard",true)
            runOnMainSync {
                val store=targetContext.getSharedPreferences("clipboard-test",android.content.Context.MODE_PRIVATE);store.edit().clear().commit()
                val history=ClipboardHistory(targetContext,"clipboard-test")
                history.add("One saved message");history.add("Second saved message")
                history.pin(history.entries().last().id)
                val reload=ClipboardHistory(targetContext,"clipboard-test")
                check(reload.entries().first().pinned && reload.entries().first().text=="One saved message")
                reload.add("One saved message");check(reload.entries().size==2 && reload.entries().first().pinned)
                reload.clearRecent();check(reload.entries().size==1)
                val panel=ClipboardPanel(activity,prefs,reload,{},{})
                panel.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(view.height,View.MeasureSpec.EXACTLY));panel.layout(0,0,1080,view.height)
                val bitmap=android.graphics.Bitmap.createBitmap(panel.width,panel.height,android.graphics.Bitmap.Config.ARGB_8888);panel.draw(android.graphics.Canvas(bitmap))
                File(targetContext.filesDir,"tools-clipboard.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                reload.delete(reload.entries().first().id);check(reload.entries().isEmpty())
                val expired=JSONArray().put(JSONObject().put("id",1).put("text","expired").put("time",0)).put(JSONObject().put("id",2).put("text","pinned").put("time",0).put("pinned",true))
                store.edit().putString("clips",expired.toString()).commit()
                check(ClipboardHistory(targetContext,"clipboard-test").entries().map { it.text }==listOf("pinned"))
                val sensitive=android.content.ClipData.newPlainText("test","fictional secret")
                sensitive.description.extras=android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
                check(ClipboardHistory.text(sensitive)==null && ClipboardHistory.text(sensitive,true)=="fictional secret")
                store.edit().clear().commit()
                result.put("clipboard_pin_reload_duplicate_clear_delete",true).put("clipboard_expiry_and_sensitive_clips",true)
                val editor=android.widget.EditText(activity).apply { showSoftInputOnFocus=false }
                activity.setContentView(editor);editor.setText("Before hello world after");editor.setSelection(7,18)
                val connection=editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                val selected=TranslationTarget.capture(connection)!!
                check(selected.source=="hello world")
                check(selected.write(connection,"ciao mondo") && editor.text.toString()=="Before ciao mondo after")
                editor.setText("unchanged source");editor.setSelection(editor.length())
                val stale=TranslationTarget.capture(connection)!!;editor.append(" new")
                check(!stale.write(connection,"must not replace"))
                editor.setText("x".repeat(6000)+" hello "+"y".repeat(6000));editor.setSelection(6000,6007)
                val long=TranslationTarget.capture(connection)!!;check(long.source==" hello ")
                check(long.write(connection,"ciao") && editor.text.toString()=="x".repeat(6000)+" ciao "+"y".repeat(6000))
                result.put("selection_only_and_stale_editor_guards",true).put("long_editor_selected_passage",true)
                editor.setText("Before hello after");editor.setSelection(7,12)
                val target=TranslationTarget.capture(connection)!!
                check(target.source=="hello" && target.write(connection,"ciao"))
                check(target.write(connection,"ciao mondo") && editor.text.toString()=="Before ciao mondo after")
                check(target.write(connection,"") && editor.text.toString()=="Before  after")
                editor.setSelection(0);check(!target.write(connection,"unsafe"))
                editor.setSelection(editor.length());val caret=TranslationTarget.capture(connection)!!
                check(caret.source.isEmpty() && caret.write(connection,"new") && editor.text.toString()=="Before  afternew")
                result.put("translation_stream_slot_and_cursor_guard",true)
            }
        } finally { runOnMainSync { activity.finish() } }
        return result
    }
    private fun completionTest():JSONObject {
        val prefs=Prefs(targetContext);val original=prefs.model;val correction=prefs.autocorrect
        prefs.model="qwen";prefs.autocorrect=true
        val languages=Languages.shared(targetContext)
        check(languages.all.none { "azzurra" in it.words })
        val cases=listOf(
            "" to "", "Im" to "I'm", "im" to "I'm", "I'm " to "",
            "C'é un amico mio che ha una casa in costa azzu" to "azzurra",
            "C'é un amico mio che ha una casa in costa azzur" to "azzurra",
            "C’è un amico mio che ha una casa in costa azzu" to "azzurra",
            "C'è un amico mio che ha una casa in costa azzur" to "azzurra",
            "The mitochondria produce adenosine triphos" to "triphosphate",
            "Hola quiero un caf" to "café", "Bonjour merci beau" to "beaucoup"
        )
        lateinit var engine:Prediction
        runOnMainSync { engine=Prediction(targetContext,prefs) }
        val report=JSONArray()
        try {
            for((text,expected) in cases) {
                val done=java.util.concurrent.CountDownLatch(1)
                var answer:Prediction.Result?=null;var first=0L
                val start=SystemClock.elapsedRealtime()
                runOnMainSync { engine.request(text) { result ->
                    if(!result.neural) first=SystemClock.elapsedRealtime()-start
                    if(result.neural) { answer=result;done.countDown() }
                } }
                check(done.await(20,java.util.concurrent.TimeUnit.SECONDS)) { "No model completion for $text" }
                val value=answer!!
                report.put(JSONObject().put("text",text).put("words",JSONArray(value.words)).put("language",value.language)
                    .put("fallback_ms",first).put("ms",SystemClock.elapsedRealtime()-start))
                File(targetContext.filesDir,"completion-details.json").writeText(report.toString(2))
                check(value.words.isNotEmpty() && value.words.none { it.startsWith("'") || it.startsWith("’") }) { "Invalid suggestions for $text: ${value.words}" }
                if(expected.isNotEmpty()) check(value.words.any { it.equals(expected,true) }) { "Missing $expected for '$text': ${value.words}" }
            }
            val input=targetContext.getSharedPreferences("lokey",android.content.Context.MODE_PRIVATE)
            check(input.getString("model",null)=="qwen")
        } finally { engine.close();prefs.model=original;prefs.autocorrect=correction }
        return JSONObject().put("cases",report).put("azzurra_not_in_vocabulary",true)
    }
    private fun sentenceTest(): JSONObject {
        val prefs=Prefs(targetContext)
        lateinit var engine:Prediction
        runOnMainSync { engine=Prediction(targetContext,prefs) }
        val cases=listOf("Ciao come stai" to "?", "Ciao come stai " to "?", "Bonjour comment ça va" to "?", "Hola cómo estás" to "?", "How are you" to "?", "Ciao come" to "stai", "I am on my" to "way")
        val report=JSONArray()
        try {
            for((text,expected) in cases) {
                val latch=java.util.concurrent.CountDownLatch(1)
                var answer:Prediction.Result?=null
                val start=SystemClock.elapsedRealtime()
                runOnMainSync { engine.request(text) { value -> if(value.neural) { answer=value;latch.countDown() } } }
                check(latch.await(20,java.util.concurrent.TimeUnit.SECONDS))
                val value=answer!!
                report.put(JSONObject().put("text",text).put("words",JSONArray(value.words)).put("append",JSONArray(value.appendWords.toList())).put("ms",SystemClock.elapsedRealtime()-start))
                check(expected in value.words) { "Sentence '$text' did not suggest $expected: ${value.words}" }
                if(expected.first().isLetter()) check(expected in value.appendWords) { "Next word would replace the completed word" }
            }
        } finally { runOnMainSync { engine.close() } }
        return JSONObject().put("cases",report)
    }
    private fun interactionTest(): JSONObject {
        val prefs = Prefs(targetContext)
        val oldHeight = prefs.height; val oldFlow = prefs.flow; val oldAdaptive = prefs.adaptive
        val oldLanguage=prefs.setupLanguage;prefs.setupLanguage="Italian"
        val result = JSONObject()
        val activity = startActivitySync(android.content.Intent().setClass(targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var view: KeyboardView
        var entered = 0; var cursor = 0; var glides = 0; var candidates = 0
        val typed = StringBuilder(); val voice = ArrayList<Boolean>()
        val density = targetContext.resources.displayMetrics.density
        fun bounds(index: Int): android.graphics.Rect {
            val rect = android.graphics.Rect()
            runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)!!.getBoundsInScreen(rect) }
            return rect
        }
        var downTime=0L
        fun touch(action: Int, x: Float, y: Float) {
            if(action==MotionEvent.ACTION_DOWN) downTime=SystemClock.uptimeMillis()
            runOnMainSync {
                check(activity.hasWindowFocus()) { "Lokey lost focus; stopping touch delivery" }
                val position=IntArray(2);view.getLocationOnScreen(position)
                val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,x,y,0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;event.offsetLocation(-position[0].toFloat(),-position[1].toFloat())
                check(view.dispatchTouchEvent(event));event.recycle()
            }
        }
        fun tap(index: Int) { val r = bounds(index); touch(MotionEvent.ACTION_DOWN, r.exactCenterX(), r.exactCenterY()); touch(MotionEvent.ACTION_UP, r.exactCenterX(), r.exactCenterY()) }
        fun capture(name: String) {
            runOnMainSync {
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(targetContext.filesDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
        try {
            runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                prefs.height = 100; prefs.flow = false; prefs.adaptive = false
                view = KeyboardView(activity, prefs, object : KeyboardView.Listener {
                    override fun text(text: String) { typed.append(text) }
                    override fun delete() { }
                    override fun enter() { entered++ }
                    override fun cursor(delta: Int) { cursor += delta }
                    override fun voice(start: Boolean): Boolean { voice.add(start); return true }
                    override fun cancelVoice() { }
                    override fun nextKeyboard() { }
                    override fun glide(points: List<Glide.Point>, centers: Map<Char, Glide.Point>, caseMode: Int) { glides++; check(points.size > 2 && centers.size == 26) }
                    override fun candidate(word: String) { candidates++ }
                    override fun feedback(key: String, down: Boolean) { }
                })
                val root = android.widget.FrameLayout(activity).apply { setBackgroundColor(android.graphics.Color.rgb(23,24,25)) }
                root.addView(view, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
                activity.setContentView(root); view.editor(false, "Done", false)
            }
            SystemClock.sleep(150); waitForIdleSync()
            fun localLetter():android.graphics.Rect {
                val rect=android.graphics.Rect()
                runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(12)!!.getBoundsInParent(rect) }
                return rect
            }
            val initial = bounds(42); val letter = localLetter()
            val idleDeadline = SystemClock.uptimeMillis() + 5000
            while (bounds(42).width() <= initial.width() * 1.1f && SystemClock.uptimeMillis() < idleDeadline) SystemClock.sleep(100)
            SystemClock.sleep(200)
            val idle = bounds(42)
            capture("interaction-idle.png")
            check(idle.width() > initial.width() * 1.1f && localLetter() == letter) { "Idle Enter ${initial.width()} -> ${idle.width()}, letter $letter -> ${localLetter()}, shown=${view.isShown}, visible=${view.windowVisibility}, focus=${activity.hasWindowFocus()}" }
            touch(MotionEvent.ACTION_DOWN, idle.exactCenterX(), idle.exactCenterY())
            SystemClock.sleep(400)
            check(bounds(42) == idle) { "Voice target moved under the finger" }
            touch(MotionEvent.ACTION_UP, idle.exactCenterX(), idle.exactCenterY())
            check(voice == listOf(true, false) && entered == 0)
            check(bounds(42).width() == initial.width())
            runOnMainSync { view.voiceState = "" }
            result.put("touch_delivery","direct_view").put("idle_enter_expands", true).put("letters_stay_fixed", true).put("held_voice_target_stays_fixed", true).put("interaction_restores_enter", true)
            fun top(): Pair<Float,Float> {
                var value = 0f to 0f
                runOnMainSync { val location = IntArray(2); view.getLocationOnScreen(location); value = view.width / 2f to location[1] + 5 * density }
                return value
            }
            val originalHeight = view.height
            val (x,y) = top()
            touch(MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(450)
            touch(MotionEvent.ACTION_MOVE, x, y - 80 * density); SystemClock.sleep(90); waitForIdleSync()
            check(view.height > originalHeight) { "Live height did not change" }
            capture("interaction-resizing.png")
            touch(MotionEvent.ACTION_UP, x, y - 80 * density); SystemClock.sleep(90); waitForIdleSync()
            check(prefs.height == 120 && Prefs(targetContext).height == 120)
            val enlarged = view.height
            val (cx,cy) = top()
            touch(MotionEvent.ACTION_DOWN, cx, cy); SystemClock.sleep(450)
            touch(MotionEvent.ACTION_MOVE, cx, cy + 100 * density); SystemClock.sleep(90); waitForIdleSync()
            check(view.height < enlarged) { "Shrink stayed ${view.height}, initial $enlarged" }
            touch(MotionEvent.ACTION_CANCEL, cx, cy + 100 * density); SystemClock.sleep(90); waitForIdleSync()
            check(prefs.height == 120 && view.height == enlarged && candidates == 0)
            result.put("top_hold_resizes_live", true).put("height_persists", true).put("cancel_restores_height", true).put("resize_does_not_select_prediction", true)
            runOnMainSync { prefs.height = 100; prefs.flow = true; view.editor(false,"Done",true); view.requestLayout() }; SystemClock.sleep(90); waitForIdleSync()
            val ids = "qwertyuiopasdfghjklzxcvbnm".mapIndexed { i,c -> c to if(i < 19) i + 10 else i + 11 }.toMap()
            val path = "helo".map { bounds(ids[it]!!) }
            touch(MotionEvent.ACTION_DOWN, path[0].exactCenterX(), path[0].exactCenterY())
            for (i in 1..path.lastIndex) for (j in 1..8) {
                val a=path[i-1]; val b=path[i]; val t=j/8f
                touch(MotionEvent.ACTION_MOVE, a.exactCenterX()+(b.exactCenterX()-a.exactCenterX())*t, a.exactCenterY()+(b.exactCenterY()-a.exactCenterY())*t)
            }
            capture("interaction-flow.png")
            touch(MotionEvent.ACTION_UP, path.last().exactCenterX(), path.last().exactCenterY())
            check(glides == 1 && typed.isEmpty()) { "Swipe emitted taps or failed to decode" }
            runOnMainSync { view.capitalization(true) }
            check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(29)!!.contentDescription=="Shift off") { "Repeated Caps hint restored Shift after swipe" }
            runOnMainSync { view.capitalization(false); view.capitalization(true) }
            check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(29)!!.contentDescription=="Shift on for next letter")
            tap(29)
            runOnMainSync { view.capitalization(false) }
            check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(29)!!.contentDescription=="Caps lock on")
            runOnMainSync { view.editor(false,"Done",false) }
            result.put("swipe_consumes_single_shift",true).put("new_sentence_enables_shift",true).put("caps_lock_survives_editor_hints",true)
            val e = bounds(12)
            touch(MotionEvent.ACTION_DOWN, e.exactCenterX(), e.exactCenterY()); touch(MotionEvent.ACTION_MOVE, e.exactCenterX(), e.exactCenterY()-15*density); touch(MotionEvent.ACTION_UP, e.exactCenterX(), e.exactCenterY()-15*density)
            check(typed.toString() == "è" && glides == 1) { "Accent flick conflicted with swipe: $typed, language=${prefs.setupLanguage}" }
            touch(MotionEvent.ACTION_DOWN, e.exactCenterX(), e.exactCenterY()); SystemClock.sleep(350); touch(MotionEvent.ACTION_UP, e.exactCenterX(), e.exactCenterY())
            check(typed.toString() == "èè")
            val space = bounds(40)
            touch(MotionEvent.ACTION_DOWN, space.exactCenterX(), space.exactCenterY()); touch(MotionEvent.ACTION_MOVE, space.exactCenterX()+30*density, space.exactCenterY()); touch(MotionEvent.ACTION_UP, space.exactCenterX()+30*density, space.exactCenterY())
            check(cursor > 0 && glides == 1)
            runOnMainSync { prefs.flow = false }
            tap(12); check(typed.toString() == "èèe")
            result.put("swipe_enabled", true).put("swipe_disabled", true).put("accent_flick_preserved", true).put("held_accent_preserved", true).put("space_cursor_preserved", true)
            runOnMainSync {
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(targetContext.filesDir, "interaction-keyboard.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
        } finally {
            runOnMainSync { prefs.height=oldHeight; prefs.flow=oldFlow; prefs.adaptive=oldAdaptive;prefs.setupLanguage=oldLanguage; activity.finish() }
        }
        return result
    }
    private fun flowTest(): JSONObject {
        val book = Languages.shared(targetContext)
        val centers = HashMap<Char, Glide.Point>()
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
            letters.forEachIndexed { i,c -> centers[c] = Glide.Point(i + if(row == 0) .5f else if(row == 1) 1f else 1.8f, row*1.3f) }
        }
        fun path(word: String): List<Glide.Point> {
            val points = ArrayList<Glide.Point>()
            Languages.fold(word).forEachIndexed { i,c ->
                val b=centers[c]!!
                if(i==0) points.add(b)
                else {
                    val a=centers[Languages.fold(word)[i-1]]!!
                    for(j in 1..8) points.add(Glide.Point(a.x+(b.x-a.x)*j/8f + kotlin.math.sin(j.toFloat())*.045f, a.y+(b.y-a.y)*j/8f + kotlin.math.cos(j.toFloat())*.045f))
                }
            }
            return points
        }
        val cases = listOf(Triple("en","I would like to say ","hello"), Triple("it","Ciao come stai ","domani"), Triple("es","Hola muchas ","gracias"), Triple("fr","Bonjour comment ça va ","merci"), Triple("es","Hola quiero un ","café"))
        val report = JSONArray()
        val prefs = Prefs(targetContext)
        lateinit var engine: Prediction
        runOnMainSync { engine = Prediction(targetContext, prefs) }
        try {
            for((code, context, word) in cases) {
                val trace=path(word)
                val entries=book.all.first { it.code==code }.entries
                val geometric=Glide.decode(trace, centers, entries)
                check(geometric.take(3).any { it.word == word }) { "Geometry missed $word: $geometric" }
                val latch=java.util.concurrent.CountDownLatch(1)
                var choices=emptyList<String>()
                val start=SystemClock.elapsedRealtime()
                runOnMainSync { engine.glide(context, trace, centers) { choices=it; latch.countDown() } }
                check(latch.await(20,java.util.concurrent.TimeUnit.SECONDS))
                check(word in choices) { "Model missed swipe $word: $choices" }
                report.put(JSONObject().put("language",code).put("word",word).put("ms",SystemClock.elapsedRealtime()-start).put("candidates",JSONArray(choices)))
            }
        } finally { runOnMainSync { engine.close() } }
        return JSONObject().put("noisy_paths",true).put("languages",report)
    }
    private fun multilingualTest(): JSONObject {
        val result = JSONObject()
        val prefs = Prefs(targetContext)
        val original = prefs.model
        val originalCorrection = prefs.autocorrect
        val languages = Languages.shared(targetContext)
        val samples = listOf(
            Triple("it", "Ciao come stai ci vediamo dom", "domani"),
            Triple("es", "Hola cómo estás muchas grac", "gracias"),
            Triple("fr", "Bonjour comment ça va merci beau", "beaucoup"),
            Triple("en", "Hello how are you thank you very mu", "much")
        )
        var previous = "en"
        for ((code, text, _) in samples + samples.reversed()) {
            val detected = languages.detect(text, previous).code
            check(detected == code) { "Detected $detected instead of $code: $text" }
            previous = detected
        }
        check(Languages.distance("domnai", "domani") == 1)
        check(languages.all.size == 21 && languages.all.all { it.entries.size >= 1000 })
        result.put("language_switching", true).put("vocabularies", languages.all.size)
        val npu=Acceleration.await()
        check(Models.predictor(targetContext, "auto", "it").id == "qwen")
        check(Models.predictor(targetContext, "auto", "en").id == if(npu) "qwen" else "lfm")
        result.put("automatic_model_selection", true).put("npu",npu)
        val models = JSONArray()
        try {
            prefs.autocorrect = true
            for (model in Models.text) {
                if (!Models.ready(targetContext, model)) continue
                prefs.model = model.id
                lateinit var engine: Prediction
                runOnMainSync { engine = Prediction(targetContext, prefs) }
                val trials = JSONArray()
                try {
                    val cases = samples + listOf(
                        Triple("it", "Ciao ci vediamo ", "domani"),
                        Triple("es", "Hola muchas ", "gracias"),
                        Triple("fr", "Bonjour merci ", "beaucoup"),
                        Triple("en", "Thank you very ", "much"),
                        Triple("it", "Ciao ci vediamo doma", "domani"),
                        Triple("it", "Ciao ci vediamo doman", "domani"),
                        Triple("it", "Ciao ci vediamo domnai", "domani"),
                        Triple("es", "Hola muchas graciass", "gracias"),
                        Triple("fr", "Bonjour merci beacoup", "beaucoup"),
                        Triple("en", "Hello I will recevie", "receive")
                    )
                    for ((code, text, expected) in cases) {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var answer: Prediction.Result? = null
                        val start = SystemClock.elapsedRealtime()
                        var fallbackMs = 0L
                        runOnMainSync { engine.request(text) { value ->
                            if (!value.neural) fallbackMs = SystemClock.elapsedRealtime() - start
                            if (value.neural) { answer = value; latch.countDown() }
                        } }
                        check(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Prediction timed out: ${model.id}: $text" }
                        val value = answer!!
                        check(value.language == code) { "Model engine language ${value.language}, expected $code" }
                        check(value.words.isNotEmpty() && value.letters.all { it.isFinite() && it >= 0 })
                        if (text.endsWith("grac")) check(value.correction == null && value.words.first() == "gracias") { "A valid prefix became a typo" }
                        trials.put(JSONObject().put("text", text).put("language", value.language).put("ms", SystemClock.elapsedRealtime() - start)
                            .put("fallback_ms", fallbackMs).put("words", JSONArray(value.words)).put("correction", value.correction ?: JSONObject.NULL).put("expected", expected)
                            .put("top3", value.words.any { it.equals(expected, true) }))
                    }
                } finally { runOnMainSync { engine.close() } }
                models.put(JSONObject().put("model", model.id).put("trials", trials))
            }
            result.put("models", models)
        } finally { prefs.model = original; prefs.autocorrect = originalCorrection }
        return result
    }
    private fun correctionTest(): JSONObject {
        val activity = startActivitySync(android.content.Intent().setClass(targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val result = JSONObject()
        lateinit var editor:android.widget.EditText
        lateinit var connection:android.view.inputmethod.InputConnection
        val correction=Autocorrect()
        var engine:Prediction?=null
        try {
            runOnMainSync {
                editor = android.widget.EditText(activity).apply { showSoftInputOnFocus = false }
                activity.setContentView(editor); editor.requestFocus()
                connection = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                fun put(value: String) { editor.setText(value); editor.setSelection(value.length) }
                fun prediction(before: String, word: String) = Prediction.Result(before, listOf(word), FloatArray(26), "it", true, word)
                put("Ciao ci vediamo domnai")
                check(correction.commit(connection, " ", prediction(editor.text.toString(), "domani"), true))
                check(editor.text.toString() == "Ciao ci vediamo domani ")
                check(correction.revert(connection) && editor.text.toString() == "Ciao ci vediamo domnai")
                result.put("apply_and_backspace_undo", true)
                check(!correction.commit(connection, " ", prediction(editor.text.toString(), "domani"), true))
                result.put("undo_is_not_immediately_recorrected", true)
                correction.reset()
                put("old text")
                check(!correction.commit(connection, " ", prediction("different text", "test"), true))
                result.put("stale_result_rejected", true)
                put("hello@domnai")
                check(!correction.commit(connection, " ", prediction(editor.text.toString(), "domani"), true))
                result.put("addresses_preserved", true)
                put("Ciao domnaiXYZ"); editor.setSelection(11)
                check(!correction.commit(connection, " ", prediction("Ciao domnai", "domani"), true))
                result.put("middle_of_word_preserved", true)
                put("Ciao domnai")
                check(!correction.commit(connection, " ", prediction(editor.text.toString(), "domani"), false))
                result.put("disabled_preserved", true)
                put("Ciao domnai")
                correction.commit(connection, " ", prediction(editor.text.toString(), "domani"), true)
                editor.setSelection(0)
                check(!correction.revert(connection))
                result.put("moved_cursor_does_not_undo_elsewhere", true)
                for(boundary in listOf(" ",",","?",".","!",";",":","…","\n")) {
                    correction.reset();put("Ciao domnai")
                    check(correction.commit(connection,boundary,prediction("Ciao domnai","domani"),true))
                    check(editor.text.toString()=="Ciao domani"+boundary)
                    check(correction.revert(connection) && editor.text.toString()=="Ciao domnai")
                }
                result.put("punctuation_and_space_correct_and_undo",true)
                correction.reset();put("Ciao domnai?")
                check(correction.defer(connection,"?",true)=="Ciao domnai")
                connection.commitText("x",1)
                check(!correction.finish(connection,prediction("Ciao domnai","domani")))
                check(editor.text.toString()=="Ciao domnai?x")
                result.put("late_correction_preserves_following_input",true)
                correction.reset();put("Ciao domnai.")
                check(correction.defer(connection,".",true)=="Ciao domnai")
                editor.setSelection(0)
                check(!correction.finish(connection,prediction("Ciao domnai","domani")))
                result.put("late_correction_preserves_moved_cursor",true)
                correction.reset();put("Ciao domnai,")
                check(correction.defer(connection,",",false)==null)
                result.put("disabled_punctuation_correction_preserved",true)
                correction.reset();put("Ciao ci vediamo domnai,")
                connection.setComposingRegion(9,editor.length())
                check(correction.defer(connection,",",true)=="Ciao ci vediamo domnai")
                check(correction.finish(connection,prediction("Ciao ci vediamo domnai","domani")))
                check(editor.text.toString()=="Ciao ci vediamo domani,")
                connection.setComposingRegion(9,editor.length())
                check(correction.revert(connection) && editor.text.toString()=="Ciao ci vediamo domnai")
                result.put("correction_and_undo_preserve_prefix_with_composing_span",true)
                engine=Prediction(targetContext,Prefs(targetContext))
            }
            val cases=listOf(
                Triple("Ciao ci vediamo domnai",",","Ciao ci vediamo domani,"),
                Triple("Ciao ci vediamo domnai","?","Ciao ci vediamo domani?"),
                Triple("Hello I will recevie",".","Hello I will receive."),
                Triple("Hola muchas graciass","!","Hola muchas gracias!"),
                Triple("Bonjour merci beacoup",";","Bonjour merci beaucoup;"),
                Triple("Ciao ci vediamo domnai",":","Ciao ci vediamo domani:"),
                Triple("Ciao ci vediamo domnai","?!","Ciao ci vediamo domani?!"),
                Triple("Im"," ","I'm "),Triple("im","?","I'm?"),Triple("Ive",".","I've."),
                Triple("Please send me the documetn",".","Please send me the document."),
                Triple("Estoy muy agradecido por tu paciencai","!","Estoy muy agradecido por tu paciencia!"),
                Triple("Je te remercie pour ta patiecne",".","Je te remercie pour ta patience."),
                Triple("The mitochondria produce adenosine triphosphatr",".","The mitochondria produce adenosine triphosphate.")
            )
            val checks=JSONArray()
            for((before,boundary,expected) in cases) {
                val done=java.util.concurrent.CountDownLatch(1)
                var failure:Throwable?=null
                runOnMainSync {
                    correction.reset();editor.setText(before+boundary);editor.setSelection(editor.length())
                    val context=correction.defer(connection,boundary,true)!!
                    engine!!.correct(context) { answer ->
                        try {
                            val applied=correction.finish(connection,answer)
                            val target=Languages.prefix(expected.dropLast(boundary.length))
                            val outside=Languages.shared(targetContext).all.none { Languages.lower(target) in it.words }
                            check(applied || outside) { "No late correction for $before$boundary: $answer" }
                            check(editor.text.toString()==if(applied) expected else before+boundary) { "Got ${editor.text}, expected $expected or unchanged text" }
                            checks.put(JSONObject().put("input",before+boundary).put("expected",expected).put("output",editor.text.toString())
                                .put("applied",applied).put("outside_vocabulary",outside))
                            if(applied) check(correction.revert(connection) && editor.text.toString()==before)
                        } catch(error:Throwable) { failure=error }
                        finally { done.countDown() }
                    }
                    engine!!.request(before+boundary) { }
                }
                check(done.await(20,java.util.concurrent.TimeUnit.SECONDS)) { "Punctuation correction timed out" }
                failure?.let { throw it }

            }
            result.put("late_model_corrections",checks)
            result.put("accepted_corrections",(0 until checks.length()).count { checks.getJSONObject(it).getBoolean("applied") })
            val preserved=JSONArray()
            for(before in listOf("The mitochondria produce adenosine triphosphate","The kinase phosphorylates tyrosine","I feel ill","We work well","As is his wont","Meet Zorvella","Use HTTP")) {
                val done=java.util.concurrent.CountDownLatch(1);var answer:Prediction.Result?=null
                runOnMainSync { engine!!.correct(before) { answer=it;done.countDown() } }
                check(done.await(20,java.util.concurrent.TimeUnit.SECONDS))
                check(answer?.correction==null) { "Unnecessary correction of '$before': $answer" }
                preserved.put(before)
            }
            result.put("valid_words_and_names_preserved",preserved)
        } finally { engine?.close();runOnMainSync { activity.finish() } }
        return result
    }
    private fun speedUiTest():JSONObject {
        val prefs=Prefs(targetContext);val original=prefs.appLight
        val report=JSONArray()
        fun <T:View> find(view:View,type:Class<T>):T? {
            if(type.isInstance(view)) return type.cast(view)
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),type)?.let { return it }
            return null
        }
        try {
            for(light in listOf(false,true)) {
                prefs.appLight=light
                val activity=startActivitySync(android.content.Intent().setClass(targetContext,MainActivity::class.java).putExtra("page","lab")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try {
                    awaitFocus(activity);SystemClock.sleep(400)
                    lateinit var game:SpeedTestView
                    var normalHeight=0;var normalWidth=0
                    runOnMainSync {
                        val normal=find(activity.window.decorView,android.widget.EditText::class.java)!!
                        val toggle=find(activity.window.decorView,android.widget.Switch::class.java)!!
                        val box=IntArray(2);val control=IntArray(2);normal.getLocationOnScreen(box);toggle.getLocationOnScreen(control)
                        check(control[1]>=box[1]+normal.height)
                        normalHeight=normal.height;normalWidth=normal.width
                        normal.setText("My typing draft");toggle.performClick()
                        game=find(activity.window.decorView,SpeedTestView::class.java)!!
                    }
                    SystemClock.sleep(500);waitForIdleSync()
                    runOnMainSync {
                        check(game.editor.width==normalWidth && game.editor.height==normalHeight)
                        val toggle=find(activity.window.decorView,android.widget.Switch::class.java)!!
                        val box=IntArray(2);val control=IntArray(2);game.editor.getLocationOnScreen(box);toggle.getLocationOnScreen(control)
                        check(control[1]>=box[1]+game.editor.height)
                        val prefix=game.run.passage.take(24)
                        game.editor.setText(prefix);game.editor.setSelection(prefix.length)
                        check(game.run.score>0 && game.run.combo==prefix.length)
                        val connection=game.editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                        check(connection.getTextBeforeCursor(1000,0).toString()==prefix && connection.getTextAfterCursor(1000,0).isNullOrEmpty())
                    }
                    SystemClock.sleep(200)
                    var focused=false;runOnMainSync { focused=activity.hasWindowFocus() };check(focused)
                    uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(targetContext.filesDir,"speedtest-"+(if(light) "light" else "dark")+".png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                    }
                    runOnMainSync {
                        find(activity.window.decorView,android.widget.Switch::class.java)!!.performClick()
                        check(find(activity.window.decorView,android.widget.EditText::class.java)!!.text.toString()=="My typing draft")
                    }
                    report.put(JSONObject().put("theme",if(light) "light" else "dark").put("same_typing_box",true).put("toggle_below_box",true).put("ghost_text_excluded_from_input",true).put("normal_draft_preserved",true))
                } finally { runOnMainSync { activity.finish() } }
            }
        } finally { prefs.appLight=original }
        return JSONObject().put("themes",report)
    }
    private fun speedTest(): JSONObject {
        val prefs=Prefs(targetContext);val originalLanguage=prefs.setupLanguage
        try {
            val texts=HashSet<String>()
            for((name,mapping) in LanguageLayouts.all) {
                prefs.setupLanguage=name
                val passage=SpeedPassages.selected(targetContext,prefs)
                check(passage.code.substringBefore('-')==mapping.code && passage.text.isNotBlank())
                check(texts.add(passage.text))
                val run=SpeedRun(passage.text,passage.id,passage.code,prefs.currentLayout.id)
                run.update(passage.text,1000)
                check(run.finished && run.accuracy==100.0)
                check(run.snapshot(2000).getString("language")==passage.code)
            }
            check(texts.size==39)
        } finally { prefs.setupLanguage=originalLanguage }
        val game=SpeedRun("a ".repeat(15))
        check(game.started==0L)
        for(index in game.passage.indices) game.update(game.passage.take(index+1),1000L+index*100)
        check(game.finished && game.bestCombo==30 && game.multiplier==4 && game.accuracy==100.0)
        check(game.score==630)
        val frozen=game.snapshot(9000).toString()
        game.update("wrong",20000);check(game.snapshot(20000).toString()==frozen)
        val exact=SpeedRun("Ab, c?")
        for(index in exact.passage.indices) {
            check(exact.update(exact.passage.take(index+1),1000L+index*100)==listOf(true))
            check(exact.score==(index+1)*10)
        }
        check(exact.finished && exact.combo==6)
        val wrong=SpeedRun("a b")
        wrong.update("a",1000);wrong.update("aX",2000)
        check(wrong.combo==0 && wrong.accuracy==50.0 && wrong.score==10)
        wrong.update("a",3000);wrong.update("a ",4000)
        check(wrong.index==2 && wrong.score==10)
        wrong.update("a b",5000);check(wrong.finished && wrong.combo==1 && wrong.score==20)
        val activity = startActivitySync(android.content.Intent().setClass(targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            runOnMainSync {
                val view = SpeedTestView(activity)
                activity.setContentView(view)
                view.editor.showSoftInputOnFocus = false
                view.editor.setText(view.run.passage.take(35))
                check(view.run.index==view.editor.length() && view.run.combo==view.editor.length())
                view.editor.onPrivateIMECommand("org.lokey.app.PREDICTION", Bundle().apply { putInt("words", 2) })
                check(view.run.predictionTaps == 1 && view.run.assistedWords == 2)
                view.editor.onPrivateIMECommand("org.lokey.app.VOICE", null)
                check(!view.run.eligible)
                view.restart()
                check(view.run.index == 0 && view.run.started == 0L && view.run.predictionTaps == 0)
                view.editor.showSoftInputOnFocus = false
                view.editor.setText(view.run.passage.take(24))
                val width = targetContext.resources.displayMetrics.widthPixels
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(android.graphics.Color.rgb(23, 24, 25)); view.draw(canvas)
                File(targetContext.filesDir, "speedtest.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            val score = game.snapshot(9000).put("wpm", 107.4).put("prediction_taps", 18)
            val image = ScoreCard.create(targetContext, score, true)
            check(image.length() > 1000)
            val uri = android.net.Uri.parse("content://org.lokey.app.scores/record.png")
            check(targetContext.contentResolver.getType(uri) == "image/png")
            targetContext.contentResolver.openInputStream(uri)!!.use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                check(bitmap.width == 1080 && bitmap.height == 1350); bitmap.recycle()
            }
            targetContext.contentResolver.query(uri, null, null, null, null)!!.use { check(it.moveToFirst() && it.getString(0) == image.name) }
        } finally { runOnMainSync { activity.finish() } }
        return JSONObject().put("character_scoring_including_spaces_and_punctuation",true).put("combo_multiplier", true).put("miss_resets_combo", true).put("no_score_farming", true)
            .put("timer_freezes", true).put("prediction_taps", true).put("practice_detection", true).put("restart", true).put("share_png_provider", true)
    }
    private fun benchmark(): JSONObject {
        val models = JSONArray()
        val prompts = listOf("I am on my way ", "Can you send me the ", "Thank you for your ", "See you ", "Good mor", "I would like to ", "Let me know when you ")
        for (model in Models.text) {
            val file = Models.file(targetContext, model)
            if (!file.exists()) { models.put(JSONObject().put("id", model.id).put("missing", true)); continue }
            val start = SystemClock.elapsedRealtimeNanos()
            check(Native.loadText(file.path,accelerator)) { "Cannot load ${model.title}" }
            val loadMs = (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0
            val trials = JSONArray()
            repeat(2) { pass ->
                prompts.forEach { prompt ->
                    val begin = SystemClock.elapsedRealtimeNanos()
                    val result = Native.predict(prompt)
                    val ms = (SystemClock.elapsedRealtimeNanos() - begin) / 1000000.0
                    val letters = Native.letters()
                    check(letters.size == 26 && letters.all { it.isFinite() && it >= 0 })
                    trials.put(JSONObject().put("pass", pass).put("prompt", prompt).put("ms", ms).put("suggestions", JSONArray(result.trim().split('\n'))))
                }
            }
            val scores=Native.score("I will","English",arrayOf("receive","recevie"))
            check(scores.size==2 && scores.all { it.isFinite() } && scores[0]>scores[1]) { "Whole-word scoring failed for ${model.id}: ${scores.toList()}" }
            models.put(JSONObject().put("id", model.id).put("backend",Native.textBackend()).put("load_ms", loadMs).put("trials", trials).put("whole_word_scores",JSONArray(scores.toList())))
        }
        val result = JSONObject().put("text", models)
        val file = Models.file(targetContext, Models.speech)
        val wav = File(targetContext.filesDir, "jfk.wav")
        if (file.exists() && wav.exists()) {
            val bytes = wav.readBytes()
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var audioOffset = 0
            var audioSize = 0
            while (offset + 8 <= bytes.size) {
                val size = data.getInt(offset + 4)
                if (String(bytes, offset, 4) == "data") { audioOffset = offset + 8; audioSize = size; break }
                offset += 8 + size + size % 2
            }
            check(audioOffset > 0)
            val samples = FloatArray(audioSize / 2) { data.getShort(audioOffset + it * 2) / 32768f }
            val begin = SystemClock.elapsedRealtimeNanos()
            check(Native.loadSpeech(file.path)) { "Cannot load Parakeet" }
            val loadMs = (SystemClock.elapsedRealtimeNanos() - begin) / 1000000.0
            val trials = JSONArray()
            val idleVoice=Voice(targetContext)
            repeat(2) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ idleVoice.cancel() },100)
                val start = SystemClock.elapsedRealtimeNanos()
                val text = Native.transcribe(samples)
                val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0
                check(text.contains("country", true) && text.contains("ask", true)) { "Speech fixture mismatch: $text" }
                trials.put(JSONObject().put("ms", ms).put("text", text).put("audio_seconds", samples.size / 16000.0))
            }
            idleVoice.close()
            result.put("parakeet", JSONObject().put("load_ms", loadMs).put("trials", trials).put("idle_cancel_does_not_interrupt",true))
        }
        Native.release()
        return result
    }
    companion object { const val RESULT_OK = -1; const val RESULT_CANCELED = 0 }
}
