package org.lokey.app

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object SpeedPassages {
    data class Passage(val code:String,val text:String) {
        val locale get()=Locale.forLanguageTag(code)
        val id get()="inferno-i-1-6-$code-v1"
    }
    fun selected(context:Context,prefs:Prefs):Passage {
        val language=ModelLanguages.selected(context,prefs)
        val code=if(language.code=="zh" && context.uiLocale().script=="Hant") "zh-Hant" else language.code
        val passages=JSONObject(context.assets.open("speed-passages.json").bufferedReader().use { it.readText() })
        return Passage(code,passages.getString(code))
    }
}
