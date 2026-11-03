package com.openobd.ui.components

import com.openobd.ui.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobd.core.obd.SensorDef

/** Searchable sensor list. Single-select calls [onPick]; multi-select when [selected] is non-null. */
@Composable
fun SensorPickerDialog(
    sensors: List<SensorDef>,
    title: String,
    onDismiss: () -> Unit,
    onPick: (SensorDef) -> Unit = {},
    selected: Set<String>? = null,
    onConfirm: (Set<String>) -> Unit = {},
    maxSelection: Int = Int.MAX_VALUE,
) {
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(selected ?: emptySet()) }
    val filtered = sensors.filter { query.isBlank() || it.name.contains(query, true) || tr(it.name).contains(query, true) || it.shortName.contains(query, true) || it.key.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(tr("Search sensors")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.padding(4.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    filtered.groupBy { it.category }.forEach { (cat, list) ->
                        item(key = "h_" + cat.name) {
                            Text(tr(cat.label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                        items(list, key = { it.key }) { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (selected == null) onPick(s)
                                    else chosen = if (s.key in chosen) chosen - s.key else if (chosen.size < maxSelection) chosen + s.key else chosen
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selected != null) {
                                    Checkbox(s.key in chosen, null)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(tr(s.name), fontWeight = FontWeight.Medium)
                                    Text(s.unit.symbol.ifEmpty { "status" } + "  ·  " + s.key, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selected != null) TextButton(onClick = { onConfirm(chosen) }) { Text("OK (${chosen.size})") }
            else TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
        dismissButton = { if (selected != null) TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
