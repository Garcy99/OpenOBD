package com.openobd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.Brand
import com.openobd.core.brand.Brands
import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ManufacturerCodes
import com.openobd.core.brand.ModuleScanResult
import com.openobd.session.Op
import com.openobd.ui.LocalApp
import com.openobd.ui.Routes
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.StatusDot
import com.openobd.ui.connectedVehicle
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import kotlinx.coroutines.launch

@Composable
fun BrandScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val scope = rememberCoroutineScope()
    val vehicle = connectedVehicle()
    val settings = appSettings()
    val garageData by app.garage.data.collectAsStateWithLifecycle()
    val activeVehicle = garageData.active
    val brand by session.brand.collectAsStateWithLifecycle()
    val detected by session.brandAutoDetected.collectAsStateWithLifecycle()
    val scan by session.moduleScan.collectAsStateWithLifecycle()
    val discovery by session.discovery.collectAsStateWithLifecycle()
    val bus by session.bus.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var showCodes by remember { mutableStateOf(false) }
    val family = brand?.family ?: com.openobd.core.brand.Families.GENERIC
    val canUseModules = vehicle?.elm?.protocol?.isCan == true

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Brand features", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard {
                activeVehicle?.let { Text(it.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Text(brand?.name ?: "No brand selected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        brand == null -> "Connect to detect it from the VIN, or choose it manually."
                        detected -> "Detected from the VIN · ${family.label}"
                        else -> "Chosen manually · ${family.label}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picking = true }) { Text("Choose brand") }
                    if (activeVehicle?.brandId != null) OutlinedButton(onClick = { scope.launch { app.garage.updateActive { it.copy(brandId = null) } } }) { Text("Auto-detect") }
                }
                Spacer(Modifier.height(10.dp))
                Text(family.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (family.modules.any { it.secondaryBus } || settings.msCanSwitch) {
                BusSwitchCard(canUseModules)
            }

            SectionCard(title = if (bus == com.openobd.session.Bus.MS) "Control modules on MS-CAN" else "Control modules") {
                Text("Reads and clears trouble codes in ABS, airbag, body, cluster, steering, EV battery and other modules (UDS / KWP2000 on CAN).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if (vehicle == null) Text("Connect to your car to scan modules.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (!canUseModules) ErrorText("Module access needs a CAN vehicle (most cars from 2008 onwards). This car uses ${vehicle.elm.protocol.label}.")
                else if (bus == com.openobd.session.Bus.RETURNING) ErrorText("Switching back to HS-CAN - see the switch card above.")
                else Button(onClick = session::scanAllModules, enabled = scan !is Op.Loading && discovery !is Op.Loading, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan all ${session.modulesOnCurrentBus().size} modules")
                }
                when (val s = scan) {
                    is Op.Loading -> { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s.progress ?: "", style = MaterialTheme.typography.bodySmall) }
                    is Op.Error -> ErrorText(s.message)
                    else -> Unit
                }
                val results = (scan as? Op.Done)?.value
                Spacer(Modifier.height(6.dp))
                if (results != null) {
                    val online = results.count { it.online }
                    val codes = results.sumOf { it.dtcs.size }
                    Text("$online of ${results.size} modules answered · $codes code(s)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                    results.sortedWith(compareByDescending<ModuleScanResult> { it.dtcs.size }.thenByDescending { it.online }).forEach { r ->
                        ModuleRow(r.module, r) { onNavigate(Routes.module(r.module.id)) }
                    }
                } else {
                    session.modulesOnCurrentBus().forEach { m -> ModuleRow(m, null) { onNavigate(Routes.module(m.id)) } }
                }
                val otherBus = session.modulesForBrand().filter { it.secondaryBus != (bus == com.openobd.session.Bus.MS) }
                if (otherBus.isNotEmpty()) {
                    Text(if (bus == com.openobd.session.Bus.MS) "On HS-CAN (switch back to reach them)" else "On MS-CAN (needs the adapter switch)",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    otherBus.forEach { m -> ModuleRow(m, null, dimmed = true) { onNavigate(Routes.module(m.id)) } }
                }
            }

            SectionCard(title = "Discover modules") {
                Text("Probes every diagnostic address (700-7F7) to find all modules on your car, whatever the brand. Takes about 30-60 seconds.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = session::discoverModules, enabled = canUseModules && discovery !is Op.Loading && scan !is Op.Loading,
                    modifier = Modifier.fillMaxWidth()) { Text("Discover modules") }
                when (val d = discovery) {
                    is Op.Loading -> { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(d.progress ?: "", style = MaterialTheme.typography.bodySmall) }
                    is Op.Error -> ErrorText(d.message)
                    is Op.Done -> {
                        val known = session.modulesForBrand().map { it.id }.toSet()
                        val new = d.value.filter { it.id !in known }
                        Text("${d.value.size} module(s) answered" + if (new.isNotEmpty()) ", ${new.size} not in the list yet" else "",
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp))
                        d.value.forEach { m -> ModuleRow(m, null) { onNavigate(Routes.module(m.id)) } }
                        if (new.isNotEmpty()) TextButton(onClick = {
                            scope.launch { app.garage.updateActive { it.copy(customModules = (it.customModules + new).distinctBy { m -> m.id }) } }
                        }) { Text("Add ${new.size} new module(s) to my list") }
                    }
                    Op.Idle -> Unit
                }
                val saved = activeVehicle?.customModules.orEmpty()
                if (saved.isNotEmpty()) TextButton(onClick = { scope.launch { app.garage.updateActive { it.copy(customModules = emptyList()) } } }) {
                    Text("Forget ${saved.size} saved module(s)")
                }
            }

            SectionCard(title = "Manufacturer-specific codes") {
                val n = ManufacturerCodes.count(family)
                Text(if (n > 0) "$n ${family.label} codes are decoded automatically in every scan." else "No manufacturer code table for ${family.label} yet; generic descriptions and web search are used.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (n > 0) TextButton(onClick = { showCodes = true }) { Text("View ${family.label} codes") }
            }

            SectionCard(title = "Brand & custom PIDs") {
                Text("${activeVehicle?.customPids.orEmpty().size} custom PID(s). Add manufacturer data (transmission temperature, EV battery health, tire pressures, DPF soot …) " +
                    "by hand or import Torque-format CSV packs shared by owner communities.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onNavigate(Routes.PIDS) }) { Text("Manage custom PIDs") }
            }

            SectionCard(title = "Safety") {
                Text("OpenOBD only reads data and clears trouble codes. It never writes coding, adaptations or software, which could disable a module. " +
                    "Some 2018+ vehicles (notably Stellantis) have a secure gateway that blocks clearing codes without an authorised unlock.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (picking) BrandPickerDialog(onDismiss = { picking = false }) { b ->
        picking = false
        scope.launch { app.garage.updateActive { it.copy(brandId = b.id) } }
    }
    if (showCodes) {
        AlertDialog(
            onDismissRequest = { showCodes = false },
            title = { Text("${family.label} codes") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(ManufacturerCodes.all(family).entries.sortedBy { it.key }.toList()) { (c, d) ->
                        Row(Modifier.padding(vertical = 6.dp)) {
                            Text(c, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                            Text(d, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCodes = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ModuleRow(m: EcuModule, r: ModuleScanResult?, dimmed: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(when {
            r == null -> MaterialTheme.colorScheme.outline
            !r.online -> MaterialTheme.colorScheme.outlineVariant
            r.dtcs.any { it.isActive || it.warningLamp } -> Bad
            r.dtcs.isNotEmpty() -> Warn
            else -> Good
        })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, fontWeight = FontWeight.Medium, color = if (dimmed || (r != null && !r.online)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(m.addressLabel + " · " + m.category.label + (if (m.secondaryBus) " · MS-CAN" else "") + (r?.protocol?.let { " · " + it.name } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            r == null -> Unit
            !r.online -> Text("no answer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.dtcs.isEmpty() -> Pill("OK", Good)
            else -> Pill("${r.dtcs.size} code(s)", if (r.dtcs.any { it.isActive || it.warningLamp }) Bad else Warn)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
fun BrandPickerDialog(onDismiss: () -> Unit, onPick: (Brand) -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = Brands.all.filter { query.isBlank() || it.name.contains(query, true) || it.family.label.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose brand") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.id }) { b ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 10.dp)) {
                            Text(b.name, fontWeight = FontWeight.Medium)
                            Text(b.family.label + " · ${b.family.modules.size} known modules", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Guides the user through flipping a manual HS/MS-CAN switch adapter and moves the app between the two buses. */
@Composable
private fun BusSwitchCard(canUseModules: Boolean) {
    val app = LocalApp.current
    val session = app.session
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val bus by session.bus.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf<com.openobd.session.Bus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    SectionCard(title = "HS / MS-CAN switch") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My adapter has an HS/MS-CAN switch", Modifier.weight(1f))
            androidx.compose.material3.Switch(settings.msCanSwitch, { v -> scope.launch { app.settings.update { it.copy(msCanSwitch = v) } } })
        }
        Text("Ford, Lincoln and Mazda put climate, doors, SYNC/infotainment and some body modules on a second, slower bus (MS-CAN). " +
            "\"Switch\" adapters reach it when you move their toggle to MS.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.msCanSwitch && canUseModules) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (bus == com.openobd.session.Bus.HS) Good else Warn)
                Spacer(Modifier.width(8.dp))
                Text(when (bus) {
                    com.openobd.session.Bus.HS -> "On HS-CAN (normal): engine, ABS, airbag …"
                    com.openobd.session.Bus.MS -> "On MS-CAN: live data and engine features are paused"
                    com.openobd.session.Bus.RETURNING -> "Waiting for HS-CAN - is the switch back on HS?"
                }, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            if (bus == com.openobd.session.Bus.HS) FilledTonalButton(onClick = { prompt = com.openobd.session.Bus.MS }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Switch to MS-CAN") }
            else Button(onClick = { prompt = com.openobd.session.Bus.HS }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Return to HS-CAN") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            message?.let { (ok, t) -> if (ok) Text(t, color = Good, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) else ErrorText(t) }
        }
    }

    prompt?.let { target ->
        val toMs = target == com.openobd.session.Bus.MS
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text(if (toMs) "Move the switch to MS-CAN" else "Move the switch back to HS-CAN") },
            text = {
                Text(if (toMs) "Flip the switch on your adapter to the MS-CAN position (often labelled \"MS\" or \"125K\"). Keep the ignition on, then tap Continue."
                else "Flip the switch on your adapter back to HS-CAN (often labelled \"HS\" or \"500K\"), then tap Continue. Live data resumes once the car answers.")
            },
            confirmButton = {
                TextButton(onClick = {
                    prompt = null
                    busy = true
                    scope.launch {
                        message = try {
                            if (toMs) {
                                val answered = session.enterMsCan()
                                val known = session.modulesForBrand().count { it.secondaryBus }
                                if (known == 0) true to "Now on MS-CAN. Use Discover modules to find the modules on this bus."
                                else if (answered.isEmpty()) false to "No known MS-CAN module answered. Check the switch is on MS, then try Discover modules."
                                else true to "Now on MS-CAN: ${answered.size} of $known known modules answered (${answered.joinToString { it.shortName }})."
                            } else {
                                if (session.returnToHsCan()) true to "Back on HS-CAN. Live data resumed."
                                else false to "The car did not answer on HS-CAN. Make sure the switch is back on HS and tap Return to HS-CAN again."
                            }
                        } catch (e: Exception) { false to (e.message ?: "Failed") }
                        busy = false
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } },
        )
    }
}
