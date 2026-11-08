package com.edgestream.tv

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Screenshots for the product website: every third-party image (backdrop photos and clips,
 * posters, film art, team and channel logos) is left out, so each screen shows the app's own
 * placeholders. Debug builds only, and only while `files/press_mode` exists:
 * `adb shell run-as com.edgestream.tv touch files/press_mode` (remove the file to turn it off).
 */
object PressMode {
    @Volatile var on = false
        private set

    /** The owner's own artwork (collection covers in their GitHub repositories) stays visible. */
    fun isOwnArt(url: String) = url.startsWith("https://raw.githubusercontent.com/EDGEL00P/")

    fun init(context: Context) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        on = debuggable && File(context.filesDir, "press_mode").exists()
    }
}
