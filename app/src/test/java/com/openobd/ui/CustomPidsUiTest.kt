package com.openobd.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.core.brand.CustomPid
import com.openobd.core.garage.withUniqueIds
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Hundreds of imported PIDs: unique ids, the whole list scrolls, search and filters narrow it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CustomPidsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @Test
    fun manyImportedPids() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en") } }
        waitFor { c.garage.loaded && I18n.language == "en" }
        // Created in a burst, like an OBDb or pack import: ids must not repeat.
        val groups = listOf("Engine", "Battery", "Tires")
        val pids = (0 until 600).map { i ->
            CustomPid(c.garage.newId(), "Value $i", "V$i", "22%04X".format(0x1000 + i), "A", 0.0, 255.0, "", if (i % 2 == 0) "7E0" else "7E1",
                source = "OBDb (CC BY-SA 4.0) · " + groups[i % 3])
        }
        assertEquals(600, pids.map { it.id }.toSet().size)
        runBlocking { c.garage.updateActive { it.copy(customPids = pids) } }

        rule.onAllNodes(hasText("More") and isSelectable())[0].performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText("Custom PIDs")) }
        }
        rule.onAllNodes(hasText("Custom PIDs") and hasClickAction())[0].performClick()
        waitFor { rule.onAllNodesWithText("Value 0").fetchSemanticsNodes().isNotEmpty() }

        // The whole list scrolls to the end without crashing.
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Value 599"))
        assertTrue(rule.onAllNodesWithText("Value 599").fetchSemanticsNodes().isNotEmpty())

        // Search
        rule.onAllNodesWithContentDescription("Search")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasTestTag("pidSearch"))
        rule.onNodeWithTag("pidSearch").performTextInput("Value 59")
        waitFor { rule.onAllNodesWithText("Showing 11 of 600").fetchSemanticsNodes().isNotEmpty() }

        // Filter by category and module
        rule.onAllNodesWithText("Show all")[0].performClick()
        rule.onAllNodesWithContentDescription("Filter")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Battery · 200") and hasClickAction())[0].performClick()
        waitFor { rule.onAllNodesWithText("Showing 200 of 600").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("7E1 · 300") and hasClickAction())[0].performClick()
        waitFor { rule.onAllNodesWithText("Showing 100 of 600").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun repeatedIdsAreRepaired() {
        val p = CustomPid("same", "A", "A", "220101", "A")
        var n = 0
        val v = com.openobd.core.garage.Vehicle("v", "Car", customPids = listOf(p, p.copy(name = "B"), p.copy(name = "C")))
        val fixed = v.withUniqueIds { "new${n++}" }
        assertEquals(listOf("same", "new0", "new1"), fixed.customPids.map { it.id })
        assertTrue(fixed.withUniqueIds { "x" } === fixed)
    }
}
