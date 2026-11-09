package com.edgestream.tv

import androidx.activity.viewModels
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.edgestream.tv.ui.EdgeApp
import com.edgestream.tv.ui.EdgeTheme

class MainActivity : ComponentActivity() {
    private val vm: com.edgestream.tv.ui.AppViewModel by viewModels()

    /** The remote's GUIDE button works from anywhere, whatever has focus. */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) vm.remoteUsed.value = true
        if (event.keyCode == android.view.KeyEvent.KEYCODE_GUIDE) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.openGuide()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeLink(intent)
        setContent { EdgeTheme { EdgeApp() } }

        val app = EdgeStreamApp.instance
        // Keys from the phone companion arrive as if pressed on the remote.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.edgestream.tv.remote.PhoneLink.commands.collect { c ->
                    when (c) {
                        is com.edgestream.tv.remote.PhoneLink.Command.Key -> pressKey(c.code)
                        else -> Unit // play/search/open are handled by the view model
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.player.state, app.settings.flow, app.player.fullscreen) { st, s, full ->
                    if (s.matchFrameRate && full && st.isPlaying) st.frameRate else 0f
                }
                    .distinctUntilChanged()
                    .collect(::matchDisplayMode)
            }
        }
    }

    /**
     * Picks the display mode at the current resolution whose refresh rate is an exact multiple
     * of the video frame rate (23.976 -> 23.976/47.95 Hz, 25 -> 50 Hz, 29.97 -> 59.94 Hz).
     * 0 restores the TV's default mode.
     */
    private fun matchDisplayMode(fps: Float) {
        val display = window.decorView.display ?: return
        val current = display.mode
        val target = if (fps <= 0f) null else display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { m -> val ratio = m.refreshRate / fps; kotlin.math.abs(ratio - kotlin.math.round(ratio)) < 0.01f && ratio >= 0.99f }
            .minByOrNull { it.refreshRate }
        val attrs = window.attributes
        val id = target?.modeId ?: 0
        if (attrs.preferredDisplayModeId != id) {
            attrs.preferredDisplayModeId = id
            window.attributes = attrs
        }
    }

    /** Home-screen cards open the app with an edgestream:// link; singleTask brings them here. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // The Home key while EdgeStream is the launcher brings it back to the front: land on Home.
        if (intent.action == android.content.Intent.ACTION_MAIN && intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            if (vm.fullscreen.value) vm.exitFullscreen()
            vm.screen.value = com.edgestream.tv.ui.Screen.HOME
        }
        takeLink(intent)
    }

    private fun takeLink(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == com.edgestream.tv.launcher.Links.SCHEME) com.edgestream.tv.ui.LauncherLinks.pending.value = uri
    }

    /**
     * Injected through the input pipeline, not dispatched to this activity, so the key reaches
     * whichever of our windows has focus: dialogs are separate windows the activity never sees.
     * Android allows injecting into the app's own windows without INJECT_EVENTS; the call blocks
     * until the key is handled, so it runs off the main thread (the collector keeps keys in order).
     */
    private suspend fun pressKey(code: Int) {
        val injected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                android.app.Instrumentation().sendKeyDownUpSync(code); true
            } catch (_: SecurityException) {
                false // focus is on another app's window (a system dialog); fall back to this activity
            }
        }
        if (!injected) {
            val now = android.os.SystemClock.uptimeMillis()
            dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0))
            dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0))
        }
    }

    /** Turns text into key events the focused field understands; characters the keymap can't type are skipped. */
    override fun onStart() {
        super.onStart()
        EdgeStreamApp.instance.player.resume()
        EdgeStreamApp.instance.companion.start()
    }

    override fun onStop() {
        super.onStop()
        // Don't keep streaming (or hold a provider connection / one of the SHIELD's two
        // decoders) while the app is hidden.
        EdgeStreamApp.instance.player.releaseDecoder()
        // The phone server only listens while the app is on screen.
        EdgeStreamApp.instance.companion.stop()
    }
}
