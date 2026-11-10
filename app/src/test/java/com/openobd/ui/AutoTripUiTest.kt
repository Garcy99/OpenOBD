package com.openobd.ui

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import com.openobd.service.AutoTripReceiver
import com.openobd.service.ObdService
import com.openobd.session.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Automatic trips: set up from Home, then the car's Bluetooth joining starts the trip and leaving it saves it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AutoTripUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val c get() = (rule.activity.application as OpenObdApp).container

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        c.session.disconnect()
        runBlocking { c.settings.update { it.copy(autoTrips = false, autoTripDevice = null, autoTripDeviceName = null, fuelPrices = true) } }
    }

    @Test
    fun carBluetoothStartsAndEndsTheTrip() {
        val app = rule.activity.application
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        val radio = adapter.getRemoteDevice("00:11:22:33:44:55")
        val obd = adapter.getRemoteDevice("66:77:88:99:AA:BB")
        shadowOf(radio).setName("Opel Astra"); shadowOf(obd).setName("OBDII")
        shadowOf(adapter).setBondedDevices(setOf(radio, obd))
        runBlocking {
            c.settings.update { it.copy(connectionType = ConnectionType.BLUETOOTH, deviceAddress = obd.address, deviceName = "OBDII", language = "en",
                onboarded = true, tourSeen = true, tripGps = false, fuelPrices = false, autoConnect = false) }
        }
        waitFor { c.garage.loaded && I18n.language == "en" }
        // Home offers it; the screen sets it up.
        rule.onNodeWithTag("autoTripsCard").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("autoTripsSwitch").performClick()
        rule.onNodeWithTag("carBt_00:11:22:33:44:55").performScrollTo().performClick()
        waitFor { c.settings.settings.value.autoTrips && c.settings.settings.value.autoTripDevice == radio.address }
        assertEquals("Opel Astra", c.settings.settings.value.autoTripDeviceName)

        // The demo car stands in for the adapter from here on.
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO) } }
        // Another device joining does nothing.
        AutoTripReceiver().onReceive(app, Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, obd))
        Thread.sleep(500)
        assertEquals(null, shadowOf(app).peekNextStartedService())
        // The car's radio joining starts the service for an automatic trip.
        AutoTripReceiver().onReceive(app, Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, radio))
        waitFor { shadowOf(app).peekNextStartedService() != null }
        val started = shadowOf(app).nextStartedService
        assertEquals(ObdService.ACTION_AUTO_START, started.action)
        val service = Robolectric.buildService(ObdService::class.java, started).create().startCommand(0, 1).get()
        waitFor(40_000) { c.session.state.value is ConnectionState.Connected && c.session.autoTrip }
        // Leaving the car ends the trip.
        AutoTripReceiver().onReceive(app, Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, radio))
        waitFor { c.session.state.value is ConnectionState.Disconnected }
        assertTrue(!c.session.autoTrip)
        service.onDestroy()
    }
}
