package org.lokey.app

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.widget.LinearLayout

class ImeLayout(context:Context):LinearLayout(context) {
    init { orientation=VERTICAL }
    override fun onApplyWindowInsets(insets:WindowInsets):WindowInsets {
        val left:Int;val right:Int;val bottom:Int
        if(Build.VERSION.SDK_INT>=30) {
            val safe=insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.displayCutout())
            left=safe.left;right=safe.right;bottom=safe.bottom
        } else {
            val cutout=insets.displayCutout
            left=maxOf(insets.systemWindowInsetLeft,cutout?.safeInsetLeft ?: 0)
            right=maxOf(insets.systemWindowInsetRight,cutout?.safeInsetRight ?: 0)
            bottom=maxOf(insets.systemWindowInsetBottom,cutout?.safeInsetBottom ?: 0)
        }
        // Ancestors may already consume navigation insets on older Android versions.
        if(paddingLeft!=left || paddingRight!=right || paddingBottom!=bottom) setPadding(left,0,right,bottom)
        return insets
    }
}
