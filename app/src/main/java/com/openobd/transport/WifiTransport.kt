package com.openobd.transport

import com.openobd.core.transport.ObdTransport
import com.openobd.core.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Wi-Fi ELM327 adapters expose a raw TCP socket, typically 192.168.0.10:35000. */
class WifiTransport(private val host: String, private val port: Int, label: String? = null) : ObdTransport() {
    override val description: String = label ?: "Wi-Fi adapter ($host:$port)"
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 6000)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw TransportException("Could not reach $host:$port. Connect your phone to the adapter's Wi-Fi network first.", e)
        }
        socket = s
        output = s.getOutputStream()
        isOpen = true
        val input = s.getInputStream()
        reader = Thread({
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) incoming.trySend(buf.copyOf(n))
                }
                notifyDisconnected(null)
            } catch (e: IOException) {
                notifyDisconnected(e)
            }
        }, "obd-wifi-reader").apply { isDaemon = true; start() }
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            (output ?: throw TransportException("Not connected")).apply { write(data); flush() }
        } catch (e: IOException) {
            notifyDisconnected(e)
            throw TransportException("Connection lost", e)
        }
        Unit
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        isOpen = false
        runCatching { socket?.close() }
        reader?.interrupt()
        socket = null
    }
}
