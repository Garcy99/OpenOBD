package com.openobd.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
        val trip = TripRecord(id = "trip1", vehicleId = car.id, start = points.first().t, end = points.last().t, distanceKm = 3.2, fuelL = 0.2, maxSpeed = 149.0, avgSpeed = 60.0, trackPoints = points.size)
        runBlocking { c.garage.saveTrip(trip, points) }

        rule.onAllNodes(hasText("Garage") and isSelectable())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Trips") and hasClickAction())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("km", substring = true) and hasClickAction())[0].performClick()
        waitFor { rule.onAllNodesWithContentDescription("Export route").fetchSemanticsNodes().isNotEmpty() }

        val reports = File(rule.activity.cacheDir, "reports")
        val shadow = shadowOf(rule.activity.application)
        fun openDialog() {
            rule.onAllNodesWithContentDescription("Export route")[0].performClick()
            waitFor { rule.onAllNodesWithText("Export route").fetchSemanticsNodes().isNotEmpty() }
        }
        fun shared(ext: String, mime: String): File {
            waitFor { reports.listFiles().orEmpty().any { it.name.endsWith(".$ext") } && shadow.peekNextStartedActivity() != null &&
                rule.onAllNodesWithText("Export route").fetchSemanticsNodes().isEmpty() }
            val chooser = shadow.nextStartedActivity
            val sent = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
            assertEquals(mime, sent.type)
            return reports.listFiles()!!.first { it.name.endsWith(".$ext") }.also { assertTrue("$ext is empty", it.length() > 500) }
        }
        // A fake slope instead of downloading elevation tiles.
        c.terrain = { _, _, _ -> com.openobd.core.trip.Terrain { lat, lon -> 600 + 3000 * (lat - 40.4) + 2000 * (lon + 3.7) } }
        // The PDF needs Android's native PDF engine, which Robolectric lacks: the device tests cover it (DemoSessionTest).
        for ((ext, mime) in listOf("gpx" to "application/gpx+xml", "html" to "text/html", "svg" to "image/svg+xml")) {
            openDialog()
            rule.onNodeWithTag("export_$ext").performClick()
            val file = shared(ext, mime)
            if (ext == "svg") assertTrue("poster has relief", file.readText().contains("data:image/png;base64,"))
        }

        // 3D: a title, a circle, terrain (the fake slope) and the title on a plaque.
        // The trip's name is the title of every export.
        rule.onNodeWithTag("tripName").performTextReplacement("Sierra loop")
        rule.onNodeWithContentDescription("Save").performClick()
        waitFor { c.garage.data.value.trips.first { it.id == "trip1" }.name == "Sierra loop" }
        openDialog()
        assertTrue(rule.onAllNodesWithText("Sierra loop").fetchSemanticsNodes().isNotEmpty())
        // A lake and a road from the "map" (OpenStreetMap is not reached in tests).
        c.mapFeatures = { area, _ ->
            val lat = (area[0] + area[2]) / 2; val lon = (area[1] + area[3]) / 2
            com.openobd.core.trip.MapFeatures(
                listOf(listOf(com.openobd.core.trip.LatLon(lat - 0.002, lon - 0.003), com.openobd.core.trip.LatLon(lat - 0.002, lon + 0.003), com.openobd.core.trip.LatLon(lat + 0.002, lon))),
                listOf(com.openobd.core.trip.MapRoad((0..10).map { com.openobd.core.trip.LatLon(area[0] + (area[2] - area[0]) * it / 10, lon) }, 1)), emptyList(),
            )
        }
        rule.onNodeWithTag("export_3mf").performClick()
        waitFor { rule.onAllNodesWithText("3D model").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("shape_CIRCLE").performScrollTo().performClick()
        rule.onNodeWithTag("theme_DESERT").performScrollTo().performClick()
        rule.onNodeWithTag("coordsSwitch").performScrollTo().performClick()
        // The filament list follows the model: plinth first, then the desert colours, water and roads.
        waitFor(60_000) { rule.onAllNodesWithTag("filament_1").fetchSemanticsNodes().isNotEmpty() &&
            rule.onAllNodesWithText("Water", substring = true).fetchSemanticsNodes().size >= 2 }
        rule.onNodeWithTag("make3d").performScrollTo()
        waitFor(60_000) { runCatching { rule.onNodeWithTag("make3d").assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag("make3d").performClick()
        val model = shared("3mf", "model/3mf")
        val xml = java.util.zip.ZipFile(model).use { z -> z.getInputStream(z.getEntry("3D/3dmodel.model")).readBytes().toString(Charsets.UTF_8) }
        assertTrue(xml.contains("name=\"Terrain\"") && xml.contains("name=\"Roads\"") && xml.contains("name=\"Plaque\"") && xml.contains("name=\"Title\"") && xml.contains("Sierra loop") && xml.contains("paint_color="))
        assertTrue("desert colours and water", xml.contains(com.openobd.core.trip.Route3mf.Theme.DESERT.low) && xml.contains(com.openobd.core.trip.Route3mf.Theme.DESERT.water))
        assertTrue(model.name.startsWith("OpenOBD "))
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
