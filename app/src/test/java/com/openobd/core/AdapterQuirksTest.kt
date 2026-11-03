package com.openobd.core

import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import com.openobd.core.transport.ObdTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour found by running against the independent ELM327-emulator (see ExternalEmulatorTest),
 * reproduced here so it is checked on every build without the Python emulator.
 */
class AdapterQuirksTest {
    /** Demo car with some commands answered differently: null = no answer at all (not even a prompt). */
    private class Quirky(private val overrides: Map<String, String?>, singleEcu: Boolean = false) : ObdTransport() {
        val demo = DemoTransport(latencyMs = 0, withTransmission = !singleEcu)
        override val description = "quirky"
        override suspend fun open() { demo.open(); isOpen = true }
        override suspend fun close() { demo.close(); isOpen = false }
        override suspend fun send(data: ByteArray) {
            val cmd = String(data).trim().replace(" ", "").uppercase()
            if (overrides.containsKey(cmd)) {
                overrides[cmd]?.let { incoming.trySend("$it\r\r>".toByteArray()) }
                return
            }
            demo.send(data)
            while (true) incoming.trySend(demo.incoming.tryReceive().getOrNull() ?: break)
        }
    }

    @Test
    fun adapterIgnoringBareCrConnectsWithoutWaiting() = runTest {
        val t = Quirky(mapOf("" to null))
        t.open()
        val start = testScheduler.currentTime
        Elm327(t).initialize(ElmConfig())
        // Only the fixed settle delays (and the short wake-up listen), no 1 s command timeout + resync.
        assertTrue("took ${testScheduler.currentTime - start} ms", testScheduler.currentTime - start < 1500)
    }

    @Test
    fun fakeStnWithoutStpxFallsBackQuickly() = runTest {
        val t = Quirky(mapOf("STI" to "STN1100 v1.2.3", "STPXD:010C0D05,R:1" to null), singleEcu = true)
        t.open()
        val elm = Elm327(t)
        val info = elm.initialize(ElmConfig())
        assertTrue(info.isStn)
        val client = ObdClient(elm).apply { stn = true }
        client.discover()
        val start = testScheduler.currentTime
        assertEquals(setOf(0x0C, 0x0D, 0x05), client.readPids(listOf(0x0C, 0x0D, 0x05)).keys)
        assertEquals(false, client.stnFastVerified)
        assertFalse(client.stnFastActive)
        assertTrue("verification took ${testScheduler.currentTime - start} ms", testScheduler.currentTime - start < 3000)
    }

    @Test
    fun strayBytesInMultiPidReplyAreNotReadAsValues() = runTest {
        // Emulator reply with an extra "41 04 00" inside the payload: 0C 0D 04 parse fine, then 41 is not requested.
        val t = Quirky(mapOf("010C0D0411050B" to "7E8 10 11 41 0C 23 82 0D 44\r7E8 21 04 00 41 04 00 11 50\r7E8 22 05 55 0B 63"))
        t.open()
        val elm = Elm327(t)
        elm.initialize(ElmConfig())
        val client = ObdClient(elm).apply { fastResponses = false }
        client.discover()
        val data = client.readPids(listOf(0x0C, 0x0D, 0x04, 0x11, 0x05, 0x0B))
        assertEquals(listOf(0x23, 0x82), data.getValue(0x0C).toList())
        assertEquals(listOf(0x44), data.getValue(0x0D).toList())
        assertEquals(listOf(0x00), data.getValue(0x04).toList())
        // Without the check, 0x41 would swallow "04 00 11 50" and 0x05 would read 0x55 from the wrong place.
        assertFalse(0x41 in data)
        assertFalse(data.keys.toString(), 0x11 in data || 0x05 in data || 0x0B in data)
    }
}
