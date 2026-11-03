package com.openobd.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.obd.UnitSystem
import com.openobd.service.FloatingGauges
import com.openobd.service.overlayValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FloatingGaugesTest {
    @Test
    fun valuesAreFormattedInTheUserUnits() {
        assertEquals("2150" to "rpm", overlayValue(SensorCatalog.get("010C"), 2150.4, UnitSystem.METRIC))
        assertEquals("88" to "km/h", overlayValue(SensorCatalog.get("010D"), 88.0, UnitSystem.METRIC))
        assertEquals("55" to "mph", overlayValue(SensorCatalog.get("010D"), 88.0, UnitSystem.IMPERIAL))
        assertEquals("–", overlayValue(SensorCatalog.get("0105"), null, UnitSystem.METRIC).first)
    }

    @Test
    fun windowNeedsThePermissionAndShowsLiveValues() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val g = FloatingGauges(ctx)
        ShadowSettings.setCanDrawOverlays(false)
        g.show(listOf("010D", "010C", "0105")) { it }
        assertFalse(g.isShown)

        ShadowSettings.setCanDrawOverlays(true)
        g.show(listOf("010D", "010C", "0105")) { it }
        assertTrue(g.isShown)
        val values = mapOf("010D" to 92.0, "010C" to 2100.0)
        g.update({ values[it] }, UnitSystem.METRIC)
        val texts = g.texts()
        assertEquals(3, texts.size)
        assertTrue(texts.toString(), texts[0].startsWith("92 ") && texts[0].endsWith("km/h"))
        assertTrue(texts.toString(), texts[1].startsWith("2100 ") && texts[1].endsWith("rpm"))
        assertTrue(texts.toString(), texts[2].startsWith("– "))
        g.hide()
        assertFalse(g.isShown)
    }
}
