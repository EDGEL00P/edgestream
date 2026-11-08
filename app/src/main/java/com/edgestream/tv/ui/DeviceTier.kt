package com.edgestream.tv.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Runtime focus-effect tier. The focused item gets the expensive treatment; everything else
 * stays flat. FULL is for 3 GB+ boxes such as the SHIELD, BALANCED for typical 2 GB TV boxes,
 * LOW for 1.5 GB sticks.
 */
object DeviceTier {
    enum class Tier { FULL, BALANCED, LOW }

    var tier by mutableStateOf(Tier.BALANCED)
        private set

    private var detected = Tier.BALANCED
    private var override = "auto"

    fun init(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val totalGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
        detected = when {
            am?.isLowRamDevice == true || totalGb < 2.0 -> Tier.LOW
            totalGb >= 3.0 -> Tier.FULL
            else -> Tier.BALANCED
        }
        tier = resolve()
    }

    /** "auto", "full", "balanced" or "low" from Settings. */
    fun setOverride(value: String) {
        override = value
        tier = resolve()
    }

    private fun resolve(): Tier = when (override) {
        "full" -> Tier.FULL
        "balanced" -> Tier.BALANCED
        "low" -> Tier.LOW
        else -> detected
    }
}
