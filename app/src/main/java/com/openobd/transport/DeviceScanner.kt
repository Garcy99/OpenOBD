package com.openobd.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
                // Dual-mode adapters (e.g. vLinker MC+) can be reached over BLE too.
                FoundDevice(it.name, it.address, it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL)
            }.sortedByDescending { it.looksLikeObd }
        }.getOrDefault(emptyList())
    }

    /** Shown when Android refuses to start a BLE scan. */
    const val SCAN_BUSY = "Android could not start the Bluetooth LE search (too many searches in a row, or Bluetooth is stuck). " +
        "Wait 30 seconds or turn Bluetooth off and on, then search again."
    const val SCAN_UNSUPPORTED = "This device cannot search for Bluetooth LE devices."

    /**
     * Emits BLE devices as they are found. Collection cancels the scan.
     * When the modern scanner refuses to start (error 2 "registration failed" is common on phones and car
     * head units with a busy Bluetooth stack, 6 is "scanning too frequently"), falls back to the older API,
     * which takes a different path through the stack and often still works.
     */
    fun scanBle(context: Context): Flow<FoundDevice> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner unavailable (is Bluetooth on?)"))
            return@callbackFlow
        }
        @Suppress("DEPRECATION")
        val legacy = BluetoothAdapter.LeScanCallback { d, rssi, _ -> trySend(FoundDevice(d.name, d.address, true, rssi)) }
        var legacyRunning = false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                trySend(FoundDevice(result.scanRecord?.deviceName ?: d.name, d.address, true, result.rssi))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }

            override fun onScanFailed(errorCode: Int) {
                if (errorCode == SCAN_FAILED_FEATURE_UNSUPPORTED) { close(IllegalStateException(SCAN_UNSUPPORTED)); return }
                @Suppress("DEPRECATION")
                legacyRunning = runCatching { adapter.startLeScan(legacy) }.getOrDefault(false)
                if (!legacyRunning) close(IllegalStateException(SCAN_BUSY))
            }
        }
        runCatching { scanner.stopScan(cb) } // clears a scan left over from an earlier attempt
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose {
            runCatching { scanner.stopScan(cb) }
            @Suppress("DEPRECATION")
            if (legacyRunning) runCatching { adapter.stopLeScan(legacy) }
        }
    }

    /** Before Android 12 a BLE scan finds nothing while the system Location switch is off. */
    fun bleNeedsLocationOn(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 &&
            context.getSystemService(android.location.LocationManager::class.java)?.let { !androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } == true
}
