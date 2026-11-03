package com.openobd

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.openobd.core.elm.ObdProtocol
import com.openobd.data.ConnectionType
import com.openobd.session.ConnectionState
import com.openobd.session.Op
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Runs on real Android (emulators in CI, API 26 to 35): the demo car through the real UI, foreground
 * service, notifications, DataStore and file storage - the parts Robolectric only approximates.
 */
@RunWith(AndroidJUnit4::class)
class DemoSessionTest {
    private val permissions = GrantPermissionRule.grant(
        *listOfNotNull(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
        ).toTypedArray(),
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenObdApp
    private val c get() = app.container

    private fun waitFor(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) { condition() }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for $what (state ${c.session.state.value})\n" + c.diagnostics.log.format().takeLast(4000), e)
        }
        compose.waitForIdle()
    }

    private fun tap(text: String) {
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { compose.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText(text)) }
            }
        }
        val clickable = compose.onAllNodes(hasText(text) and hasClickAction())
        if (clickable.fetchSemanticsNodes().isNotEmpty()) clickable[0].also { runCatching { it.performScrollTo() } }.performClick()
        else compose.onAllNodesWithText(text)[0].performClick()
        compose.waitForIdle()
    }

    private fun tab(text: String) {
        compose.onAllNodes(hasText(text) and isSelectable())[0].performClick()
        compose.waitForIdle()
    }

    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun configure(protocol: ObdProtocol = ObdProtocol.ISO_15765_4_CAN_11_500, language: String = "en") = runBlocking {
        c.settings.update {
            it.copy(
                connectionType = ConnectionType.DEMO, language = language, autoConnect = false, tripGps = false,
                demoProtocol = protocol, demoFaults = false, protocol = ObdProtocol.AUTO,
            )
        }
        waitFor("settings") { c.settings.settings.value.language == language && c.settings.settings.value.demoProtocol == protocol }
    }

    @Before
    fun disconnected() {
        c.session.disconnect()
        waitFor("disconnect") { c.session.state.value !is ConnectionState.Connected }
    }

    @After
    fun disconnect() { c.session.disconnect() }

    @Test
    fun connectReadAndClearCodes() {
        configure()
        tap("Connect")
        waitFor("connection") { c.session.state.value is ConnectionState.Connected }
        waitFor("first scan") { c.session.dtcScan.value is Op.Done }

        // The foreground service posts its notification (needs the real service and notification stack).
        waitFor("service notification", 20_000) {
            app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 42 }
        }

        tab("Dashboard")
        waitFor("live data") { c.session.live.readings.value.containsKey("010C") && c.session.live.readings.value.containsKey("0105") }
        val rpm = c.session.live.latestValue("010C")!!
        assertTrue("rpm $rpm", rpm in 500.0..7000.0)

        tab("Codes")
        tap("Scan")
        waitFor("scan") { c.session.dtcScan.value is Op.Done }
        waitFor("P0301 on screen", 10_000) { visible("P0301") }

        tap("Clear codes")
        compose.onAllNodes(hasText("Clear codes") and hasClickAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitFor("clear") { c.session.clearResult.value is Op.Done }
        waitFor("codes gone", 20_000) { !visible("P0301") }

        // The debug report can be written to app storage and contains this session.
        val report = c.diagnostics.writeReport(c.session.debugSections(), includeCrash = false).readText()
        assertTrue(report.contains("> 04") && report.contains("Connection: DEMO"))

        tab("Vehicle")
        tap("Disconnect")
        waitFor("disconnected") { c.session.state.value is ConnectionState.Disconnected }
    }

    @Test
    fun olderCarOnKLine() {
        configure(ObdProtocol.ISO_9141_2)
        tap("Connect")
        waitFor("connection") { c.session.state.value is ConnectionState.Connected }
        val vehicle = (c.session.state.value as ConnectionState.Connected).vehicle
        assertEquals(ObdProtocol.ISO_9141_2, vehicle.elm.protocol)
        assertEquals("1HGCM82633A004352", vehicle.vin)
        tab("Dashboard")
        waitFor("live data") { c.session.live.readings.value.containsKey("010D") }
        tab("Codes")
        tap("Scan")
        waitFor("scan") { c.session.dtcScan.value is Op.Done }
        waitFor("P0420 on screen", 10_000) { visible("P0420") }
    }

    @Test
    fun spanishInterface() {
        configure(language = "es")
        waitFor("Spanish home", 20_000) { visible("Conectar") }
        tap("Conectar")
        waitFor("connection") { c.session.state.value is ConnectionState.Connected }
        tab("Códigos")
        waitFor("scan") { c.session.dtcScan.value is Op.Done }
        waitFor("P0301 on screen", 10_000) { visible("P0301") }
        configure(language = "en")
    }
}
