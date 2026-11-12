package com.openobd.session

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app closed in the middle of a drive: the trip is not lost, it is saved as it was at its last checkpoint. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TripRecoveryTest {
    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    @Test fun aTripLeftByAClosedAppIsRecoveredOnce() {
        val app = ApplicationProvider.getApplicationContext<OpenObdApp>()
        val c = app.container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = false, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        val tripsBefore = c.garage.data.value.trips.size
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            waitFor(60_000) { c.session.trip.stats.value.let { it.distanceKm >= 0.2 || it.movingS >= 60 } }
            runBlocking { c.session.writeCheckpoint() }
            val km = c.session.trip.stats.value.distanceKm
            assertTrue(com.openobd.data.TripCheckpoints(app).exists)

            // The app dies: nothing saved.
            runBlocking { c.session.dieForTest() }
            assertEquals(tripsBefore, c.garage.data.value.trips.size)

            // Next start: the trip comes back, once.
            val recovered = runBlocking { c.session.recoverTrip() }
            assertNotNull(recovered)
            assertEquals(tripsBefore + 1, c.garage.data.value.trips.size)
            assertEquals(km, recovered!!.distanceKm, 0.05)
            assertEquals(c.garage.data.value.active!!.id, recovered.vehicleId)
            assertNull(runBlocking { c.session.recoverTrip() })
            assertTrue(!com.openobd.data.TripCheckpoints(app).exists)
        } finally {
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
        }
    }

    @Test fun aTripSavedNormallyLeavesNothingToRecover() {
        val app = ApplicationProvider.getApplicationContext<OpenObdApp>()
        val c = app.container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = false, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        val tripsBefore = c.garage.data.value.trips.size
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected }
        waitFor(60_000) { c.session.trip.stats.value.let { it.distanceKm >= 0.2 || it.movingS >= 60 } }
        runBlocking { c.session.writeCheckpoint() }
        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected && c.garage.data.value.trips.size == tripsBefore + 1 }
        waitFor { !com.openobd.data.TripCheckpoints(app).exists }
        assertNull(runBlocking { c.session.recoverTrip() })
        assertEquals(tripsBefore + 1, c.garage.data.value.trips.size)
    }
}
