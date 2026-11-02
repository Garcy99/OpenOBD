package com.openobd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.BuildConfig
import com.openobd.core.dtc.DtcDescription
import com.openobd.core.obd.Pids
import com.openobd.ui.LocalApp
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun DtcLookupScreen(onBack: () -> Unit) {
    val db = LocalApp.current.dtcDatabase
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<DtcDescription?>(null) }
    val results = remember(query) {
        val q = query.trim()
        when {
            q.isEmpty() -> emptyList()
            Regex("^[PCBUpcbu][0-9A-Fa-f]{4}$").matches(q) -> listOf(db.lookup(q)) + db.search(q).filter { it.code != q.uppercase() }
            else -> db.search(q)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Trouble code lookup", onBack)
        OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Code (P0420) or keyword (oxygen)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Text("${db.size} generic codes available offline", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 6.dp))
        LazyColumn {
            items(results, key = { it.code }) { d ->
                ListItem(
                    headlineContent = { Text(d.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(d.description) },
                    modifier = Modifier.clickable { detail = d },
                )
                HorizontalDivider()
            }
        }
    }
    detail?.let { d -> DtcDetailDialog(d.code, d, null) { detail = null } }
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val records by app.history.records.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Scan history", onBack, actions = {
            if (records.isNotEmpty()) IconButton(onClick = { scope.launch { app.history.clear() } }) { Icon(Icons.Default.DeleteSweep, "Clear history") }
        })
        if (records.isEmpty()) { CenteredMessage(Icons.Default.History, "Scans and clears will be listed here."); return@Column }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(records, key = { it.timestamp }) { r ->
                SectionCard {
                    Row {
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.timestamp)), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        when {
                            r.cleared -> Pill("Cleared", Warn)
                            r.codes.isEmpty() -> Pill("No codes", Good)
                            else -> Pill("${r.codes.size} code(s)", Bad)
                        }
                    }
                    r.vin?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    r.codes.forEach { c ->
                        Row(Modifier.padding(top = 6.dp)) {
                            Text(c.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
                            Text("${c.description} (${c.type.lowercase()})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("About", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard {
                Text("OpenOBD", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("A free, open-source OBD-II scanner for ELM327 compatible adapters. ${Pids.all.size} standard PIDs, " +
                    "${Pids.sensors.size} sensors, trouble codes from every ECU, freeze frames, readiness, Mode 06, vehicle info, logging and more.")
            }
            SectionCard(title = "Supported adapters") {
                Text("• ELM327 v1.4 / v1.5 / v2.x and clones (Bluetooth Classic, BLE, Wi-Fi)\n" +
                    "• OBDLink (STN11xx/STN21xx), Vgate iCar / vLinker, Veepeak, Konnwei, BAFX, Carista and similar\n" +
                    "• All OBD-II protocols: CAN 11/29-bit 250/500k, KWP2000, ISO 9141-2, J1850 PWM/VPW")
            }
            SectionCard(title = "Disclaimer") {
                Text("Use at your own risk. Do not operate the app while driving. Clearing codes does not fix the underlying problem. " +
                    "Code descriptions are generic (SAE J2012); manufacturer-specific codes may differ.",
                    style = MaterialTheme.typography.bodySmall)
            }
            SectionCard(title = "Open-source licenses") {
                Text("Built with Kotlin, Jetpack Compose, AndroidX and kotlinx libraries (Apache License 2.0).", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
