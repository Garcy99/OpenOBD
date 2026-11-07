package com.openobd.ui

import com.openobd.core.obd.UnitSystem
import com.openobd.ui.components.GaugeSpec
import com.openobd.ui.components.GaugeStyle
import com.openobd.ui.components.GaugeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeSpecTest {
    @Test
    fun olderDashboardsStillRead() {
        assertEquals(GaugeSpec("010C"), GaugeSpec.parse("010C"))
        assertEquals(GaugeSpec("0105", GaugeStyle.DIGITAL), GaugeSpec.parse("0105@d"))
        assertEquals("0105@d", GaugeSpec.parse("0105@d").encode())
        assertEquals("010C", GaugeSpec.parse("010C").encode())
    }

    @Test
    fun customisationsRoundTrip() {
        val s = GaugeSpec("010C", GaugeStyle.NEEDLE, color = "E53935", min = 0.0, max = 6000.0, warnAbove = 4500.0, decimals = 0)
        assertEquals("010C@n;c=E53935;lo=0;hi=6000;w=4500;dp=0", s.encode())
        assertEquals(s, GaugeSpec.parse(s.encode()))
        val noStyle = GaugeSpec("0142", warnBelow = 11.8)
        assertEquals("0142@;wl=11.8", noStyle.encode())
        assertEquals(noStyle, GaugeSpec.parse(noStyle.encode()))
        // Never a comma: the dashboard setting is comma-separated
        assertFalse(s.encode().contains(','))
    }

    @Test
    fun rangesBelongToTheirUnitSystem() {
        val f = GaugeSpec("0105", max = 260.0, warnAbove = 230.0, units = UnitSystem.IMPERIAL)
        assertTrue(GaugeSpec.parse(f.encode()).appliesTo(UnitSystem.IMPERIAL))
        assertFalse(GaugeSpec.parse(f.encode()).appliesTo(UnitSystem.METRIC))
    }

    @Test
    fun badInputIsIgnored() {
        val s = GaugeSpec.parse("010C@x;c=red;hi=abc;dp=9")
        assertNull(s.style); assertNull(s.color); assertNull(s.max); assertEquals(3, s.decimals)
        assertEquals(GaugeTheme.NEON, GaugeTheme.of("nope"))
    }
}
