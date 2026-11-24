package io.executionbridge.android

import android.os.Build
import android.view.View
import android.view.WindowInsets

/** Keep controls outside status/navigation bars and cutouts on edge-to-edge Android. */
@Suppress("DEPRECATION")
internal fun applySystemInsets(view:View) {
    if(Build.VERSION.SDK_INT<30) return
    view.setOnApplyWindowInsetsListener { v, insets ->
        val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime=insets.getInsets(WindowInsets.Type.ime())
        v.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom))
        insets
    }
    view.requestApplyInsets()
}
