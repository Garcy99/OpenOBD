package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.obd.SensorCategory
import com.openobd.ui.LocalApp
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.Sparkline
import com.openobd.ui.components.formatSensor
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad

@Composable
fun LiveDataScreen(onConnect: () -> Unit, onChart: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val vehicle = connectedVehicle()
    val settings = appSettings()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<SensorCategory?>(null) }
    val chartKeys by session.live.chartKeys.collectAsStateWithLifecycle()
    val recording by session.recording.collectAsStateWithLifecycle()

    if (vehicle == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(tr("Live data"))
            NotConnected(onConnect)
        }
        return
    }
    val all = remember(vehicle.supportedPids) { SensorCatalog.available(vehicle.supportedPids) }
    val categories = remember(all) { all.map { it.category }.distinct() }
    val shown = all.filter { (category == null || it.category == category) && (query.isBlank() || (it.name.contains(query, true) || tr(it.name).contains(query, true))) }
    SubscribeSensors("live", shown.map { it.key })
    val readings by session.live.readings.collectAsStateWithLifecycle()
    val tick by session.live.tick.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(tr("Live data"), actions = {
                IconButton(onClick = { session.live.resetStats() }) { Icon(Icons.Default.RestartAlt, tr("Reset min/max")) }
                IconButton(onClick = { if (recording) session.stopRecording() else session.startRecording(shown.map { it.key }) }) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, if (recording) tr("Stop recording") else tr("Record"), tint = Bad)
                }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onChart, icon = { Icon(Icons.Default.ShowChart, null) }, text = { Text(tr("Chart (%1\$s)", chartKeys.size)) })
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(tr("Search %1\$s sensors", all.size)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(category == null, { category = null }, label = { Text(tr("All")) })
                categories.forEach { c -> FilterChip(category == c, { category = if (category == c) null else c }, label = { Text(tr(c.label)) }) }
            }
            if (recording) Text(tr("● Recording %1\$s sensors to CSV", shown.size), color = Bad, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.key }) { def ->
                    val r = readings[def.key]
                    val selected = def.key in chartKeys
                    val f = formatSensor(def, r?.value, settings.units)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().clickable {
                            session.live.chartKeys.value = if (selected) chartKeys - def.key else (chartKeys + def.key).takeLast(6)
                        },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, tr("Chart"),
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tr(def.name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (r != null && def.labeler == null) {
                                    Text(tr("min %1\$s · avg %2\$s · max %3\$s", (formatSensor(def, r.min, settings.units).value), (formatSensor(def, r.avg, settings.units).value), (formatSensor(def, r.max, settings.units).value)),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (def.labeler == null) {
                                val pts = remember(tick, def.key) { session.live.history(def.key).snapshot(System.currentTimeMillis() - 30_000) }
                                Sparkline(pts, MaterialTheme.colorScheme.primary, Modifier.width(56.dp).height(28.dp))
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(if (def.labeler != null) 120.dp else 92.dp)) {
                                if (def.labeler != null) Text(f.value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                else Text(f.value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(f.unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
