package com.openobd.core

import com.openobd.core.report.CarScore
import com.openobd.core.report.ScoreInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarScoreTest {
    @Test
    fun nothingKnownMeansNoScore() {
        val r = CarScore.compute(ScoreInput())
        assertNull(r.score)
        assertEquals("Not checked yet", r.label)
    }

    @Test
    fun healthyCarScoresFull() {
        val r = CarScore.compute(ScoreInput(milOn = false, storedCodes = 0, pendingCodes = 0, permanentCodes = 0, monitorsNotReady = 0,
            moduleCodes = 0, batteryV = 14.2, engineRunning = true))
        assertEquals(100, r.score)
        assertEquals("Excellent", r.label)
        assertTrue(r.reasons.isEmpty())
    }

    @Test
    fun demoCarWithMisfire() {
        // MIL on, two confirmed and one pending code: as in the demo car
        val r = CarScore.compute(ScoreInput(milOn = true, storedCodes = 2, pendingCodes = 1, permanentCodes = 1, monitorsNotReady = 0,
            batteryV = 14.0, engineRunning = true))
        assertEquals(46, r.score)
        assertEquals("Have it checked", r.label)
        assertEquals("Check engine light is on", r.reasons.first().text)
        // Permanent codes are not counted twice while confirmed codes are present
        assertTrue(r.reasons.none { it.text.contains("permanent") })
    }

    @Test
    fun categoriesAreCappedAndOrdered() {
        val many = CarScore.compute(ScoreInput(storedCodes = 9))
        assertEquals(70, many.score) // capped at 30 points
        val mixed = CarScore.compute(ScoreInput(storedCodes = 0, moduleCodes = 2, maintenanceOverdue = 1, healthWatch = 1))
        assertEquals(listOf(8, 8, 4), mixed.reasons.map { it.points })
        assertEquals(80, mixed.score)
        assertEquals("Good", mixed.label)
    }

    @Test
    fun batteryRules() {
        fun reason(v: Double, running: Boolean?) = CarScore.compute(ScoreInput(batteryV = v, engineRunning = running)).reasons.firstOrNull()?.text
        assertEquals("The battery is not charging", reason(12.3, true))
        assertEquals("Battery charge is low", reason(11.8, false))
        assertEquals("Battery partly discharged", reason(12.2, false))
        assertEquals("Charging voltage too high", reason(15.4, true))
        assertNull(reason(12.6, false))
        // Unknown engine state: above 13.2 V counts as running
        assertNull(reason(14.1, null))
    }
}
