package com.openobd.service

import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.openobd.OpenObdApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The car's Bluetooth linked to OpenOBD as a companion device (Android 12 and later). Android then wakes the app
 * itself when the phone joins the car, even where broadcasts to closed apps are held back (many brands do), and lets
 * it start the recording from the background without asking to leave battery saving.
 */
object CarCompanion {
    fun available(c: Context) = Build.VERSION.SDK_INT >= 31 && c.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun cdm(c: Context): CompanionDeviceManager? = if (Build.VERSION.SDK_INT >= 26) c.getSystemService(CompanionDeviceManager::class.java) else null

    /** Whether [address] is linked to the app. */
    fun linked(c: Context, address: String?): Boolean {
        if (address == null || !available(c)) return false
        val m = cdm(c) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) m.myAssociations.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }
            else @Suppress("DEPRECATION") m.associations.any { it.equals(address, ignoreCase = true) }
        }.getOrDefault(false)
    }

    /** Asks Android to link [address] (its own dialog, shown through [launch]); [onError] when it cannot. */
    fun link(c: Context, address: String, launch: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val m = cdm(c) ?: return onError("Not available on this phone")
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        runCatching {
            @Suppress("DEPRECATION")
            m.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Replaced by onAssociationPending on Android 13, which calls this by default")
                override fun onDeviceFound(chooserLauncher: IntentSender) = launch(chooserLauncher)
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Not linked")
            }, null)
        }.onFailure { onError(it.message ?: it.javaClass.simpleName) }
    }

    /** Asks Android to wake the app when [address] joins or leaves (again after each restart of the app: harmless). */
    fun observe(c: Context, address: String?) {
        if (address == null || Build.VERSION.SDK_INT < 31 || !linked(c, address)) return
        runCatching { cdm(c)?.startObservingDevicePresence(address) }
            .onFailure { (c.applicationContext as? OpenObdApp)?.container?.diagnostics?.log?.error("Automatic trip: Android did not accept watching the car", it) }
    }
}

/** Woken by Android when the linked car's Bluetooth joins or leaves the phone. */
@RequiresApi(31)
class CarPresenceService : CompanionDeviceService() {
    @Deprecated("Android 13 calls this from onDeviceAppeared(AssociationInfo) for Bluetooth devices")
    override fun onDeviceAppeared(address: String) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { AutoTripReceiver.handle(applicationContext, address, true, "companion") } }
    }

    @Deprecated("Android 13 calls this from onDeviceDisappeared(AssociationInfo) for Bluetooth devices")
    override fun onDeviceDisappeared(address: String) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { AutoTripReceiver.handle(applicationContext, address, false, "companion") } }
    }
}
