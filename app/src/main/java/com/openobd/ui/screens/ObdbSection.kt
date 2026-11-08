package com.openobd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.Obdb
import com.openobd.core.brand.ObdbEntry
import com.openobd.core.brand.PidPacks
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.SectionCard
import com.openobd.ui.openUrl
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ObdbPick(val entry: ObdbEntry, val pids: List<CustomPid>)

private const val DPF = "DPF"

/**
 * Community data from OBDb: pick a make / model, choose categories, and add the values to the car as read-only
 * custom PIDs. Shown on the PID packs screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObdbSection(onAdded: (String) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val car = garage.active
    val brand by app.session.brand.collectAsStateWithLifecycle()
    var index by remember { mutableStateOf<List<ObdbEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<ObdbPick?>(null) }

    LaunchedEffect(Unit) {
        index = withContext(Dispatchers.IO) { runCatching { Obdb.decodeIndex(context.assets.open("obdb/index.json").bufferedReader().use { it.readText() }) }.getOrDefault(emptyList()) }
    }

    fun open(e: ObdbEntry) {
        loading = true; error = null
        scope.launch {
            runCatching {
                val text = app.http.get(Obdb.rawUrl(e.repo))
                withContext(Dispatchers.Default) { Obdb.signals(text, car?.specs?.year) }
            }.onSuccess { pids ->
                if (pids.isEmpty()) error = tr("No values that OpenOBD can read for this car in that data set.") else pick = ObdbPick(e, pids)
            }.onFailure { error = tr("Could not download the data. Check the internet connection.") }
            loading = false
        }
    }

    @Composable
    fun EntryRow(e: ObdbEntry) {
        Column(Modifier.fillMaxWidth().clickable { open(e) }.padding(vertical = 6.dp)) {
            Text(e.title, fontWeight = FontWeight.SemiBold)
            Text(tr("%1\$s values", e.signals), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SectionCard(title = tr("Community data (OBDb)")) {
        Text(tr("OBDb is an open database of manufacturer values (battery, DPF, tyres, gearbox ...) collected by the community. OpenOBD only uses its read requests."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val make = car?.specs?.make ?: brand?.name
        val suggested = remember(index, make, car?.specs?.model) { Obdb.forCar(index, make, car?.specs?.model).take(4) }
        if (suggested.isNotEmpty()) {
            Text(tr("For your car"), style = MaterialTheme.typography.labelLarge)
            suggested.forEach { EntryRow(it) }
        }
        val related = remember(index, make, car?.specs?.model) { Obdb.related(index, make, car?.specs?.model) }
        if (related.isNotEmpty()) {
            Text(tr("Same manufacturer group (shared engines and modules, may also work)"), style = MaterialTheme.typography.labelLarge)
            related.forEach { EntryRow(it) }
        }
        OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            label = { Text(tr("Search make or model")) }, modifier = Modifier.fillMaxWidth().testTag("obdbSearch"))
        if (query.trim().length >= 2) {
            val found = Obdb.search(index, query).take(30)
            if (found.isEmpty()) Text(tr("Nothing found"), style = MaterialTheme.typography.bodySmall)
            found.forEach { EntryRow(it) }
        }
        if (loading) LoadingBox(null)
        error?.let { ErrorText(it) }
        Text(Obdb.ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { openUrl(context, "https://obdb.community") })
    }

    pick?.let { p ->
        val groups = remember(p) { p.pids.groupBy { Obdb.group(it) }.toList().sortedByDescending { it.second.size } }
        val dpfCount = remember(p) { p.pids.count { Obdb.isDpf(it) } }
        var chosen by remember(p) {
            mutableStateOf(when {
                dpfCount > 0 -> setOf(DPF)
                p.pids.size <= PidPacks.MAX_PIDS -> groups.map { it.first }.toSet()
                else -> emptySet()
            })
        }
        val selected = p.pids.filter { (DPF in chosen && Obdb.isDpf(it)) || Obdb.group(it) in chosen }
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(p.entry.title) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("%1\$s values. Choose what to add:", p.pids.size), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (dpfCount > 0) FilterChip(DPF in chosen, { chosen = if (DPF in chosen) chosen - DPF else chosen + DPF },
                            label = { Text(tr("Particulate filter (DPF)") + " · $dpfCount") })
                        groups.forEach { (g, list) ->
                            FilterChip(g in chosen, { chosen = if (g in chosen) chosen - g else chosen + g }, label = { Text(tr(g) + " · ${list.size}") })
                        }
                    }
                    Text(tr("%1\$s selected", selected.size), fontWeight = FontWeight.SemiBold)
                    if (selected.size > PidPacks.MAX_PIDS) Text(tr("Only the first %1\$s are added. Choose fewer categories.", PidPacks.MAX_PIDS), color = Warn, style = MaterialTheme.typography.bodySmall)
                    Text(tr("Community data, not tested on your car: some values may not answer or may be wrong. Nothing is written to the car."),
                        color = Warn, style = MaterialTheme.typography.bodySmall)
                    selected.take(40).forEach { Text("• ${it.name}" + (if (it.unit.isNotBlank()) " (${it.unit})" else ""), style = MaterialTheme.typography.bodySmall) }
                    if (selected.size > 40) Text("…", style = MaterialTheme.typography.bodySmall)
                    Text(Obdb.ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { openUrl(context, Obdb.pageUrl(p.entry.repo)) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pick = null
                    val pack = Obdb.pack(p.entry, selected)
                    scope.launch {
                        var added = 0; var skipped = 0
                        app.garage.updateActive { v ->
                            val r = PidPacks.merge(v.customPids, v.customModules, pack) { app.garage.newId() }
                            added = r.added; skipped = r.skipped
                            v.copy(customPids = r.pids, customModules = r.modules)
                        }
                        onAdded(tr("Added %1\$s PIDs (%2\$s already present). They appear in Live data under \"Brand / custom\".", added, skipped))
                    }
                }, enabled = car != null && selected.isNotEmpty()) { Text(tr("Add to my car")) }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text(tr("Cancel")) } },
        )
    }
}
