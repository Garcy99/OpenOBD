package com.openobd.core

import com.openobd.core.diesel.RegenWatch
import com.openobd.core.garage.FuelEntry
import com.openobd.core.garage.MonthlySummary
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.core.obd.Pids
import com.openobd.core.trip.TrackColoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class Release122Test {
    @Test fun `the diesel aftertreatment PID tells the regeneration, the filter load and the intervals`() {
        val info = Pids.all[0x8B]!!
        // Fields filled in: status, trigger, time and distance; regenerating; 50 %; every 300 min / 450 km.
        val bytes = intArrayOf(0x71, 0x01, 0x80, 0x01, 0x2C, 0x01, 0xC2)
        val v = info.sensors.associate { it.key to it.decodeSafe(bytes) }
        assertEquals(1.0, v["018B_R"]!!, 1e-9)
        assertEquals(50.2, v["018B_T"]!!, 0.1)
        assertEquals(300.0, v["018B_M"]!!, 1e-9)
        assertEquals(450.0, v["018B_K"]!!, 1e-9)
        // Fields the car does not fill in are unknown, not zero.
        val none = info.sensors.associate { it.key to it.decodeSafe(intArrayOf(0x01, 0x00, 0, 0, 0, 0, 0)) }
        assertEquals(0.0, none["018B_R"]!!, 1e-9)
        assertNull(none["018B_T"])
    }

    @Test fun `regenerations are told from the car's status`() {
        val w = RegenWatch()
        var t = 0L
        fun cycle(status: Double, rpm: Double = 2200.0) = w.onCycle(mapOf(RegenWatch.STATUS to status, RegenWatch.LOAD to 80.0, RegenWatch.RPM to rpm), t).also { t += 1000 }
        assertNull(cycle(0.0))
        val start = cycle(1.0)
        assertTrue(start is RegenWatch.Event.Started && start.source == RegenWatch.Source.STATUS)
        repeat(600) { assertNull(cycle(1.0)) }
        val end = cycle(0.0)
        assertTrue("$end", end is RegenWatch.Event.Ended && end.seconds in 595..605)
        // Again, and this time the engine stops in the middle.
        cycle(1.0); repeat(120) { cycle(1.0) }
        val cut = cycle(1.0, rpm = 0.0)
        assertTrue("$cut", cut is RegenWatch.Event.Interrupted && cut.seconds in 118..125)
        assertTrue(!w.state.active)
    }

    @Test fun `without a status the filter temperature tells, ignoring short peaks`() {
        val w = RegenWatch()
        var t = 0L
        fun cycle(temp: Double) = w.onCycle(mapOf(RegenWatch.INLET_TEMP to temp, RegenWatch.RPM to 2000.0), t).also { t += 1000 }
        repeat(20) { assertNull(cycle(300.0)) }
        // A hard climb: hot for 20 s only.
        repeat(20) { assertNull(cycle(560.0)) }
        repeat(20) { assertNull(cycle(320.0)) }
        // A real regeneration: hot for minutes.
        var started: RegenWatch.Event? = null
        repeat(70) { cycle(600.0)?.let { started = it } }
        assertTrue(started is RegenWatch.Event.Started && (started as RegenWatch.Event.Started).source == RegenWatch.Source.TEMPERATURE)
        repeat(300) { cycle(610.0) }
        var ended: RegenWatch.Event? = null
        repeat(40) { cycle(350.0)?.let { ended = it } }
        assertTrue("$ended", ended is RegenWatch.Event.Ended)
        // Losing the link mid-regeneration counts as cut short.
        repeat(70) { cycle(600.0) }
        assertTrue(w.onLinkLost(t) is RegenWatch.Event.Interrupted)
        // A car that tells nothing: nothing is guessed.
        val quiet = RegenWatch()
        repeat(100) { assertNull(quiet.onCycle(mapOf(RegenWatch.RPM to 2500.0), it * 1000L)) }
        assertNull(quiet.state.source)
    }

    @Test fun `the route is coloured by consumption against the trip's own average`() {
        fun p(i: Int, l100: Float?, speed: Float = 60f) = TrackPoint(i * 1000L, 40.0 + i * 1e-4, -4.0, speed, l100 = l100)
        val pts = (0 until 10).map { p(it, 5f) } + (10 until 20).map { p(it, 12f) } + (20 until 25).map { p(it, null, 0f) }
        val limits = TrackColoring.consumptionLimits(pts)!!
        val avg = (10 * 5.0 + 10 * 12.0) / 20
        assertEquals(avg * 0.8, limits.first, 1e-9)
        val runs = TrackColoring.runs(pts, TrackColoring.Mode.CONSUMPTION)
        assertEquals(listOf(0, 2, 3), runs.map { it.band })
        // Runs join: each starts where the last ended.
        for (i in 1 until runs.size) assertEquals(runs[i - 1].to, runs[i].from)
        assertEquals(pts.size - 1, runs.last().to)
        // No consumption values: no consumption colouring.
        assertNull(TrackColoring.consumptionLimits(pts.map { it.copy(l100 = null) }))
        assertEquals(1, TrackColoring.runs(pts.take(10), TrackColoring.Mode.SPEED).size)
    }

    @Test fun `a month adds up, compares and finds the saving`() {
        val tz = TimeZone.getTimeZone("Europe/Madrid")
        fun at(month: Int, day: Int) = Calendar.getInstance(tz).apply { clear(); set(2026, month, day, 9, 0) }.timeInMillis
        fun trip(id: Int, month: Int, day: Int, km: Double, l: Double?, eco: Int? = null, cost: Double? = null) =
            TripRecord(id = "t$id", vehicleId = "car", start = at(month, day), end = at(month, day) + 3600_000, distanceKm = km, fuelL = l, ecoScore = eco, cost = cost)
        val trips = listOf(
            // September: 300 km at 6 L/100.
            trip(1, 8, 3, 150.0, 9.0, 70), trip(2, 8, 20, 150.0, 9.0, 70),
            // October: 4 trips, one thrifty (4 L/100), three thirstier (7 L/100).
            trip(3, 9, 2, 100.0, 4.0, 90, 6.0), trip(4, 9, 9, 100.0, 7.0, 60, 10.5), trip(5, 9, 16, 100.0, 7.0, 60, 10.5), trip(6, 9, 23, 100.0, 7.0, 60, 10.5),
        )
        val refuels = listOf(FuelEntry("f1", "car", at(9, 10), 50_000.0, 30.0, 45.0))
        val s = MonthlySummary.of(2026, 9, trips, refuels, diesel = true, lpg = false, officialL100 = 4.5, fallbackPrice = 1.6, tz = tz)
        assertEquals(4, s.trips)
        assertEquals(400.0, s.km, 1e-9)
        assertEquals(25.0, s.fuelL!!, 1e-9)
        assertEquals(6.25, s.avgL100!!, 1e-9)
        // The fill-ups of the month are what was spent; their price per litre values the saving.
        assertEquals(45.0, s.cost!!, 1e-9); assertTrue(s.costFromRefuels)
        assertEquals(1.5, s.pricePerL!!, 1e-9)
        assertEquals(25 * 2.64, s.co2Kg!!, 1e-9)
        assertEquals(68, s.ecoScore)
        // Against the official 4.5 L/100: 39 % more.
        assertEquals(38.9, s.vsOfficialPct!!, 0.1)
        // The thriftiest quarter of the last three months (of 6 trips: the best one, 4 L/100) → 9 L and 13.5 € less.
        assertEquals(4.0, s.bestL100!!, 1e-9)
        assertEquals(9.0, s.savingL!!, 1e-9)
        assertEquals(13.5, s.savingMoney!!, 1e-9)
        // The month before, for the changes.
        assertEquals(300.0, s.previous!!.km, 1e-9)
        assertEquals(6.0, s.previous!!.avgL100!!, 1e-9)
        assertNull(s.previous!!.previous)
        assertEquals(0, MonthlySummary.of(2026, 10, trips, refuels, true, false, null, null, tz).trips)
    }
}
