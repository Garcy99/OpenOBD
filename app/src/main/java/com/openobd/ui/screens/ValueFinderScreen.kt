package com.openobd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.ValueFinder
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Good
import com.openobd.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds where this car's engine computer keeps a value seen in another app (ScanMyOpel and the like): the particulate
 * filter's soot load, temperature, distance since the last regeneration and regenerations, or any other value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ValueFinderScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val finder = app.session.finder
    val st by finder.state.collectAsStateWithLifecycle()
    val vehicle = connectedVehicle()
    var q by remember { mutableStateOf(ValueFinder.Quantity.DPF_SOOT) }
    var text by remember { mutableStateOf("") }
    var otherName by remember { mutableStateOf("") }
    var refineText by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<ValueFinder.Candidate?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    val value = text.replace(',', '.').toDoubleOrNull()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Find the filter values"), onBack)
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Engine computers keep these values in their own identifiers, different on each engine. Look the value up in another app (ScanMyOpel, for example), then type it here: OpenOBD finds where your engine keeps it and uses it from then on (the filter card, the alerts and the gauges)."),
                style = MaterialTheme.typography.bodyMedium)
            Text(tr("Do it with the engine idling or the ignition on and the car standing, so the value does not change in between. The first search reads the engine computer for a few minutes."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard(title = tr("What value?")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ValueFinder.Quantity.entries.forEach { x ->
                        FilterChip(q == x, { q = x; picked = null }, label = { Text(tr(x.label)) }, modifier = Modifier.testTag("q_${x.name}"))
                    }
                }
                if (q == ValueFinder.Quantity.OTHER) OutlinedTextField(otherName, { otherName = it.take(40) }, singleLine = true, label = { Text(tr("Name")) },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }.take(10) }, singleLine = true,
                    label = { Text(tr("Value shown in the other app") + if (q.unit.isNotEmpty()) " (${q.unit})" else "") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("finderValue"))
                if (value == 0.0) Text(tr("Zero matches too many things: wait until the value is not zero."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { picked = null; saved = null; finder.search(value!!, q) }, enabled = value != null && value != 0.0,
                        modifier = Modifier.testTag("finderSearch")) { Text(tr("Search")) }
                    if (st.scanning) OutlinedButton(onClick = { finder.stop() }) { Text(tr("Stop")) }
                }
            }
            if (st.scanning || st.done > 0) SectionCard {
                if (st.total > 0) LinearProgressIndicator({ (st.done.toFloat() / st.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text(tr("%1\$s identifiers answered", st.answered) + if (st.scanning) " · " + tr("reading the engine computer…") else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            st.error?.let { ErrorText(tr(it)) }
            if (st.searched) SectionCard(title = tr("Where it could be")) {
                if (st.candidates.isEmpty()) Text(if (st.scanning) tr("Nothing yet: still reading.") else
                    tr("Nothing reads as that value. Check it in the other app (it may have changed) and search again."), style = MaterialTheme.typography.bodySmall)
                st.candidates.take(15).forEach { c ->
                    val sel = picked?.key == c.key
                    Row(Modifier.fillMaxWidth().clickable { picked = c; saved = null }.padding(vertical = 6.dp).testTag("cand_${c.key}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.kind.readRequest(c.id) + " · " + ValueFinder.equation(c), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("%.1f %s".format(c.value, q.unit).trim(), fontWeight = FontWeight.SemiBold, color = if (sel) Good else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (st.candidates.size > 1 && !st.scanning) {
                    Text(tr("Several readings match. When the value has changed (after driving a little), type the new one to narrow them down."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(refineText, { refineText = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '-' }.take(10) }, singleLine = true,
                            label = { Text(tr("New value")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        TextButton(onClick = { refineText.replace(',', '.').toDoubleOrNull()?.let { finder.refine(it); picked = null } },
                            enabled = refineText.replace(',', '.').toDoubleOrNull() != null) { Text(tr("Narrow down")) }
                    }
                }
            }
            picked?.let { c ->
                var now by remember(c.key) { mutableStateOf<Double?>(null) }
                LaunchedEffect(c.key) { while (true) { now = finder.readNow(c); delay(2000) } }
                SectionCard(title = tr("Check it")) {
                    Text(tr("It reads now: %1\$s", now?.let { "%.1f %s".format(it, q.unit).trim() } ?: "…"), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("finderNow"))
                    Text(tr("If it follows the other app, save it."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val name = if (q == ValueFinder.Quantity.OTHER) otherName.ifBlank { tr("Value found") } else tr(q.label)
                    Button(onClick = { scope.launch { finder.save(c, q, name); saved = name } }, modifier = Modifier.padding(top = 6.dp).testTag("finderSave")) {
                        Text(tr("Save as \"%1\$s\"", name))
                    }
                    saved?.let { Text(tr("Saved. The filter card, the alerts and the gauges use it from now on."), color = Good, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
