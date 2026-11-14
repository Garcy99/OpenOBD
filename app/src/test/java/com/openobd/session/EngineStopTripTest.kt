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

/** The trip ends when the engine stops, not when the ignition goes off; a start/stop pause does not cut it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EngineStopTripTest {
    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    private fun drive(block: (OpenObdApp) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<OpenObdApp>()
        val c = app.container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = false, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        c.session.engineOffEndMs = 6_000
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            waitFor { c.session.trip.stats.value.let { it.distanceKm >= 0.2 || it.movingS >= 60 } }
            block(app)
        } finally {
            c.session.engineOffEndMs = ENGINE_OFF_END_MS
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
        }
    }

    @Test fun theTripEndsWhenTheEngineStopsAndANewOneBeginsWhenItStarts() = drive { app ->
        val c = app.container
        val before = c.garage.data.value.trips.size
        c.session.demoEngineForTest(false)
        waitFor { c.session.trip.engineStoppedAt.value != null }
        val stoppedAt = c.session.trip.engineStoppedAt.value!!
        // The ignition stays on: after a while the trip is saved by itself, ending when the engine stopped.
        waitFor(30_000) { c.garage.data.value.trips.size > before }
        val t = c.garage.data.value.trips.maxByOrNull { it.end }!!
        assertEquals(stoppedAt.toDouble(), t.end.toDouble(), 2_000.0)
        assertTrue(!c.session.tripInProgress)
        assertTrue(c.session.state.value is ConnectionState.Connected)
        // The engine starts again, still connected: a new trip.
        c.session.demoEngineForTest(true)
        waitFor { c.session.tripInProgress }
        waitFor { c.session.trip.stats.value.durationS > 1 }
    }

    @Test fun theDriverCanEndItAtOnce() = drive { app ->
        val c = app.container
        c.session.engineOffEndMs = 10 * 60_000L
        val before = c.garage.data.value.trips.size
        c.session.demoEngineForTest(false)
        waitFor { c.session.trip.engineStoppedAt.value != null }
        val stoppedAt = c.session.trip.engineStoppedAt.value!!
        Thread.sleep(3_000)
        c.session.endTripNow()
        waitFor(10_000) { c.garage.data.value.trips.size > before }
        assertEquals(stoppedAt.toDouble(), c.garage.data.value.trips.maxByOrNull { it.end }!!.end.toDouble(), 2_000.0)
    }

    @Test fun aStartStopPauseDoesNotCutTheTrip() = drive { app ->
        val c = app.container
        val before = c.garage.data.value.trips.size
        val start = c.session.trip.firstRunningAt
        c.session.demoEngineForTest(false)
        waitFor { c.session.trip.engineStoppedAt.value != null }
        Thread.sleep(2_000)
        c.session.demoEngineForTest(true)
        waitFor { c.session.trip.engineStoppedAt.value == null }
        Thread.sleep(6_000)
        assertEquals(before, c.garage.data.value.trips.size)
        assertTrue(c.session.tripInProgress)
        assertEquals(start, c.session.trip.firstRunningAt)
    }
}
