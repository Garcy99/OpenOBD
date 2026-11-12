package com.openobd.core

import com.openobd.core.battery.BatteryHealth
import com.openobd.core.garage.TripRecord
import com.openobd.core.health.HealthSample
import com.openobd.core.voice.VoiceWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAndAlertsTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test fun `resting voltage tells the charge`() {
        assertEquals(100, BatteryHealth.chargePct(12.8))
        assertEquals(90, BatteryHealth.chargePct(12.5))
        assertEquals(50, BatteryHealth.chargePct(12.06))
        assertEquals(0, BatteryHealth.chargePct(10.0))
        assertTrue(BatteryHealth.chargePct(12.3) in 66..70)
        assertEquals(BatteryHealth.Level.LOW, BatteryHealth.levelOf(BatteryHealth.chargePct(11.95)))
    }

    @Test fun `a battery that drains while parked and an alternator that charges little are told`() {
        // Each reading taken on connecting, after the car stood 1, 3 and 6 days since the trip before.
        val trips = listOf(0L, 10L, 20L).map { d -> TripRecord(id = "t$d", vehicleId = "c", start = now - (40 - d) * day, end = now - (40 - d) * day + 3600_000, distanceKm = 20.0) }
        val samples = listOf(1L to 12.55, 3L to 12.35, 6L to 12.15).mapIndexed { i, (parked, v) ->
            HealthSample("c", trips[i].end + parked * day, restingV = v, chargingV = 13.0)
        }
        val a = BatteryHealth.assess(samples, trips, now)!!
        assertEquals(12.15, a.latest.restV, 1e-9)
        assertEquals(6.0 * 24, a.latest.parkedHours!!, 0.1)
        assertEquals(BatteryHealth.Level.FAIR, a.level)
        assertEquals(0.079, a.dropPerDay!!, 0.005)
        assertTrue(a.drainsFast)
        assertTrue(a.alternatorWeak)
        // Healthy: a little drop, good charging.
        val ok = listOf(1L to 12.62, 3L to 12.57, 6L to 12.5).mapIndexed { i, (parked, v) -> HealthSample("c", trips[i].end + parked * day, restingV = v, chargingV = 14.2) }
        val b = BatteryHealth.assess(ok, trips, now)!!
        assertEquals(BatteryHealth.Level.GOOD, b.level)
        assertFalse(b.drainsFast)
        assertFalse(b.alternatorWeak)
        assertNull(BatteryHealth.assess(emptyList(), trips, now))
    }

    @Test fun `the low battery warning says how long the car stood`() {
        assertTrue(BatteryHealth.lowWarning(11.9, 80.0, 40).let { it.text.contains("days") && it.args == listOf("11.9", "3", "40") })
        assertTrue(BatteryHealth.lowWarning(11.9, 10.0, 40).text.contains("hours"))
        assertFalse(BatteryHealth.lowWarning(11.9, null, 40).text.contains("parked"))
    }

    @Test fun `a cold engine revved hard is warned once, with a notification`() {
        val w = VoiceWatch(diesel = true)
        var t = 0L
        val said = (0..20).flatMap { w.onCycle(mapOf("0105" to 35.0, "010C" to 3400.0), t).also { t += 500 } }
        assertEquals(1, said.size)
        assertTrue(said.single().text.startsWith("The engine is still cold") && said.single().notify)
        // Warm: nothing.
        assertTrue(VoiceWatch(diesel = true).onCycle(mapOf("0105" to 85.0, "010C" to 3400.0), 0).isEmpty())
    }

    @Test fun `low fuel is warned from the range or the tank`() {
        val a = VoiceWatch().onCycle(mapOf("CALC_RANGE" to 45.0, "CALC_TANK" to 9.0), 0)
        assertEquals(listOf("45", "kilometres"), a.single().args)
        assertTrue(a.single().notify)
        val b = VoiceWatch().onCycle(mapOf("CALC_TANK" to 8.0, "CALC_TANK_L" to 4.4), 0)
        assertEquals(listOf("4", "litres"), b.single().args)
        assertTrue(VoiceWatch().onCycle(mapOf("CALC_RANGE" to 300.0, "CALC_TANK" to 50.0), 0).isEmpty())
    }
}
