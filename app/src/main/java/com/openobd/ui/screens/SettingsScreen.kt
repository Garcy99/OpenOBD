package com.openobd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
        ScreenTopBar("Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard(title = "Display") {
                Label("Units")
                Chips(UnitSystem.entries, s.units, { if (it == UnitSystem.METRIC) "Metric" else "Imperial (US)" }) { u -> update { it.copy(units = u) } }
                Label("Theme")
                Chips(ThemeMode.entries, s.theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { m -> update { it.copy(theme = m) } }
                SwitchRow("Keep screen on while connected", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
                NumberField("Shift light RPM (0 = off)", s.shiftLightRpm.toString()) { v -> v.toIntOrNull()?.let { n -> update { it.copy(shiftLightRpm = n) } } }
            }
            SectionCard(title = "Connection") {
                SwitchRow("Auto-connect on start and reconnect when lost", s.autoConnect) { v -> update { it.copy(autoConnect = v) } }
                Label("OBD protocol")
                Dropdown(ObdProtocol.selectable, s.protocol, { it.label }) { p -> update { it.copy(protocol = p) } }
                Label("Adaptive timing (ATAT)")
                Chips(listOf(0, 1, 2), s.adaptiveTiming, { listOf("Off", "Normal", "Aggressive")[it] }) { v -> update { it.copy(adaptiveTiming = v) } }
                NumberField("Adapter response timeout in ms (0 = default)", s.elmTimeoutMs.toString()) { v -> v.toIntOrNull()?.let { n -> update { it.copy(elmTimeoutMs = n) } } }
                SwitchRow("Multi-PID requests (faster on CAN)", s.batching) { v -> update { it.copy(batching = v) } }
                SwitchRow("Fast responses (response count hint)", s.fastMode) { v -> update { it.copy(fastMode = v) } }
                Text("If your adapter is a cheap clone and data looks wrong, turn off the two options above. Changes apply on the next connection.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard(title = "Vehicle") {
                Label("Fuel type (for consumption)")
                Dropdown(FuelKind.entries, s.fuel, { it.label }) { f -> update { it.copy(fuel = f) } }
                NumberField("Engine displacement (L) - used when there is no MAF sensor", s.displacementL.toString()) { v ->
                    v.replace(',', '.').toFloatOrNull()?.let { d -> update { it.copy(displacementL = d) } }
                }
                NumberField("Fuel price per liter (trip cost)", s.fuelPricePerL.toString()) { v ->
                    v.replace(',', '.').toFloatOrNull()?.let { d -> update { it.copy(fuelPricePerL = d) } }
                }
            }
            SectionCard(title = "Alerts") {
                Text("Vibrate and show a warning when a sensor crosses a threshold (values in metric units).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.alerts.forEach { rule ->
                    val def = SensorCatalog.get(rule.sensorKey)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(def?.name ?: rule.sensorKey, fontWeight = FontWeight.Medium)
                            Text((if (rule.above) "above " else "below ") + rule.threshold + " " + (def?.unit?.symbol ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(rule.enabled, { v -> update { st -> st.copy(alerts = st.alerts.map { if (it.id == rule.id) it.copy(enabled = v) else it }) } })
                        IconButton(onClick = { update { st -> st.copy(alerts = st.alerts.filter { it.id != rule.id }) } }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
                TextButton(onClick = { addingAlert = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add alert") }
                if (s.alerts.isEmpty()) TextButton(onClick = {
                    update { it.copy(alerts = listOf(AlertRule(1, "0105", true, 108.0), AlertRule(2, "0142", false, 12.0))) }
                }) { Text("Add suggested alerts (overheating, low voltage)") }
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
        title = { Text("New alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key?.let { SensorCatalog.get(it)?.name } ?: "", {}, readOnly = true, label = { Text("Sensor") },
                    modifier = Modifier.fillMaxWidth().clickable { picking = true }, enabled = false)
                TextButton(onClick = { picking = true }) { Text("Choose sensor") }
                Chips(listOf(true, false), above, { if (it) "Above" else "Below" }) { above = it }
                OutlinedTextField(threshold, { threshold = it }, label = { Text("Threshold (" + (key?.let { SensorCatalog.get(it)?.unit?.symbol } ?: "") + ")") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(AlertRule(System.currentTimeMillis(), key!!, above, threshold.replace(',', '.').toDouble())) },
                enabled = key != null && threshold.replace(',', '.').toDoubleOrNull() != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) SensorPickerDialog(sensors, "Alert sensor", onDismiss = { picking = false }, onPick = { key = it.key; picking = false })
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
