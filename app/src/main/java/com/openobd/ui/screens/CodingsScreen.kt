package com.openobd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.Coding
import com.openobd.core.brand.CodingOption
import com.openobd.core.brand.OutcomeKind
import com.openobd.core.brand.Routine
import com.openobd.core.brand.WriteOutcome
import com.openobd.core.brand.WriteRecord
import com.openobd.core.brand.WriteRisk
import com.openobd.core.brand.WriteSafety
import com.openobd.core.brand.WriteStatus
import com.openobd.session.Bus
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** A write waiting for the user: what it is, and the action once confirmed. */
private class PendingWrite(
    val title: String,
    val what: String,
    val risk: WriteRisk,
    val warning: String,
    val tested: Boolean,
    val engineRunning: Boolean,
    val run: suspend () -> WriteOutcome,
)

private fun outcomeText(o: WriteOutcome) = tr(o.message) + if (o.reason.isNotBlank()) " " + tr(o.reason) else ""

/**
 * Settings stored in the car's modules (codings / adaptations) and routines. Safe changes are written straight
 * away; dangerous ones show an explanation and then a confirmation. Every write saves a backup first, is read
 * back to check it, is put back automatically if the check fails, and can be undone from the history.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodingsScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val scope = rememberCoroutineScope()
    val connected = connectedVehicle() != null
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val bus by session.modules.bus.collectAsStateWithLifecycle()
    val car = garage.active
    val codings = car?.codings.orEmpty()
    val routines = car?.routines.orEmpty()
    val history = garage.writes.filter { it.vehicleId == car?.id }.reversed()
    val current = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var blocked by remember { mutableStateOf<List<String>?>(null) }
    var explain by remember { mutableStateOf<Pair<PendingWrite, List<String>>?>(null) }
    var confirm by remember { mutableStateOf<PendingWrite?>(null) }
    var undo by remember { mutableStateOf<WriteRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    val running = remember { mutableStateMapOf<String, Boolean>() }

    fun execute(p: PendingWrite) {
        busy = p.title; result = null
        scope.launch {
            val o = runCatching { p.run() }.getOrElse { WriteOutcome(OutcomeKind.NOT_CHANGED, it.message ?: "Failed") }
            result = outcomeText(o) to o.ok
            // Undo / restore can change any setting shown: read again to see it.
            if (o.record?.undoOf?.isNotEmpty() == true || o.kind == OutcomeKind.RESTORED) current.clear()
            busy = null
        }
    }

    /** Checks the car first; safe writes then go straight ahead, dangerous ones open the explanation. */
    fun request(p: PendingWrite) {
        if (!connected) { result = tr("Connect to your car to use this feature.") to false; return }
        busy = tr("Checking the car …"); result = null
        scope.launch {
            val state = runCatching { session.modules.carState() }.getOrNull() ?: com.openobd.core.brand.CarState(null, null, null)
            busy = null
            val problems = WriteSafety.problems(p.risk, state, p.engineRunning)
            when {
                problems.isNotEmpty() -> blocked = problems
                p.risk == WriteRisk.SAFE -> execute(p)
                else -> explain = p to WriteSafety.unknowns(state)
            }
        }
    }

    fun readCoding(c: Coding) {
        busy = c.name
        scope.launch {
            runCatching { session.modules.readCoding(c) }
                .onSuccess { d -> current[c.id] = c.current(d)?.label ?: tr("Unknown value %1\$s", "%02X".format(d.getOrElse(c.byte) { 0 } and c.mask)) }
                .onFailure { current[c.id] = tr(com.openobd.core.brand.CodingEngine.reason(it as? Exception)) }
            busy = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Codings & adaptations"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("Every change first reads the current value and saves it as a backup, then writes, then reads again to check. If the check fails, the original value is written back. Any change can be undone from the history."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!connected) OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text(tr("Go to connection")) }

            history.filter { it.needsRestore }.forEach { r ->
                SectionCard(title = tr("A change was not confirmed")) {
                    Text(tr("%1\$s: the module may not hold the original value. Keep the ignition on and restore the backup.", r.name), color = Bad)
                    Button(onClick = { execute(PendingWrite(r.name, tr("Restore backup"), WriteRisk.SAFE, "", true, false) { session.modules.restoreWrite(r) }) },
                        enabled = connected && busy == null) { Text(tr("Restore backup")) }
                }
            }

            busy?.let { b ->
                Column { Text(tr("Working: %1\$s", b), style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            result?.let { (text, ok) -> Text(text, color = if (ok) Good else Warn, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("codingResult")) }

            SectionCard(title = tr("Settings"), action = {
                TextButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(tr("Add")) }
            }) {
                if (codings.isEmpty()) Text(tr("No codings for this car yet. Add them from a pack (PID packs), type in one from a forum, or find one with the identifier explorer."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                codings.forEach { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (c.risk == WriteRisk.DANGEROUS) Pill(tr("Dangerous"), Bad) else Pill(tr("Safe"), Good)
                        }
                        if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall)
                        Text(listOfNotNull(c.header + (if (c.extAddr.isNotBlank()) "/" + c.extAddr else "") + " · " +
                            (if (c.kind != com.openobd.core.brand.IdKind.UDS) c.kind.name + " " else "") + "${c.did} · " + tr("byte %1\$s", c.byte) + " · " + tr("bits %1\$s", "%02X".format(c.mask)),
                            if (c.tested) tr("Tested on a real car") else tr("Not tested"), c.source.takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = if (c.tested) MaterialTheme.colorScheme.onSurfaceVariant else Warn)
                        val wrongBus = c.secondaryBus != (bus == Bus.MS)
                        if (wrongBus) Text(if (c.secondaryBus) tr("This module is on MS-CAN: switch bus in Brand features first.") else tr("The adapter is on MS-CAN. Go to Brand features and switch back to HS-CAN first."),
                            color = Warn, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Now: %1\$s", current[c.id] ?: "—"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { readCoding(c) }, enabled = connected && busy == null && !wrongBus) { Text(tr("Read")) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            c.options.forEach { o ->
                                FilterChip(selected = current[c.id] == o.label, enabled = connected && busy == null && !wrongBus, onClick = {
                                    request(PendingWrite(c.name, o.label, c.risk, c.warning, c.tested, false) {
                                        session.modules.applyCoding(c, o).also { out -> if (out.ok) current[c.id] = o.label }
                                    })
                                }, label = { Text(o.label) })
                            }
                        }
                        TextButton(onClick = { scope.launch { app.garage.updateActive { v -> v.copy(codings = v.codings.filter { it.id != c.id }) } } }) {
                            Text(tr("Remove from list"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (routines.isNotEmpty()) SectionCard(title = tr("Routines")) {
                routines.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Pill(tr("Dangerous"), Bad)
                        }
                        if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.bodySmall)
                        Text(listOfNotNull(r.header, r.start, if (r.tested) tr("Tested on a real car") else tr("Not tested"), r.source.takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = if (r.tested) MaterialTheme.colorScheme.onSurfaceVariant else Warn)
                        Row {
                            Button(onClick = {
                                request(PendingWrite(r.name, tr("Run routine"), WriteRisk.DANGEROUS, r.warning, r.tested, r.engineRunning) {
                                    session.modules.runRoutine(r).also { if (it.ok) running[r.id] = true }
                                })
                            }, enabled = connected && busy == null) { Text(tr("Run")) }
                            Spacer(Modifier.width(8.dp))
                            if (running[r.id] == true && r.stop.isNotBlank()) OutlinedButton(onClick = {
                                scope.launch { val ok = session.modules.stopRoutine(r); running[r.id] = false; result = (if (ok) tr("Stopped.") else tr("The module did not confirm the stop.")) to ok }
                            }) { Text(tr("Stop")) }
                        }
                    }
                }
            }

            SectionCard(title = tr("History and backups")) {
                if (history.isEmpty()) Text(tr("Nothing written yet."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                history.take(60).forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (r.undoOf.isNotEmpty()) tr("Undo") + ": ${r.name} (${r.detail})" else "${r.name} → ${r.detail}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            val (label, color) = statusLabel(r.status)
                            Pill(tr(label), color)
                        }
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.time)) +
                            (if (r.did.isNotEmpty()) " · ${r.header} ${r.did}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.before.isNotEmpty()) Text(tr("Backup: %1\$s", r.before.chunked(2).joinToString(" ")) + "\n" + tr("Written: %1\$s", r.after.chunked(2).joinToString(" ")),
                            fontFamily = com.openobd.ui.theme.Inter, style = MaterialTheme.typography.bodySmall)
                        if (r.message.isNotBlank()) Text(tr(r.message) + if (r.reason.isNotBlank()) " " + tr(r.reason) else "", style = MaterialTheme.typography.bodySmall)
                        if (r.undoable) TextButton(onClick = { undo = r }, enabled = connected && busy == null, modifier = Modifier.testTag("undo_${r.id}")) { Text(tr("Undo")) }
                    }
                }
            }
        }
    }

    blocked?.let { list ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            icon = { Icon(Icons.Default.Warning, null, tint = Warn) },
            title = { Text(tr("Not now")) },
            text = { Column { list.forEach { Text("• " + tr(it)) } } },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text(tr("OK")) } },
        )
    }

    // Dangerous writes, step 1: what it does and what can go wrong.
    explain?.let { (p, unknowns) ->
        AlertDialog(
            onDismissRequest = { explain = null },
            icon = { Icon(Icons.Default.Warning, null, tint = Bad) },
            title = { Text(tr("Before you change this")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${p.title}: ${p.what}", fontWeight = FontWeight.SemiBold)
                    Text(p.warning.ifBlank { tr("This changes how a control module of your car works. A wrong value can switch off a function, light up a warning or stop the module answering until the value is put back.") })
                    if (!p.tested) Text(tr("Nobody has marked this as tested on a real car. It may not match your car."), color = Warn)
                    Text(if (p.engineRunning) tr("The engine must keep running, the car parked, until it finishes. Do not drive away.")
                        else tr("Keep the car parked with the engine off and the ignition on. Do not switch the ignition off or disconnect the adapter while it writes."))
                    Text(tr("A backup of the current value is saved first. If the module does not confirm the change, the backup is written back automatically, and you can undo it later from the history."),
                        style = MaterialTheme.typography.bodySmall)
                    unknowns.forEach { Text("• " + tr(it), color = Warn, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { explain = null; confirm = p }) { Text(tr("I understand, continue")) } },
            dismissButton = { TextButton(onClick = { explain = null }) { Text(tr("Cancel")) } },
        )
    }

    // Dangerous writes, step 2: the confirmation.
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("Confirm the change")) },
            text = { Text(tr("Write \"%1\$s\" to %2\$s now?", p.what, p.title)) },
            confirmButton = {
                Button(onClick = { confirm = null; execute(p) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirmWrite")) { Text(tr("Write now")) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Cancel")) } },
        )
    }

    undo?.let { r ->
        AlertDialog(
            onDismissRequest = { undo = null },
            title = { Text(tr("Undo this change?")) },
            text = { Text(tr("The value from before (%1\$s) is written back to %2\$s.", r.before.chunked(2).joinToString(" "), r.name)) },
            confirmButton = { TextButton(onClick = { undo = null; execute(PendingWrite(r.name, tr("Undo"), WriteRisk.SAFE, "", true, false) { session.modules.undoWrite(r) }) }, modifier = Modifier.testTag("confirmUndo")) { Text(tr("Undo")) } },
            dismissButton = { TextButton(onClick = { undo = null }) { Text(tr("Cancel")) } },
        )
    }

    if (adding) CodingEditor(onDismiss = { adding = false }) { c ->
        adding = false
        scope.launch { app.garage.updateActive { v -> v.copy(codings = v.codings + c.copy(id = app.garage.newId())) } }
    }
}

private fun statusLabel(s: WriteStatus): Pair<String, androidx.compose.ui.graphics.Color> = when (s) {
    WriteStatus.APPLIED -> "Applied" to Good
    WriteStatus.UNDONE -> "Undone" to Good
    WriteStatus.UNCHANGED -> "Not changed" to Warn
    WriteStatus.RESTORED -> "Restored" to Warn
    WriteStatus.RESTORE_FAILED -> "Needs restore" to Bad
    WriteStatus.PENDING -> "Interrupted" to Bad
    WriteStatus.ROUTINE_RUN -> "Started" to Good
    WriteStatus.ROUTINE_FAILED -> "Failed" to Warn
}

/** Types in a coding from a forum list: module, identifier, byte, bits and the two values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodingEditor(initial: Coding? = null, onDismiss: () -> Unit, onSave: (Coding) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var header by remember { mutableStateOf(initial?.header ?: "") }
    var receive by remember { mutableStateOf(initial?.receive.orEmpty()) }
    var did by remember { mutableStateOf(initial?.did.orEmpty()) }
    var byte by remember { mutableStateOf(initial?.byte?.toString().orEmpty()) }
    var mask by remember { mutableStateOf(initial?.let { "%02X".format(it.mask) } ?: "") }
    var off by remember { mutableStateOf(initial?.options?.getOrNull(0)?.label ?: tr("Off")) }
    var offValue by remember { mutableStateOf(initial?.options?.getOrNull(0)?.value ?: "00") }
    var on by remember { mutableStateOf(initial?.options?.getOrNull(1)?.label ?: tr("On")) }
    var onValue by remember { mutableStateOf(initial?.options?.getOrNull(1)?.value ?: "") }
    var safe by remember { mutableStateOf(initial?.risk == WriteRisk.SAFE) }
    var ms by remember { mutableStateOf(initial?.secondaryBus ?: false) }
    var kind by remember { mutableStateOf(initial?.kind ?: com.openobd.core.brand.IdKind.UDS) }
    var ext by remember { mutableStateOf(initial?.extAddr.orEmpty()) }
    var session by remember { mutableStateOf("%02X".format(initial?.session ?: 3)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add a coding")) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Copy the values from a coding list (module address, identifier, byte and bits). Only use lists for your exact model."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Protocol"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.openobd.core.brand.IdKind.entries.forEach { k ->
                        FilterChip(kind == k, {
                            kind = k
                            session = if (k == com.openobd.core.brand.IdKind.UDS) "03" else "00"
                            did = did.take(k.idBytes * 2)
                        }, label = { Text(k.label) })
                    }
                }
                Text(tr("UDS: most cars since about 2008. KWP2000: many 2000-2010 cars. GMLAN: GM, Opel / Vauxhall and Chevrolet of about 2005-2017."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(header, { header = it.uppercase().take(6) }, label = { Text(tr("Module ID")) }, placeholder = { Text("726") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(receive, { receive = it.uppercase().take(3) }, label = { Text(tr("Reply ID")) }, placeholder = { Text("72E") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(ext, { ext = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(2) }, label = { Text(tr("Extended address")) }, placeholder = { Text("60") },
                        supportingText = { Text(tr("BMW / MINI: module address, with Module ID 6F1")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(session, { session = it.uppercase().take(2) }, label = { Text(tr("Session (hex)")) },
                        supportingText = { Text(tr("00 = none")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(did, { did = it.uppercase().take(kind.idBytes * 2) }, label = { Text(tr("Identifier")) },
                        placeholder = { Text(if (kind.idBytes == 2) "0101" else "10") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(byte, { byte = it.filter(Char::isDigit).take(3) }, label = { Text(tr("Byte")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(mask, { mask = it.uppercase().take(2) }, label = { Text(tr("Bits (hex)")) }, placeholder = { Text("04") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(off, { off = it }, label = { Text(tr("Option 1")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(offValue, { offValue = it.uppercase().take(2) }, label = { Text(tr("Value (hex)")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(on, { on = it }, label = { Text(tr("Option 2")) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(onValue, { onValue = it.uppercase().take(2) }, label = { Text(tr("Value (hex)")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Safe change"))
                        Text(tr("Only for comfort settings (lights, locks, displays). Anything else stays dangerous and asks twice."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(safe, { safe = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Module on MS-CAN"), Modifier.weight(1f))
                    Switch(ms, { ms = it })
                }
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = Coding(
                    id = initial?.id ?: "new", name = name.trim(), header = header.trim(), receive = receive.trim(), did = did.trim(),
                    byte = byte.toIntOrNull() ?: -1, mask = mask.toIntOrNull(16) ?: 0,
                    options = listOf(CodingOption(off.trim(), offValue.trim()), CodingOption(on.trim(), onValue.trim())),
                    risk = if (safe) WriteRisk.SAFE else WriteRisk.DANGEROUS, tested = false, source = initial?.source ?: tr("Typed in"),
                    secondaryBus = ms, kind = kind, extAddr = ext.trim(), session = session.toIntOrNull(16) ?: -1,
                )
                val e = c.validate()
                if (e != null) error = tr(e) else onSave(c)
            }) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
