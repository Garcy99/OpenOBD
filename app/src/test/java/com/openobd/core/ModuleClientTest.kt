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

class MsCanAndCloneTest {
    private fun connect(clone: Boolean = false) = runBlocking {
        val t = DemoTransport(latencyMs = 0, limitedClone = clone)
        t.open()
        val elm = Elm327(t)
        elm.initialize(ElmConfig())
        Triple(t, elm, ModuleClient(elm))
    }

    @Test
    fun switchesToMsCanAndBack() = runBlocking {
        val (_, elm, mc) = connect()
        val obd = ObdClient(elm).also { it.discover() }
        val ms = Families.FORD.modules.filter { it.secondaryBus }
        // While still on HS-CAN the MS modules don't answer
        assertTrue(mc.ping(ms).isEmpty())

        assertTrue(elm.setProtocol(com.openobd.core.elm.ObdProtocol.USER1_CAN))
        assertEquals(listOf("HVAC", "DDM", "APIM"), mc.ping(ms).map { it.shortName })
        val hvac = mc.scan(ms.first { it.shortName == "HVAC" })
        assertEquals("B1A4F-12", hvac.dtcs.single().fullCode)
        val found = mc.discover(secondaryBus = true)
        assertEquals(listOf(0x733, 0x740, 0x7D0), found.map { it.tx }.sorted())
        assertTrue(found.all { it.secondaryBus && it.id.startsWith("ms:") })
        // Engine is not reachable on MS-CAN
        assertTrue(obd.readPids(listOf(0x0C)).isEmpty())

        assertTrue(elm.setProtocol(elm.baseProtocol))
        assertEquals(1, obd.readPids(listOf(0x0C)).size)
    }

    @Test
    fun limitedCloneStillScansModules() = runBlocking {
        val (_, elm, mc) = connect(clone = true)
        val abs = Families.FORD.modules.first { it.shortName == "ABS" }
        assertEquals("C0035-31", mc.scan(abs).dtcs.single().fullCode)
        assertEquals(6, mc.discover().size) // falls back to ATCF/ATCM
        val caps = elm.checkCapabilities().toMap()
        assertFalse(caps.getValue("Receive filter (ATCRA) - module access"))
        assertTrue(caps.getValue("CAN filter / mask (ATCF, ATCM) - fallback filter"))
        assertTrue(caps.getValue("User CAN 125k protocol (ATSP B) - Ford/Mazda MS-CAN"))
        assertEquals(2, ObdClient(elm).also { it.discover() }.readPids(listOf(0x0C, 0x0D)).size)
    }

    @Test
    fun genuineAdapterPassesCapabilityCheck() = runBlocking {
        val (_, elm, _) = connect()
        val caps = elm.checkCapabilities()
        assertTrue(caps.toString(), caps.all { it.second })
        assertEquals(2, ObdClient(elm).also { it.discover() }.readPids(listOf(0x0C, 0x0D)).size)
    }
}

class DtcDetailsTest {
    @Test
    fun readsSnapshotAndExtendedData() = runBlocking {
        val t = DemoTransport(latencyMs = 0).also { it.open() }
        val elm = Elm327(t).also { it.initialize(ElmConfig()) }
        val mc = ModuleClient(elm)
        val abs = Families.FORD.modules.first { it.shortName == "ABS" }
        val dtc = mc.scan(abs).dtcs.single()
        val details = mc.readDtcDetails(abs, dtc)
        val snap = details.snapshots.single()
        val byName = snap.values.associate { it.name to it.value }
        assertEquals("42 km/h", byName["Vehicle speed"])
        assertEquals("750 rpm", byName["Engine RPM"])
        assertEquals("14.00 V", byName["Control module voltage"])
        assertEquals("01 86 A0", snap.values.last().value)
        assertEquals(listOf(1 to "3", 2 to "40"), details.extended)
        assertTrue(com.openobd.core.brand.FailureTypes.hint(0x31)!!.contains("No signal"))
    }
}
