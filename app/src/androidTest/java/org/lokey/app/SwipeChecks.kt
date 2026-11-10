package org.lokey.app

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SwipeChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val languages=Languages.shared(context);val english=languages.all.first { it.code=="en" }
        val expanded=languages.swipe(english);val report=JSONObject();val rows=JSONArray()
        val fixture=File(context.filesDir,"swipe-test.json")
        if(fixture.exists()) {
            val data=JSONObject(fixture.readText());val keys=data.getJSONObject("layout").getJSONArray("keys");val cases=data.getJSONArray("cases")
            repeat(3) { pass -> for(i in 0 until cases.length()) {
                val case=cases.getJSONObject(i);val sy=(case.getDouble("canvas_height")/case.getDouble("canvas_width")*10).toFloat()
                val centers=(0 until keys.length()).associate { j -> val k=keys.getJSONObject(j);k.getString("letter").single() to Glide.Point(k.getDouble("cx").toFloat()*10,k.getDouble("cy").toFloat()*sy) }
                val raw=case.getJSONArray("points");val points=List(raw.length()) { j -> val p=raw.getJSONObject(j);Glide.Point(p.getDouble("x").toFloat()*10,p.getDouble("y").toFloat()*sy) }
                val start=SystemClock.elapsedRealtimeNanos();val old=Glide.decode(points,centers,english.entries);val oldTime=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                val indexedStart=SystemClock.elapsedRealtimeNanos();val words=Glide.decode(points,centers,expanded);val ms=(SystemClock.elapsedRealtimeNanos()-indexedStart)/1e6
                val scan=Glide.decode(points,centers,expanded.entries)
                check(words.map { it.word }==scan.map { it.word }) { "Endpoint index changed ranking for ${case.getString("id")}" }
                rows.put(JSONObject().put("id",case.getString("id")).put("pass",pass).put("reference",case.getString("word"))
                    .put("baseline",JSONArray(old.take(3).map { it.word })).put("words",JSONArray(words.take(3).map { it.word })).put("ms",ms).put("baseline_ms",oldTime))
            } }
            report.put("recorded_qwerty",rows).put("english_entries",expanded.entries.size)
        }
        val sandbox=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("swipe-check-$name",mode)
        }
        val prefs=Prefs(sandbox);val layouts=JSONArray()
        val field=KeyboardView::class.java.getDeclaredField("keys").apply { isAccessible=true }
        val cases=mapOf(
            "en" to listOf("hello","world","keyboard","thanks","please","tomorrow","meeting","language"),
            "it" to listOf("ciao","grazie","domani","buongiorno","parola","tastiera","amico","messaggio"),
            "es" to listOf("hola","gracias","mañana","niño","palabra","teclado","amigo","mensaje"),
            "pt" to listOf("olá","obrigado","amanhã","palavra","teclado","amigo","mensagem","coração"),
            "tr" to listOf("merhaba","teşekkür","yarın","dünya","kitap","klavye","arkadaş","mesaj"),
            "fr" to listOf("bonjour","merci","demain","clavier","ami","message","école","français"),
            "de" to listOf("hallo","danke","morgen","tastatur","freund","nachricht","straße","grüße"))
        for((id,code) in listOf("qwerty" to "en","intl" to "it","spanish" to "es","portuguese" to "pt","turkish" to "tr","azerty" to "fr","qwertz" to "de")) {
            var centers=emptyMap<Char,Glide.Point>()
            test.runOnMainSync {
                prefs.layout=id
                val view=KeyboardView(sandbox,prefs,object:KeyboardView.Listener {
                    override fun text(text:String) {};override fun delete() {};override fun enter() {};override fun cursor(delta:Int) {}
                    override fun voice(start:Boolean)=false;override fun cancelVoice() {};override fun nextKeyboard() {}
                    override fun candidate(word:String) {};override fun feedback(key:String,down:Boolean) {}
                })
                view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,1080,view.measuredHeight)
                @Suppress("UNCHECKED_CAST") val keys=field.get(view) as List<KeyboardView.Key>
                centers=keys.filter { it.code.singleOrNull()?.isLetter()==true }.associate { it.code.single() to Glide.Point(it.anchor.centerX()*10/view.width,it.anchor.centerY()*10/view.width) }
            }
            val language=languages.all.first { it.code==code };val lexicon=languages.swipe(language);val results=JSONArray()
            for(word in cases.getValue(code)) {
                val path=word.flatMap { c -> (if(c in centers) c.toString() else Glide.fold(c.toString())).map { centers.getValue(it) } }
                val trace=ArrayList<Glide.Point>();trace.add(path.first())
                for((a,b) in path.zipWithNext()) for(j in 1..12) trace.add(Glide.Point(a.x+(b.x-a.x)*j/12,a.y+(b.y-a.y)*j/12))
                val start=SystemClock.elapsedRealtimeNanos();val words=Glide.decode(trace,centers,lexicon)
                results.put(JSONObject().put("reference",word).put("words",JSONArray(words.take(3).map { it.word })).put("ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6))
                check(words.take(3).any { it.word==word }) { "Swipe missed $id/$word: $words" }
            }
            layouts.put(JSONObject().put("layout",id).put("language",code).put("entries",lexicon.entries.size).put("rows",results))
        }
        report.put("synthetic_layouts",layouts).put("synthetic_notice","Paths follow actual key centers; not recorded human accuracy.")
        sandbox.getSharedPreferences("lokey",0).edit().clear().commit()
        return report
    }
}
