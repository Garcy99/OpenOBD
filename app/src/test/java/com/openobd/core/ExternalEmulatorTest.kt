package com.openobd.core

import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ModuleClient
import com.openobd.core.debug.DebugLog
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.elm.ObdProtocol
import com.openobd.core.obd.DtcType
import com.openobd.core.obd.ObdClient
import com.openobd.core.obd.Pids
import com.openobd.core.obd.VinDecoder
import com.openobd.transport.WifiTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Talks over TCP to the independent Python ELM327-emulator (https://github.com/Ircama/ELM327-emulator),
 * exactly as the app talks to a Wi-Fi adapter. It was written by other people from the ELM327 data sheet
 * and real car captures, so it catches assumptions our own simulator shares with our driver.
 *
 *   pip install ELM327-emulator
 *   elm -n 35000 -s car -d
 *   ./gradlew testDebugUnitTest --tests '*ExternalEmulatorTest' -PelmEmulator=127.0.0.1:35000
 */
class ExternalEmulatorTest {
    private val target = System.getProperty("elmEmulator")
    private val log = DebugLog(20_000)
    private var transport: WifiTransport? = null

    @After
    fun tearDown() {
        runBlocking { transport?.close() }
        File(System.getProperty("screenshot.dir") ?: "build").parentFile?.let { File(it, "elm-emulator.log").writeText(log.format()) }
    }

    private fun connect(): Triple<Elm327, ObdClient, com.openobd.core.elm.ElmInfo> = runBlocking {
        assumeTrue("set -PelmEmulator=host:port to run", !target.isNullOrBlank())
        val (host, port) = target!!.split(":").let { it[0] to it[1].toInt() }
        val t = WifiTransport(host, port)
        transport = t
        t.open()
        val elm = Elm327(t) { d, s -> log.add(d, s) }
        val info = elm.initialize(ElmConfig()) { log.event(it) }
        val client = ObdClient(elm).apply { stn = info.isStn }
        Triple(elm, client, info)
    }

    private fun <T> step(name: String, block: suspend () -> T): T = runBlocking {
        log.event("--- $name")
        try { block() } catch (e: Throwable) { throw AssertionError("$name failed: ${e.message}\n\n${log.format().takeLast(6000)}", e) }
    }

    @Test
    fun fullSession() {
        val (elm, client, info) = connect()
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, info.protocol)

        val supported = step("discover") { client.discover() }
        assertTrue(supported.toString(), 0x0C in supported && 0x0D in supported && 0x05 in supported)
        assertEquals("7E8", client.ecus.first().address)

        // Batched (and, if the emulator claims to be an STN, STPX) reads must equal one-by-one reads.
        val pids = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0F, 0x10, 0x0B).filter { it in supported }
        val batched = step("batched read") { client.readPids(pids) }
        val single = step("single reads") { pids.associateWith { client.readPids(listOf(it))[it] } }
        assertEquals("batched ${batched.keys} vs single ${single.filterValues { it != null }.keys}", single.filterValues { it != null }.keys, batched.keys)
        for ((pid, bytes) in batched) for (s in Pids.all.getValue(pid).sensors) {
            val v = s.decodeSafe(bytes)
            assertNotNull("${s.key} undecodable", v)
            assertTrue("${s.key} = $v outside ${s.min}..${s.max}", v!! >= s.min - 1e-6 && v <= s.max + 1e-6)
        }

        for (type in DtcType.entries) {
            val r = step("read $type") { client.readDtcs(type) }
            assertEquals("$type: ${r.error}", null, r.error)
        }
        val readiness = step("readiness") { client.readReadiness() }
        assertNotNull(readiness)

        val vin = step("VIN") { client.readVin() }
        assertNotNull(vin)
        assertEquals(17, vin!!.length)
        step("VIN decode") { VinDecoder.decode(vin) }

        val vi = step("vehicle info") { client.readVehicleInfo() }
        assertTrue(vi.calibrationIds.toString(), vi.calibrationIds.isNotEmpty())
        assertTrue(vi.cvns.toString(), vi.cvns.isNotEmpty())

        step("monitor tests") { client.readMonitorTests() }
        step("freeze frame") { client.readFreezeFrame() }

        // Module work through header/filter changes must leave the adapter usable for OBD.
        val modules = ModuleClient(elm)
        step("module scan") { modules.scan(EcuModule("ECM", "ECM", 0x7E0, 0x7E8)) }
        step("module identification") { runCatching { modules.readIdentification(EcuModule("ECM", "ECM", 0x7E0, 0x7E8)) } }
        assertTrue("OBD after module work", step("read after modules") { client.readPids(listOf(0x0C)) }.containsKey(0x0C))

        val caps = step("adapter check") { elm.checkCapabilities() }
        assertTrue(caps.toString(), caps.first { it.first.startsWith("Set header") }.second)
        assertTrue("OBD after adapter check", step("read after check") { client.readPids(listOf(0x0D)) }.containsKey(0x0D))

        val bench = step("benchmark") { client.benchmark(supported, rounds = 3) }
        assertTrue(bench.toString(), bench.pidsPerSecond > 0)

        assertTrue(step("clear") { client.clearDtcs() }.success)
        assertNotNull(step("voltage") { elm.readVoltage() })
    }
}
