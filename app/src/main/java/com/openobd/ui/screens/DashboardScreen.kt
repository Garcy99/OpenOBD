package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.SensorCatalog
import com.openobd.data.AppSettings
import com.openobd.ui.LocalApp
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.AnalogGauge
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.TopBar
import com.openobd.ui.components.TopBarSlot
import com.openobd.ui.components.SensorPickerDialog
import com.openobd.ui.components.displayRange
import com.openobd.ui.components.displayValue
import com.openobd.ui.components.formatNumber
import com.openobd.ui.components.formatSensor
import com.openobd.ui.connectedVehicle
import kotlinx.coroutines.launch

fun redZone(key: String): Float? = when (key) {
    "010C" -> 0.8f
    "0105", "0167_1" -> 0.85f
    "015C" -> 0.84f
    "0104", "0143" -> 0.9f
    else -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(onConnect: () -> Unit, onHud: () -> Unit, topBar: TopBarSlot? = null) {
    val app = LocalApp.current
    val vehicle = connectedVehicle()
    val settings = appSettings()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(topBar, tr("Dashboard"), actions = {
            if (vehicle != null) {
                IconButton(onClick = { app.session.live.resetStats() }) { Icon(Icons.Default.RestartAlt, tr("Reset min/max")) }
                IconButton(onClick = onHud) { Icon(Icons.Default.Fullscreen, tr("HUD mode")) }
                FloatingGaugesButton()
            }
            IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Close else Icons.Default.Edit, tr("Edit")) }
        })
        if (vehicle == null && !editing) {
            NotConnected(onConnect)
            return@Column
        }
        val supported = vehicle?.supportedPids ?: emptySet()
        val available = remember(supported) { SensorCatalog.available(supported) }
        // Entries are sensor keys, optionally with "@d" for a large digital tile.
        val entries = settings.dashboard
        val keys = entries.map { it.substringBefore('@') }
        SubscribeSensors("dashboard", keys)
        fun updateEntries(t: (MutableList<String>) -> Unit) {
            scope.launch { app.settings.update { s -> s.copy(dashboard = s.dashboard.toMutableList().also(t)) } }
        }
        val readings by app.session.live.readings.collectAsStateWithLifecycle()

        LazyVerticalGrid(
            columns = GridCells.Adaptive(165.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            if (editing) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(tr("Layouts"), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        items(DashboardPresets.all.size) { i ->
                            val (name, list) = DashboardPresets.all[i]
                            androidx.compose.material3.AssistChip(onClick = { scope.launch { app.settings.update { it.copy(dashboard = list) } } }, label = { Text(tr(name)) })
                        }
                    }
                    Text(tr("Tap a gauge to change it; use the arrows to move it and the dial button to switch between gauge and big number."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(keys, key = { i, k -> "$i$k" }) { index, key ->
                val digital = entries.getOrNull(index)?.endsWith("@d") == true
                val def = SensorCatalog.get(key)
                val reading = readings[key]
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.combinedClickable(onClick = { if (editing) pickFor = index }, onLongClick = { pickFor = index }),
                ) {
                    Box {
                        if (def == null) {
                            Text(tr("Unknown sensor %1\$s", key), Modifier.padding(16.dp))
                        } else {
                            val units = settings.units
                            val (lo, hi) = displayRange(def, units)
                            val v = reading?.value
                            val fraction = v?.let { ((displayValue(def, it, units) - lo) / (hi - lo)).toFloat() }
                            val f = formatSensor(def, v, units)
                            val supportedHere = vehicle == null || (def.pid < 0 && available.any { it.key == key }) || def.pid in supported
                            if (digital) DigitalTile(
                                label = tr(def.name), valueText = if (supportedHere) f.value else "N/A", unit = f.unit,
                                minText = reading?.let { formatSensor(def, it.min, units).value }, maxText = reading?.let { formatSensor(def, it.max, units).value },
                            ) else AnalogGauge(
                                label = tr(def.shortName),
                                valueText = if (supportedHere) f.value else "N/A",
                                unit = f.unit,
                                fraction = if (def.labeler != null) null else fraction,
                                redFrom = redZone(key),
                                minText = reading?.let { formatSensor(def, it.min, units).value },
                                maxText = reading?.let { formatSensor(def, it.max, units).value },
                                highlight = key == "010C" && settings.shiftLightRpm > 0 && (v ?: 0.0) >= settings.shiftLightRpm,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (editing) {
                            IconButton(
                                onClick = { scope.launch { app.settings.update { it.copy(dashboard = it.dashboard.filterIndexed { i, _ -> i != index }) } } },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) { Icon(Icons.Default.Close, tr("Remove")) }
                            Row(Modifier.align(Alignment.BottomCenter)) {
                                IconButton(onClick = { updateEntries { l -> if (index > 0) java.util.Collections.swap(l, index, index - 1) } }, enabled = index > 0) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Move earlier"))
                                }
                                IconButton(onClick = { updateEntries { l -> if (index < l.size) l[index] = if (digital) key else "$key@d" } }) {
                                    Icon(if (digital) Icons.Default.Speed else Icons.Default.Pin, tr("Switch gauge style"))
                                }
                                IconButton(onClick = { updateEntries { l -> if (index < l.size - 1) java.util.Collections.swap(l, index, index + 1) } }, enabled = index < keys.size - 1) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Move later"))
                                }
                            }
                        }
                    }
                }
            }
            if (editing) item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.aspectRatio(1f).combinedClickable(onClick = { pickFor = -1 }),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, null)
                            Text(tr("Add gauge"))
                        }
                    }
                }
            }
            if (editing) item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.aspectRatio(1f).combinedClickable(onClick = {
                    scope.launch { app.settings.update { it.copy(dashboard = AppSettings.DEFAULT_DASHBOARD) } }
                })) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("Reset layout")) }
                }
            }
        }

        pickFor?.let { slot ->
            SensorPickerDialog(
                sensors = if (vehicle == null) SensorCatalog.available((0..0xFF).toSet()) else available,
                title = if (slot < 0) tr("Add gauge") else tr("Change gauge"),
                onDismiss = { pickFor = null },
                onPick = { def ->
                    pickFor = null
                    scope.launch {
                        app.settings.update {
                            val list = it.dashboard.toMutableList()
                            if (slot < 0 || slot >= list.size) list += def.key else list[slot] = def.key
                            it.copy(dashboard = list)
                        }
                    }
                },
            )
        }
    }
}

/** Ready-made dashboard layouts. */
object DashboardPresets {
    val all = listOf(
        "Everyday" to listOf("010C", "010D", "0105", "0104", "0111", "CALC_FUEL", "0142", "CALC_BOOST"),
        "Diesel" to listOf("010C", "CALC_BOOST@d", "0105", "0123", "012C", "017A_D", "0178_1", "CALC_FUEL"),
        "Economy" to listOf("CALC_FUEL@d", "CALC_CONS@d", "010D", "010C", "0104", "0111"),
        "Performance" to listOf("010C", "010D", "CALC_POWER", "CALC_TORQUE", "CALC_BOOST", "0111", "010F", "0105"),
        "Temperatures" to listOf("0105@d", "010F", "015C", "0146", "013C", "0105"),
    )
}

/** Large number tile: easier to read at a glance than a dial. */
@Composable
private fun DigitalTile(label: String, valueText: String, unit: String, minText: String?, maxText: String?) {
    Column(Modifier.fillMaxWidth().aspectRatio(1f).padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valueText, style = MaterialTheme.typography.displayMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
            Text(" $unit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(listOfNotNull(minText?.let { "↓ $it" }, maxText?.let { "↑ $it" }).joinToString("   "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Formats a reading compactly for small displays. */
fun compact(value: Double?, decimals: Int) = value?.let { formatNumber(it, decimals) } ?: "—"

/** Shows or hides the floating window with three values over other apps (asks for the permission first). */
@Composable
private fun FloatingGaugesButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shown by com.openobd.service.ObdService.gaugesShown.collectAsStateWithLifecycle()
    var explain by remember { mutableStateOf(false) }
    fun send(action: String) {
        runCatching { context.startService(android.content.Intent(context, com.openobd.service.ObdService::class.java).setAction(action)) }
    }
    IconButton(onClick = {
        when {
            shown -> send(com.openobd.service.ObdService.ACTION_HIDE_GAUGES)
            com.openobd.service.FloatingGauges.canShow(context) -> send(com.openobd.service.ObdService.ACTION_SHOW_GAUGES)
            else -> explain = true
        }
    }) {
        Icon(Icons.Default.PictureInPictureAlt, if (shown) tr("Hide the floating window") else tr("Floating window over other apps"))
    }
    if (explain) androidx.compose.material3.AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text(tr("Floating window")) },
        text = { Text(tr("Shows the first three values of your dashboard over other apps, such as Google Maps or Waze. Drag it anywhere; tap it to come back here. Android asks you to allow \"Display over other apps\" for OpenOBD once.")) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                explain = false
                runCatching {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + context.packageName)))
                }
            }) { Text(tr("Allow")) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { explain = false }) { Text(tr("Cancel")) } },
    )
}
