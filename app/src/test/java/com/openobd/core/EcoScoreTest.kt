package com.openobd.core

import com.openobd.core.trip.EcoScorer
import com.openobd.core.trip.EcoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcoScoreTest {
    /** Feeds a speed profile sampled every 250 ms; rpm follows speed like a car in a sensible gear. */
    private fun drive(scorer: EcoScorer, from: Long, seconds: Int, speed: (Double) -> Double): Long = driveRpm(scorer, from, seconds, speed, null)

    private fun driveRpm(scorer: EcoScorer, from: Long, seconds: Int, speed: (Double) -> Double, rpm: ((Double) -> Double)?): Long {
        var t = from
        val end = from + seconds * 1000L
        while (t <= end) {
            val sec = (t - from) / 1000.0
            val v = speed(sec)
            scorer.onSample(v, rpm?.invoke(v) ?: if (v < 1) 800.0 else 1200 + v * 12, t)
            t += 250
        }
        return t
    }

    @Test
    fun calmDrivingScoresHigh() {
        val s = EcoScorer()
        var t = drive(s, 1, 60) { it * 1.0 }              // 0-60 km/h in a minute
        t = drive(s, t, 600) { 60.0 }                    // 10 min cruising
        drive(s, t, 30) { 60.0 - it * 2 }                // gentle stop
        val st = s.current
        assertEquals(0, st.harshAccel)
        assertEquals(0, st.harshBrake)
        assertTrue(st.distanceKm > 10)
        assertEquals(100, EcoScorer.score(st))
        assertEquals(listOf("Smooth driving. Keep it up!"), EcoScorer.tips(st).map { it.text })
    }

    @Test
    fun aggressiveDrivingCostsPoints() {
        val s = EcoScorer()
        var t = 1L
        repeat(6) {
            t = drive(s, t, 4) { sec -> sec * 15 }            // 0-60 in 4 s: hard acceleration
            t = driveRpm(s, t, 60, { 60.0 }) { 3900.0 }          // revving high
            t = drive(s, t, 3) { sec -> 60 - sec * 20 }       // hard stop
            t = drive(s, t, 5) { 0.0 }
        }
        val st = s.current
        assertEquals(6, st.harshAccel)
        assertEquals(6, st.harshBrake)
        val score = EcoScorer.score(st)!!
        assertTrue("score $score", score < 40)
        val tips = EcoScorer.tips(st).map { it.text }
        assertEquals(2, tips.size)
        assertTrue(tips.toString(), tips.any { it.startsWith("Accelerate") } && tips.any { it.startsWith("Look further") })
    }

    @Test
    fun noisyReadingIsNotAnEvent() {
        val s = EcoScorer()
        var t = drive(s, 1, 30) { 50.0 }
        s.onSample(80.0, 1800.0, t); t += 250   // one glitchy sample
        drive(s, t, 30) { 50.0 }
        assertEquals(0, s.current.harshAccel)
    }

    @Test
    fun longIdlingAndDieselRpm() {
        val diesel = EcoScorer(diesel = true)
        var t = driveRpm(diesel, 1, 300, { 60.0 }) { 3200.0 }
        t = drive(diesel, t, 300) { 0.0 }
        val st = diesel.current
        assertTrue(st.highRpmS > 290)
        assertTrue(st.longIdleS in 230.0..245.0) // the first minute of a stop is free
        assertTrue(EcoScorer.tips(st, diesel = true).any { it.args.contains("3000") })
        // Petrol: 3200 rpm is fine
        val petrol = EcoScorer()
        driveRpm(petrol, 1, 300, { 60.0 }) { 3200.0 }
        assertEquals(0.0, petrol.current.highRpmS, 0.0)
    }

    @Test
    fun shortTripsAreNotScored() {
        assertNull(EcoScorer.score(EcoStats(distanceKm = 0.6, drivingS = 300.0)))
        assertNull(EcoScorer.score(EcoStats(distanceKm = 2.0, drivingS = 60.0)))
        assertTrue(EcoScorer.tips(EcoStats(distanceKm = 0.5, drivingS = 60.0)).isEmpty())
    }
}
