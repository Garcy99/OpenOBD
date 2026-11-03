package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.SensorCatalog
import com.openobd.ui.LocalApp
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.ChartSeries
import com.openobd.ui.components.LineChart
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.SensorPickerDialog
import com.openobd.ui.components.SeriesColors
import com.openobd.ui.components.displayValue
import com.openobd.ui.components.formatSensor
import com.openobd.ui.connectedVehicle

@Composable
fun ChartScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val settings = appSettings()
    val keys by session.live.chartKeys.collectAsStateWithLifecycle()
    var window by remember { mutableLongStateOf(60_000L) }
    var paused by remember { mutableStateOf(false) }
    var frozenAt by remember { mutableLongStateOf(0L) }
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Chart"), onBack, actions = {
            IconButton(onClick = { paused = !paused; frozenAt = System.currentTimeMillis() }) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) tr("Resume") else tr("Pause"))
            }
            IconButton(onClick = { picking = true }) { Icon(Icons.Default.Add, tr("Choose sensors")) }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        SubscribeSensors("chart", keys)
        val tick by session.live.tick.collectAsStateWithLifecycle()
        val readings by session.live.readings.collectAsStateWithLifecycle()
        val now = if (paused) frozenAt else System.currentTimeMillis()
        val defs = keys.mapNotNull { SensorCatalog.get(it) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30_000L to tr("30 s"), 60_000L to tr("1 min"), 300_000L to tr("5 min"), 900_000L to tr("15 min")).forEach { (ms, label) ->
                    FilterChip(window == ms, { window = ms }, label = { Text(label) })
                }
            }
            SectionCard {
                val series = remember(tick, keys, window, paused) {
                    defs.mapIndexed { i, d ->
                        val pts = session.live.history(d.key).snapshot(now - window)
                            .map { (t, v) -> t to displayValue(d, v.toDouble(), settings.units).toFloat() }
                        ChartSeries(pts, SeriesColors[i % SeriesColors.size])
                    }
                }
                LineChart(series, window, now, Modifier.fillMaxWidth().height(260.dp).padding(vertical = 4.dp))
                Text(tr("Each line uses its own scale. %1\$s", (if (paused) tr("Paused.") else "")), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            defs.forEachIndexed { i, d ->
                val r = readings[d.key]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Box(Modifier.size(12.dp).background(SeriesColors[i % SeriesColors.size], CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr(d.name), fontWeight = FontWeight.Medium)
                        if (r != null) Text(tr("min %1\$s  max %2\$s", (formatSensor(d, r.min, settings.units).value), (formatSensor(d, r.max, settings.units).value)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val f = formatSensor(d, r?.value, settings.units)
                    Text("${f.value} ${f.unit}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (picking) {
            SensorPickerDialog(
                sensors = SensorCatalog.available(vehicle.supportedPids).filter { it.labeler == null },
                title = tr("Chart up to 6 sensors"),
                onDismiss = { picking = false },
                selected = keys.toSet(),
                maxSelection = 6,
                onConfirm = { session.live.chartKeys.value = it.toList(); picking = false },
            )
        }
    }
}
