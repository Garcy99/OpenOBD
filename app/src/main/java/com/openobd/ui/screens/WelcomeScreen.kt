package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobd.data.ConnectionType
import com.openobd.ui.I18n
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.tr
import kotlinx.coroutines.launch

/** First-run setup: language, adapter type, car, and what the app can do. */
@Composable
fun WelcomeScreen(onDone: () -> Unit, onGarage: () -> Unit) {
    val app = LocalApp.current
    val s = appSettings()
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    val pages = 4
    fun finish() { scope.launch { app.settings.update { it.copy(onboarded = true) } }; onDone() }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(pages) { i ->
                Box(Modifier.padding(4.dp).size(if (i == page) 10.dp else 8.dp).background(
                    if (i <= page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (page) {
                0 -> {
                    Text(tr("Welcome to OpenOBD"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(tr("Read and clear trouble codes in every module, watch live data, check a used car, follow your car's health and much more - free and open source."),
                        style = MaterialTheme.typography.bodyLarge)
                    Text(tr("Language"), style = MaterialTheme.typography.titleMedium)
                    I18n.languages.forEach { (code, name) ->
                        Choice(if (code == "system") tr(name) else name, selected = s.language == code) { scope.launch { app.settings.update { it.copy(language = code) } } }
                    }
                }
                1 -> {
                    Text(tr("Your adapter"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Choice(tr("Bluetooth (most ELM327 adapters)"), tr("Pair it first in Android's Bluetooth settings (PIN usually 1234 or 0000)."), s.connectionType == ConnectionType.BLUETOOTH) {
                        scope.launch { app.settings.update { it.copy(connectionType = ConnectionType.BLUETOOTH) } }
                    }
                    Choice(tr("Bluetooth LE (iPhone-compatible adapters)"), tr("No pairing needed: OpenOBD scans for it."), s.connectionType == ConnectionType.BLE) {
                        scope.launch { app.settings.update { it.copy(connectionType = ConnectionType.BLE) } }
                    }
                    Choice(tr("Wi-Fi"), tr("Join the adapter's Wi-Fi network first (usually 192.168.0.10:35000)."), s.connectionType == ConnectionType.WIFI) {
                        scope.launch { app.settings.update { it.copy(connectionType = ConnectionType.WIFI) } }
                    }
                    Choice(tr("USB cable (car head units)"), tr("Plug the ELM327 USB cable in; Android offers to open OpenOBD."), s.connectionType == ConnectionType.USB) {
                        scope.launch { app.settings.update { it.copy(connectionType = ConnectionType.USB) } }
                    }
                    Choice(tr("My phone (bridge)"), tr("For car screens whose Bluetooth struggles with the adapter: OpenOBD on your phone lends its adapter over Wi-Fi."), s.connectionType == ConnectionType.PHONE) {
                        scope.launch { app.settings.update { it.copy(connectionType = ConnectionType.PHONE) } }
                    }
                    Choice(tr("Demo (no adapter)"), tr("A simulated car to try every feature."), s.connectionType == ConnectionType.DEMO) {
                        scope.launch { app.settings.update { it.copy(connectionType = ConnectionType.DEMO) } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("My adapter has an HS/MS-CAN switch"), style = MaterialTheme.typography.bodyLarge)
                            Text(tr("For Ford, Mazda and Opel / Vauxhall: reaches radio, climate and comfort modules."), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.msCanSwitch, { v -> scope.launch { app.settings.update { it.copy(msCanSwitch = v) } } })
                    }
                }
                2 -> {
                    Text(tr("Your car"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr("OpenOBD recognises the brand from the VIN when it connects. In the Garage you can also find the exact model and its official consumption, and get maintenance and ITV reminders."),
                        style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onGarage) { Text(tr("Open the Garage")) }
                }
                else -> {
                    Text(tr("Ready"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    listOf(
                        tr("Plug the adapter into the OBD port (under the dashboard, near the steering column)."),
                        tr("Turn the ignition on - the engine running is best for live data."),
                        tr("Press Connect on the home screen."),
                        tr("Then try: Codes for faults, the used car check, Car health, and the dashboard layouts."),
                    ).forEachIndexed { i, t ->
                        Row { Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp)); Text(t, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::finish) { Text(tr("Skip")) }
            Spacer(Modifier.weight(1f))
            if (page > 0) TextButton(onClick = { page-- }) { Text(tr("Back")) }
            Button(onClick = { if (page < pages - 1) page++ else finish() }) { Text(if (page < pages - 1) tr("Next") else tr("Start")) }
        }
    }
}

@Composable
private fun Choice(title: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier.fillMaxWidth().border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        subtitle?.let { Spacer(Modifier.height(2.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
