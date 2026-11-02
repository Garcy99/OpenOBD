package com.openobd.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.RestartAlt
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
import com.openobd.ui.components.ScreenTopBar
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
fun DashboardScreen(onConnect: () -> Unit, onHud: () -> Unit) {
    val app = LocalApp.current
    val vehicle = connectedVehicle()
    val settings = appSettings()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Dashboard", actions = {
            if (vehicle != null) {
                IconButton(onClick = { app.session.live.resetStats() }) { Icon(Icons.Default.RestartAlt, "Reset min/max") }
                IconButton(onClick = onHud) { Icon(Icons.Default.Fullscreen, "HUD mode") }
            }
            IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Close else Icons.Default.Edit, "Edit") }
        })
        if (vehicle == null && !editing) {
            NotConnected(onConnect)
            return@Column
        }
        val supported = vehicle?.supportedPids ?: emptySet()
        val available = remember(supported) { SensorCatalog.available(supported) }
        val keys = settings.dashboard
        SubscribeSensors("dashboard", keys)
        val readings by app.session.live.readings.collectAsStateWithLifecycle()

        LazyVerticalGrid(
            columns = GridCells.Adaptive(165.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(keys, key = { i, k -> "$i$k" }) { index, key ->
                val def = SensorCatalog.get(key)
                val reading = readings[key]
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.combinedClickable(onClick = { if (editing) pickFor = index }, onLongClick = { pickFor = index }),
                ) {
                    Box {
                        if (def == null) {
                            Text("Unknown sensor $key", Modifier.padding(16.dp))
                        } else {
                            val units = settings.units
                            val (lo, hi) = displayRange(def, units)
                            val v = reading?.value
                            val fraction = v?.let { ((displayValue(def, it, units) - lo) / (hi - lo)).toFloat() }
                            val f = formatSensor(def, v, units)
                            val supportedHere = vehicle == null || (def.pid < 0 && available.any { it.key == key }) || def.pid in supported
                            AnalogGauge(
                                label = def.shortName,
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
                            ) { Icon(Icons.Default.Close, "Remove") }
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
                            Text("Add gauge")
                        }
                    }
                }
            }
            if (editing) item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.aspectRatio(1f).combinedClickable(onClick = {
                    scope.launch { app.settings.update { it.copy(dashboard = AppSettings.DEFAULT_DASHBOARD) } }
                })) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Reset layout") }
                }
            }
        }

        pickFor?.let { slot ->
            SensorPickerDialog(
                sensors = if (vehicle == null) SensorCatalog.available((0..0xFF).toSet()) else available,
                title = if (slot < 0) "Add gauge" else "Change gauge",
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

/** Formats a reading compactly for small displays. */
fun compact(value: Double?, decimals: Int) = value?.let { formatNumber(it, decimals) } ?: "—"
