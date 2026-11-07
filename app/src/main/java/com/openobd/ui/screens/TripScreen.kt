package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
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
import com.openobd.ui.components.TopBar
import com.openobd.ui.components.TopBarSlot
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.formatDuration
import com.openobd.ui.connectedVehicle

@Composable
fun TripScreen(onBack: () -> Unit, onConnect: () -> Unit, onHistory: () -> Unit = {}, topBar: TopBarSlot? = null) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val settings = appSettings()
    Column(Modifier.fillMaxSize()) {
        TopBar(topBar, tr("Trip computer"), onBack, actions = {
            IconButton(onClick = onHistory) { Icon(Icons.Default.History, tr("Trip history")) }
            IconButton(onClick = session.trip::reset) { Icon(Icons.Default.RestartAlt, tr("Reset trip")) }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        SubscribeSensors("tripscreen", listOf("CALC_CONS", "CALC_FUEL", "010D"))
        val t by session.trip.stats.collectAsStateWithLifecycle()
        val readings by session.live.readings.collectAsStateWithLifecycle()
        val u = settings.units
        fun fmt(v: Double, unit: ObdUnit, dec: Int = 1) = unit.display(v, u).let { "%.${dec}f %s".format(it.value, it.unit.symbol) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat(tr("Distance"), fmt(t.distanceKm, ObdUnit.KM, 2), Modifier.weight(1f))
                BigStat(tr("Time"), formatDuration(t.durationS), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val inst = readings["CALC_CONS"]?.value
                BigStat(tr("Now"), inst?.let { fmt(it, ObdUnit.L_PER_100KM) } ?: readings["CALC_FUEL"]?.value?.let { fmt(it, ObdUnit.LITERS_PER_HOUR, 2) } ?: "—", Modifier.weight(1f))
                BigStat(tr("Trip average"), t.avgConsumption?.let { fmt(it, ObdUnit.L_PER_100KM) } ?: "—", Modifier.weight(1f))
            }
            EcoScoreCard(t.ecoScore, t.eco, session.trip.diesel)
            SectionCard(title = tr("Details")) {
                KeyValueRow(tr("Fuel used"), if (t.hasFuelData) fmt(t.fuelL, ObdUnit.LITERS, 2) else tr("Not available"))
                if (settings.fuelPricePerL > 0 && t.hasFuelData) KeyValueRow(tr("Fuel cost"), "%.2f".format(t.fuelL * settings.fuelPricePerL))
                KeyValueRow(tr("Average speed (moving)"), fmt(t.avgSpeed, ObdUnit.KMH, 0))
                KeyValueRow(tr("Max speed"), fmt(t.maxSpeed, ObdUnit.KMH, 0))
                KeyValueRow(tr("Max RPM"), "%.0f".format(t.maxRpm))
                KeyValueRow(tr("Moving time"), formatDuration(t.movingS))
                KeyValueRow(tr("Idle time"), formatDuration(t.idleS))
            }
            Text(tr("Fuel is calculated from the fuel-rate PID or from mass air flow. Accuracy is typically within 5-10%; ") +
                tr("set your fuel type and engine size in Settings for best results.") + " " +
                tr("Log full-tank fill-ups in the fuel log and the estimate learns your car's real consumption."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // "11.5 L/100km": the number large, the unit small, so long units never get cut off.
            val number = value.substringBefore(' ')
            val unit = value.substringAfter(' ', "")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(number, style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** Smooth-driving score with the counters behind it and the most useful tips. */
@Composable
fun EcoScoreCard(score: Int?, eco: com.openobd.core.trip.EcoStats, diesel: Boolean) {
    SectionCard(title = tr("Eco driving")) {
        if (score == null) {
            Text(tr("The score appears after the first kilometre and two minutes of driving."), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val color = when { score >= 80 -> com.openobd.ui.theme.Good; score >= 60 -> com.openobd.ui.theme.Warn; else -> com.openobd.ui.theme.Bad }
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("$score", style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = color)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))
            Text(tr("of 100"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        KeyValueRow(tr("Hard accelerations"), eco.harshAccel.toString())
        KeyValueRow(tr("Hard braking"), eco.harshBrake.toString())
        KeyValueRow(tr("High engine speed"), formatDuration(eco.highRpmS))
        KeyValueRow(tr("Long idling"), formatDuration(eco.longIdleS))
        com.openobd.core.trip.EcoScorer.tips(eco, diesel).forEach { tip ->
            Text("• " + tr(tip.text, *tip.args.toTypedArray()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
