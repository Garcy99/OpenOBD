package com.openobd.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.core.garage.TripRecord
import com.openobd.data.ConnectionType
import com.openobd.data.Parking
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Home shows where the car was left and walks there; the monthly summary adds up the month's trips. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ParkedAndMonthUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val c get() = (rule.activity.application as OpenObdApp).container

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @Test fun parkedCarAndMonthlySummary() {
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", onboarded = true, tourSeen = true, autoConnect = false, fuelPrices = false, units = com.openobd.core.obd.UnitSystem.METRIC) } }
        waitFor { c.garage.loaded && I18n.language == "en" }
        // A car of its own (the garage outlives each test): its trips are only this test's, and it goes away after.
        val before = c.garage.data.value.activeVehicleId
        runBlocking { c.garage.update { it.copy(vehicles = it.vehicles + com.openobd.core.garage.Vehicle("vMonth", "Astra"), activeVehicleId = "vMonth") } }
        waitFor { c.garage.data.value.active?.id == "vMonth" }
        val car = c.garage.data.value.active!!
        c.parking.set(Parking.Spot(40.9479, -4.1178, System.currentTimeMillis() - 3600_000, car.id))
        waitFor { rule.onAllNodesWithTag("parkedCard").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("walkToCar").performScrollTo().performClick()
        val started = shadowOf(rule.activity.application).nextStartedActivity
        assertTrue("${started?.data}", started?.data.toString().contains("40.9479,-4.1178"))

        // A petrol car has no filter card; a diesel gets the shortcut to its particulate filter.
        assertTrue(rule.onAllNodesWithTag("dpfCard").fetchSemanticsNodes().isEmpty())
        runBlocking { c.garage.updateActive { it.copy(specs = com.openobd.core.specs.VehicleSpecs(make = "OPEL", model = "ASTRA", fuel = com.openobd.core.specs.SpecFuel.DIESEL)) } }
        waitFor { rule.onAllNodesWithTag("dpfCard").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("dpfCard").performScrollTo().performClick()
        waitFor { rule.onAllNodesWithText("Diesel particulate filter").fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        // Two trips this month, then the summary from a notification's request.
        val now = System.currentTimeMillis()
        runBlocking {
            for (i in 0..1) c.garage.saveTrip(TripRecord(id = "m$i", vehicleId = car.id, start = now - (i + 1) * 3600_000L, end = now - i * 3600_000L - 1800_000L,
                distanceKm = 50.0, fuelL = 3.0, ecoScore = 80, cost = 4.5), emptyList())
        }
        c.openRequest.value = Routes.MONTH
        waitFor { rule.onAllNodesWithText("Monthly summary").fetchSemanticsNodes().isNotEmpty() }
        waitFor { rule.onAllNodesWithText("100 km", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("6.0 L/100km", substring = true).fetchSemanticsNodes().isNotEmpty())
        // The month as a PDF to share (drawing PDFs needs a device: checked there).
        rule.onNodeWithTag("monthPdf").assertExists()
        runBlocking {
            c.garage.deleteTrip("m0"); c.garage.deleteTrip("m1")
            c.garage.update { it.copy(vehicles = it.vehicles.filter { v -> v.id != "vMonth" }, activeVehicleId = before) }
        }
        c.parking.clear()
    }
}
