package com.openobd.service

import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** When Android does not let the recording start from the background, the driver gets a notification to start it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoTripOfferTest {
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    @Test fun aRefusedStartBecomesANotificationToTap() {
        val app = ApplicationProvider.getApplicationContext<OpenObdApp>()
        val c = app.container
        val radio = app.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("00:11:22:33:44:55")
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.BLUETOOTH, deviceAddress = "66:77:88:99:AA:BB", autoTrips = true,
            autoTripDevice = radio.address, autoTripDeviceName = "Opel Astra", language = "en") } }
        val nm = app.getSystemService(NotificationManager::class.java)
        fun offer() = shadowOf(nm).allNotifications.firstOrNull { shadowOf(it).contentTitle == "Car detected" }
        try {
            AutoTripReceiver.startForTest = { _, _ -> false }
            AutoTripReceiver().onReceive(app, Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, radio))
            waitFor { offer() != null }
            // Tapping it starts the recording service for an automatic trip.
            assertEquals(ObdService.ACTION_AUTO_START, shadowOf(offer()!!.contentIntent).savedIntent.action)
            assertNotNull(c.diagnostics.log.entries().firstOrNull { it.text.contains("did not let the recording start") })
            // Leaving the car takes the offer away.
            AutoTripReceiver().onReceive(app, Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, radio))
            waitFor { offer() == null }
        } finally {
            AutoTripReceiver.startForTest = null
            runBlocking { c.settings.update { it.copy(autoTrips = false, autoTripDevice = null, autoTripDeviceName = null, connectionType = ConnectionType.DEMO) } }
        }
    }

    /** A car linked as a companion device: Android waking the app when the phone joins it starts the recording. */
    @Test fun theLinkedCarWakesTheRecording() {
        val app = ApplicationProvider.getApplicationContext<OpenObdApp>()
        val c = app.container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.BLUETOOTH, deviceAddress = "66:77:88:99:AA:BB", autoTrips = true,
            autoTripDevice = "00:11:22:33:44:55", autoTripDeviceName = "Opel Astra", language = "en") } }
        val started = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            AutoTripReceiver.startForTest = { _, action -> started += action; true }
            val service = org.robolectric.Robolectric.setupService(CarPresenceService::class.java)
            // Another device: nothing.
            @Suppress("DEPRECATION") service.onDeviceAppeared("AA:AA:AA:AA:AA:AA")
            Thread.sleep(500)
            assertEquals(0, started.size)
            @Suppress("DEPRECATION") service.onDeviceAppeared("00:11:22:33:44:55")
            waitFor { started.isNotEmpty() }
            assertEquals(ObdService.ACTION_AUTO_START, started.single())
            assertEquals(AutoTripReceiver.DETECTED_STARTED, AutoTripReceiver.lastDetection(app)!!.second)
        } finally {
            AutoTripReceiver.startForTest = null
            runBlocking { c.settings.update { it.copy(autoTrips = false, autoTripDevice = null, autoTripDeviceName = null, connectionType = ConnectionType.DEMO) } }
        }
    }
}
