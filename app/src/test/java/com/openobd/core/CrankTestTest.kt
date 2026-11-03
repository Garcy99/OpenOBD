package com.openobd.core

import com.openobd.core.battery.BatteryLevel
import com.openobd.core.battery.CrankAnalysis
import com.openobd.core.battery.VoltSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrankTestTest {
    /** Samples every 60 ms: [restMs] at rest, [crankMs] at the crank voltage, then running at [charge]. */
    private fun start(rest: Double, crank: Double, crankMs: Long, charge: Double, restMs: Long = 2000, runMs: Long = 10_000): List<VoltSample> {
        val out = mutableListOf<VoltSample>()
        var t = 0L
        while (t < restMs) { out += VoltSample(t, rest); t += 60 }
        out += VoltSample(t, crank - 0.4); t += 60 // inrush
        val end = t + crankMs
        while (t < end) { out += VoltSample(t, crank); t += 60 }
        val runEnd = t + runMs
        while (t < runEnd) { out += VoltSample(t, charge); t += 60 }
        return out
    }

    @Test
    fun healthyBattery() {
        val r = CrankAnalysis.analyze(12.6, start(12.6, 10.4, 900, 14.3))
        assertEquals(BatteryLevel.GOOD, r.overall)
        assertEquals(10.4, r.minV!!, 0.01) // the single inrush reading is ignored
        assertTrue(r.crankMs!! in 900..1100)
        assertEquals(14.3, r.chargingV!!, 0.01)
    }

    @Test
    fun weakAndWornBatteries() {
        assertEquals(BatteryLevel.WATCH, CrankAnalysis.analyze(12.5, start(12.5, 9.8, 1200, 14.2)).overall)
        val worn = CrankAnalysis.analyze(12.3, start(12.3, 9.0, 1500, 14.1))
        assertEquals(BatteryLevel.BAD, worn.overall)
        assertTrue(worn.findings.any { it.text.contains("worn out") })
    }

    @Test
    fun smartAlternatorIsOnlyAHint() {
        // Opel-style regulated charging around 12.9 V right after the start
        val r = CrankAnalysis.analyze(12.5, start(12.5, 10.5, 800, 12.9))
        assertEquals(BatteryLevel.WATCH, r.overall)
        assertTrue(r.findings.any { it.text.contains("lower it on purpose") })
        // A dead alternator: the voltage never rises, but the engine computer reports engine speed
        val dead = start(12.5, 10.5, 800, 12.2)
        assertTrue(CrankAnalysis.analyze(12.5, dead).findings.none { it.text.contains("not charging") })
        assertTrue(CrankAnalysis.analyze(12.5, dead, rpmRunningAt = 3000).findings.any { it.text.contains("not charging") })
        assertTrue(CrankAnalysis.analyze(12.5, start(12.5, 10.5, 800, 15.4)).findings.any { it.text.contains("too high") })
    }

    @Test
    fun longStartAndDiesel() {
        val petrol = CrankAnalysis.analyze(12.6, start(12.6, 10.5, 3500, 14.2))
        assertTrue(petrol.findings.any { it.text.contains("starter turned") })
        assertTrue(CrankAnalysis.analyze(12.6, start(12.6, 10.5, 3500, 14.2), isDiesel = true).findings.none { it.text.contains("starter turned") })
    }

    @Test
    fun adapterBrownOutAndNoStart() {
        // Adapter stops answering at the moment of the start, comes back with the engine running
        val samples = start(12.4, 12.4, 0, 14.1).filter { it.t < 2000 || it.t > 3000 }
        val r = CrankAnalysis.analyze(12.4, samples, gaps = listOf(2000L, 2500L))
        assertTrue(r.adapterDropped)
        assertEquals(BatteryLevel.BAD, r.overall)
        val none = CrankAnalysis.analyze(12.6, List(100) { VoltSample(it * 60L, 12.5) })
        assertTrue(none.findings.last().text.startsWith("No engine start"))
        assertNull(none.minV)
    }

    @Test
    fun engineAlreadyRunning() {
        val r = CrankAnalysis.analyze(14.1, emptyList())
        assertTrue(r.findings.single().text.startsWith("The engine seems to be running"))
        assertEquals(BatteryLevel.UNKNOWN, r.overall)
    }

    @Test
    fun restingVoltage() {
        assertEquals(12.5, CrankAnalysis.restOf(listOf(12.4, 12.5, 12.6, 9.0, 12.5).mapIndexed { i, v -> VoltSample(i.toLong(), v) })!!, 0.001)
        assertEquals(BatteryLevel.BAD, CrankAnalysis.analyze(11.9, start(11.9, 10.2, 900, 14.2)).overall)
    }
}
