package com.openobd.session

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Engine off, then the adapter pulled out a moment later: the trip must be saved at once, whatever the settings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EngineOffThenUnplugTest {
    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    private fun drive(autoConnect: Boolean, pauseBeforeUnplugMs: Long) {
        val c = ApplicationProvider.getApplicationContext<OpenObdApp>().container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = autoConnect, autoTrips = false, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO && c.settings.settings.value.autoConnect == autoConnect }
        val tripsBefore = c.garage.data.value.trips.size
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            waitFor(60_000) { c.session.trip.stats.value.let { it.distanceKm >= 0.2 || it.movingS >= 60 } }
            c.session.switchOffDemoCarForTest()
            Thread.sleep(pauseBeforeUnplugMs)
            c.session.unplugDemoForTest()
            val off = System.currentTimeMillis()
            waitFor(20_000) { c.garage.data.value.trips.size > tripsBefore }
            assertTrue("saved after ${System.currentTimeMillis() - off} ms", System.currentTimeMillis() - off < 20_000)
        } finally {
            c.session.demoCarOff = false
            c.session.demoUnplugged = false
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
            runBlocking { c.settings.update { it.copy(autoConnect = false) } }
        }
    }

    @Test fun withoutReconnectingUnpluggedAtOnce() = drive(autoConnect = false, pauseBeforeUnplugMs = 1500)
    @Test fun withoutReconnectingUnpluggedLater() = drive(autoConnect = false, pauseBeforeUnplugMs = 8000)
    @Test fun reconnectingUnpluggedAtOnce() = drive(autoConnect = true, pauseBeforeUnplugMs = 1500)
    @Test fun reconnectingUnpluggedLater() = drive(autoConnect = true, pauseBeforeUnplugMs = 8000)
}
