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
        val action = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> ObdService.ACTION_AUTO_START
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> ObdService.ACTION_AUTO_STOP
            else -> return
        }
        // Null when called directly (tests).
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext as OpenObdApp
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val s = app.container.settings.readOnce()
                // While this phone lends its adapter to the car screen, the screen records the trip: the phone
                // must not reach for the adapter too (two links to it make both drop).
                if (s.autoTrips && s.autoTripDevice.equals(address, ignoreCase = true) && !app.container.bridge.running) {
                    if (action == ObdService.ACTION_AUTO_START) start(context, action)
                    // The drive is over: disconnecting saves the trip, and the service stops with the connection.
                    else if (app.container.session.state.value !is com.openobd.session.ConnectionState.Disconnected) app.container.session.disconnect()
                }
            } finally { pending?.finish() }
        }
    }

    companion object {
        /** What the receiver does for the car's device, also used to try it from the setup screen. */
        fun start(context: Context, action: String) {
            val i = Intent(context, ObdService::class.java).setAction(action)
            runCatching {
                if (action == ObdService.ACTION_AUTO_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
            }
        }
    }
}
