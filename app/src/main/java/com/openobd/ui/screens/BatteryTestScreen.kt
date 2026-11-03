package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.battery.BatteryLevel
import com.openobd.core.battery.VoltSample
import com.openobd.session.BatteryTestState
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.Sparkline
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import java.text.DateFormat
import java.util.Date

private fun levelColor(l: BatteryLevel): Color = when (l) {
    BatteryLevel.GOOD -> Good; BatteryLevel.WATCH -> Warn; BatteryLevel.BAD -> Bad; BatteryLevel.UNKNOWN -> Color.Gray
}

private fun volts(v: Double?) = v?.let { "%.2f V".format(it) } ?: "-"

/** Battery and starting test: resting voltage, the dip while starting and the charging voltage. */
@Composable
fun BatteryTestScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val vehicle = connectedVehicle()
    val state by session.battery.state.collectAsStateWithLifecycle()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { session.battery.reset() } }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Battery & starting test"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val s = state) {
                BatteryTestState.Idle -> {
                    Text(tr("Measures the battery with the adapter's voltmeter while you start the engine: the voltage at rest, how far it falls while the starter turns, and whether the alternator charges afterwards."),
                        style = MaterialTheme.typography.bodyMedium)
                    SectionCard(title = tr("How to do it")) {
                        Text(tr("1. Engine off for at least a minute (best after the car has rested for hours)."))
                        Text(tr("2. Switch the ignition on without starting, connect, and wait for the glow plug light to go out on a diesel."))
                        Text(tr("3. Press Start test, and start the engine when the app asks."))
                        Text(tr("Switch off lights, heater fan and rear window heating for a fair result."), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (vehicle == null) NotConnected(onConnect)
                    else Button(onClick = { session.battery.start() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Start test")) }
                }
                is BatteryTestState.Resting -> Live(tr("Measuring the resting voltage…"), s.volts, emptyList(), null)
                is BatteryTestState.WaitingStart -> Live(tr("Start the engine now"), s.volts, s.samples, Warn) { session.battery.cancel() }
                is BatteryTestState.Charging -> Live(tr("Engine running: measuring the charging voltage…"), s.volts, s.samples, Good)
                is BatteryTestState.Done -> {
                    val r = s.result
                    val color = levelColor(r.overall)
                    Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f), RoundedCornerShape(16.dp)).padding(16.dp)) {
                        Text(tr("Result"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tr(r.overall.label), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
                    }
                    SectionCard {
                        KeyValueRow(tr("Resting voltage"), volts(r.restV))
                        KeyValueRow(tr("Lowest while starting"), volts(r.minV))
                        r.crankMs?.let { KeyValueRow(tr("Starter time"), "%.1f s".format(it / 1000.0)) }
                        KeyValueRow(tr("Charging voltage"), volts(r.chargingV))
                        if (s.samples.size >= 2) {
                            Spacer(Modifier.height(8.dp))
                            Sparkline(s.samples.map { it.t to it.v.toFloat() }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(90.dp))
                        }
                    }
                    SectionCard(title = tr("Findings")) {
                        r.findings.forEach { f ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                                Icon(when (f.level) {
                                    BatteryLevel.GOOD -> Icons.Default.CheckCircle; BatteryLevel.WATCH -> Icons.Default.Visibility
                                    BatteryLevel.BAD -> Icons.Default.Error; BatteryLevel.UNKNOWN -> Icons.Default.Info
                                }, null, tint = levelColor(f.level), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(tr(f.text, *f.args.toTypedArray()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    OutlinedButton(onClick = { session.battery.reset() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Test again")) }
                }
                is BatteryTestState.Failed -> {
                    ErrorText(tr(s.message))
                    OutlinedButton(onClick = { session.battery.reset() }, modifier = Modifier.fillMaxWidth()) { Text(tr("Back")) }
                }
            }
            val past = garage.batteryTests.filter { it.vehicleId == garage.active?.id }.sortedByDescending { it.time }
            if (past.isNotEmpty()) SectionCard(title = tr("Earlier tests")) {
                past.take(10).forEach { t ->
                    val level = runCatching { BatteryLevel.valueOf(t.level) }.getOrDefault(BatteryLevel.UNKNOWN)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t.time)), fontWeight = FontWeight.Medium)
                            Text(tr("Rest %1\$s · start %2\$s · charging %3\$s", volts(t.restV), volts(t.minV), volts(t.chargingV)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(tr(level.label), color = levelColor(level), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Live(title: String, volts: Double?, samples: List<VoltSample>, accent: Color?, onCancel: (() -> Unit)? = null) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent ?: MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(volts(volts), fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (samples.size >= 2) Sparkline(samples.map { it.t to it.v.toFloat() }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(90.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        onCancel?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(tr("Cancel")) } }
    }
}
