package com.openobd.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.VinDecoder
import com.openobd.data.ConnectionType
import com.openobd.session.ConnectionState
import com.openobd.session.Op
import com.openobd.transport.DeviceScanner
import com.openobd.transport.FoundDevice
import com.openobd.ui.LocalApp
import com.openobd.ui.Permissions
import com.openobd.ui.Routes
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.StatusDot
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onTab: (String) -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    val settings = appSettings()
    var showPicker by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            if (pendingConnect) session.connect() else showPicker = true
        } else {
            Toast.makeText(context, "Bluetooth permission is required to talk to the adapter", Toast.LENGTH_LONG).show()
        }
        pendingConnect = false
    }
    val needsBt = settings.connectionType == ConnectionType.BLUETOOTH || settings.connectionType == ConnectionType.BLE

    fun connect() {
        val notif = Permissions.notifications()
        if (notif.isNotEmpty() && !Permissions.granted(context, notif)) notifLauncher.launch(notif)
        if (needsBt && !Permissions.granted(context, Permissions.bluetooth())) {
            pendingConnect = true
            btLauncher.launch(Permissions.bluetooth())
        } else session.connect()
    }

    fun pick() {
        if (!Permissions.granted(context, Permissions.bluetooth())) btLauncher.launch(Permissions.bluetooth()) else showPicker = true
    }

    LaunchedEffect(Unit) {
        if (settings.autoConnect && state is ConnectionState.Disconnected &&
            (settings.deviceAddress != null || !needsBt) && (!needsBt || Permissions.granted(context, Permissions.bluetooth()))
        ) session.connect()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("OpenOBD", actions = {
            IconButton(onClick = { onNavigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings") }
        })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionCard(
                state = state,
                onType = { t -> scope.launch { app.settings.update { it.copy(connectionType = t) } } },
                onPick = ::pick,
                onConnect = ::connect,
                onDisconnect = session::disconnect,
            )
            when (val s = state) {
                is ConnectionState.Connected -> {
                    VehicleCard(s)
                    BrandCard { onNavigate(Routes.BRAND) }
                    HealthCard(onCodes = { onTab(Routes.DIAGNOSTICS) }, onReadiness = { onNavigate(Routes.READINESS) })
                    QuickActions(onNavigate, onTab)
                }
                else -> GettingStarted()
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPicker) {
        DevicePickerDialog(
            ble = settings.connectionType == ConnectionType.BLE,
            onDismiss = { showPicker = false },
            onPick = { d ->
                showPicker = false
                scope.launch {
                    app.settings.update {
                        it.copy(deviceAddress = d.address, deviceName = d.name,
                            connectionType = if (d.ble && it.connectionType == ConnectionType.BLE) ConnectionType.BLE else it.connectionType)
                    }
                }
            },
        )
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    onType: (ConnectionType) -> Unit,
    onPick: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val busy = state is ConnectionState.Connecting
    val connected = state is ConnectionState.Connected
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(when (state) { is ConnectionState.Connected -> Good; is ConnectionState.Connecting -> Warn; is ConnectionState.Failed -> Bad; else -> MaterialTheme.colorScheme.outline })
            Spacer(Modifier.width(10.dp))
            Text(
                when (state) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Connecting -> "Connecting…"
                    is ConnectionState.Failed -> "Connection failed"
                    ConnectionState.Disconnected -> "Not connected"
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ConnectionType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = settings.connectionType == t,
                        onClick = { onType(t) },
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(i, ConnectionType.entries.size),
                        label = { Text(if (t == ConnectionType.BLE) "BLE" else t.label, maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when (settings.connectionType) {
                ConnectionType.BLUETOOTH, ConnectionType.BLE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(settings.deviceName ?: if (settings.deviceAddress != null) "Unnamed adapter" else "No adapter selected", fontWeight = FontWeight.Medium)
                            settings.deviceAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        OutlinedButton(onClick = onPick, enabled = !busy) { Text("Choose") }
                    }
                    if (settings.connectionType == ConnectionType.BLUETOOTH) {
                        Text("Classic adapters must be paired first in Android Bluetooth settings (PIN is usually 1234 or 0000).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                ConnectionType.WIFI -> {
                    var host by remember(settings.wifiHost) { mutableStateOf(settings.wifiHost) }
                    var port by remember(settings.wifiPort) { mutableStateOf(settings.wifiPort.toString()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it; scope.launch { app.settings.update { s -> s.copy(wifiHost = it.trim()) } } },
                            label = { Text("IP address") }, singleLine = true, modifier = Modifier.weight(2f), enabled = !busy)
                        OutlinedTextField(port, { v -> port = v.filter(Char::isDigit); port.toIntOrNull()?.let { p -> scope.launch { app.settings.update { s -> s.copy(wifiPort = p) } } } },
                            label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text("Join the adapter's Wi-Fi network (often \"WiFi_OBDII\" or \"V-LINK\") before connecting.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                ConnectionType.DEMO -> Text("Explore every feature with a simulated car: live data, trouble codes, freeze frames and more.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val bus by LocalApp.current.session.bus.collectAsStateWithLifecycle()
        if (connected && bus != com.openobd.session.Bus.HS) {
            Text("Adapter is on MS-CAN: live data is paused. Return to HS-CAN in Brand features.", color = Warn,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        when (state) {
            is ConnectionState.Connecting -> {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            is ConnectionState.Failed -> ErrorText(state.message)
            else -> Unit
        }
        Spacer(Modifier.height(12.dp))
        if (connected || busy) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Cancel" else "Disconnect") }
        } else {
            val ready = settings.connectionType == ConnectionType.DEMO || settings.connectionType == ConnectionType.WIFI || settings.deviceAddress != null
            Button(onClick = onConnect, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state is ConnectionState.Failed) "Retry" else "Connect")
            }
        }
    }
}

@Composable
private fun VehicleCard(s: ConnectionState.Connected) {
    val v = s.vehicle
    val rate by LocalApp.current.session.live.rate.collectAsStateWithLifecycle()
    SectionCard(title = "Vehicle") {
        val vin = v.vin?.let { VinDecoder.decode(it) }
        if (vin != null) {
            Text(listOfNotNull(vin.modelYear?.toString(), vin.manufacturer).joinToString(" ").ifEmpty { "Unknown make" },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(vin.vin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        KeyValueRow("Protocol", v.elm.protocol.label)
        KeyValueRow("Adapter", v.elm.version + if (v.elm.isStn) " (STN)" else "")
        KeyValueRow("Link", v.transport)
        KeyValueRow("ECUs found", v.ecus.joinToString { it.address })
        KeyValueRow("Supported PIDs", v.supportedPids.count { it % 0x20 != 0 }.toString())
        v.voltage?.let { volts ->
            KeyValueRow("Battery", "%.1f V".format(volts), valueColor = when { volts < 11.8 -> Bad; volts < 12.3 -> Warn; else -> Good })
        }
        if (rate > 0) KeyValueRow("Live data rate", "%.1f updates/s".format(rate))
    }
}

@Composable
private fun BrandCard(onOpen: () -> Unit) {
    val session = LocalApp.current.session
    val brand by session.brand.collectAsStateWithLifecycle()
    val scan by session.moduleScan.collectAsStateWithLifecycle()
    SectionCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(brand?.let { "${it.name} features" } ?: "Choose your brand", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val done = (scan as? Op.Done)?.value
                Text(
                    if (done != null) "${done.count { it.online }} modules answered · ${done.sumOf { it.dtcs.size }} module code(s)"
                    else "Scan ABS, airbag, body and other modules",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
private fun HealthCard(onCodes: () -> Unit, onReadiness: () -> Unit) {
    val scan by LocalApp.current.session.dtcScan.collectAsStateWithLifecycle()
    SectionCard(title = "Health") {
        when (val s = scan) {
            is Op.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s.progress ?: "", style = MaterialTheme.typography.bodySmall) }
            is Op.Done -> {
                val r = s.value.readiness
                val stored = s.value.codes.count { it.type.name == "STORED" }
                val pending = s.value.codes.count { it.type.name == "PENDING" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r?.milOn == true || stored > 0) Icons.Default.Error else Icons.Default.CheckCircle, null,
                        tint = if (r?.milOn == true || stored > 0) Bad else Good, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (r?.milOn == true) "Check engine light ON" else if (stored + pending == 0) "No trouble codes" else "Trouble codes present",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("$stored confirmed · $pending pending · ${s.value.codes.size - stored - pending} permanent",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                r?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("${it.complete.size} monitors ready", Good)
                        if (it.incomplete.isNotEmpty()) Pill("${it.incomplete.size} not ready", Warn)
                    }
                }
            }
            is Op.Error -> ErrorText(s.message)
            Op.Idle -> Text("Not scanned yet")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onCodes, modifier = Modifier.weight(1f)) { Text("Trouble codes") }
            OutlinedButton(onClick = onReadiness, modifier = Modifier.weight(1f)) { Text("Readiness") }
        }
    }
}

@Composable
private fun QuickActions(onNavigate: (String) -> Unit, onTab: (String) -> Unit) {
    data class A(val label: String, val icon: ImageVector, val go: () -> Unit)
    val actions = listOf(
        A("Dashboard", Icons.Default.Speed) { onTab(Routes.DASHBOARD) },
        A("Live data", Icons.Default.ShowChart) { onTab(Routes.LIVE) },
        A("Trip", Icons.Default.Route) { onNavigate(Routes.TRIP) },
        A("0-100 timer", Icons.Default.Timer) { onNavigate(Routes.PERFORMANCE) },
        A("Vehicle info", Icons.Default.DirectionsCar) { onNavigate(Routes.VEHICLE) },
        A("Freeze frame", Icons.Default.Flag) { onNavigate(Routes.FREEZE) },
    )
    SectionCard(title = "Quick actions") {
        actions.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { a ->
                    Column(
                        Modifier.weight(1f).clickable(onClick = a.go).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(a.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(a.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun GettingStarted() {
    SectionCard(title = "Getting started") {
        val steps = listOf(
            "Plug the ELM327 adapter into the OBD-II port (usually under the dashboard, near the steering column).",
            "Turn the ignition ON (engine running is best for live data).",
            "Bluetooth classic: pair the adapter in Android settings. BLE: just scan. Wi-Fi: join its network.",
            "Choose the adapter above and press Connect.",
        )
        steps.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("No adapter? Choose Demo to try the app.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DevicePickerDialog(ble: Boolean, onDismiss: () -> Unit, onPick: (FoundDevice) -> Unit) {
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<FoundDevice>() }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val btOn = DeviceScanner.bluetoothEnabled(context)
    LaunchedEffect(ble) {
        devices.clear()
        if (!btOn) return@LaunchedEffect
        devices += DeviceScanner.bondedDevices(context).filter { !ble || it.ble }
        if (ble) {
            scanning = true
            try {
                kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    DeviceScanner.scanBle(context).collect { d ->
                        val idx = devices.indexOfFirst { it.address == d.address }
                        if (idx >= 0) devices[idx] = d else devices += d
                        devices.sortByDescending { it.looksLikeObd }
                    }
                }
            } catch (e: Exception) {
                error = e.message
            }
            scanning = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (ble) Icons.Default.BluetoothSearching else Icons.Default.Bluetooth, null) },
        title = { Text(if (ble) "Bluetooth LE adapters" else "Paired adapters") },
        text = {
            Column {
                if (!btOn) {
                    Text("Bluetooth is off. Turn it on and try again.")
                } else {
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    error?.let { ErrorText(it) }
                    if (devices.isEmpty() && !scanning) Text(if (ble) "No BLE devices found." else "No paired devices. Pair your adapter in Bluetooth settings first.")
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(devices, key = { it.address }) { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Build, null, tint = if (d.looksLikeObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name ?: "Unnamed device", fontWeight = if (d.looksLikeObd) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(d.address + (d.rssi?.let { "  ·  $it dBm" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (!ble) TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Pair new") }
        },
    )
}
