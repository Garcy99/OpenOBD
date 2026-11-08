package com.openobd.sync

import com.openobd.core.garage.FuelEntry
import com.openobd.core.garage.GarageData
import com.openobd.core.garage.TripRecord
import com.openobd.core.garage.Vehicle
import com.openobd.core.sync.SyncMerge
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMergeTest {
    data class R(val id: String, val v: Int)

    @Test fun `changes on either side survive and deletions travel`() {
        val base = listOf(R("a", 1), R("b", 1), R("c", 1), R("d", 1))
        val local = listOf(R("a", 2), R("b", 1), R("d", 1), R("e", 1))   // edited a, deleted c, added e
        val remote = listOf(R("a", 1), R("b", 3), R("c", 1), R("f", 1)) // edited b, deleted d, added f
        assertEquals(listOf(R("a", 2), R("b", 3), R("e", 1), R("f", 1)), SyncMerge.list(base, local, remote) { it.id })
    }

    @Test fun `same record changed on both sides keeps this device's, and an edit beats a deletion`() {
        val base = listOf(R("a", 1), R("b", 1), R("c", 1))
        val local = listOf(R("a", 2), R("c", 5))   // b deleted here, c edited
        val remote = listOf(R("a", 3), R("b", 4))  // b edited there, c deleted
        assertEquals(listOf(R("a", 2), R("c", 5), R("b", 4)), SyncMerge.list(base, local, remote) { it.id })
    }

    @Test fun `first sync without a base joins both`() {
        assertEquals(listOf(R("x", 1), R("y", 1)), SyncMerge.list(emptyList(), listOf(R("x", 1)), listOf(R("y", 1))) { it.id })
    }

    @Test fun `garage merges every list and keeps this phone's active car`() {
        val car1 = Vehicle("v1", "Astra"); val car2 = Vehicle("v2", "Corsa")
        val trip = { id: String, start: Long -> TripRecord(id = id, vehicleId = "v1", start = start, end = start + 1000, distanceKm = 1.0) }
        val base = GarageData(listOf(car1), "v1", trips = listOf(trip("t1", 1)))
        val local = base.copy(vehicles = listOf(car1, car2), activeVehicleId = "v2", trips = listOf(trip("t2", 5), trip("t1", 1)))
        val remote = base.copy(trips = listOf(trip("t3", 9), trip("t1", 1).copy(name = "Commute")), fuel = listOf(FuelEntry("f1", "v1", 3, 1000.0, 40.0, 60.0)))
        val m = SyncMerge.garage(base, local, remote)
        assertEquals(listOf("v1", "v2"), m.vehicles.map { it.id })
        assertEquals("v2", m.activeVehicleId)
        assertEquals(listOf("t3", "t2", "t1"), m.trips.map { it.id }) // newest first
        assertEquals("Commute", m.trips.last().name)
        assertEquals(listOf("f1"), m.fuel.map { it.id })
        // Nothing in the cloud yet: this phone's data as it is.
        assertEquals(local, SyncMerge.garage(null, local, null))
    }
}
