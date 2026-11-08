package com.openobd.ui.screens

import com.openobd.ui.tr
import com.openobd.ui.shareFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.testTag
import com.openobd.ui.components.ErrorText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openobd.core.elm.ObdProtocol
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.obd.UnitSystem
import com.openobd.data.AlertRule
import com.openobd.data.AppSettings
import com.openobd.data.FuelKind
import com.openobd.data.ThemeMode
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.SensorPickerDialog
import com.openobd.ui.connectedVehicle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val s = appSettings()
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { app.settings.update(t) } }
    var addingAlert by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Settings"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard(title = tr("Display")) {
                Label(tr("Language"))
                Dropdown(com.openobd.ui.I18n.languages.map { it.first }, s.language, { code -> com.openobd.ui.I18n.languages.first { it.first == code }.second.let { if (code == "system") tr(it) else it } }) { l ->
                    update { it.copy(language = l) }
                }
                Label(tr("Units"))
                Chips(UnitSystem.entries, s.units, { if (it == UnitSystem.METRIC) tr("Metric") else tr("Imperial (US)") }) { u -> update { it.copy(units = u) } }
                Label(tr("Theme"))
                Chips(ThemeMode.entries, s.theme, { if (it == ThemeMode.AMOLED) tr("Black") else tr(it.name.lowercase().replaceFirstChar(Char::uppercase)) }) { m -> update { it.copy(theme = m) } }
                SwitchRow(tr("Keep screen on while connected"), s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
                NumberField(tr("Shift light RPM (0 = off)"), s.shiftLightRpm.toString()) { v -> v.toIntOrNull()?.let { n -> update { it.copy(shiftLightRpm = n) } } }
            }
            SectionCard(title = tr("Connection")) {
                SwitchRow(tr("Auto-connect on start and reconnect when lost"), s.autoConnect) { v -> update { it.copy(autoConnect = v) } }
                Label(tr("OBD protocol"))
                Dropdown(ObdProtocol.selectable, s.protocol, { it.label }) { p -> update { it.copy(protocol = p) } }
                Label(tr("Adaptive timing (ATAT)"))
                Chips(listOf(0, 1, 2), s.adaptiveTiming, { listOf(tr("Off"), tr("Normal"), tr("Aggressive"))[it] }) { v -> update { it.copy(adaptiveTiming = v) } }
                NumberField(tr("Adapter response timeout in ms (0 = default)"), s.elmTimeoutMs.toString()) { v -> v.toIntOrNull()?.let { n -> update { it.copy(elmTimeoutMs = n) } } }
                SwitchRow(tr("Multi-PID requests (faster on CAN)"), s.batching) { v -> update { it.copy(batching = v) } }
                SwitchRow(tr("Fast responses (response count hint)"), s.fastMode) { v -> update { it.copy(fastMode = v) } }
                SwitchRow(tr("Adapter has an HS/MS-CAN switch (Ford, Mazda, Opel)"), s.msCanSwitch) { v -> update { it.copy(msCanSwitch = v) } }
                Text(tr("If your adapter is a cheap clone and data looks wrong, turn off multi-PID and fast responses. Changes apply on the next connection."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AdapterCheck()
                SpeedTest()
            }
            SectionCard(title = tr("Vehicle")) {
                Label(tr("Fuel type (for consumption)"))
                Dropdown(FuelKind.entries, s.fuel, { tr(it.label) }) { f -> update { it.copy(fuel = f) } }
                NumberField(tr("Engine displacement (L) - used when there is no MAF sensor"), s.displacementL.toString()) { v ->
                    v.replace(',', '.').toFloatOrNull()?.let { d -> update { it.copy(displacementL = d) } }
                }
                SwitchRow(tr("Record GPS track for trips (shown on a map)"), s.tripGps) { v -> update { it.copy(tripGps = v) } }
                NumberField(tr("Fuel price per liter (trip cost)"), s.fuelPricePerL.toString()) { v ->
                    v.replace(',', '.').toFloatOrNull()?.let { d -> update { it.copy(fuelPricePerL = d) } }
                }
            }
            SectionCard(title = tr("Demo simulator")) {
                Text(tr("Demo mode can imitate older cars and bad adapters, to see how the app copes without a real car."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Label(tr("Protocol of the simulated car"))
                Dropdown(AppSettings.DEMO_PROTOCOLS, s.demoProtocol, { it.label }) { p -> update { it.copy(demoProtocol = p) } }
                SwitchRow(tr("Unreliable adapter (fragmented replies, noise, errors, lost prompts)"), s.demoFaults) { v -> update { it.copy(demoFaults = v) } }
            }
            SectionCard(title = tr("AI assistant")) {
                Text(tr("Optional: explain faults in plain language with Claude, by Anthropic. Uses your own API key from console.anthropic.com (paid per use). ") +
                    tr("Only codes, sensor values and the car model are sent when you ask - never the VIN or your location."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var key by remember(s.claudeApiKey) { mutableStateOf(s.claudeApiKey) }
                OutlinedTextField(
                    key, { key = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr("Claude API key")) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                Row {
                    TextButton(onClick = { update { it.copy(claudeApiKey = key) } }, enabled = key != s.claudeApiKey) { Text(tr("Save")) }
                    if (s.claudeApiKey.isNotEmpty()) TextButton(onClick = { key = ""; update { it.copy(claudeApiKey = "") } }) { Text(tr("Remove key")) }
                }
            }
            SectionCard(title = tr("Google account and sync")) { SyncSection() }
            SectionCard(title = tr("Backup")) { BackupSection() }
            SectionCard(title = tr("Troubleshooting")) { DebugReportSection() }
            SectionCard(title = tr("Voice")) {
                SwitchRow(tr("Spoken alerts"), s.voiceAlerts) { v -> update { it.copy(voiceAlerts = v) } }
                Text(tr("Says your alerts out loud and warns by itself about overheating, a battery that is not charging and particulate filter cleaning (when the car reports it). Music is lowered while it speaks."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.voiceAlerts) TextButton(onClick = { app.session.voice.test() }) { Text(tr("Test the voice")) }
            }
            SectionCard(title = tr("Alerts")) {
                Text(tr("Vibrate and show a warning when a sensor crosses a threshold (values in metric units)."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.alerts.forEach { rule ->
                    val def = SensorCatalog.get(rule.sensorKey)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(def?.name?.let { tr(it) } ?: rule.sensorKey, fontWeight = FontWeight.Medium)
                            Text((if (rule.above) "above " else "below ") + rule.threshold + " " + (def?.unit?.symbol ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(rule.enabled, { v -> update { st -> st.copy(alerts = st.alerts.map { if (it.id == rule.id) it.copy(enabled = v) else it }) } })
                        IconButton(onClick = { update { st -> st.copy(alerts = st.alerts.filter { it.id != rule.id }) } }) { Icon(Icons.Default.Delete, tr("Delete")) }
                    }
                }
                TextButton(onClick = { addingAlert = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(tr("Add alert")) }
                if (s.alerts.isEmpty()) TextButton(onClick = {
                    update { it.copy(alerts = listOf(AlertRule(1, "0105", true, 108.0), AlertRule(2, "0142", false, 12.0))) }
                }) { Text(tr("Add suggested alerts (overheating, low voltage)")) }
            }
        }
    }
    if (addingAlert) AddAlertDialog(onDismiss = { addingAlert = false }) { rule ->
        addingAlert = false
        update { it.copy(alerts = it.alerts + rule) }
    }
}

@Composable
private fun AddAlertDialog(onDismiss: () -> Unit, onAdd: (AlertRule) -> Unit) {
    val vehicle = connectedVehicle()
    var key by remember { mutableStateOf<String?>(null) }
    var above by remember { mutableStateOf(true) }
    var threshold by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val sensors = SensorCatalog.available(vehicle?.supportedPids ?: (0..0xFF).toSet()).filter { it.labeler == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("New alert")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key?.let { SensorCatalog.get(it)?.name?.let { n -> tr(n) } } ?: "", {}, readOnly = true, label = { Text(tr("Sensor")) },
                    modifier = Modifier.fillMaxWidth().clickable { picking = true }, enabled = false)
                TextButton(onClick = { picking = true }) { Text(tr("Choose sensor")) }
                Chips(listOf(true, false), above, { if (it) tr("Above") else tr("Below") }) { above = it }
                OutlinedTextField(threshold, { threshold = it }, label = { Text(tr("Threshold (") + (key?.let { SensorCatalog.get(it)?.unit?.symbol } ?: "") + ")") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(AlertRule(System.currentTimeMillis(), key!!, above, threshold.replace(',', '.').toDouble())) },
                enabled = key != null && threshold.replace(',', '.').toDoubleOrNull() != null,
            ) { Text(tr("Add")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    if (picking) SensorPickerDialog(sensors, tr("Alert sensor"), onDismiss = { picking = false }, onPick = { key = it.key; picking = false })
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(o == selected, { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(text, { text = it; onChange(it) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable
private fun <T> Dropdown(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) { Text(label(selected)) }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

@Composable
private fun AdapterCheck() {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        running = true; error = null
        scope.launch {
            try { result = session.checkAdapter() } catch (e: Exception) { error = e.message }
            running = false
        }
    }, enabled = vehicle != null && !running) { Text(if (running) tr("Checking adapter…") else tr("Check adapter capabilities")) }
    if (vehicle == null) Text(tr("Connect to check which features your adapter supports (many \"v1.4/v1.5\" adapters are clones missing commands)."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.let { list ->
        val missing = list.count { !it.second }
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(if (missing == 0) tr("Adapter looks complete") else tr("%1\$s feature(s) missing", missing)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${vehicle?.elm?.version ?: "Adapter"} · ${vehicle?.elm?.deviceDescription ?: ""}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    list.forEach { (name, ok) ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(if (ok) "✓" else "✗", color = if (ok) com.openobd.ui.theme.Good else com.openobd.ui.theme.Bad, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (missing > 0) Text(tr("\nMissing commands usually mean a low-quality clone. Basic OBD functions still work; the features listed may not. ") +
                        tr("OpenOBD already falls back to alternatives where possible (e.g. CAN filter/mask instead of ATCRA)."),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
}

/**
 * Connects the user's Google account so cars, trips and history sync with a private folder in their own Drive,
 * across phones (and a future desktop program).
 */
@Composable
fun SyncSection() {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val st by app.sync.state.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<String?>(null) }
    val consent = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val token = app.sync.auth.fromConsent(context, r.data)
        if (token != null) scope.launch { app.sync.connect(token) } else problem = tr("Google access was not given.")
    }
    fun signIn() {
        problem = null
        scope.launch {
            runCatching { app.sync.auth.authorize(context) }.fold({ res ->
                when {
                    res.token != null -> app.sync.connect(res.token)
                    res.consent != null -> consent.launch(androidx.activity.result.IntentSenderRequest.Builder(res.consent.intentSender).build())
                    else -> problem = tr("Google access was not given.")
                }
            }, { e ->
                // 10 = DEVELOPER_ERROR: this build is not registered for Google sign-in (see docs/GOOGLE_SYNC.md).
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                problem = when (code) {
                    10 -> tr("Google sign-in is not set up for this build of the app yet.")
                    null -> tr("Google sign-in needs Google Play services, which this device does not have.")
                    else -> tr("Could not connect to Google: %1\$s", e.message ?: code.toString())
                }
            })
        }
    }
    Text(tr("Sign in with Google to keep your cars, maintenance, fuel log, trips with their GPS routes, health and scan history the same on every phone. Everything goes to a private folder of OpenOBD in your own Google Drive that only this app can see; no OpenOBD server is involved. API keys, the adapter and recorded logs stay on each phone."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!st.connected) {
        androidx.compose.material3.Button(onClick = { signIn() }, modifier = Modifier.testTag("googleSignIn")) { Text(tr("Connect Google account")) }
    } else {
        Text(tr("Connected as %1\$s", st.email ?: "Google"), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.testTag("syncAccount"))
        Text(
            when {
                st.syncing -> tr("Syncing…")
                st.lastSync > 0 -> tr("Last sync: %1\$s", java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(st.lastSync)))
                else -> tr("Not synced yet.")
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("syncStatus"),
        )
        st.error?.let { ErrorText(tr(it, app.sync.lastErrorDetail ?: "")) }
        Row {
            if (st.needsSignIn) TextButton(onClick = { signIn() }) { Text(tr("Sign in again")) }
            else TextButton(onClick = { scope.launch { app.sync.syncIfConnected(force = true) } }, enabled = !st.syncing, modifier = Modifier.testTag("syncNow")) { Text(tr("Sync now")) }
            TextButton(onClick = { scope.launch { app.sync.disconnect() } }) { Text(tr("Disconnect")) }
        }
        Text(tr("It syncs when you open and leave the app. Disconnecting keeps your data on this phone and in Drive."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    problem?.let { ErrorText(it) }
}

@Composable
fun BackupSection() {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val create = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching { context.contentResolver.openOutputStream(uri)!!.use { app.backupTo(it) } }
                .fold({ tr("Backup saved (%1\$s files).", it) }, { tr("Backup failed: %1\$s", it.message ?: "") })
        }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }
    Text(tr("Save your cars, maintenance, fuel log, trips, health history, scan history and logs to one file (for example on Google Drive), and restore them on a new phone. API keys are not included."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row {
        TextButton(onClick = { create.launch("openobd-backup-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".zip") }) { Text(tr("Back up")) }
        TextButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(tr("Restore")) }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(tr("Restore this backup?")) },
            text = { Text(tr("Your current cars, history and logs on this phone will be replaced by the ones in the backup.")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        message = runCatching { context.contentResolver.openInputStream(uri)!!.use { app.restoreFrom(it) } }
                            .fold({ tr("Restored %1\$s files.", it) }, { tr("Restore failed: %1\$s", tr(it.message ?: "")) })
                    }
                }) { Text(tr("Restore")) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
fun DebugReportSection() {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val crash by app.diagnostics.pendingCrash.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf(app.diagnostics.log.size) }
    Text(tr("If something does not work with your car or adapter, share a debug report. It contains the adapter traffic of this session and app/phone details. Bluetooth addresses and the VIN serial number are removed."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(tr("%1\$s log entries", entries), style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = {
            scope.launch {
                val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.diagnostics.writeReport(app.session.reports.debugSections(), includeCrash = true)
                }
                shareFile(context, f, "text/plain")
            }
        }) { Text(tr("Share debug report")) }
        TextButton(onClick = { app.diagnostics.log.clear(); entries = 0 }) { Text(tr("Clear log")) }
    }
    if (crash != null) {
        Text(tr("A crash report from the last run is included."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { app.diagnostics.dismissCrash() }) { Text(tr("Delete crash report")) }
    }
}

@Composable
private fun SpeedTest() {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.openobd.core.obd.ObdClient.Benchmark?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        running = true; error = null
        scope.launch {
            try { result = session.speedTest() } catch (e: Exception) { error = e.message }
            running = false
        }
    }, enabled = vehicle != null && !running) { Text(if (running) tr("Measuring…") else tr("Run live data speed test")) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.let { b ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(tr("%.0f PIDs per second").format(b.pidsPerSecond)) },
            text = {
                Column {
                    Text(tr("Single request: %.0f ms (%.1f per second)").format(b.singleMs, 1000 / b.singleMs))
                    b.batchMs?.let { Text(tr("Batch of %1\$s PIDs: %%.0f ms", b.batchSize).format(it)) }
                    Text(if (b.stnFast) tr("OBDLink/STN fast mode: active") else if (vehicle?.elm?.isStn == true) tr("OBDLink/STN fast mode: not usable with this car") else tr("OBDLink/STN fast mode: not available (ELM327 adapter)"))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            b.batchMs == null -> tr("Multi-PID requests are off or not supported by this car; turning them on (CAN cars) is usually the biggest speed-up.")
                            b.singleMs > 150 -> tr("Responses are slow. Try adaptive timing \"Aggressive\", or a timeout around 60-100 ms. Bluetooth clones are often limited to ~5-10 requests/s.")
                            else -> tr("Good. Fewer sensors on screen = faster updates for each one.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
}
