package org.lokey.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class KeyboardView(context: Context, private val prefs: Prefs, private val listener: Listener) : View(context) {
    private companion object {
        val tools=listOf("clipboard" to "Clipboard","translate" to "Translate","layout" to "Layout")
    }
    interface Listener {
        fun text(text: String)
        fun symbol(text: String) = text(text)
        fun delete()
        fun enter()
        fun cursor(delta: Int)
        fun voice(start: Boolean): Boolean
        fun pauseVoice(paused:Boolean):Boolean = false
        fun cancelVoice()
        fun glide(points: List<Glide.Point>, centers: Map<Char, Glide.Point>, caseMode: Int) { }
        fun nextKeyboard()
        fun candidate(word: String)
        fun feedback(key: String = "a", down: Boolean = true)
        fun tool(name:String) { }
        fun moreCandidates() { }
    }
    data class Key(val label: String, val alternate: String, val weight: Float = 1f, val code: String = label, val rect: RectF = RectF(), var row:Int=0,val native:Boolean=false,val custom:CustomLayouts.Key?=null,val anchor:RectF=RectF(),val menu:List<String> = emptyList())
    private data class Touch(val key: Key, val startX: Float, val startY: Float, var x: Float, var held: Boolean = false, var cursor: Boolean = false, var choice: Int = -1, var recording: Boolean = false, var stopping: Boolean = false, var flick: String? = null, var glide: Boolean = false, var committed:Boolean=false, val trace: MutableList<Glide.Point> = ArrayList(), val centers: Map<Char, Glide.Point> = emptyMap())
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelShape=Path()
    private val panelCorners=FloatArray(8)
    private val textBounds=Rect()
    private val labelArea=RectF()
    private data class LabelSpec(val text:String,val pixels:Float,val bold:Boolean)
    private data class LabelMetrics(val width:Float,val height:Int,val centerX:Float,val centerY:Float)
    // Only static key labels use this cache; suggestions and editor text do not.
    private val labelMetrics=object:LinkedHashMap<LabelSpec,LabelMetrics>(128,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<LabelSpec,LabelMetrics>)=size>256
    }
    private val regularFont = Typeface.create("sans-serif", Typeface.NORMAL)
    private val boldFont = Typeface.create("sans-serif", Typeface.BOLD)
    private val strokes = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val shiftIcon = context.getDrawable(R.drawable.ic_shift)!!.mutate()
    private val capsIcon = context.getDrawable(R.drawable.ic_caps_lock)!!.mutate()
    private val deleteIcon = context.getDrawable(R.drawable.ic_backspace)!!.mutate()
    private val keys = ArrayList<Key>()
    private var keysDirty=false
    private var adaptiveDirty=false
    private var glideCenters:Map<Char,Glide.Point>?=null
    private val touches = HashMap<Int, Touch>()
    private val callbacks = HashMap<Int, Runnable>()
    private var shift = false
    private var caps = false
    private var automaticCapital = false
    private var symbols = false
    private var toolsOpen=false
    private val toolBounds=Array(tools.size) { RectF() }
    private var symbolPage = false
    private var inputType = InputType.TYPE_CLASS_TEXT
    private val inputClass get()=inputType and InputType.TYPE_MASK_CLASS
    private val numeric get()=inputClass==InputType.TYPE_CLASS_NUMBER || inputClass==InputType.TYPE_CLASS_PHONE
    private var decimal = '.'
    private val landscape get()=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
    val split get()=landscape && !numeric && prefs.splitLandscape && previewLayout==null &&
        previewTheme==null && previewDesign==null && selectPreview==null && chooseKey==null
    val suggestionCount get()=if(split) 4 else 3
    private var splitEdge=0f
    private var numberInset=0f
    private fun inSplitGap(x:Float)=splitEdge>0f && x>=splitEdge && x<width-splitEdge
    private var enterLabel = "↵"
    private var probabilities = FloatArray(26)
    var adaptiveInput=true
        set(value) { if(field!=value) { field=value;adaptKeys();invalidate() } }
    var rawInput=false
    private var suggestions = emptyList<String>()
    private var accentLanguage:String?=null
    private var nextAccentLanguage:String?=null
    var moreCandidates=false
        set(value) { if(field!=value) { field=value;invalidate() } }
    var forceLatin=false
        set(value) { if(field!=value) { field=value;cancelTouches();requestLayout();layoutKeys();invalidate() } }
    var previewLayout:KeyboardLayouts.Layout?=null
    // Resolve preferences once per drawing or geometry pass.
    private var frameLayout:KeyboardLayouts.Layout?=null
    private val layout get()=frameLayout ?: previewLayout ?: if(forceLatin) KeyboardLayouts.find("qwerty") else prefs.currentLayout
    private val flowEnabled get()=!rawInput && prefs.flow && layout.flow
    private fun punctuation(value:String)=layout.punctuation(value)
    private fun nativeDigit(digit:Char):String? {
        val zero=when(layout.behaviorId) {
            "arabic" -> '٠';"persian" -> '۰';"hindi","bolnagri","hindi_phonetic" -> '०';"thai" -> '๐';else -> return null
        }
        return (zero+digit.digitToInt()).toString()
    }
    private fun customOutput(key:CustomLayouts.Key):String {
        if(!shift && !caps) return key.primary
        return layout.shifted[key.primary] ?: if(layout.cased) key.primary.uppercase(java.util.Locale.forLanguageTag(if(layout.behaviorId=="turkish") "tr" else "und")) else key.primary
    }
    private fun variants(code:String):List<String> {
        val native=if(numeric) emptyList() else layout.variants(code)
        if(native.isNotEmpty()) return native
        if(layout.composer=="dead") return emptyList()
        return if(layout.latin && !layout.phonetic) code.singleOrNull()?.let { LatinAccents.options(it,accentLanguage ?: prefs.setupLanguage) }.orEmpty() else emptyList()
    }
    private fun secondaryLabel(key:Key)=key.custom?.secondary?.joinToString(" ") ?: if(layout.composer=="dead" && !numeric && !symbols) layout.variants(key.code).joinToString(" ").ifEmpty { key.alternate } else key.alternate
    private fun symbolHints(key:Key)=key.custom==null && layout.composer=="dead" && !numeric && !symbols && key.code.length==1 && key.code!=","
    private fun period(key:Key)=!numeric && key.custom==null && !key.native && key.code=="."
    private fun fitSize(text:String,size:Float,available:Float):Float {
        paint.textSize=dp(size);paint.typeface=regularFont
        return min(size,size*available.coerceAtLeast(1f)/paint.measureText(text).coerceAtLeast(1f))
    }
    private fun fitKeyLabel(text:String,size:Float,area:RectF,bold:Boolean=false):Float {
        val metrics=metrics(text,size,bold)
        return size*min(1f,min(area.width().coerceAtLeast(0f)/metrics.width.coerceAtLeast(1f),area.height().coerceAtLeast(0f)/metrics.height.coerceAtLeast(1)))
    }
    private fun metrics(text:String,size:Float,bold:Boolean=false):LabelMetrics {
        val spec=LabelSpec(text,dp(size),bold)
        return labelMetrics.getOrPut(spec) {
            paint.textSize=spec.pixels;paint.typeface=if(bold) boldFont else regularFont;paint.textAlign=Paint.Align.LEFT
            paint.getTextBounds(text,0,text.length,textBounds)
            LabelMetrics(max(paint.measureText(text),textBounds.width().toFloat()),textBounds.height(),textBounds.exactCenterX(),textBounds.exactCenterY())
        }
    }
    private fun keyLabel(canvas:Canvas,text:String,area:RectF,size:Float,color:Int,bold:Boolean=false) {
        val fitted=fitKeyLabel(text,size,area,bold)
        if(fitted<=0f) return
        val metrics=metrics(text,fitted,bold)
        label(canvas,text,area.centerX()-metrics.centerX,area.centerY()-metrics.centerY,fitted,color,Paint.Align.LEFT,bold)
    }
    private fun hintArea(key:Key,area:RectF) {
        area.set(key.rect.left+dp(3f),key.rect.top+dp(3f),key.rect.right-dp(3f),key.rect.top+key.rect.height()*.36f-dp(2f))
    }
    private fun units()=293f+(if(numeric || symbols) 0 else if(layout.keyRows.isNotEmpty()) layout.keyRows.size-5 else layout.rows.size-3)*48
    private fun baseHeight()=(if(landscape) 260f else 325f)*units()/325f
    private var showSuggestions=true
    var suggestionsVisible:Boolean
        get()=showSuggestions && !numeric
        set(value) {
            if(showSuggestions==value) return
            showSuggestions=value;cancelTouches();requestLayout();layoutKeys();invalidate()
        }
    private var probabilistic = false
    private var status = ""
    private var idleAmount = 0f
    private var idleAnimation: ValueAnimator? = null
    private val idle = Runnable {
        if (touches.isEmpty() && resize == null && voiceState.isEmpty() && isShown && previewTheme==null) {
            idleAnimation = ValueAnimator.ofFloat(idleAmount, 1f).apply {
                duration = 180
                addUpdateListener { idleAmount = it.animatedValue as Float; layoutKeys(); invalidate() }
                start()
            }
        }
    }
    private data class Resize(val id: Int, val rawY: Float, val initial: Int, var held: Boolean = false, var cancelled: Boolean = false)
    private var resize: Resize? = null
    private var resizeHeight: Int? = null
    private val resizeHold = Runnable { resize?.let { if (!it.cancelled) { it.held = true; listener.feedback("hold"); invalidate() } } }
    fun activity() {
        main.removeCallbacks(idle); idleAnimation?.cancel(); idleAnimation = null
        if (idleAmount != 0f) { idleAmount = 0f; layoutKeys(); invalidate() }
        if (isAttachedToWindow) main.postDelayed(idle, 3000)
    }
    var recordingLocked = false
        private set
    var speechAllowed=true
        set(value) { if(field!=value) { field=value;invalidate() } }
    var floatingSpeech=false
        set(value) { if(field!=value) { field=value;invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) } }
    var recordingPaused=false
        private set
    private val speechBounds=Array(2) { RectF() }
    private var speechPointer=-1
    private var speechAction=-1
    private val speechButton get()=prefs.oneTapSpeech && speechAllowed && !floatingSpeech && suggestionsVisible && !moreCandidates &&
        previewTheme==null && previewDesign==null && previewLayout==null && selectPreview==null && chooseKey==null
    private fun speechRects() {
        speechBounds.forEach { it.setEmpty() }
        if(!speechButton || voiceState.isNotEmpty() && !listening) return
        val right=width-dp(6f);val cell=dp(44f)
        val top=(48*unit()-cell)/2
        speechBounds[0].set(right-cell,top,right,top+cell)
        if(listening) speechBounds[1].set(right-2*cell,top,right-cell,top+cell)
    }
    private fun speechAction(index:Int):Boolean {
        if(!speechButton) return false
        if(!listening) {
            if(index!=0 || !startRecording()) return false
            recordingLocked=true
        } else if(index==0) stopRecording()
        else {
            val next=!recordingPaused
            if(!listener.pauseVoice(next)) return false
            recordingPaused=next
        }
        activity();invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        return true
    }
    var voiceState = ""
        set(value) {
            if (field != value) {
                if (value == "Listening") { levels.fill(0f); levelHead = 0; levelCount = 0; elapsedMillis = 0; lastBucket = -1 }
                if (value != "Listening") { recordingLocked = false;recordingPaused=false }
                field = value; keepScreenOn = value == "Listening"; activity(); invalidate()
                sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
        }
    private val levels = FloatArray(64)
    private var levelHead = 0
    private var levelCount = 0
    private var lastBucket = -1L
    private var elapsedMillis = 0L
    private val listening get() = voiceState == "Listening"
    fun lockRecording() { if(listening) { recordingLocked=true;invalidate() } }
    fun pauseRecording(value:Boolean) { if(listening) { recordingPaused=value;invalidate() } }
    fun audioLevel(level: Float, millis: Long) {
        if (!listening || recordingPaused) return
        elapsedMillis = millis
        val amplitude = (kotlin.math.sqrt(level.coerceIn(0f, 1f)) * 2f).coerceAtMost(1f)
        val bucket = millis / 125
        if (bucket != lastBucket) {
            levels[levelHead] = amplitude; levelHead = (levelHead + 1) % levels.size
            levelCount = min(levels.size, levelCount + 1); lastBucket = bucket
        } else {
            val index = (levelHead + levels.size - 1) % levels.size
            levels[index] = max(levels[index], amplitude)
        }
        invalidate()
    }
    private val candidateBounds = Array(4) { RectF() }
    private val shownSuggestions get()=if(suggestionsVisible) min(suggestions.size,if(splitEdge>0f) 4 else 3) else 0
    private fun candidateRects() {
        val reserve=dp(if(moreCandidates) 32f else if(speechButton) 50f else 0f)
        val margin=dp(12f)
        val count=if(splitEdge>0f) 2 else 3
        for(i in 0 until shownSuggestions) {
            val start=if(i>=count) width-splitEdge+margin else margin
            val end=if(splitEdge>0f && i<count) splitEdge-margin else width-margin-reserve
            val cell=max(0f,end-start)/count
            val column=i%count
            candidateBounds[i].set(start+column*cell,0f,start+(column+1)*cell,46*unit())
        }
    }
    private var palette = KeyboardTheme.colors(prefs)
    private val bg get()=palette.background
    private val keyColor get()=palette.keys
    private val ink get()=palette.ink
    private val muted get()=palette.muted
    private val accent get()=palette.accent
    private var imageVersion=Long.MIN_VALUE
    private var imageRequest=0
    private var keycapBitmap:Bitmap?=null
    private var keycapShader:BitmapShader?=null
    private val imageMatrix=Matrix()
    private val imageInverse=Matrix()
    private val imagePoint=FloatArray(2)
    private var imageLabels=false
    var previewTheme:String?=null
    var previewDesign:DesignPresets.Design?=null
    var selectPreview:(()->Unit)?=null
    var chooseKey:((String)->Unit)?=null
    private fun dp(value: Float) = value * resources.displayMetrics.density
    init {
        layoutDirection=LAYOUT_DIRECTION_LTR
        isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isSoundEffectsEnabled = false; isHapticFeedbackEnabled = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun editor(numeric:Boolean, action:String, capital:Boolean) =
        editor(if(numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT,action,capital)
    fun editor(inputType:Int, action:String, capital:Boolean, decimal:Char='.') {
        this.inputType=inputType;this.decimal=decimal
        enterLabel=action;shift=capital && !numeric;automaticCapital=shift;caps=false;symbols=false;symbolPage=false
        probabilities=FloatArray(26);probabilistic=false
        cancelTouches();accentLanguage=null;nextAccentLanguage=null;activity(); layoutKeys(); invalidate()
    }
    fun updateGeometry() {
        cancelTouches();layoutKeys();invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
    fun action(label:String) { if(enterLabel!=label) { enterLabel=label;layoutKeys();invalidate() } }
    fun prediction(words: List<String>, letters: FloatArray, probabilistic: Boolean,language:String?=null) {
        val accents=accentLanguage!=(language ?: nextAccentLanguage)
        val geometry=this.probabilistic!=probabilistic || adaptiveTouch() && !probabilities.contentEquals(letters)
        if(language!=null) nextAccentLanguage=language
        if(touches.isEmpty()) accentLanguage=nextAccentLanguage
        val next=words.take(candidateBounds.size)
        val changed=suggestions!=next
        suggestions = next; probabilities = letters.copyOf(); this.probabilistic = probabilistic
        if(changed) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        if(accents) layoutKeys() else if(geometry) adaptKeys()
        if(changed || accents || geometry) invalidate()
    }
    fun capitalization(capital: Boolean) {
        // A repeated editor hint must not restore Shift after it was consumed or turned off.
        if (capital == automaticCapital) return
        automaticCapital = capital
        if (!caps && shift != capital) { shift = capital; layoutKeys(); invalidate() }
    }
    fun status(text: String) { val value=context.tr(text);if(status!=value) { status=value;invalidate() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired=(dp(baseHeight()*(resizeHeight ?: prefs.height)/100f)*(if(suggestionsVisible) 1f else (units()-48)/units())).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { imageVersion=Long.MIN_VALUE;imageRequest++;layoutKeys() }
    override fun onConfigurationChanged(newConfig:Configuration) {
        super.onConfigurationChanged(newConfig);labelMetrics.clear();updateGeometry()
    }
    private fun unit()=height/(units()-(if(suggestionsVisible) 0 else 48))
    private fun layoutKeys() {
        keysDirty=true
        if (width == 0 || height == 0 || touches.isNotEmpty()) return
        val previous=frameLayout;frameLayout=layout
        try { buildKeys() } finally { frameLayout=previous }
    }
    private fun buildKeys() {
        keysDirty=false;adaptiveDirty=false;glideCenters=null
        keys.clear()
        val u = unit()
        val margin = dp(5f)
        val gap = dp(4f)
        val rowHeight = 48 * u
        var rowIndex=0
        splitEdge=if(split) min(dp(340f),width*.39f) else 0f
        numberInset=if(numeric) max(0f,(width-dp(380f))/2f) else 0f
        fun place(items:List<Key?>, y:Float, h:Float, left:Float, right:Float) {
            if(items.isEmpty()) return
            val available=right-left
            val sum=items.sumOf { (it?.weight ?: 1f).toDouble() }.toFloat()
            val minimum=if(items.any { it?.custom!=null }) min(dp(4f),(available-gap*(items.size-1))/items.size) else 0f
            val flexible=available-gap*(items.size-1)-minimum*items.size
            var x=left
            items.forEach { key ->
                val weight=key?.weight ?: 1f
                val w=if(minimum>0f) minimum+flexible*weight/sum else (available+gap)*weight/sum-gap
                if(key!=null) {
                    val top=y-(if(suggestionsVisible) 0f else 48*u)
                    key.rect.set(x,top,x+w,top+h-5*u)
                    key.anchor.set(key.rect);key.row=rowIndex;keys.add(key)
                }
                x+=w+gap
            }
        }
        fun row(items: List<Key>, y: Float, inset: Float = 0f, h: Float = rowHeight) {
            val edge=margin+inset
            if(splitEdge==0f) place(items,y,h,edge,width-edge)
            else {
                val middle=items.sumOf { it.weight.toDouble() }/2
                var weight=0.0
                val space=items.indexOfFirst { key ->
                    val start=weight
                    weight+=key.weight
                    key.code=="space" && start<=middle && weight>=middle
                }
                val cut=(items.size+1)/2
                val left:List<Key>
                val right:List<Key>
                if(space<0) {
                    left=items.take(cut);right=items.drop(cut)
                } else {
                    val key=items[space]
                    val half=key.copy(weight=key.weight/2)
                    left=items.take(space)+half
                    right=listOf(half.copy(rect=RectF(),anchor=RectF()))+items.drop(space+1)
                }
                place(left,y,h,edge,splitEdge)
                place(right,y,h,width-splitEdge,width-edge)
            }
            rowIndex++
        }
        fun key(value:String,menu:List<String> = emptyList())=Key(value,menu.firstOrNull().orEmpty(),menu=menu)
        fun digit(value:Char,menu:List<String> = emptyList())=key(value.toString(),menu+listOfNotNull(nativeDigit(value)))
        if (numeric) {
            val phone=inputClass==InputType.TYPE_CLASS_PHONE
            val signed=inputType and InputType.TYPE_NUMBER_FLAG_SIGNED!=0
            val fractional=inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL!=0
            fun pad(digits:String,action:Key?) {
                place(digits.map { digit(it) }+action,(48+60*rowIndex)*u,60*u,margin+numberInset,width-margin-numberInset)
                rowIndex++
            }
            pad("123",when { phone -> key("+");signed -> key("-",listOf("+"));else -> null })
            pad("456",if(phone) key(",",listOf(";")) else null)
            pad("789",Key("⌫","hold",code="delete"))
            val separator=decimal.toString()
            val alternateSeparator=if(decimal=='.') "," else "."
            val bottom=listOf(
                when { phone -> key("*");fractional -> key(alternateSeparator);else -> null },
                digit('0',if(phone) listOf("+") else emptyList()),
                when { phone -> key("#");fractional -> key(separator,if(decimal in ".,") emptyList() else listOf(","));else -> null },
                Key(enterLabel,context.tr("hold · voice"),code="enter")
            )
            place(bottom,228*u,60*u,margin+numberInset,width-margin-numberInset)
            return
        }
        if(!symbols && layout.keyRows.isNotEmpty()) {
            layout.keyRows.forEachIndexed { index,values ->
                row(values.map { value ->
                    val action=value.action
                    val label=when(action) {
                        "shift" -> if(caps) "⇪" else "⇧"
                        "delete" -> "⌫"
                        "symbols" -> "?123"
                        "space" -> "space"
                        "enter" -> enterLabel
                        else -> KeyboardLayouts.display(customOutput(value))
                    }
                    val hint=when(action) { "shift" -> "caps";"delete" -> "hold";"symbols" -> "tools";"space" -> context.tr("Hold for cursor");"enter" -> context.tr("hold · voice");else -> value.secondary.firstOrNull().orEmpty() }
                    Key(label,hint,value.width,action.ifEmpty { if(value.primary in CustomLayouts.actions) "literal:${value.primary}" else value.primary },custom=value)
                },(48+index*48)*u,h=(if(index==layout.keyRows.lastIndex) 53 else 48)*u)
            }
            return
        }
        fun nativeLetters(items:List<String>)=items.map { code ->
            var secondary=if(layout.composer in listOf("cangjie","sucheng")) KeyboardLayouts.radical(code) else variants(code).firstOrNull().orEmpty()
            if(shift && layout.cased) secondary=uppercaseVariant(secondary)
            Key(layout.label(code,shift || caps),secondary,code=code,native=true)
        }
        val showNumbers=symbols || prefs.numberRow
        if(showNumbers) row("1234567890".mapIndexed { i,c ->
            val code=c.toString()
            if(symbols) digit(c) else Key(code,variants(code).firstOrNull() ?: "!@#$%^&*()"[i].toString())
        },48*u)
        // Keep saved row colors tied to their original rows when the number row is hidden.
        if(!showNumbers) rowIndex++
        if (!symbols) {
            val letterTop=if(showNumbers) 96f else 48f
            val letterHeight=if(showNumbers) 48f else 48f+48f/layout.rows.size
            layout.rows.forEachIndexed { index,letters ->
                val inset=if(index==1 && letters.size<layout.rows.first().size) dp(15f) else 0f
                val keys=nativeLetters(letters)
                val values=if(index==layout.rows.lastIndex) listOf(Key(if(caps) "⇪" else "⇧",if(caps) "lock" else if(shift) "once" else "caps",1.35f,"shift"))+keys+Key("⌫","hold",1.35f,"delete") else keys
                row(values,(letterTop+index*letterHeight)*u,inset,h=letterHeight*u)
            }
        } else {
            fun marks(chars:String)=chars.map { char ->
                val code=char.toString()
                val localized=punctuation(code)
                val label=if(layout.behaviorId=="greek" && code=="?") code else localized
                val extras=when(char) { '?' -> "¿";'!' -> "¡";'"' -> "«»“”";'\'' -> "‘’";'*' -> "^";else -> "" }
                key(label,(listOf(code,localized)+extras.map { it.toString() }).distinct().filter { it!=label })
            }
            row(marks(if(symbolPage) "~`|•√π÷×¶∆" else "@#\$€%&-+()"),96*u)
            row(marks(if(symbolPage) "£¥¢©®™✓§^" else "\"':;/_=!?"),144*u,dp(15f))
            row(listOf(Key(if(symbolPage) "123" else "=/<","more",1.35f,"page"))+
                marks(if(symbolPage) "…°∞≠≤≥≈¿¡" else "[]{}<>\\*")+Key("⌫","hold",1.35f,"delete"),192*u)
        }
        val bottom=mutableListOf(Key(if (symbols) "ABC" else "?123", "tools", 1.5f, "symbols"), Key(punctuation(","), "😊", 0.9f, ","),
            Key("space", context.tr("Hold for cursor"), (if(layout.id=="persian") 4f else 4.9f)-idleAmount*.3f,"space"),
            Key(punctuation("."),listOf("!",",","?").joinToString(" ") { punctuation(it) },0.9f,"."), Key(enterLabel, context.tr("hold · voice"), 1.5f + idleAmount * 0.3f, "enter"))
        if(layout.id=="persian") bottom.add(3,Key("½","",.9f,"\u200c",native=true))
        row(bottom,(units()-53)*u,h=53*u)
        adaptKeys()
    }
    private fun adaptKeys() {
        adaptiveDirty=true
        if(touches.isNotEmpty() || keysDirty) return
        adaptiveDirty=false
        for(key in keys) key.rect.set(key.anchor)
        if(!adaptiveTouch() || !probabilistic) return
        val gap=dp(4f)
        for(index in 1 until keys.size) {
            val a=keys[index-1];val b=keys[index]
            if(a.row!=b.row) continue
            if(b.anchor.left-a.anchor.right>gap+1f) continue
            val pitch=b.anchor.centerX()-a.anchor.centerX()
            val shift=AdaptiveKeys.boundary(a.code,b.code,probabilities,pitch)
            a.rect.right+=shift;b.rect.left+=shift
        }
    }
    override fun onDraw(canvas: Canvas) {
        val previous=frameLayout;frameLayout=layout
        try { drawKeyboard(canvas) } finally { frameLayout=previous }
    }
    private fun drawKeyboard(canvas:Canvas) {
        val saved=previewDesign
        val theme=saved?.settings?.getString("keyboard_theme") ?: previewTheme ?: prefs.keyboardTheme
        val contrast=theme==KeyboardTheme.HIGH_CONTRAST
        palette=if(saved!=null) KeyboardTheme.colors(saved.settings) else KeyboardTheme.colors(prefs,theme)
        val builtin=theme=="wave" || theme=="image" && (saved?.settings?.optString("theme_picture") ?: prefs.themePicture)=="wave" && (previewTheme==null || saved!=null)
        val picture=theme=="image" || theme=="wave"
        if(!picture && imageVersion!=Long.MIN_VALUE) {
            imageVersion=Long.MIN_VALUE;imageRequest++;keycapBitmap=null;keycapShader=null
        }
        val requested=if(builtin) -1L else prefs.themeImageVersion
        if(picture && imageVersion!=requested) {
            val version=requested;imageVersion=version;val request=++imageRequest
            val file=when {
                saved?.image?.isNotEmpty()==true -> saved.imageFile(prefs)
                saved==null && previewTheme=="image" && KeyboardTheme.uploadFile(context).exists() -> KeyboardTheme.uploadFile(context)
                else -> KeyboardTheme.imageFile(context)
            }
            KeyboardTheme.image(context.applicationContext,version,width,kotlin.math.ceil((units()-48)*unit()).toInt(),builtin,file) { bitmap -> main.post {
                if(imageRequest==request) { keycapBitmap=bitmap;keycapShader=bitmap?.let { BitmapShader(it,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP) };invalidate() }
            } }
        }
        val bitmap=keycapBitmap.takeIf { picture }
        if(bitmap!=null) {
            val top=(if(suggestionsVisible) 48 else 0)*unit();val area=(units()-48)*unit();val scale=max(width.toFloat()/bitmap.width,area/bitmap.height)
            imageMatrix.setScale(scale,scale);imageMatrix.postTranslate((width-bitmap.width*scale)/2,top+(area-bitmap.height*scale)/2)
            keycapShader?.setLocalMatrix(imageMatrix);imageMatrix.invert(imageInverse)
        }
        val u = unit()
        drawBackground(canvas,u)
        val leftPanel=if(splitEdge>0f) splitEdge else width.toFloat()
        speechRects()
        if(suggestionsVisible) {
            if (resize?.held == true) {
                val caption=context.tr("Drag to resize · %1\$s%%",resizeHeight ?: prefs.height)
                label(canvas,caption,leftPanel/2,29*u,fitSize(caption,14f,leftPanel-dp(16f)),ink)
            } else if (voiceState.isNotEmpty()) {
                if (listening) {
                    paint.color = accent; canvas.drawCircle(dp(10f), 24 * u, dp(2.5f), paint)
                    val seconds = elapsedMillis / 1000
                    label(canvas, "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", dp(20f), 29 * u, 12f, ink, Paint.Align.LEFT)
                    val left=dp(65f)
                    val right=leftPanel-dp(if(splitEdge>0f) 8f else if(speechButton) 164f else if(recordingLocked) 93f else 72f)
                    val step = dp(5f); val bars = ((right - left) / step).toInt().coerceAtLeast(1)
                    val phase = (elapsedMillis % 125) / 125f
                    for (i in 0 until min(bars, levelCount)) {
                        val x = right - (i + phase) * step
                        if (x < left) break
                        val level = levels[(levelHead - 1 - i + levels.size) % levels.size]
                        val h = max(dp(3f), level * 24 * u)
                        paint.color = accent; paint.alpha = (64 + 191 * (1f - i.toFloat() / bars)).toInt()
                        canvas.drawRoundRect(x - dp(1.3f), 24 * u - h / 2, x + dp(1.3f), 24 * u + h / 2, dp(2f), dp(2f), paint)
                    }
                    if (recordingLocked && !speechButton) drawLock(canvas, width - dp(80f), 24 * u, accent)
                } else {
                    val message=context.tr(voiceState)
                    val available=leftPanel-dp(if(splitEdge>0f) 0f else 60f)
                    label(canvas,message,available/2,29*u,fitSize(message,12f,available-dp(16f)),ink)
                }
                val cancel=context.tr("Cancel")
                label(canvas, cancel, width - dp(if(speechButton && listening) 125f else 33f), 29 * u, fitSize(cancel,12f,dp(60f)), muted)
            } else {
                candidateRects()
                for(index in 0 until shownSuggestions) {
                    val rect=candidateBounds[index];val word=suggestions[index]
                    label(canvas,word,rect.centerX(),29*u,fitSize(word,16f,rect.width()-dp(12f)),if(index==0) ink else muted,bold=index==0)
                    if(if(splitEdge>0f) index%2==0 else index<2) {
                        paint.color=keyColor;canvas.drawRect(rect.right,15*u,rect.right+dp(.5f),34*u,paint)
                    }
                }
            }
            if(moreCandidates) label(canvas,"›",width-dp(16f),30*u,24f,accent)
            paint.color = if (resize?.held == true) accent else muted
            paint.alpha = if (resize?.held == true) 255 else 100
            fun grip(center:Float) { canvas.drawRoundRect(center-dp(15f),dp(3f),center+dp(15f),dp(4.2f),dp(1f),dp(1f),paint) }
            grip(leftPanel/2)
            if(splitEdge>0f) grip(width-leftPanel/2)
            paint.alpha = 255
            if(!speechBounds[0].isEmpty) {
                val area=RectF(speechBounds[if(listening) 1 else 0]);area.right=speechBounds[0].right
                paint.color=accent;canvas.drawRoundRect(area,dp(22f),dp(22f),paint)
                val color=KeyboardTheme.ink(accent)
                val stop=speechBounds[0];val x=stop.centerX();val y=stop.centerY()
                if(!listening) drawMic(canvas,x,y,color)
                else {
                    paint.color=color;canvas.drawRoundRect(x-dp(6f),y-dp(6f),x+dp(6f),y+dp(6f),dp(2f),dp(2f),paint)
                    val pause=speechBounds[1];val px=pause.centerX()
                    if(recordingPaused) {
                        val path=Path().apply { moveTo(px-dp(4f),y-dp(7f));lineTo(px+dp(7f),y);lineTo(px-dp(4f),y+dp(7f));close() }
                        canvas.drawPath(path,paint)
                    } else {
                        canvas.drawRect(px-dp(5f),y-dp(7f),px-dp(1f),y+dp(7f),paint)
                        canvas.drawRect(px+dp(1f),y-dp(7f),px+dp(5f),y+dp(7f),paint)
                    }
                }
            }
        }
        var symbolSize=12f
        for(key in keys) if(symbolHints(key)) {
            val hints=secondaryLabel(key).split(' ').filter { it.isNotEmpty() }
            hintArea(key,labelArea)
            labelArea.right=labelArea.left+labelArea.width()/maxOf(1,hints.size)
            for(hint in hints) symbolSize=min(symbolSize,fitKeyLabel(hint,12f,labelArea))
        }
        for (key in keys) {
            val pressed = touches.values.any { it.key === key }
            val special = key.code == "shift" || key.code == "delete" || key.code == "symbols" || key.code == "page"
            val colorCode=key.custom?.id ?: key.code
            val custom=if(theme=="custom" || theme=="image") if(saved==null) prefs.keyColor(colorCode) else saved.settings.opt("key_color.$colorCode") as? Int else null
            val rowColor=if(theme=="custom" || theme=="image") if(saved==null) prefs.rowColor(key.row) else saved.settings.opt("row_color.${key.row}") as? Int else null
            val active=key.code=="enter" || key.code=="shift" && shift
            val fill=when { active -> custom ?: accent; contrast -> keyColor; custom!=null -> custom; rowColor!=null -> rowColor; key.code=="space" -> palette.space; special -> palette.special; else -> keyColor }
            paint.color=if(pressed) KeyboardTheme.blend(fill,KeyboardTheme.ink(fill),.17f) else fill
            var textColor=if(contrast) Color.BLACK else KeyboardTheme.ink(fill)
            if(bitmap!=null && !active && custom==null && rowColor==null) {
                canvas.drawRoundRect(key.rect,dp(8f),dp(8f),paint)
                paint.shader=keycapShader
                imagePoint[0]=key.rect.centerX();imagePoint[1]=key.rect.centerY();imageInverse.mapPoints(imagePoint)
                val pixel=bitmap.getPixel(imagePoint[0].toInt().coerceIn(0,bitmap.width-1),imagePoint[1].toInt().coerceIn(0,bitmap.height-1))
                textColor=KeyboardTheme.ink(KeyboardTheme.blend(fill,pixel,Color.alpha(pixel)/255f))
            }
            canvas.drawRoundRect(key.rect, dp(8f), dp(8f), paint);paint.shader=null
            if(bitmap!=null && pressed) { paint.color=0x33777777;canvas.drawRoundRect(key.rect,dp(8f),dp(8f),paint) }
            if(!contrast) {
                paint.color = palette.shadow
                canvas.drawRoundRect(key.rect.left, key.rect.bottom - dp(2f), key.rect.right, key.rect.bottom + dp(1f), dp(8f), dp(8f), paint)
            }
            val color = textColor
            imageLabels=bitmap!=null && !active && custom==null && rowColor==null
            if (key.code == "space") {
                val cursor=touches.values.any { it.cursor }
                val caption=if(cursor) context.tr("‹  Move cursor  ›") else context.tr("Hold for cursor")
                label(canvas,caption,key.rect.centerX(),if(cursor) key.rect.centerY()+dp(4f) else key.rect.bottom-dp(5f),
                    fitSize(caption,if(cursor || contrast) 11f else 9f,key.rect.width()-dp(8f)),if(bitmap!=null || contrast) color else KeyboardTheme.blend(color,fill,.3f))
            } else if (key.code == "shift") {
                drawIcon(canvas, if (caps) capsIcon else shiftIcon, key.rect.centerX(), key.rect.centerY() - dp(4f), 24f, color)
                val caption=context.tr(layout.shiftLabel)
                label(canvas, caption, key.rect.centerX(), key.rect.bottom - dp(5f), fitSize(caption,9f,key.rect.width()-dp(8f)), color)
            } else if (key.code == "delete") {
                drawIcon(canvas, deleteIcon, key.rect.centerX(), key.rect.centerY(), 28f, color)
            } else if (key.code == "enter") {
                if (listening) {
                    paint.color = color
                    canvas.drawRoundRect(key.rect.centerX() - dp(8f), key.rect.centerY() - dp(12f), key.rect.centerX() + dp(8f), key.rect.centerY() + dp(4f), dp(2f), dp(2f), paint)
                    label(canvas, context.tr("Stop"), key.rect.centerX(), key.rect.bottom - dp(5f), 9f, color)
                } else if (voiceState.isNotEmpty()) {
                    label(canvas, "…", key.rect.centerX(), key.rect.centerY() + dp(5f), 23f, color)
                } else {
                    val x = key.rect.centerX(); val y = key.rect.centerY() - dp(4f)
                    drawMic(canvas, x, y, color)
                    val action = when (key.label) { "↵" -> context.tr("Enter"); "⌕" -> context.tr("Search"); else -> key.label }
                    label(canvas, action, x, key.rect.bottom - dp(5f), fitSize(action,9f,key.rect.width()-dp(8f)), color)
                }
            } else {
                val baseSize=if(key.label.length>2) { if(contrast) 16f else 13f } else { if(contrast) 30f else 22f }
                // Shared bands keep letter baselines steady when hints or priors change.
                val half=min(key.anchor.centerX()-key.rect.left,key.rect.right-key.anchor.centerX())-dp(4f)
                labelArea.set(key.anchor.centerX()-half,key.rect.top+key.rect.height()*.36f,key.anchor.centerX()+half,key.rect.bottom-dp(3f))
                keyLabel(canvas,key.label,labelArea,baseSize,color,bold=contrast)
                val hint=if(key.code in listOf("shift","delete","symbols","page","enter")) context.tr(secondaryLabel(key)) else secondaryLabel(key)
                if(hint.isNotEmpty()) {
                    hintArea(key,labelArea)
                    if(symbolHints(key)) {
                        val hints=hint.split(' ').filter { it.isNotEmpty() }
                        val cell=labelArea.width()/hints.size
                        for((index,value) in hints.withIndex()) {
                            labelArea.left=key.rect.left+dp(3f)+index*cell;labelArea.right=labelArea.left+cell
                            keyLabel(canvas,value,labelArea,symbolSize,if(bitmap!=null || contrast) color else KeyboardTheme.blend(color,fill,.15f))
                        }
                    } else {
                        val size=fitKeyLabel(hint,if(hint.length>2) 7f else 9f,labelArea)
                        labelArea.left=labelArea.right-metrics(hint,size).width
                        keyLabel(canvas,hint,labelArea,size,if(bitmap!=null || contrast) color else KeyboardTheme.blend(color,fill,.35f))
                    }
                }
            }
        }
        imageLabels=false
        val footer = if (recordingPaused) context.tr("RECORDING PAUSED") else if (listening) {
            if (recordingLocked) context.tr("RECORDING LOCKED · TAP STOP TO FINISH") else context.tr("SLIDE UP TO LOCK · RELEASE TO FINISH")
        } else status
        if(suggestionsVisible && footer.isNotEmpty() && resize?.held!=true) {
            val available=leftPanel-dp(if(splitEdge==0f && speechButton && listening) 104f else 0f)
            label(canvas,footer,available/2f,45*u,fitSize(footer,8f,available-dp(16f)),muted)
        }
        touches.values.firstOrNull { it.glide }?.let { touch ->
            strokes.color = accent; strokes.strokeWidth = dp(2f); strokes.alpha = 180
            val path = Path()
            touch.trace.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * width / 10f, p.y * width / 10f) else path.lineTo(p.x * width / 10f, p.y * width / 10f) }
            canvas.drawPath(path, strokes); strokes.alpha = 255; strokes.strokeWidth = dp(1.8f)
        }
        val recordingTouch = touches.values.firstOrNull { it.recording }
        if (recordingTouch != null && listening && !recordingLocked) {
            val x = recordingTouch.key.rect.centerX()
            val y = recordingTouch.key.rect.top - dp(40f)
            paint.color = keyColor; canvas.drawRoundRect(x - dp(16f), y - dp(23f), x + dp(16f), y + dp(23f), dp(16f), dp(16f), paint)
            drawLock(canvas, x, y - dp(8f), accent)
            label(canvas, "↑", x, y + dp(17f), 17f, ink)
        }
        val flick = touches.values.firstOrNull { it.flick != null }
        if (flick != null) {
            val x = flick.key.rect.centerX().coerceIn(dp(25f), width - dp(25f))
            val y = flick.key.rect.top - dp(30f)
            paint.color = keyColor; canvas.drawRoundRect(x - dp(22f), y - dp(22f), x + dp(22f), y + dp(22f), dp(10f), dp(10f), paint)
            label(canvas, flick.flick!!, x, y + dp(8f), 24f, if(contrast) Color.BLACK else ink,bold=contrast)
        }
        val held = touches.values.firstOrNull { it.held && it.choice >= 0 }
        if (held != null) {
            val options = options(held.key)
            val rect = popup(held, options.size)
            paint.color = palette.special; canvas.drawRoundRect(rect, dp(12f), dp(12f), paint)
            options.forEachIndexed { i, option ->
                val position=if(reverseOptions(held.key)) options.lastIndex-i else i
                val cx = rect.left + (position + 0.5f) * rect.width() / options.size
                val half = min(dp(18f), rect.width() / options.size / 2 - dp(2f))
                if (i == held.choice) { paint.color = accent; canvas.drawRoundRect(cx - half, rect.top + dp(4f), cx + half, rect.bottom - dp(4f), dp(8f), dp(8f), paint) }
                val text=KeyboardLayouts.display(option)
                label(canvas, text, cx, rect.centerY() + dp(7f), fitSize(text,20f,rect.width()/options.size-dp(6f)), KeyboardTheme.ink(if(i==held.choice) accent else palette.special))
            }
        }
        if(toolsOpen) {
            val first=toolBounds.first()
            paint.color=palette.special
            canvas.drawRoundRect(first.left,first.top,toolBounds.last().right,first.bottom,dp(12f),dp(12f),paint)
            tools.forEachIndexed { i,(icon,name) ->
                val bounds=toolBounds[i];val title=context.tr(name)
                ToolIcons.draw(canvas,icon,bounds.centerX(),bounds.top+dp(21f),dp(24f),ink)
                label(canvas,title,bounds.centerX(),bounds.top+dp(48f),fitSize(title,11f,bounds.width()-dp(8f)),ink)
            }
        }
    }
    private fun openTools(key:Key) {
        val inset=dp(5f)
        val cell=min(dp(68f),(width-2*inset)/tools.size)
        if(cell<=0f) return
        val left=key.rect.left.coerceIn(inset,max(inset,width-cell*tools.size-inset))
        val top=(key.rect.top-dp(66f)).coerceAtLeast(0f)
        toolBounds.forEachIndexed { i,rect -> rect.set(left+i*cell,top,left+(i+1)*cell,top+dp(60f)) }
        toolsOpen=true;invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
    private fun selectTool(index:Int) {
        cancelTouches();invalidate()
        listener.tool(tools[index].first)
    }
    private fun drawBackground(canvas:Canvas,u:Float) {
        if(splitEdge==0f) { canvas.drawColor(bg);return }
        val radius=if(suggestionsVisible) minOf(dp(34f),42*u,splitEdge/4) else 0f
        panelShape.rewind()
        panelCorners[2]=radius;panelCorners[3]=radius
        panelShape.addRoundRect(0f,0f,splitEdge,height.toFloat(),panelCorners,Path.Direction.CW)
        panelCorners[2]=0f;panelCorners[3]=0f
        panelCorners[0]=radius;panelCorners[1]=radius
        panelShape.addRoundRect(width-splitEdge,0f,width.toFloat(),height.toFloat(),panelCorners,Path.Direction.CW)
        panelCorners[0]=0f;panelCorners[1]=0f
        paint.color=bg;canvas.drawPath(panelShape,paint)
    }
    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) {
        paint.color = color; paint.alpha = 255; paint.textSize = dp(size); paint.textAlign = align
        val font=if(bold) boldFont else regularFont
        if(paint.typeface!==font) paint.typeface=font
        if(imageLabels) paint.setShadowLayer(dp(1.2f),0f,0f,if(KeyboardTheme.light(color)) Color.argb(235,0,0,0) else Color.argb(235,255,255,255))
        canvas.drawText(text, x, y, paint)
        paint.clearShadowLayer()
    }
    private fun drawMic(canvas: Canvas, x: Float, y: Float, color: Int) {
        val width = strokes.strokeWidth
        strokes.strokeWidth = dp(1.35f)
        strokes.color = color
        canvas.drawRoundRect(x - dp(3f), y - dp(8f), x + dp(3f), y + dp(2f), dp(3f), dp(3f), strokes)
        canvas.drawArc(x - dp(6f), y - dp(5f), x + dp(6f), y + dp(5f), 0f, 180f, false, strokes)
        canvas.drawLine(x, y + dp(5f), x, y + dp(9f), strokes)
        canvas.drawLine(x - dp(4f), y + dp(9f), x + dp(4f), y + dp(9f), strokes)
        strokes.strokeWidth = width
    }
    private fun drawIcon(canvas: Canvas, icon: android.graphics.drawable.Drawable, x: Float, y: Float, size: Float, color: Int) {
        val half = dp(size) / 2
        icon.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
        icon.setTint(color)
        icon.draw(canvas)
    }
    private fun drawLock(canvas: Canvas, x: Float, y: Float, color: Int) {
        strokes.color = color
        canvas.drawArc(x - dp(3f), y - dp(7f), x + dp(3f), y + dp(1f), 180f, 180f, false, strokes)
        canvas.drawRoundRect(x - dp(5f), y - dp(1f), x + dp(5f), y + dp(6f), dp(2f), dp(2f), strokes)
    }
    private fun uppercaseVariant(value: String) = if (value == "ß") "ẞ" else value.uppercase()
    private fun reverseOptions(key:Key)=key.rect.centerX()>width/2f+dp(1f) && !period(key)
    private fun options(key: Key): List<String> {
        key.custom?.let { return if((shift || caps) && layout.cased) it.secondary.map(::uppercaseVariant) else it.secondary }
        if(key.menu.isNotEmpty()) return key.menu
        val variants=if(symbols || numeric) emptyList() else variants(key.code)
        if((key.native || key.code.singleOrNull()?.isDigit()==true) && variants.isNotEmpty()) return variants.map { if((shift || caps) && layout.cased) uppercaseVariant(it) else it }
        if(!symbols && !numeric && key.code.length==1 && key.code[0].isDigit()) {
            nativeDigit(key.code[0])?.let { return listOf(key.alternate,it) }
        }
        return when {
            period(key) -> listOf("!", ",", "?").map { punctuation(it) }
            !numeric && key.code=="," -> CustomLayouts.emojis
            else -> listOf(key.alternate.ifEmpty { key.label })
        }
    }
    private fun popup(touch: Touch, count: Int): RectF {
        val width = min(count * dp(41f), this.width - dp(8f))
        val left = (touch.key.rect.centerX() - width / 2).coerceIn(dp(4f), max(dp(4f), this.width - width - dp(4f)))
        val above=touch.key.rect.top-dp(11f)
        val height=min(dp(51f),max(dp(32f),above))
        val top=(if(above>=dp(32f)) touch.key.rect.top-dp(7f)-height else touch.key.rect.bottom+dp(7f))
            .coerceIn(dp(4f),max(dp(4f),this.height-height-dp(4f)))
        return RectF(left,top,left+width,top+height)
    }
    private fun startRecording(): Boolean {
        if (voiceState.isNotEmpty() || !listener.voice(true)) return false
        voiceState = "Listening"
        return true
    }
    private fun stopRecording() {
        if (listening) { listener.voice(false); if (listening) voiceState = context.tr("Transcribing on device…") }
    }
    private fun cancelRecording() { listener.cancelVoice(); voiceState = "" }
    private fun hold(id: Int) {
        callbacks.remove(id)?.let { main.removeCallbacks(it) }
        val touch = touches[id] ?: return
        if (touch.held || touch.stopping) return
        touch.held = true
        if(touch.key.code!="enter") listener.feedback("hold")
        if(touch.key.custom?.secondary?.isNotEmpty()==true) { touch.choice=0;invalidate();return }
        if(touch.key.native && variants(touch.key.code).isNotEmpty()) { touch.choice=0;invalidate();return }
        when (touch.key.code) {
            "space" -> touch.cursor = true
            "enter" -> touch.recording = startRecording()
            "delete" -> { listener.delete(); repeatDelete(id) }
            "shift" -> { caps=true;shift=true }
            "symbols" -> openTools(touch.key)
            else -> touch.choice = if(period(touch.key)) 2 else 0
        }
        invalidate()
    }
    private fun repeatDelete(id: Int) {
        val task = Runnable { if (touches.containsKey(id)) { listener.delete(); repeatDelete(id) } }
        callbacks[id] = task; main.postDelayed(task, 45)
    }
    private fun moveTouch(id: Int, x: Float, y: Float, released:Boolean=false) {
        val touch = touches[id] ?: return
        if(touch.committed) return
        val dx = x - touch.startX; val dy = y - touch.startY
        if (touch.stopping) return
        if (touch.trace.isNotEmpty() && !touch.held) {
            val point = point(x, y)
            if (Glide.distance(touch.trace.last(), point) > 0.08f) touch.trace.add(point)
            val destination = hit(x, y)?.code
            val crossed = destination != null && destination.length == 1 && destination[0].isLetter() && destination != touch.key.code
            if (!touch.glide && crossed && Glide.distance(touch.trace.first(), point) > 0.8f && (abs(dx) > abs(dy) * 0.55f || Glide.distance(touch.trace.first(), point) > 1.2f)) {
                callbacks.remove(id)?.let { main.removeCallbacks(it) }; touch.glide = true; touch.flick = null
                listener.feedback("hold")
            }
            if (touch.glide) return
        }
        if (touch.key.code == "enter") {
            if (!touch.held && dy < -dp(48f) && abs(dx) < dp(48f)) hold(id)
            if (touch.recording && listening && !recordingLocked) {
                if (dy < -dp(48f)) { recordingLocked = true; listener.feedback("hold") }
                else if (dx < -dp(70f)) cancelRecording()
            }
            return
        }
        if (touch.key.code == "space" && !touch.held && abs(dx) > dp(12f)) {
            callbacks.remove(id)?.let { main.removeCallbacks(it) }
            touch.cursor = true; touch.held = true
        }
        if (touch.cursor) {
            val delta = ((x - touch.x) / dp(9f)).toInt()
            if (delta != 0) { listener.cursor(delta); touch.x += delta * dp(9f) }
        } else if (!touch.held && (period(touch.key) || touch.key.alternate.isNotBlank()) && (touch.key.custom==null || touch.key.custom.action.isEmpty() && touch.key.custom.secondary.isNotEmpty())) {
            val horizontal=abs(dx)>abs(dy)*1.35f && abs(dx)>=dp(14f)
            val value = when {
                horizontal && dx<0 && (!symbols && !numeric && layout.composer=="dead" || touch.key.custom!=null) && reverseOptions(touch.key) -> options(touch.key).last()
                horizontal && dx>0 && touch.key.custom?.secondary?.isNotEmpty()==true -> options(touch.key).last()
                horizontal && period(touch.key) -> punctuation(if(dx>0) "?" else "!")
                dy <= -dp(14f) && -dy > abs(dx)*1.35f -> if(period(touch.key)) punctuation(",") else touch.key.alternate
                else -> null
            }
            if (value != null && !released) {
                callbacks.remove(id)?.let { main.removeCallbacks(it) }
                if (touch.flick != value) listener.feedback("hold")
            }
            // A release can confirm a flick, but cannot start one by itself.
            touch.flick=value.takeIf { !released || it==touch.flick }
        }
        if (touch.held && touch.choice >= 0) {
            val options = options(touch.key)
            val rect = popup(touch, options.size)
            val initial = if(period(touch.key)) 2 else 0
            val delta=if(reverseOptions(touch.key)) -dx else dx
            val cell=rect.width()/options.size
            val available=if(reverseOptions(touch.key)) touch.startX-dp(1f) else width-dp(1f)-touch.startX
            // Keep the full menu reachable without moving a finger past the screen edge.
            val step=if(initial==0 && options.size>1) min(cell,available/options.lastIndex).coerceAtLeast(dp(1f)) else cell
            touch.choice = (initial + (delta / step).roundToInt()).coerceIn(0, options.lastIndex)
        } else if (!touch.held && touch.flick == null && touch.key.code != "space" && touch.trace.isEmpty() && (abs(dx) > dp(28f) || abs(dy) > dp(45f))) {
            callbacks.remove(id)?.let { main.removeCallbacks(it) }; touches.remove(id)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        selectPreview?.let { if(event.actionMasked==MotionEvent.ACTION_UP) it();return true }
        chooseKey?.let { choose ->
            if(event.actionMasked==MotionEvent.ACTION_UP) hit(event.x,event.y)?.let { choose(it.custom?.id ?: it.code) }
            return true
        }
        speechRects()
        if(speechPointer>=0) {
            val pointer=event.findPointerIndex(speechPointer)
            if(event.actionMasked==MotionEvent.ACTION_CANCEL || event.actionMasked==MotionEvent.ACTION_POINTER_DOWN) { speechPointer=-1;speechAction=-1;return true }
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex)==speechPointer) {
                val action=speechAction
                speechPointer=-1;speechAction=-1
                if(pointer>=0 && action>=0 && speechBounds[action].contains(event.getX(pointer),event.getY(pointer))) { speechAction(action);performClick() }
            } else if(event.actionMasked==MotionEvent.ACTION_MOVE && (pointer<0 || speechAction<0 || !speechBounds[speechAction].contains(event.getX(pointer),event.getY(pointer)))) {
                speechAction=-1
            }
            return true
        }
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            val selected=speechBounds.indexOfFirst { it.contains(event.x,event.y) }
            if(selected>=0) { speechPointer=event.getPointerId(0);speechAction=selected;activity();return true }
        }
        if(toolsOpen && event.actionMasked==MotionEvent.ACTION_DOWN) {
            val selected=toolBounds.indexOfFirst { it.contains(event.x,event.y) }
            toolsOpen=false;invalidate()
            if(selected>=0) { selectTool(selected);return true }
        }
        if(toolsOpen && event.actionMasked==MotionEvent.ACTION_UP) {
            val selected=toolBounds.indexOfFirst { it.contains(event.x,event.y) }
            if(selected>=0) { selectTool(selected);return true }
        }
        if (resize != null) return resize(event)
        if (suggestionsVisible && event.actionMasked == MotionEvent.ACTION_DOWN && event.y < dp(12f) && !inSplitGap(event.x) && voiceState.isEmpty() && touches.isEmpty()) {
            activity(); resize = Resize(event.getPointerId(0), event.rawY, prefs.height)
            main.postDelayed(resizeHold, 400)
            return true
        }
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val x = event.getX(index); val y = event.getY(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (suggestionsVisible && event.actionMasked == MotionEvent.ACTION_DOWN && y < 46 * unit()) {
                    if(moreCandidates && x>width-dp(32f)) listener.moreCandidates()
                    else if (voiceState.isNotEmpty() && x > width - dp(if(speechButton && listening) 158f else 70f)) cancelRecording()
                    else if (voiceState.isEmpty()) {
                        candidateRects()
                        (0 until shownSuggestions).firstOrNull { candidateBounds[it].contains(x,y) }?.let { listener.feedback();listener.candidate(suggestions[it]) }
                    }
                    return true
                }
                val key = hit(x, y) ?: return true
                // A second thumb can land and lift before the first one lifts.
                for((otherId,other) in touches) if(!other.committed && !other.held && !other.glide && other.flick==null &&
                    (other.key.code.length==1 || other.key.code=="space" || other.key.custom?.action=="")) {
                    callbacks.remove(otherId)?.let { main.removeCallbacks(it) }
                    other.committed=true;activate(other.key)
                }
                val touch = Touch(key, x, y, x, stopping = key.code == "enter" && voiceState.isNotEmpty(),
                    centers = if (flowEnabled) glideCenters ?: keys.filter { it.code.length == 1 && it.code[0].isLetter() }
                        .associate { it.code[0] to point(it.anchor.centerX(), it.anchor.centerY()) }.also { glideCenters=it } else emptyMap())
                if (flowEnabled && key.code.length == 1 && key.code[0].isLetter()) {
                    touch.trace.add(point(x, y))
                }
                touches[id] = touch
                activity()
                listener.feedback(key.code)
                if (key.code == "delete") listener.delete()
                val hasHold = if(key.custom?.action=="") key.custom.secondary.isNotEmpty() else key.code in listOf("space", "enter", "delete", "shift", "symbols") ||
                    period(key) || key.custom?.secondary?.isNotEmpty()==true || ((key.native || key.code.length==1) && key.alternate.isNotBlank())
                if (hasHold && !touch.stopping) {
                    val task = Runnable { hold(id) }; callbacks[id] = task
                    main.postDelayed(task, when (key.code) { "shift", "symbols" -> 250L; else -> 320L })
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) moveTouch(event.getPointerId(i), event.getX(i), event.getY(i))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                moveTouch(id, x, y, released=true)
                callbacks.remove(id)?.let { main.removeCallbacks(it) }
                val touch = touches.remove(id)
                if (touch != null) {
                    listener.feedback(touch.key.code, false)
                    when {
                        touch.committed -> Unit
                        touch.stopping -> stopRecording()
                        touch.recording -> if (!recordingLocked) stopRecording()
                        touch.cursor -> Unit
                        touch.glide -> { listener.glide(touch.trace.toList(), touch.centers, if (caps) 2 else if (shift) 1 else 0); if (!caps) shift = false }
                        touch.flick != null -> emit(if(touch.key.code.singleOrNull() in 'a'..'z') {
                            val accent=if(touch.key.custom!=null || layout.composer=="dead") touch.flick!! else variants(touch.key.code).firstOrNull() ?: touch.flick!!
                            if((shift || caps) && layout.cased) uppercaseVariant(accent) else accent
                        } else touch.flick!!)
                        touch.held -> if (touch.choice >= 0) emit(options(touch.key).getOrElse(touch.choice) { touch.key.alternate })
                        touch.key.code != "delete" -> activate(touch.key)
                    }
                }
                if (touches.isEmpty()) {
                    val changed=accentLanguage!=nextAccentLanguage;accentLanguage=nextAccentLanguage
                    if(keysDirty || changed) layoutKeys() else if(adaptiveDirty) adaptKeys()
                    activity()
                }
                invalidate(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!recordingLocked && touches.values.any { it.recording }) cancelRecording()
                cancelTouches()
            }
        }
        return true
    }
    private fun activate(key: Key) {
        if(key.custom?.action=="") { emit(customOutput(key.custom));return }
        when (key.code) {
            "shift" -> { when { caps -> { caps = false; shift = false }; shift -> caps = true; else -> shift = true }; layoutKeys() }
            "symbols","page" -> {
                if(key.code=="symbols") { symbols=!symbols;symbolPage=false;requestLayout() }
                else symbolPage=!symbolPage
                layoutKeys();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            "enter" -> if (listening) stopRecording() else if (voiceState.isEmpty()) listener.enter()
            "space" -> listener.text(" ")
            "delete" -> listener.delete()
            else -> emit(if(key.native) layout.output(key.code,shift || caps) else key.label)
        }
    }
    private fun hit(x: Float, y: Float): Key? {
        if(inSplitGap(x) || x<numberInset || x>width-numberInset) return null
        keys.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        if(numeric) return keys.firstOrNull { x>=it.rect.left-dp(2f) && x<it.rect.right+dp(2f) && y>=it.rect.top-dp(2f) && y<it.rect.bottom+dp(2f) }
        if (keys.isEmpty() || y < keys.first().rect.top - dp(2f) || y > keys.last().rect.bottom + dp(5f)) return null
        val side=if(splitEdge==0f) keys else keys.filter { (it.anchor.centerX()<width/2f)==(x<width/2f) }
        return side.minByOrNull {
            val dx = x - x.coerceIn(it.rect.left, it.rect.right)
            val dy = y - y.coerceIn(it.rect.top, it.rect.bottom)
            dx * dx + dy * dy
        }
    }
    private fun adaptiveTouch()=prefs.adaptive && adaptiveInput && !numeric && !symbols && !forceLatin &&
        previewLayout==null && previewTheme==null && previewDesign==null && layout.keyRows.isEmpty() && layout.composer in listOf("","dead")
    private fun emit(text: String) {
        if(symbols) listener.symbol(text) else listener.text(text)
        if(!caps && shift && !(layout.composer=="dead" && text in listOf("'","`","^","~","\""))) { shift=false;layoutKeys() }
    }
    private fun point(x: Float, y: Float) = Glide.Point(x * 10f / width, y * 10f / width)
    private fun resize(event: MotionEvent): Boolean {
        val drag = resize ?: return true
        val index = event.findPointerIndex(drag.id)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> { drag.cancelled = true; main.removeCallbacks(resizeHold); resizeHeight = null; requestLayout() }
            MotionEvent.ACTION_MOVE -> if (index >= 0 && !drag.cancelled) {
                val raw = event.rawY + event.getY(index) - event.y
                if (drag.held) {
                    val base=dp(baseHeight())
                    val value = (drag.initial + (drag.rawY - raw) * 100f / base).roundToInt().coerceIn(85, 120)
                    if (value != resizeHeight) { resizeHeight = value; requestLayout(); invalidate() }
                } else if (abs(raw - drag.rawY) > dp(12f)) { drag.cancelled = true; main.removeCallbacks(resizeHold) }
            }
            MotionEvent.ACTION_UP -> {
                if (drag.held && !drag.cancelled) resizeHeight?.let { prefs.height = it }
                endResize(); activity()
            }
            MotionEvent.ACTION_CANCEL -> { endResize(); activity() }
        }
        return true
    }
    private fun endResize() { main.removeCallbacks(resizeHold); resize = null; resizeHeight = null; requestLayout(); invalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow();layoutKeys();invalidate();activity() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) activity() else { main.removeCallbacks(idle); idleAnimation?.cancel() } }
    fun cancelTouches() {
        speechPointer=-1;speechAction=-1;toolsOpen=false;endResize()
        callbacks.values.forEach { main.removeCallbacks(it) };callbacks.clear();touches.clear()
        val changed=accentLanguage!=nextAccentLanguage;accentLanguage=nextAccentLanguage
        if(keysDirty || changed) layoutKeys() else if(adaptiveDirty) adaptKeys()
        invalidate()
    }
    override fun onDetachedFromWindow() { main.removeCallbacks(idle); idleAnimation?.cancel(); if (voiceState.isNotEmpty()) cancelRecording(); cancelTouches(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            speechRects()
            if (id == View.NO_ID) return AccessibilityNodeInfo.obtain(this@KeyboardView).apply {
                className = "android.inputmethodservice.KeyboardView"
                keys.indices.forEach { addChild(this@KeyboardView, it) }
                if (suggestionsVisible && voiceState.isEmpty() && resize?.held != true) repeat(shownSuggestions) { addChild(this@KeyboardView,1000+it) }
                if(toolsOpen) tools.indices.forEach { addChild(this@KeyboardView,2000+it) }
                if(moreCandidates && suggestionsVisible) addChild(this@KeyboardView,1004)
                speechBounds.indices.filter { !speechBounds[it].isEmpty }.forEach { addChild(this@KeyboardView,3000+it) }
            }
            if(id in 3000..3001 && !speechBounds[id-3000].isEmpty) return AccessibilityNodeInfo.obtain().apply {
                setSource(this@KeyboardView,id);setParent(this@KeyboardView);packageName=context.packageName
                className="android.widget.Button";contentDescription=context.tr(if(!listening) "Start recording" else if(id==3000) "Stop recording" else if(recordingPaused) "Resume recording" else "Pause recording")
                isEnabled=true;isClickable=true;isFocusable=true;isVisibleToUser=true
                val rect=Rect();speechBounds[id-3000].roundOut(rect);setBoundsInParent(rect)
                val position=IntArray(2);getLocationOnScreen(position);rect.offset(position[0],position[1]);setBoundsInScreen(rect)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            if(id-2000 in tools.indices && toolsOpen) return AccessibilityNodeInfo.obtain().apply {
                setSource(this@KeyboardView,id);setParent(this@KeyboardView);packageName=context.packageName
                className="android.widget.Button";contentDescription=context.tr(tools[id-2000].second)
                isEnabled=true;isClickable=true;isVisibleToUser=true
                val rect=Rect();toolBounds[id-2000].roundOut(rect);setBoundsInParent(rect)
                val position=IntArray(2);getLocationOnScreen(position);rect.offset(position[0],position[1]);setBoundsInScreen(rect)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            if(id==1004 && moreCandidates && suggestionsVisible) return AccessibilityNodeInfo.obtain().apply {
                setSource(this@KeyboardView,id);setParent(this@KeyboardView);packageName=context.packageName
                className="android.widget.Button";contentDescription=context.tr("More candidates");isEnabled=true;isClickable=true;isVisibleToUser=true
                val rect=Rect(width-dp(32f).toInt(),0,width,(46*unit()).toInt());setBoundsInParent(rect)
                val location=IntArray(2);getLocationOnScreen(location);rect.offset(location[0],location[1]);setBoundsInScreen(rect)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            if (id in 1000..1003) {
                if(!suggestionsVisible || id-1000>=shownSuggestions) return null
                val index = id - 1000
                val word = suggestions[index]
                candidateRects()
                return AccessibilityNodeInfo.obtain().apply {
                    setSource(this@KeyboardView, id); setParent(this@KeyboardView)
                    packageName = context.packageName; className = "android.widget.Button"; contentDescription = context.tr("Suggestion: %1\$s",word)
                    isEnabled = true; isClickable = true; isVisibleToUser = voiceState.isEmpty() && resize?.held != true
                    val rect = Rect(); candidateBounds[index].roundOut(rect); setBoundsInParent(rect)
                    val location = IntArray(2); getLocationOnScreen(location); rect.offset(location[0],location[1]); setBoundsInScreen(rect)
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                }
            }
            val key = keys.getOrNull(id)
            if (key == null) return null
            return AccessibilityNodeInfo.obtain().apply {
                setSource(this@KeyboardView, id); setParent(this@KeyboardView)
                packageName = context.packageName; className = "android.widget.Button"
                contentDescription = when (key.code) {
                    "shift" -> if (caps) context.tr("Caps lock on") else if (shift) context.tr("Shift on for next letter") else context.tr("Shift off")
                    "space" -> context.tr("Space, hold to move cursor")
                    "delete" -> context.tr("Backspace, hold to keep deleting")
                    "\u200c" -> context.tr("Half space")
                    "enter" -> if (listening) context.tr("Stop recording") else if (voiceState.isNotEmpty()) context.tr("Transcribing") else context.tr("%1\$s, hold to record, slide up to lock",key.label)
                    else -> secondaryLabel(key).let { if(it.isBlank()) key.label else context.tr("%1\$s, hold for %2\$s",key.label,it) }
                }
                isEnabled = true; isClickable = true; isFocusable = true; isVisibleToUser = true
                val r = Rect(); key.rect.roundOut(r); setBoundsInParent(r)
                val location = IntArray(2); getLocationOnScreen(location); r.offset(location[0], location[1]); setBoundsInScreen(r)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            }
        }
        override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
            if(action==AccessibilityNodeInfo.ACTION_CLICK && id in 3000..3001) { speechRects();return !speechBounds[id-3000].isEmpty && speechAction(id-3000) }
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                event.setSource(this@KeyboardView, id); parent?.requestSendAccessibilityEvent(this@KeyboardView, event); return true
            }
            if(action==AccessibilityNodeInfo.ACTION_CLICK && id==1004 && moreCandidates && suggestionsVisible) { listener.moreCandidates();return true }
            if (action == AccessibilityNodeInfo.ACTION_CLICK && id in 1000..1003) {
                if(!suggestionsVisible || voiceState.isNotEmpty() || resize?.held==true || id-1000>=shownSuggestions) return false
                activity();listener.candidate(suggestions[id-1000]);return true
            }
            if(action==AccessibilityNodeInfo.ACTION_CLICK && id-2000 in tools.indices && toolsOpen) {
                selectTool(id-2000);return true
            }
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                keys.getOrNull(id)?.let { key -> if(chooseKey!=null) chooseKey!!.invoke(key.custom?.id ?: key.code) else activate(key);activity();invalidate() }
                return true
            }
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                keys.getOrNull(id)?.let { if(it.custom?.secondary?.isNotEmpty()==true) emit(options(it).first()) else if(it.code=="symbols") openTools(it) else if(it.code=="shift") { caps=true;shift=true;layoutKeys();invalidate() } else if (it.code == "enter" && startRecording()) recordingLocked = true else if ((it.native || it.code.length==1) && it.alternate.isNotEmpty()) emit(options(it).first()) }
                return true
            }
            return false
        }
    }
}
