package com.openobd.core

import com.openobd.core.health.HealthLevel
import com.openobd.core.health.HealthMetric
import com.openobd.core.health.HealthRecorder
import com.openobd.core.health.HealthSample
import com.openobd.core.health.HealthTrends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val day = 86_400_000L

    @Test
    fun recorderMeasuresWarmupChargingAndIdleTrims() {
        val t0 = 1_000_000L
        val r = HealthRecorder("car", restingV = 12.55, start = t0)
        // Cold start, warming up 1 °C every 10 s, then idling warm.
        for (i in 0..120) {
            val coolant = (20 + i * 1.0).coerceAtMost(88.0)
            r.onCycle(mapOf("010C" to 820.0, "0105" to coolant, "010D" to 0.0, "0142" to 14.2, "0107" to 4.0, "0106" to -1.0), t0 + i * 10_000L)
        }
        val s = r.result()!!
        assertEquals(12.55, s.restingV!!, 0.001)
        assertEquals(14.2, s.chargingV!!, 0.001)
        assertEquals(4.0, s.ltftIdle!!, 0.001)
        assertEquals(10.0, s.warmupMin!!, 0.01) // 20 -> 80 °C in 60 cycles of 10 s
        assertEquals(820.0, s.idleRpm!!, 0.001)
        assertEquals(88.0, s.maxCoolant!!, 0.001)
    }

    @Test
    fun veryShortSessionIsNotRecorded() {
        val r = HealthRecorder("car", restingV = null, start = 0)
        r.onCycle(mapOf("010C" to 0.0), 30_000)
        assertNull(r.result())
    }

    private fun sample(i: Int, restingV: Double = 12.6, charging: Double = 14.2, ltft: Double = 2.0, warmup: Double = 9.0, idle: Double = 820.0, mis: Map<Int, Double> = emptyMap()) =
        HealthSample("car", i * day, restingV, charging, ltft, 0.0, warmup, 90.0, idle, mis, 30.0)

    @Test
    fun healthyCarHasNoWarnings() {
        val r = HealthTrends.analyze((1..10).map { sample(it) })
        assertEquals(HealthLevel.GOOD, r.overall)
        assertEquals(5, r.series.size) // no misfire data
    }

    @Test
    fun learningPhase() {
        val r = HealthTrends.analyze(listOf(sample(1)))
        assertTrue(r.findings.any { it.text.startsWith("Drive a few more times") && it.args == listOf("1", "3") })
    }

    @Test
    fun ageingBatteryAndWeakCharging() {
        val s = (1..8).map { sample(it, restingV = 12.7) } + (9..11).map { sample(it, restingV = 12.35, charging = 13.0) }
        val r = HealthTrends.analyze(s)
        assertEquals(HealthLevel.WATCH, r.findings.first { it.metric == HealthMetric.RESTING_V }.level)
        assertEquals(HealthLevel.ACT, r.findings.first { it.metric == HealthMetric.CHARGING_V }.level)
        assertEquals(HealthLevel.ACT, HealthTrends.analyze(s + sample(12, restingV = 11.9) + sample(13, restingV = 11.9) + sample(14, restingV = 11.9))
            .findings.first { it.metric == HealthMetric.RESTING_V }.level)
    }

    @Test
    fun driftingTrimsThermostatIdleAndMisfires() {
        val base = (1..8).map { sample(it, mis = mapOf(1 to 0.0, 3 to 2.0)) }
        val bad = listOf(sample(9, ltft = 9.0, warmup = 18.0, idle = 980.0, mis = mapOf(1 to 4.0, 3 to 2.0)),
            sample(10, ltft = 10.0, warmup = 19.0, idle = 990.0, mis = mapOf(1 to 9.0, 3 to 2.0)),
            sample(11, ltft = 10.5, warmup = 20.0, idle = 1000.0, mis = mapOf(1 to 15.0, 3 to 2.0)))
        val r = HealthTrends.analyze(base + bad)
        val by = r.findings.associateBy { it.metric }
        assertEquals(HealthLevel.WATCH, by.getValue(HealthMetric.LTFT).level)
        assertEquals(HealthLevel.WATCH, by.getValue(HealthMetric.WARMUP).level)
        assertEquals(HealthLevel.WATCH, by.getValue(HealthMetric.IDLE_RPM).level)
        val misfire = r.findings.single { it.metric == HealthMetric.MISFIRES }
        assertEquals("1", misfire.args[0])
        r.findings.forEach { assertTrue(it.text, it.text in HealthTrends.texts) }
    }
}
