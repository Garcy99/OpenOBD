package com.openobd

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.openobd.session.ConnectionState
import com.openobd.ui.LocalApp
import com.openobd.ui.OpenObdNavHost
import com.openobd.ui.theme.OpenObdTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenObdApp).container
        handleUsbAttach(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val state by container.session.state.collectAsStateWithLifecycle()
            LaunchedEffect(settings.keepScreenOn, state is ConnectionState.Connected) {
                if (settings.keepScreenOn && state is ConnectionState.Connected) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(Unit) {
                container.session.alerts.collect { vibrate() }
            }
            // Reload translations when the language changes and rebuild the UI so every screen picks them up.
            val language = settings.language
            androidx.compose.runtime.remember(language) {
                com.openobd.ui.I18n.load(this, language)
                (application as OpenObdApp).container.dtcDatabase.useLocalized = com.openobd.ui.I18n.language == "es"
                language
            }
            androidx.compose.runtime.key(language) {
                CompositionLocalProvider(LocalApp provides container) {
                    OpenObdTheme(settings.theme) { OpenObdNavHost() }
                }
            }
        }
    }

    /** Leaving the app: send this phone's changes to the user's Drive (when connected; at most every few minutes). */
    override fun onStop() {
        super.onStop()
        val c = (application as OpenObdApp).container
        c.scope.launch { c.sync.syncIfConnected() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleUsbAttach(intent)
    }

    /**
     * Android opened the app because an ELM327 USB cable was plugged in (typical on car head units, where
     * the cable stays connected and is re-detected at every ignition): switch to it and connect.
     */
    private fun handleUsbAttach(intent: android.content.Intent?) {
        if (intent?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java) ?: return
        val key = "%04X:%04X".format(device.vendorId, device.productId)
        val container = (application as OpenObdApp).container
        lifecycleScope.launch {
            container.settings.update { it.copy(connectionType = com.openobd.data.ConnectionType.USB, usbDevice = key, usbBaud = if (it.usbDevice == key) it.usbBaud else null) }
            val st = container.session.state.value
            if (st is ConnectionState.Disconnected || st is ConnectionState.Failed) container.session.connect()
        }
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        val pattern = longArrayOf(0, 300, 150, 300)
        if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
    }
}
