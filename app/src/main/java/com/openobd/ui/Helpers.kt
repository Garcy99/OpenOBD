package com.openobd.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.data.AppSettings
import com.openobd.session.ConnectionState
import com.openobd.session.VehicleSnapshot
import java.io.File

/** Registers the sensors a screen needs for as long as it is shown. */
@Composable
fun SubscribeSensors(owner: String, keys: Collection<String>) {
    val live = LocalApp.current.session.live
    val set = keys.toSet()
    DisposableEffect(owner, set) {
        live.subscribe(owner, set)
        onDispose { live.unsubscribe(owner) }
    }
}

@Composable
fun connectedVehicle(): VehicleSnapshot? {
    val state by LocalApp.current.session.state.collectAsStateWithLifecycle()
    return (state as? ConnectionState.Connected)?.vehicle
}

@Composable
fun appSettings(): AppSettings {
    val s by LocalApp.current.settings.settings.collectAsStateWithLifecycle()
    return s
}

object Permissions {
    /**
     * Runtime permissions to use an adapter. Android 12+ has Bluetooth permissions; before that a paired
     * classic adapter needs none (common on car head units, often without location), only a BLE scan needs location.
     */
    fun bluetooth(ble: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        ble -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> emptyArray()
    }

    fun location(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun notifications(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun granted(context: Context, perms: Array<String>) =
        perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

fun shareText(context: Context, subject: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(i, subject))
}

fun shareFile(context: Context, file: File, mime: String = "text/csv") {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(i, file.name))
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
}
