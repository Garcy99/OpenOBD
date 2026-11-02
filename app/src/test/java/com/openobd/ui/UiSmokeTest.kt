package com.openobd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import com.openobd.data.ThemeMode
import com.openobd.session.ConnectionState
import com.openobd.session.Op
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app against the built-in demo car and renders every main screen.
 * Screenshots are written to app/build/screenshots for visual review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = rule.activity.application as OpenObdApp

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
        rule.activityRule.scenario.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeBy(50)
        }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        if (rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText(text)) }
            }
            rule.waitForIdle()
        }
        val merged = rule.onAllNodesWithText(text)
        if (merged.fetchSemanticsNodes().isNotEmpty()) merged.onFirst().performClick()
        else rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]

    @Test
    fun demoTourDark() = tour(ThemeMode.DARK, "dark")

    private fun tour(theme: ThemeMode, suffix: String) {
        val c = app.container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = theme) } }
        waitFor { c.settings.settings.value.theme == theme }
        shot("01_home_disconnected_$suffix")

        tap("Connect")
        waitFor { c.session.state.value is ConnectionState.Connected }
        waitFor { c.session.dtcScan.value is Op.Done }
        shot("02_home_connected_$suffix")

        tap("Dashboard")
        waitFor { c.session.live.readings.value.containsKey("010C") && c.session.live.readings.value.containsKey("0105") }
        Thread.sleep(1500)
        shot("03_dashboard_$suffix")

        tap("Live data")
        waitFor { c.session.live.readings.value.size > 15 }
        Thread.sleep(1000)
        shot("04_live_$suffix")

        tap("Chart (3)")
        Thread.sleep(2500)
        shot("05_chart_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("Codes")
        tap("Scan")
        waitFor { c.session.dtcScan.value is Op.Done }
        shot("06_codes_$suffix")
        assertTrue(rule.onAllNodesWithText("P0301").fetchSemanticsNodes().isNotEmpty())

        tap("P0301")
        shot("07_code_detail_$suffix")
        tap("Close")

        tap("More")
        shot("08_more_$suffix")

        tap("Emission readiness (I/M)")
        waitFor { c.session.readiness.value is Op.Done }
        shot("09_readiness_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("Vehicle information")
        waitFor { c.session.vehicleInfo.value is Op.Done }
        shot("10_vehicle_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("Freeze frame")
        waitFor { c.session.freezeFrame.value is Op.Done }
        shot("11_freeze_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("On-board monitor tests (Mode 06)")
        waitFor { c.session.monitorTests.value is Op.Done }
        shot("12_mode06_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("Trip computer")
        Thread.sleep(1500)
        shot("13_trip_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("Terminal")
        shot("14_terminal_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("Settings")
        shot("15_settings_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        // Clear codes end to end
        tap("Codes")
        tap("Clear codes")
        rule.onAllNodesWithText("Clear codes").fetchSemanticsNodes() // dialog shown
        rule.onAllNodes(hasText("Clear codes"))[1].performClick()
        waitFor { c.session.clearResult.value is Op.Done }
        waitFor { (c.session.dtcScan.value as? Op.Done)?.value?.codes?.none { it.code == "P0301" } == true }
        shot("16_codes_cleared_$suffix")

        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected }
    }
}
