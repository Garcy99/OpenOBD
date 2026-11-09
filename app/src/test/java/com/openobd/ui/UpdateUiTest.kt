package com.openobd.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.core.update.Release
import com.openobd.data.Updater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A newer version on GitHub pops up, downloads and opens Android's installer; "Later" puts it away. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdateUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val newer = Release("99.0.0", "https://example.org/OpenOBD-v99.0.0.apk", 3_000_000,
        "## What's new since v1.16.1\n• Car screen bridge\n\n## Novedades desde v1.16.1\n• Puente", "https://example.org/release")

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @Test
    fun offersDownloadsAndInstalls() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(language = "en", onboarded = true, tourSeen = true) } }
        waitFor { I18n.language == "en" }
        c.updater.latest = { newer }
        c.updater.download = { _, file, progress -> file.writeBytes(ByteArray(1000)); progress(1000, 1000) }
        shadowOf(rule.activity.packageManager).setCanRequestPackageInstalls(true)

        runBlocking { c.updater.check(manual = true) }
        waitFor { rule.onAllNodesWithTag("updateDialog").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("• Car screen bridge").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("updateNow").performClick()
        waitFor { c.updater.state.value is Updater.State.Ready }
        // Android's installer opens with the downloaded APK.
        val shadow = shadowOf(rule.activity.application)
        waitFor { shadow.peekNextStartedActivity()?.action == Intent.ACTION_INSTALL_PACKAGE }
        val install = shadow.nextStartedActivity
        assertEquals("application/vnd.android.package-archive", install.type)
        assertTrue(install.data.toString().endsWith("OpenOBD-99.0.0.apk"))

        rule.onNodeWithTag("updateLater").performClick()
        waitFor { rule.onAllNodesWithTag("updateDialog").fetchSemanticsNodes().isEmpty() }

        // Already up to date: About says so instead of popping up.
        c.updater.latest = { newer.copy(version = "1.0.0") }
        runBlocking { c.updater.check(manual = true) }
        assertTrue(c.updater.state.value is Updater.State.UpToDate)
        // A skipped version is not offered again by the daily check.
        c.updater.latest = { newer }
        runBlocking { c.updater.check(manual = true) }
        waitFor { rule.onAllNodesWithTag("updateDialog").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Skip this version")[0].performClick()
        waitFor { c.updater.state.value is Updater.State.Idle }
    }

    @Test
    fun automaticCheckPopsUpAndRetriesWithoutSignal() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(language = "en", onboarded = true, tourSeen = true) } }
        waitFor { I18n.language == "en" }
        val prefs = rule.activity.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // No signal: nothing shows, and the next automatic check is not put off for hours.
        c.updater.latest = { null }
        runBlocking { c.updater.check() }
        assertTrue(c.updater.state.value is Updater.State.Idle)
        assertEquals(0L, prefs.getLong("last", 0))
        // Signal again (the retry pause over): the new version pops up by itself.
        prefs.edit().putLong("tried", 0).commit()
        c.updater.latest = { newer }
        runBlocking { c.updater.check() }
        waitFor { rule.onAllNodesWithTag("updateDialog").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("updateLater").performClick()
        waitFor { rule.onAllNodesWithTag("updateDialog").fetchSemanticsNodes().isEmpty() }
        // "Later": not again on the next opening of the app within hours.
        runBlocking { c.updater.check() }
        assertTrue(c.updater.state.value is Updater.State.Idle)
    }
}
