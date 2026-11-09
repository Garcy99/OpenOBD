package com.openobd.ui.screens

import com.openobd.ui.tr

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.Brands
import com.openobd.core.garage.FuelEntry
import com.openobd.core.garage.FuelStats
import com.openobd.core.garage.Maintenance
import com.openobd.core.garage.MaintenanceItem
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.Vehicle
import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.UnitSystem
import com.openobd.core.obd.display
import com.openobd.ui.LocalApp
import com.openobd.ui.Routes
import com.openobd.ui.appSettings
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.ChartSeries
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.LineChart
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.TrackMap
import com.openobd.ui.components.formatDuration
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Cyan
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------- formatting helpers

fun fmtKm(km: Double?, units: UnitSystem, decimals: Int = 0): String =
    km?.let { ObdUnit.KM.display(it, units).let { d -> "%,.${decimals}f %s".format(d.value, d.unit.symbol) } } ?: "—"

fun fmtL100(v: Double?, units: UnitSystem): String =
    v?.let { ObdUnit.L_PER_100KM.display(it, units).let { d -> "%.1f %s".format(d.value, d.unit.symbol) } } ?: "—"

fun fmtLiters(v: Double, units: UnitSystem): String = ObdUnit.LITERS.display(v, units).let { "%.2f %s".format(it.value, it.unit.symbol) }

fun fmtMoney(v: Double?): String = v?.let { "%.2f".format(it) } ?: "—"

private fun fmtDate(t: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
private fun fmtDateTime(t: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

/** Converts a typed distance in the user's units to km. */
private fun toKm(v: Double, units: UnitSystem) = if (units == UnitSystem.IMPERIAL) v / 0.621371 else v
private fun fromKm(v: Double, units: UnitSystem) = if (units == UnitSystem.IMPERIAL) v * 0.621371 else v
private fun toLiters(v: Double, units: UnitSystem) = if (units == UnitSystem.IMPERIAL) v / 0.264172 else v
private fun fromLiters(v: Double, units: UnitSystem) = if (units == UnitSystem.IMPERIAL) v * 0.264172 else v
private fun distUnit(units: UnitSystem) = if (units == UnitSystem.IMPERIAL) "mi" else "km"
private fun volUnit(units: UnitSystem) = if (units == UnitSystem.IMPERIAL) "gal" else "L"

// ---------------------------------------------------------------- Garage

@Composable
fun GarageScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Vehicle?>(null) }
    Scaffold(
        topBar = { ScreenTopBar(tr("Garage"), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = Vehicle(app.garage.newId(), "", createdAt = System.currentTimeMillis()) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text(tr("Add car")) })
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(tr("Each car keeps its own brand, custom PIDs, modules, scan history, maintenance, fuel log and trips. ") +
                    tr("When you connect, the car is picked automatically from its VIN."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(g.vehicles, key = { it.id }) { v ->
                val active = v.id == g.active?.id
                SectionCard(Modifier.clickable { scope.launch { app.garage.update { it.copy(activeVehicleId = v.id) } } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(v.vin, v.brandId?.let { Brands.byId[it]?.name } ?: v.vin?.let { Brands.detect(it)?.name }).joinToString(" · ").ifEmpty { tr("No VIN yet") },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tr("Odometer ") + fmtKm(v.odometerKm, settings.units) + (if (v.odometerFromCar) tr(" (from car)") else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (active) Pill(tr("Selected"), Good)
                        TextButton(onClick = { editing = v }) { Text(tr("Edit")) }
                    }
                    v.specs?.let { s ->
                        Text(listOfNotNull(s.year?.toString(), s.title.ifBlank { null }, s.engineLabel().ifBlank { null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onNavigate(Routes.findCar(v.id)) }) {
                        Text(if (v.specs == null) tr("Identify your car (model & specs)") else tr("Model & specifications"))
                    }
                }
            }
        }
    }
    editing?.let { v ->
        val isNew = g.vehicles.none { it.id == v.id }
        VehicleEditor(v, isNew, settings.units, onDismiss = { editing = null }, onSave = { saved ->
            editing = null
            scope.launch {
                app.garage.update { d ->
                    if (isNew) d.copy(vehicles = d.vehicles + saved, activeVehicleId = saved.id)
                    else d.copy(vehicles = d.vehicles.map { if (it.id == saved.id) saved else it })
                }
            }
        }, onDelete = if (!isNew && g.vehicles.size > 1) ({ editing = null; scope.launch { app.garage.deleteVehicle(v.id) } }) else null)
    }
}

@Composable
private fun VehicleEditor(v: Vehicle, isNew: Boolean, units: UnitSystem, onDismiss: () -> Unit, onSave: (Vehicle) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(v.name) }
    var vin by remember { mutableStateOf(v.vin.orEmpty()) }
    var odo by remember { mutableStateOf(v.odometerKm?.let { "%.0f".format(fromKm(it, units)) }.orEmpty()) }
    var brandId by remember { mutableStateOf(v.brandId) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr("Add car") else tr("Edit car")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vin, { vin = it.uppercase().filter(Char::isLetterOrDigit).take(17) }, label = { Text(tr("VIN (optional, read automatically)")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(odo, { odo = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(tr("Odometer (%1\$s)", (distUnit(units)))) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Brand: ") + (brandId?.let { Brands.byId[it]?.name } ?: vin.takeIf { it.length >= 3 }?.let { Brands.detect(it)?.name?.let { n -> tr("%1\$s (from VIN)", n) } } ?: "auto"),
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { picking = true }) { Text(tr("Choose")) }
                    if (brandId != null) TextButton(onClick = { brandId = null }) { Text(tr("Auto")) }
                }
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text(tr("Delete car and its data"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val km = odo.toDoubleOrNull()?.let { toKm(it, units) }
                onSave(v.copy(name = name.trim().ifEmpty { tr("My car") }, vin = vin.takeIf { it.length >= 11 }, brandId = brandId,
                    odometerKm = km ?: v.odometerKm, odometerFromCar = if (km != null && km != v.odometerKm) false else v.odometerFromCar,
                    odometerUpdatedAt = if (km != null) System.currentTimeMillis() else v.odometerUpdatedAt))
            }) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    if (picking) BrandPickerDialog(onDismiss = { picking = false }) { brandId = it.id; picking = false }
    if (confirmDelete && onDelete != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Delete %1\$s?", v.name)) },
        text = { Text(tr("Its maintenance items, fuel log and trips are deleted too. This can't be undone.")) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
    )
}

// ---------------------------------------------------------------- Maintenance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val v = g.active
    var editing by remember { mutableStateOf<MaintenanceItem?>(null) }
    var presets by remember { mutableStateOf(false) }
    var odoDialog by remember { mutableStateOf(false) }
    var inspectionPassed by remember { mutableStateOf<MaintenanceItem?>(null) }
    Scaffold(
        topBar = { ScreenTopBar(tr("Maintenance"), onBack) },
        floatingActionButton = {
            if (v != null) ExtendedFloatingActionButton(onClick = { presets = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(tr("Add item")) })
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        if (v == null) { CenteredMessage(null, tr("Add a car in the garage first."), Modifier.padding(pad)); return@Scaffold }
        val now = System.currentTimeMillis()
        val items = g.maintenance.filter { it.vehicleId == v.id }.map { it to Maintenance.status(it, v.odometerKm, now) }
            .sortedWith(compareByDescending<Pair<MaintenanceItem, com.openobd.core.garage.MaintenanceStatus>> { it.second.overdue }.thenByDescending { it.second.fraction ?: 0f })
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Text(v.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Odometer ") + fmtKm(v.odometerKm, settings.units) + if (v.odometerFromCar) tr(" (read from car)") else "", Modifier.weight(1f))
                        TextButton(onClick = { odoDialog = true }) { Text(tr("Update")) }
                    }
                    if (v.odometerKm == null) Text(tr("Enter the odometer so distance-based reminders work. Cars that report it over OBD update it automatically."),
                        style = MaterialTheme.typography.bodySmall, color = Warn)
                }
            }
            if (items.isEmpty()) item {
                Text(tr("No items yet. Add oil changes, filters, brake fluid, inspections … and OpenOBD will tell you when they're due."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(items, key = { it.first.id }) { (item, st) ->
                SectionCard(Modifier.clickable { editing = item }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        when {
                            st.overdue -> Pill(tr("Overdue"), Bad)
                            st.dueSoon -> Pill(tr("Due soon"), Warn)
                            st.known -> Pill("OK", Good)
                        }
                    }
                    st.fraction?.let {
                        LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            color = if (st.overdue) Bad else if (st.dueSoon) Warn else MaterialTheme.colorScheme.primary)
                    }
                    val parts = listOfNotNull(
                        st.remainingKm?.let { if (it >= 0) "in ${fmtKm(it, settings.units)}" else "${fmtKm(-it, settings.units)} overdue" },
                        st.remainingDays?.let { if (it >= 0) tr("in %1\$s days", it) else tr("%1\$s days overdue", (-it)) },
                    )
                    Text(if (parts.isEmpty()) tr("Mark it done once to start tracking") else tr("Due ") + parts.joinToString(tr(" or ")),
                        style = MaterialTheme.typography.bodySmall)
                    Text(tr("Every ") + listOfNotNull(item.intervalKm?.let { fmtKm(it.toDouble(), settings.units) }, item.intervalMonths?.let { "$it months" }).joinToString(" / ") +
                        (item.lastDate?.let { tr(" · last done %1\$s", (fmtDate(it))) } ?: "") + (item.lastKm?.let { " at ${fmtKm(it, settings.units)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val inspection = Maintenance.isInspection(item, tr(Maintenance.INSPECTION))
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                app.garage.upsertMaintenance(
                                    if (inspection) Maintenance.inspectionPassed(item, v.specs, System.currentTimeMillis(), v.odometerKm)
                                    else item.copy(lastDate = System.currentTimeMillis(), lastKm = v.odometerKm ?: item.lastKm, dueDate = null),
                                )
                            }
                        }) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(6.dp)); Text(tr("Mark done today")) }
                        // The next inspection counts from the day it was passed, which is often not today.
                        if (inspection) TextButton(onClick = { inspectionPassed = item }) { Text(tr("Passed on…")) }
                    }
                }
            }
        }
    }
    inspectionPassed?.let { item ->
        val state = rememberDatePickerState(initialSelectedDateMillis = item.lastDate ?: System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = { inspectionPassed = null },
            confirmButton = {
                TextButton(onClick = {
                    val day = state.selectedDateMillis
                    if (day != null && v != null) scope.launch { app.garage.upsertMaintenance(Maintenance.inspectionPassed(item, v.specs, day, null)) }
                    inspectionPassed = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { inspectionPassed = null }) { Text(tr("Cancel")) } }) {
            DatePicker(state, title = { Text(tr("Day you passed the inspection"), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) })
        }
    }
    if (presets && v != null) AlertDialog(
        onDismissRequest = { presets = false },
        title = { Text(tr("Add maintenance item")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Maintenance.presets.forEach { (name, km, months) ->
                    Text(name + "  ·  " + listOfNotNull(km?.let { fmtKm(it.toDouble(), settings.units) }, months?.let { "$it mo" }).joinToString(" / "),
                        Modifier.fillMaxWidth().clickable {
                            presets = false
                            editing = MaintenanceItem(app.garage.newId(), v.id, name, km, months)
                        }.padding(vertical = 10.dp))
                }
                TextButton(onClick = { presets = false; editing = MaintenanceItem(app.garage.newId(), v.id, "", 10000, 12) }) { Text(tr("Custom item…")) }
            }
        },
        confirmButton = { TextButton(onClick = { presets = false }) { Text(tr("Cancel")) } },
    )
    editing?.let { item ->
        MaintenanceEditor(item, isNew = g.maintenance.none { it.id == item.id }, units = settings.units, currentKm = v?.odometerKm,
            onDismiss = { editing = null },
            onSave = { editing = null; scope.launch { app.garage.upsertMaintenance(it) } },
            onDelete = { editing = null; scope.launch { app.garage.deleteMaintenance(item.id) } })
    }
    if (odoDialog && v != null) OdometerDialog(v, settings.units, onDismiss = { odoDialog = false }) { km ->
        odoDialog = false
        scope.launch { app.garage.updateVehicle(v.id) { it.copy(odometerKm = km, odometerUpdatedAt = System.currentTimeMillis(), odometerFromCar = false) } }
    }
}

@Composable
private fun OdometerDialog(v: Vehicle, units: UnitSystem, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf(v.odometerKm?.let { "%.0f".format(fromKm(it, units)) }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Odometer")) },
        text = {
            OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(distUnit(units)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { text.toDoubleOrNull()?.let { onSave(toKm(it, units)) } }, enabled = text.toDoubleOrNull() != null) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaintenanceEditor(item: MaintenanceItem, isNew: Boolean, units: UnitSystem, currentKm: Double?, onDismiss: () -> Unit, onSave: (MaintenanceItem) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var km by remember { mutableStateOf(item.intervalKm?.let { "%.0f".format(fromKm(it.toDouble(), units)) }.orEmpty()) }
    var months by remember { mutableStateOf(item.intervalMonths?.toString().orEmpty()) }
    var lastKm by remember { mutableStateOf((item.lastKm ?: if (isNew) currentKm else null)?.let { "%.0f".format(fromKm(it, units)) }.orEmpty()) }
    var lastDate by remember { mutableStateOf(item.lastDate ?: if (isNew) System.currentTimeMillis() else null) }
    var notes by remember { mutableStateOf(item.notes) }
    var pickDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) tr("New item") else tr("Edit item")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text(tr("Every %1\$s", (distUnit(units)))) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text(tr("Every months")) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(lastKm, { lastKm = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(tr("Last done at (%1\$s)", (distUnit(units)))) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Last done: ") + (lastDate?.let { fmtDate(it) } ?: "never"), Modifier.weight(1f))
                    TextButton(onClick = { pickDate = true }) { Text(tr("Change")) }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text(tr("Notes (oil grade, part numbers …)")) }, modifier = Modifier.fillMaxWidth())
                if (!isNew) TextButton(onClick = onDelete) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(item.copy(name = name.trim(), intervalKm = km.toIntOrNull()?.let { toKm(it.toDouble(), units).toInt() }, intervalMonths = months.toIntOrNull(),
                    lastKm = lastKm.toDoubleOrNull()?.let { toKm(it, units) }, lastDate = lastDate, notes = notes))
            }, enabled = name.isNotBlank() && (km.toIntOrNull() != null || months.toIntOrNull() != null)) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = lastDate ?: System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { lastDate = state.selectedDateMillis ?: lastDate; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(tr("Cancel")) } }) { DatePicker(state) }
    }
}

// ---------------------------------------------------------------- Fuel log

@Composable
fun FuelLogScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val v = g.active
    var editing by remember { mutableStateOf<FuelEntry?>(null) }
    Scaffold(
        topBar = { ScreenTopBar(tr("Fuel log"), onBack) },
        floatingActionButton = {
            if (v != null) ExtendedFloatingActionButton(onClick = {
                editing = FuelEntry(app.garage.newId(), v.id, System.currentTimeMillis(), v.odometerKm ?: 0.0, 0.0, 0.0)
            }, icon = { Icon(Icons.Default.LocalGasStation, null) }, text = { Text(tr("Add fill-up")) })
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        if (v == null) { CenteredMessage(null, tr("Add a car in the garage first."), Modifier.padding(pad)); return@Scaffold }
        val entries = g.fuel.filter { it.vehicleId == v.id }.sortedByDescending { it.odometerKm }
        val sum = remember(entries) { FuelStats.compute(entries) }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard(title = v.name) {
                    KeyValueRow(tr("Average consumption"), fmtL100(sum.averageL100, settings.units))
                    KeyValueRow(tr("Last full tank"), fmtL100(sum.lastL100, settings.units))
                    v.specs?.consumptionL100?.let { official ->
                        KeyValueRow(tr("Official consumption"), fmtL100(official, settings.units) + if (v.specs.consumptionEstimated) "*" else "")
                        sum.averageL100?.let { real ->
                            val diff = (real - official) / official * 100
                            KeyValueRow(tr("You vs official"), "%+.0f %%".format(diff), valueColor = if (diff > 15) Warn else Good)
                        }
                    }
                    KeyValueRow(tr("Cost per %1\$s", (distUnit(settings.units))), sum.costPerKm?.let { fmtMoney(if (settings.units == UnitSystem.IMPERIAL) it / 0.621371 else it) } ?: "—")
                    KeyValueRow(tr("Total spent"), fmtMoney(sum.totalCost))
                    KeyValueRow(tr("Fuel bought"), fmtLiters(sum.totalLiters, settings.units))
                    KeyValueRow(tr("Distance tracked"), fmtKm(sum.distanceKm, settings.units))
                    Text(tr("Consumption uses the full-to-full method: fill up completely and tick \"Full tank\" for accurate numbers."),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entries.isEmpty()) item { Text(tr("No fill-ups yet."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(entries, key = { it.id }) { e ->
                SectionCard(Modifier.clickable { editing = e }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDate(e.date), fontWeight = FontWeight.SemiBold)
                            Text("${fmtLiters(e.liters, settings.units)} · ${fmtMoney(e.totalCost)} · ${fmtKm(e.odometerKm, settings.units)}" + if (!e.fullTank) tr(" · partial") else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        sum.perEntry[e.id]?.let { Text(fmtL100(it, settings.units), fontFamily = com.openobd.ui.theme.Inter, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
    // At the pump: the price of the station you are at (or the usual one around), instead of the one in Settings.
    var herePrice by remember { mutableStateOf<Pair<Double, String>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pumpFuel = currentPumpFuel()
    LaunchedEffect(editing?.id) {
        if (editing == null || !settings.fuelPrices) return@LaunchedEffect
        val here = com.openobd.data.CurrentLocation.get(context, maxAgeMs = 5 * 60_000L) ?: return@LaunchedEffect
        val list = app.fuelPrices.around(here.latitude, here.longitude, 5.0) ?: return@LaunchedEffect
        val near = com.openobd.core.fuel.FuelPricesEs.near(list.stations, here.latitude, here.longitude, pumpFuel, 5.0)
        herePrice = near.firstOrNull()?.takeIf { it.km < 0.3 }?.let { s -> s.price(pumpFuel)!! to s.station.brand }
            ?: com.openobd.core.fuel.FuelPricesEs.localPrice(list.stations, here.latitude, here.longitude, pumpFuel)?.let { it to "" }
    }
    editing?.let { e ->
        FuelEditor(e, isNew = g.fuel.none { it.id == e.id }, units = settings.units, defaultPrice = herePrice?.first ?: settings.fuelPricePerL.toDouble(),
            priceSource = herePrice?.let { (p, brand) -> if (brand.isNotEmpty()) tr("%1\$s/L at %2\$s, where you are", euros(p), brand) else tr("%1\$s/L, the usual price around you", euros(p)) },
            onDismiss = { editing = null },
            onSave = { editing = null; scope.launch { app.garage.upsertFuel(it) } },
            onDelete = { editing = null; scope.launch { app.garage.deleteFuel(e.id) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuelEditor(e: FuelEntry, isNew: Boolean, units: UnitSystem, defaultPrice: Double, onDismiss: () -> Unit, onSave: (FuelEntry) -> Unit, onDelete: () -> Unit,
                       priceSource: String? = null) {
    var odo by remember { mutableStateOf(if (e.odometerKm > 0) "%.0f".format(fromKm(e.odometerKm, units)) else "") }
    var vol by remember { mutableStateOf(if (e.liters > 0) "%.2f".format(fromLiters(e.liters, units)) else "") }
    var total by remember { mutableStateOf(if (e.totalCost > 0) "%.2f".format(e.totalCost) else "") }
    var full by remember { mutableStateOf(e.fullTank) }
    var date by remember { mutableStateOf(e.date) }
    var pickDate by remember { mutableStateOf(false) }
    val litres = vol.replace(',', '.').toDoubleOrNull()?.let { toLiters(it, units) }
    val cost = total.replace(',', '.').toDoubleOrNull() ?: litres?.let { if (defaultPrice > 0) it * defaultPrice else null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Fill-up" else tr("Edit fill-up")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtDate(date), Modifier.weight(1f))
                    TextButton(onClick = { pickDate = true }) { Text(tr("Change date")) }
                }
                OutlinedTextField(odo, { odo = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(tr("Odometer (%1\$s)", (distUnit(units)))) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(vol, { vol = it }, label = { Text(tr("Fuel added (%1\$s)", (volUnit(units)))) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(total, { total = it }, label = { Text(tr("Total paid") + if (defaultPrice > 0 && priceSource == null) tr(" (blank = settings price)") else "") }, singleLine = true,
                    placeholder = { cost?.let { Text("%.2f".format(it)) } },
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (priceSource != null) Text(priceSource, style = MaterialTheme.typography.bodySmall, color = com.openobd.ui.theme.Good, modifier = Modifier.testTag("pumpPrice"))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { full = !full }) {
                    Checkbox(full, { full = it }); Text(tr("Full tank"))
                }
                if (!isNew) TextButton(onClick = onDelete) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(e.copy(date = date, odometerKm = toKm(odo.toDouble(), units), liters = litres!!, totalCost = cost ?: 0.0, fullTank = full))
            }, enabled = odo.toDoubleOrNull() != null && (litres ?: 0.0) > 0) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date)
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { date = state.selectedDateMillis ?: date; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(tr("Cancel")) } }) { DatePicker(state) }
    }
}

// ---------------------------------------------------------------- Trips

@Composable
fun TripsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = LocalApp.current
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val v = g.active
    val trips = g.trips.filter { it.vehicleId == v?.id }
    var poster by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Trip history"), onBack, actions = {
            if (trips.any { it.trackPoints > 1 }) IconButton(onClick = { poster = true }, modifier = Modifier.testTag("allTripsPoster")) { Icon(Icons.Default.Map, tr("Poster of all trips")) }
        })
        if (trips.isEmpty()) {
            CenteredMessage(Icons.Default.Route, tr("Trips are saved automatically when you disconnect after driving. ") +
                tr("Turn on GPS tracks in Settings to see them on a map."))
            return@Column
        }
        val totalKm = trips.sumOf { it.distanceKm }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(tr("%1\$s trips · %2\$s", trips.size, (fmtKm(totalKm, settings.units))), style = MaterialTheme.typography.titleMedium)
            }
            if (trips.count { it.trackPoints > 1 } >= 2) item {
                com.openobd.ui.components.NoticeCard(Icons.Default.Map, tr("Poster of all trips"),
                    tr("Every road you drove in a period on one A3 poster: the ones you drive most stand out."), com.openobd.ui.theme.Violet) { poster = true }
            }
            items(trips, key = { it.id }) { t ->
                SectionCard(Modifier.clickable { onOpen(t.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDateTime(t.start), fontWeight = FontWeight.SemiBold)
                            Text("${fmtKm(t.distanceKm, settings.units, 1)} · ${formatDuration((t.end - t.start) / 1000.0)} · ${fmtL100(t.avgL100, settings.units)}" +
                                (t.ecoScore?.let { " · " + tr("Eco %1\$s", it) } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (t.trackPoints > 1) Icon(Icons.Default.Map, tr("Has map"), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (poster) AllTripsPosterDialog(trips, v?.let { listOfNotNull(it.name, it.specs?.title?.takeIf { t -> t.isNotBlank() && t != it.name }).joinToString(" · ") }.orEmpty()) { poster = false }
}

/** What the car was doing at one point of a trip's route. */
@Composable
private fun TrackPointCard(p: TrackPoint, tripStart: Long, units: UnitSystem) {
    SectionCard(title = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(p.t)) +
        "  ·  " + tr("%1\$s into the trip", formatDuration(((p.t - tripStart) / 1000.0).coerceAtLeast(0.0)))) {
        p.speedKmh?.let { KeyValueRow(tr("Speed"), ObdUnit.KMH.display(it.toDouble(), units).let { d -> "%.0f %s".format(d.value, d.unit.symbol) }) }
        p.rpm?.let { KeyValueRow(tr("Engine RPM"), "%.0f".format(it)) }
        p.l100?.let { KeyValueRow(tr("Instant fuel consumption"), fmtL100(it.toDouble(), units)) }
        p.pedal?.let { KeyValueRow(tr("Accelerator"), "%.0f %%".format(it)) }
        p.boostBar?.let { KeyValueRow(tr("Boost"), ObdUnit.BAR.display(it.toDouble(), units).let { d -> "%.2f %s".format(d.value, d.unit.symbol) }) }
        p.coolantC?.let { KeyValueRow(tr("Coolant"), ObdUnit.CELSIUS.display(it.toDouble(), units).let { d -> "%.0f %s".format(d.value, d.unit.symbol) }) }
        if (p.rpm == null && p.l100 == null) Text(tr("Trips recorded with older versions only stored the speed."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TripDetailScreen(tripId: String, onBack: () -> Unit, onModel3d: () -> Unit = {}) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val t = g.trips.firstOrNull { it.id == tripId }
    var track by remember { mutableStateOf<List<TrackPoint>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var point by remember { mutableStateOf<TrackPoint?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(false) }
    LaunchedEffect(tripId) { track = app.garage.loadTrack(tripId) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(t?.name?.takeIf { it.isNotBlank() } ?: tr("Trip"), onBack, actions = {
            if (t != null && track.orEmpty().size >= 2) IconButton(onClick = { exporting = true }) { Icon(Icons.Default.Share, tr("Export route")) }
            if (t != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, tr("Delete trip")) }
        })
        if (t == null) { CenteredMessage(null, tr("Trip not found.")); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val pts = track.orEmpty()
            var name by remember(t.id) { mutableStateOf(t.name) }
            val saveName = { scope.launch { app.garage.renameTrip(t.id, name) }; Unit }
            OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(tr("Name of the trip")) },
                placeholder = { Text(tr("For example: Sunday drive to the mountains")) },
                trailingIcon = { if (name.trim() != t.name) IconButton(onClick = saveName) { Icon(Icons.Default.Check, tr("Save")) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { saveName() }),
                modifier = Modifier.fillMaxWidth().testTag("tripName"))
            if (pts.size >= 2) {
                SectionCard { TrackMap(pts, Modifier.fillMaxWidth().height(300.dp), selected = point) { point = it } }
                Text(tr("Map data © OpenStreetMap contributors") + " · " + tr("Tap the route to see what the car was doing there."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                point?.let { TrackPointCard(it, t.start, settings.units) }
            }
            SectionCard(title = fmtDateTime(t.start)) {
                KeyValueRow(tr("Distance"), fmtKm(t.distanceKm, settings.units, 2))
                KeyValueRow(tr("Duration"), formatDuration((t.end - t.start) / 1000.0))
                KeyValueRow(tr("Moving / idle"), formatDuration(t.movingS) + " / " + formatDuration(t.idleS))
                KeyValueRow(tr("Average speed (moving)"), ObdUnit.KMH.display(t.avgSpeed, settings.units).let { "%.0f %s".format(it.value, it.unit.symbol) })
                KeyValueRow(tr("Max speed"), ObdUnit.KMH.display(t.maxSpeed, settings.units).let { "%.0f %s".format(it.value, it.unit.symbol) })
                KeyValueRow(tr("Max RPM"), "%.0f".format(t.maxRpm))
                KeyValueRow(tr("Fuel used"), t.fuelL?.let { fmtLiters(it, settings.units) } ?: "—")
                KeyValueRow(tr("Average consumption"), fmtL100(t.avgL100, settings.units))
                t.cost?.let { KeyValueRow(tr("Fuel cost"), fmtMoney(it)) }
                t.fuelPrice?.let { KeyValueRow(tr("Fuel price"), euros(it) + "/L" + if (t.fuelPriceLocal) " · " + tr("where you drove") else "") }
            }
            if (pts.size >= 2) RouteStationsCard(pts)
            if (t.ecoScore != null) EcoScoreCard(t.ecoScore, t.eco, g.vehicles.firstOrNull { it.id == t.vehicleId }?.specs?.fuel?.burnsDiesel == true)
            val speeds = pts.mapNotNull { p -> p.speedKmh?.let { p.t to ObdUnit.KMH.display(it.toDouble(), settings.units).value.toFloat() } }
            if (speeds.size >= 2) SectionCard(title = tr("Speed")) {
                LineChart(listOf(ChartSeries(speeds, Cyan, min = 0f)), (speeds.last().first - speeds.first().first).coerceAtLeast(1), speeds.last().first,
                    Modifier.fillMaxWidth().height(160.dp))
            }
        }
    }
    if (exporting && t != null) {
        val v = g.vehicles.firstOrNull { it.id == t.vehicleId }
        val car = listOfNotNull(v?.name, v?.specs?.title?.takeIf { it.isNotBlank() && it != v.name }).joinToString(" · ")
        RouteExportDialog(t, track.orEmpty(), car, onDismiss = { exporting = false }, onModel3d = { exporting = false; onModel3d() },
            onVideo = { exporting = false; video = true })
    }
    if (video && t != null) {
        val v = g.vehicles.firstOrNull { it.id == t.vehicleId }
        TripVideoDialog(t, track.orEmpty(), v?.name.orEmpty()) { video = false }
    }
    if (confirmDelete && t != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Delete this trip?")) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { app.garage.deleteTrip(t.id); onBack() } }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
    )
}

/** The cheapest stations along a trip's route (for the routes you drive often), from the live prices. */
@Composable
private fun RouteStationsCard(points: List<TrackPoint>) {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = appSettings()
    if (!settings.fuelPrices) return
    val fuel = currentPumpFuel()
    var found by remember(points) { mutableStateOf<List<com.openobd.core.fuel.StationNear>?>(null) }
    LaunchedEffect(points, fuel) {
        val route = points.map { it.lat to it.lon }
        if (!com.openobd.core.fuel.FuelPricesEs.covers(route.first().first, route.first().second)) return@LaunchedEffect
        val list = app.fuelPrices.along(route) ?: return@LaunchedEffect
        found = com.openobd.core.fuel.FuelPricesEs.alongRoute(list.stations, route, fuel).take(3)
    }
    val list = found?.takeIf { it.isNotEmpty() } ?: return
    SectionCard(title = tr("Cheapest %1\$s on this route", tr(fuel.label).lowercase()), modifier = Modifier.testTag("routeStations")) {
        list.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { com.openobd.data.FuelPriceRepository.directions(context, s.station) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.station.brand, style = MaterialTheme.typography.titleSmall)
                    Text(s.station.address + ", " + s.station.town, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(euros(s.price(fuel) ?: 0.0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = com.openobd.ui.theme.Good)
            }
        }
    }
}
