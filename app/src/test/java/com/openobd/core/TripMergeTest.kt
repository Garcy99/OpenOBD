package com.openobd.core

import com.openobd.core.garage.PURPOSE_PERSONAL
import com.openobd.core.garage.PURPOSE_WORK
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.core.trip.TripMerge
import com.openobd.core.trip.TripReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TripMergeTest {
    private val tz = TimeZone.getTimeZone("Europe/Madrid")
    private fun at(day: Int, h: Int, m: Int) = Calendar.getInstance(tz).apply { clear(); set(2026, 10, day, h, m) }.timeInMillis
    private fun trip(id: String, start: Long, mins: Int, km: Double, fuel: Double? = km * 0.06, car: String = "c", purpose: String = "") = TripRecord(
        id = id, vehicleId = car, start = start, end = start + mins * 60_000L, distanceKm = km, fuelL = fuel, maxSpeed = 40 + km,
        avgSpeed = km / (mins / 60.0), movingS = mins * 50.0, idleS = mins * 10.0, maxRpm = 2000 + km * 10, cost = fuel?.let { it * 1.5 },
        trackPoints = mins, harshAccel = 1, harshBrake = 1, highRpmS = 10.0, longIdleS = 5.0, purpose = purpose,
    )

    @Test fun `pieces of one drive add up into one trip`() {
        val a = trip("a", at(12, 18, 0), 20, 15.0, purpose = PURPOSE_WORK)
        val b = trip("b", at(12, 18, 23), 10, 8.0)
        val c = trip("c", at(12, 18, 35), 30, 25.0)
        val m = TripMerge.merge(listOf(c, a, b), "m")
        assertEquals(a.start, m.start)
        assertEquals(c.end, m.end)
        assertEquals(48.0, m.distanceKm, 1e-9)
        assertEquals(48 * 0.06, m.fuelL!!, 1e-9)
        assertEquals(48 * 0.06 * 1.5, m.cost!!, 1e-9)
        assertEquals(1.5, m.fuelPrice!!, 1e-9)
        assertEquals(c.maxSpeed, m.maxSpeed, 1e-9)
        assertEquals(60 * 50.0, m.movingS, 1e-9)
        assertEquals(48.0 / (60 * 50.0 / 3600), m.avgSpeed, 1e-9)
        assertEquals(3, m.harshAccel)
        assertEquals(60, m.trackPoints)
        assertEquals(PURPOSE_WORK, m.purpose)
        assertTrue(m.ecoScore != null)
        // A piece without fuel data: no total (it would make the average wrong).
        assertNull(TripMerge.merge(listOf(a, trip("x", at(12, 19, 0), 5, 2.0, fuel = null)), "n").fuelL)
    }

    @Test fun `trips of different cars are not joined`() {
        val r = runCatching { TripMerge.merge(listOf(trip("a", at(1, 9, 0), 5, 3.0), trip("b", at(1, 9, 10), 5, 3.0, car = "other")), "m") }
        assertTrue(r.isFailure)
    }

    @Test fun `routes follow one another`() {
        val t1 = listOf(TrackPoint(1000, 40.0, -4.0), TrackPoint(2000, 40.1, -4.0))
        val t2 = listOf(TrackPoint(5000, 40.2, -4.0))
        assertEquals(listOf(1000L, 2000L, 5000L), TripMerge.mergeTracks(listOf(t2, t1)).map { it.t })
    }

    @Test fun `drives cut in pieces are suggested, separate drives are not`() {
        val trips = listOf(
            trip("a", at(12, 18, 0), 20, 15.0), trip("b", at(12, 18, 25), 10, 8.0), trip("c", at(12, 18, 40), 30, 25.0),
            trip("d", at(12, 21, 0), 15, 10.0), // hours later: its own drive
            trip("e", at(13, 8, 0), 15, 10.0), trip("f", at(13, 8, 20), 15, 10.0, car = "other"),
        )
        val s = TripMerge.suggestions(trips)
        assertEquals(1, s.size)
        assertEquals(listOf("a", "b", "c"), s.single().map { it.id })
    }

    @Test fun `the monthly report counts work, personal or all trips`() {
        val trips = listOf(
            trip("a", at(3, 9, 0), 30, 20.0, purpose = PURPOSE_WORK), trip("b", at(4, 9, 0), 30, 30.0, purpose = PURPOSE_PERSONAL),
            trip("c", at(5, 9, 0), 30, 10.0), Calendar.getInstance(tz).apply { clear(); set(2026, 11, 1, 9, 0) }.timeInMillis.let { trip("d", it, 30, 99.0, purpose = PURPOSE_WORK) },
        )
        assertEquals(listOf("a", "b", "c"), TripReport.tripsOf(trips, 2026, 10, TripReport.Filter.ALL, tz).map { it.id })
        val work = TripReport.tripsOf(trips, 2026, 10, TripReport.Filter.WORK, tz)
        assertEquals(listOf("a"), work.map { it.id })
        assertEquals(20.0, TripReport.totals(work).km, 1e-9)
        val csv = TripReport.csv(TripReport.tripsOf(trips, 2026, 10, TripReport.Filter.ALL, tz), TripReport.Labels(), tz).trim().lines()
        assertEquals("Date;Start;End;Name;Purpose;km;Minutes;Litres;Cost", csv.first())
        assertEquals("2026-11-03;09:00;09:30;;Work;20,0;30;1,20;1,80", csv[1])
        assertEquals("Total;;;;;60,0;90;3,60;5,40", csv.last())
    }
}
