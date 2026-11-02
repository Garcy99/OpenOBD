package com.openobd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.openobd.core.transport.ObdTransport
import com.openobd.core.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Classic Bluetooth (RFCOMM / Serial Port Profile) - the most common ELM327 adapter type. */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(
    private val context: Context,
    private val address: String,
    private val name: String?,
) : ObdTransport() {
    override val description: String = "${name ?: "Bluetooth adapter"} ($address)"

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw TransportException("Invalid Bluetooth address $address")
        }
        runCatching { adapter.cancelDiscovery() }

        // Cheap clones differ in what they accept, so try the standard secure socket, then insecure,
        // then the hidden channel-1 method that works on many problematic adapters.
        val factories: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for (factory in factories) {
            var s: BluetoothSocket? = null
            try {
                s = factory()
                s.connect()
                socket = s
                break
            } catch (e: Exception) {
                lastError = e
                runCatching { s?.close() }
            }
        }
        val s = socket ?: throw TransportException(
            "Could not connect to ${name ?: address}. Make sure the adapter is plugged in, the ignition is on and it is paired.",
            lastError,
        )
        output = s.outputStream
        isOpen = true
        startReader(s.inputStream)
    }

    private fun startReader(input: InputStream) {
        reader = Thread({
            val buf = ByteArray(1024)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) incoming.trySend(buf.copyOf(n))
                }
                notifyDisconnected(null)
            } catch (e: IOException) {
                notifyDisconnected(e)
            }
        }, "obd-bt-reader").apply { isDaemon = true; start() }
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw TransportException("Not connected")
        try {
            out.write(data)
            out.flush()
        } catch (e: IOException) {
            notifyDisconnected(e)
            throw TransportException("Connection lost", e)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        isOpen = false
        reader?.interrupt()
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
