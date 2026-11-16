package com.openobd.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.openobd.OpenObdApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts an automatic trip when the phone joins the car's own Bluetooth (its radio or hands-free), and ends it when
 * it leaves. Android wakes the app for these events even when it is closed.
 */
class AutoTripReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val address = device?.address ?: return
        val joined = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        // Null when called directly (tests).
        val pending: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { handle(context, address, joined, "Bluetooth") } finally { pending?.finish() }
        }
    }

    companion object {
        /**
         * The car's Bluetooth joined ([joined]) or left the phone: told by Android's broadcast or, for a car linked
         * as a companion device, by Android waking [CarPresenceService] (which also works where broadcasts to closed
         * apps are held back).
         */
        suspend fun handle(context: Context, address: String, joined: Boolean, via: String) {
            val app = context.applicationContext as OpenObdApp
            val s = app.container.settings.readOnce()
            // While this phone lends its adapter to the car screen, the screen records the trip: the phone
            // must not reach for the adapter too (two links to it make both drop).
            if (!s.autoTrips || !s.autoTripDevice.equals(address, ignoreCase = true) || app.container.bridge.running) return
            val log = app.container.diagnostics.log
            if (joined) {
                // Already connected (opened by hand, or told by both the broadcast and the companion service): the drive
                // is now an automatic trip, ended by the engine staying off.
                if (app.container.session.state.value is com.openobd.session.ConnectionState.Connected) { app.container.session.autoTrip = true; return }
                log.event("Automatic trip ($via): the car's Bluetooth joined, starting the recording")
                if (startForTest?.invoke(context, ObdService.ACTION_AUTO_START) ?: start(context, ObdService.ACTION_AUTO_START)) {
                    noteDetection(context, DETECTED_STARTED)
                } else {
                    // Android did not allow it from the background: one tap on a notification does.
                    log.error("Automatic trip: Android did not let the recording start from the background (battery optimisation?)")
                    noteDetection(context, DETECTED_BLOCKED)
                    offerStart(context)
                }
            }
            // The drive is over: disconnecting saves the trip, and the service stops with the connection.
            else {
                clearOffer(context)
                if (app.container.session.state.value !is com.openobd.session.ConnectionState.Disconnected) app.container.session.disconnect()
            }
        }

        const val DETECTED_STARTED = "started"
        const val DETECTED_BLOCKED = "blocked"

        /** When the car was last detected and what came of it (shown on the setup screen, to tell what goes wrong). */
        fun noteDetection(context: Context, outcome: String) {
            context.getSharedPreferences("auto_trip", Context.MODE_PRIVATE).edit().putLong("at", System.currentTimeMillis()).putString("outcome", outcome).apply()
        }

        fun lastDetection(context: Context): Pair<Long, String>? = context.getSharedPreferences("auto_trip", Context.MODE_PRIVATE).let { p ->
            p.getLong("at", 0).takeIf { it > 0 }?.let { it to p.getString("outcome", "").orEmpty() }
        }

        /** What the receiver does for the car's device, also used to try it from the setup screen. False when Android refused. */
        fun start(context: Context, action: String): Boolean {
            val i = Intent(context, ObdService::class.java).setAction(action)
            return runCatching {
                if (action == ObdService.ACTION_AUTO_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
            }.isSuccess
        }

        /** For tests: stands in for starting the service (false = Android refused, as from the background on 12+). */
        @Volatile internal var startForTest: ((Context, String) -> Boolean)? = null

        internal const val OFFER_ID = 79

        /**
         * The car was detected but the recording could not start on its own (Android 12+ without the battery exemption,
         * or the phone's own restrictions): a notification starts it with one tap (always allowed).
         */
        fun offerStart(context: Context) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                android.app.NotificationChannel("trips", com.openobd.ui.tr("Trip saved"), android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val i = Intent(context, ObdService::class.java).setAction(ObdService.ACTION_AUTO_START)
            val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val tap = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.PendingIntent.getForegroundService(context, OFFER_ID, i, flags)
                else android.app.PendingIntent.getService(context, OFFER_ID, i, flags)
            runCatching {
                nm.notify(OFFER_ID, androidx.core.app.NotificationCompat.Builder(context, "trips").setSmallIcon(com.openobd.R.drawable.ic_stat_car)
                    .setContentTitle(com.openobd.ui.tr("Car detected")).setContentText(com.openobd.ui.tr(OFFER_TEXT))
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(com.openobd.ui.tr(OFFER_TEXT)))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setContentIntent(tap).setAutoCancel(true).build())
            }
        }

        /** Takes the offer away once the trip is recording (or the car is left). */
        fun clearOffer(context: Context) { context.getSystemService(android.app.NotificationManager::class.java)?.cancel(OFFER_ID) }

        const val OFFER_TEXT = "Tap to start recording the trip. To have it start by itself, let OpenOBD run in the background (Automatic trips → Not stopped by battery saving)."
        val texts = listOf("Car detected", OFFER_TEXT)
    }
}
