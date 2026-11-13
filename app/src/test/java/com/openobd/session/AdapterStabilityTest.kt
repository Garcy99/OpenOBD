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

/** An adapter that gets stuck for a moment is brought back without dropping the link; values a car lacks are not asked again. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AdapterStabilityTest {
    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    @Test fun aStuckAdapterIsRecoveredWithoutReconnecting() {
        val c = ApplicationProvider.getApplicationContext<OpenObdApp>().container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = false, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            waitFor { c.session.live.readings.value.containsKey("010C") }
            val tripStarted = c.session.trip.stats.value.startedAt
            // Stuck for a few requests: more than it takes to give the link up for lost before.
            val before = c.session.live.softRecoveries.get()
            // After the code scan done on connecting, so only the live readings are going on.
            waitFor { c.session.dtcScan.value !is Op.Loading && c.session.live.tick.value > 10 }
            c.session.stickDemoAdapterForTest(40_000)
            waitFor(120_000) { c.session.live.softRecoveries.get() > before }
            // Same connection all along, and the values come back.
            assertTrue(c.session.state.value is ConnectionState.Connected && !c.session.reconnecting)
            assertEquals(tripStarted, c.session.trip.stats.value.startedAt)
            val tick = c.session.live.tick.value
            waitFor { c.session.live.tick.value > tick + 5 }
        } finally {
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
        }
    }

    @Test fun silentMakerValuesAreRememberedForAWeek() {
        val ctx = ApplicationProvider.getApplicationContext<OpenObdApp>()
        val s = SilentMakerValues(ctx)
        val now = 1_000_000_000_000L
        s.add("car", "X_gm_dpf_soot", now)
        s.add("car", "X_gm_tp_fl", now - 8 * 86_400_000L)
        assertEquals(setOf("X_gm_dpf_soot"), s.of("car", now + 86_400_000L))
        assertTrue(s.of("car", now + 8 * 86_400_000L).isEmpty())
        assertTrue(s.of("other", now).isEmpty())
    }
}
