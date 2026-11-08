package com.edgestream.tv.data

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import kotlin.math.roundToInt

/** What the Device settings page shows: all read from the system, nothing invented. */
data class DeviceSnapshot(
    val model: String,
    val android: String,
    val mode: String,
    val hdr: String,
    val audio: String,
    val network: String,
    val storageFree: String,
    val storageTotal: String,
    val memoryFree: String,
)

object DeviceInfo {
    /** API 31's TYPE_HDMI_EARC, by value so it reads on the SHIELD's API 30 too. */
    private const val TYPE_HDMI_EARC = 29

    fun formatMode(width: Int, height: Int, refreshHz: Float): String = "$width × $height · ${refreshHz.roundToInt()} Hz"

    fun formatHdr(types: IntArray): String {
        val order = listOf(
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION to "Dolby Vision",
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS to "HDR10+",
            Display.HdrCapabilities.HDR_TYPE_HDR10 to "HDR10",
            Display.HdrCapabilities.HDR_TYPE_HLG to "HLG",
        )
        return order.filter { (t, _) -> t in types }.joinToString(" · ") { it.second }.ifEmpty { "SDR only" }
    }

    /** The output sound actually goes to, most specific first (a soundbar beats plain HDMI). */
    fun audioOutputName(types: List<Int>): String = when {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types -> "Bluetooth"
        TYPE_HDMI_EARC in types -> "HDMI eARC"
        AudioDeviceInfo.TYPE_HDMI_ARC in types -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI in types -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE in types || AudioDeviceInfo.TYPE_USB_HEADSET in types -> "USB audio"
        else -> "TV speakers"
    }

    fun formatBytes(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000L} MB"

    fun snapshot(ctx: Context): DeviceSnapshot {
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val audio = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val stat = StatFs(Environment.getDataDirectory().path)
        val mem = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        return DeviceSnapshot(
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim(),
            android = "Android ${Build.VERSION.RELEASE}",
            mode = mode?.let { formatMode(it.physicalWidth, it.physicalHeight, it.refreshRate) } ?: "Unknown",
            hdr = formatHdr(display?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)),
            audio = audioOutputName(audio),
            network = network(ctx),
            storageFree = formatBytes(stat.availableBytes),
            storageTotal = formatBytes(stat.totalBytes),
            memoryFree = formatBytes(mem.availMem),
        )
    }

    private fun network(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        val speed = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { " · ${it / 1000} Mbps" }.orEmpty()
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet$speed"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi$speed"
            else -> "Connected$speed"
        }
    }.getOrDefault("Unknown")
}
