package com.openobd.core

import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ModuleClient
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.elm.ElmException
import com.openobd.core.elm.ObdProtocol
import com.openobd.core.obd.DtcType
import com.openobd.core.obd.ObdClient
import com.openobd.core.obd.Pids
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Full sessions against simulated cars that speak each OBD-II protocol, with headers on and off. */
class LegacyProtocolTest {
    private val protocols = listOf(
        ObdProtocol.ISO_15765_4_CAN_29_500,
        ObdProtocol.J1850_PWM,
        ObdProtocol.J1850_VPW,
        ObdProtocol.ISO_9141_2,
        ObdProtocol.ISO_14230_4_KWP_5BAUD,
        ObdProtocol.ISO_14230_4_KWP_FAST,
    )

    private fun session(p: ObdProtocol, headers: Boolean) = runTest {
        val t = DemoTransport(latencyMs = 0, protocol = p)
        t.open()
        val log = StringBuilder()
        val elm = Elm327(t) { d, s -> log.append(d).append(s).append('\n') }
        val info = elm.initialize(ElmConfig(headersOn = headers))
        val what = "$p headers=$headers"
        assertEquals(what, p, info.protocol)
        val client = ObdClient(elm)
        assertEquals(p.isCan, client.isCan)

        val supported = client.discover()
        assertTrue("$what: $supported", 0x0C in supported && 0x0D in supported && 0xA6 in supported)
        if (headers) assertEquals(what, "10", client.ecus.first().address)

        // Live data: multi-PID batching on CAN, one request per PID otherwise.
        val data = client.readPids(listOf(0x0C, 0x0D, 0x05, 0x11, 0x04, 0x42))
        assertEquals("$what: ${data.keys}", 6, data.size)
        assertTrue(what, Pids.sensors.getValue("010C").decode(data.getValue(0x0C))!! in 500.0..7000.0)
        assertTrue(what, Pids.sensors.getValue("0142").decode(data.getValue(0x42))!! in 13.0..15.0)

        // Trouble codes: CAN replies carry a count byte, pre-CAN frames hold 3 codes padded with zeros.
        t.stored += listOf("P0133", "P0300") // 4 codes -> 2 frames on pre-CAN protocols
        assertEquals(what, listOf("P0301", "P0420", "P0133", "P0300"), client.readDtcs(DtcType.STORED).codes.map { it.code })
        assertEquals(what, listOf("P0171"), client.readDtcs(DtcType.PENDING).codes.map { it.code })
        val permanent = client.readDtcs(DtcType.PERMANENT)
        if (p.isCan) assertEquals(what, listOf("P0420"), permanent.codes.map { it.code })
        else assertTrue(what, permanent.codes.isEmpty() && permanent.error == null) // service 0A is CAN only

        val ff = client.readFreezeFrame()
        assertNotNull(what, ff)
        assertEquals(what, "P0301", ff!!.dtc)
        assertEquals(what, 850.0, ff.values.getValue("010C"), 0.1)

        // VIN: one ISO-TP message on CAN, five numbered messages with 3 pad bytes otherwise.
        assertEquals(what, "1HGCM82633A004352", client.readVin())
        val vi = client.readVehicleInfo()
        assertEquals(what, "1HGCM82633A004352", vi.vin)
        assertEquals(what, listOf("OPENOBD-DEMO-1.0"), vi.calibrationIds.map { it.second })
        assertEquals(what, listOf("1B3C9A7F"), vi.cvns.map { it.second })

        val readiness = client.readReadiness()!!
        assertTrue(what, readiness.milOn)

        // Monitor tests (service 06 with scaling ids) are only defined for CAN.
        assertEquals(what, p.isCan, client.readMonitorTests().isNotEmpty())

        // Module access needs 11-bit CAN headers: refused cleanly, and live data keeps working afterwards.
        val modules = ModuleClient(elm)
        if (!p.isCan) {
            val r = modules.scan(EcuModule("ABS", "ABS", 0x760, 0x768))
            assertFalse(what, r.online)
            assertEquals(what, ModuleClient.NOT_CAN, r.error)
            assertTrue(what, client.readPids(listOf(0x0D)).containsKey(0x0D))
        }

        assertTrue(what, client.clearDtcs().success)
        assertTrue(what, client.readDtcs(DtcType.STORED).codes.isEmpty())
        assertFalse(what, client.readReadiness()!!.milOn)
    }

    @Test
    fun everyProtocolWithHeaders() { for (p in protocols) session(p, headers = true) }

    @Test
    fun everyProtocolWithoutHeaders() { for (p in protocols) session(p, headers = false) }

    @Test
    fun lockedToTheWrongProtocolFailsClearly() = runTest {
        for ((car, locked) in listOf(
            ObdProtocol.ISO_9141_2 to ObdProtocol.ISO_15765_4_CAN_11_500,
            ObdProtocol.ISO_15765_4_CAN_11_500 to ObdProtocol.ISO_14230_4_KWP_FAST,
            ObdProtocol.J1850_PWM to ObdProtocol.J1850_VPW,
        )) {
            val t = DemoTransport(latencyMs = 0, protocol = car)
            t.open()
            try {
                Elm327(t).initialize(ElmConfig(protocol = locked))
                fail("$car locked to $locked should not connect")
            } catch (e: ElmException) {
                assertNotNull(e.error)
            }
            // ...and the right protocol works on the same adapter.
            assertEquals(car, Elm327(t).initialize(ElmConfig(protocol = car)).protocol)
        }
    }

    @Test
    fun legacyFramesCarryChecksums() {
        // Inspect the raw frames: header bytes and checksum/CRC as a real adapter shows them with ATH1.
        fun raw(p: ObdProtocol): String {
            var out = ""
            kotlinx.coroutines.runBlocking {
                val t = DemoTransport(latencyMs = 0, protocol = p)
                t.open()
                val elm = Elm327(t)
                elm.initialize(ElmConfig())
                out = elm.command("0100").first()
            }
            return out
        }
        // ISO 9141-2: 48 6B 10 <data> <sum of all bytes mod 256>
        val iso = raw(ObdProtocol.ISO_9141_2).split(" ").map { it.toInt(16) }
        assertEquals(listOf(0x48, 0x6B, 0x10, 0x41, 0x00), iso.take(5))
        assertEquals(iso.dropLast(1).sum() and 0xFF, iso.last())
        // KWP: format byte 0x80 + data length
        val kwp = raw(ObdProtocol.ISO_14230_4_KWP_FAST).split(" ").map { it.toInt(16) }
        assertEquals(0x80 or (kwp.size - 4), kwp[0])
        assertEquals(kwp.dropLast(1).sum() and 0xFF, kwp.last())
        // J1850 PWM: 41 6B 10 ... CRC
        val pwm = raw(ObdProtocol.J1850_PWM).split(" ").map { it.toInt(16) }
        assertEquals(listOf(0x41, 0x6B, 0x10), pwm.take(3))
        // 29-bit CAN: 18 DA F1 10 <PCI> ...
        assertTrue(raw(ObdProtocol.ISO_15765_4_CAN_29_500).startsWith("18 DA F1 10 06 41 00"))
    }
}
