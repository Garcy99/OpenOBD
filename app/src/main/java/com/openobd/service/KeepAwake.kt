package com.openobd.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Keeps the processor (and, when the link goes over Wi-Fi, the Wi-Fi radio at full speed) awake while the car is
 * being read: with the screen off a phone otherwise sleeps between packets, which shows as lag, dropped answers and
 * lost links. Released as soon as the link ends.
 */
class KeepAwake(context: Context, private val tag: String) {
    private val app = context.applicationContext
    private var cpu: PowerManager.WakeLock? = null
    private val wifi = mutableListOf<WifiManager.WifiLock>()

    @Synchronized fun hold(overWifi: Boolean) {
        if (cpu == null) runCatching {
            cpu = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenOBD:$tag")
                ?.apply { setReferenceCounted(false); acquire(MAX_MS) }
        }
        if (overWifi && wifi.isEmpty()) runCatching {
            val wm = app.getSystemService(WifiManager::class.java) ?: return@runCatching
            // Full power while the screen is off (older Android) and low latency while it is on (newer).
            @Suppress("DEPRECATION")
            wifi += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OpenOBD:$tag")
            if (Build.VERSION.SDK_INT >= 29) wifi += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "OpenOBD:$tag:fast")
            wifi.forEach { it.setReferenceCounted(false); it.acquire() }
        }
        if (!overWifi) releaseWifi()
    }

    @Synchronized fun release() {
        runCatching { cpu?.takeIf { it.isHeld }?.release() }
        cpu = null
        releaseWifi()
    }

    private fun releaseWifi() {
        wifi.forEach { l -> runCatching { if (l.isHeld) l.release() } }
        wifi.clear()
    }

    companion object {
        /** A safety limit: renewed by the next hold, released when the link ends. */
        private const val MAX_MS = 6 * 3600_000L
    }
}
