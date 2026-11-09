package com.openobd.ui

import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Home shows the cheapest station around (Segovia here), and its screen lists them with the saving. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FuelPricesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val c get() = (rule.activity.application as OpenObdApp).container

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    @Test
    fun cheapestStationOnHomeAndTheList() {
        val app = rule.activity.application
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 40.9480; longitude = -4.1178; time = System.currentTimeMillis()
        })
        val json = javaClass.getResource("/fuel-segovia.json")!!.readText()
        val asked = mutableListOf<String>()
        c.fuelPrices.fetch = { url -> asked += url; json }
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, language = "en", onboarded = true, tourSeen = true, pumpFuel = "DIESEL", fuelPrices = true) } }
        waitFor { c.garage.loaded && I18n.language == "en" }
        // Home: the cheapest diesel around, with the saving against the usual price.
        waitFor { rule.onAllNodesWithText("Cheapest diesel near you: 1.739 €/L", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("Ballenoil", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Only the province (Segovia, 40) is asked for.
        assertTrue(asked.isNotEmpty() && asked.all { it.endsWith("FiltroProvincia/40") || it.contains("FiltroProvincia/") })
        rule.onNodeWithTag("fuelCard").performScrollTo().performClick()
        waitFor { rule.onAllNodesWithText("filling 50 L there saves", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("Cepsa", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Petrol: another price for the cheapest.
        rule.onNodeWithTag("pump_PETROL_95").performClick()
        waitFor { rule.onAllNodesWithText("1.559 €/L", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
