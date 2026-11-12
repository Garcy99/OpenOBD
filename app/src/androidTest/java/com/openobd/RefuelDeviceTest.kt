package com.openobd

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.data.ConnectionType
import com.openobd.session.ObdSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A refuel the tank gauge noticed opens in the fuel log ready to confirm, and one tap logs it. */
@RunWith(AndroidJUnit4::class)
class RefuelDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val c get() = (compose.activity.application as OpenObdApp).container

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(100) }
    }

    @Test
    fun refuelIsLoggedWithOneTap() {
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", onboarded = true, tourSeen = true, autoConnect = false, fuelPrices = false, fuelPricePerL = 1.5f) } }
        waitFor { c.garage.loaded }
        val car = c.garage.data.value.active!!.id
        val before = c.garage.data.value.fuel.map { it.id }.toSet()
        try {
            c.session.refuelForTest(ObdSession.RefuelDetected(car, System.currentTimeMillis(), 38.4, true, 123_456.0))
            waitFor { c.refuelSuggestions.pending.value != null }
            compose.runOnUiThread { c.openRequest.value = com.openobd.ui.Routes.FUEL }
            waitFor { compose.onAllNodesWithText("38.40", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Save") and hasClickAction()).performClick()
            waitFor { c.garage.data.value.fuel.any { it.id !in before } }
            val e = c.garage.data.value.fuel.first { it.id !in before }
            assertEquals(38.4, e.liters, 0.01)
            assertEquals(57.6, e.totalCost, 0.01)
            assertTrue(e.fullTank)
            waitFor { c.refuelSuggestions.pending.value == null }
        } finally {
            c.refuelSuggestions.clear()
            runBlocking {
                c.garage.update { d -> d.copy(fuel = d.fuel.filter { it.id in before }) }
                c.settings.update { it.copy(fuelPricePerL = 0f) }
            }
        }
    }
}
