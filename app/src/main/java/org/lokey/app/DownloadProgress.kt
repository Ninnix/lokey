package org.lokey.app

import android.content.Context
import android.content.res.ColorStateList
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class DownloadProgress(context:Context,accent:Int,muted:Int,size:Float=12f):LinearLayout(context) {
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private val label=TextView(context).apply { textSize=size;setTextColor(muted);maxLines=2;gravity=android.view.Gravity.CENTER_VERTICAL }
    private val bar=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=100;progressTintList=ColorStateList.valueOf(accent);indeterminateTintList=ColorStateList.valueOf(accent)
        progressBackgroundTintList=ColorStateList.valueOf((muted and 0xffffff) or 0x33000000)
    }
    init {
        orientation=VERTICAL
        addView(label,LayoutParams(-1,dp(32)));addView(bar,LayoutParams(-1,dp(4)))
    }
    fun update(state:Downloads.State?,idle:String) {
        val text=context.tr(state?.message ?: idle)
        if(label.text.toString()!=text) label.text=text
        bar.visibility=if(state?.active==true) VISIBLE else INVISIBLE
        bar.isIndeterminate=state?.active==true && state.total<=0
        bar.progress=state?.percent ?: 0
        contentDescription=context.tr("Download status: %1\$s",text)
    }
}
