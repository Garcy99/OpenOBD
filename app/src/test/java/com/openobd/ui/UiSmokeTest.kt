package com.openobd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performScrollTo
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
        val clickable = rule.onAllNodes(hasText(text) and hasClickAction())
        val merged = rule.onAllNodesWithText(text)
        if (clickable.fetchSemanticsNodes().isNotEmpty()) clickable.onFirst().also { runCatching { it.performScrollTo() } }.performClick()
        else if (merged.fetchSemanticsNodes().isNotEmpty()) merged.onFirst().performClick()
        else rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]

    /** Bottom navigation tabs are selectable; this avoids hitting same-named buttons elsewhere. */
    private fun tab(text: String) {
        rule.onAllNodes(hasText(text) and isSelectable())[0].performClick()
        rule.waitForIdle()
    }

    @Test
    fun demoTourDark() = tour(ThemeMode.DARK, "dark")

    private fun tour(theme: ThemeMode, suffix: String) {
        val c = app.container
        // Pretend the user granted the runtime permissions requested on Connect.
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN,
        )
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = theme) } }
        waitFor { c.settings.settings.value.theme == theme }
        shot("01_home_disconnected_$suffix")

        tap("Connect")
        waitFor { c.session.state.value is ConnectionState.Connected }
        waitFor { c.session.dtcScan.value is Op.Done }
        shot("02_home_connected_$suffix")

        tab("Dashboard")
        waitFor { c.session.live.readings.value.containsKey("010C") && c.session.live.readings.value.containsKey("0105") }
        Thread.sleep(1500)
        shot("03_dashboard_$suffix")

        tab("Live data")
        waitFor { c.session.live.readings.value.size > 15 }
        Thread.sleep(1000)
        shot("04_live_$suffix")

        tap("Chart (3)")
        Thread.sleep(2500)
        shot("05_chart_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tab("Codes")
        tap("Scan")
        waitFor { c.session.dtcScan.value is Op.Done }
        shot("06_codes_$suffix")
        assertTrue(rule.onAllNodesWithText("P0301").fetchSemanticsNodes().isNotEmpty())

        tap("P0301")
        shot("07_code_detail_$suffix")
        tap("Close")

        tab("More")
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
        tab("Codes")
        tap("Clear codes")
        rule.onAllNodesWithText("Clear codes").fetchSemanticsNodes() // dialog shown
        rule.onAllNodes(hasText("Clear codes"))[1].performClick()
        waitFor { c.session.clearResult.value is Op.Done }
        waitFor { (c.session.dtcScan.value as? Op.Done)?.value?.codes?.none { it.code == "P0301" } == true }
        shot("16_codes_cleared_$suffix")

        // Brand features: pick Ford, scan modules, open ABS, discover, custom PIDs
        tab("More")
        tap("Brand features & modules")
        shot("17_brand_$suffix")
        runBlocking {
            c.garage.updateActive {
                it.copy(brandId = "ford", customPids = listOf(com.openobd.core.brand.CustomPid("demo", "Transmission temperature", "TFT", "221E1C",
                    "((A*256)+B)/16", -40.0, 150.0, "°C", "7E1")))
            }
        }
        waitFor { c.session.brand.value?.id == "ford" }
        tap("Scan all ${c.session.modulesOnCurrentBus().size} modules")
        waitFor(30_000) { c.session.moduleScan.value is Op.Done }
        shot("18_modules_$suffix")
        tap("Anti-lock brake system")
        waitFor { rule.onAllNodesWithText("C0035-31").fetchSemanticsNodes().isNotEmpty() }
        shot("19_module_abs_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Discover modules")
        waitFor(60_000) { c.session.discovery.value is Op.Done }
        assertTrue((c.session.discovery.value as Op.Done).value.size == 6)
        shot("20_discovery_$suffix")
        // Manual HS/MS-CAN switch adapter flow
        runBlocking { c.settings.update { it.copy(msCanSwitch = true) } }
        waitFor { c.settings.settings.value.msCanSwitch }
        tap("Switch to MS-CAN")
        tap("Continue")
        waitFor { c.session.bus.value == com.openobd.session.Bus.MS }
        waitFor { rule.onAllNodesWithText("3 of 6 known modules", substring = true).fetchSemanticsNodes().isNotEmpty() }
        tap("Scan all 6 modules")
        waitFor(30_000) { c.session.moduleScan.value is Op.Done }
        shot("22_ms_can_$suffix")
        tap("Return to HS-CAN")
        tap("Continue")
        waitFor { c.session.bus.value == com.openobd.session.Bus.HS }
        waitFor { c.session.live.rate.value > 0 || c.session.live.readings.value.isNotEmpty() }

        tap("Manage custom PIDs")
        waitFor { c.session.live.readings.value.containsKey("X_demo") }
        shot("21_custom_pids_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected }

        // The drive was saved as a trip (with a simulated GPS track in demo mode)
        waitFor { c.garage.data.value.trips.isNotEmpty() }
        val vid = c.garage.data.value.active!!.id
        runBlocking {
            c.garage.upsertMaintenance(com.openobd.core.garage.MaintenanceItem("m1", vid, "Engine oil & filter", 10_000, 12, lastKm = 120_000.0,
                lastDate = System.currentTimeMillis() - 200L * 86_400_000))
            c.garage.upsertMaintenance(com.openobd.core.garage.MaintenanceItem("m2", vid, "Brake fluid", null, 24, lastDate = System.currentTimeMillis() - 740L * 86_400_000))
            c.garage.upsertFuel(com.openobd.core.garage.FuelEntry("f1", vid, System.currentTimeMillis() - 20L * 86_400_000, 128_100.0, 42.0, 67.2))
            c.garage.upsertFuel(com.openobd.core.garage.FuelEntry("f2", vid, System.currentTimeMillis() - 8L * 86_400_000, 128_620.0, 36.4, 58.6))
            c.garage.upsertFuel(com.openobd.core.garage.FuelEntry("f3", vid, System.currentTimeMillis() - 1L * 86_400_000, 129_100.0, 33.1, 52.0))
        }
        tab("Vehicle")
        shot("23_home_garage_$suffix")
        tap("Service")
        shot("24_maintenance_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Fuel")
        shot("25_fuel_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Trips")
        shot("26_trips_$suffix")
        rule.onAllNodes(hasText("km", substring = true) and hasClickAction())[0].performClick()
        Thread.sleep(500)
        shot("27_trip_detail_$suffix")
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetTest {
    @Test
    fun rendersWidget() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<OpenObdApp>()
        val c = app.container
        runBlocking {
            c.garage.load(null)
            val id = c.garage.data.value.active!!.id
            c.garage.updateActive { it.copy(name = "Widget test car", odometerKm = 50_000.0) }
            c.garage.upsertMaintenance(com.openobd.core.garage.MaintenanceItem("w", id, "Engine oil", 10_000, null, lastKm = 40_500.0))
        }
        val views = com.openobd.widget.CarWidget.build(app, c)
        val root = views.apply(app, android.widget.FrameLayout(app))
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 400)
        val text = (0 until (root as android.view.ViewGroup).childCount).flatMap { i -> collectText(root.getChildAt(i)) }
        assertTrue(text.toString(), text.contains("Widget test car"))
        assertTrue(text.toString(), text.any { it.startsWith("Due soon: Engine oil") }) // due at 50,500 km
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "28_widget.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun collectText(v: android.view.View): List<String> = when (v) {
        is android.widget.TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { collectText(v.getChildAt(it)) }
        else -> emptyList()
    }
}
