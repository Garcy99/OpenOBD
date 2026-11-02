package com.openobd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(val name: String?, val address: String, val ble: Boolean, val rssi: Int? = null) {
    /** Heuristic: name suggests an OBD adapter, so we can sort it to the top. */
    val looksLikeObd: Boolean
        get() = name?.let { n -> OBD_HINTS.any { n.contains(it, ignoreCase = true) } } ?: false

    companion object {
        private val OBD_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VEEPEAK", "VGATE", "ICAR", "KONNWEI", "KW9", "CAR", "SCAN", "OBDLINK", "LELINK", "CARISTA", "BAFX", "THINKCAR", "ANCEL", "TOPDON", "FIXD", "BLUEDRIVER", "NEXAS")
    }
}

@SuppressLint("MissingPermission")
object DeviceScanner {
    fun bluetoothEnabled(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun bondedDevices(context: Context): List<FoundDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map {
                FoundDevice(it.name, it.address, it.type == BluetoothDevice.DEVICE_TYPE_LE)
            }.sortedByDescending { it.looksLikeObd }
        }.getOrDefault(emptyList())
    }

    /** Emits BLE devices as they are found. Collection cancels the scan. */
    fun scanBle(context: Context): Flow<FoundDevice> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner unavailable (is Bluetooth on?)"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                trySend(FoundDevice(result.scanRecord?.deviceName ?: d.name, d.address, true, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("BLE scan failed (error $errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }
}
