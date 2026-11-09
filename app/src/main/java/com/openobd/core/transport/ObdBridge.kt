package com.openobd.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lets another screen use this phone's adapter: the phone keeps the link to the adapter (Bluetooth, USB…) and
 * passes every byte both ways to whoever connects over Wi-Fi, exactly like a Wi-Fi ELM327 would. The car's head
 * unit then runs OpenOBD as if the adapter were its own, even when its own Bluetooth cannot hold the adapter.
 *
 * One screen at a time: a new one replaces the previous (a head unit that lost Wi-Fi and comes back). If the link to
 * the adapter drops, it is opened again.
 */
class ObdBridge(
    private val openAdapter: suspend () -> ObdTransport,
    private val ports: List<Int> = listOf(DEFAULT_PORT, DEFAULT_PORT + 1, DEFAULT_PORT + 2, 0),
) {
    data class State(
        val running: Boolean = false,
        val adapter: String? = null,
        val port: Int = 0,
        /** Address of the screen connected now. */
        val client: String? = null,
        val toCar: Long = 0,
        val fromCar: Long = 0,
        /** English message (translated by the app). */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Runs until cancelled. */
    suspend fun run() = coroutineScope {
        val server = withContext(Dispatchers.IO) { bind() }
        _state.value = State(running = true, port = server.localPort)
        var client: Socket? = null
        var pump: Job? = null
        var adapter: ObdTransport? = null
        try {
            while (true) {
                val t = adapter?.takeIf { it.isOpen } ?: try {
                    adapter?.let { runCatching { it.close() } }
                    _state.update { it.copy(adapter = null, error = null) }
                    openAdapter().also { withTimeout(30_000) { it.open() }; adapter = it }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(adapter = null, error = e.message ?: "Could not open the adapter") }
                    delay(5_000)
                    continue
                }
                _state.update { it.copy(adapter = t.description, error = null) }
                // Wait for a screen; check the adapter link every couple of seconds.
                val s = withContext(Dispatchers.IO) {
                    server.soTimeout = 2_000
                    try { server.accept() } catch (_: java.net.SocketTimeoutException) { null }
                } ?: continue
                // Closing the old socket first frees its blocked read, so the old pump can end.
                client?.let { runCatching { it.close() } }
                pump?.cancelAndJoin()
                client = s
                // Answers the previous screen was still waiting for are of no use to the new one.
                while (t.incoming.tryReceive().isSuccess) Unit
                pump = launch { serve(s, t) }
            }
        } finally {
            // Everything inside NonCancellable: coming back from the IO thread would otherwise end this block early.
            withContext(kotlinx.coroutines.NonCancellable) {
                withContext(Dispatchers.IO) {
                    runCatching { client?.close() }
                    pump?.cancelAndJoin()
                    runCatching { server.close() }
                    runCatching { adapter?.close() }
                }
                _state.value = State()
            }
        }
    }

    private fun bind(): ServerSocket {
        var last: IOException? = null
        for (p in ports) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                return s
            } catch (e: IOException) {
                last = e; runCatching { s.close() }
            }
        }
        throw last ?: IOException("No free port")
    }

    /** Copies bytes both ways until the screen leaves or the adapter link drops. */
    private suspend fun serve(s: Socket, t: ObdTransport) = coroutineScope {
        s.tcpNoDelay = true
        _state.update { it.copy(client = s.inetAddress?.hostAddress) }
        val out = s.getOutputStream()
        val fromCar = launch(Dispatchers.IO) {
            for (bytes in t.incoming) {
                try { out.write(bytes); out.flush() } catch (_: IOException) { break }
                _state.update { it.copy(fromCar = _state.value.fromCar + bytes.size) }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val input = s.getInputStream()
                val buf = ByteArray(1024)
                while (true) {
                    val n = try { input.read(buf) } catch (_: IOException) { -1 }
                    if (n < 0) break
                    if (n == 0) continue
                    if (!t.isOpen) break
                    t.send(buf.copyOf(n))
                    _state.update { it.copy(toCar = _state.value.toCar + n) }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        } finally {
            fromCar.cancel()
            runCatching { s.close() }
            _state.update { it.copy(client = null) }
        }
    }

    companion object {
        const val DEFAULT_PORT = 35000
        /** Service type the phone announces on the network, so the head unit finds it without typing an address. */
        const val SERVICE_TYPE = "_openobd._tcp."
        val texts = listOf("Could not open the adapter")
    }
}

/** For running a bridge in a scope and stopping it. */
class BridgeRunner(private val scope: CoroutineScope, private val bridge: () -> ObdBridge) {
    private var job: Job? = null
    var current: ObdBridge? = null
        private set
    val running: Boolean get() = job?.isActive == true

    fun start(): ObdBridge {
        stop()
        val b = bridge()
        current = b
        job = scope.launch { b.run() }
        return b
    }

    fun stop() {
        job?.cancel(); job = null
    }
}
