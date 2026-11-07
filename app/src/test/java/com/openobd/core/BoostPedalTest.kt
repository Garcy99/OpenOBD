package com.openobd.core

import com.openobd.core.obd.DerivedSensors
import org.junit.Assert.assertEquals
import org.junit.Test

class BoostPedalTest {
    private val p = DerivedSensors.Params()
    private fun calc(v: Map<String, Double>) = DerivedSensors.compute(v, p)

    @Test
    fun boostInBarFromTheWidestRangeSensor() {
        // PID 0B clips at 255 kPa; the boost sensor (PID 70) does not
        assertEquals(1.32, calc(mapOf("0170_AS" to 230.0, "010B" to 255.0, "0133" to 98.0))["CALC_BOOST"]!!, 1e-9)
        assertEquals(0.5, calc(mapOf("0187_A" to 150.0, "0133" to 100.0))["CALC_BOOST"]!!, 1e-9)
        assertEquals(-0.6, calc(mapOf("010B" to 40.0, "0133" to 100.0))["CALC_BOOST"]!!, 1e-9) // vacuum at idle
        // Without (or with a nonsense) barometric reading, sea level is assumed
        assertEquals(0.987, calc(mapOf("010B" to 200.0, "0133" to 0.0))["CALC_BOOST"]!!, 1e-9)
        assertEquals(listOf("0170_AS", "0133"), DerivedSensors.requirements("CALC_BOOST", setOf(0x0B, 0x33, 0x70, 0x87)))
        assertEquals(listOf("010B"), DerivedSensors.requirements("CALC_BOOST", setOf(0x0B)))
    }

    @Test
    fun acceleratorPrefersThePedal() {
        assertEquals(42.0, calc(mapOf("015A" to 42.0, "0111" to 80.0))["CALC_PEDAL"]!!, 1e-9)
        // Pedal sensor D: ~15 % at rest, ~80 % floored
        assertEquals(0.0, calc(mapOf("0149" to 15.0))["CALC_PEDAL"]!!, 1e-9)
        assertEquals(100.0, calc(mapOf("0149" to 82.0))["CALC_PEDAL"]!!, 1e-9)
        assertEquals(12.0, calc(mapOf("0111" to 12.0))["CALC_PEDAL"]!!, 1e-9)
        assertEquals(listOf("015A"), DerivedSensors.requirements("CALC_PEDAL", setOf(0x11, 0x49, 0x5A)))
    }
}
