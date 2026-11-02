package com.openobd.ui.screens

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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.dtc.DtcDescription
import com.openobd.core.obd.Dtc
import com.openobd.core.obd.DtcType
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.Routes
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.openUrl
import com.openobd.ui.shareText
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn

@Composable
fun DiagnosticsScreen(onConnect: () -> Unit, onNavigate: (String) -> Unit) {
    val session = LocalApp.current.session
    val context = LocalContext.current
    val vehicle = connectedVehicle()
    val scan by session.dtcScan.collectAsStateWithLifecycle()
    val clear by session.clearResult.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Dtc?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Trouble codes", actions = {
            IconButton(onClick = { onNavigate(Routes.LOOKUP) }) { Icon(Icons.Default.Search, "Code lookup") }
            IconButton(onClick = { onNavigate(Routes.HISTORY) }) { Icon(Icons.Default.History, "Scan history") }
            if (vehicle != null) IconButton(onClick = { shareText(context, "Vehicle diagnostic report", session.buildReport()) }) {
                Icon(Icons.Default.Share, "Share report")
            }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = session::scanDtcs, enabled = scan !is Op.Loading, modifier = Modifier.weight(1f)) { Text("Scan") }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = scan !is Op.Loading && clear !is Op.Loading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clear codes")
                }
            }
            when (val c = clear) {
                is Op.Loading -> LoadingBox(c.progress)
                is Op.Done -> Text("✓ ${c.value}", color = Good, style = MaterialTheme.typography.bodyMedium)
                is Op.Error -> ErrorText(c.message)
                Op.Idle -> Unit
            }
            when (val s = scan) {
                is Op.Loading -> LoadingBox(s.progress)
                is Op.Error -> ErrorText(s.message)
                Op.Idle -> Text("Press Scan to read stored, pending and permanent codes from every ECU.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                is Op.Done -> {
                    val r = s.value.readiness
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val bad = r?.milOn == true
                            Icon(if (bad) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = if (bad) Bad else Good, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(if (bad) "Check engine light is ON" else "Check engine light is OFF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${s.value.codes.size} code(s) found across ${s.value.codes.map { it.ecu }.distinct().size.coerceAtLeast(if (s.value.codes.isEmpty()) 0 else 1)} ECU(s)",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (s.value.codes.any { it.type == DtcType.STORED }) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onNavigate(Routes.FREEZE) }) { Text("View freeze frame (conditions when the fault occurred)") }
                        }
                    }
                    s.value.errors.forEach { (t, e) -> ErrorText("${t.label}: $e") }
                    for (type in DtcType.entries) {
                        val codes = s.value.codes.filter { it.type == type }
                        Text("${type.label} (${codes.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp))
                        Text(type.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (codes.isEmpty()) Text("None", style = MaterialTheme.typography.bodyMedium, color = Good)
                        codes.forEach { d -> DtcCard(d, session.dtcDatabase.lookup(d.code), session.client?.ecuName(d.ecu)) { detail = d } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Warn) },
            title = { Text("Clear trouble codes?") },
            text = {
                Text("This erases stored and pending codes, freeze frame data and resets all readiness monitors on every ECU. " +
                    "The check engine light turns off, but it will come back if the fault is still present.\n\n" +
                    "For best results: engine OFF, ignition ON. Your car will not pass an emissions inspection until the monitors complete again.")
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; session.clearDtcs() }) { Text("Clear codes", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    detail?.let { d -> DtcDetailDialog(d.code, session.dtcDatabase.lookup(d.code), d.type.label + " · ECU " + d.ecu) { detail = null } }
}

@Composable
fun DtcCard(d: Dtc, desc: DtcDescription, ecuName: String?, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                    color = if (d.type == DtcType.PENDING) Warn else Bad)
                Spacer(Modifier.width(10.dp))
                Pill(d.system.label, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                desc.knowledge?.let { k -> Pill(desc.severityLabel, when (k.severity) { 3 -> Bad; 2 -> Warn; else -> Good }) }
            }
            Spacer(Modifier.height(4.dp))
            Text(desc.description, style = MaterialTheme.typography.bodyMedium)
            if (ecuName != null) Text(ecuName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DtcDetailDialog(code: String, desc: DtcDescription, subtitle: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(desc.description, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(10.dp))
                Text(desc.category, style = MaterialTheme.typography.bodySmall)
                if (!desc.known) {
                    Text("This code is not in the generic database. Manufacturer-specific meanings vary by brand; use the web search below.",
                        style = MaterialTheme.typography.bodySmall, color = Warn, modifier = Modifier.padding(top = 6.dp))
                }
                desc.knowledge?.let { k ->
                    Spacer(Modifier.height(12.dp))
                    Text("Severity: ${desc.severityLabel}", fontWeight = FontWeight.SemiBold, color = when (k.severity) { 3 -> Bad; 2 -> Warn; else -> Good })
                    Spacer(Modifier.height(8.dp))
                    Text("Common causes", fontWeight = FontWeight.SemiBold)
                    k.causes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
                    Text("Tip", fontWeight = FontWeight.SemiBold)
                    Text(k.advice, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { openUrl(context, "https://www.google.com/search?q=" + android.net.Uri.encode("$code ${desc.description}")) }) { Text("Search web") }
        },
    )
}
