package com.openobd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.display
import com.openobd.ui.LocalApp
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.formatDuration
import com.openobd.ui.connectedVehicle

@Composable
fun TripScreen(onBack: () -> Unit, onConnect: () -> Unit, onHistory: () -> Unit = {}) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val settings = appSettings()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Trip computer", onBack, actions = {
            IconButton(onClick = onHistory) { Icon(Icons.Default.History, "Trip history") }
            IconButton(onClick = session.trip::reset) { Icon(Icons.Default.RestartAlt, "Reset trip") }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        SubscribeSensors("tripscreen", listOf("CALC_CONS", "CALC_FUEL", "010D"))
        val t by session.trip.stats.collectAsStateWithLifecycle()
        val readings by session.live.readings.collectAsStateWithLifecycle()
        val u = settings.units
        fun fmt(v: Double, unit: ObdUnit, dec: Int = 1) = unit.display(v, u).let { "%.${dec}f %s".format(it.value, it.unit.symbol) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat("Distance", fmt(t.distanceKm, ObdUnit.KM, 2), Modifier.weight(1f))
                BigStat("Time", formatDuration(t.durationS), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val inst = readings["CALC_CONS"]?.value
                BigStat("Now", inst?.let { fmt(it, ObdUnit.L_PER_100KM) } ?: readings["CALC_FUEL"]?.value?.let { fmt(it, ObdUnit.LITERS_PER_HOUR, 2) } ?: "—", Modifier.weight(1f))
                BigStat("Trip average", t.avgConsumption?.let { fmt(it, ObdUnit.L_PER_100KM) } ?: "—", Modifier.weight(1f))
            }
            SectionCard(title = "Details") {
                KeyValueRow("Fuel used", if (t.hasFuelData) fmt(t.fuelL, ObdUnit.LITERS, 2) else "Not available")
                if (settings.fuelPricePerL > 0 && t.hasFuelData) KeyValueRow("Fuel cost", "%.2f".format(t.fuelL * settings.fuelPricePerL))
                KeyValueRow("Average speed (moving)", fmt(t.avgSpeed, ObdUnit.KMH, 0))
                KeyValueRow("Max speed", fmt(t.maxSpeed, ObdUnit.KMH, 0))
                KeyValueRow("Max RPM", "%.0f".format(t.maxRpm))
                KeyValueRow("Moving time", formatDuration(t.movingS))
                KeyValueRow("Idle time", formatDuration(t.idleS))
            }
            Text("Fuel is calculated from the fuel-rate PID or from mass air flow. Accuracy is typically within 5-10%; " +
                "set your fuel type and engine size in Settings for best results.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}
