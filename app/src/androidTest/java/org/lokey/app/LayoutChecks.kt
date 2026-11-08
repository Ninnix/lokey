package org.lokey.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LayoutChecks {
    fun intl(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val original=if(prefs.automaticLayout) "auto" else prefs.layout
        val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val raw=StringBuilder();val typed=StringBuilder();val dead=DeadKeys()
        lateinit var view:KeyboardView
        val density=context.resources.displayMetrics.density
        fun id(code:String):Int {
            if(code=="space") return 40
            if(code.singleOrNull()?.isDigit()==true) return "1234567890".indexOf(code)
            val index="qwertyuiopasdfghjklzxcvbnm".indexOf(code)
            return 10+index+if(index>=19) 1 else 0
        }
        fun reset(capital:Boolean=false) { test.runOnMainSync { raw.clear();typed.clear();dead.clear();view.editor(false,"Done",capital) } }
        fun tap(code:String) { test.runOnMainSync { view.accessibilityNodeProvider.performAction(id(code),AccessibilityNodeInfo.ACTION_CLICK,null) } }
        fun capture(name:String) { test.runOnMainSync {
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
            File(context.filesDir,name+".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        } }
        fun secondary(code:String,index:Int,leftFlick:Boolean=false,shot:String?=null) {
            val rect=android.graphics.Rect()
            test.runOnMainSync { view.accessibilityNodeProvider.createAccessibilityNodeInfo(id(code))!!.getBoundsInParent(rect) }
            val start=SystemClock.uptimeMillis();val x=rect.exactCenterX();val y=rect.exactCenterY()
            fun event(action:Int,dx:Float=0f,dy:Float=0f) { test.runOnMainSync {
                val event=android.view.MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,(x+dx*density).coerceIn(0f,view.width-1f),y+dy*density,0)
                view.onTouchEvent(event);event.recycle()
            } }
            event(android.view.MotionEvent.ACTION_DOWN)
            if(leftFlick) {
                event(android.view.MotionEvent.ACTION_MOVE,dx=-15f);event(android.view.MotionEvent.ACTION_UP,dx=-15f)
            } else if(index==0) {
                event(android.view.MotionEvent.ACTION_MOVE,dy=-15f);event(android.view.MotionEvent.ACTION_UP,dy=-15f)
            } else {
                SystemClock.sleep(350)
                val direction=if(x>view.width/2f+density) -1 else 1
                event(android.view.MotionEvent.ACTION_MOVE,dx=41f*index*direction)
                if(shot!=null) capture(shot)
                event(android.view.MotionEvent.ACTION_UP,dx=41f*index*direction)
            }
        }
        try {
            test.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                prefs.layout="intl"
                view=KeyboardView(activity,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) { raw.append(text);typed.append(dead.input(text)) }
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
                view.layout(0,0,1080,view.measuredHeight);view.editor(false,"Done",false)
                check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(43)==null) { "Extra bottom-row key remains" }
                val bottom=android.graphics.Rect();view.accessibilityNodeProvider.createAccessibilityNodeInfo(40)!!.getBoundsInParent(bottom)
                check(view.height-bottom.bottom<=6*context.resources.displayMetrics.density) { "Unused keyboard footer remains" }
            }
            val mappings=mapOf("l" to "'\"","k" to ":;","m" to "<>/","p" to "|\\","o" to "=+","9" to "[{","0" to "]}","q" to "`~")
            for((key,symbols) in mappings) for((index,symbol) in symbols.withIndex()) {
                reset();secondary(key,index)
                check(raw.toString()==symbol.toString()) { "$key option $index: $raw, expected $symbol" }
            }
            reset();secondary("0",0,true);check(raw.toString()=="}") { "Left flick on 0: $raw" }
            reset();secondary("p",0,true);check(raw.toString()=="\\") { "Left flick on P: $raw" }
            test.runOnMainSync {
                for((key,symbols) in mappings) check(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id(key))!!.contentDescription.toString().endsWith("hold for "+symbols.map { it.toString() }.joinToString(" "))) { "Missing secondary labels on $key" }
            }
            for((key,index,pair) in listOf(Triple("l",0,"e" to "é"),Triple("l",1,"u" to "ü"),Triple("q",0,"e" to "è"),Triple("q",1,"n" to "ñ"),Triple("6",0,"o" to "ô"),Triple("l",0,"space" to "'"))) {
                reset();secondary(key,index);tap(pair.first)
                check(typed.toString()==pair.second) { "Dead key $key/$index: $typed" }
            }
            reset(true);secondary("l",1);tap("u");tap("a");check(typed.toString()=="Üa") { "Dead key consumed Shift: $typed" }
            reset();secondary("0",1,shot="intl-popup-number");secondary("p",1,shot="intl-popup-p");secondary("m",2,shot="intl-popup-m")
            test.runOnMainSync {
                view.suggestionsVisible=false
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,1080,view.measuredHeight)
            }
            secondary("0",1,shot="intl-popup-no-suggestions")
            test.runOnMainSync {
                view.suggestionsVisible=true
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,1080,view.measuredHeight)
            }
            reset();test.runOnMainSync {
                view.editor(true,"Done",false)
                view.accessibilityNodeProvider.performAction(10,AccessibilityNodeInfo.ACTION_LONG_CLICK,null)
                check(raw.toString()=="(") { "Numeric field alternates changed: $raw" }
                view.editor(false,"Done",false)
                val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
                File(context.filesDir,"intl-symbols.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            }
            return JSONObject().put("secondary_symbols",17).put("all_secondary_labels_visible",true).put("fast_upward_flick",true).put("zero_brace_slide_left",true).put("zero_brace_fast_left_flick",true).put("p_backslash_slide_left",true).put("p_backslash_fast_left_flick",true).put("held_symbol_selection",true).put("dead_key_cases",7).put("shift_preserved",true).put("dedicated_apostrophe_removed",true).put("numeric_fields_unchanged",true).put("popup_screenshots_with_and_without_suggestions",true).put("event_delivery","Direct events to Lokey View")
        } finally { prefs.layout=original;test.runOnMainSync { activity.finish() } }
    }
    fun run(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val original=if(prefs.automaticLayout) "auto" else prefs.layout;val originalLanguage=prefs.setupLanguage
        val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val report=JSONArray()
        try {
            for(layout in KeyboardLayouts.all) test.runOnMainSync {
                prefs.layout=layout.id
                val typed=StringBuilder()
                val view=KeyboardView(activity,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) { typed.append(text) }
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
                view.layout(0,0,1080,view.measuredHeight);view.editor(false,"Done",false)
                val nodes=view.accessibilityNodeProvider
                var id=10
                for(row in layout.rows) {
                    if(row===layout.rows.last()) id++
                    for(letter in row) {
                        val description=nodes.createAccessibilityNodeInfo(id)!!.contentDescription.toString()
                        val label=if(letter=="\u200c") "Half space" else layout.label(letter,false)
                        check(description==label || description.startsWith(label+", hold for ")) { "${layout.id}: key $id $description, expected $label" }
                        nodes.performAction(id,AccessibilityNodeInfo.ACTION_CLICK,null);id++
                    }
                }
                check(typed.toString()==layout.rows.flatten().joinToString("") { layout.output(it,false) }) { "${layout.id}: $typed" }
                val shift=10+layout.rows.dropLast(1).sumOf { it.size }
                nodes.performAction(shift,AccessibilityNodeInfo.ACTION_CLICK,null)
                typed.clear();nodes.performAction(10,AccessibilityNodeInfo.ACTION_CLICK,null);nodes.performAction(11,AccessibilityNodeInfo.ACTION_CLICK,null)
                check(typed.toString()==layout.output(layout.rows[0][0],true)+layout.output(layout.rows[0][1],false)) { "${layout.id}: consumed shift $typed" }
                view.editor(false,"Done",false)
                val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
                File(context.filesDir,"layout-${layout.id}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                report.put(JSONObject().put("layout",layout.id).put("all_letters",true).put("one_letter_shift",true))
            }
            val accents=JSONArray()
            for((language,key,expected) in listOf(Triple("German","u","ü"),Triple("Romanian","i","î"),Triple("Czech","r","ř"),Triple("Dutch","j","j́"))) test.runOnMainSync {
                prefs.layout="qwerty";prefs.setupLanguage=language
                val typed=StringBuilder()
                val view=KeyboardView(activity,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) { typed.append(text) }
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
                view.layout(0,0,1080,view.measuredHeight);view.editor(false,"Done",false)
                val index="qwertyuiopasdfghjklzxcvbnm".indexOf(key)
                val id=10+index+if(index>=19) 1 else 0
                view.accessibilityNodeProvider.performAction(id,AccessibilityNodeInfo.ACTION_LONG_CLICK,null)
                check(typed.toString()==expected) { "$language: $typed, expected $expected" }
                view.prediction(emptyList(),FloatArray(26),false,"ro");typed.clear()
                view.accessibilityNodeProvider.performAction(17,AccessibilityNodeInfo.ACTION_LONG_CLICK,null)
                check(typed.toString()=="î") { "Detected language: $typed" }
                view.prediction(emptyList(),FloatArray(26),false,"de");typed.clear()
                val bounds=android.graphics.Rect();view.accessibilityNodeProvider.createAccessibilityNodeInfo(16)!!.getBoundsInParent(bounds)
                val down=SystemClock.uptimeMillis();val x=bounds.exactCenterX();val y=bounds.exactCenterY()
                fun touch(action:Int,at:Float) {
                    val event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,at,0)
                    view.onTouchEvent(event);event.recycle()
                }
                touch(android.view.MotionEvent.ACTION_DOWN,y)
                touch(android.view.MotionEvent.ACTION_MOVE,y-15*context.resources.displayMetrics.density)
                view.prediction(emptyList(),FloatArray(26),false,"it")
                touch(android.view.MotionEvent.ACTION_UP,y-15*context.resources.displayMetrics.density)
                check(typed.toString()=="ü") { "Accent changed under the finger: $typed" }
                typed.clear();view.accessibilityNodeProvider.performAction(16,AccessibilityNodeInfo.ACTION_LONG_CLICK,null)
                check(typed.toString()=="ù")
                view.prediction(emptyList(),FloatArray(26),false,"nl")
                if(language=="Dutch") {
                    val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
                    File(context.filesDir,"layout-dutch-accents.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                }
                accents.put(JSONObject().put("language",language).put("key",key).put("typed",expected).put("detected_language_updates_menu",true))
            }
            val composers=compositionChecks()
            val engine=Phonetic.shared(context);val candidates=JSONArray()
            for((input,expected) in listOf("nihao" to "你好","zhongguo" to "中国","beijing" to "北京","xiexie" to "谢谢","woaini" to "我爱你","xi'an" to "西安")) {
                val done=CountDownLatch(1);var choices=emptyList<String>();val start=SystemClock.elapsedRealtime()
                test.runOnMainSync { engine.request(input) { choices=it;done.countDown() } }
                check(done.await(15,TimeUnit.SECONDS)) { "Pinyin timed out: $input" }
                check(expected in choices) { "$input: ${choices.take(10)}" }
                if(input in listOf("nihao","zhongguo","beijing","xiexie")) check(choices.first()==expected) { "Default $input: ${choices.take(5)}" }
                candidates.put(JSONObject().put("input",input).put("first",choices.first()).put("ms",SystemClock.elapsedRealtime()-start))
            }
            val japanese=JSONArray()
            for((reading,word) in listOf("にほん" to "日本","わたし" to "私","とうきょう" to "東京")) {
                val done=CountDownLatch(1);var choices=emptyList<String>()
                test.runOnMainSync { Phonetic.shared(context,"japanese.tsv").request(reading) { choices=it;done.countDown() } }
                check(done.await(15,TimeUnit.SECONDS)) { "Japanese timed out" }
                check(word in choices) { "$reading: ${choices.take(10)}" }
                japanese.put(JSONObject().put("reading",reading).put("first",choices.first()).put("expected_in_candidates",word))
            }
            return JSONObject().put("keymaps",report).put("pinyin",candidates).put("japanese",japanese).put("new_input_methods",inputMethods(test,context)).put("composers",composers).put("accent_typing",accents)
        } finally { prefs.layout=original;prefs.setupLanguage=originalLanguage;test.runOnMainSync { activity.finish() } }
    }
    private fun inputMethods(test:Instrumentation,context:Context):JSONArray {
        val report=JSONArray()
        val fixtures=mapOf(
            "jyutping" to listOf("neihou" to "你好","nei5hou2" to "你好","nei5'hou2" to "你好","hoenggong" to "香港","mgoi" to "唔該","doze" to "多謝","gwongdungwaa" to "廣東話","gamjat" to "今日","m" to "唔","fu1" to "夫","fu2" to "虎"),
            "cangjie" to listOf("onf" to "你","vnd" to "好","onfvnd" to "你好"),
            "sucheng" to listOf("of" to "你","vd" to "好","ofvd" to "你好","hu" to "𫏮"),
            "hindi" to listOf("namaste" to "नमस्ते","hindi" to "हिंदी","dhanyavad" to "धन्यवाद","kripya" to "कृपया","mera" to "मेरा","khaa" to "खा")
        )
        for((method,cases) in fixtures) for((raw,expected) in cases) {
            val done=CountDownLatch(1);var choices=emptyList<String>();val start=SystemClock.elapsedRealtime()
            test.runOnMainSync { Phonetic.shared(context,"$method.tsv").request(raw) { choices=it;done.countDown() } }
            check(done.await(15,TimeUnit.SECONDS)) { "$method timed out: $raw" }
            check(expected in choices) { "$method $raw: ${choices.take(12)}; expected $expected" }
            if(method=="jyutping" && raw=="fu1") check("虎" !in choices)
            if(method=="jyutping" && raw=="fu2") check("夫" !in choices)
            if(method=="sucheng" && raw=="hu") check(choices.size>500) { "Rare shape candidates were truncated" }
            report.put(JSONObject().put("method",method).put("raw",raw).put("expected",expected).put("first",choices.first()).put("ms",SystemClock.elapsedRealtime()-start))
        }
        check("ृ" in KeyboardLayouts.find("hindi").variants("र"))
        check("ऋ" in KeyboardLayouts.find("hindi").variants("र"))
        check("๎" in KeyboardLayouts.find("thai").variants("ื"))
        check("ʼ" in KeyboardLayouts.find("ukrainian").variants("ь"))
        return report
    }
    private fun compositionChecks():JSONObject {
        val cases=mapOf(
            "korean" to listOf("ㅎㅏㄴㄱㅡㄹ" to "한글","ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ" to "안녕하세요","ㄱㅏㄴㅏ" to "가나","ㄱㅗㅏㄴ" to "관","ㄷㅏㄹㄱㅏ" to "달가"),
            "japanese" to listOf("konnichiha" to "こんにちは","nihongo" to "にほんご","gakkou" to "がっこう","kan'i" to "かんい","shinbun" to "しんぶん","Ko-hi-" to "コーヒー"),
            "vietnamese" to listOf("tieengs" to "tiếng","Vieetj" to "Việt","ddawngj" to "đặng","dduowngf" to "đường","chaof" to "chào","aaa" to "aa","aww" to "aw","ddd" to "dd"),
            "hindi_phonetic" to listOf("namaste" to "नमस्ते","khaa" to "खा","kRpaa" to "कृपा","Ta" to "ट","pha" to "फ")
        )
        val report=JSONObject()
        for((kind,words) in cases) {
            for((raw,expected) in words) check(Composers.display(kind,raw,true)==expected) { "$kind: $raw → ${Composers.display(kind,raw,true)}, expected $expected" }
            report.put(kind,words.size)
        }
        check(Japanese.kana(Japanese.delete("kya"))=="き")
        check(Hangul.render(Composers.delete("korean","ㅎㅏㄴ"))=="하")
        val locales=mapOf("en" to "intl","it" to "qwerty","fr" to "azerty","de" to "qwertz","es" to "spanish","pt" to "portuguese","tr" to "turkish","uk" to "ukrainian","ru" to "cyrillic","ar" to "arabic","fa" to "persian","el" to "greek","hi" to "hindi","ko" to "korean","ja" to "japanese","zh" to "pinyin","yue-HK" to "jyutping","vi" to "vietnamese","th" to "thai","bn" to "intl","he" to "intl","ur" to "intl")
        for((language,id) in locales) check(KeyboardLayouts.forLocale(java.util.Locale.forLanguageTag(language)).id==id) { language }
        check(KeyboardLayouts.families.size==11 && KeyboardLayouts.all.size==23)
        check("ї" in KeyboardLayouts.find("cyrillic").variants("и"))
        check("پ" in KeyboardLayouts.find("arabic").variants("ب"))
        check(KeyboardLayouts.predictionLanguage("Hello","Russian")==null)
        check(KeyboardLayouts.predictionLanguage("Hello","Arabic")==null)
        check(KeyboardLayouts.predictionLanguage("Selamat","Indonesian")=="Indonesian")
        for((text,language) in listOf("Привет" to "Russian","Привіт" to "Ukrainian","مرحبا" to "Arabic","خوبی" to "Persian","γεια" to "Greek","नमस्ते" to "Hindi","안녕" to "Korean","こんにちは" to "Japanese","你好" to "Chinese","สวัสดี" to "Thai","tiếng" to "Vietnamese"))
            check(KeyboardLayouts.predictionLanguage(text,null)==language) { text }
        check(KeyboardLayouts.predictionLanguage("你好","Cantonese")=="Cantonese")
        report.put("locale_cases",locales.size).put("families",11).put("variants",23)
        return report
    }
    fun picker(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val original=if(prefs.automaticLayout) "auto" else prefs.layout;val light=prefs.appLight
        var activity:Activity?=null
        fun find(view:View,label:String):View? {
            if(view.contentDescription?.toString()==label) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
            return null
        }
        fun capture(name:String) {
            SystemClock.sleep(200)
            test.runOnMainSync {
                val view=activity!!.window.decorView
                check(activity!!.hasWindowFocus()) { "Layout picker lost focus" }
                val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
                File(context.filesDir,name+".png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            }
        }
        try {
            for(isLight in listOf(false,true)) {
                prefs.layout="auto";prefs.appLight=isLight
                activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","feel").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                val until=SystemClock.uptimeMillis()+8000
                var focused=false
                while(SystemClock.uptimeMillis()<until) { test.runOnMainSync { focused=activity!!.hasWindowFocus() };if(focused) break;SystemClock.sleep(50) }
                check(focused)
                test.runOnMainSync {
                    val root=activity!!.window.decorView
                    val layout=find(root,"Keyboard layout settings")!!;val appearance=find(root,"Appearance and keyboard themes")!!
                    check(layout.top<appearance.top);layout.performClick()
                    check(find(activity!!.window.decorView,"Automatic keyboard layout")!=null)
                }
                capture("layout-picker-"+if(isLight) "light" else "dark")
                test.runOnMainSync {
                    val root=activity!!.window.decorView
                    find(root,"Keyboard layout: Cyrillic")!!.performClick()
                    check(prefs.layout=="cyrillic" && !prefs.automaticLayout)
                    find(root,"Layout variant: Ukrainian")!!.performClick();check(prefs.layout=="ukrainian")
                    find(root,"Keyboard layout: Arabic script")!!.performClick()
                    find(root,"Layout variant: Persian")!!.performClick();check(prefs.layout=="persian")
                    find(root,"Keyboard layout: QWERTY")!!.performClick()
                    find(root,"Layout variant: Vietnamese Telex")!!.performClick();check(prefs.layout=="vietnamese")
                    find(root,"Keyboard layout: Hindi")!!.performClick()
                    find(root,"Layout variant: Roman transliteration")!!.performClick();check(prefs.layout=="hindi_phonetic")
                    find(root,"Layout variant: Bolnagri")!!.performClick();check(prefs.layout=="bolnagri")
                    find(root,"Keyboard layout: Chinese & Cantonese")!!.performClick()
                    find(root,"Layout variant: Jyutping")!!.performClick();check(prefs.layout=="jyutping")
                    find(root,"Layout variant: Cangjie 5")!!.performClick();check(prefs.layout=="cangjie")
                    find(root,"Layout variant: Sucheng")!!.performClick();check(prefs.layout=="sucheng")
                    find(root,"Automatic keyboard layout")!!.performClick();check(prefs.automaticLayout)
                }
                test.runOnMainSync { activity!!.finish() };activity=null
            }
            return JSONObject().put("layout_before_appearance",true).put("family_and_variant_selection",true).put("automatic_restored",true).put("dark_and_light",true)
        } finally { prefs.layout=original;prefs.appLight=light;activity?.let { a -> test.runOnMainSync { a.finish() } } }
    }
    private fun editor(view:View):EditText? {
        if(view is EditText) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) editor(view.getChildAt(i))?.let { return it }
        return null
    }
    fun live(test:Instrumentation,context:Context):JSONObject {
        val prefs=Prefs(context);val original=if(prefs.automaticLayout) "auto" else prefs.layout
        val info=test.uiAutomation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo=info
        val report=JSONArray()
        try {
            val cases=listOf(
                Triple("qwerty","ciao","Ciao"),Triple("intl","'e","É"),Triple("spanish","niño","Niño"),Triple("portuguese","cç","Cç"),Triple("turkish","ıiş","Iiş"),
                Triple("azerty","aze","Aze"),Triple("qwertz","qwe","Qwe"),Triple("cyrillic","йцу","Йцу"),Triple("ukrainian","привіт","Привіт"),
                Triple("arabic","ضصث","ضصث"),Triple("persian","سلام","سلام"),Triple("greek","γεια","Γεια"),Triple("hindi","नमस्ते","नमस्ते"),Triple("bolnagri","नमस्ते","नमस्ते"),Triple("hindi_phonetic","namaste","नमस्ते "),
                Triple("korean","ㅎㅏㄴㄱㅡㄹ","한글"),Triple("japanese","toukyou","東京"),Triple("pinyin","nihao","你好"),Triple("jyutping","nei5hou2","你好"),Triple("cangjie","onfvnd","你好"),Triple("sucheng","ofvd","你好"),Triple("vietnamese","tieengs","Tiếng"),Triple("thai","สวัสดี","สวัสดี")
            )
            for((layout,raw,expected) in cases) {
                val keys=raw.map { KeyboardLayouts.display(it.toString()) }
                val firstKey=if(layout=="intl" && keys.first()=="'") "l" else keys.first()
                prefs.layout=layout
                val activity=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","lab").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                lateinit var input:EditText
                fun focused():Boolean { var ready=false;test.runOnMainSync { ready=activity.hasWindowFocus() };return ready }
                fun value():String { var value="";test.runOnMainSync { value=input.text.toString() };return value }
                fun node(label:String):AccessibilityNodeInfo? {
                    if(android.os.Build.VERSION.SDK_INT>=34) test.uiAutomation.clearCache()
                    val matches=ArrayList<AccessibilityNodeInfo>()
                    fun walk(n:AccessibilityNodeInfo) {
                        if(!n.refresh()) return
                        if(n.packageName==context.packageName && n.contentDescription?.toString()?.substringBefore(',')?.equals(label,true)==true) matches.add(n)
                        for(i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
                    }
                    for(window in test.uiAutomation.windows) if(window.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.root?.let { walk(it) }
                    return matches.firstOrNull { it.contentDescription.toString().substringBefore(',')==label } ?: matches.firstOrNull()
                }
                fun firstSuggestion():String? {
                    if(android.os.Build.VERSION.SDK_INT>=34) test.uiAutomation.clearCache()
                    fun find(n:AccessibilityNodeInfo):String? {
                        if(!n.refresh()) return null
                        val label=n.contentDescription?.toString().orEmpty()
                        if(n.packageName==context.packageName && label.startsWith("Suggestion: ")) return label.removePrefix("Suggestion: ")
                        for(i in 0 until n.childCount) n.getChild(i)?.let { find(it) }?.let { return it }
                        return null
                    }
                    return test.uiAutomation.windows.firstOrNull { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root?.let { find(it) }
                }
                fun await(condition:()->Boolean) {
                    val until=SystemClock.uptimeMillis()+15000
                    while(SystemClock.uptimeMillis()<until) {
                        if(focused() && condition()) return
                        SystemClock.sleep(50)
                    }
                    var details=""
                    test.runOnMainSync { details="focus=${activity.hasWindowFocus()}, editor=${input.hasFocus()}, active=${activity.getSystemService(InputMethodManager::class.java).isActive(input)}" }
                    val windows=test.uiAutomation.windows.joinToString { "${it.type}:${it.root?.packageName}" }
                    error("$layout timed out: ${value()}; $details; $windows")
                }
                fun action(label:String,operation:Int=AccessibilityNodeInfo.ACTION_CLICK) {
                    val apostrophe=layout=="intl" && label=="'"
                    val key=if(apostrophe) "l" else label
                    await { node(key)!=null };check(focused())
                    check(node(key)!!.performAction(if(apostrophe) AccessibilityNodeInfo.ACTION_LONG_CLICK else operation));SystemClock.sleep(30)
                }
                try {
                    test.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    val until=SystemClock.uptimeMillis()+8000
                    while(!focused() && SystemClock.uptimeMillis()<until) SystemClock.sleep(50)
                    check(focused())
                    test.runOnMainSync { input=editor(activity.window.decorView)!!;input.setText("");input.requestFocus();activity.getSystemService(InputMethodManager::class.java).showSoftInput(input,0) }
                    var restarted=false;val shown=SystemClock.uptimeMillis()
                    await {
                        if(node(firstKey)==null) test.runOnMainSync {
                            val manager=activity.getSystemService(InputMethodManager::class.java)
                            if(!restarted && SystemClock.uptimeMillis()-shown>500) { manager.restartInput(input);restarted=true }
                            manager.showSoftInput(input,0)
                            if(android.os.Build.VERSION.SDK_INT>=30) activity.window.insetsController?.show(android.view.WindowInsets.Type.ime())
                        }
                        node(firstKey)!=null
                    }
                    for(key in keys) action(key)
                    if(KeyboardLayouts.find(layout).phonetic) {
                        await { node("Suggestion: ${expected.trim()}")!=null }
                        action(if(layout=="sucheng") "Suggestion: ${expected.trim()}" else "space")
                    }
                    await { value()==expected }
                    check(focused())
                    test.uiAutomation.takeScreenshot()?.let { image -> File(context.filesDir,"layout-live-$layout.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle() }
                    if(layout=="pinyin") {
                        for(key in "shijie") action(key.toString())
                        await { node("Suggestion: 世界")!=null };action("space");await { value()=="你好世界" }
                        action("Backspace");await { value()=="你好世" }
                        test.runOnMainSync { input.setText("keep old end");input.setSelection(5,8) };SystemClock.sleep(150)
                        for(key in "nihao") action(key.toString())
                        await { node("Suggestion: 你好")!=null };action("space");await { value()=="keep 你好 end" }
                    }
                    if(layout=="hindi_phonetic") {
                        for(key in "mera") action(key.toString())
                        await { node("Suggestion: मेरा")!=null };action("space");await { value()=="नमस्ते मेरा " }
                        test.runOnMainSync { input.setText("keep old end");input.setSelection(5,8) };SystemClock.sleep(150)
                        for(key in "namaste") action(key.toString())
                        await { node("Suggestion: नमस्ते")!=null };action("Suggestion: नमस्ते");await { value()=="keep नमस्ते end" }
                    }
                    if(layout=="jyutping") {
                        action("1");await { value()=="你好1" }
                        test.runOnMainSync { input.setText("keep old end");input.setSelection(5,8) };SystemClock.sleep(150)
                        for(key in "neihou") action(key.toString())
                        await { node("Suggestion: 你好")!=null };action("space");await { value()=="keep 你好 end" }
                    }
                    if(layout=="sucheng") {
                        test.runOnMainSync { input.setText("") };SystemClock.sleep(150)
                        action("o");action("f");await { node("More candidates")!=null }
                        val first=firstSuggestion();action("More candidates")
                        val paged=firstSuggestion();check(paged!=null && first!=paged)
                        action("space");await { value()==paged }
                    }
                    if(layout=="intl") {
                        action("space");action("'");action("space");await { value()=="É '" }
                        action("'");action("Backspace");await { value()=="É '" }
                        test.runOnMainSync { input.setText("keep old end");input.setSelection(5,8) };SystemClock.sleep(150)
                        action("'");check(value()=="keep old end");action("e");await { value()=="keep é end" }
                        action("'");test.runOnMainSync { input.setSelection(0) };SystemClock.sleep(150)
                        action("e");await { value()=="Ekeep é end" }
                        test.runOnMainSync { input.setText("") };SystemClock.sleep(150)
                        action("q",AccessibilityNodeInfo.ACTION_LONG_CLICK);action("e");await { value()=="È" }
                        action("9",AccessibilityNodeInfo.ACTION_LONG_CLICK);action("0",AccessibilityNodeInfo.ACTION_LONG_CLICK);await { value()=="È[]" }
                    }
                    if(layout=="korean") {
                        action("Backspace");await { value()=="한그" };action("ㄹ");await { value()=="한글" }
                        action("space");await { value()=="한글 " }
                    }
                    if(layout=="vietnamese") {
                        action("Backspace");await { value()=="Tiến" };action("g");await { value()=="Tiếng" }
                        action("space");await { value()=="Tiếng " }
                    }
                    if(layout=="qwerty") {
                        action("?123",AccessibilityNodeInfo.ACTION_LONG_CLICK);action("Layout")
                        action("Keyboard layout: Cyrillic");action("Layout variant: Ukrainian");check(prefs.layout=="ukrainian")
                        action("Back to keyboard");await { node("ї")!=null }
                    }
                    report.put(JSONObject().put("layout",layout).put("typed",expected).put("live_input",true))
                    File(context.filesDir,"layout-details.json").writeText(report.toString(2))
                } finally { test.runOnMainSync { activity.finish() } }
            }
        } finally { prefs.layout=original }
        return JSONObject().put("layouts",report).put("pinyin_space_and_selection",true)
    }
}
