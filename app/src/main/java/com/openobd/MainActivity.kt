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
import com.openobd.session.ConnectionState
import com.openobd.ui.LocalApp
import com.openobd.ui.OpenObdNavHost
import com.openobd.ui.theme.OpenObdTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenObdApp).container
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
            CompositionLocalProvider(LocalApp provides container) {
                OpenObdTheme(settings.theme) { OpenObdNavHost() }
            }
        }
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }
}
