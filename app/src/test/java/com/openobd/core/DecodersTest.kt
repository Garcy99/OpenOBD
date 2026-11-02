package com.openobd.core

import com.openobd.core.obd.DtcDecoder
import com.openobd.core.obd.Mode06
import com.openobd.core.obd.MonitorState
import com.openobd.core.obd.Pids
import com.openobd.core.obd.Readiness
import com.openobd.core.obd.VinDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodersTest {
    @Test
    fun dtcDecodeEncode() {
        assertEquals("P0133", DtcDecoder.decode(0x01, 0x33))
        assertEquals("C0123", DtcDecoder.decode(0x41, 0x23))
        assertEquals("B1A45", DtcDecoder.decode(0x9A, 0x45))
        assertEquals("U0100", DtcDecoder.decode(0xC1, 0x00))
        for (c in listOf("P0301", "P2A00", "U0100", "B1234", "C0561")) {
            val (a, b) = DtcDecoder.encode(c)
            assertEquals(c, DtcDecoder.decode(a, b))
        }
    }

    @Test
    fun dtcResponseCanAndLegacy() {
        assertEquals(listOf("P0301", "P0420"), DtcDecoder.parseResponse(intArrayOf(0x43, 2, 0x03, 0x01, 0x04, 0x20), true))
        assertEquals(listOf("P0133"), DtcDecoder.parseResponse(intArrayOf(0x43, 0x01, 0x33, 0, 0, 0, 0), false))
        assertTrue(DtcDecoder.parseResponse(intArrayOf(0x43, 0), true).isEmpty())
    }

    @Test
    fun pidFormulas() {
        fun v(key: String, vararg b: Int) = Pids.sensors.getValue(key).decodeSafe(b)
        assertEquals(1726.0, v("010C", 0x1A, 0xF8)!!, 0.01)
        assertEquals(50.0, v("010D", 0x32)!!, 0.01)
        assertEquals(90.0, v("0105", 130)!!, 0.01)
        assertEquals(100.0, v("0104", 255)!!, 0.01)
        assertEquals(-100.0, v("0106", 0)!!, 0.01)
        assertEquals(12.5, v("0110", 0x04, 0xE2)!!, 0.01)
        assertEquals(14.2, v("0142", 0x37, 0x78)!!, 0.01)
        assertEquals(128448.4, v("01A6", 0x00, 0x13, 0x99, 0x84)!!, 0.01)
        assertEquals(0.45, v("0114_V", 90, 0xFF)!!, 0.001)
        assertNull(v("0114_T", 90, 0xFF))
        assertNull(v("010C", 0x1A)) // truncated -> null, not crash
    }

    @Test
    fun allPidsHaveConsistentLengths() {
        assertTrue(Pids.sensors.size >= 200)
        for ((pid, info) in Pids.all) {
            assertTrue("PID %02X length".format(pid), info.bytes in 1..41)
            for (s in info.sensors) {
                // Every decoder must accept a zero-filled buffer of the declared length without crashing.
                s.decodeSafe(IntArray(info.bytes))
                s.decodeSafe(IntArray(info.bytes) { 0xFF })
            }
        }
    }

    @Test
    fun readinessSpark() {
        // MIL on, 2 DTCs; misfire/fuel/components available & complete; catalyst/evap/o2/o2 heater available; evap incomplete
        val r = Readiness.decode(intArrayOf(0x82, 0x07, 0x65, 0x04))
        assertTrue(r.milOn)
        assertEquals(2, r.dtcCount)
        assertFalse(r.isDiesel)
        assertEquals(MonitorState.INCOMPLETE, r.monitors.first { it.name == "Evaporative system" }.state)
        assertEquals(MonitorState.COMPLETE, r.monitors.first { it.name == "Catalyst" }.state)
        assertEquals(MonitorState.NOT_SUPPORTED, r.monitors.first { it.name == "Secondary air system" }.state)
    }

    @Test
    fun vin() {
        val info = VinDecoder.decode("1HGCM82633A004352")
        assertEquals(true, info.checkDigitValid)
        assertEquals(2003, info.modelYear)
        assertEquals("Honda (USA)", info.manufacturer)
        assertEquals("United States", info.country)
        assertEquals("Volkswagen", VinDecoder.decode("WVWZZZ1KZAW000001").manufacturer)
        assertEquals("Germany", VinDecoder.decode("WVWZZZ1KZAW000001").country)
    }

    @Test
    fun mode06() {
        val data = intArrayOf(0x46, 0x01, 0x01, 0x0A, 0x0E, 0x10, 0x0C, 0x00, 0x10, 0x00)
        val r = Mode06.parse(data, "7E8")
        assertEquals(1, r.size)
        assertEquals(0.439, r[0].value, 0.001)
        assertTrue(r[0].passed)
        assertNotNull(r[0].monitorName)
    }
}
