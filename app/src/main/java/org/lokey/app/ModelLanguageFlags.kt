package org.lokey.app

import android.content.Context
import android.icu.util.ULocale
import android.view.Gravity
import android.view.View
import android.widget.TextView

internal fun Context.modelLanguageFlags(languages:List<ModelLanguages.Language>,centered:Boolean=false)=TextView(this).apply {
    text=languages.joinToString(" ") { language ->
        val region=ULocale.addLikelySubtags(ULocale.forLanguageTag(language.code)).country
        regionFlag(region)
    }
    contentDescription=tr("Supported languages")+" · "+languages.joinToString(", ") { tr(it.name) }
    textSize=12f
    textDirection=View.TEXT_DIRECTION_LTR
    gravity=if(centered) Gravity.CENTER_HORIZONTAL else Gravity.START
    includeFontPadding=false
    setLineSpacing(2*resources.displayMetrics.density,1f)
    isScreenReaderFocusable=true
}
