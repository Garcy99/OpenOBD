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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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

    /** The main tabs in the light theme, connected to the demo car. */
    @Test
    fun lightThemeTabs() {
        val c = app.container
        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = ThemeMode.LIGHT, language = "en", tripGps = false, onboarded = true) } }
        waitFor { c.settings.settings.value.theme == ThemeMode.LIGHT && I18n.language == "en" }
        shot("50_home_light_disconnected")
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected && c.session.dtcScan.value is Op.Done }
        shot("51_home_light")
        tab("Live")
        waitFor { c.session.live.readings.value.containsKey("010C") }
        Thread.sleep(1500)
        shot("52_live_light")
        tab("Diagnose")
        shot("53_diagnose_light")
        tab("Garage")
        shot("54_garage_light")
        tab("More")
        shot("55_more_light")
        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected }
    }

    private fun tour(theme: ThemeMode, suffix: String) {
        val c = app.container
        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        // Pretend the user granted the runtime permissions requested on Connect.
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN,
        )
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = theme, language = "en") } }
        waitFor { c.settings.settings.value.theme == theme && I18n.language == "en" }
        shot("01_home_disconnected_$suffix")

        tap("Connect")
        waitFor { c.session.state.value is ConnectionState.Connected }
        waitFor { c.session.dtcScan.value is Op.Done }
        shot("02_home_connected_$suffix")

        tab("Live")
        waitFor { c.session.live.readings.value.containsKey("010C") && c.session.live.readings.value.containsKey("0105") }
        Thread.sleep(1500)
        shot("03_dashboard_$suffix")

        tab("Sensors")
        waitFor { c.session.live.readings.value.size > 15 }
        Thread.sleep(1000)
        shot("04_live_$suffix")

        tap("Chart (3)")
        Thread.sleep(2500)
        shot("05_chart_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tab("Trip")
        Thread.sleep(1500)
        shot("13_trip_$suffix")
        tab("Gauges")

        tab("Diagnose")
        tap("Scan again")
        waitFor { c.session.dtcScan.value is Op.Done }
        shot("06_codes_$suffix")
        assertTrue(
            "scan: ${(c.session.dtcScan.value as? Op.Done)?.value}\n" + c.diagnostics.log.format().takeLast(5000),
            rule.onAllNodesWithText("P0301").fetchSemanticsNodes().isNotEmpty(),
        )

        tap("P0301")
        waitFor { rule.onAllNodesWithText("Can I keep driving?").fetchSemanticsNodes().isNotEmpty() }
        shot("07_code_detail_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

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

        tab("More")
        shot("08_more_$suffix")

        tap("Terminal")
        shot("14_terminal_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        tap("Settings")
        shot("15_settings_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        // Clear codes end to end
        tab("Diagnose")
        tap("Clear codes")
        rule.onAllNodesWithText("Clear codes").fetchSemanticsNodes() // dialog shown
        rule.onAllNodes(hasText("Clear codes"))[1].performClick()
        waitFor { c.session.clearResult.value is Op.Done }
        waitFor { (c.session.dtcScan.value as? Op.Done)?.value?.codes?.none { it.code == "P0301" } == true }
        shot("16_codes_cleared_$suffix")

        // Brand features: pick Ford, scan modules, open ABS, discover, custom PIDs
        tab("More")
        tap(c.session.brand.value?.let { "${it.name} features" } ?: "Brand features & modules")
        shot("17_brand_$suffix")
        runBlocking {
            c.garage.updateActive {
                it.copy(brandId = "ford", customPids = listOf(com.openobd.core.brand.CustomPid("demo", "Transmission temperature", "TFT", "221E1C",
                    "((A*256)+B)/16", -40.0, 150.0, "°C", "7E1")))
            }
        }
        waitFor { c.session.brand.value?.id == "ford" }
        tap("Scan all ${c.session.modules.modulesOnCurrentBus().size} modules")
        waitFor(30_000) { c.session.modules.moduleScan.value is Op.Done }
        shot("18_modules_$suffix")
        tap("Anti-lock brake system")
        waitFor { rule.onAllNodesWithText("C0035-31").fetchSemanticsNodes().isNotEmpty() }
        shot("19_module_abs_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Discover modules")
        waitFor(60_000) { c.session.modules.discovery.value is Op.Done }
        assertTrue((c.session.modules.discovery.value as Op.Done).value.size == 6)
        shot("20_discovery_$suffix")
        // Manual HS/MS-CAN switch adapter flow
        runBlocking { c.settings.update { it.copy(msCanSwitch = true) } }
        waitFor { c.settings.settings.value.msCanSwitch }
        tap("Switch to MS-CAN")
        tap("Continue")
        waitFor { c.session.modules.bus.value == com.openobd.session.Bus.MS }
        waitFor { rule.onAllNodesWithText("3 of 6 known modules", substring = true).fetchSemanticsNodes().isNotEmpty() }
        tap("Scan all 6 modules")
        waitFor(30_000) { c.session.modules.moduleScan.value is Op.Done }
        shot("22_ms_can_$suffix")
        tap("Return to HS-CAN")
        tap("Continue")
        waitFor { c.session.modules.bus.value == com.openobd.session.Bus.HS }
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
        tab("Home")
        shot("23_home_after_drive_$suffix")
        tab("Garage")
        shot("23_garage_$suffix")
        tap("Maintenance")
        shot("24_maintenance_$suffix")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Fuel log")
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

/** Spanish UI tour: renders the main screens with the Spanish translation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "es-w411dp-h891dp-xxhdpi")
class SpanishUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

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

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @Test
    fun spanishTour() {
        val app = rule.activity.application as OpenObdApp
        val c = app.container
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = ThemeMode.DARK, language = "es") } }
        waitFor { I18n.language == "es" }
        assertTrue(rule.onAllNodesWithText("Conectar").fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(hasText("Conectar") and hasClickAction())[0].performClick()
        waitFor { c.session.state.value is ConnectionState.Connected && c.session.dtcScan.value is Op.Done }
        shot("es_01_home")
        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        rule.onAllNodes(hasText("En directo") and isSelectable())[0].performClick()
        waitFor { c.session.live.readings.value.containsKey("010C") }
        Thread.sleep(1000)
        shot("es_02_dashboard")
        rule.onAllNodes(hasText("Diagnosis") and isSelectable())[0].performClick()
        rule.onAllNodes(hasText("Escanear de nuevo") and hasClickAction())[0].performClick()
        waitFor { c.session.dtcScan.value is Op.Done }
        shot("es_03_codes")
        rule.onAllNodes(hasText("Más") and isSelectable())[0].performClick()
        shot("es_04_more")
        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected }
    }
}

/** Find-your-car flow for a Spanish user, with the EEA service replayed from recorded responses. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
class FindCarUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

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

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    private fun click(text: String) {
        waitFor { rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(text) and hasClickAction())[0].also { runCatching { it.performScrollTo() } }.performClick()
        rule.waitForIdle()
    }

    @Test
    fun spanishCarFromCatalog() {
        val app = rule.activity.application as OpenObdApp
        val c = app.container
        c.http = com.openobd.core.FixtureHttp(listOf(
            "DISTINCT Year" to "eea_years.json", "UPPER(Mk) AS mk" to "eea_makes.json",
            "UPPER(Cn) AS cn" to "eea_models.json", "GROUP BY UPPER(Ft)" to "eea_versions.json",
        ))
        runBlocking {
            c.settings.update { it.copy(theme = ThemeMode.DARK, language = "es", specsCountry = "ES") }
            c.garage.load(null)
        }
        waitFor { I18n.language == "es" && c.garage.data.value.active != null }
        click("Identificar tu vehículo (modelo y especificaciones)")
        waitFor { rule.onAllNodesWithText("SEAT").fetchSemanticsNodes().isNotEmpty() }
        shot("es_10_find_car")
        click("2019")
        click("SEAT")
        click("Leon")
        waitFor { rule.onAllNodesWithText("Gasolina · 1,5 L · 110 kW (150 CV)").fetchSemanticsNodes().isNotEmpty() }
        shot("es_11_versions")
        click("Gasolina · 1,5 L · 110 kW (150 CV)")
        shot("es_12_editor")
        click("Guardar")
        val v = c.garage.data.value.active!!
        waitFor { c.garage.data.value.active?.specs != null }
        val specs = c.garage.data.value.active!!.specs!!
        assertTrue(specs.make == "SEAT" && specs.model == "Leon" && specs.engineCc == 1498)
        assertTrue(c.garage.data.value.active!!.name == "2019 SEAT Leon")
        shot("es_13_specs")
        // Second-source check: specs came from the EU catalog and the car is not connected, so it asks for the car or its VIN
        waitFor { rule.onAllNodesWithText("Comprobación con otra fuente").fetchSemanticsNodes().isNotEmpty() }
        waitFor { rule.onAllNodesWithText("Conecta el coche o guarda su VIN", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The fuel model now uses the car's engine size
        assertTrue(c.session.live.params.displacementL == 1.498)
        // No first-registration date yet, so no ITV reminder; adding it creates one
        runBlocking {
            c.garage.updateVehicle(v.id) { it.copy(specs = specs.copy(firstRegistration = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, -5) }.timeInMillis)) }
        }
        assertTrue(com.openobd.core.specs.Spain.dgtLabel(specs.fuel, 2019) == com.openobd.core.specs.DgtLabel.C)
    }

    /** A 2004 car: the EU catalog starts later, so make and model come from its first year and the engine is typed in. */
    @Test
    fun carOlderThanTheCatalog() {
        val c = (rule.activity.application as OpenObdApp).container
        c.http = com.openobd.core.FixtureHttp(listOf(
            "DISTINCT Year" to "eea_years.json", "UPPER(Mk) AS mk" to "eea_makes.json",
            "UPPER(Cn) AS cn" to "eea_models.json", "GROUP BY UPPER(Ft)" to "eea_versions.json",
        ))
        runBlocking {
            c.settings.update { it.copy(theme = ThemeMode.DARK, language = "es", specsCountry = "ES") }
            c.garage.load(null)
        }
        waitFor { I18n.language == "es" && c.garage.data.value.active != null }
        click("Identificar tu vehículo (modelo y especificaciones)")
        waitFor { rule.onAllNodesWithText("SEAT").fetchSemanticsNodes().isNotEmpty() }
        click("2004")
        waitFor { rule.onAllNodesWithText("El catálogo europeo empieza en 2018", substring = true).fetchSemanticsNodes().isNotEmpty() }
        click("SEAT")
        click("Leon")
        shot("es_14_older_car")
        click("Continuar")
        click("Guardar")
        waitFor { c.garage.data.value.active?.specs != null }
        val specs = c.garage.data.value.active!!.specs!!
        assertTrue("$specs", specs.make == "SEAT" && specs.model == "Leon" && specs.year == 2004 && specs.source == "Manual" && specs.country == "ES")
    }
}

/** An uncaught exception is written to disk, offered on the next start, shared and then forgotten. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CrashReportUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun before() = clearFileProviderCache()

    @Test
    fun crashIsOfferedOnceAndShared() {
        val app = rule.activity.application as OpenObdApp
        runBlocking { app.container.settings.update { it.copy(language = "en", connectionType = ConnectionType.DEMO) } }
        val d = app.container.diagnostics
        d.log.add('>', "010C")
        d.log.add('<', "7E8 04 41 0C 1A F8")
        d.recordCrash(Thread.currentThread(), IllegalStateException("simulated crash for the test"))
        d.reload()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("OpenOBD closed unexpectedly").fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodes(hasText("Share report") and hasClickAction())[0].performClick()
        val end = System.currentTimeMillis() + 10_000
        while (d.pendingCrash.value != null && System.currentTimeMillis() < end) { Thread.sleep(50); rule.waitForIdle() }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("OpenOBD closed unexpectedly").fetchSemanticsNodes().isEmpty())
        val report = File(app.cacheDir, "reports").listFiles()!!.single { it.name.startsWith("openobd-debug-") }.readText()
        assertTrue(report, report.contains("IllegalStateException: simulated crash for the test"))
        assertTrue(report, report.contains("> 010C") && report.contains("App: OpenOBD"))
        assertTrue(report, report.contains("## Settings") && report.contains("Connection: DEMO"))
    }
}

/** Used car / pre-ITV check against the demo car, including the PDF export. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VehicleCheckUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeBy(50)
        }
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
    fun checkAndPdf() {
        val app = rule.activity.application as OpenObdApp
        val c = app.container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", tripGps = false, theme = ThemeMode.DARK) } }
        waitFor { c.settings.settings.value.connectionType == ConnectionType.DEMO && I18n.language == "en" }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected }
        waitFor { c.session.dtcScan.value is Op.Done }
        rule.onAllNodes(hasText("Diagnose") and isSelectable())[0].performClick()
        rule.waitForIdle()
        // During the tab cross-fade both screens exist: try every scrollable until the item is found.
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText("Used car & pre-ITV check")) }
        }
        rule.onAllNodesWithText("Used car & pre-ITV check")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Run the check") and hasClickAction())[0].performClick()
        waitFor { c.session.check.result.value is Op.Done }
        val report = (c.session.check.result.value as Op.Done).value
        // Demo car: MIL on with P0301/P0420 stored and ABS / airbag / body codes -> serious warning, would fail the ITV.
        assertTrue(report.items.joinToString("\n"), report.verdict == com.openobd.core.report.Verdict.BAD)
        assertTrue(report.itv == com.openobd.core.report.ItvVerdict.FAIL)
        assertTrue(rule.onAllNodesWithText("Serious warning signs").fetchSemanticsNodes().isNotEmpty())
        shot("30_vehicle_check_dark")

        // The PDF itself is generated on real Android in the device tests (Robolectric has no native PDF engine).
        c.session.disconnect()
    }

    @Test
    fun batteryStartingTest() {
        val app = rule.activity.application as OpenObdApp
        val c = app.container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", tripGps = false, theme = ThemeMode.DARK) } }
        waitFor { c.settings.settings.value.connectionType == ConnectionType.DEMO && I18n.language == "en" }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected }
        rule.onAllNodes(hasText("Diagnose") and isSelectable())[0].performClick()
        rule.waitForIdle()
        // During the tab cross-fade both screens exist: try every scrollable until the item is found.
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { rule.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText("Battery & starting test")) }
        }
        rule.onAllNodesWithText("Battery & starting test")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Start test") and hasClickAction())[0].performClick()
        waitFor { c.session.battery.state.value is com.openobd.session.BatteryTestState.WaitingStart }
        assertTrue(rule.onAllNodesWithText("Start the engine now").fetchSemanticsNodes().isNotEmpty())
        waitFor(60_000) { c.session.battery.state.value is com.openobd.session.BatteryTestState.Done }
        val r = (c.session.battery.state.value as com.openobd.session.BatteryTestState.Done).result
        // Demo start: 12.5 V at rest, a short inrush spike, ~10.3 V while cranking, then 14.2 V charging
        assertTrue(r.toString(), r.restV!! in 12.3..12.7 && r.minV!! in 10.0..10.8 && r.chargingV!! in 14.0..14.4)
        assertTrue(r.toString(), r.overall == com.openobd.core.battery.BatteryLevel.GOOD)
        waitFor { c.garage.data.value.batteryTests.isNotEmpty() }
        waitFor { rule.onAllNodesWithText("Earlier tests").fetchSemanticsNodes().isNotEmpty() }
        shot("31_battery_test_dark")
        c.session.disconnect()
    }
}

/** Code explanation screen and the AI assistant chat (with a fake backend instead of the Claude API). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "es-rES-w411dp-h891dp-xxhdpi")
class AssistantUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting" }
            Thread.sleep(50)
            rule.mainClock.advanceTimeBy(50)
        }
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
    fun explainAndAsk() {
        val app = rule.activity.application as OpenObdApp
        val c = app.container
        val sent = mutableListOf<String>()
        c.assistantBackend = { key ->
            check(key == "sk-test")
            com.openobd.data.ChatBackend { _, turns -> sent += turns.last().text; "Lo más probable es un fallo de la bobina del cilindro 1." }
        }
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "es", tripGps = false, theme = ThemeMode.DARK, claudeApiKey = "sk-test") } }
        waitFor { c.settings.settings.value.claudeApiKey == "sk-test" && I18n.language == "es" }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected }
        waitFor { c.session.dtcScan.value is Op.Done }
        rule.onAllNodes(hasText("Diagnosis") and isSelectable())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("P0301")[0].performClick()
        waitFor { rule.onAllNodesWithText("¿Puedo seguir conduciendo?").fetchSemanticsNodes().isNotEmpty() }
        shot("31_code_explained_es")

        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Preguntar al asistente IA"))
        rule.onAllNodes(hasText("Preguntar al asistente IA") and hasClickAction())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Explica esta avería y qué debo hacer") and hasClickAction())[0].performClick()
        waitFor { rule.onAllNodesWithText("Lo más probable es un fallo de la bobina del cilindro 1.").fetchSemanticsNodes().isNotEmpty() }
        shot("32_assistant_es")
        // The first message carries the car data; no VIN is sent.
        assertTrue(sent.first(), sent.first().contains("P0301") && sent.first().contains("Explica esta avería"))
        assertTrue(!sent.first().contains(c.session.vehicle?.vin ?: "-"))
        c.session.disconnect()
    }
}

/** First-run wizard and dashboard layouts on a phone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WelcomeAndDashboardUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    private fun click(text: String) { rule.onAllNodes(hasText(text) and hasClickAction())[0].performClick(); rule.waitForIdle() }

    @Test
    fun wizardThenDashboardPreset() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(language = "en", onboarded = false, tripGps = false) } }
        waitFor { I18n.language == "en" && !c.settings.settings.value.onboarded }
        click("Start setup")
        click("Next")
        click("Demo (no adapter)")
        waitFor { c.settings.settings.value.connectionType == ConnectionType.DEMO }
        click("Next"); click("Next"); click("Start")
        waitFor { c.settings.settings.value.onboarded }
        assertTrue(rule.onAllNodesWithText("Welcome to OpenOBD").fetchSemanticsNodes().isEmpty())

        // The basic tour follows the setup; at its end it offers the full tour.
        waitFor { rule.onAllNodesWithText("App tour").fetchSemanticsNodes().isNotEmpty() }
        waitFor { c.settings.settings.value.tourSeen }
        repeat(com.openobd.ui.screens.Tours.basic.size) { rule.onNodeWithTag("tourNext").performClick(); rule.waitForIdle() }
        rule.onNodeWithTag("fullTour").performClick()
        waitFor { rule.onAllNodesWithText("Full tour").fetchSemanticsNodes().isNotEmpty() }
        // "Open" shows the feature on top of the tour; Back returns to the same page.
        rule.onNodeWithTag("tourOpen").performClick()
        waitFor { rule.onAllNodesWithText("Connection").fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor { rule.onAllNodesWithText("Full tour").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("tourClose").performClick()
        rule.waitForIdle()

        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        rule.onAllNodes(hasText("Live") and isSelectable())[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("Edit")[0].performClick()
        rule.waitForIdle()
        click("Diesel")
        waitFor { c.settings.settings.value.dashboard == com.openobd.ui.screens.DashboardPresets.all.first { it.first == "Diesel" }.second }
        rule.onAllNodesWithContentDescription("Customise gauge")[0].performClick()
        waitFor { rule.onAllNodes(hasText("Number") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("Number") and hasClickAction())[0].performClick()
        click("Save")
        waitFor { c.settings.settings.value.dashboard.first() == "010C@d" }
        rule.onAllNodesWithContentDescription("Move later")[0].performClick()
        waitFor { c.settings.settings.value.dashboard.take(2) == listOf("CALC_BOOST@d", "010C@d") }
    }
}

/** Tablet / landscape: navigation rail instead of the bottom bar, black (AMOLED) theme. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun railLayout() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(language = "en", theme = ThemeMode.AMOLED, connectionType = ConnectionType.DEMO, tripGps = false, onboarded = true) } }
        val end = System.currentTimeMillis() + 20_000
        while (c.settings.settings.value.theme != ThemeMode.AMOLED && System.currentTimeMillis() < end) { Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        c.session.connect()
        while (c.session.state.value !is ConnectionState.Connected && System.currentTimeMillis() < end + 20_000) { Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        rule.onAllNodes(hasText("Live") and isSelectable())[0].performClick()
        Thread.sleep(1500); rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
        rule.activityRule.scenario.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "40_tablet_amoled_dashboard.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        c.session.disconnect()
    }
}

/** Quick tour of the main tabs in each added language, to catch missing glyphs and labels that do not fit. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class LanguagesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

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

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @Test
    fun tourEveryLanguage() {
        val c = (rule.activity.application as OpenObdApp).container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = ThemeMode.DARK, language = "de", tripGps = false, onboarded = true) } }
        waitFor { I18n.language == "de" }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected && c.session.dtcScan.value is Op.Done }
        for (lang in listOf("de", "fr", "it", "pt", "nl", "pl", "tr", "ru", "zh")) {
            runBlocking { c.settings.update { it.copy(language = lang) } }
            waitFor { I18n.language == lang }
            rule.onAllNodes(hasText(tr("Home")) and isSelectable())[0].performClick()
            shot("lang_${lang}_1_home")
            rule.onAllNodes(hasText(tr("Diagnose")) and isSelectable())[0].performClick()
            shot("lang_${lang}_2_diagnose")
            rule.onAllNodes(hasText(tr("More")) and isSelectable())[0].performClick()
            shot("lang_${lang}_3_more")
        }
        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected }
    }
}
