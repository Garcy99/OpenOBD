package com.openobd.core

import com.openobd.session.EngineOffDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOffTest {
    @Test fun `ends the trip after the engine stays off, waits longer when it never started`() {
        val d = EngineOffDetector(offMs = 180_000, neverStartedMs = 600_000)
        d.reset(1)
        // Car radio joined, engine not started yet: wait up to ten minutes.
        assertFalse(d.engineOff(300_000))
        assertTrue(d.engineOff(600_002))
        d.reset(1)
        d.onCycle(mapOf("010C" to 820.0, "010D" to 0.0), setOf("010C", "010D"), 10_000)
        d.onCycle(mapOf("010C" to 1900.0, "010D" to 60.0), setOf("010C", "010D"), 500_000)
        // Stopped at a light with start-stop: the speed and rpm are 0, but only for a while.
        d.onCycle(mapOf("010C" to 0.0, "010D" to 0.0), setOf("010C", "010D"), 560_000)
        assertFalse(d.engineOff(600_000))
        // Old values that were not read again do not keep the trip alive.
        d.onCycle(mapOf("010C" to 1900.0), emptySet(), 650_000)
        assertTrue(d.engineOff(500_000 + 180_001))
    }
}
