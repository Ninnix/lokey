package org.lokey.app

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

object PrivacyChecks {
    fun run(test:Instrumentation,context:Context):JSONObject {
        val opened=AtomicReference<PrivacyActivity>()
        val app=context.applicationContext as Application
        val callback=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity:Activity,state:Bundle?) { if(activity is PrivacyActivity) opened.set(activity) }
            override fun onActivityStarted(activity:Activity) { }
            override fun onActivityResumed(activity:Activity) { }
            override fun onActivityPaused(activity:Activity) { }
            override fun onActivityStopped(activity:Activity) { }
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) { }
            override fun onActivityDestroyed(activity:Activity) { }
        }
        app.registerActivityLifecycleCallbacks(callback)
        fun views(view:View):List<TextView> = (if(view is TextView) listOf(view) else emptyList())+
            if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        val models=test.startActivitySync(Intent().setClass(context,MainActivity::class.java).putExtra("page","models").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var setup:Activity?=null
        val prefs=Prefs(context);val originalLanguage=prefs.setupLanguage
        try {
            test.waitForIdleSync()
            test.runOnMainSync {
                val labels=views(models.window.decorView)
                check(labels.any { it.text.toString()==context.tr("Model downloads connect to Hugging Face, which receives your IP address. Typing and audio stay on your device.") })
                val credits=labels.first { it.text.toString()==context.tr("Models & inference credits") }
                val privacy=labels.first { it.text.toString()==context.tr("Privacy policy") }
                check(privacy.top-credits.bottom>=8*context.resources.displayMetrics.density) { "Models footer buttons touch" }
                labels.first { it.text.toString()==context.tr("Privacy policy") }.performClick()
            }
            val end=SystemClock.uptimeMillis()+8000
            while(opened.get()==null && SystemClock.uptimeMillis()<end) SystemClock.sleep(30)
            val policy=checkNotNull(opened.get()) { "Models did not open the privacy policy" }
            test.waitForIdleSync()
            val bundled=context.assets.open("privacy-policy.txt").bufferedReader().use { it.readText() }
            check(bundled.startsWith("Lokey privacy policy\n"))
            check(context.assets.open("licenses/lokey.txt").bufferedReader().use { it.readText() }.startsWith("MIT License\n"))
            check("nicolo.deva@gmail.com" in bundled && "Nicolo' D'Evangelista" in bundled)
            check("Hugging Face" in bundled && "one hour" in bundled && "uninstall Lokey" in bundled)
            test.runOnMainSync {
                val body=views(policy.window.decorView).first { it.text.toString()==bundled }
                check(body.isTextSelectable)
                check((body.text as Spanned).getSpans(0,body.length(),URLSpan::class.java).any { it.url=="mailto:nicolo.deva@gmail.com" })
                policy.finish()
            }
            setup=test.startActivitySync(Intent().setClass(context,SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.waitForIdleSync()
            test.runOnMainSync { check(views(setup!!.window.decorView).any { it.text.toString()==context.tr("Privacy policy") }) }
            return JSONObject().put("models_link_opens_policy",true).put("setup_link",true).put("bundled_policy_offline",true)
                .put("contact_and_retention",true).put("clickable_contact",true).put("download_metadata_disclosed",true)
        } finally {
            prefs.setupLanguage=originalLanguage
            test.runOnMainSync { opened.get()?.finish();setup?.finish();models.finish() }
            app.unregisterActivityLifecycleCallbacks(callback)
        }
    }
}
