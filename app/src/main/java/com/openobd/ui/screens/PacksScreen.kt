package com.openobd.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.openobd.core.brand.PackEntry
import com.openobd.core.brand.PidPack
import com.openobd.core.brand.PidPacks
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.shareFile
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Share the car's custom PIDs as a pack, import a pack file, or download one from the community catalog. */
@Composable
fun PacksScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val car = garage.active
    var catalog by remember { mutableStateOf<List<PackEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PidPack?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun openPack(text: String) {
        runCatching { PidPacks.decode(text) }.onSuccess { preview = it; error = null }.onFailure { error = tr(it.message ?: "Failed") }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull() }
            if (text == null) error = tr("Could not read file: %1\$s", "") else openPack(text)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("PID packs"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("Packs are sets of manufacturer PIDs, identifiers and codings for a make, model or engine. Share what you discovered, or use what others found. Values are only read; a coding is only written when you choose it in Codings, after a backup."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionCard(title = tr("Share your PIDs")) {
                val pids = car?.customPids.orEmpty()
                val codings = car?.codings.orEmpty().size + car?.routines.orEmpty().size
                Text(if (pids.isEmpty() && codings == 0) tr("This car has no custom PIDs yet. Find some with the identifier explorer or add them by hand.")
                else tr("%1\$s custom PIDs of %2\$s", pids.size, car?.name ?: "") + (if (codings > 0) " · " + tr("%1\$s codings", codings) else ""),
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    val c = car ?: return@Button
                    scope.launch {
                        val pack = PidPacks.fromCar("${c.name} PIDs", c.specs?.make, c.specs?.model, c.customPids, c.customModules, c.codings, c.routines)
                        val file = withContext(Dispatchers.IO) {
                            File(File(context.cacheDir, "reports").apply { mkdirs() }, c.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifBlank { "car" } + ".openobd-pack.json")
                                .apply { writeText(PidPacks.encode(pack)) }
                        }
                        shareFile(context, file, "application/json")
                    }
                }, enabled = pids.isNotEmpty() || codings > 0) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(tr("Share as a pack")) }
                Text(tr("To add it to the community catalog, open a pull request with the file in the packs folder of the OpenOBD repository (only values you tested on a real car, please)."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard(title = tr("Add a pack")) {
                OutlinedButton(onClick = { openFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) {
                    Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text(tr("Open a pack file"))
                }
                OutlinedButton(onClick = {
                    loading = true; error = null
                    scope.launch {
                        runCatching { PidPacks.decodeCatalog(app.http.get(PidPacks.CATALOG_URL)) }
                            .onSuccess { c -> catalog = PidPacks.sortFor(c.packs, car?.specs?.make ?: app.session.brand.value?.name, car?.specs?.model) }
                            .onFailure { error = tr("The online catalog could not be read.") }
                        loading = false
                    }
                }) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text(tr("Browse the community catalog")) }
            }
            if (loading) LoadingBox(null)
            error?.let { ErrorText(it) }
            ObdbSection(onAdded = { message = it })
            message?.let { Text(it, color = Good, style = MaterialTheme.typography.bodyMedium) }
            catalog?.let { list ->
                SectionCard(title = tr("Community packs")) {
                    if (list.isEmpty()) Text(tr("No packs yet. Be the first to share one!"))
                    list.forEach { e ->
                        Column(Modifier.fillMaxWidth().clickable {
                            loading = true; error = null
                            scope.launch {
                                runCatching { app.http.get(PidPacks.urlOf(e)) }.onSuccess { openPack(it) }.onFailure { error = tr("Could not download the pack.") }
                                loading = false
                            }
                        }.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (e.verified) Icon(Icons.Default.Verified, tr("Tested on a real car"), tint = Good)
                            }
                            Text(listOf(e.makes.joinToString(), e.models.joinToString(), e.years, e.engines.joinToString()).filter { it.isNotBlank() }.joinToString(" · ") +
                                " · " + tr("%1\$s PIDs", e.pids) + (if (e.codings > 0) " · " + tr("%1\$s codings", e.codings) else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    preview?.let { pack ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(pack.name) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOf(pack.makes.joinToString(), pack.models.joinToString(), pack.years, pack.engines.joinToString()).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                    if (pack.author.isNotBlank()) Text(tr("By %1\$s", pack.author), style = MaterialTheme.typography.bodySmall)
                    Text(if (pack.verified) tr("Tested on a real car by the author.") else tr("Not marked as tested on a real car: values may be wrong for your car."),
                        style = MaterialTheme.typography.bodySmall, color = if (pack.verified) Good else Warn)
                    if (pack.description.isNotBlank()) Text(pack.description, style = MaterialTheme.typography.bodyMedium)
                    pack.pids.forEach { p ->
                        Text("• ${p.name}  ", fontWeight = FontWeight.Medium)
                        Text("   ${p.header.ifBlank { "7DF" }} · ${p.request} · ${p.equation}", fontFamily = com.openobd.ui.theme.Inter, style = MaterialTheme.typography.bodySmall)
                    }
                    if (pack.codings.isNotEmpty() || pack.routines.isNotEmpty()) {
                        Text(tr("Codings (nothing is written until you choose an option in Codings):"), fontWeight = FontWeight.SemiBold, color = Warn)
                        pack.codings.forEach { c ->
                            Text("• ${c.name}" + (if (c.risk == com.openobd.core.brand.WriteRisk.DANGEROUS) " ⚠" else ""), fontWeight = FontWeight.Medium)
                            Text("   ${c.header} · ${c.did} · " + tr("byte %1\$s", c.byte) + " · " + c.options.joinToString(" / ") { it.label }, style = MaterialTheme.typography.bodySmall)
                        }
                        pack.routines.forEach { r -> Text("• ${r.name} ⚠", fontWeight = FontWeight.Medium) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    scope.launch {
                        var added = 0; var skipped = 0; var codings = 0
                        app.garage.updateActive { v ->
                            val r = PidPacks.merge(v.customPids, v.customModules, pack) { app.garage.newId() }
                            val (c, rt, n) = PidPacks.mergeCodings(v.codings, v.routines, pack) { app.garage.newId() }
                            added = r.added; skipped = r.skipped; codings = n
                            v.copy(customPids = r.pids, customModules = r.modules, codings = c, routines = rt)
                        }
                        message = tr("Added %1\$s PIDs (%2\$s already present). They appear in Live data under \"Brand / custom\".", added, skipped) +
                            (if (codings > 0) " " + tr("%1\$s codings added: find them in Brand features > Codings.", codings) else "")
                    }
                }, enabled = car != null) { Text(tr("Add to my car")) }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(tr("Cancel")) } },
        )
    }
}
