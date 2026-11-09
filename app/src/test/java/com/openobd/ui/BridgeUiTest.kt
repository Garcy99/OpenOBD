package com.openobd.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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

/** More → Car screen: the phone lends its adapter (the demo car here) and a car screen reads the car through it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BridgeUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val c get() = (rule.activity.application as OpenObdApp).container

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @After
    fun tearDown() { c.session.disconnect(); c.bridge.stop() }

    @Test
    fun phoneLendsTheAdapterToTheCarScreen() {
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", onboarded = true, tourSeen = true, tripGps = false) } }
        waitFor { c.garage.loaded && I18n.language == "en" }
        rule.onAllNodes(hasText("More") and isSelectable())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Car screen"))
        rule.onAllNodes(hasText("Car screen") and hasClickAction())[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("bridgeStart").performScrollTo().performClick()
        waitFor { c.bridge.state.value.adapter != null && c.bridge.state.value.port > 0 }
        assertTrue(rule.onAllNodesWithText("Waiting for the car screen").fetchSemanticsNodes().isNotEmpty())
        // While lending the adapter, this phone cannot connect to it itself.
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Failed }
        assertEquals(com.openobd.session.PHONE_BRIDGE_BUSY, (c.session.state.value as ConnectionState.Failed).message)

        // The car screen (here the same app, set to "Phone") finds the bridge and reads the car through it.
        val port = c.bridge.state.value.port
        c.session.findPhone = { "127.0.0.1" to port }
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.PHONE) } }
        c.session.connect()
        waitFor(40_000) { c.session.state.value is ConnectionState.Connected }
        val v = (c.session.state.value as ConnectionState.Connected).vehicle
        assertEquals("Phone bridge (127.0.0.1)", v.transport)
        assertTrue(v.supportedPids.isNotEmpty() && v.vin != null)
        waitFor { c.bridge.state.value.client != null && c.bridge.state.value.fromCar > 0 }
        assertTrue(rule.onAllNodesWithText("Connected (127.0.0.1)").fetchSemanticsNodes().isNotEmpty())

        c.session.disconnect()
        rule.onNodeWithTag("bridgeStop").performScrollTo().performClick()
        waitFor { !c.bridge.state.value.running }
    }
}
