package com.openobd.ui

import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.ui.components.Donate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The optional donation card: only with a donation link, never forced, opens the page in the browser. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DonateUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun reset() { Donate.url = "" }

    private fun openMore() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(language = "en", onboarded = true) } }
        rule.waitUntil(10_000) { I18n.language == "en" }
        rule.onAllNodes(hasText("More") and isSelectable())[0].performClick()
        rule.waitForIdle()
    }

    @Test
    fun noLinkNoButton() {
        Donate.url = ""
        openMore()
        assertTrue(rule.onAllNodesWithText("Support OpenOBD").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun opensTheDonationPage() {
        Donate.url = "https://example.org/donate"
        openMore()
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Donate"))
        assertTrue(rule.onAllNodesWithText("Support OpenOBD").fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(hasText("Donate") and hasClickAction())[0].performClick()
        val started = Shadows.shadowOf(rule.activity.application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.org/donate", started.dataString)
    }
}
