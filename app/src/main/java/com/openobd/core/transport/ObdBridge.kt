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
 * the adapter drops, it is opened again without disconnecting the screen.
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
        /** Times the link to the adapter dropped and was opened again while running. */
        val reconnects: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The link to the adapter now (replaced when it drops and is opened again). */
    @Volatile private var adapterNow: ObdTransport? = null

    /** The setup the screen gave the adapter (echo off, protocol, headers…), to give again to a reopened one. */
    private val setup = AdapterSetup()

    /** Runs until cancelled. */
    suspend fun run() = coroutineScope {
        val server = withContext(Dispatchers.IO) { bind() }
        _state.value = State(running = true, port = server.localPort)
        var client: Socket? = null
        var pump: Job? = null
        // The adapter is kept open on its own: when it drops (Bluetooth hiccup) it is opened again while the car
        // screen stays connected, which then only sees a short pause.
        val keeper = launch {
            while (true) {
                val t = adapterNow
                if (t != null && t.isOpen) { delay(300); continue }
                t?.let { old -> withContext(Dispatchers.IO) { runCatching { old.close() } } }
                adapterNow = null
                _state.update { it.copy(adapter = null) }
                try {
                    val fresh = openAdapter()
                    withTimeout(30_000) { fresh.open() }
                    // An adapter that restarted forgot what the screen set: it is told again before the screen goes on.
                    if (t != null) setup.replay(fresh)
                    adapterNow = fresh
                    _state.update { it.copy(adapter = fresh.description, error = null, reconnects = if (t != null) it.reconnects + 1 else it.reconnects) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _state.update { it.copy(adapter = null, error = e.message ?: "Could not open the adapter") }
                    delay(2_000)
                }
            }
        }
        try {
            while (true) {
                val s = withContext(Dispatchers.IO) {
                    server.soTimeout = 2_000
                    try { server.accept() } catch (_: java.net.SocketTimeoutException) { null }
                } ?: continue
                // Closing the old socket first frees its blocked read, so the old pump can end.
                client?.let { runCatching { it.close() } }
                pump?.cancelAndJoin()
                client = s
                // Answers the previous screen was still waiting for are of no use to the new one.
                adapterNow?.let { t -> while (t.incoming.tryReceive().isSuccess) Unit }
                setup.clear()
                pump = launch { serve(s) }
            }
        } finally {
            // Everything inside NonCancellable: coming back from the IO thread would otherwise end this block early.
            withContext(kotlinx.coroutines.NonCancellable) {
                keeper.cancel()
                withContext(Dispatchers.IO) {
                    runCatching { client?.close() }
                    pump?.cancelAndJoin()
                    runCatching { server.close() }
                    runCatching { adapterNow?.close() }
                }
                adapterNow = null
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

    /** Copies bytes both ways until the screen leaves, across reconnections of the adapter. */
    private suspend fun serve(s: Socket) = coroutineScope {
        s.tcpNoDelay = true
        runCatching { s.keepAlive = true }
        _state.update { it.copy(client = s.inetAddress?.hostAddress) }
        val out = s.getOutputStream()
        val fromCar = launch(Dispatchers.IO) {
            while (true) {
                val t = adapterNow
                if (t == null || !t.isOpen) { delay(50); continue }
                val bytes = kotlinx.coroutines.withTimeoutOrNull(250) { t.incoming.receive() } ?: continue
                try { out.write(bytes); out.flush() } catch (_: IOException) { break }
                _state.update { it.copy(fromCar = it.fromCar + bytes.size) }
            }
            runCatching { s.close() }
        }
        try {
            withContext(Dispatchers.IO) {
                val input = s.getInputStream()
                val buf = ByteArray(1024)
                while (true) {
                    val n = try { input.read(buf) } catch (_: IOException) { -1 }
                    if (n < 0) break
                    if (n == 0) continue
                    // While the adapter is being opened again, the request waits a little (then is dropped: the
                    // screen asks again when its answer does not come).
                    var t = adapterNow
                    var waited = 0
                    while ((t == null || !t.isOpen) && waited < 3_000) { delay(50); waited += 50; t = adapterNow }
                    if (t == null || !t.isOpen) continue
                    setup.watch(buf, n)
                    try { t.send(buf.copyOf(n)) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; continue }
                    _state.update { it.copy(toCar = it.toCar + n) }
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

/**
 * The configuration commands a screen sent to the adapter (AT commands that set echo, protocol, headers, filters…),
 * the latest of each kind, so they can be sent again to an adapter that was reopened (and may have restarted).
 */
class AdapterSetup {
    private val commands = LinkedHashMap<String, String>()
    private val line = StringBuilder()

    @Synchronized fun clear() { commands.clear(); line.setLength(0) }

    /** Reads what the screen sends, keeping the setup commands among it. */
    @Synchronized fun watch(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val ch = buf[i].toInt().toChar()
            if (ch == '\r' || ch == '\n') { take(line.toString()); line.setLength(0) } else if (line.length < 64) line.append(ch)
        }
    }

    private fun take(raw: String) {
        val cmd = raw.replace(" ", "").uppercase()
        if (!cmd.startsWith("AT")) return
        // Back to the factory settings: what came before no longer applies.
        if (cmd == "ATZ" || cmd == "ATWS" || cmd == "ATD") { commands.clear(); return }
        val kind = KINDS.firstOrNull { cmd.startsWith(it) } ?: return
        commands.remove(kind)
        commands[kind] = cmd
    }

    @Synchronized fun list(): List<String> = commands.values.toList()

    /** Sends the setup again, waiting for each answer (the prompt). */
    suspend fun replay(t: ObdTransport) {
        for (cmd in list()) {
            while (t.incoming.tryReceive().isSuccess) Unit
            t.send((cmd + "\r").toByteArray())
            kotlinx.coroutines.withTimeoutOrNull(2_000) {
                while (true) { if (t.incoming.receive().any { it == '>'.code.toByte() }) break }
            }
        }
        while (t.incoming.tryReceive().isSuccess) Unit
    }

    companion object {
        /** Setup commands by kind, longer first (ATFCSH before ATS…). */
        val KINDS = listOf("ATFCSH", "ATFCSD", "ATFCSM", "ATCRA", "ATCEA", "ATCAF", "ATCFC", "ATIIA", "ATSP", "ATTP", "ATSH", "ATST",
            "ATAT", "ATSW", "ATPB", "ATAR", "ATE", "ATL", "ATS", "ATH", "ATM")
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
