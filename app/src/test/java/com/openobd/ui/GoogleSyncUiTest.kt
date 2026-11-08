package com.openobd.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import com.openobd.data.GoogleAuth
import com.openobd.data.MemoryDriveApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings > Google account and sync: connecting uploads this phone's garage to the Drive folder. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class GoogleSyncUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @Test fun connectAndSync() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en") } }
        waitFor { c.garage.loaded && I18n.language == "en" }
        val drive = MemoryDriveApi()
        c.sync.auth = object : GoogleAuth {
            override suspend fun authorize(context: Context) = GoogleAuth.Result("token", null)
            override fun fromConsent(context: Context, data: Intent?) = "token"
            override suspend fun revoke(token: String) {}
        }
        c.sync.driveFor = { drive }

        rule.onAllNodes(hasText("More") and isSelectable())[0].performClick()
        rule.waitForIdle()
        // The Settings entry may be below the fold of the More list.
        if (rule.onAllNodes(hasText("Settings") and hasClickAction()).fetchSemanticsNodes().isEmpty())
            rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText("Settings")) }
            }
        rule.onAllNodes(hasText("Settings") and hasClickAction())[0].also { runCatching { it.performScrollTo() } }.performClick()
        rule.waitForIdle()
        waitFor { rule.onAllNodesWithText("Google account and sync").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("googleSignIn").performScrollTo().performClick()
        waitFor { c.sync.state.value.connected && c.sync.state.value.lastSync > 0 }
        rule.onNodeWithTag("syncAccount").performScrollTo()
        assertTrue(rule.onAllNodesWithText("Connected as driver@example.com").fetchSemanticsNodes().isNotEmpty())
        assertTrue(drive.files.values.any { it.first.name == "garage.json" } && drive.files.values.any { it.first.name == "preferences.json" })
        rule.onNodeWithTag("syncNow").performClick()
        waitFor { !c.sync.state.value.syncing }
        assertTrue(c.sync.state.value.error == null)
    }
}
