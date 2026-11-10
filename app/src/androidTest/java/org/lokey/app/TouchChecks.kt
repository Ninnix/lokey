package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TouchChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val report=JSONObject()
        val text=InputType.TYPE_CLASS_TEXT
        check(AdaptiveKeys.allowed(text,0))
        check(!AdaptiveKeys.allowed(text or InputType.TYPE_TEXT_VARIATION_PASSWORD,0))
        check(!AdaptiveKeys.allowed(text,EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        check(!AdaptiveKeys.allowed(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,0))
        check(!AdaptiveKeys.allowed(InputType.TYPE_CLASS_NUMBER,0))
        val prior=FloatArray(26).apply { this['w'-'a']=1f }
        val logits=AdaptiveKeys.probabilities("a",listOf("ab" to kotlin.math.ln(.75),"ac" to kotlin.math.ln(.25),"ab" to kotlin.math.ln(.75)))
        check(abs(logits[1]-.75f)<.0001 && abs(logits[2]-.25f)<.0001)
        check(AdaptiveKeys.probabilities("zzz",listOf("ab" to -1.0)).all { it==0f })
        check(abs(AdaptiveKeys.boundary("q","w",prior,100f))<=12)
        check(AdaptiveKeys.boundary("q","w",FloatArray(26) { 1f/26 },100f)==0f)
        check(AdaptiveKeys.boundary("α","β",prior,100f)==0f)
        report.put("bounded_probabilities_and_private_fields",true)
        val sandbox=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("visible-keys-check-$name",mode)
        }
        val store=sandbox.getSharedPreferences("lokey",0);store.edit().clear().commit()
        val prefs=Prefs(sandbox);prefs.layout="qwerty";prefs.flow=false;prefs.adaptive=true
        val typed=StringBuilder();lateinit var view:KeyboardView
        val field=KeyboardView::class.java.getDeclaredField("keys").apply { isAccessible=true }
        fun keys():List<KeyboardView.Key> {
            @Suppress("UNCHECKED_CAST") return (field.get(view) as List<KeyboardView.Key>).toList()
        }
        fun measure(width:Int=1080) {
            view.editor(false,"Done",false)
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            view.layout(0,0,width,view.measuredHeight)
        }
        fun event(action:Int,x:Float,y:Float) {
            val now=SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,action,x,y,0)
            view.onTouchEvent(e);e.recycle()
        }
        fun capture(name:String) {
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File(context.filesDir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        try { test.runOnMainSync {
            view=KeyboardView(sandbox,prefs,object:KeyboardView.Listener {
                override fun text(text:String) { typed.append(text) };override fun delete() {};override fun enter() {};override fun cursor(delta:Int) {}
                override fun voice(start:Boolean)=false;override fun cancelVoice() {};override fun nextKeyboard() {}
                override fun candidate(word:String) {};override fun feedback(key:String,down:Boolean) {}
            })
            measure();val original=keys().map { it.code to RectF(it.anchor) };capture("adaptive-base.png")
            view.prediction(listOf("word"),prior,true,"en")
            val w=keys().first { it.code=="w" }
            check(w.rect.width()>w.anchor.width()) { "Likely key did not visibly grow" }
            check(original==keys().map { it.code to RectF(it.anchor) }) { "Letter positions moved" }
            capture("adaptive-expanded.png")
            val q=keys().first { it.code=="q" };val x=q.anchor.centerX();val y=q.anchor.centerY()
            val held=keys().map { RectF(it.rect) }
            event(MotionEvent.ACTION_DOWN,x,y)
            view.prediction(emptyList(),FloatArray(26).apply { this[4]=1f },true,"en")
            check(held==keys().map { RectF(it.rect) });event(MotionEvent.ACTION_UP,x,y)
            check(typed.toString()=="q")
            report.put("visible_growth_fixed_letters_and_frozen_touch",true)
            val matrix=JSONArray()
            for(layout in KeyboardLayouts.all) for(width in listOf(1080,1920)) {
                prefs.layout=layout.id;measure(width)
                val anchors=keys().map { it.code to RectF(it.anchor) }
                view.prediction(emptyList(),prior,true,"en")
                check(anchors==keys().map { it.code to RectF(it.anchor) })
                for(k in keys()) check(k.rect.contains(k.anchor.centerX(),k.anchor.centerY())) { "Original center lost: ${layout.id}/${k.code}" }
                for(row in keys().groupBy { it.row }.values) for((a,b) in row.zipWithNext()) {
                    check(a.rect.right<=b.rect.left) { "Overlapping keys: ${layout.id}" }
                    val pitch=b.anchor.centerX()-a.anchor.centerX()
                    check(abs(a.rect.right-a.anchor.right)<=pitch*.121f)
                }
                matrix.put(JSONObject().put("layout",layout.id).put("width",width).put("centers",keys().size))
            }
            report.put("layout_matrix",matrix)
            report.put("simulation",simulate(context,prefs,view,::measure,::keys))
            check(store.all.keys.none { it.startsWith("touch_") || it.contains("learning") || it.contains("history") })
            report.put("no_touch_learning_or_storage",true)
            report.put("boundary_budget",.12)
            view.cancelTouches()
        }
            lateinit var engine:Prediction
            test.runOnMainSync { engine=Prediction(sandbox,prefs) }
            try {
                val live=JSONArray();val updates=JSONArray()
                for(model in Models.text) for(before in listOf("Please send me the docum","See you tomor","Good mor")) {
                    prefs.model=model.id
                    val done=CountDownLatch(1);var answer:Prediction.Result?=null;val start=SystemClock.elapsedRealtimeNanos()
                    test.runOnMainSync { engine.clear();engine.request(before) { if(it.neural) { answer=it;done.countDown() } } }
                    check(done.await(30,TimeUnit.SECONDS)) { "No model probabilities: ${model.id}/$before" }
                    val result=answer!!;check(result.letters.all { it.isFinite() && it>=0f } && result.letters.sum()>.99f)
                    live.put(JSONObject().put("model",model.id).put("before",before).put("words",JSONArray(result.words)).put("letters",JSONArray(result.letters.toList()))
                        .put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6).put("backend",Native.textBackend()))
                    test.runOnMainSync {
                        prefs.layout="qwerty";prefs.adaptive=true;measure();view.prediction(result.words,result.letters,true,result.language)
                        if(before.endsWith("docum")) capture("adaptive-live-${model.id}.png")
                        if(before=="Good mor") return@runOnMainSync
                        val prefix=Languages.prefix(before)
                        val next=before+result.words.first { it.startsWith(prefix,true) && it.length>prefix.length }[prefix.length]
                        repeat(16) {
                            var cached:Prediction.Result?=null;val began=SystemClock.elapsedRealtimeNanos()
                            engine.request(next) { value -> if(value.neural) cached=value }
                            val ready=SystemClock.elapsedRealtimeNanos();check(cached!=null) { "Next key waited for inference: $next" }
                            val value=cached!!;check(value.letters.sum()>.99f)
                            view.prediction(value.words,value.letters,true,value.language)
                            updates.put(JSONObject().put("model",model.id).put("before",next).put("probability_ms",(ready-began)/1e6)
                                .put("geometry_ready_ms",(SystemClock.elapsedRealtimeNanos()-began)/1e6))
                        }
                    }
                }
                report.put("live_model_probabilities",live)
                report.put("immediate_prefix_geometry",updates)
            } finally { test.runOnMainSync { engine.close() } }
        } finally { store.edit().clear().commit() }
        return report
    }
    private fun simulate(context:Context,prefs:Prefs,view:KeyboardView,measure:(Int)->Unit,keys:()->List<KeyboardView.Key>):JSONArray {
        val file=File(context.filesDir,"adaptive-cases.json");val rows=JSONArray()
        if(!file.exists()) return rows
        val cases=JSONArray(file.readText());val density=context.resources.displayMetrics.density
        fun hit(rects:List<Pair<String,RectF>>,x:Float,y:Float):String? {
            rects.firstOrNull { it.second.contains(x,y) }?.let { return it.first }
            if(y<rects.first().second.top-2*density || y>rects.last().second.bottom+5*density) return null
            return rects.minBy { (_,r) -> val dx=x-x.coerceIn(r.left,r.right);val dy=y-y.coerceIn(r.top,r.bottom);dx*dx+dy*dy }.first
        }
        for(i in 0 until cases.length()) {
            val case=cases.getJSONObject(i);val language=case.getString("language");val target=case.getString("target")
            prefs.layout=KeyboardLayouts.forLocale(Locale.forLanguageTag(language)).id;prefs.adaptive=false;measure(1080)
            val fixed=keys().map { it.code to RectF(it.anchor) };val key=keys().firstOrNull { it.code==target } ?: continue
            if(target.singleOrNull() !in 'a'..'z') continue
            val p=FloatArray(26) { case.getJSONArray("letters").getDouble(it).toFloat() }
            val pitch=key.anchor.width()+4*density;val height=key.anchor.height()+5*density
            val legacy=ArrayList<Pair<String,RectF>>()
            for(row in keys().groupBy { it.row }.values) {
                val gap=4*density;val available=row.last().anchor.right-row.first().anchor.left+gap
                val weights=row.map { k -> k.weight+(k.code.singleOrNull()?.takeIf { it in 'a'..'z' }?.let { minOf(.32f,p[it-'a']*.9f) } ?: 0f) }
                var x=row.first().anchor.left
                for((j,k) in row.withIndex()) { val width=available*weights[j]/weights.sum()-gap;legacy.add(k.code to RectF(x,k.anchor.top,x+width,k.anchor.bottom));x+=width+gap }
            }
            prefs.adaptive=true;view.prediction(emptyList(),p,true,language)
            val bounded=keys().map { it.code to RectF(it.rect) }
            fun candidate(budget:Float):List<Pair<String,RectF>> {
                val base=keys();val out=base.map { it.code to RectF(it.anchor) }
                for(row in base.indices.groupBy { base[it].row }.values) for((a,b) in row.zipWithNext()) {
                    val pitch=base[b].anchor.centerX()-base[a].anchor.centerX()
                    val shift=AdaptiveKeys.boundary(base[a].code,base[b].code,p,pitch,budget)
                    out[a].second.right+=shift;out[b].second.left+=shift
                }
                return out
            }
            val small=candidate(.04f);val large=candidate(.12f)
            for((profile,settings) in listOf("precise" to doubleArrayOf(.18,0.0,0.0),"thumb" to doubleArrayOf(.28,.13,.08),"noisy" to doubleArrayOf(.36,-.14,.11))) {
                val rng=Random(7391L+case.getString("id").hashCode());val errors=IntArray(5);var harm=0;var rescue=0;var oldHarm=0;var smallHarm=0;var largeHarm=0
                repeat(256) {
                    val x=(key.anchor.centerX()+(settings[1]+rng.nextGaussian()*settings[0])*pitch).toFloat()
                    val y=(key.anchor.centerY()+(settings[2]+rng.nextGaussian()*settings[0])*height).toFloat()
                    val choices=listOf(hit(fixed,x,y),hit(legacy,x,y),hit(bounded,x,y),hit(small,x,y),hit(large,x,y))
                    for(j in choices.indices) if(choices[j]!=target) errors[j]++
                    if(choices[0]==target && choices[2]!=target) harm++
                    if(choices[0]!=target && choices[2]==target) rescue++
                    if(choices[0]==target && choices[1]!=target) oldHarm++
                    if(choices[0]==target && choices[3]!=target) smallHarm++
                    if(choices[0]==target && choices[4]!=target) largeHarm++
                }
                rows.put(JSONObject().put("id",case.getString("id")).put("model",case.getString("model")).put("language",language).put("profile",profile)
                    .put("taps",256).put("fixed_errors",errors[0]).put("old_resizing_errors",errors[1]).put("bounded_errors",errors[2]).put("harmed",harm).put("rescued",rescue)
                    .put("small_errors",errors[3]).put("large_errors",errors[4]).put("old_harmed",oldHarm).put("small_harmed",smallHarm).put("large_harmed",largeHarm))
            }
        }
        return rows
    }
}
