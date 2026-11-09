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
import com.openobd.ui.tr
import kotlinx.coroutines.launch

/** Keeps the phone bridge alive with the screen off, and shows whether the car screen is connected. */
class BridgeService : LifecycleService() {
    private val bridge get() = (application as OpenObdApp).container.bridge

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Car screen bridge"), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { bridge.stop(); stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(tr("Starting…")),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        lifecycleScope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            bridge.state.collect { s ->
                if (!bridge.running && !s.running) { stopSelf(); return@collect }
                val text = when {
                    s.adapter == null -> s.error?.let { tr(it) } ?: tr("Connecting to the adapter…")
                    s.client != null -> tr("Car screen connected (%1\$s)", s.client)
                    else -> tr("Waiting for the car screen")
                }
                nm.notify(NOTIFICATION_ID, build(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(tr("Lending the adapter to the car screen"))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, tr("Stop"), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 43
        const val ACTION_STOP = "com.openobd.STOP_BRIDGE"
        val texts = listOf("Car screen bridge", "Starting…", "Connecting to the adapter…", "Car screen connected (%1\$s)", "Waiting for the car screen",
            "Lending the adapter to the car screen", "Stop")
    }
}
