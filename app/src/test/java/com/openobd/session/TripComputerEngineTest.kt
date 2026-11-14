package com.openobd.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripComputerEngineTest {
    @Test fun `time counts only with the engine running, and the stop is remembered`() {
        val t = TripComputer()
        var now = 1_000L
        fun cycle(rpm: Double, speed: Double) { t.onCycle(mapOf("010C" to rpm, "010D" to speed), setOf("010C", "010D"), now); now += 1000 }
        // Ignition on, engine not started yet.
        repeat(5) { cycle(0.0, 0.0) }
        assertNull(t.firstRunningAt)
        assertEquals(0.0, t.stats.value.durationS, 1e-9)
        repeat(10) { cycle(1500.0, 40.0) }
        assertEquals(6_000L, t.firstRunningAt)
        val lastRunning = now - 1000
        repeat(20) { cycle(0.0, 0.0) } // engine stopped, ignition on
        assertEquals(lastRunning, t.engineStoppedAt.value)
        assertEquals(10.0, t.stats.value.durationS, 1e-9) // only the running seconds
        cycle(800.0, 0.0) // started again
        assertNull(t.engineStoppedAt.value)
    }
}
