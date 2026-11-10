package com.openobd.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import com.openobd.session.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** On a landscape car screen the gauges fill it in balanced rows, oil temperature included. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class DashboardLayoutUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val c get() = (rule.activity.application as OpenObdApp).container
    private fun waitFor(timeoutMs: Long = 40_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @After fun tearDown() { c.session.disconnect() }

    @Test fun gaugesFillTheCarScreen() {
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", onboarded = true, tourSeen = true, tripGps = false, fuelPrices = false) } }
        waitFor { c.garage.loaded && I18n.language == "en" && c.settings.settings.value.oilGaugesAdded }
        // Dashboards from before gain the oil gauges once.
        assertTrue(c.settings.settings.value.dashboard.any { it.startsWith("OIL_TEMP") } && c.settings.settings.value.dashboard.any { it.startsWith("OIL_PRESS") })
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected }
        rule.onAllNodes(hasText("Live") and isSelectable())[0].performClick()
        waitFor { rule.onAllNodesWithTag("fittedGauges").fetchSemanticsNodes().isNotEmpty() }
        // The demo car reports PID 5C: the oil temperature gauge reads it.
        waitFor { rule.onAllNodesWithText("Oil temp").fetchSemanticsNodes().isNotEmpty() }
        waitFor { c.session.live.latestValue("015C") != null }
        // The fuel tank gauge joins too, and reads the demo car's level (steadied) as soon as there are a few readings.
        waitFor { c.settings.settings.value.tankGaugeAdded && c.settings.settings.value.dashboard.any { it.startsWith("CALC_TANK") } }
        waitFor(60_000) { c.session.live.latestValue("CALC_TANK") != null }
        assertTrue(c.session.live.latestValue("CALC_TANK")!! in 0.0..100.0)
        waitFor { rule.onAllNodesWithText("Fuel tank").fetchSemanticsNodes().isNotEmpty() }
    }

    /** A car screen whose climate bar covers the bottom: the space set aside in Settings stays free of the app. */
    @Test fun roomForTheCarsOwnControls() {
        runBlocking { c.settings.update { it.copy(language = "en", onboarded = true, tourSeen = true, marginBottomDp = 100, marginLeftDp = 0) } }
        waitFor { c.settings.settings.value.marginBottomDp == 100 && I18n.language == "en" }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val content = rule.onNodeWithTag("appContent").fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        assertEquals(root.bottom - 100 * density, content.bottom, 1f)
        // The tab rail and the screens all fit above it.
        rule.onAllNodesWithText("Home").fetchSemanticsNodes().forEach { assertTrue(it.boundsInRoot.bottom <= content.bottom + 0.5f) }
        runBlocking { c.settings.update { it.copy(marginBottomDp = 0) } }
    }
}
