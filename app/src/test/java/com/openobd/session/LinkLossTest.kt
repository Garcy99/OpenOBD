package com.openobd.session

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A link that drops for a moment (Bluetooth or the phone bridge's Wi-Fi) no longer cuts the drive in pieces. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LinkLossTest {
    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    @Test fun theTripGoesOnAndTheGaugesComeBackQuickly() {
        val c = ApplicationProvider.getApplicationContext<OpenObdApp>().container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = true, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        val tripsBefore = c.garage.data.value.trips.size
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            waitFor { c.session.trip.stats.value.distanceKm > 0.0 || c.session.trip.stats.value.movingS > 0 }
            val before = c.session.trip.stats.value

            c.session.dropLinkForTest()
            waitFor { c.session.state.value !is ConnectionState.Connected }
            assertTrue(c.session.reconnecting)
            // Back by itself within seconds, without reading the car's sensors and VIN all over again.
            val dropped = System.currentTimeMillis()
            waitFor(15_000) { c.session.state.value is ConnectionState.Connected }
            assertTrue("back in ${System.currentTimeMillis() - dropped} ms", System.currentTimeMillis() - dropped < 10_000)
            assertTrue(!c.session.reconnecting)
            waitFor { c.session.live.readings.value.containsKey("010C") }
            // The same trip: nothing saved in between, and its figures go on from where they were.
            assertEquals(tripsBefore, c.garage.data.value.trips.size)
            assertTrue(c.session.trip.stats.value.movingS >= before.movingS)
            assertTrue(c.session.trip.stats.value.distanceKm >= before.distanceKm)
        } finally {
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
            // Settings live on between tests: leave reconnecting off for the others.
            runBlocking { c.settings.update { it.copy(autoConnect = false) } }
        }
    }

    /**
     * The engine switched off: most adapters stay powered and keep answering, but the car does not. The trip must be
     * saved at once (it used to wait for the car forever, so no trip was ever recorded).
     */
    @Test fun theEngineOffSavesTheTrip() {
        val c = ApplicationProvider.getApplicationContext<OpenObdApp>().container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = true, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        val tripsBefore = c.garage.data.value.trips.size
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            // Long enough to be worth saving.
            waitFor(60_000) { c.session.trip.stats.value.let { it.distanceKm >= 0.2 || it.movingS >= 60 } }

            c.session.switchOffDemoCarForTest()
            c.session.dropLinkForTest()
            waitFor { c.session.state.value !is ConnectionState.Connected }
            val off = System.currentTimeMillis()
            waitFor(30_000) { c.garage.data.value.trips.size > tripsBefore }
            assertTrue("saved after ${System.currentTimeMillis() - off} ms", System.currentTimeMillis() - off < 30_000)
            // And it stops trying: nothing keeps the trip (or the app) waiting.
            waitFor(10_000) { !c.session.reconnecting && !c.session.tripPending }
            assertTrue(c.session.state.value !is ConnectionState.Connected)
        } finally {
            c.session.demoCarOff = false
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
            runBlocking { c.settings.update { it.copy(autoConnect = false) } }
        }
    }

    /**
     * Bluetooth getting choppy before it drops (requests unanswered, then the link gone) is not the engine being switched
     * off: the trip goes on and the link comes back by itself. (1.23.3 to 1.26.0 took the timeouts for a silent car,
     * ended the trip and tried to reconnect only once.)
     */
    @Test fun aChoppyLinkIsNotTheEngineOff() {
        val c = ApplicationProvider.getApplicationContext<OpenObdApp>().container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = true, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        val tripsBefore = c.garage.data.value.trips.size
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            waitFor(60_000) { c.session.trip.stats.value.let { it.distanceKm >= 0.2 || it.movingS >= 60 } }
            waitFor { c.session.dtcScan.value !is Op.Loading }
            c.session.stickDemoAdapterForTest(7_000)
            Thread.sleep(6_000)
            assertTrue("timeouts are not a silent car", c.session.live.carSilentSince == null)
            c.session.dropLinkForTest()
            waitFor { c.session.state.value !is ConnectionState.Connected }
            waitFor(30_000) { c.session.state.value is ConnectionState.Connected }
            // The same trip: nothing saved in between.
            assertEquals(tripsBefore, c.garage.data.value.trips.size)
            assertTrue(c.session.tripInProgress)
        } finally {
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
            runBlocking { c.settings.update { it.copy(autoConnect = false) } }
        }
    }
}
