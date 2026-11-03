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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.health.HealthFinding
import com.openobd.core.health.HealthLevel
import com.openobd.core.health.HealthMetric
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.Sparkline
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import java.text.DateFormat
import java.util.Date

private fun healthColor(l: HealthLevel) = when (l) { HealthLevel.GOOD -> Good; HealthLevel.WATCH -> Warn; HealthLevel.ACT -> Bad }

/** Long-term trends of the active car: one sample per drive, compared with its own baseline. */
@Composable
fun HealthScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val report = remember(garage.health, garage.activeVehicleId) { app.session.healthReport() }
    val carName = garage.active?.name

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Car health"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val color = healthColor(report.overall)
            Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f), RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text(carName ?: tr("Your car"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (report.overall) {
                        HealthLevel.GOOD -> tr("Everything looks normal")
                        HealthLevel.WATCH -> tr("Something to keep an eye on")
                        HealthLevel.ACT -> tr("Needs attention")
                    },
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color,
                )
                Text(tr("Based on %1\$s drives. Every connection records battery, charging, fuel trim, warm-up and idle values; trends are compared with your car's own normal.", report.samples),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (report.findings.isNotEmpty()) SectionCard(title = tr("Findings")) {
                report.findings.sortedByDescending { it.level }.forEach { FindingRow(it) }
            }
            for (series in report.series) {
                SectionCard(title = tr(series.metric.label)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val last = series.latest
                        Text(last?.let { formatMetric(series.metric, it) } ?: "-", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(tr("last %1\$s drives", series.points.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (series.points.size >= 2) {
                        Spacer(Modifier.height(8.dp))
                        Sparkline(series.points.map { it.first to it.second.toFloat() }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(56.dp))
                        Text(
                            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(series.points.first().first)) + " – " +
                                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(series.points.last().first)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(tr("Health trends are estimates from the car's standard sensors. They warn early; they do not replace a service."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMetric(m: HealthMetric, v: Double): String = when (m) {
    HealthMetric.IDLE_RPM, HealthMetric.MISFIRES -> "%.0f %s".format(v, m.unit).trim()
    else -> "%.1f %s".format(v, m.unit)
}

@Composable
private fun FindingRow(f: HealthFinding) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(
            when (f.level) { HealthLevel.GOOD -> Icons.Default.CheckCircle; HealthLevel.WATCH -> Icons.Default.Visibility; HealthLevel.ACT -> Icons.Default.Error },
            null, tint = healthColor(f.level), modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(tr(f.text, *f.args.toTypedArray()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
