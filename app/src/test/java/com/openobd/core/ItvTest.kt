package com.openobd.core

import com.openobd.core.garage.Maintenance
import com.openobd.core.garage.MaintenanceItem
import com.openobd.core.specs.Spain
import com.openobd.core.specs.VehicleSpecs
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ItvTest {
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }.timeInMillis
    private val astra = VehicleSpecs(make = "Opel", model = "Astra", country = "ES", firstRegistration = day(2014, 3, 10))

    @Test
    fun nextItvCountsFromTheDayItWasPassed() {
        // A 12-year-old car passed on 20 Sep 2026: next one 20 Sep 2027, not on the registration anniversary
        assertEquals(day(2027, 9, 20), Spain.nextItvAfter(day(2026, 9, 20), astra.firstRegistration, null))
        // A 6-year-old car: every 2 years
        assertEquals(day(2028, 5, 2), Spain.nextItvAfter(day(2026, 5, 2), day(2020, 1, 15), null))
        // Unknown registration: assume yearly
        assertEquals(day(2027, 5, 2), Spain.nextItvAfter(day(2026, 5, 2), null, null))
    }

    @Test
    fun passingInTheMonthBeforeItIsDueKeepsTheDueDate() {
        val due = day(2026, 3, 10)
        assertEquals(day(2027, 3, 10), Spain.nextItvAfter(day(2026, 2, 20), astra.firstRegistration, due))
        // Two months early: counts from the day it was passed
        assertEquals(day(2027, 1, 5), Spain.nextItvAfter(day(2026, 1, 5), astra.firstRegistration, due))
        // Late: from the day it was passed
        assertEquals(day(2027, 4, 1), Spain.nextItvAfter(day(2026, 4, 1), astra.firstRegistration, due))
    }

    @Test
    fun reminderUsesThePassedDateEverywhere() {
        val item = MaintenanceItem("inspection_car", "car", "ITV", intervalMonths = 12, dueDate = Spain.nextItv(astra.firstRegistration!!, day(2026, 1, 1)))
        val passed = Maintenance.inspectionPassed(item, astra, day(2026, 9, 20), 150_000.0)
        assertEquals(day(2027, 9, 20), passed.dueDate)
        assertEquals(12, passed.intervalMonths)
        assertEquals(day(2027, 9, 20), Maintenance.nextInspection(astra, listOf(passed), day(2026, 10, 1)))
        // Without a passed date, the registration calendar
        assertEquals(Spain.nextItv(astra.firstRegistration!!, day(2026, 10, 1)), Maintenance.nextInspection(astra, emptyList(), day(2026, 10, 1)))
    }
}
