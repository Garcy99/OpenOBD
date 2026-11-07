package com.openobd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.openobd.core.transport.ObdTransport
import com.openobd.core.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Bluetooth Low Energy ELM327 adapters (Vgate iCar Pro BLE, Veepeak BLE, OBDLink CX, many clones, and
 * the only option on iOS-oriented adapters). They emulate a serial port with a notify + write characteristic.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val address: String,
    private val name: String?,
) : ObdTransport() {
    override val description: String = "${name ?: "BLE adapter"} ($address, BLE)"

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val writeMutex = Mutex()

    private var connected = CompletableDeferred<Boolean>()
    private var servicesDiscovered = CompletableDeferred<Boolean>()
    private var mtuChanged = CompletableDeferred<Int>()
    private var descriptorWritten = CompletableDeferred<Boolean>()
    @Volatile private var writeDone: CompletableDeferred<Boolean>? = null
    @Volatile private var lastStatus = 0

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(true)
            } else if (status != BluetoothGatt.GATT_SUCCESS && !connected.isCompleted) {
                // e.g. 133 (GATT_ERROR), very common on a first attempt: open() retries
                lastStatus = status
                connected.complete(false)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.complete(false)
                servicesDiscovered.complete(false)
                writeDone?.complete(false)
                notifyDisconnected(TransportException("BLE link lost (status $status)"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDiscovered.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuChanged.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value.copyOf())
        }

        @Deprecated("Pre-Android 13 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { incoming.trySend(it.copyOf()) }
        }
    }

    override suspend fun open() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportException("This device has no Bluetooth")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is turned off")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        // Android often fails the first GATT connection (status 133) and sometimes serves a stale list of
        // services from its cache, so retry a few times and refresh the cache before the last tries.
        var lastError: Exception? = null
        for (attempt in 1..3) {
            if (attempt > 1) delay(700L * attempt)
            try {
                connectOnce(device, refreshCache = attempt > 1)
                return
            } catch (e: TransportException) {
                lastError = e
            }
        }
        throw lastError ?: TransportException("BLE adapter ${name ?: address} did not connect")
    }

    private suspend fun connectOnce(device: BluetoothDevice, refreshCache: Boolean) {
        connected = CompletableDeferred(); servicesDiscovered = CompletableDeferred(); lastStatus = 0
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("Could not start BLE connection")
        gatt = g
        try {
            if (withTimeoutOrNull(15_000) { connected.await() } != true) {
                throw TransportException("BLE adapter ${name ?: address} did not connect" + if (lastStatus != 0) " (status $lastStatus)" else "")
            }
            if (refreshCache) refreshGattCache(g)
            delay(300) // some stacks drop a discovery started right after connecting
            mtuChanged = CompletableDeferred()
            if (g.requestMtu(247)) withTimeoutOrNull(3_000) { mtuChanged.await() }
            if (!g.discoverServices() || withTimeoutOrNull(10_000) { servicesDiscovered.await() } != true) {
                throw TransportException("Could not discover BLE services")
            }
            val (notify, write) = pickCharacteristics(g.services)
                ?: throw TransportException("This BLE device does not look like an OBD adapter (no serial characteristics)")
            writeChar = write
            enableNotifications(g, notify)
            isOpen = true
        } catch (e: Exception) {
            runCatching { g.disconnect(); g.close() }
            gatt = null
            throw if (e is TransportException) e else TransportException(e.message ?: "BLE error", e)
        }
    }

    /** Clears Android's cached service list for the device (hidden but long-standing API). */
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
    }

    private fun pickCharacteristics(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        fun canNotify(c: BluetoothGattCharacteristic) =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        fun canWrite(c: BluetoothGattCharacteristic) =
            c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        val ordered = services.sortedBy { s ->
            val idx = KNOWN_SERVICES.indexOf(s.uuid)
            if (idx >= 0) idx else KNOWN_SERVICES.size + (if (isStandard(s.uuid)) 100 else 0)
        }
        for (s in ordered) {
            if (isStandard(s.uuid)) continue
            val notify = s.characteristics.firstOrNull { canNotify(it) } ?: continue
            val write = s.characteristics.firstOrNull { canWrite(it) && it.uuid != notify.uuid }
                ?: s.characteristics.firstOrNull { canWrite(it) } ?: continue
            return notify to write
        }
        return null
    }

    private fun isStandard(uuid: UUID): Boolean {
        val s = uuid.toString().lowercase()
        return s.endsWith("-0000-1000-8000-00805f9b34fb") && s.substring(4, 8) in setOf("1800", "1801", "180a", "180f", "1805")
    }

    @Suppress("DEPRECATION")
    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        descriptorWritten = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
        if (started) withTimeoutOrNull(3_000) { descriptorWritten.await() }
    }

    @Suppress("DEPRECATION")
    override suspend fun send(data: ByteArray) = writeMutex.withLock {
        val g = gatt ?: throw TransportException("Not connected")
        val c = writeChar ?: throw TransportException("Not connected")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val chunk = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < data.size) {
            val part = data.copyOfRange(offset, minOf(data.size, offset + chunk))
            offset += part.size
            val done = CompletableDeferred<Boolean>()
            writeDone = done
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = part
                g.writeCharacteristic(c)
            }
            if (!started) throw TransportException("BLE write failed")
            if (withTimeoutOrNull(2_000) { done.await() } == false) throw TransportException("BLE write rejected")
        }
    }

    override suspend fun close() {
        isOpen = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        private fun uuid16(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = uuid16("2902")
        val KNOWN_SERVICES: List<UUID> = listOf(
            uuid16("fff0"), uuid16("ffe0"), uuid16("18f0"),
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"), // Vgate iCar Pro
            UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb"),
        )
    }
}
