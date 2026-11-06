package org.lokey.app

import android.os.Bundle
import android.text.util.Linkify
import android.widget.LinearLayout
import android.widget.ScrollView

class PrivacyActivity:AppScreen() {
    override fun onCreate(state:Bundle?) { super.onCreate(state);render() }
    override fun render() {
        val root=frame(tr("Privacy policy")) { finish() }
        val policy=assets.open("privacy-policy.txt").bufferedReader().use { it.readText() }
        val body=text(policy,14f).apply {
            setPadding(dp(24),dp(12),dp(24),dp(24));setLineSpacing(dp(3).toFloat(),1f)
            autoLinkMask=Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES
            setLinkTextColor(accent);setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
    }
}
