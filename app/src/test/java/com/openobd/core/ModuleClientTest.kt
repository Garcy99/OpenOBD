package com.openobd.core

import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.DiagProtocol
import com.openobd.core.brand.Families
import com.openobd.core.brand.ModuleClient
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.obd.Pids
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleClientTest {
    private fun connect(headers: Boolean = true) = runBlocking {
        val t = DemoTransport(latencyMs = 0)
        t.open()
        val elm = Elm327(t)
        elm.initialize(ElmConfig(headersOn = headers))
        Triple(t, ObdClient(elm).also { it.discover() }, ModuleClient(elm))
    }

    @Test
    fun scansFordModules() = runBlocking {
        val (_, obd, mc) = connect()
        val results = Families.FORD.modules.map { mc.scan(it) }
        val byShort = results.associateBy { it.module.shortName }
        assertTrue(byShort.getValue("PCM").online)
        assertEquals(listOf("P0301-00", "P0420-00", "P0171-00"), byShort.getValue("PCM").dtcs.map { it.fullCode })

        val abs = byShort.getValue("ABS")
        assertTrue(abs.online)
        assertEquals(DiagProtocol.UDS, abs.protocol)
        val c = abs.dtcs.single()
        assertEquals("C0035-31", c.fullCode)
        assertEquals("No signal", c.failureTypeText)
        assertTrue(c.isActive && c.isConfirmed)

        assertTrue(byShort.getValue("RCM").dtcs.single().warningLamp)
        assertEquals("U0155-00", byShort.getValue("BCM").dtcs.single().fullCode)
        assertTrue(byShort.getValue("IPC").online && byShort.getValue("IPC").dtcs.isEmpty())
        assertFalse(byShort.getValue("GWM").online)

        // Standard OBD polling must still work after switching headers around.
        val data = obd.readPids(listOf(0x0C, 0x0D))
        assertEquals(2, data.size)
        assertTrue(Pids.sensors.getValue("010C").decode(data.getValue(0x0C))!! > 0)
    }

    @Test
    fun clearIdentifyAndReadDid() = runBlocking {
        val (demo, _, mc) = connect()
        val abs = Families.FORD.modules.first { it.shortName == "ABS" }
        assertTrue(mc.clear(abs).success)
        assertTrue(mc.scan(abs).dtcs.isEmpty())
        assertTrue(demo.modules.getValue(0x760).dtcs.isEmpty())

        val ident = mc.readIdentification(abs).toMap()
        assertEquals("OB-2C219-DEMO", ident["Spare part number"])
        assertEquals(demo.vin, ident["VIN"])
        assertEquals("ABS", ident["System name"])

        val bytes = mc.readDid(abs, 0xF195)
        assertEquals("SW 2.4.1", ModuleClient.formatDidValue(bytes))
        assertTrue(runCatching { mc.readDid(abs, 0x1234) }.exceptionOrNull()!!.message!!.contains("out of range"))
    }

    @Test
    fun discoversAllModules() = runBlocking {
        val (demo, obd, mc) = connect()
        val found = mc.discover()
        assertEquals(demo.modules.keys.sorted(), found.map { it.tx }.sorted())
        assertEquals(0x768, found.first { it.tx == 0x760 }.rx)
        assertEquals(2, obd.readPids(listOf(0x0C, 0x0D)).size)
    }

    @Test
    fun readsCustomPids() = runBlocking {
        val (_, obd, mc) = connect()
        val pid = CustomPid("1", "Transmission temperature", "TFT", "221E1C", "((A*256)+B)/16", -40.0, 150.0, "°C", "7E1")
        val std = CustomPid("2", "RPM via custom", "RPM", "010C", "((A*256)+B)/4", 0.0, 8000.0, "rpm", "")
        val values = mc.readCustom(listOf(pid, std))
        val tft = pid.toSensor().decodeSafe(values.getValue(pid.key))!!
        assertTrue("TFT $tft", tft in 55.0..80.0)
        assertTrue(std.toSensor().decodeSafe(values.getValue(std.key))!! > 0)
        assertEquals(2, obd.readPids(listOf(0x0C, 0x05)).size)
    }

    @Test
    fun worksWithHeadersOffForKnownModules() = runBlocking {
        val (_, _, mc) = connect(headers = false)
        val abs = Families.FORD.modules.first { it.shortName == "ABS" }
        assertEquals("C0035-31", mc.scan(abs).dtcs.single().fullCode)
    }
}
