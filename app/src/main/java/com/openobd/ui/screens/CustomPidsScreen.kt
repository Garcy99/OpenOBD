package com.openobd.ui.screens

import com.openobd.ui.tr

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.Equation
import com.openobd.core.brand.TorqueCsv
import com.openobd.ui.LocalApp
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.formatSensor
import com.openobd.ui.connectedVehicle
import com.openobd.ui.shareText
import kotlinx.coroutines.launch

@Composable
fun CustomPidsScreen(onBack: () -> Unit, onPacks: () -> Unit = {}) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val vehicle = connectedVehicle()
    val garageData by app.garage.data.collectAsStateWithLifecycle()
    val pids = garageData.active?.customPids.orEmpty()
    var editing by remember { mutableStateOf<CustomPid?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var importReport by remember { mutableStateOf<TorqueCsv.ImportResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var module by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<List<CustomPid>?>(null) }
    fun moduleOf(p: CustomPid) = p.header.ifBlank { "7DF" } + (if (p.extAddr.isNotBlank()) "/" + p.extAddr else "")
    val groups = remember(pids) { pids.groupingBy { com.openobd.core.brand.Obdb.group(it) }.eachCount().entries.sortedByDescending { it.value } }
    val modules = remember(pids) { pids.groupingBy { moduleOf(it) }.eachCount().entries.sortedByDescending { it.value } }
    val shown = remember(pids, query, group, module) {
        val q = query.trim().lowercase()
        pids.filter { p ->
            (group == null || com.openobd.core.brand.Obdb.group(p) == group) && (module == null || moduleOf(p) == module) &&
                (q.isEmpty() || p.name.lowercase().contains(q) || p.shortName.lowercase().contains(q) || p.request.lowercase().contains(q) ||
                    p.unit.lowercase().contains(q) || p.header.lowercase().contains(q))
        }
    }
    val filtered = shown.size != pids.size

    fun import(text: String) {
        val r = TorqueCsv.parse(text)
        importReport = r
        if (r.pids.isNotEmpty()) scope.launch { app.garage.updateActive { it.copy(customPids = it.customPids + r.pids) } }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onSuccess { t -> t?.let(::import) }.onFailure { Toast.makeText(context, tr("Could not read file: %1\$s", it.message), Toast.LENGTH_LONG).show() }
    }

    // Only the first ones on screen are polled: hundreds of imported values would slow live data to a crawl.
    if (vehicle != null) SubscribeSensors("custompids", shown.take(30).map { it.key })
    val readings by app.session.live.readings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(tr("Custom PIDs"), onBack, actions = {
                if (pids.isNotEmpty()) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, tr("Search")) }
                    IconButton(onClick = { filtering = !filtering }) {
                        Icon(Icons.Default.FilterList, tr("Filter"), tint = if (group != null || module != null) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified)
                    }
                }
                IconButton(onClick = onPacks) { Icon(Icons.Default.Inventory2, tr("PID packs")) }
                IconButton(onClick = { fileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Icon(Icons.Default.FileOpen, tr("Import CSV file")) }
                IconButton(onClick = { pasting = true }) { Icon(Icons.Default.ContentPaste, tr("Paste CSV")) }
                if (pids.isNotEmpty()) IconButton(onClick = { shareText(context, tr("OpenOBD custom PIDs"), TorqueCsv.export(pids)) }) { Icon(Icons.Default.Share, tr("Export")) }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = newPid() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(tr("Add PID")) })
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        if (pids.isEmpty()) {
            CenteredMessage(null, tr("No custom PIDs yet.\n\nAdd one by hand, or import a Torque-format CSV (Name, ShortName, ModeAndPID, Equation, Min, Max, Units, Header) ") +
                tr("shared by your car's owner community: EV battery health, transmission temperature, DPF soot load and more."), Modifier.padding(pad))
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(tr("Custom PIDs appear in Live data, the dashboard, charts and logs under \"Brand / custom\"."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (searching) item {
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                    placeholder = { Text(tr("Name, request, module or unit")) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, tr("Clear")) } },
                    modifier = Modifier.fillMaxWidth().testTag("pidSearch"))
            }
            if (filtering) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Category"), style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(group == null, { group = null }, label = { Text(tr("All") + " · ${pids.size}") }) }
                        items(groups.map { it.key to it.value }) { (g, n) -> FilterChip(group == g, { group = if (group == g) null else g }, label = { Text(tr(g) + " · $n") }) }
                    }
                    if (modules.size > 1) {
                        Text(tr("Module"), style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { FilterChip(module == null, { module = null }, label = { Text(tr("All")) }) }
                            items(modules.map { it.key to it.value }) { (m, n) -> FilterChip(module == m, { module = if (module == m) null else m }, label = { Text("$m · $n") }) }
                        }
                    }
                }
            }
            if (filtered) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Showing %1\$s of %2\$s", shown.size, pids.size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { query = ""; group = null; module = null }) { Text(tr("Show all")) }
                }
            }
            items(shown, key = { it.id }) { p ->
                val def = remember(p) { p.toSensor() }
                SectionCard(Modifier.clickable { editing = p }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text("${p.header.ifBlank { "7DF" }} · ${p.request} · ${p.equation}", fontFamily = com.openobd.ui.theme.Inter,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val f = formatSensor(def, readings[p.key]?.value, settings.units)
                        Text("${f.value} ${f.unit}", fontFamily = com.openobd.ui.theme.Inter, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                TextButton(onClick = { confirmDelete = shown }, enabled = shown.isNotEmpty()) {
                    Text(if (filtered) tr("Delete these %1\$s PIDs", shown.size) else tr("Delete all custom PIDs"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Delete %1\$s PIDs?", list.size)) },
            text = { Text(tr("They are removed from this car. Gauges and logs that use them stop showing them.")) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = list.map { it.id }.toSet()
                    confirmDelete = null
                    scope.launch { app.garage.updateActive { s -> s.copy(customPids = s.customPids.filter { it.id !in ids }) } }
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Cancel")) } },
        )
    }

    editing?.let { p ->
        PidEditor(p, isNew = pids.none { it.id == p.id }, onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                scope.launch { app.garage.updateActive { s -> s.copy(customPids = if (s.customPids.any { it.id == saved.id }) s.customPids.map { if (it.id == saved.id) saved else it } else s.customPids + saved) } }
            },
            onDelete = { editing = null; scope.launch { app.garage.updateActive { s -> s.copy(customPids = s.customPids.filter { it.id != p.id }) } } })
    }
    if (pasting) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(tr("Paste Torque CSV")) },
            text = {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    placeholder = { Text(TorqueCsv.HEADER + tr("\nTrans temp,TFT,221E1C,((A*256)+B)/16,-40,150,°C,7E1")) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = com.openobd.ui.theme.Inter))
            },
            confirmButton = { TextButton(onClick = { pasting = false; import(text) }, enabled = text.isNotBlank()) { Text(tr("Import")) } },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text(tr("Cancel")) } },
        )
    }
    importReport?.let { r ->
        AlertDialog(
            onDismissRequest = { importReport = null },
            title = { Text(tr("Imported %1\$s PID(s)", r.pids.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (r.skipped.isEmpty()) Text(tr("All lines imported."))
                    else { Text(tr("%1\$s line(s) skipped:", r.skipped.size)); r.skipped.take(30).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
                }
            },
            confirmButton = { TextButton(onClick = { importReport = null }) { Text("OK") } },
        )
    }
}

private fun newPid() = CustomPid(System.currentTimeMillis().toString(36), "", "", "", "A", 0.0, 100.0, "", "7E0")

@Composable
private fun PidEditor(initial: CustomPid, isNew: Boolean, onDismiss: () -> Unit, onSave: (CustomPid) -> Unit, onDelete: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var short by remember { mutableStateOf(initial.shortName) }
    var request by remember { mutableStateOf(initial.request) }
    var equation by remember { mutableStateOf(initial.equation) }
    var header by remember { mutableStateOf(initial.header) }
    var receive by remember { mutableStateOf(initial.receive) }
    var ext by remember { mutableStateOf(initial.extAddr) }
    var min by remember { mutableStateOf(initial.min.toString()) }
    var max by remember { mutableStateOf(initial.max.toString()) }
    var unit by remember { mutableStateOf(initial.unit) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val pid = initial.copy(name = name.trim(), shortName = short.trim(), request = request.trim().uppercase(), equation = equation.trim(),
        header = header.trim().uppercase(), receive = receive.trim().uppercase(), extAddr = ext.trim().uppercase(), min = min.toDoubleOrNull() ?: 0.0,
        max = max.toDoubleOrNull() ?: 100.0, unit = unit.trim())
    val error = pid.validate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr("New PID") else tr("Edit PID")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(short, { short = it }, label = { Text(tr("Short name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(request, { request = it.uppercase() }, label = { Text(tr("Mode+PID")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(header, { header = it.uppercase() }, label = { Text(tr("Header")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(equation, { equation = it }, label = { Text(tr("Equation (A, B, C … = bytes)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = com.openobd.ui.theme.Inter))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(min, { min = it }, label = { Text(tr("Min")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(max, { max = it }, label = { Text(tr("Max")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it }, label = { Text(tr("Unit")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(receive, { receive = it.uppercase() }, label = { Text(tr("Response ID (optional, default header+8)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ext, { ext = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(2) }, label = { Text(tr("Extended address")) },
                    supportingText = { Text(tr("BMW / MINI: module address, with Module ID 6F1")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Functions: Signed(A), Int16(A,B), {A:7} for bit 7, Min, Max, Abs. Example: ((A*256)+B)/16"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { ErrorText(tr(it)) }
                if (vehicle != null && error == null) {
                    TextButton(onClick = {
                        testResult = tr("Reading…")
                        scope.launch {
                            testResult = try {
                                val bytes = session.modules.testCustomPid(pid)
                                if (bytes == null) tr("No answer (check header and request)")
                                else tr("Bytes: ") + bytes.joinToString(" ") { "%02X".format(it) } + "\nValue: " + (Equation(pid.equation).evaluate(bytes)?.let { "%.3f %s".format(it, pid.unit) } ?: tr("equation needs more bytes"))
                            } catch (e: Exception) { tr("Error: %1\$s", e.message) }
                        }
                    }) { Text(tr("Test on the car")) }
                }
                testResult?.let { Text(it, fontFamily = com.openobd.ui.theme.Inter, style = MaterialTheme.typography.bodySmall) }
                if (!isNew) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDelete) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(pid) }, enabled = error == null) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
