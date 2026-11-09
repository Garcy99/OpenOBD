package com.openobd.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openobd.data.ConnectionType
import com.openobd.transport.DeviceScanner
import com.openobd.ui.LocalApp
import com.openobd.ui.Permissions
import com.openobd.ui.appSettings
import com.openobd.ui.components.NoticeCard
import com.openobd.ui.components.ScreenColumn
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Violet
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.launch

/** What automatic trips still need on this phone. */
object AutoTripChecks {
    fun bluetooth(c: Context) = Permissions.granted(c, Permissions.bluetooth(false))
    fun location(c: Context) = Permissions.granted(c, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION))
    fun backgroundLocation(c: Context) = Build.VERSION.SDK_INT < 29 || Permissions.granted(c, arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    fun notifications(c: Context) = Permissions.granted(c, Permissions.notifications())
    fun battery(c: Context) = Build.VERSION.SDK_INT < 23 || c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true
}

/**
 * Automatic trips: every drive recorded without opening the app. The phone joining the car's own Bluetooth (its
 * radio or hands-free) starts the trip, leaving it (or the engine staying off) ends and saves it.
 */
@Composable
fun AutoTripsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = appSettings()
    var refresh by remember { mutableIntStateOf(0) }
    // Permissions granted in the system's screens show up when coming back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    fun update(f: (com.openobd.data.AppSettings) -> com.openobd.data.AppSettings) { scope.launch { app.settings.update(f) } }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Automatic trips"), onBack)
        ScreenColumn {
            Text(tr("Every drive is recorded without opening the app: when your phone joins the car's Bluetooth (its radio or hands-free), OpenOBD connects to the adapter in the background, records the trip with its route, and saves it when you switch the engine off."),
                style = MaterialTheme.typography.bodyMedium)
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Record trips automatically"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(s.autoTrips, { on -> update { it.copy(autoTrips = on) }; if (on) ask.launch((Permissions.bluetooth(false) + Permissions.notifications() + Permissions.location())) },
                        modifier = Modifier.testTag("autoTripsSwitch"))
                }
            }
            val bonded = remember(refresh) { if (AutoTripChecks.bluetooth(context)) DeviceScanner.bondedDevices(context).filter { it.address != s.deviceAddress } else emptyList() }
            SectionCard(title = tr("Your car's Bluetooth")) {
                Text(tr("The car's radio or hands-free that the phone joins when you get in (not the OBD adapter)."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!AutoTripChecks.bluetooth(context)) TextButton(onClick = { ask.launch(Permissions.bluetooth(false)) }) { Text(tr("Allow Bluetooth to see paired devices")) }
                else if (bonded.isEmpty()) Text(tr("No paired devices. Pair the phone with the car's radio first (for calls or music)."), style = MaterialTheme.typography.bodyMedium)
                bonded.sortedByDescending { it.looksLikeObd.not() }.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { update { it.copy(autoTripDevice = d.address, autoTripDeviceName = d.name ?: d.address) } }.testTag("carBt_${d.address}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(s.autoTripDevice == d.address, { update { it.copy(autoTripDevice = d.address, autoTripDeviceName = d.name ?: d.address) } })
                        Column {
                            Text(d.name ?: d.address)
                            if (d.looksLikeObd) Text(tr("Looks like an OBD adapter"), style = MaterialTheme.typography.bodySmall, color = Warn)
                        }
                    }
                }
            }
            SectionCard(title = tr("Checklist")) {
                androidx.compose.runtime.key(refresh) {
                    Check(tr("Adapter chosen on Home"), s.connectionType != ConnectionType.DEMO && s.connectionType != ConnectionType.PHONE &&
                        (s.deviceAddress != null || s.connectionType == ConnectionType.WIFI || s.connectionType == ConnectionType.USB), null) {}
                    Check(tr("Car's Bluetooth chosen"), s.autoTripDevice != null, null) {}
                    Check(tr("Bluetooth permission"), AutoTripChecks.bluetooth(context), tr("Allow")) { ask.launch(Permissions.bluetooth(false)) }
                    Check(tr("Location while recording"), AutoTripChecks.location(context), tr("Allow")) { ask.launch(Permissions.location()) }
                    if (AutoTripChecks.location(context)) Check(tr("Location \"Allow all the time\" (the route of trips that start on their own)"), AutoTripChecks.backgroundLocation(context), tr("Allow")) {
                        if (Build.VERSION.SDK_INT >= 29) ask.launch(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    }
                    if (Build.VERSION.SDK_INT >= 33) Check(tr("Notifications (to know a trip was saved)"), AutoTripChecks.notifications(context), tr("Allow")) { ask.launch(Permissions.notifications()) }
                    Check(tr("Not stopped by battery saving"), AutoTripChecks.battery(context), tr("Open")) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }
            }
            if (s.autoTrips && s.autoTripDevice != null) NoticeCard(Icons.Default.Route, tr("Ready"),
                tr("The next time the phone joins %1\$s, the trip starts by itself.", s.autoTripDeviceName ?: s.autoTripDevice), Good)
            Text(tr("Some phones close apps in the background anyway. If a trip is missed, check that battery saving leaves OpenOBD alone (Settings → Apps → OpenOBD → Battery → Unrestricted)."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, action: String?, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, null, Modifier.size(22.dp), tint = if (ok) Good else Warn)
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok && action != null) TextButton(onClick = onFix) { Text(action) }
    }
}

/** Home: automatic trips on (and on which car), or the offer to turn them on. */
@Composable
fun AutoTripsCard(onOpen: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val s = appSettings()
    if (s.connectionType == ConnectionType.DEMO || s.connectionType == ConnectionType.PHONE) return
    if (s.autoTrips && s.autoTripDevice != null) {
        NoticeCard(Icons.Default.Route, tr("Automatic trips on"), tr("Every drive is recorded when the phone joins %1\$s.", s.autoTripDeviceName ?: s.autoTripDevice),
            Good, Modifier.testTag("autoTripsCard"), onClick = onOpen)
    } else if (!s.autoTripsOffered) {
        NoticeCard(Icons.Default.Route, tr("Record every drive automatically"), tr("No need to open the app: trips start and stop with the car."),
            Violet, Modifier.testTag("autoTripsCard"),
            trailing = { IconButton(onClick = { scope.launch { app.settings.update { it.copy(autoTripsOffered = true) } } }) { Icon(Icons.Default.Close, tr("Not now")) } },
            onClick = onOpen)
    }
}

/** Phrases of the automatic trip screens, for translation coverage. */
val autoTripScreenTexts = listOf("Allow", "Open", "Not now")
