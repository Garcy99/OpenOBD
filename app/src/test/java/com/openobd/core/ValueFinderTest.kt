package com.openobd.core

import com.openobd.core.brand.Equation
import com.openobd.core.brand.IdKind
import com.openobd.core.brand.ValueFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueFinderTest {
    private fun u16(v: Int) = intArrayOf(v shr 8, v and 0xFF)

    @Test fun `a soot load is found among the engine's answers, and a second reading leaves the right one`() {
        // The load at 1000/65535 per bit (as GM keeps it) in 22 3A10, among other values.
        val records = mapOf(
            (IdKind.UDS to 0x3A10) to u16(Math.round(37.0 * 65.535).toInt()),
            (IdKind.UDS to 0x1800) to intArrayOf(0x55, 0x12, 0x80, 0x01),
            (IdKind.UDS to 0x1801) to u16(1234),
            (IdKind.KWP to 0x4D) to intArrayOf(0x00, 0x25, 0x10, 0x07, 0xD0),
        )
        val found = ValueFinder.search(records, 37.0, ValueFinder.Quantity.DPF_SOOT)
        val right = found.firstOrNull { it.kind == IdKind.UDS && it.id == 0x3A10 && it.width == ValueFinder.Width.U16 }
        assertTrue("$found", right != null)
        assertEquals(37.0, right!!.value, 0.05)
        // Its equation reads the same value as a custom PID.
        val pid = ValueFinder.toCustomPid(right, ValueFinder.Quantity.DPF_SOOT, "Soot")
        assertEquals("gm_dpf_soot", pid.id)
        assertEquals("223A10", pid.request)
        assertEquals(37.0, Equation(pid.equation).evaluate(records[IdKind.UDS to 0x3A10]!!)!!, 0.05)
        // Later the load is 52 %: the coincidences fall away.
        val later = records + ((IdKind.UDS to 0x3A10) to u16(Math.round(52.0 * 65.535).toInt()))
        val left = ValueFinder.refine(found, later, 52.0, ValueFinder.Quantity.DPF_SOOT)
        assertTrue(left.isNotEmpty() && left.all { it.id == 0x3A10 })
    }

    @Test fun `temperatures, distances and counts decode with their usual scalings`() {
        val temp = mapOf((IdKind.UDS to 0x18DE) to u16(Math.round((612.0 + 40) / 0.02).toInt()))
        assertTrue(ValueFinder.search(temp, 610.0, ValueFinder.Quantity.DPF_TEMP).any { it.id == 0x18DE && it.scale.add == -40.0 })
        val km = mapOf((IdKind.UDS to 0x3807) to intArrayOf(0x00, 0x0F, 0x3C))
        val k = ValueFinder.search(km, 390.0, ValueFinder.Quantity.DPF_KM).first { it.width == ValueFinder.Width.U24 }
        assertEquals("(A*65536+B*256+C)*0.1", ValueFinder.equation(k))
        val count = mapOf((IdKind.KWP to 0xB1) to intArrayOf(0x00, 0x00, 0x01, 0x9C))
        val c = ValueFinder.search(count, 412.0, ValueFinder.Quantity.DPF_REGENS)
        assertTrue(c.any { it.offset == 2 && it.width == ValueFinder.Width.U16 && it.scale.mul == 1.0 })
        assertEquals("(C*256+D)", ValueFinder.equation(c.first { it.offset == 2 && it.width == ValueFinder.Width.U16 && it.scale.mul == 1.0 }))
    }

    @Test fun `byte names go past Z`() {
        assertEquals("A", ValueFinder.letter(0)); assertEquals("Z", ValueFinder.letter(25)); assertEquals("AA", ValueFinder.letter(26)); assertEquals("AZ", ValueFinder.letter(51))
    }
}
