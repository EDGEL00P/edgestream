package com.edgestream.tv.data

import android.media.AudioDeviceInfo
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {

    @Test fun displayMode4k60() = assertEquals("3840 × 2160 · 60 Hz", DeviceInfo.formatMode(3840, 2160, 59.94f))

    @Test fun displayMode1080p50() = assertEquals("1920 × 1080 · 50 Hz", DeviceInfo.formatMode(1920, 1080, 50f))

    @Test fun hdrNamesInOrder() = assertEquals(
        "Dolby Vision · HDR10 · HLG",
        DeviceInfo.formatHdr(intArrayOf(Display.HdrCapabilities.HDR_TYPE_HLG, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION, Display.HdrCapabilities.HDR_TYPE_HDR10)),
    )

    @Test fun noHdr() = assertEquals("SDR only", DeviceInfo.formatHdr(intArrayOf()))

    @Test fun audioPrefersHdmiArc() = assertEquals(
        "HDMI ARC",
        DeviceInfo.audioOutputName(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI)),
    )

    @Test fun audioHdmi() = assertEquals("HDMI", DeviceInfo.audioOutputName(listOf(AudioDeviceInfo.TYPE_HDMI)))

    @Test fun audioBluetooth() = assertEquals("Bluetooth", DeviceInfo.audioOutputName(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HDMI)))

    @Test fun audioUnknown() = assertEquals("TV speakers", DeviceInfo.audioOutputName(emptyList()))

    @Test fun bytes() {
        assertEquals("24.5 GB", DeviceInfo.formatBytes(24_500_000_000L))
        assertEquals("812 MB", DeviceInfo.formatBytes(812_000_000L))
    }
}
