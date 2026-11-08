package com.openobd.ui.screens

import com.openobd.ui.tr

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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.LinearProgressIndicator
import com.openobd.ui.components.ActionGrid
import com.openobd.ui.components.ActionTile
import com.openobd.ui.components.GradientButton
import com.openobd.ui.components.GroupCard
import com.openobd.ui.components.GroupDivider
import com.openobd.ui.components.HeroCard
import com.openobd.ui.components.IconBadge
import com.openobd.ui.components.NavRow
import com.openobd.ui.components.NoticeCard
import com.openobd.ui.components.ScreenColumn
import com.openobd.ui.components.SectionLabel
import com.openobd.ui.components.SoftButton
import com.openobd.ui.components.TabHeader
import com.openobd.ui.theme.Orange
import com.openobd.ui.theme.Violet
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
    val modules by session.modules.moduleScan.collectAsStateWithLifecycle()
    val brand by session.brand.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Dtc?>(null) }
    val connected = vehicle != null

    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("Diagnose"), actions = {
            if (connected) IconButton(onClick = { shareText(context, tr("Vehicle diagnostic report"), session.reports.text()) }) {
                Icon(Icons.Default.Share, tr("Share report"))
            }
        })
        ScreenColumn {
            ScanHero(connected, scan, clear, onConnect, onScan = session::scanDtcs, onClear = { confirmClear = true })
            when (val c = clear) {
                is Op.Loading -> LoadingBox(c.progress)
                is Op.Done -> NoticeCard(Icons.Default.CheckCircle, tr("Codes cleared"), c.value, Good)
                is Op.Error -> ErrorText(c.message)
                Op.Idle -> Unit
            }
            (scan as? Op.Done)?.value?.let { result ->
                result.errors.forEach { (t, e) -> ErrorText(tr(t.label) + ": " + tr(e)) }
                for (type in DtcType.entries) {
                    val codes = result.codes.filter { it.type == type }
                    if (codes.isEmpty()) continue
                    SectionLabel("${tr(type.label)} (${codes.size})")
                    codes.forEach { d -> DtcCard(d, session.describe(d.code), session.client?.ecuName(d.ecu)?.let { tr(it) }) { detail = d } }
                }
                if (result.codes.any { it.type == DtcType.STORED }) {
                    NoticeCard(Icons.Default.Flag, tr("Freeze frame"), tr("View freeze frame (conditions when the fault occurred)"),
                        MaterialTheme.colorScheme.primary) { onNavigate(Routes.FREEZE) }
                }
            }

            SectionLabel(tr("Engine & emissions"))
            GroupCard {
                NavRow(Icons.Default.Checklist, tr("Emission readiness (I/M)"), tr("Smog check monitors, since clear and this drive cycle"), enabled = connected) { onNavigate(Routes.READINESS) }
                GroupDivider()
                NavRow(Icons.Default.Flag, tr("Freeze frame"), tr("Sensor snapshot captured when a fault was stored"), enabled = connected) { onNavigate(Routes.FREEZE) }
                GroupDivider()
                NavRow(Icons.Default.Science, tr("On-board monitor tests (Mode 06)"), tr("Catalyst, O2 sensors, misfire counts per cylinder, EVAP"), enabled = connected) { onNavigate(Routes.TESTS) }
                GroupDivider()
                NavRow(Icons.Default.DirectionsCar, tr("Vehicle information"), tr("VIN decoder, calibration IDs, CVN, ECU names, IPT counters"), enabled = connected) { onNavigate(Routes.VEHICLE) }
            }

            SectionLabel(tr("Other modules"))
            GroupCard {
                val done = (modules as? Op.Done)?.value
                NavRow(Icons.Default.Hub, brand?.let { tr("%1\$s features", it.name) } ?: tr("Brand features & modules"),
                    if (done != null) tr("%1\$s modules answered · %2\$s module code(s)", done.count { it.online }, done.sumOf { it.dtcs.size })
                    else tr("ABS, airbag, body, EV battery modules · manufacturer codes · brand PIDs"),
                    tint = Violet,
                    trailing = { done?.sumOf { it.dtcs.size }?.takeIf { it > 0 }?.let { Pill("$it", Bad) } },
                ) { onNavigate(Routes.BRAND) }
                GroupDivider()
                NavRow(Icons.Default.Build, tr("Codings & adaptations"), tr("Change module settings, with a backup and undo for every change"),
                    tint = Violet) { onNavigate(Routes.CODINGS) }
            }

            SectionLabel(tr("Inspections"))
            ActionGrid(2, listOf(
                { m -> ActionTile(Icons.Default.FactCheck, tr("Used car & pre-ITV check"), m, tint = Good) { onNavigate(Routes.CHECK) } },
                { m -> ActionTile(Icons.Default.PhotoCamera, tr("Identify a warning light"), m, tint = Orange) { onNavigate(Routes.LIGHTS) } },
                { m -> ActionTile(Icons.Default.BatteryChargingFull, tr("Battery & starting test"), m, tint = Good) { onNavigate(Routes.BATTERY) } },
                { m -> ActionTile(Icons.Default.LocalFireDepartment, tr("Diesel particulate filter"), m, tint = Orange) { onNavigate(Routes.DIESEL) } },
            ))

            SectionLabel(tr("Reference"))
            GroupCard {
                NavRow(Icons.Default.Search, tr("Trouble code lookup"), tr("Offline database of 1300+ generic codes")) { onNavigate(Routes.LOOKUP) }
                GroupDivider()
                NavRow(Icons.Default.History, tr("Scan history"), tr("Every scan and clear, so you know when codes came back")) { onNavigate(Routes.HISTORY) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Default.Warning, null, tint = Warn) },
            title = { Text(tr("Clear trouble codes?")) },
            text = {
                Text(tr("This erases stored and pending codes, freeze frame data and resets all readiness monitors on every ECU. ") +
                    tr("The check engine light turns off, but it will come back if the fault is still present.\n\n") +
                    tr("For best results: engine OFF, ignition ON. Your car will not pass an emissions inspection until the monitors complete again."))
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; session.clearDtcs() }) { Text(tr("Clear codes"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }
    detail?.let { d -> androidx.compose.runtime.LaunchedEffect(d) { detail = null; onNavigate(com.openobd.ui.Routes.code(d.code)) } }
}

/** Scan status and the two main actions. */
@Composable
private fun ScanHero(connected: Boolean, scan: Op<*>, clear: Op<*>, onConnect: () -> Unit, onScan: () -> Unit, onClear: () -> Unit) {
    HeroCard {
        if (!connected) {
            IconBadge(Icons.Default.LinkOff, MaterialTheme.colorScheme.primary, 48.dp)
            Spacer(Modifier.height(14.dp))
            Text(tr("Connect to scan your car"), style = MaterialTheme.typography.titleLarge)
            Text(tr("Read the trouble codes of the engine and every module. Code lookup, scan history and the warning light photo work offline."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            GradientButton(tr("Go to connection"), onConnect, Modifier.fillMaxWidth())
            return@HeroCard
        }
        val done = (scan as? Op.Done<*>)?.value as? com.openobd.session.DtcScan
        val mil = done?.readiness?.milOn == true
        val count = done?.codes?.distinctBy { it.code }?.size ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when {
                scan is Op.Loading -> Icons.Default.Search to MaterialTheme.colorScheme.primary
                done == null -> Icons.Default.Search to MaterialTheme.colorScheme.primary
                mil || count > 0 -> Icons.Default.Warning to Bad
                else -> Icons.Default.CheckCircle to Good
            }
            IconBadge(icon, tint, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        scan is Op.Loading -> tr("Scanning…")
                        done == null -> tr("Ready to scan")
                        mil -> tr("Check engine light is ON")
                        count > 0 -> tr("Trouble codes present")
                        else -> tr("No trouble codes")
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    when {
                        scan is Op.Loading -> tr((scan as Op.Loading).progress ?: "Reading trouble codes")
                        done == null -> tr("Press Scan to read stored, pending and permanent codes from every ECU.")
                        else -> tr("%1\$s code(s) found across %2\$s ECU(s)", done.codes.size, done.codes.map { it.ecu }.distinct().size) +
                            (if (mil) "" else " · " + tr("Check engine light is OFF"))
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (scan is Op.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp))
        (scan as? Op.Error)?.let { ErrorText(it.message) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton(if (done == null) tr("Scan") else tr("Scan again"), onScan, Modifier.weight(1f), enabled = scan !is Op.Loading, icon = Icons.Default.Search)
            SoftButton(tr("Clear codes"), onClear, Modifier.weight(1f), enabled = scan !is Op.Loading && clear !is Op.Loading,
                icon = Icons.Default.DeleteSweep, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun DtcCard(d: Dtc, desc: DtcDescription, ecuName: String?, onClick: () -> Unit) {
    val tint = if (d.type == DtcType.PENDING) Warn else Bad
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.code, fontFamily = com.openobd.ui.theme.Inter, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = tint)
            Spacer(Modifier.width(10.dp))
            Pill(tr(d.system.label), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            desc.knowledge?.let { k -> Pill(tr(desc.severityLabel), when (k.severity) { 3 -> Bad; 2 -> Warn; else -> Good }) }
        }
        Spacer(Modifier.height(6.dp))
        Text(desc.description, style = MaterialTheme.typography.bodyMedium)
        if (ecuName != null) Text(ecuName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DtcDetailDialog(code: String, desc: DtcDescription, subtitle: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(code, fontFamily = com.openobd.ui.theme.Inter, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(desc.description, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(10.dp))
                Text(com.openobd.ui.trCategory(desc.category), style = MaterialTheme.typography.bodySmall)
                if (!desc.known) {
                    Text(tr("This code is not in the generic database. Manufacturer-specific meanings vary by brand; use the web search below."),
                        style = MaterialTheme.typography.bodySmall, color = Warn, modifier = Modifier.padding(top = 6.dp))
                }
                desc.knowledge?.let { k ->
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Severity: %1\$s", tr(desc.severityLabel)), fontWeight = FontWeight.SemiBold, color = when (k.severity) { 3 -> Bad; 2 -> Warn; else -> Good })
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Common causes"), fontWeight = FontWeight.SemiBold)
                    k.causes.forEach { Text("• " + tr(it), style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Tip"), fontWeight = FontWeight.SemiBold)
                    Text(tr(k.advice), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
        dismissButton = {
            TextButton(onClick = { openUrl(context, "https://www.google.com/search?q=" + android.net.Uri.encode("$code ${desc.description}")) }) { Text(tr("Search web")) }
        },
    )
}
