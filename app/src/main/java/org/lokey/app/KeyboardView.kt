package org.lokey.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    interface Listener {
        fun text(text: String)
        fun delete()
        fun enter()
        fun cursor(delta: Int)
        fun voice(start: Boolean): Boolean
        fun cancelVoice()
        fun glide(points: List<Glide.Point>, centers: Map<Char, Glide.Point>, caseMode: Int) { }
        fun nextKeyboard()
        fun candidate(word: String)
        fun feedback(key: String = "a", down: Boolean = true)
        fun tool(name:String) { }
        fun moreCandidates() { }
    }
    data class Key(val label: String, val alternate: String, val weight: Float = 1f, val code: String = label, val rect: RectF = RectF(), var row:Int=0,val native:Boolean=false,val custom:CustomLayouts.Key?=null)
    private data class Touch(val key: Key, val startX: Float, val startY: Float, var x: Float, var held: Boolean = false, var cursor: Boolean = false, var choice: Int = -1, var recording: Boolean = false, var stopping: Boolean = false, var flick: String? = null, var glide: Boolean = false, val trace: MutableList<Glide.Point> = ArrayList(), val centers: Map<Char, Glide.Point> = emptyMap())
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regularFont = Typeface.create("sans-serif", Typeface.NORMAL)
    private val boldFont = Typeface.create("sans-serif", Typeface.BOLD)
    private val strokes = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val shiftIcon = context.getDrawable(R.drawable.ic_shift)!!.mutate()
    private val capsIcon = context.getDrawable(R.drawable.ic_caps_lock)!!.mutate()
    private val deleteIcon = context.getDrawable(R.drawable.ic_backspace)!!.mutate()
    private val keys = ArrayList<Key>()
    private val touches = HashMap<Int, Touch>()
    private val callbacks = HashMap<Int, Runnable>()
    private var shift = false
    private var caps = false
    private var automaticCapital = false
    private var symbols = false
    private var toolsOpen=false
    private val toolBounds=Array(2) { RectF() }
    private var symbolPage = false
    private var numeric = false
    private var enterLabel = "↵"
    private var probabilities = FloatArray(26)
    private var suggestions = emptyList<String>()
    private var accentLanguage:String?=null
    private var nextAccentLanguage:String?=null
    var moreCandidates=false
    var forceLatin=false
        set(value) { if(field!=value) { field=value;cancelTouches();requestLayout();layoutKeys();invalidate() } }
    var previewLayout:KeyboardLayouts.Layout?=null
    private val layout get()=previewLayout ?: if(forceLatin) KeyboardLayouts.find("qwerty") else prefs.currentLayout
    private val flowEnabled get()=prefs.flow && layout.flow
    private fun punctuation(value:String)=if(numeric) value else layout.punctuation(value)
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
    private fun secondaryLabel(key:Key)=key.custom?.secondary?.joinToString(" ") ?: if(layout.composer=="dead" && !numeric) layout.variants(key.code).joinToString(" ").ifEmpty { key.alternate } else key.alternate
    private fun fitSize(text:String,size:Float,available:Float):Float {
        paint.textSize=dp(size);paint.typeface=regularFont
        return min(size,size*available.coerceAtLeast(1f)/paint.measureText(text).coerceAtLeast(1f))
    }
    private fun units()=293f+(if(numeric || symbols) 0 else if(layout.keyRows.isNotEmpty()) layout.keyRows.size-5 else layout.rows.size-3)*48
    private fun baseHeight()=(if(resources.configuration.screenWidthDp>resources.configuration.screenHeightDp) 260f else 325f)*units()/325f
    var suggestionsVisible=true
        set(value) {
            if(field==value) return
            field=value;cancelTouches();requestLayout();layoutKeys();invalidate()
        }
    private var neural = false
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
    var voiceState = ""
        set(value) {
            if (field != value) {
                if (value == "Listening") { levels.fill(0f); levelHead = 0; levelCount = 0; elapsedMillis = 0; lastBucket = -1 }
                if (value != "Listening") recordingLocked = false
                field = value; keepScreenOn = value == "Listening"; activity(); invalidate()
            }
        }
    private val levels = FloatArray(64)
    private var levelHead = 0
    private var levelCount = 0
    private var lastBucket = -1L
    private var elapsedMillis = 0L
    private val listening get() = voiceState == "Listening"
    fun audioLevel(level: Float, millis: Long) {
        if (!listening) return
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
    private val candidateBounds = Array(3) { RectF() }
    private var palette = KeyboardTheme.colors(prefs)
    private val bg get()=palette.background
    private val keyColor get()=palette.keys
    private val ink get()=palette.ink
    private val muted get()=palette.muted
    private val accent get()=palette.accent
    private var imageVersion=Long.MIN_VALUE
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

    fun editor(numeric: Boolean, action: String, capital: Boolean) {
        this.numeric = numeric; enterLabel = action; shift = capital; automaticCapital = capital; caps = false; symbols = false
        cancelTouches();accentLanguage=null;nextAccentLanguage=null;activity(); layoutKeys(); invalidate()
    }
    fun action(label:String) { if(enterLabel!=label) { enterLabel=label;layoutKeys();invalidate() } }
    fun prediction(words: List<String>, letters: FloatArray, neural: Boolean,language:String?=null) {
        if(language!=null) nextAccentLanguage=language
        if(touches.isEmpty()) accentLanguage=nextAccentLanguage
        val next=words.take(3)
        val changed=suggestions!=next
        suggestions = next; probabilities = letters; this.neural = neural
        if(changed) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        if (touches.isEmpty()) layoutKeys()
        invalidate()
    }
    fun capitalization(capital: Boolean) {
        // A repeated editor hint must not restore Shift after it was consumed or turned off.
        if (capital == automaticCapital) return
        automaticCapital = capital
        if (!caps && shift != capital) { shift = capital; layoutKeys(); invalidate() }
    }
    fun status(text: String) { status = context.tr(text); invalidate() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired=(dp(baseHeight()*(resizeHeight ?: prefs.height)/100f)*(if(suggestionsVisible) 1f else (units()-48)/units())).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutKeys() }
    private fun unit()=height/(units()-(if(suggestionsVisible) 0 else 48))
    private fun layoutKeys() {
        if (width == 0 || height == 0 || touches.isNotEmpty()) return
        keys.clear()
        val u = unit()
        val margin = dp(5f)
        val gap = dp(4f)
        val rowHeight = 48 * u
        var rowIndex=0
        fun row(items: List<Key>, y: Float, inset: Float = 0f, h: Float = rowHeight) {
            val available = width - 2 * margin - inset * 2
            val sum = items.sumOf { it.weight.toDouble() }.toFloat()
            val minimum=if(items.any { it.custom!=null }) min(dp(4f),(available-gap*(items.size-1))/items.size) else 0f
            val flexible=available-gap*(items.size-1)-minimum*items.size
            var x = margin + inset
            items.forEach { key ->
                val width = if(minimum>0f) minimum+flexible*key.weight/sum else (available + gap) * key.weight / sum - gap
                val top=y-(if(suggestionsVisible) 0f else 48*u)
                key.rect.set(x, top, x + width, top + h - 5 * u)
                key.row=rowIndex
                keys.add(key); x += width + gap
            }
            rowIndex++
        }
        fun letters(chars: String, alt: String = ""): List<Key> = chars.mapIndexed { i, c ->
            val p = if (prefs.adaptive && c in 'a'..'z') probabilities.getOrElse(c - 'a') { 0f } else 0f
            var secondary = variants(c.toString()).firstOrNull() ?: alt.getOrNull(i)?.toString().orEmpty().trim()
            if (shift && c in 'a'..'z') secondary = uppercaseVariant(secondary)
            Key(if (shift) c.uppercase() else c.toString(), secondary, 1f + min(0.32f, p * 0.9f), c.toString())
        }
        if (numeric) {
            row(letters("123", "!@#") + Key("⌫", "hold", code = "delete"), 48 * u, h = 60 * u)
            row(letters("456", "$%^") + Key("-", "+"), 108 * u, h = 60 * u)
            row(letters("789", "&*(") + Key(",", ";"), 168 * u, h = 60 * u)
            row(listOf(Key(".", ","), Key("0", ")"), Key("space", "", code = "space"), Key(enterLabel, context.tr("hold · voice"), code = "enter")), 228 * u, h = 58 * u)
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
            val p=if(prefs.adaptive && code.length==1 && code[0] in 'a'..'z') probabilities.getOrElse(code[0]-'a') { 0f } else 0f
            var secondary=if(layout.composer in listOf("cangjie","sucheng")) KeyboardLayouts.radical(code) else variants(code).firstOrNull().orEmpty()
            if(shift && layout.cased) secondary=uppercaseVariant(secondary)
            Key(layout.label(code,shift || caps),secondary,1f+min(.32f,p*.9f),code,native=true)
        }
        row(letters("1234567890", "!@#$%^&*()"), 48 * u)
        if (!symbols) {
            layout.rows.forEachIndexed { index,letters ->
                val inset=if(index==1 && letters.size<layout.rows.first().size) dp(15f) else 0f
                val keys=nativeLetters(letters)
                val values=if(index==layout.rows.lastIndex) listOf(Key(if(caps) "⇪" else "⇧",if(caps) "lock" else if(shift) "once" else "caps",1.35f,"shift"))+keys+Key("⌫","hold",1.35f,"delete") else keys
                row(values,(96+index*48)*u,inset)
            }
        } else {
            row(letters(if (symbolPage) "~`|•√π÷×¶∆" else "!@#$%^&*()", "          "), 96 * u)
            row(letters(if (symbolPage) "£€¥¢©®™✓§" else "-_=+[]{}\\", "         "), 144 * u, dp(15f))
            row(listOf(Key(if (symbolPage) "123" else "=/<", "more", 1.35f, "page")) + letters(if (symbolPage) "…°∞≠≤≥≈" else ";:'\"<>/", "       ") + Key("⌫", "hold", 1.35f, "delete"), 192 * u)
        }
        val bottom=mutableListOf(Key(if (symbols) "ABC" else "?123", "tools", 1.5f, "symbols"), Key(punctuation(","), "😊", 0.9f, ","),
            Key("space", context.tr("Hold for cursor"), (if(layout.id=="persian") 4f else 4.9f)-idleAmount*.3f,"space"),
            Key(punctuation("."),listOf("!",",","?").joinToString(" ") { punctuation(it) },0.9f,"."), Key(enterLabel, context.tr("hold · voice"), 1.5f + idleAmount * 0.3f, "enter"))
        if(layout.id=="persian") bottom.add(3,Key("½","",.9f,"\u200c",native=true))
        row(bottom,(units()-53)*u,h=53*u)
    }
    override fun onDraw(canvas: Canvas) {
        val saved=previewDesign
        val theme=saved?.settings?.getString("keyboard_theme") ?: previewTheme ?: prefs.keyboardTheme
        palette=if(saved!=null) KeyboardTheme.colors(saved.settings) else KeyboardTheme.colors(prefs,theme)
        val builtin=theme=="wave" || theme=="image" && (saved?.settings?.optString("theme_picture") ?: prefs.themePicture)=="wave" && (previewTheme==null || saved!=null)
        val picture=theme=="image" || theme=="wave"
        val requested=if(builtin) -1L else prefs.themeImageVersion
        if(picture && imageVersion!=requested) {
            val version=requested;imageVersion=version
            val file=when {
                saved?.image?.isNotEmpty()==true -> saved.imageFile(prefs)
                saved==null && previewTheme=="image" && KeyboardTheme.uploadFile(context).exists() -> KeyboardTheme.uploadFile(context)
                else -> KeyboardTheme.imageFile(context)
            }
            KeyboardTheme.image(context.applicationContext,version,builtin,file) { bitmap -> main.post {
                if(imageVersion==version) { keycapBitmap=bitmap;keycapShader=bitmap?.let { BitmapShader(it,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP) };invalidate() }
            } }
        }
        val bitmap=keycapBitmap.takeIf { picture }
        if(bitmap!=null) {
            val top=(if(suggestionsVisible) 48 else 0)*unit();val area=(units()-48)*unit();val scale=max(width.toFloat()/bitmap.width,area/bitmap.height)
            imageMatrix.setScale(scale,scale);imageMatrix.postTranslate((width-bitmap.width*scale)/2,top+(area-bitmap.height*scale)/2)
            keycapShader?.setLocalMatrix(imageMatrix);imageMatrix.invert(imageInverse)
        }
        canvas.drawColor(bg)
        val u = unit()
        if(suggestionsVisible) {
            if (resize?.held == true) {
                label(canvas, context.tr("Drag to resize · %1\$s%%",resizeHeight ?: prefs.height), width / 2f, 29 * u, 14f, ink)
            } else if (voiceState.isNotEmpty()) {
                if (listening) {
                    paint.color = accent; canvas.drawCircle(dp(10f), 24 * u, dp(2.5f), paint)
                    val seconds = elapsedMillis / 1000
                    label(canvas, "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", dp(20f), 29 * u, 12f, ink, Paint.Align.LEFT)
                    val left = dp(65f); val right = width - dp(if (recordingLocked) 93f else 72f)
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
                    if (recordingLocked) drawLock(canvas, width - dp(80f), 24 * u, accent)
                } else label(canvas, context.tr(voiceState), (width - dp(60f)) / 2, 29 * u, 12f, ink)
                val cancel=context.tr("Cancel")
                label(canvas, cancel, width - dp(33f), 29 * u, fitSize(cancel,12f,dp(60f)), muted)
            } else {
                val cell = (width - dp(if(moreCandidates) 56f else 24f)) / 3
                suggestions.forEachIndexed { index, word ->
                    val rect = candidateBounds[index]
                    rect.set(dp(12f) + index * cell, 0f, dp(12f) + (index + 1) * cell, 46 * u)
                    label(canvas, word, rect.centerX(), 29 * u, 16f, if (index == 0) ink else muted, bold = index == 0)
                    if (index < 2) { paint.color = keyColor; canvas.drawRect(rect.right, 15 * u, rect.right + dp(0.5f), 34 * u, paint) }
                }
            }
            if(moreCandidates) label(canvas,"›",width-dp(16f),30*u,24f,accent)
            paint.color = if (resize?.held == true) accent else muted
            paint.alpha = if (resize?.held == true) 255 else 100
            canvas.drawRoundRect(width / 2f - dp(15f), dp(3f), width / 2f + dp(15f), dp(4.2f), dp(1f), dp(1f), paint)
            paint.alpha = 255
        }
        for (key in keys) {
            val pressed = touches.values.any { it.key === key }
            val special = key.code == "shift" || key.code == "delete" || key.code == "symbols" || key.code == "page"
            val colorCode=key.custom?.id ?: key.code
            val custom=if(theme=="custom" || theme=="image") if(saved==null) prefs.keyColor(colorCode) else saved.settings.opt("key_color.$colorCode") as? Int else null
            val rowColor=if(theme=="custom" || theme=="image") if(saved==null) prefs.rowColor(key.row) else saved.settings.opt("row_color.${key.row}") as? Int else null
            val active=key.code=="enter" || key.code=="shift" && shift
            val fill=when { active -> custom ?: accent; custom!=null -> custom; rowColor!=null -> rowColor; key.code=="space" -> palette.space; special -> palette.special; else -> keyColor }
            paint.color=if(pressed) KeyboardTheme.blend(fill,KeyboardTheme.ink(fill),.17f) else fill
            var textColor=KeyboardTheme.ink(fill)
            if(bitmap!=null && !active && custom==null && rowColor==null) {
                canvas.drawRoundRect(key.rect,dp(8f),dp(8f),paint)
                paint.shader=keycapShader
                imagePoint[0]=key.rect.centerX();imagePoint[1]=key.rect.centerY();imageInverse.mapPoints(imagePoint)
                val pixel=bitmap.getPixel(imagePoint[0].toInt().coerceIn(0,bitmap.width-1),imagePoint[1].toInt().coerceIn(0,bitmap.height-1))
                textColor=KeyboardTheme.ink(KeyboardTheme.blend(fill,pixel,Color.alpha(pixel)/255f))
            }
            canvas.drawRoundRect(key.rect, dp(8f), dp(8f), paint);paint.shader=null
            if(bitmap!=null && pressed) { paint.color=0x33777777;canvas.drawRoundRect(key.rect,dp(8f),dp(8f),paint) }
            paint.color = palette.shadow
            canvas.drawRoundRect(key.rect.left, key.rect.bottom - dp(2f), key.rect.right, key.rect.bottom + dp(1f), dp(8f), dp(8f), paint)
            val color = textColor
            imageLabels=bitmap!=null && !active && custom==null && rowColor==null
            if (key.code == "space") {
                val cursor=touches.values.any { it.cursor }
                label(canvas, if(cursor) context.tr("‹  Move cursor  ›") else context.tr("Hold for cursor"), key.rect.centerX(), if(cursor) key.rect.centerY()+dp(4f) else key.rect.bottom-dp(5f), if(cursor) 11f else 9f, if(bitmap!=null) color else KeyboardTheme.blend(color,fill,.3f))
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
                val baseSize=if(key.label.length>2) 13f else 22f
                val size=fitSize(key.label,baseSize,key.rect.width()-dp(8f))
                label(canvas,key.label,key.rect.centerX(),key.rect.centerY()+dp(if(key.label.length>2) 5f else 8f),size,color)
                val hint=if(key.code in listOf("shift","delete","symbols","page","enter")) context.tr(secondaryLabel(key)) else secondaryLabel(key)
                if (hint.isNotEmpty()) label(canvas, hint, key.rect.right - dp(5f), key.rect.top + dp(11f), fitSize(hint,if(hint.length>2) 7f else 9f,key.rect.width()-dp(10f)), if(bitmap!=null) color else KeyboardTheme.blend(color,fill,.35f), Paint.Align.RIGHT)
            }
        }
        imageLabels=false
        val footer = if (listening) {
            if (recordingLocked) context.tr("RECORDING LOCKED · TAP STOP TO FINISH") else context.tr("SLIDE UP TO LOCK · RELEASE TO FINISH")
        } else status
        if(suggestionsVisible && footer.isNotEmpty() && resize?.held!=true)
            label(canvas,footer,width/2f,45*u,fitSize(footer,8f,width-dp(16f)),muted)
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
            label(canvas, flick.flick!!, x, y + dp(8f), 24f, ink)
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
            val key=keys.firstOrNull { it.code=="symbols" }
            if(key!=null) {
                val left=dp(5f);val top=key.rect.top-dp(66f)
                val rect=RectF(left,top,left+dp(136f),top+dp(60f))
                paint.color=palette.special;canvas.drawRoundRect(rect,dp(12f),dp(12f),paint)
                listOf("clipboard" to context.tr("Clipboard"),"translate" to context.tr("Translate")).forEachIndexed { i,(icon,title) ->
                    val bounds=toolBounds[i];bounds.set(left+i*dp(68f),top,left+(i+1)*dp(68f),rect.bottom)
                    ToolIcons.draw(canvas,icon,bounds.centerX(),top+dp(21f),dp(24f),ink)
                    label(canvas,title,bounds.centerX(),top+dp(48f),fitSize(title,11f,bounds.width()-dp(8f)),ink)
                }
            }
        }
    }
    private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false) {
        paint.color = color; paint.alpha = 255; paint.textSize = dp(size); paint.textAlign = align
        paint.typeface = if (bold) boldFont else regularFont
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
    private fun reverseOptions(key:Key)=if(key.custom!=null) key.rect.centerX()>width/2 else layout.composer=="dead" && !numeric && key.code in listOf("0","p")
    private fun options(key: Key): List<String> {
        key.custom?.let { return if((shift || caps) && layout.cased) it.secondary.map(::uppercaseVariant) else it.secondary }
        val variants=variants(key.code)
        if((key.native || key.code.singleOrNull()?.isDigit()==true) && variants.isNotEmpty()) return variants.map { if((shift || caps) && layout.cased) uppercaseVariant(it) else it }
        if(key.code.length==1 && key.code[0].isDigit()) {
            val base=when(layout.id) { "arabic" -> 0x660;"persian" -> 0x6f0;"hindi","bolnagri","hindi_phonetic" -> 0x966;"thai" -> 0xe50;else -> 0 }
            if(base!=0) return listOf(key.alternate,(base+key.code[0].digitToInt()).toChar().toString())
        }
        return when (key.code) {
            "." -> listOf("!", ",", "?").map { punctuation(it) }
            "," -> CustomLayouts.emojis
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
        listener.feedback("hold")
        if(touch.key.custom?.secondary?.isNotEmpty()==true) { touch.choice=0;invalidate();return }
        if(touch.key.native && variants(touch.key.code).isNotEmpty()) { touch.choice=0;invalidate();return }
        when (touch.key.code) {
            "space" -> touch.cursor = true
            "enter" -> touch.recording = startRecording()
            "delete" -> { listener.delete(); repeatDelete(id) }
            "shift" -> { caps=true;shift=true }
            "symbols" -> { toolsOpen=true;sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
            else -> touch.choice = if (touch.key.code == ".") 2 else 0
        }
        invalidate()
    }
    private fun repeatDelete(id: Int) {
        val task = Runnable { if (touches.containsKey(id)) { listener.delete(); repeatDelete(id) } }
        callbacks[id] = task; main.postDelayed(task, 45)
    }
    private fun moveTouch(id: Int, x: Float, y: Float) {
        val touch = touches[id] ?: return
        val dx = x - touch.startX; val dy = y - touch.startY
        if (touch.stopping) return
        if (touch.trace.isNotEmpty() && !touch.held) {
            val point = point(x, y)
            if (Glide.distance(touch.trace.last(), point) > 0.08f) touch.trace.add(point)
            val destination = hit(x, y)?.code
            val crossed = destination != null && destination.length == 1 && destination[0] in 'a'..'z' && destination != touch.key.code
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
        } else if (!touch.held && (touch.key.code == "." || touch.key.alternate.isNotBlank()) && (touch.key.custom==null || touch.key.custom.action.isEmpty() && touch.key.custom.secondary.isNotEmpty())) {
            val value = when {
                reverseOptions(touch.key) && dx <= -dp(10f) && abs(dx)>abs(dy) -> options(touch.key).last()
                touch.key.custom?.secondary?.isNotEmpty()==true && dx >= dp(10f) && abs(dx)>abs(dy) -> options(touch.key).last()
                touch.key.custom==null && !touch.key.native && touch.key.code == "." && abs(dx) >= dp(10f) && abs(dx) > abs(dy) -> punctuation(if(dx>0) "?" else "!")
                dy <= -dp(10f) && abs(dy) > abs(dx) -> if (touch.key.custom==null && !touch.key.native && touch.key.code == ".") punctuation(",") else touch.key.alternate
                else -> null
            }
            if (value != null) {
                callbacks.remove(id)?.let { main.removeCallbacks(it) }
                if (touch.flick != value) listener.feedback("hold")
                touch.flick = value
            }
        }
        if (touch.held && touch.choice >= 0) {
            val options = options(touch.key)
            val rect = popup(touch, options.size)
            val initial = if (touch.key.custom==null && !touch.key.native && touch.key.code == ".") 2 else 0
            val delta=if(reverseOptions(touch.key)) -dx else dx
            val cell=rect.width()/options.size
            val available=if(reverseOptions(touch.key)) touch.startX-dp(1f) else width-dp(1f)-touch.startX
            val step=if((layout.composer=="dead" || touch.key.custom!=null) && initial==0 && options.size>1) min(cell,available/options.lastIndex).coerceAtLeast(dp(1f)) else cell
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
        if(toolsOpen && event.actionMasked==MotionEvent.ACTION_DOWN) {
            val selected=toolBounds.indexOfFirst { it.contains(event.x,event.y) }
            toolsOpen=false;invalidate()
            if(selected>=0) { cancelTouches();listener.tool(when(selected) { 0 -> "clipboard";else -> "translate" });return true }
        }
        if(toolsOpen && event.actionMasked==MotionEvent.ACTION_UP) {
            val selected=toolBounds.indexOfFirst { it.contains(event.x,event.y) }
            if(selected>=0) { toolsOpen=false;cancelTouches();listener.tool(when(selected) { 0 -> "clipboard";else -> "translate" });return true }
        }
        if (resize != null) return resize(event)
        if (suggestionsVisible && event.actionMasked == MotionEvent.ACTION_DOWN && event.y < dp(12f) && voiceState.isEmpty() && touches.isEmpty()) {
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
                    else if (voiceState.isNotEmpty() && x > width - dp(70f)) cancelRecording()
                    else if (voiceState.isEmpty()) suggestions.indices.firstOrNull { candidateBounds[it].contains(x, y) }?.let { listener.feedback(); listener.candidate(suggestions[it]) }
                    return true
                }
                val key = hit(x, y) ?: return true
                val touch = Touch(key, x, y, x, stopping = key.code == "enter" && voiceState.isNotEmpty(),
                    centers = if (flowEnabled) keys.filter { it.code.length == 1 && it.code[0] in 'a'..'z' }.associate { it.code[0] to point(it.rect.centerX(), it.rect.centerY()) } else emptyMap())
                if (flowEnabled && key.code.length == 1 && key.code[0] in 'a'..'z') {
                    touch.trace.add(point(x, y))
                }
                touches[id] = touch
                activity()
                listener.feedback(key.code)
                if (key.code == "delete") listener.delete()
                val hasHold = if(key.custom?.action=="") key.custom.secondary.isNotEmpty() else key.code in listOf("space", "enter", "delete", "shift", "symbols", ".", ",") ||
                    key.custom?.secondary?.isNotEmpty()==true || (key.code.length == 1 && key.alternate.isNotBlank())
                if (hasHold && !touch.stopping) {
                    val task = Runnable { hold(id) }; callbacks[id] = task
                    main.postDelayed(task, when (key.code) { "enter", "delete", "space" -> 320L; else -> 250L })
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) moveTouch(event.getPointerId(i), event.getX(i), event.getY(i))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                moveTouch(id, x, y)
                callbacks.remove(id)?.let { main.removeCallbacks(it) }
                val touch = touches.remove(id)
                if (touch != null) {
                    listener.feedback(touch.key.code, false)
                    when {
                        touch.stopping -> stopRecording()
                        touch.recording -> if (!recordingLocked) stopRecording()
                        touch.cursor -> Unit
                        touch.glide -> { listener.glide(touch.trace.toList(), touch.centers, if (caps) 2 else if (shift) 1 else 0); if (!caps) shift = false }
                        touch.flick != null -> emit(if(touch.key.code.singleOrNull() in 'a'..'z') {
                            val accent=if(touch.key.custom!=null || reverseOptions(touch.key)) touch.flick!! else variants(touch.key.code).firstOrNull() ?: touch.flick!!
                            if((shift || caps) && layout.cased) uppercaseVariant(accent) else accent
                        } else touch.flick!!)
                        touch.held -> if (touch.choice >= 0) emit(options(touch.key).getOrElse(touch.choice) { touch.key.alternate })
                        touch.key.code != "delete" -> activate(touch.key)
                    }
                }
                if (touches.isEmpty()) { accentLanguage=nextAccentLanguage;layoutKeys(); activity() }
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
            "symbols" -> { symbols = !symbols;requestLayout();layoutKeys() }
            "page" -> { symbolPage = !symbolPage; layoutKeys() }
            "enter" -> if (listening) stopRecording() else if (voiceState.isEmpty()) listener.enter()
            "space" -> listener.text(" ")
            "delete" -> listener.delete()
            else -> emit(if(key.native) layout.output(key.code,shift || caps) else punctuation(key.label))
        }
    }
    private fun hit(x: Float, y: Float): Key? {
        keys.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        if (keys.isEmpty() || y < keys.first().rect.top - dp(2f) || y > keys.last().rect.bottom + dp(5f)) return null
        return keys.minByOrNull {
            val dx = x - x.coerceIn(it.rect.left, it.rect.right)
            val dy = y - y.coerceIn(it.rect.top, it.rect.bottom)
            dx * dx + dy * dy
        }
    }
    private fun emit(text: String) { listener.text(text); if (!caps && shift && !(layout.composer=="dead" && text in listOf("'","`","^","~","\""))) { shift = false; layoutKeys() } }
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
    fun cancelTouches() { toolsOpen=false;endResize(); callbacks.values.forEach { main.removeCallbacks(it) }; callbacks.clear(); touches.clear();accentLanguage=nextAccentLanguage;invalidate() }
    override fun onDetachedFromWindow() { main.removeCallbacks(idle); idleAnimation?.cancel(); if (voiceState.isNotEmpty()) cancelRecording(); cancelTouches(); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            if (id == View.NO_ID) return AccessibilityNodeInfo.obtain(this@KeyboardView).apply {
                className = "android.inputmethodservice.KeyboardView"
                keys.indices.forEach { addChild(this@KeyboardView, it) }
                if (suggestionsVisible && voiceState.isEmpty() && resize?.held != true) suggestions.indices.forEach { addChild(this@KeyboardView, 1000 + it) }
                if(toolsOpen) repeat(2) { addChild(this@KeyboardView,2000+it) }
                if(moreCandidates && suggestionsVisible) addChild(this@KeyboardView,1003)
            }
            if(id in 2000..2001 && toolsOpen) return AccessibilityNodeInfo.obtain().apply {
                setSource(this@KeyboardView,id);setParent(this@KeyboardView);packageName=context.packageName
                className="android.widget.Button";contentDescription=when(id) { 2000 -> context.tr("Clipboard");else -> context.tr("Translate") }
                isEnabled=true;isClickable=true;isVisibleToUser=true
                val rect=Rect();toolBounds[id-2000].roundOut(rect);setBoundsInParent(rect)
                val position=IntArray(2);getLocationOnScreen(position);rect.offset(position[0],position[1]);setBoundsInScreen(rect)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            if(id==1003 && moreCandidates && suggestionsVisible) return AccessibilityNodeInfo.obtain().apply {
                setSource(this@KeyboardView,id);setParent(this@KeyboardView);packageName=context.packageName
                className="android.widget.Button";contentDescription=context.tr("More candidates");isEnabled=true;isClickable=true;isVisibleToUser=true
                val rect=Rect(width-dp(32f).toInt(),0,width,(46*unit()).toInt());setBoundsInParent(rect)
                val location=IntArray(2);getLocationOnScreen(location);rect.offset(location[0],location[1]);setBoundsInScreen(rect)
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
            if (id in 1000..1002) {
                if(!suggestionsVisible) return null
                val index = id - 1000
                val word = suggestions.getOrNull(index) ?: return null
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
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                event.setSource(this@KeyboardView, id); parent?.requestSendAccessibilityEvent(this@KeyboardView, event); return true
            }
            if(action==AccessibilityNodeInfo.ACTION_CLICK && id==1003 && moreCandidates) { listener.moreCandidates();return true }
            if (action == AccessibilityNodeInfo.ACTION_CLICK && id in 1000..1002) {
                if(!suggestionsVisible) return false
                val word = suggestions.getOrNull(id - 1000) ?: return false
                activity(); listener.candidate(word); return true
            }
            if(action==AccessibilityNodeInfo.ACTION_CLICK && id in 2000..2001 && toolsOpen) {
                toolsOpen=false;invalidate();listener.tool(when(id) { 2000 -> "clipboard";else -> "translate" });return true
            }
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                keys.getOrNull(id)?.let { key -> if(chooseKey!=null) chooseKey!!.invoke(key.custom?.id ?: key.code) else activate(key);activity();invalidate() }
                return true
            }
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                keys.getOrNull(id)?.let { if(it.custom?.secondary?.isNotEmpty()==true) emit(options(it).first()) else if(it.code=="symbols") { toolsOpen=true;invalidate();sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) } else if(it.code=="shift") { caps=true;shift=true;layoutKeys();invalidate() } else if (it.code == "enter" && startRecording()) recordingLocked = true else if (it.code.length == 1 && it.alternate.isNotEmpty()) emit(options(it).first()) }
                return true
            }
            return false
        }
    }
}
