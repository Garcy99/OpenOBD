package com.openobd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.core.brand.Coding
import com.openobd.core.brand.CodingOption
import com.openobd.core.brand.WriteRisk
import com.openobd.core.brand.WriteStatus
import com.openobd.data.ConnectionType
import com.openobd.data.ThemeMode
import com.openobd.session.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Codings on the demo car: a safe one is written straight away, a dangerous one asks twice; both can be undone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CodingsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
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

    private fun scrollTo(text: String) {
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText(text)) }
        }
    }

    private fun click(text: String) { rule.onAllNodes(hasText(text) and hasClickAction())[0].performClick(); rule.waitForIdle() }

    @Test
    fun safeAndDangerousCodings() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", tripGps = false, theme = ThemeMode.DARK) } }
        waitFor { c.settings.settings.value.connectionType == ConnectionType.DEMO && I18n.language == "en" }
        waitFor { c.garage.loaded }
        runBlocking {
            c.garage.updateActive { v ->
                v.copy(codings = listOf(
                    Coding("drl", "Daytime running lights", header = "726", did = "0101", byte = 0, mask = 0x04,
                        options = listOf(CodingOption("DRL off", "00"), CodingOption("DRL on", "04")), risk = WriteRisk.SAFE, tested = true),
                    Coding("sweep", "Needle sweep", header = "720", did = "0600", byte = 9, mask = 0x40,
                        options = listOf(CodingOption("Sweep off", "00"), CodingOption("Sweep on", "40")), warning = "The cluster restarts."),
                ))
            }
        }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected }

        rule.onAllNodes(hasText("Diagnose") and isSelectable())[0].performClick()
        rule.waitForIdle()
        scrollTo("ABS, airbag, body, EV battery modules · manufacturer codes · brand PIDs")
        rule.onAllNodesWithText("ABS, airbag, body, EV battery modules · manufacturer codes · brand PIDs")[0].performClick()
        rule.waitForIdle()
        scrollTo("Open codings (2)")
        click("Open codings (2)")

        // Safe: no popup, written at once.
        click("DRL off")
        waitFor { c.garage.data.value.writes.any { it.status == WriteStatus.APPLIED } }
        assertTrue(rule.onAllNodesWithText("Before you change this").fetchSemanticsNodes().isEmpty())
        val first = c.garage.data.value.writes.single()
        assertEquals("050010", first.before)
        assertEquals("010010", first.after)

        // Dangerous: explanation, then confirmation.
        click("Sweep off")
        waitFor { rule.onAllNodesWithText("Before you change this").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("The cluster restarts.").fetchSemanticsNodes().isNotEmpty())
        shot("40_coding_explain")
        assertTrue(c.garage.data.value.writes.size == 1)
        click("I understand, continue")
        waitFor { rule.onAllNodesWithText("Confirm the change").fetchSemanticsNodes().isNotEmpty() }
        click("Write now")
        waitFor { c.garage.data.value.writes.count { it.status == WriteStatus.APPLIED } == 2 }
        shot("41_codings")

        // Undo the first change from the history.
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasTestTag("undo_${first.id}"))
        rule.onNodeWithTag("undo_${first.id}").performClick()
        rule.waitForIdle()
        waitFor { rule.onAllNodesWithText("Undo this change?").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("confirmUndo").performClick()
        waitFor { c.garage.data.value.writes.first().status == WriteStatus.UNDONE }
        shot("42_codings_history")
        c.session.disconnect()
    }
}
