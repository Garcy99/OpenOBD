package com.openobd.core

import com.openobd.core.brand.Brands
import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.Equation
import com.openobd.core.brand.Families
import com.openobd.core.brand.FailureTypes
import com.openobd.core.brand.ManufacturerCodes
import com.openobd.core.brand.TorqueCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandTest {
    @Test
    fun detectsBrandFromVin() {
        assertEquals("ford", Brands.detect("1FA6P8CF5H5212345")?.id)
        assertEquals("volkswagen", Brands.detect("WVWZZZ1KZAW000001")?.id)
        assertEquals("kia", Brands.detect("KNDJ23AU5L7000001")?.id)
        assertEquals("toyota", Brands.detect("JTDKN3DU0A0000001")?.id)
        assertEquals("honda", Brands.detect("1HGCM82633A004352")?.id)
        assertEquals("bmw", Brands.detect("WBA8E9C50GK000001")?.id)
        assertNull(Brands.detect("ZZZ00000000000000"))
        assertNull(Brands.detect(null))
    }

    @Test
    fun brandCatalogIsConsistent() {
        val ids = Brands.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // A WMI must map to exactly one brand
        val wmis = Brands.all.flatMap { b -> b.wmis.map { it to b.id } }
        val dup = wmis.groupBy { it.first }.filter { it.value.size > 1 }
        assertTrue("Duplicate WMIs: $dup", dup.isEmpty())
        for (f in Families.all) {
            val moduleIds = f.modules.map { it.id }
            assertEquals("Duplicate module in ${f.label}", moduleIds.size, moduleIds.toSet().size)
            assertTrue(f.modules.all { it.tx in 0..0x7FF && it.rx in 0..0x7FF && it.tx != it.rx })
        }
    }

    @Test
    fun manufacturerCodes() {
        assertEquals("Theft detected - vehicle immobilized", ManufacturerCodes.lookup(Families.FORD, "P1260"))
        assertTrue(ManufacturerCodes.lookup(Families.TOYOTA, "P1349-00")!!.contains("VVT"))
        assertNull(ManufacturerCodes.lookup(Families.GENERIC, "P1260"))
        assertEquals("Circuit open", FailureTypes.describe(0x13))
    }

    @Test
    fun equations() {
        fun ev(eq: String, vararg b: Int) = Equation(eq).evaluate(b)
        assertEquals(62.5, ev("((A*256)+B)/16", 0x03, 0xE8)!!, 1e-9)
        assertEquals(-2.0, ev("Signed(A)", 0xFE)!!, 1e-9)
        assertEquals(-300.0, ev("Int16(A,B)", 0xFE, 0xD4)!!, 1e-9)
        assertEquals(1.0, ev("{B:7}", 0, 0x80)!!, 1e-9)
        assertEquals(4.0, ev("(A<8)*B+2^1", 3, 2)!!, 1e-9)
        assertEquals(12.0, ev("(A>>4)+(A&0x0F)", 0xA2)!!, 1e-9)
        assertEquals(9.0, ev("max(A,B,9)-min(1,2)+1", 1, 2)!!, 1e-9)
        assertEquals(27, Equation("AA+1").bytesNeeded) // AA is the 27th byte
        assertNull(ev("A+B", 1)) // not enough bytes
        assertTrue(runCatching { Equation("A+") }.isFailure)
        assertTrue(runCatching { Equation("foo(A)") }.isFailure)
    }

    @Test
    fun torqueCsvRoundTrip() {
        val csv = """
            Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
            "Trans temp, sump",TFT,221E1C,((A*256)+B)/16,-40,150,°C,7E1
            # comment line
            State of charge,SOC,220101,F/2,0,100,%,7E4
            Broken,X,ZZ,A,0,1,,
        """.trimIndent()
        val r = TorqueCsv.parse(csv, "t")
        assertEquals(2, r.pids.size)
        assertEquals(1, r.skipped.size)
        val tft = r.pids[0]
        assertEquals("Trans temp, sump", tft.name)
        assertEquals(0x7E1, tft.headerId)
        assertEquals(0x7E9, tft.receiveId)
        assertEquals(0x22, tft.service)
        val again = TorqueCsv.parse(TorqueCsv.export(r.pids), "u")
        assertEquals(r.pids.map { it.copy(id = "") }, again.pids.map { it.copy(id = "") })
        val sensor = tft.toSensor()
        assertNotNull(sensor.decodeSafe(intArrayOf(0x03, 0xE8)))
        assertNull(CustomPid("x", "n", "", "22", "A").validate()?.let { null }) // validate does not throw
    }
}
