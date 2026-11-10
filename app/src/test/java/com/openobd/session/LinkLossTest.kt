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
        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected }
        // Settings live on between tests: leave reconnecting off for the others.
        runBlocking { c.settings.update { it.copy(autoConnect = false) } }
    }
}
