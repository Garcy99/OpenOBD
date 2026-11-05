package com.openobd.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.openobd.core.transport.BaudProbe
import com.openobd.core.transport.ObdTransport
import com.openobd.core.transport.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** A USB serial device that can talk to an ELM327 (FTDI, CH340, PL2303, CP210x or CDC-ACM chip). */
data class UsbAdapter(val device: UsbDevice, val driver: UsbSerialDriver) {
    val key: String get() = "%04X:%04X".format(device.vendorId, device.productId)
    val name: String get() = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { chip }
    val chip: String get() = driver.javaClass.simpleName.removeSuffix("SerialDriver").replace("Ch34x", "CH340").replace("Cp21xx", "CP210x")
        .replace("Prolific", "PL2303").replace("Ftdi", "FTDI").replace("CdcAcm", "CDC")
}

/**
 * ELM327 USB cable, common with car head units whose Bluetooth only talks to phones.
 * Finds the cable, asks for USB permission (granted automatically when Android opened the app on plug-in)
 * and detects the serial speed.
 */
class UsbSerialTransport(
    private val context: Context,
    /** "VID:PID" of the cable chosen earlier; any supported cable when null or not plugged in. */
    private val preferredKey: String?,
    /** Serial speed that worked last time, tried first. */
    private val preferredBaud: Int?,
    /** Remembers the speed that worked. */
    private val onBaudFound: (Int) -> Unit = {},
) : ObdTransport() {
    override var description: String = "USB adapter"
        private set

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: throw TransportException("This device has no USB host support")
        val adapters = find(context)
        val adapter = adapters.firstOrNull { it.key == preferredKey } ?: adapters.firstOrNull()
            ?: throw TransportException(NO_CABLE)
        if (!usb.hasPermission(adapter.device) && !requestPermission(usb, adapter.device)) throw TransportException(NO_PERMISSION)
        val connection = usb.openDevice(adapter.device) ?: throw TransportException(NO_PERMISSION)
        val p = adapter.driver.ports.firstOrNull() ?: throw TransportException(NO_CABLE)
        try {
            p.open(connection)
            runCatching { p.dtr = true; p.rts = true }
            val baud = BaudProbe.find(preferredBaud) { rate -> probe(p, rate) }
                ?: throw TransportException(NO_ANSWER)
            onBaudFound(baud)
            port = p
            description = "${adapter.name} (USB, $baud baud)"
            isOpen = true
            io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) { incoming.trySend(data) }
                override fun onRunError(e: Exception) { notifyDisconnected(e) }
            }).also { it.start() }
        } catch (e: Exception) {
            runCatching { p.close() }
            throw if (e is TransportException) e else TransportException("Could not open the USB cable: ${e.message}", e)
        }
    }

    /** Sets [rate], sends a harmless command and collects the reply for a moment. */
    private fun probe(p: UsbSerialPort, rate: Int): String {
        p.setParameters(rate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val buf = ByteArray(256)
        while (p.read(buf, 50) > 0) { /* drop what is left from the previous rate */ }
        p.write("\rATI\r".toByteArray(), 500)
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + 700
        while (System.currentTimeMillis() < end) {
            val n = p.read(buf, 100)
            if (n > 0) sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            if (sb.contains('>')) break
        }
        return sb.toString()
    }

    private suspend fun requestPermission(usb: UsbManager, device: UsbDevice): Boolean = withTimeout(60_000) {
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // The system adds the result to the intent, so it must be mutable (and explicit) on Android 12+.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        val p = port ?: throw TransportException("Not connected")
        try {
            p.write(data, 1000)
        } catch (e: Exception) {
            notifyDisconnected(e)
            throw TransportException("Connection lost", e)
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        isOpen = false
        runCatching { io?.listener = null; io?.stop() }
        io = null
        runCatching { port?.close() }
        port = null
    }

    companion object {
        private const val ACTION_PERMISSION = "com.openobd.USB_PERMISSION"
        const val NO_CABLE = "No USB OBD cable found. Plug the ELM327 USB cable into the car and into this device."
        const val NO_PERMISSION = "Allow OpenOBD to use the USB cable when Android asks."
        const val NO_ANSWER = "The USB cable did not answer like an ELM327 at any common speed."

        /** USB serial cables currently plugged in. */
        fun find(context: Context): List<UsbAdapter> {
            val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
            return runCatching { UsbSerialProber.getDefaultProber().findAllDrivers(usb).map { UsbAdapter(it.device, it) } }.getOrDefault(emptyList())
        }
    }
}
