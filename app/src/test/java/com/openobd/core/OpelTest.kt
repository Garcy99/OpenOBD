package com.openobd.core

import com.openobd.core.brand.Brands
import com.openobd.core.brand.DiagProtocol
import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.Families
import com.openobd.core.brand.ModuleClient
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opel / Vauxhall (GM Global A, e.g. Astra J): GMLAN codes, 0x24x addressing and 95.2 kbps MS-CAN. */
class OpelTest {
    private val ebcm = EcuModule("Electronic brake control (ABS / ESP)", "EBCM", 0x243, 0x643)

    private suspend fun connect(limitedClone: Boolean = false): Triple<DemoTransport, Elm327, ObdClient> {
        val t = DemoTransport(latencyMs = 0, limitedClone = limitedClone)
        t.open()
        val elm = Elm327(t)
        elm.initialize(ElmConfig())
        val client = ObdClient(elm)
        client.discover()
        return Triple(t, elm, client)
    }

    @Test
    fun astraVinIsOpelOnGmEurope() {
        val brand = Brands.detect("W0LPE8EE5E1000001")!!
        assertEquals("opel", brand.id)
        assertEquals(Families.OPEL, brand.family)
        assertEquals("ATPB F1 06", brand.family.secondaryBus!!.atpb)
        // 500 kbps * 8/7 / 6 = 95.24 kbps
        assertEquals(95.24, 500.0 * 8 / 7 / 6, 0.01)
        assertTrue(brand.family.discoveryRanges.any { 0x243 in it.first && it.second == "6XX" })
    }

    @Test
    fun gmlanModuleCodesAreReadAndCleared() = runTest {
        val (demo, elm, client) = connect()
        val modules = ModuleClient(elm).apply { gmlan = true }
        val r = modules.scan(ebcm)
        assertTrue(r.error, r.online)
        assertEquals(DiagProtocol.GMLAN, r.protocol)
        assertEquals(listOf("C0561", "C0035"), r.dtcs.map { it.code })
        assertEquals(0x71, r.dtcs.first().failureType)

        // Live data still works: auto formatting and filters are restored after the raw GMLAN read.
        assertEquals(2, client.readPids(listOf(0x0C, 0x0D)).size)

        assertTrue(modules.clear(ebcm).success)
        assertTrue(demo.modules.getValue(0x243).dtcs.isEmpty())
        val again = modules.scan(ebcm)
        assertTrue(again.online && again.dtcs.isEmpty())
        assertEquals(2, client.readPids(listOf(0x0C, 0x0D)).size)
    }

    @Test
    fun withoutGmlanTheCodesStayInvisible() = runTest {
        val (_, elm, _) = connect()
        val r = ModuleClient(elm).scan(ebcm)
        // The module answers (negatively) to UDS / KWP, so it is online but its codes cannot be read that way.
        assertTrue(r.online)
        assertTrue(r.dtcs.isEmpty())
    }

    @Test
    fun discoveryFindsGmAddresses() = runTest {
        val (_, elm, client) = connect()
        val found = ModuleClient(elm).discover(0x240..0x25F, rxFilter = "6XX")
        assertEquals(listOf(0x243 to 0x643, 0x247 to 0x647), found.map { it.tx to it.rx })
        assertTrue(client.readPids(listOf(0x0D)).containsKey(0x0D))
    }

    @Test
    fun msCanAt95kReachesOpelInfotainment() = runTest {
        val (_, elm, _) = connect()
        val bus = Families.OPEL_MS
        assertTrue(elm.setUserCan(bus.atpb, needsCustomSpeed = true))
        val modules = ModuleClient(elm).apply { gmlan = true }
        val radio = EcuModule("Radio / infotainment", "RADIO", 0x244, 0x644, secondaryBus = true)
        val hvac = EcuModule("Climate control (HVAC)", "HVAC", 0x251, 0x651, secondaryBus = true)
        assertEquals(listOf("RADIO", "HVAC"), modules.ping(listOf(radio, hvac)).map { it.shortName })
        assertEquals(listOf("B1025"), modules.scan(radio).dtcs.map { it.code })

        // At Ford's 125 kbps the Opel modules are silent.
        assertTrue(elm.setUserCan(Families.FORD_MS.atpb, needsCustomSpeed = false))
        assertTrue(modules.ping(listOf(radio, hvac)).isEmpty())
    }

    @Test
    fun cloneWithoutAtpbCannotReach95k() = runTest {
        val (_, elm, _) = connect(limitedClone = true)
        assertFalse(elm.setUserCan(Families.OPEL_MS.atpb, needsCustomSpeed = true))
        // Ford's 125 kbps is the adapter default, so it still works without ATPB.
        assertTrue(elm.setUserCan(Families.FORD_MS.atpb, needsCustomSpeed = false))
    }

    @Test
    fun parsesGmlanFrames() {
        val mc = ModuleClient(Elm327(DemoTransport(latencyMs = 0)))
        val lines = listOf("5E8 81 01 71 00 6F 00 00 00", "5E8 81 24 63 00 2F 00 00 00", "7E8 03 7F A9 78", "5E8 81 00 00 00 FF 00 00 00", "5E8 81 03 00 00 6F 00 00 00")
        assertEquals(listOf("P0171", "P2463"), mc.parseGmlan(lines, 0x5E8)!!.map { it.code })
        assertEquals(null, mc.parseGmlan(listOf("NO DATA"), 0x5E8))
        assertEquals(0x5E8, ModuleClient.gmlanUudtId(EcuModule("ECM", "ECM", 0x7E0, 0x7E8)))
        assertEquals(0x543, ModuleClient.gmlanUudtId(ebcm))
    }
}
