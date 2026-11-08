package com.openobd.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Trip detail > Export route: every format is written and handed to the share sheet. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RouteExportUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    private val points = (0 until 120).map { i ->
        val a = i / 120.0 * Math.PI
        TrackPoint(1_762_600_000_000L + i * 2000L, 40.4 + 0.01 * sin(a), -3.7 + 0.02 * cos(a), (30 + i).toFloat(), 2000f, 6f, 88f, 20f, 0.5f, (600 + i).toFloat())
    }

    @org.junit.Before
    fun before() = clearFileProviderCache()

    @Test
    fun exportsEveryFormat() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en") } }
        waitFor { c.garage.loaded && I18n.language == "en" }
        val car = c.garage.data.value.active!!
        val trip = TripRecord("trip1", car.id, points.first().t, points.last().t, 3.2, fuelL = 0.2, maxSpeed = 149.0, avgSpeed = 60.0, trackPoints = points.size)
        runBlocking { c.garage.saveTrip(trip, points) }

        rule.onAllNodes(hasText("Garage") and isSelectable())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Trips") and hasClickAction())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("km", substring = true) and hasClickAction())[0].performClick()
        waitFor { rule.onAllNodesWithContentDescription("Export route").fetchSemanticsNodes().isNotEmpty() }

        val reports = File(rule.activity.cacheDir, "reports")
        val shadow = shadowOf(rule.activity.application)
        // The PDF needs Android's native PDF engine, which Robolectric lacks: the device tests cover it (DemoSessionTest).
        for ((ext, mime) in listOf("gpx" to "application/gpx+xml", "html" to "text/html", "svg" to "image/svg+xml", "3mf" to "model/3mf")) {
            rule.onAllNodesWithContentDescription("Export route")[0].performClick()
            waitFor { rule.onAllNodesWithText("Export route").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("export_$ext").performClick()
            waitFor { reports.listFiles().orEmpty().any { it.name.endsWith(".$ext") } && rule.onAllNodesWithText("Export route").fetchSemanticsNodes().isEmpty() }
            val chooser = shadow.nextStartedActivity
            val sent = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            assertEquals(mime, sent.type)
            val file = reports.listFiles()!!.first { it.name.endsWith(".$ext") }
            assertTrue("$ext is empty", file.length() > 500)
        }
    }

}

/**
 * FileProvider caches the first app's folders in a static map, but every Robolectric test runs in its own temporary
 * folder: tests that share files clear it first, whatever order they run in.
 */
fun clearFileProviderCache() {
    androidx.core.content.FileProvider::class.java.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) }
        .forEach { f -> f.isAccessible = true; (f.get(null) as? MutableMap<*, *>)?.clear() }
}
