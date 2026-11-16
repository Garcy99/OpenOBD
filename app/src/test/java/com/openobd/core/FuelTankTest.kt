package com.openobd.core

import com.openobd.core.obd.DerivedSensors
import com.openobd.core.obd.FuelTankEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class FuelTankTest {
    @Test fun `a sloshing level becomes a steady figure that follows the fuel burnt`() {
        val tank = FuelTankEstimator(capacityL = 56.0, l100 = 6.0)
        var trueL = 30.0
        var t = 0L
        val rnd = java.util.Random(3)
        var last: Double? = null
        var maxStep = 0.0
        // 20 minutes at 90 km/h burning 6 L/h; the sensor sways ±12 % with curves and brakes, in 1 % steps.
        for (s in 0 until 1200) {
            trueL -= 6.0 / 3600
            val swayPct = 12 * sin(s / 7.0) + rnd.nextGaussian() * 3
            val sensorPct = Math.round(trueL / 56 * 100 + swayPct).toDouble()
            val e = tank.update(mapOf("012F" to sensorPct, "CALC_FUEL" to 6.0, "010D" to 90.0), t) ?: run { t += 1000; null }
            t += 1000
            if (e == null) continue
            last?.let { maxStep = maxOf(maxStep, abs(e.litres!! - it)) }
            last = e.litres
        }
        // Close to the truth, and it never jumps about like the sensor does.
        assertEquals(trueL, last!!, 0.8)
        assertTrue("steps of $maxStep L", maxStep < 0.2)
        val e = tank.update(mapOf("012F" to 50.0, "CALC_FUEL" to 6.0, "010D" to 90.0), t)!!
        assertEquals(e.litres!! / 6.0 * 100, e.rangeKm!!, 1e-6)
        assertEquals(e.litres!! / 56 * 100, e.pct!!, 1e-6)
    }

    @Test fun `a refuel while stopped starts it again`() {
        val tank = FuelTankEstimator(capacityL = 56.0)
        var t = 0L
        repeat(60) { tank.update(mapOf("012F" to 20.0, "CALC_FUEL" to 0.8, "010D" to 0.0), t); t += 1000 }
        val before = tank.litres!!
        assertEquals(11.2, before, 0.3)
        // Filling up: the level jumps to full; after the level settles, the estimate follows at once, and says how much went in.
        val refuels = (0 until 90).mapNotNull { tank.update(mapOf("012F" to 100.0, "010D" to 0.0), t).also { t += 1000 }?.refuel }
        assertEquals(56.0, tank.litres!!, 1.0)
        assertEquals(1, refuels.size)
        assertEquals(56.0 - before, refuels.single().litres!!, 2.0)
        assertTrue(refuels.single().full)
        // Half a tank more: not full.
        val half = FuelTankEstimator(capacityL = 56.0)
        repeat(60) { half.update(mapOf("012F" to 20.0, "010D" to 0.0), t); t += 1000 }
        val r = (0 until 90).mapNotNull { half.update(mapOf("012F" to 70.0, "010D" to 0.0), t).also { t += 1000 }?.refuel }.single()
        assertEquals(56.0 * 0.5, r.litres!!, 2.0)
        assertTrue(!r.full)
        // A short jump while driving (a hill) is not a refuel.
        val driving = FuelTankEstimator(capacityL = 56.0)
        repeat(60) { driving.update(mapOf("012F" to 20.0, "010D" to 80.0), t); t += 1000 }
        repeat(20) { driving.update(mapOf("012F" to 45.0, "010D" to 80.0), t); t += 1000 }
        assertTrue(driving.litres!! < 15)
    }

    @Test fun `without the level it follows the fuel burnt from the last known figure`() {
        val tank = FuelTankEstimator(capacityL = 56.0, startL = 40.0, l100 = 5.0)
        var t = 0L
        var e: FuelTankEstimator.Estimate? = null
        repeat(3600) { e = tank.update(mapOf("CALC_FUEL" to 5.0, "010D" to 100.0), t); t += 1000 }
        assertEquals(35.0, e!!.litres!!, 0.05)
        assertEquals(700.0, e!!.rangeKm!!, 1.0)
        assertTrue(!e!!.fromSensor)
        // And from the fuel log: a full tank, a top-up, the trips since.
        assertEquals(56.0 + 10 - 30, FuelTankEstimator.fromLog(56.0, 1L, 10.0, 30.0)!!, 1e-9)
        assertNull(FuelTankEstimator.fromLog(null, 1L, 0.0, 0.0))
    }

    @Test fun `without the capacity only the steadied level is shown`() {
        val tank = FuelTankEstimator(capacityL = null)
        var t = 0L
        var e: FuelTankEstimator.Estimate? = null
        repeat(120) { e = tank.update(mapOf("012F" to if (it % 2 == 0) 40.0 else 60.0, "010D" to 0.0), t); t += 1000 }
        assertEquals(50.0, e!!.pct!!, 10.0)
        assertNull(e!!.litres)
        // What the tank gauge asks for: the level and the fuel burnt, whichever the car has.
        assertEquals(listOf("012F", "015E", "010D"), DerivedSensors.requirements("CALC_TANK", setOf(0x2F, 0x5E, 0x0D)))
        assertEquals(listOf("015E"), DerivedSensors.requirements("CALC_RANGE", setOf(0x5E)))
        assertNull(DerivedSensors.requirements("CALC_TANK", setOf(0x0C)))
    }

    @Test fun `a refuel with the engine off is noticed when the car is read again`() {
        val tank = FuelTankEstimator(capacityL = 56.0)
        var t = 0L
        repeat(120) { tank.update(mapOf("012F" to 20.0, "CALC_FUEL" to 4.0, "010D" to 50.0), t); t += 1000 }
        val before = tank.litres!!
        // Engine off at the pump for ten minutes (nothing read), filled up, engine on and away within seconds.
        t += 600_000
        val seen = (0 until 8).mapNotNull { tank.update(mapOf("012F" to 97.0, "CALC_FUEL" to 0.8, "010D" to if (it < 6) 0.0 else 10.0), t).also { t += 1000 }?.refuel }
        assertEquals(1, seen.size)
        assertEquals(56 * 0.97 - before, seen.single().litres!!, 1.0)
        assertTrue(seen.single().full)
        assertEquals(56 * 0.97, tank.litres!!, 0.5)
        // A pause without a refuel proposes nothing.
        t += 600_000
        assertTrue((0 until 30).none { tank.update(mapOf("012F" to 96.0, "010D" to 0.0), t).also { t += 1000 }?.refuel != null })
    }

    @Test fun `a refuel between two connections is noticed at the next one`() {
        // The last connection left 11 L; this one finds the tank at three quarters.
        val tank = FuelTankEstimator(capacityL = 56.0, startL = 11.0)
        var t = 0L
        val seen = (0 until 10).mapNotNull { tank.update(mapOf("012F" to 75.0, "010D" to 0.0), t).also { t += 1000 }?.refuel }
        assertEquals(42.0 - 11.0, seen.single().litres!!, 1.0)
        assertTrue(!seen.single().full)
        // Driving off before the gauge is read standing: it is looked at the next time the car stops (here, never).
        val moving = FuelTankEstimator(capacityL = 56.0, startL = 11.0)
        assertTrue((0 until 60).none { moving.update(mapOf("012F" to 75.0, "010D" to 60.0), t).also { t += 1000 }?.refuel != null })
        // The same level as before: nothing.
        val same = FuelTankEstimator(capacityL = 56.0, startL = 30.0)
        assertTrue((0 until 30).none { same.update(mapOf("012F" to 55.0, "010D" to 0.0), t).also { t += 1000 }?.refuel != null })
    }

    @Test fun `without the tank size a refuel is noticed in percent`() {
        val tank = FuelTankEstimator(capacityL = null, startPct = 18.0)
        var t = 0L
        val r = (0 until 10).mapNotNull { tank.update(mapOf("012F" to 96.0, "010D" to 0.0), t).also { t += 1000 }?.refuel }.single()
        assertNull(r.litres)
        assertTrue(r.full)
        assertEquals(18.0, r.fromPct!!, 1e-9)
        assertEquals(96.0, tank.pct!!, 1e-9)
    }
}
