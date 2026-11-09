package com.openobd.core

import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import com.openobd.core.transport.ObdBridge
import com.openobd.transport.WifiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone bridge: a head unit talks to the phone over TCP exactly as to a Wi-Fi adapter, and reaches the car. */
class BridgeTest {
    private suspend fun waitFor(what: String, condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(20)
    }

    private suspend fun connectThrough(port: Int): Pair<WifiTransport, ObdClient> {
        val t = WifiTransport("127.0.0.1", port)
        t.open()
        val elm = Elm327(t)
        elm.initialize(ElmConfig())
        return t to ObdClient(elm)
    }

    @Test fun `a screen reads the car through the phone, and a second screen takes over`() = runBlocking {
        var opened = 0
        // Port 0: any free port, so tests never clash.
        val bridge = ObdBridge({ opened++; DemoTransport(latencyMs = 5) }, ports = listOf(0))
        val job = launch(Dispatchers.Default) { bridge.run() }
        waitFor("adapter open") { bridge.state.value.adapter != null && bridge.state.value.port > 0 }
        val port = bridge.state.value.port

        val (first, client) = connectThrough(port)
        waitFor("screen seen") { bridge.state.value.client != null }
        assertEquals("127.0.0.1", bridge.state.value.client)
        val supported = client.discover()
        assertTrue(0x0C in supported && 0x0D in supported)
        assertNotNull(client.readVin())
        val rpm = client.readPids(listOf(0x0C))[0x0C]
        assertTrue(rpm != null && rpm.size >= 2)
        assertTrue(bridge.state.value.toCar > 0 && bridge.state.value.fromCar > 0)

        // The head unit reconnects (Wi-Fi dropped): the new connection replaces the old one and works at once.
        val (second, client2) = connectThrough(port)
        assertTrue(0x0D in client2.discover())
        assertEquals(1, opened) // the adapter link stayed open throughout
        first.close(); second.close()

        job.cancelAndJoin()
        assertTrue("after stop: ${bridge.state.value}", !bridge.state.value.running)
    }

    @Test fun `an adapter that cannot be opened is retried and reported`() = runBlocking {
        var attempts = 0
        val bridge = ObdBridge({
            attempts++
            object : com.openobd.core.transport.ObdTransport() {
                override val description = "Broken"
                override suspend fun open() { throw com.openobd.core.transport.TransportException("Adapter not found") }
                override suspend fun send(data: ByteArray) {}
                override suspend fun close() {}
            }
        }, ports = listOf(0))
        val job = launch(Dispatchers.Default) { bridge.run() }
        waitFor("error shown") { bridge.state.value.error != null }
        assertEquals("Adapter not found", bridge.state.value.error)
        assertTrue(bridge.state.value.running && bridge.state.value.adapter == null)
        job.cancelAndJoin()
        assertEquals(1, attempts)
    }
}
