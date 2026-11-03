package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.ModuleClient
import com.openobd.core.brand.ModuleDtc
import com.openobd.core.brand.ModuleScanResult
import com.openobd.ui.LocalApp
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import kotlinx.coroutines.launch

@Composable
fun ModuleScreen(moduleId: String, onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val scope = rememberCoroutineScope()
    val module = remember(moduleId) { session.findModule(moduleId) }
    var result by remember { mutableStateOf<ModuleScanResult?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var ident by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var didText by remember { mutableStateOf("F187") }
    var didResult by remember { mutableStateOf<String?>(null) }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        scope.launch {
            try { block() } catch (e: Exception) { message = false to (e.message ?: tr("Failed")) } finally { busy = null }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(module?.shortName ?: tr("Module"), onBack)
        if (module == null) { CenteredMessage(null, tr("Unknown module.")); return@Column }
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        val bus by session.bus.collectAsStateWithLifecycle()
        val onMs = bus == com.openobd.session.Bus.MS
        if (bus == com.openobd.session.Bus.RETURNING || module.secondaryBus != onMs) {
            CenteredMessage(null, if (module.secondaryBus) tr("%1\$s is on MS-CAN. In Brand features, enable the HS/MS-CAN switch option, ", module.name) +
                tr("flip your adapter's switch to MS and tap \"Switch to MS-CAN\".")
            else tr("%1\$s is on HS-CAN but the adapter is on MS-CAN. Go back to Brand features and tap \"Return to HS-CAN\".", module.name))
            return@Column
        }
        LaunchedEffect(module.id) { run(tr("Reading codes")) { result = session.scanModule(module) } }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard {
                Text(tr(module.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                KeyValueRow(tr("Address"), module.addressLabel)
                KeyValueRow(tr("Category"), tr(module.category.label))
                result?.let { r ->
                    KeyValueRow(tr("Status"), if (r.online) tr("Answering") else tr("No answer"), valueColor = if (r.online) Good else Bad)
                    r.protocol?.let { KeyValueRow(tr("Protocol"), it.label) }
                }
                if (module.secondaryBus) Text(tr("This module is on a secondary bus (MS-CAN) and only answers through adapters with a bus switch."),
                    style = MaterialTheme.typography.bodySmall, color = Warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run(tr("Reading codes")) { result = session.scanModule(module); message = null } }, enabled = busy == null, modifier = Modifier.weight(1f)) { Text(tr("Read codes")) }
                OutlinedButton(onClick = { confirmClear = true }, enabled = busy == null && result?.online == true, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr("Clear codes")) }
            }
            busy?.let { LoadingBox(it) }
            message?.let { (ok, text) -> if (ok) Text("✓ $text", color = Good) else ErrorText(text) }

            result?.let { r ->
                if (!r.online) {
                    Text(tr("The module did not answer. It may not be fitted to this car, sit on another bus, or use a protocol ELM327 adapters can't reach."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (r.dtcs.isEmpty()) {
                    Text(tr("No trouble codes stored in this module."), color = Good)
                } else {
                    Text(tr("%1\$s trouble code(s)", r.dtcs.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    r.dtcs.forEach { d -> ModuleDtcCard(module, d) }
                }
            }

            SectionCard(title = tr("Identification")) {
                if (ident == null) TextButton(onClick = { run(tr("Reading identification")) { ident = session.identifyModule(module) } }, enabled = busy == null) {
                    Text(tr("Read part number, software and hardware versions"))
                } else if (ident!!.isEmpty()) Text(tr("The module did not report standard identification data."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else SelectionContainer { Column { ident!!.forEach { (k, v) -> KeyValueRow(k, v) } } }
            }

            SectionCard(title = tr("Read data identifier (advanced)")) {
                Text(tr("Read any UDS data identifier (service 22), e.g. F187 part number or a manufacturer DID."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    OutlinedTextField(didText, { didText = it.uppercase().filter { c -> c.isDigit() || c in 'A'..'F' }.take(4) }, singleLine = true,
                        label = { Text(tr("DID (hex)")) }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val did = didText.toIntOrNull(16) ?: return@TextButton
                        run(tr("Reading DID %1\$s", didText)) {
                            didResult = try {
                                val bytes = session.readDid(module, did)
                                bytes.joinToString(" ") { "%02X".format(it) } + "\n" + ModuleClient.formatDidValue(bytes)
                            } catch (e: Exception) { tr("Error: %1\$s", e.message) }
                        }
                    }, enabled = busy == null && didText.length == 4) { Text(tr("Read")) }
                }
                didResult?.let { SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }

    if (confirmClear && module != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Warn) },
            title = { Text(tr("Clear codes in %1\$s?", module.shortName)) },
            text = { Text(tr("Codes for faults that are still present will come back, and warning lights (ABS, airbag …) stay on until the fault is repaired. Engine off, ignition on is recommended.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    run(tr("Clearing codes")) {
                        val r = session.clearModule(module)
                        message = r.success to r.message
                        result = session.scanModule(module)
                    }
                }) { Text(tr("Clear"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun ModuleDtcCard(module: com.openobd.core.brand.EcuModule, d: ModuleDtc) {
    val session = LocalApp.current.session
    val desc = session.describe(d.code)
    var open by remember { mutableStateOf(false) }
    SectionCard(Modifier) {
        Row {
            Text(d.fullCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                color = if (d.isActive || d.warningLamp) Bad else Warn, modifier = Modifier.weight(1f))
            desc.knowledge?.let { k -> Pill(tr(desc.severityLabel), when (k.severity) { 3 -> Bad; 2 -> Warn; else -> Good }); Spacer(Modifier.width(6.dp)) }
            Pill(tr(d.system.label), MaterialTheme.colorScheme.primary)
        }
        Text(desc.description, style = MaterialTheme.typography.bodyMedium)
        d.failureTypeText?.let { Text(tr("Failure type: %1\$s", tr(it)), style = MaterialTheme.typography.bodySmall) }
        Text(statusText(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { open = true }) { Text(tr("Details, freeze frame & what to check")) }
    }
    if (open) ModuleDtcDialog(module, d) { open = false }
}

@Composable
private fun ModuleDtcDialog(module: com.openobd.core.brand.EcuModule, d: ModuleDtc, onDismiss: () -> Unit) {
    val session = LocalApp.current.session
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val desc = session.describe(d.code)
    var details by remember { mutableStateOf<com.openobd.core.brand.DtcDetails?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.fullCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(desc.description, style = MaterialTheme.typography.titleMedium)
                Text(tr(module.name) + " · " + statusText(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.failureType?.let { ftb ->
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Failure type: %1\$s", tr(com.openobd.core.brand.FailureTypes.describe(ftb))), fontWeight = FontWeight.SemiBold)
                    com.openobd.core.brand.FailureTypes.hint(ftb)?.let { Text(tr(it), style = MaterialTheme.typography.bodyMedium) }
                }
                desc.knowledge?.let { k ->
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Common causes"), fontWeight = FontWeight.SemiBold)
                    k.causes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text(k.advice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
                if (!desc.known) Text(tr("Manufacturer-specific code: meaning varies by brand; use web search."), color = Warn,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (d.protocol == com.openobd.core.brand.DiagProtocol.UDS) {
                    Spacer(Modifier.height(10.dp))
                    when {
                        loading -> LoadingBox(tr("Reading freeze frame and counters"))
                        error != null -> ErrorText(error!!)
                        details == null -> TextButton(onClick = {
                            loading = true
                            scope.launch {
                                try { details = session.dtcDetails(module, d) } catch (e: Exception) { error = e.message }
                                loading = false
                            }
                        }) { Text(tr("Read freeze frame & counters")) }
                        else -> {
                            val dd = details!!
                            if (dd.snapshots.isEmpty() && dd.extended.isEmpty()) Text(tr("The module stored no freeze frame or counters for this code."),
                                style = MaterialTheme.typography.bodySmall)
                            dd.snapshots.forEach { snap ->
                                Text(tr("Freeze frame #%1\$s", snap.record), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                                snap.values.forEach { v -> KeyValueRow(v.name, v.value) }
                                snap.undecoded?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (dd.extended.isNotEmpty()) {
                                Text(tr("Extended data"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                                dd.extended.forEach { (rec, v) -> KeyValueRow(tr("Record %02X").format(rec), v) }
                                Text(tr("Record contents are manufacturer-defined; low record numbers are often the occurrence and aging counters."),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
        dismissButton = {
            TextButton(onClick = { com.openobd.ui.openUrl(context, "https://www.google.com/search?q=" + android.net.Uri.encode("${d.code} ${desc.description}")) }) { Text(tr("Search web")) }
        },
    )
}

/** Status flags are composed in the core in English; translate each part for display. */
private fun statusText(d: ModuleDtc) = d.statusText.split(" · ").joinToString(" · ") { tr(it) }

private val ModuleDtc.system get() = com.openobd.core.obd.DtcSystem.of(code)
