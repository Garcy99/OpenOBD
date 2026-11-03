package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.EcuModule
import com.openobd.core.service.Procedures
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ DPF regeneration helper

@Composable
fun DieselScreen(onBack: () -> Unit, onConnect: () -> Unit, onGuides: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val status by session.diesel.status.collectAsStateWithLifecycle()
    DisposableEffect(vehicle != null) {
        if (vehicle != null) session.diesel.start()
        onDispose { session.diesel.stop() }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Diesel particulate filter"), onBack)
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("The engine computer burns the soot in the filter by itself when the engine is warm and under steady load. This helper tells you when the driving conditions are right and, if your car reports it, when a regeneration is running."),
                style = MaterialTheme.typography.bodyMedium)
            val s = status
            SectionCard(title = tr("Regeneration conditions")) {
                Condition(tr("Engine warm (coolant 70 °C or more)"), s?.coolantOk)
                Condition(tr("Speed 60 km/h or more"), s?.speedOk)
                Condition(tr("Engine speed 1800-3500 rpm"), s?.rpmOk)
                Spacer(Modifier.height(10.dp))
                val minutes = (s?.secondsInConditions ?: 0.0) / 60
                Text(tr("%1\$s of %2\$s minutes in the right conditions", "%.0f".format(minutes), "%.0f".format((s?.targetSeconds ?: 1200.0) / 60)), fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { s?.progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            SectionCard(title = tr("Regeneration")) {
                when (s?.regenActive) {
                    null -> Text(tr("This car does not report exhaust or filter temperatures over standard OBD, so a running regeneration cannot be seen directly. Use the conditions above, or find the manufacturer value with the identifier explorer."),
                        style = MaterialTheme.typography.bodySmall)
                    true -> Text(tr("Regeneration in progress - keep driving like this."), color = Good, fontWeight = FontWeight.Bold)
                    false -> Text(tr("No regeneration running right now."))
                }
                s?.dpfTempC?.let { Text(tr("Exhaust / filter temperature: %1\$s °C", "%.0f".format(it))) }
                s?.dpfDeltaKpa?.let { Text(tr("Filter pressure difference: %1\$s kPa (rises as the filter fills)", "%.2f".format(it))) }
                if ((s?.regensSeen ?: 0) > 0) Text(tr("Regenerations seen this drive: %1\$s", s!!.regensSeen))
            }
            OutlinedButton(onClick = onGuides, modifier = Modifier.fillMaxWidth()) { Text(tr("Step-by-step guide")) }
        }
    }
}

@Composable
private fun Condition(label: String, ok: Boolean?) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok == true) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = when (ok) { true -> Good; false -> Warn; null -> MaterialTheme.colorScheme.onSurfaceVariant }, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label + if (ok == null) " · " + tr("not reported") else "", style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------ service guides

@Composable
fun ServiceGuidesScreen(onBack: () -> Unit, onDieselHelper: () -> Unit) {
    val session = LocalApp.current.session
    val brand by session.brand.collectAsStateWithLifecycle()
    val procedures = remember(brand) { Procedures.forCar(brand?.family?.id, session.diesel.isDiesel || brand == null) }
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Service guides"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Procedures done with the car's own controls. OpenOBD never writes to your car's control units for these."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (p in procedures) {
                SectionCard {
                    Column(Modifier.fillMaxWidth().clickable { open = if (open == p.id) null else p.id }) {
                        Text(tr(p.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(tr(p.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open == p.id) {
                        Spacer(Modifier.height(8.dp))
                        p.steps.forEachIndexed { i, step ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                                Text(tr(step), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        p.caution?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = Warn, modifier = Modifier.padding(top = 6.dp)) }
                        if (p.liveHelper) Button(onClick = onDieselHelper, modifier = Modifier.padding(top = 8.dp)) { Text(tr("Open the live helper")) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ identifier explorer

private data class IdRange(val label: String, val range: IntRange, val gmlan: Boolean)

private val ranges = listOf(
    IdRange("UDS F180-F1FF", 0xF180..0xF1FF, false),
    IdRange("UDS 0100-01FF", 0x0100..0x01FF, false),
    IdRange("UDS 0D00-0DFF", 0x0D00..0x0DFF, false),
    IdRange("UDS 1000-10FF", 0x1000..0x10FF, false),
    IdRange("UDS 2000-20FF", 0x2000..0x20FF, false),
    IdRange("UDS D000-D0FF", 0xD000..0xD0FF, false),
    IdRange("GMLAN 1A 00-FF", 0x00..0xFF, true),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun IdExplorerScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val scope = rememberCoroutineScope()
    val discovered by session.modules.discovery.collectAsStateWithLifecycle()
    val modules = remember(discovered) {
        (session.modules.modulesOnCurrentBus() + ((discovered as? Op.Done)?.value.orEmpty())).distinctBy { it.id }
    }
    var module by remember { mutableStateOf<EcuModule?>(null) }
    var range by remember { mutableStateOf(ranges.first()) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateMapOf<Int, IntArray>() }
    val previous = remember { mutableStateMapOf<Int, IntArray>() }
    val watched = remember { mutableStateMapOf<Int, Boolean>() }
    var watching by remember { mutableStateOf(false) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(watching, module) {
        val m = module ?: return@LaunchedEffect
        while (watching && isActive) {
            val ids = watched.filterValues { it }.keys
            if (ids.isNotEmpty()) runCatching { session.modules.readIds(m, ids, range.gmlan) }.onSuccess { r ->
                r.forEach { (id, bytes) -> found[id]?.let { previous[id] = it }; found[id] = bytes }
            }
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Identifier explorer"), onBack)
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Advanced: finds which data identifiers a module answers, so you can discover manufacturer values (for example DPF soot load) and watch them change while driving. Read-only: nothing is written to the car."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard(title = tr("Module")) {
                modules.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable { module = m; found.clear(); watched.clear() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = module?.id == m.id, onClick = { module = m; found.clear(); watched.clear() })
                        Text("${tr(m.name)} · ${m.addressLabel}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SectionCard(title = tr("Range")) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ranges.forEach { r -> FilterChip(selected = r == range, onClick = { range = r; found.clear(); watched.clear() }, label = { Text(r.label) }) }
                }
            }
            val m = module
            val p = progress
            if (p != null) {
                Text(tr("Probing %1\$s of %2\$s …", p.first, p.second))
                LinearProgressIndicator(progress = { p.first / p.second.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(onClick = {
                    if (m == null) return@Button
                    error = null; found.clear(); watched.clear(); watching = false
                    scope.launch {
                        try {
                            val r = session.modules.scanIds(m, range.range, range.gmlan) { i, n -> progress = i to n }
                            found.putAll(r)
                        } catch (e: Exception) { error = e.message }
                        progress = null
                    }
                }, enabled = m != null, modifier = Modifier.fillMaxWidth()) { Text(tr("Scan this range")) }
            }
            error?.let { ErrorText(it) }
            if (found.isNotEmpty() && m != null) {
                SectionCard(title = tr("%1\$s identifiers answered", found.size)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Re-read the ticked ones every second"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.Switch(watching, { watching = it })
                    }
                    found.entries.sortedBy { it.key }.forEach { (id, bytes) ->
                        val changed = previous[id]?.contentEquals(bytes) == false
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(watched[id] == true, { watched[id] = it })
                            Column(Modifier.weight(1f)) {
                                Text(if (range.gmlan) "%02X".format(id) else "%04X".format(id), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                Text(bytes.joinToString(" ") { "%02X".format(it) }.take(60), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    color = if (changed) Good else MaterialTheme.colorScheme.onSurface,
                                    modifier = if (changed) Modifier.background(Good.copy(alpha = 0.12f), RoundedCornerShape(4.dp)) else Modifier)
                                val ascii = bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
                                if (ascii.length >= 4 && ascii.length >= bytes.size / 2) Text("\"$ascii\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { scope.launch { session.modules.saveIdAsCustomPid(m, id, range.gmlan, bytes.size); savedMsg = tr("Saved as a custom PID. Edit its name and formula in Custom PIDs.") } }) {
                                Text(tr("Save"))
                            }
                        }
                    }
                }
            }
            savedMsg?.let { Text(it, color = Good, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
