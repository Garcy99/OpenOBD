package com.openobd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.R
import com.openobd.session.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service that keeps the OBD connection (and data logging) alive in the background. */
class ObdService : LifecycleService() {
    private val session get() = (application as OpenObdApp).container.session
    private var gauges: FloatingGauges? = null
    private var gaugesJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Vehicle connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while OpenOBD is connected to your car"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) {
            session.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW_GAUGES) { showGauges(); return START_NOT_STICKY }
        if (intent?.action == ACTION_HIDE_GAUGES) { hideGauges(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build("Connected"),
                if (Build.VERSION.SDK_INT >= 29) {
                    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    val gps = (application as OpenObdApp).container.settings.settings.value.tripGps
                    if (gps && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                    type
                } else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        lifecycleScope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (true) {
                val s = session.state.value
                if (s !is ConnectionState.Connected) { stopSelf(); break }
                val rpm = session.live.latestValue("010C")
                val speed = session.live.latestValue("010D")
                val parts = listOfNotNull(
                    rpm?.let { "%.0f rpm".format(it) },
                    speed?.let { "%.0f km/h".format(it) },
                    s.vehicle.voltage?.let { "%.1f V".format(it) },
                    if (session.recording.value) "● recording" else null,
                )
                nm.notify(NOTIFICATION_ID, build(parts.joinToString(" · ").ifEmpty { s.vehicle.elm.protocol.label }))
                delay(3000)
            }
        }
        return START_NOT_STICKY
    }

    /** Floating window with the first three dashboard values, over other apps. */
    private fun showGauges() {
        if (gauges?.isShown == true || !FloatingGauges.canShow(this)) return
        val settings = (application as OpenObdApp).container.settings
        val keys = settings.settings.value.dashboard.map { it.substringBefore('@') }.distinct().take(3)
        val g = gauges ?: FloatingGauges(this).also { gauges = it }
        g.show(keys) { com.openobd.ui.tr(it) }
        if (!g.isShown) return
        session.live.subscribe("overlay", keys)
        gaugesShown.value = true
        gaugesJob?.cancel()
        gaugesJob = lifecycleScope.launch {
            while (g.isShown) {
                g.update({ session.live.latestValue(it) }, settings.settings.value.units)
                delay(500)
            }
            session.live.unsubscribe("overlay")
            gaugesShown.value = false
        }
    }

    private fun hideGauges() {
        gauges?.hide()
        gaugesJob?.cancel()
        session.live.unsubscribe("overlay")
        gaugesShown.value = false
    }

    override fun onDestroy() {
        hideGauges()
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, ObdService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle("Connected to vehicle")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", disconnect)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 42
        const val ACTION_DISCONNECT = "com.openobd.DISCONNECT"
        const val ACTION_SHOW_GAUGES = "com.openobd.SHOW_GAUGES"
        const val ACTION_HIDE_GAUGES = "com.openobd.HIDE_GAUGES"
        /** Whether the floating window is on screen, for the button that toggles it. */
        val gaugesShown = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
}
