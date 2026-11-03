package com.openobd.core

import com.openobd.core.garage.FuelEntry
import com.openobd.core.garage.FuelStats
import com.openobd.core.garage.GarageData
import com.openobd.core.garage.Maintenance
import com.openobd.core.garage.MaintenanceItem
import com.openobd.core.garage.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarageTest {
    private val day = 86_400_000L

    @Test
    fun fuelFullToFull() {
        val e = listOf(
            FuelEntry("1", "v", 0, 10_000.0, 40.0, 60.0, fullTank = true),
            FuelEntry("2", "v", day, 10_300.0, 15.0, 22.5, fullTank = false), // partial
            FuelEntry("3", "v", 2 * day, 10_600.0, 21.0, 31.5, fullTank = true),
            FuelEntry("4", "v", 3 * day, 11_100.0, 30.0, 45.0, fullTank = true),
        )
        val s = FuelStats.compute(e.shuffled())
        assertEquals(6.0, s.perEntry.getValue("3"), 1e-9) // (15 + 21) / 600 km
        assertEquals(6.0, s.perEntry.getValue("4"), 1e-9) // 30 / 500 km
        assertEquals(6.0, s.averageL100!!, 1e-9)
        assertEquals(6.0, s.lastL100!!, 1e-9)
        assertEquals(1100.0, s.distanceKm, 1e-9)
        assertEquals((22.5 + 31.5 + 45.0) / 1100.0, s.costPerKm!!, 1e-9)
        assertNull(FuelStats.compute(e.take(1)).averageL100)
    }

    @Test
    fun maintenanceStatus() {
        val now = 400 * day
        val oil = MaintenanceItem("o", "v", "Oil", intervalKm = 10_000, intervalMonths = 12, lastKm = 50_000.0, lastDate = now - 100 * day)
        val ok = Maintenance.status(oil, 55_000.0, now)
        assertEquals(5_000.0, ok.remainingKm!!, 1e-9)
        assertFalse(ok.overdue || ok.dueSoon)
        assertTrue(Maintenance.status(oil, 59_500.0, now).dueSoon)
        assertTrue(Maintenance.status(oil, 60_100.0, now).overdue)
        assertTrue(Maintenance.status(oil, 51_000.0, now + 300 * day).overdue) // time-based
        assertFalse(Maintenance.status(oil.copy(lastKm = null, lastDate = null), 51_000.0, now).known)
    }

    @Test
    fun vehicleSelectionByVin() {
        val first = GarageData(listOf(Vehicle("a", GarageData.DEFAULT_NAME)), "a")
        // First connection fills in the VIN of the default car and names it
        val g1 = first.forConnection("1HGCM82633A004352", "2003 Honda", "b", 0)
        assertEquals(1, g1.vehicles.size)
        assertEquals("2003 Honda", g1.active!!.name)
        // Another car creates a new profile and selects it
        val g2 = g1.forConnection("WVWZZZ1KZAW000001", "2010 Volkswagen", "c", 0)
        assertEquals(2, g2.vehicles.size)
        assertEquals("c", g2.activeVehicleId)
        // Coming back to the first car re-selects it
        assertEquals("a", g2.forConnection("1HGCM82633A004352", "x", "d", 0).activeVehicleId)
        // No VIN: keep the current selection
        assertEquals("c", g2.forConnection(null, "x", "e", 0).activeVehicleId)
    }
}
