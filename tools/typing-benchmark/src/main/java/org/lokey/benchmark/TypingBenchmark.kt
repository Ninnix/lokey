package org.lokey.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

class TypingBenchmark:Instrumentation() {
    private lateinit var args:Bundle
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);args=arguments ?: Bundle();start() }
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun nodes(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> {
        val out=arrayListOf(node)
        for(i in 0 until node.childCount) node.getChild(i)?.let { out.addAll(nodes(it)) }
        return out
    }
    private fun keyboard():List<AccessibilityNodeInfo> =uiAutomation.windows.filter { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .flatMap { it.root?.let(::nodes).orEmpty() }
    private fun bounds(node:AccessibilityNodeInfo)=Rect().apply { node.getBoundsInScreen(this) }
    private fun centers():Map<Char,Pair<Float,Float>> {
        val out=HashMap<Char,Pair<Float,Float>>()
        for(node in keyboard()) {
            val label=(node.text ?: node.contentDescription)?.toString()?.lowercase() ?: continue
            val first=label.substringBefore(',').removePrefix("capital ")
            val char=when {
                first.length==1 && first[0] in 'a'..'z' -> first[0]
                label in listOf("space","spacebar") || label.startsWith("space,") -> ' '
                first in listOf("delete","backspace","⌫") -> '\b'
                else -> continue
            }
            val rect=bounds(node)
            if(rect.width()>0 && rect.height()>0) out[char]=rect.exactCenterX() to rect.exactCenterY()
        }
        return out
    }
    private fun touch(action:Int,at:Long,down:Long,x:Float,y:Float) {
        val event=MotionEvent.obtain(down,at,action,x,y,0)
        event.source=InputDevice.SOURCE_TOUCHSCREEN
        check(uiAutomation.injectInputEvent(event,false)) { "Touch injection failed" };event.recycle()
    }
    override fun onStart() {
        val report=JSONObject()
        var activity:TypingActivity?=null
        val original=shell("settings get secure default_input_method")
        try {
            val ime=args.getString("ime") ?: error("Pass -e ime package/service")
            check(Regex("[A-Za-z0-9_.$/]+").matches(ime))
            val terminal=args.getString("terminal")=="true"
            val delay=args.getString("read_delay_ms")?.toInt() ?: 0
            val interval=args.getString("interval_ms")?.toLong() ?: 40L
            val rounds=args.getString("rounds")?.toInt() ?: 3
            val text=args.getString("text") ?: "the quick brown fox jumps over the lazy dog "
            val deletes=args.getString("deletes")?.toInt() ?: 8
            check(interval>=8 && rounds>0 && text.all { it in 'a'..'z' || it==' ' })
            uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            shell("ime enable $ime");shell("ime set $ime")
            activity=startActivitySync(Intent(targetContext,TypingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("terminal",terminal).putExtra("read_delay_ms",delay)) as TypingActivity
            SystemClock.sleep(1500)
            var keys=centers()
            val until=SystemClock.uptimeMillis()+10000
            while((text.toSet()+ '\b').any { it !in keys } && SystemClock.uptimeMillis()<until) { SystemClock.sleep(250);keys=centers() }
            val tree=JSONArray()
            keyboard().forEach { tree.put(JSONObject().put("text",it.text).put("description",it.contentDescription).put("bounds",bounds(it).toShortString())) }
            report.put("keyboard_nodes",tree)
            check((text.toSet()+ '\b').all { it in keys }) { "Missing keys: ${(text.toSet()+'\b').filter { it !in keys }}" }
            val samples=JSONArray()
            for(round in 0..rounds) {
                runOnMainSync { activity.clear() }
                SystemClock.sleep(350)
                val sequence=text+"\b".repeat(deletes)
                val states=ArrayList<String>();var expected=""
                for(char in sequence) { expected=if(char=='\b') expected.dropLast(1) else expected+char;states.add(expected) }
                val releases=ArrayList<Long>();val injected=ArrayList<Long>();val began=SystemClock.uptimeMillis()+80
                for((i,char) in sequence.withIndex()) {
                    check(activity.focused) { "Benchmark editor lost focus; stopping input" }
                    val down=began+i*interval
                    SystemClock.sleep((down-SystemClock.uptimeMillis()).coerceAtLeast(0))
                    val (x,y)=keys.getValue(char)
                    injected.add(SystemClock.uptimeMillis());touch(MotionEvent.ACTION_DOWN,down,down,x,y)
                    val up=down+8
                    SystemClock.sleep((up-SystemClock.uptimeMillis()).coerceAtLeast(0))
                    releases.add(up);touch(MotionEvent.ACTION_UP,up,down,x,y)
                }
                SystemClock.sleep(3000)
                var actual="";var changes=emptyList<Pair<Long,String>>();var reads=emptyMap<String,Int>()
                runOnMainSync { actual=activity.value;changes=activity.changes.toList();reads=activity.reads.toMap() }
                val latency=ArrayList<Long>();var matched=-1
                for((time,value) in changes) {
                    val next=(matched+1 until states.size).firstOrNull { states[it].equals(value,true) } ?: continue
                    for(i in matched+1..next) latency.add((time-releases[i]).coerceAtLeast(0))
                    matched=next
                }
                val sorted=latency.sorted()
                fun percentile(p:Double)=if(sorted.isEmpty()) JSONObject.NULL else sorted[(ceil(sorted.size*p).toInt()-1).coerceAtLeast(0)]
                val row=JSONObject().put("warmup",round==0).put("keys",sequence.length).put("expected",expected).put("actual",actual)
                    .put("correct",actual.equals(expected,true)).put("matched_events",latency.size).put("latency_p50_ms",percentile(.5))
                    .put("latency_p95_ms",percentile(.95)).put("latency_max_ms",sorted.lastOrNull()).put("editor_reads",JSONObject(reads))
                    .put("injection_max_lateness_ms",injected.mapIndexed { i,t -> t-(began+i*interval) }.maxOrNull())
                    .put("changes",JSONArray(changes.map { (t,s) -> JSONObject().put("at_ms",t-began).put("text",s) }))
                samples.put(row)
            }
            report.put("ime",ime).put("terminal",terminal).put("read_delay_ms",delay).put("interval_ms",interval)
                .put("offered_wpm",12000.0/interval).put("settle_ms",3000).put("rounds",samples).put("passed",true)
        } catch(error:Throwable) { report.put("passed",false).put("error",error.stackTraceToString()) }
        finally { runOnMainSync { activity?.finish() };shell("ime set $original") }
        File(targetContext.getExternalFilesDir(null),"typing-benchmark.json").writeText(report.toString(2))
        finish(if(report.optBoolean("passed")) ActivityResult.OK else ActivityResult.FAIL,Bundle().apply { putString("results",report.toString()) })
    }
    private object ActivityResult { const val OK=-1;const val FAIL=0 }
}
