package com.openobd.ui.screens

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.ui.platform.testTag

import com.openobd.ui.tr

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Tune
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
import com.openobd.ui.components.GaugeSpec
import com.openobd.ui.components.GaugeTheme
import androidx.compose.ui.graphics.Color
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.TopBar
import com.openobd.ui.components.TopBarSlot
import com.openobd.ui.components.SensorPickerDialog
import com.openobd.ui.components.formatNumber
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
    var customise by remember { mutableStateOf<Int?>(null) }

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
        // Each entry is a sensor key plus how it is drawn (GaugeSpec); "@d" from older versions means a big number.
        val specs = settings.dashboard.map { GaugeSpec.parse(it) }
        val theme = GaugeTheme.of(settings.dashboardTheme)
        // Oil temperature / pressure gauges read whichever source this car has (standard PID or the maker's).
        val sources = specs.map { com.openobd.core.obd.VirtualSensors.resolve(it.key, supported) }
        SubscribeSensors("dashboard", sources.filterNotNull().distinct())
        fun updateEntries(t: (MutableList<String>) -> Unit) {
            scope.launch { app.settings.update { s -> s.copy(dashboard = s.dashboard.toMutableList().also(t)) } }
        }
        val readings by app.session.live.readings.collectAsStateWithLifecycle()

        @Composable
        fun gaugeCell(index: Int, spec: GaugeSpec, modifier: Modifier, fontScale: Float = 1f) {
                val key = spec.key
                val source = sources.getOrNull(index)
                val def = source?.let { SensorCatalog.get(it) } ?: SensorCatalog.get(key)
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = theme.face?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainer),
                    modifier = modifier.combinedClickable(onClick = { if (editing) customise = index }, onLongClick = { customise = index }),
                ) {
                    Box {
                        if (def == null) {
                            Text(tr("Unknown sensor %1\$s", key), Modifier.padding(16.dp))
                        } else {
                            val supportedHere = vehicle == null || (source != null && (def.pid == -2 || (def.pid < 0 && available.any { it.key == source }) || def.pid in supported))
                            val reading = source?.let { readings[it] }
                            // Square gauge, as big as the card allows, centred in it.
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val side = if (maxHeight == androidx.compose.ui.unit.Dp.Infinity) maxWidth else minOf(maxWidth, maxHeight)
                                Box(Modifier.size(side)) {
                                    ScaledText(fontScale) {
                                        GaugeView(spec, theme, def, reading, settings.units, supportedHere,
                                            shift = key == "010C" && settings.shiftLightRpm > 0 && (reading?.value ?: 0.0) >= settings.shiftLightRpm)
                                    }
                                }
                            }
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
                                IconButton(onClick = { customise = index }) { Icon(Icons.Default.Tune, tr("Customise gauge")) }
                                IconButton(onClick = { updateEntries { l -> if (index < l.size - 1) java.util.Collections.swap(l, index, index + 1) } }, enabled = index < specs.size - 1) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Move later"))
                                }
                            }
                        }
                    }
                }
        }

        if (!editing) FittedGauges(specs.size) { i, m, scale -> gaugeCell(i, specs[i], m, scale) }
        else LazyVerticalGrid(
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
                    Text(tr("Theme"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        items(GaugeTheme.entries.size) { i ->
                            val t = GaugeTheme.entries[i]
                            androidx.compose.material3.FilterChip(
                                selected = t == theme,
                                onClick = { scope.launch { app.settings.update { it.copy(dashboardTheme = t.id) } } },
                                label = { Text(tr(t.label)) },
                                leadingIcon = { Swatch(t.accent.last(), t.face) },
                            )
                        }
                    }
                    Text(tr("Tap a gauge to change its sensor, style, colour, range and warning; use the arrows to move it."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(specs, key = { i, sp -> "$i${sp.key}" }) { index, spec ->
                gaugeCell(index, spec, Modifier)
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

        customise?.let { slot ->
            val spec = specs.getOrNull(slot)
            if (spec == null) customise = null
            else GaugeEditor(spec, theme, settings.units,
                onChangeSensor = { pickFor = slot },
                onDismiss = { customise = null },
                onSave = { updated -> customise = null; updateEntries { l -> if (slot < l.size) l[slot] = updated.encode() } },
                onRemove = { customise = null; scope.launch { app.settings.update { it.copy(dashboard = it.dashboard.filterIndexed { i, _ -> i != slot }) } } },
            )
        }

        pickFor?.let { slot ->
            SensorPickerDialog(
                // Oil temperature, oil pressure and tyre pressures first: they read whichever source the car has.
                sensors = com.openobd.core.obd.VirtualSensors.all + (if (vehicle == null) SensorCatalog.available((0..0xFF).toSet()) else available),
                title = if (slot < 0) tr("Add gauge") else tr("Change gauge"),
                onDismiss = { pickFor = null },
                onPick = { def ->
                    pickFor = null
                    scope.launch {
                        app.settings.update {
                            val list = it.dashboard.toMutableList()
                            // A new sensor keeps the gauge's style and colour; its range and warnings belonged to the old one.
                            if (slot < 0 || slot >= list.size) list += def.key
                            else list[slot] = GaugeSpec.parse(list[slot]).let { old -> GaugeSpec(def.key, old.style, old.color) }.encode()
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
        "Everyday" to AppSettings.DEFAULT_DASHBOARD,
        "Diesel" to listOf("010C", "CALC_BOOST@d", "0105", "OIL_TEMP", "OIL_PRESS", "0123", "012C", "017A_D", "0178_1", "CALC_FUEL"),
        "Economy" to listOf("CALC_FUEL@d", "CALC_CONS@d", "010D", "010C", "0104", "CALC_PEDAL"),
        "Performance" to listOf("010C", "010D", "CALC_POWER", "CALC_TORQUE", "CALC_BOOST", "CALC_PEDAL", "010F", "0105"),
        "Temperatures" to listOf("0105@d", "OIL_TEMP@d", "010F", "0146", "013C", "OIL_PRESS"),
        // Cars with a pressure sensor in each wheel (direct TPMS).
        "Tyres" to listOf("TYRE_FL@d", "TYRE_FR@d", "TYRE_RL@d", "TYRE_RR@d", "010D", "0146"),
    )
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

/** How many gauges go on each row so they fill the screen: balanced rows, as big as they can be. */
object GaugeGrid {
    data class Plan(val rows: List<Int>, val cell: Float)

    /** [n] items over [rows] rows as evenly as possible, the fuller rows first (7 over 2: 4 and 3). */
    fun balanced(n: Int, rows: Int): List<Int> = List(rows) { i -> n / rows + if (i < n % rows) 1 else 0 }

    /** The arrangement giving the biggest square gauges in [w] x [h] (with [gap] between), or null below [minCell]. */
    fun plan(n: Int, w: Float, h: Float, gap: Float, minCell: Float = 96f): Plan? {
        if (n <= 0) return null
        val all = (1..n).map { r ->
            val rows = balanced(n, r)
            val k = rows.max()
            Plan(rows, minOf((w - gap * (k - 1)) / k, (h - gap * (r - 1)) / r))
        }
        val biggest = all.maxOf { it.cell }
        // Rows of equal length look tidier: take them when the gauges stay almost as big.
        val even = all.filter { it.rows.distinct().size == 1 && it.cell >= biggest * 0.9f }.maxByOrNull { it.cell }
        return (even ?: all.maxBy { it.cell }).takeIf { it.cell >= minCell }
    }
}

/** Text sized for the gauge: gauges that fill a big car screen get bigger numbers too. */
@Composable
private fun ScaledText(scale: Float, content: @Composable () -> Unit) {
    if (scale == 1f) { content(); return }
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * scale),
    ) { content() }
}

/**
 * The gauges filling the screen in balanced rows (6 gauges: 3 + 3, not 4 + 2), each card stretching to its share of
 * the row; on screens too small for that, a scrolling grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FittedGauges(n: Int, cell: @Composable (Int, Modifier, Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val gap = 10.dp
        val plan = GaugeGrid.plan(n, maxWidth.value, maxHeight.value, gap.value)
        if (plan == null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(165.dp), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap),
            ) { items(n) { i -> cell(i, Modifier, 1f) } }
            return@BoxWithConstraints
        }
        // Numbers grow with the gauges (the tiles were designed at about 165 dp).
        val scale = (plan.cell / 165f).coerceIn(0.85f, 2.6f)
        Column(Modifier.fillMaxSize().testTag("fittedGauges"), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) {
            var first = 0
            for (count in plan.rows) {
                val start = first
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap)) {
                    for (i in start until start + count) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            cell(i, Modifier.fillMaxSize(), scale)
                        }
                    }
                }
                first += count
            }
        }
    }
}
