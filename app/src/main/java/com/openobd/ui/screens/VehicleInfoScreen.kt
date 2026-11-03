package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.obd.Pids
import com.openobd.core.obd.VinDecoder
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good

@Composable
fun VehicleInfoScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val session = LocalApp.current.session
    val vehicle = connectedVehicle()
    val op by session.vehicleInfo.collectAsStateWithLifecycle()
    LaunchedEffect(vehicle != null) { if (vehicle != null && op !is Op.Done) session.loadVehicleInfo() }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Vehicle information"), onBack, actions = {
            if (vehicle != null) IconButton(onClick = session::loadVehicleInfo) { Icon(Icons.Default.Refresh, tr("Refresh")) }
        })
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val info = (op as? Op.Done)?.value
            val vin = info?.vin ?: vehicle.vin
            SectionCard(title = "VIN") {
                if (vin == null) {
                    Text(tr("This vehicle does not report its VIN over OBD (common before ~2005)."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val d = VinDecoder.decode(vin)
                    SelectionContainer { Text(d.vin, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
                    KeyValueRow(tr("Manufacturer"), d.manufacturer ?: tr("Unknown (%1\$s)", d.wmi))
                    KeyValueRow(tr("Model year"), d.modelYear?.toString() ?: tr("Unknown"))
                    KeyValueRow(tr("Country"), d.country ?: d.region)
                    KeyValueRow(tr("Region"), d.region)
                    d.plantCode?.let { KeyValueRow(tr("Plant code"), it.toString()) }
                    KeyValueRow(tr("Serial"), d.serial)
                    d.checkDigitValid?.let { KeyValueRow(tr("Check digit"), if (it) tr("Valid") else tr("Invalid"), valueColor = if (it) Good else Bad) }
                }
            }
            SectionCard(title = tr("Connection")) {
                KeyValueRow(tr("Protocol"), vehicle.elm.protocol.label)
                KeyValueRow(tr("Adapter"), vehicle.elm.version)
                vehicle.elm.deviceDescription?.let { KeyValueRow(tr("Adapter description"), it) }
                vehicle.elm.stnVersion?.let { KeyValueRow(tr("STN firmware"), it) }
                KeyValueRow(tr("Link"), tr(vehicle.transport))
                vehicle.voltage?.let { KeyValueRow(tr("Battery voltage"), "%.2f V".format(it)) }
                session.live.latestValue("011C")?.let { KeyValueRow(tr("OBD standard"), Pids.obdStandards[it.toInt()] ?: tr("Unknown")) }
                session.live.latestValue("0151")?.let { KeyValueRow(tr("Fuel type"), Pids.fuelTypes[it.toInt()] ?: tr("Unknown")) }
            }
            SectionCard(title = tr("Control modules")) {
                vehicle.ecus.forEach { KeyValueRow(it.address, tr(it.name)) }
                info?.ecuNames?.forEach { (ecu, name) -> KeyValueRow(tr("Name reported by %1\$s", ecu), name) }
            }
            when (val s = op) {
                is Op.Loading -> LoadingBox(s.progress)
                is Op.Error -> ErrorText(s.message)
                else -> Unit
            }
            info?.let {
                if (it.calibrationIds.isNotEmpty() || it.cvns.isNotEmpty()) SectionCard(title = tr("Software calibration")) {
                    it.calibrationIds.forEach { (ecu, id) -> KeyValueRow(tr("Calibration ID (%1\$s)", ecu), id) }
                    it.cvns.forEach { (ecu, cvn) -> KeyValueRow("CVN ($ecu)", cvn) }
                }
                if (it.inUsePerformance.isNotEmpty()) SectionCard(title = tr("In-use performance tracking")) {
                    Text(tr("How often each monitor ran (completions) vs. had the chance to run (conditions)."), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    it.inUsePerformance.forEach { (n, v) -> KeyValueRow(n, v.toString()) }
                }
            }
            SectionCard(title = tr("Supported PIDs (%1\$s)", (vehicle.supportedPids.count { it % 0x20 != 0 }))) {
                Text(vehicle.supportedPids.filter { it % 0x20 != 0 }.joinToString("  ") { "%02X".format(it) },
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                vehicle.supportedPids.filter { it % 0x20 != 0 }.forEach { p ->
                    Pids.all[p]?.let { KeyValueRow("%02X".format(p), it.description) }
                }
            }
        }
    }
}
