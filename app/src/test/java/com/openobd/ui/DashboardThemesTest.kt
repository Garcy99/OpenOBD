package com.openobd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import com.openobd.data.ThemeMode
import com.openobd.session.ConnectionState
import com.openobd.session.Op
import com.openobd.ui.components.GaugeSpec
import com.openobd.ui.components.GaugeStyle
import com.openobd.ui.components.GaugeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every dashboard theme with gauges in each style, connected to the demo car. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardThemesTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

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

    @Test
    fun themesAndStyles() {
        val c = (rule.activity.application as OpenObdApp).container
        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        val board = listOf(
            GaugeSpec("010C", max = 7000.0, warnAbove = 4500.0),
            GaugeSpec("010D", GaugeStyle.NEEDLE),
            GaugeSpec("0105", GaugeStyle.BAR, warnAbove = 105.0),
            GaugeSpec("CALC_BOOST", GaugeStyle.DIGITAL, decimals = 2),
            GaugeSpec("CALC_PEDAL", color = "E53935"),
            GaugeSpec("0142"),
        ).map { it.encode() }
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = ThemeMode.DARK, language = "en", tripGps = false, onboarded = true, dashboard = board) } }
        waitFor { I18n.language == "en" }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected && c.session.dtcScan.value is Op.Done }
        rule.onAllNodes(hasText("Live") and isSelectable())[0].performClick()
        waitFor { c.session.live.readings.value.containsKey("010C") && c.session.live.readings.value.containsKey("CALC_PEDAL") }
        for (t in GaugeTheme.entries) {
            runBlocking { c.settings.update { it.copy(dashboardTheme = t.id) } }
            waitFor { c.settings.settings.value.dashboardTheme == t.id }
            Thread.sleep(400); rule.mainClock.advanceTimeBy(400)
            shot("70_gauges_${t.id}")
        }
        // The boost gauge shows bar with 2 decimals
        assertTrue(rule.onAllNodes(hasText("bar", substring = true)).fetchSemanticsNodes().isNotEmpty())
        c.session.disconnect()
    }

    @Test
    fun customiseAGauge() {
        val c = (rule.activity.application as OpenObdApp).container
        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = ThemeMode.DARK, language = "en", tripGps = false, onboarded = true,
            dashboard = listOf("010C", "010D"), dashboardTheme = "neon") } }
        waitFor { I18n.language == "en" }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected }
        rule.onAllNodes(hasText("Live") and isSelectable())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Edit"))[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Customise gauge"))[0].performClick()
        waitFor { rule.onAllNodes(hasText("Customise gauge")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Needle"))[0].performClick()
        rule.onAllNodes(hasText("Max") and androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("6000")
        rule.onAllNodes(hasText("At or above") and androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("4500")
        rule.onAllNodes(hasText("Save") and androidx.compose.ui.test.hasClickAction())[0].also { runCatching { it.performScrollTo() } }.performClick()
        waitFor { GaugeSpec.parse(c.settings.settings.value.dashboard[0]).style == GaugeStyle.NEEDLE }
        val spec = GaugeSpec.parse(c.settings.settings.value.dashboard[0])
        assertTrue("$spec", spec.key == "010C" && spec.max == 6000.0 && spec.warnAbove == 4500.0)
        c.session.disconnect()
    }
}
