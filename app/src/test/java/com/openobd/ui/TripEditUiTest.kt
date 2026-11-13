package com.openobd.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.core.garage.PURPOSE_WORK
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.data.ConnectionType
import com.openobd.session.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A drive recorded in pieces is joined back into one; trips are marked work or personal and reported by month. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TripEditUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val c get() = (rule.activity.application as OpenObdApp).container

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    private fun setUp(): String? {
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", onboarded = true, tourSeen = true, autoConnect = false, fuelPrices = false, units = com.openobd.core.obd.UnitSystem.METRIC) } }
        waitFor { c.garage.loaded && I18n.language == "en" }
        val before = c.garage.data.value.activeVehicleId
        runBlocking { c.garage.update { it.copy(vehicles = it.vehicles + com.openobd.core.garage.Vehicle("vEdit", "Astra"), activeVehicleId = "vEdit") } }
        waitFor { c.garage.data.value.active?.id == "vEdit" }
        return before
    }

    private fun tearDown(before: String?) = runBlocking {
        c.garage.update { it.copy(trips = it.trips.filter { t -> t.vehicleId != "vEdit" }, vehicles = it.vehicles.filter { v -> v.id != "vEdit" }, activeVehicleId = before) }
    }

    @Test fun joinPiecesMarkWorkAndReport() {
        val before = setUp()
        try {
            val t0 = System.currentTimeMillis() - 4 * 3600_000L
            runBlocking {
                // One drive cut in three by a lost link, and a separate one later.
                listOf(0L to 20, 25L to 10, 40L to 30).forEachIndexed { i, (offset, mins) ->
                    val start = t0 + offset * 60_000
                    c.garage.saveTrip(TripRecord(id = "p$i", vehicleId = "vEdit", start = start, end = start + mins * 60_000L, distanceKm = 10.0 + i,
                        fuelL = 0.6, cost = 0.9, movingS = mins * 50.0), listOf(TrackPoint(start, 40.9 + i * 0.01, -4.1), TrackPoint(start + 60_000, 40.905 + i * 0.01, -4.1)))
                }
                c.garage.saveTrip(TripRecord(id = "late", vehicleId = "vEdit", start = t0 + 3 * 3600_000L, end = t0 + 3 * 3600_000L + 600_000, distanceKm = 5.0), emptyList())
            }
            c.openRequest.value = Routes.TRIPS
            waitFor { rule.onAllNodesWithText("Trip history").fetchSemanticsNodes().isNotEmpty() }
            // The pieces are offered as one drive; joined, one trip with all their kilometres and route is left.
            waitFor { rule.onAllNodesWithTag("joinSuggestion").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("One drive in 3 pieces?").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithTag("joinSuggested").performClick()
            waitFor { c.garage.data.value.trips.count { it.vehicleId == "vEdit" } == 2 }
            val joined = c.garage.data.value.trips.first { it.vehicleId == "vEdit" && it.id != "late" }
            assertEquals(33.0, joined.distanceKm, 1e-9)
            assertEquals(1.8, joined.fuelL!!, 1e-9)
            assertEquals(6, runBlocking { c.garage.loadTrack(joined.id) }.size)
            waitFor { rule.onAllNodesWithTag("joinSuggestion").fetchSemanticsNodes().isEmpty() }

            // Picking trips by hand: a long press, then the other one, then join.
            rule.onNodeWithTag("trip_late").performScrollTo().performTouchInput { longClick() }
            rule.onNodeWithTag("trip_" + joined.id).performScrollTo().performClick()
            rule.onNodeWithTag("joinTrips").performClick()
            waitFor { c.garage.data.value.trips.count { it.vehicleId == "vEdit" } == 1 }
            val all = c.garage.data.value.trips.single { it.vehicleId == "vEdit" }
            assertEquals(38.0, all.distanceKm, 1e-9)

            // Marked as work in its details, it shows in the work report.
            rule.onNodeWithTag("trip_" + all.id).performScrollTo().performClick()
            waitFor { rule.onAllNodesWithTag("purpose_$PURPOSE_WORK").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("purpose_$PURPOSE_WORK").performScrollTo().performClick()
            waitFor { c.garage.data.value.trips.single { it.vehicleId == "vEdit" }.purpose == PURPOSE_WORK }
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
            rule.onNodeWithTag("tripReport").performClick()
            waitFor { rule.onAllNodesWithTag("reportTotals").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("Work").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
            assertTrue(rule.onAllNodesWithText("1 trips · 38.0 km", substring = true).fetchSemanticsNodes().isNotEmpty())
        } finally { tearDown(before) }
    }

    @Test fun theLiveScreenShowsTheTripBeingRecorded() {
        val before = setUp()
        try {
            c.session.connect()
            waitFor(30_000) { c.session.state.value is ConnectionState.Connected }
            c.openRequest.value = Routes.LIVE
            waitFor { rule.onAllNodesWithTag("tripStatus").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("Recording trip", substring = true).fetchSemanticsNodes().isNotEmpty())
            // And how the adapter link is doing.
            waitFor { rule.onAllNodesWithText("Connection:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithTag("linkQuality").fetchSemanticsNodes().isNotEmpty())
        } finally {
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
            tearDown(before)
        }
    }

    @Test fun homeShowsTheBatteryAsTheCarStood() {
        val before = setUp()
        try {
            val now = System.currentTimeMillis()
            runBlocking {
                c.garage.saveTrip(TripRecord(id = "b0", vehicleId = "vEdit", start = now - 3 * 86_400_000L - 3600_000, end = now - 3 * 86_400_000L, distanceKm = 12.0), emptyList())
                c.garage.addHealthSample(com.openobd.core.health.HealthSample("vEdit", now - 600_000, restingV = 11.9, chargingV = 14.1))
            }
            c.openRequest.value = Routes.HOME
            waitFor { rule.onAllNodesWithTag("batteryCard").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("Battery · Low", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText("11.9 V after 3 days parked", substring = true).fetchSemanticsNodes().isNotEmpty())
        } finally {
            runBlocking { c.garage.update { it.copy(health = it.health.filter { h -> h.vehicleId != "vEdit" }) } }
            tearDown(before)
        }
    }

    @Test fun aRefuelSeenByTheTankGaugeIsProposed() {
        val before = setUp()
        try {
            runBlocking { c.settings.update { it.copy(fuelPrices = false, fuelPricePerL = 1.5f) } }
            c.session.refuelForTest(com.openobd.session.ObdSession.RefuelDetected("vEdit", System.currentTimeMillis(), 38.4, true, 123_456.0))
            waitFor { c.refuelSuggestions.pending.value?.vehicleId == "vEdit" }
            val sg = c.refuelSuggestions.pending.value!!
            assertEquals(38.4, sg.litres, 1e-9)
            assertEquals(1.5, sg.pricePerL!!, 1e-9)
            assertTrue(sg.full)
            // Home offers it (logging it opens the fill-up dialog: checked on a device); it can be dismissed.
            c.openRequest.value = Routes.HOME
            waitFor { rule.onAllNodesWithTag("refuelSuggestion").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(rule.onAllNodesWithText("Refuel detected").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithTag("refuelDismiss").performScrollTo().performClick()
            waitFor { c.refuelSuggestions.pending.value == null }
            // A fill-up already logged by hand around then: nothing is proposed.
            runBlocking { c.garage.upsertFuel(com.openobd.core.garage.FuelEntry("f1", "vEdit", System.currentTimeMillis(), 123_456.0, 40.0, 60.0)) }
            c.session.refuelForTest(com.openobd.session.ObdSession.RefuelDetected("vEdit", System.currentTimeMillis(), 30.0, false, null))
            Thread.sleep(500)
            assertTrue(c.refuelSuggestions.pending.value == null)
        } finally {
            c.refuelSuggestions.clear()
            runBlocking { c.garage.update { it.copy(fuel = it.fuel.filter { f -> f.vehicleId != "vEdit" }) } }
            runBlocking { c.settings.update { it.copy(fuelPricePerL = 0f) } }
            tearDown(before)
        }
    }

    @Test fun theFilterValuesAreFoundFromAnotherAppsFigures() {
        val before = setUp()
        try {
            c.session.connect()
            waitFor(30_000) { c.session.state.value is ConnectionState.Connected }
            c.session.finder.rangesForTest = listOf(com.openobd.core.brand.IdKind.UDS to 0x1900..0x190F)
            c.openRequest.value = Routes.VALUE_FINDER
            waitFor { rule.onAllNodesWithTag("finderValue").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("finderValue").performTextInput("37,4")
            rule.onNodeWithTag("finderSearch").performClick()
            waitFor(60_000) { c.session.finder.state.value.let { !it.scanning && it.candidates.isNotEmpty() } }
            val best = c.session.finder.state.value.candidates.first()
            rule.onNodeWithTag("cand_${best.key}").performScrollTo().performClick()
            waitFor { rule.onAllNodesWithText("It reads now: 37.4", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("finderSave").performScrollTo().performClick()
            waitFor { c.garage.data.value.active!!.customPids.any { it.id == "gm_dpf_soot" } }
        } finally {
            c.session.finder.rangesForTest = null
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
            tearDown(before)
        }
    }
}
