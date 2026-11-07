package com.openobd.core

import com.openobd.core.garage.FuelEntry
import com.openobd.core.garage.RealConsumption
import com.openobd.core.garage.TripRecord
import com.openobd.core.obd.DerivedSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fuel estimates: a 1.6 CDTI (81 kW) without the fuel-rate PID used to read ~85 L/100 km. */
class FuelEstimateTest {
    private val diesel = DerivedSensors.Params(14.5, 832.0, 1.6, diesel = true, maxPowerKw = 81.0)

    private fun l100(v: Map<String, Double>, p: DerivedSensors.Params) = DerivedSensors.compute(v, p)["CALC_CONS"]

    @Test
    fun dieselCruiseIsRealisticWithoutTheFuelRatePid() {
        // 120 km/h in 6th: ~2200 rpm, ~35 % load, lots of air (MAF 45 g/s, lambda ~2.5)
        val cruise = mapOf("010C" to 2200.0, "0104" to 35.0, "010D" to 120.0, "0110" to 45.0)
        val l = l100(cruise, diesel)!!
        assertTrue("cruise $l", l in 3.5..8.0)
        // The old air-flow formula would have said ~12 L/100 km here, and far more at idle and in town
        val asGasoline = l100(cruise, diesel.copy(diesel = false))!!
        assertTrue(asGasoline > l * 1.5)
    }

    @Test
    fun dieselIdleAndOverrun() {
        val idle = DerivedSensors.compute(mapOf("010C" to 820.0, "0104" to 18.0, "010D" to 0.0), diesel)["CALC_FUEL"]!!
        assertTrue("idle $idle L/h", idle in 0.3..1.5)
        val overrun = DerivedSensors.compute(mapOf("010C" to 2000.0, "0104" to 0.0, "010D" to 80.0), diesel)["CALC_FUEL"]!!
        assertEquals(0.0, overrun, 0.0)
    }

    @Test
    fun dieselTorqueMethodWhenAvailable() {
        // 25 kW at 2200 rpm: 108 Nm = 36 % of a 300 Nm reference
        val v = mapOf("010C" to 2200.0, "0162" to 36.0, "0163" to 300.0, "0104" to 40.0, "010D" to 120.0)
        assertTrue(l100(v, diesel)!! in 4.0..8.0)
        assertEquals(listOf("0162", "0163", "010C"), DerivedSensors.requirements("CALC_FUEL", setOf(0x62, 0x63, 0x0C, 0x04, 0x10), diesel = true))
        assertEquals(listOf("0104", "010C"), DerivedSensors.requirements("CALC_FUEL", setOf(0x0C, 0x04, 0x10), diesel = true))
        // Petrol cars keep the air-flow method
        assertEquals(listOf("0110"), DerivedSensors.requirements("CALC_FUEL", setOf(0x0C, 0x04, 0x10)))
    }

    @Test
    fun fuelRatePidWinsAndIsNotCalibrated() {
        val v = mapOf("015E" to 4.0, "010C" to 2000.0, "0104" to 30.0, "010D" to 100.0)
        assertEquals(4.0, DerivedSensors.compute(v, diesel.copy(calibration = 0.5))["CALC_FUEL"]!!, 1e-9)
        val est = DerivedSensors.compute(v - "015E", diesel)["CALC_FUEL"]!!
        assertEquals(est * 0.8, DerivedSensors.compute(v - "015E", diesel.copy(calibration = 0.8))["CALC_FUEL"]!!, 1e-9)
    }

    @Test
    fun petrolAirFlowUnchanged() {
        val p = DerivedSensors.Params()
        assertEquals(20 / 14.7 / 745 * 3600, DerivedSensors.compute(mapOf("0110" to 20.0), p)["CALC_FUEL"]!!, 1e-6)
    }

    private val day = 86_400_000L
    private fun trip(id: String, startDay: Int, km: Double, litres: Double, model: Int = DerivedSensors.FUEL_MODEL) =
        TripRecord(id, "car", startDay * day, startDay * day + 3_600_000, km, litres, fuelModel = model)

    @Test
    fun calibrationFromFullToFullFillUps() {
        val fuel = listOf(
            FuelEntry("f1", "car", 0, 10_000.0, 40.0, 60.0, fullTank = true),
            FuelEntry("f2", "car", 10 * day, 10_600.0, 36.0, 54.0, fullTank = true), // 6.0 L/100 km for real
        )
        // The estimate said 9 L/100 km over 500 of those 600 km
        val trips = listOf(trip("a", 1, 250.0, 22.5), trip("b", 5, 250.0, 22.5))
        assertEquals(6.0 / 9.0, RealConsumption.calibration(fuel, trips)!!, 1e-9)
        // Too little recorded driving inside the interval: no calibration
        assertNull(RealConsumption.calibration(fuel, listOf(trip("a", 1, 80.0, 7.2))))
        // Trips from older versions (other estimate) are ignored
        assertNull(RealConsumption.calibration(fuel, trips.map { it.copy(fuelModel = 0) }))
    }

    @Test
    fun averageFromFuelLogThenTrips() {
        val fuel = listOf(
            FuelEntry("f1", "car", 0, 10_000.0, 40.0, 60.0, fullTank = true),
            FuelEntry("f2", "car", 10 * day, 10_600.0, 36.0, 54.0, fullTank = true),
        )
        val trips = listOf(trip("a", 1, 100.0, 7.0), trip("old", 2, 100.0, 85.0, model = 0))
        val fromLog = RealConsumption.average(fuel, trips)!!
        assertTrue(fromLog.fromFuelLog); assertEquals(6.0, fromLog.l100, 1e-9); assertEquals(600.0, fromLog.km, 1e-9)
        val fromTrips = RealConsumption.average(emptyList(), trips)!!
        assertTrue(!fromTrips.fromFuelLog); assertEquals(7.0, fromTrips.l100, 1e-9) // the 85 L/100 km trip is not used
        assertNull(RealConsumption.average(emptyList(), listOf(trip("short", 1, 10.0, 0.6))))
    }
}
