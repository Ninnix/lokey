package org.lokey.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import org.json.JSONObject

class CreditsActivity:AppScreen() {
    override fun onCreate(state:Bundle?) { super.onCreate(state);render() }
    private fun open(url:String) { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
    private fun license(title:String,path:String) {
        val sheet=EditorSheet(this,prefs,title)
        val value=assets.open(path).bufferedReader().use { it.readText() }
        sheet.body.addView(sheet.text(value,12f).apply { setTextIsSelectable(true);setLineSpacing(dp(3).toFloat(),1f) })
        sheet.action(tr("Close")) { sheet.dismiss() };sheet.show()
    }
    override fun render() {
        val input=intent.getStringExtra("section")=="input"
        val root=frame(if(input) tr("Input credits") else tr("Credits")) { finish() }
        val body=vertical().apply { setPadding(dp(24),dp(8),dp(24),dp(20)) }
        root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        fun heading(title:String) { body.addView(text(title,21f,bold=true).apply { setPadding(0,dp(20),0,dp(12)) }) }
        fun card()=vertical().apply {
            background=shape();setPadding(dp(16),dp(16),dp(16),dp(16));body.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        }
        fun LinearLayout.note(value:String) { addView(text(value,13f,muted).apply { setPadding(0,dp(6),0,dp(6));setLineSpacing(dp(2).toFloat(),1f) }) }
        fun LinearLayout.link(label:String,url:String) { addView(button(label) { open(url) }.apply { setTextColor(accent) },LinearLayout.LayoutParams(-1,-2)) }
        fun LinearLayout.notice(label:String,path:String) { addView(button(label) { license(label,path) }.apply { setTextColor(accent) },LinearLayout.LayoutParams(-1,-2)) }
        if(input) {
            body.addView(text(tr("Input & design credits"),26f,bold=true))
            heading(tr("Recordings & artwork"))
            card().apply {
                notice(tr("Mechanical switch recordings"),"switches/CREDITS.txt")
                notice(tr("Mechvibes recording license"),"switches/MECHVIBES-LICENSE.txt")
                notice(tr("Keyboard simulator recording license"),"switches/KBSIM-LICENSE.txt")
                notice(tr("Light-switch recording"),"ui/CREDITS.txt")
                notice(tr("Great Wave artwork"),"themes/CREDITS.txt")
                notice(tr("Provider logo sources"),"model-brand-sources.json")
            }
            heading(tr("Keyboard & language data"))
            card().apply {
                note(tr("Keyboard icons, alphabets, dictionaries, and conversion data."))
                val covered=setOf("lfm-model.txt","qwen-model.txt","qwen3-asr.txt","parakeet-model.txt","loina-core.txt","llama.cpp.txt","whisper.cpp.txt","hexagon-toolchain.txt","android-ndk.txt","rotate-bits.txt","sha256.txt","xxhash.txt")
                for(file in assets.list("licenses").orEmpty().filter { it !in covered }.sorted()) notice(file.removeSuffix(".txt"),"licenses/$file")
                for(file in listOf("input-method-sources.json","pinyin-sources.json","japanese-sources.json")) notice(file.removeSuffix(".json"),file)
            }
            return
        }
        body.addView(text(tr("The people behind Lokey."),26f,bold=true))
        body.addView(text(tr("Model weights and inference code have separate licenses. Their notices are available here offline."),13f,muted).apply { setPadding(0,dp(10),0,0);setLineSpacing(dp(3).toFloat(),1f) })
        heading(tr("Models"))
        for(credit in ModelCredits.all) {
            val model=(Models.text+Models.voices).first { it.id==credit.id }
            card().apply {
                val header=LinearLayout(this@CreditsActivity).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
                header.addView(ModelCredits.logo(this@CreditsActivity,prefs,credit.id),LinearLayout.LayoutParams(dp(36),dp(36)).apply { marginEnd=dp(12) })
                header.addView(text(model.title,19f,bold=true));addView(header)
                note(credit.provider+" · "+credit.license)
                note(tr("%1\$s\nGGUF conversion: %2\$s\n%3\$s",credit.format,credit.conversion,model.filename)+(if(model.parts.isNotEmpty()) "\n${model.parts.joinToString("\n") { it.filename }}" else ""))
                link(tr("Original model & provider"),credit.source)
                link(tr("GGUF source"),model.url.substringBefore("/resolve/"))
                notice(tr("Model license"),"licenses/${credit.file}")
            }
        }
        heading(tr("Inference"))
        val runtimes=JSONObject(assets.open("runtimes.json").bufferedReader().use { it.readText() })
        val roles=mapOf("loina-core" to tr("Loina AI · C inference API and speech adapters."),"llama.cpp" to tr("ggml authors and Loina AI contributors · text inference, Qwen ASR and acceleration."),"whisper.cpp" to tr("ggml authors and Loina AI contributors · Parakeet speech inference in the Loina fork."))
        for(name in listOf("loina-core","llama.cpp","whisper.cpp")) {
            val runtime=runtimes.getJSONObject(name);val revision=runtime.getString("revision");val url=runtime.getString("url")
            card().apply {
                addView(text(name,19f,bold=true));note(roles.getValue(name)+tr("\nMIT License"))
                addView(text(tr("Revision %1\$s",revision),11f,muted).apply { setTextIsSelectable(true) })
                link(tr("Source at this revision"),"$url/tree/$revision")
                if(name!="loina-core") link(tr("Upstream project"),"https://github.com/ggml-org/$name")
                notice(tr("License & contributor notices"),"licenses/$name.txt")
            }
        }
        card().apply {
            addView(text(tr("ggml & Hexagon"),19f,bold=true))
            note(tr("ggml provides tensor operations and CPU / DSP kernels. The optional Hexagon backend is built with Qualcomm’s SDK and uses the phone’s Qualcomm system libraries under separate vendor terms. CPU fallback remains available."))
            val llama=runtimes.getJSONObject("llama.cpp")
            link(tr("ggml source in this build"),llama.getString("url")+"/tree/"+llama.getString("revision")+"/ggml")
            notice(tr("Hexagon toolchain notices"),"licenses/hexagon-toolchain.txt")
            notice(tr("Android NDK distribution notices"),"licenses/android-ndk.txt")
        }
        card().apply {
            addView(text(tr("Supporting code"),19f,bold=true))
            for(file in listOf("rotate-bits.txt","sha256.txt","xxhash.txt")) notice(file.removeSuffix(".txt"),"licenses/$file")
        }

    }
}
