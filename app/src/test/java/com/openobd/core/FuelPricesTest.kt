package com.openobd.core

import com.openobd.core.fuel.FuelPricesEs
import com.openobd.core.fuel.PumpFuel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelPricesTest {
    private val json = javaClass.getResource("/fuel-segovia.json")!!.readText()

    @Test fun `parses the ministry's stations with decimal commas`() {
        val list = FuelPricesEs.parse(json)
        assertEquals(6, list.stations.size)
        val ballenoil = list.stations.single { it.brand == "Ballenoil" }
        assertEquals(1.739, ballenoil.prices[PumpFuel.DIESEL]!!, 1e-9)
        assertEquals(1.559, ballenoil.prices[PumpFuel.PETROL_95]!!, 1e-9)
        assertEquals(40.937778, ballenoil.lat, 1e-9)
        assertTrue(list.updatedAt > 1_700_000_000_000L)
        assertFalse(PumpFuel.LPG in ballenoil.prices) // empty price fields are left out
    }

    @Test fun `cheapest near, usual price, saving and stations along a route`() {
        val st = FuelPricesEs.parse(json).stations
        // Segovia's aqueduct.
        val lat = 40.9480; val lon = -4.1178
        val near = FuelPricesEs.near(st, lat, lon, PumpFuel.DIESEL, 10.0)
        assertEquals(6, near.size)
        assertTrue(near.zipWithNext().all { (a, b) -> a.km <= b.km })
        assertEquals("Ballenoil", near.minBy { it.price(PumpFuel.DIESEL)!! }.station.brand)
        assertTrue(FuelPricesEs.near(st, lat, lon, PumpFuel.DIESEL, 0.3).size <= 1)
        // Median of the six: (1.929 + 1.930) / 2.
        assertEquals(1.9295, FuelPricesEs.localPrice(st, lat, lon, PumpFuel.DIESEL)!!, 1e-9)
        assertEquals(9.6, FuelPricesEs.saving(50.0, 1.739, 1.931), 1e-9)
        // A road passing the Shell station (south) but far from the others.
        val road = listOf(40.9200 to -4.1092, 40.9272 to -4.1092, 40.9300 to -4.1092)
        val along = FuelPricesEs.alongRoute(st, road, PumpFuel.DIESEL, corridorKm = 0.3)
        assertEquals(listOf("Shell"), along.map { it.station.brand })
        // Madrid is near no Segovia station, but Spain's provinces cover it; Paris is outside.
        assertTrue(FuelPricesEs.covers(40.4168, -3.7038) && !FuelPricesEs.covers(48.85, 2.35))
        assertTrue("40" in FuelPricesEs.provincesNear(lat, lon, 10.0))
        assertEquals(52, FuelPricesEs.provinces.size)
    }
}
