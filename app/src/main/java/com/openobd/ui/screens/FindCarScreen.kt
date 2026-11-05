package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.brand.Brands
import com.openobd.core.garage.GarageData
import com.openobd.core.garage.Maintenance
import com.openobd.core.garage.MaintenanceItem
import com.openobd.core.garage.Vehicle
import com.openobd.core.obd.VinDecoder
import com.openobd.core.specs.CatalogEntry
import com.openobd.core.specs.CrossCheck
import com.openobd.core.specs.CrossLevel
import com.openobd.core.specs.DgtLabel
import com.openobd.core.specs.DvlaClient
import com.openobd.core.specs.EeaCatalog
import com.openobd.core.specs.EeaVersion
import com.openobd.core.specs.EpaCatalog
import com.openobd.core.specs.EpaOption
import com.openobd.core.specs.NhtsaVin
import com.openobd.core.specs.RdwClient
import com.openobd.core.specs.SpecCountries
import com.openobd.core.specs.SpecFuel
import com.openobd.core.specs.SpecSource
import com.openobd.core.specs.Spain
import com.openobd.core.specs.VehicleSpecs
import com.openobd.core.specs.prettyName
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.Pill
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.tr
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.session.Op
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun countryName(code: String) = Locale("", code).displayCountry.ifBlank { code }
private fun fmtDay(t: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
/** Years before the catalog's first one, back to the start of OBD-II (1996). */
private fun olderYears(catalogYears: List<Int>): List<Int> = catalogYears.minOrNull()?.let { ((it - 1) downTo 1996).toList() }.orEmpty()
private fun yearOf(t: Long) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = t }.get(Calendar.YEAR)

/** Look up the car's model and specifications by licence plate, VIN or catalog, then review and save them. */
@Composable
fun FindCarScreen(vehicleId: String, onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val v = g.vehicles.firstOrNull { it.id == vehicleId } ?: g.active
    var draft by remember(v?.id) { mutableStateOf<VehicleSpecs?>(null) }
    var searching by remember(v?.id) { mutableStateOf(v?.specs == null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Model & specifications"), onBack)
        if (v == null) { CenteredMessage(null, tr("Add a car in the garage first.")); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val current = draft
            when {
                current != null -> SpecsEditor(current, onCancel = { draft = null }, onSave = { specs ->
                    scope.launch { saveSpecs(app.garage, v, specs) }
                    draft = null; searching = false
                })
                !searching && v.specs != null -> {
                    SpecsSummary(v.specs, settings.units)
                    CrossCheckCard(v, v.specs, isActive = v.id == g.activeVehicleId)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { draft = v.specs }, modifier = Modifier.weight(1f)) { Text(tr("Edit")) }
                        OutlinedButton(onClick = { searching = true }, modifier = Modifier.weight(1f)) { Text(tr("Search again")) }
                    }
                }
                else -> SearchPanel(v) { draft = it }
            }
        }
    }
}

private suspend fun saveSpecs(garage: com.openobd.data.GarageRepository, v: Vehicle, specs: VehicleSpecs) {
    val autoNamed = v.name == GarageData.DEFAULT_NAME || Regex("^(\\d{4} )?[\\p{L}-]+$").matches(v.name) || Regex("^Car \\d+$").matches(v.name)
    val newName = listOfNotNull(specs.year?.toString(), specs.title.ifBlank { null }).joinToString(" ")
    val now = System.currentTimeMillis()
    garage.updateVehicle(v.id) { it.copy(specs = specs, name = if (autoNamed && newName.isNotBlank()) newName else it.name) }
    // Inspection reminder from the registry date, or from the Spanish ITV calendar
    val due = specs.inspectionDue ?: if (specs.country == "ES" && specs.firstRegistration != null) Spain.nextItv(specs.firstRegistration, now) else null
    if (due != null) {
        val id = "inspection_${v.id}"
        val existing = garage.data.value.maintenance.firstOrNull { it.id == id }
        val age = specs.firstRegistration?.let { (now - it) / (365.25 * 86_400_000) } ?: 0.0
        val months = if (specs.country == "ES" && age < 10) 24 else 12
        garage.upsertMaintenance((existing ?: MaintenanceItem(id, v.id, tr(Maintenance.presets.last().first))).copy(dueDate = due, intervalMonths = months))
    }
}

// ---------------------------------------------------------------- second source

/** Versions of the saved model in the EU catalog; the model name is matched loosely ("Astra Sports Tourer" -> "ASTRA"). */
private suspend fun eeaVersionsFor(eea: EeaCatalog, s: VehicleSpecs): List<EeaVersion> {
    val year = s.year ?: return emptyList()
    val country = s.country.takeIf { it in SpecCountries.eea }
    val make = s.make.uppercase()
    val direct = eea.versions(country, year, make, s.model.uppercase())
    if (direct.isNotEmpty()) return direct
    val wanted = s.model.uppercase()
    val model = eea.models(country, year, make).filter { wanted.contains(it.name) || it.name.contains(wanted) }.maxByOrNull { it.name.length } ?: return emptyList()
    return eea.versions(country, year, make, model.name)
}

/** Compares the saved model with the VIN, what the engine computer reports and the EU registration catalog. */
@Composable
private fun CrossCheckCard(v: Vehicle, specs: VehicleSpecs, isActive: Boolean) {
    val app = LocalApp.current
    val session = app.session
    val state by session.state.collectAsStateWithLifecycle()
    val observedOp by session.specsCheck.observed.collectAsStateWithLifecycle()
    val connected = isActive && session.vehicle != null
    LaunchedEffect(connected, state) { if (connected && session.specsCheck.observed.value is Op.Idle) session.specsCheck.read() }
    val eea = remember { EeaCatalog(app.http) }
    var catalog by remember(specs) { mutableStateOf<List<EeaVersion>?>(null) }
    var catalogLoading by remember(specs) { mutableStateOf(false) }
    val useCatalog = !specs.source.startsWith("EEA") && specs.year != null && specs.make.isNotBlank() && specs.model.isNotBlank() &&
        (specs.country.isBlank() || specs.country in SpecCountries.eea || specs.country == "GB")
    LaunchedEffect(specs, useCatalog) {
        if (!useCatalog) return@LaunchedEffect
        catalogLoading = true
        catalog = runCatching { eeaVersionsFor(eea, specs) }.getOrNull()
        catalogLoading = false
    }
    val observed = (observedOp as? Op.Done)?.value?.takeIf { connected }
    val findings = remember(specs, v.vin, observed, catalog) { CrossCheck.check(specs, v.vin, observed, catalog) }
    SectionCard(title = tr("Second source check")) {
        Text(tr("The saved data is compared with the VIN, what the engine computer reports and the EU registration catalog."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (observedOp is Op.Loading || catalogLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        findings.forEach { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    when (f.level) { CrossLevel.MATCH -> Icons.Default.CheckCircle; CrossLevel.INFO -> Icons.Default.Info; CrossLevel.MISMATCH -> Icons.Default.Warning },
                    null, tint = when (f.level) { CrossLevel.MATCH -> Good; CrossLevel.INFO -> Warn; CrossLevel.MISMATCH -> Bad }, modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(tr(f.text, *f.args.map { tr(it) }.toTypedArray()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        if (connected && observedOp !is Op.Loading) TextButton(onClick = { session.specsCheck.observed.value = Op.Idle; session.specsCheck.read() }) { Text(tr("Read from the car again")) }
    }
}

// ---------------------------------------------------------------- search

@Composable
private fun SearchPanel(v: Vehicle, onFound: (VehicleSpecs) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val country = settings.specsCountry.ifBlank { Locale.getDefault().country.takeIf { it in SpecCountries.all } ?: "ES" }
    var picking by remember { mutableStateOf(false) }
    val sources = SpecCountries.sources(country)

    SectionCard {
        Text(tr("Country of registration"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(countryName(country), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { picking = true }) { Text(tr("Change")) }
        }
        Text(
            when {
                SpecSource.RDW_PLATE in sources -> tr("Your licence plate is enough: the Dutch registry (RDW) is public.")
                SpecSource.DVLA_PLATE in sources -> tr("UK plates need a free DVLA API key. You can also pick the model from the catalog.")
                country == "ES" -> tr("Spain's DGT has no public licence-plate service, so pick your model from the official catalog of every car registered in Spain. It's quick: we start from your VIN.")
                SpecSource.EEA_CATALOG in sources && country in SpecCountries.eea -> tr("Pick your model from the official catalog of every car registered in %1\$s.", countryName(country))
                else -> tr("Decode your VIN or pick your model from the US or European catalogs.")
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (picking) CountryPicker(country, onDismiss = { picking = false }) { c ->
        picking = false
        scope.launch { app.settings.update { it.copy(specsCountry = c) } }
    }

    if (SpecSource.RDW_PLATE in sources) PlateSection(country, v, onFound)
    if (SpecSource.DVLA_PLATE in sources) PlateSection(country, v, onFound)
    if (SpecSource.NHTSA_VIN in sources) VinSection(v, onFound)
    if (SpecSource.EEA_CATALOG in sources) EeaSection(if (country in SpecCountries.eea || country == "GB") country else null, v, onFound)
    if (SpecSource.EPA_CATALOG in sources) EpaSection(v, onFound)
    SectionCard(title = tr("Not listed?")) {
        Text(tr("Enter the details yourself; you can copy them from the registration certificate or the owner's manual."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            val vin = v.vin?.let { VinDecoder.decode(it) }
            onFound(VehicleSpecs(make = vin?.manufacturer?.substringBefore(" (").orEmpty(), year = vin?.modelYear, country = country, source = "Manual"))
        }) { Text(tr("Enter manually")) }
    }
}

@Composable
private fun CountryPicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = SpecCountries.all.sortedBy { countryName(it) }.filter { query.isBlank() || countryName(it).contains(query, true) || it.equals(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Country of registration")) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it }) { c ->
                        Text(countryName(c), fontWeight = if (c == current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp))
                    }
                }
                Text(tr("Other countries: choose any country to use the European or US catalogs, or enter the details manually."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun PlateSection(country: String, v: Vehicle, onFound: (VehicleSpecs) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    var plate by remember { mutableStateOf(v.specs?.plate.orEmpty()) }
    var key by remember { mutableStateOf(settings.dvlaApiKey) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard(title = tr("Licence plate")) {
        OutlinedTextField(plate, { plate = it.uppercase() }, label = { Text(tr("Licence plate")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (country == "GB") {
            OutlinedTextField(key, { key = it.trim() }, label = { Text(tr("DVLA API key")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text(tr("Request a free key on the DVLA developer portal (Vehicle Enquiry Service). The DVLA doesn't provide the model name."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val now = System.currentTimeMillis()
                    val specs = if (country == "NL") RdwClient(app.http).lookup(plate, now) else {
                        app.settings.update { it.copy(dvlaApiKey = key) }
                        DvlaClient(app.http, key).lookup(plate, now)
                    }
                    onFound(specs)
                } catch (e: Exception) { error = e.message }
                busy = false
            }
        }, enabled = !busy && plate.length >= 4, modifier = Modifier.fillMaxWidth()) { Text(tr("Look up")) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun VinSection(v: Vehicle, onFound: (VehicleSpecs) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var vin by remember { mutableStateOf(v.vin.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard(title = tr("VIN")) {
        OutlinedTextField(vin, { vin = it.uppercase().filter(Char::isLetterOrDigit).take(17) }, label = { Text(tr("VIN (read automatically when connected)")) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(tr("Decoded by the US NHTSA database; works best for cars sold in North America."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try { onFound(NhtsaVin(app.http).decode(vin, System.currentTimeMillis())) } catch (e: Exception) { error = e.message }
                busy = false
            }
        }, enabled = !busy && vin.length == 17, modifier = Modifier.fillMaxWidth()) { Text(tr("Decode VIN")) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let { ErrorText(it) }
    }
}

/** Searchable list for catalog steps (make, model). Most registered first. */
@Composable
private fun PickList(label: String, entries: List<CatalogEntry>, onPick: (String) -> Unit) {
    var query by remember(entries) { mutableStateOf("") }
    var all by remember(entries) { mutableStateOf(false) }
    val filtered = entries.filter { query.isBlank() || it.name.contains(query, true) }
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(tr("Search")) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    (if (all || query.isNotBlank()) filtered else filtered.take(12)).forEach { e ->
        Row(Modifier.fillMaxWidth().clickable { onPick(e.name) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(prettyName(e.name), Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (e.count > 0) Text("%,d".format(e.count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
    if (!all && query.isBlank() && filtered.size > 12) TextButton(onClick = { all = true }) { Text(tr("Show all %1\$s", filtered.size)) }
}

@Composable
private fun StepHeader(label: String, value: String, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text("$label: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onChange) { Text(tr("Change")) }
    }
}

@Composable
private fun EeaSection(country: String?, v: Vehicle, onFound: (VehicleSpecs) -> Unit) {
    val app = LocalApp.current
    val eea = remember { EeaCatalog(app.http) }
    val vinInfo = v.vin?.let { VinDecoder.decode(it) }
    val vinBrand = Brands.detect(v.vin)?.name?.uppercase()
    var years by remember(country) { mutableStateOf<List<Int>?>(null) }
    var year by remember(country) { mutableStateOf<Int?>(null) }
    // The EEA started collecting in 2010. Older cars still get the make and model lists (from the first year
    // available; most names did not change) and then the engine is entered by hand.
    val firstYear = years?.minOrNull()
    val older = year != null && firstYear != null && year!! < firstYear
    val catalogYear = if (older) firstYear else year
    var makes by remember(country, catalogYear) { mutableStateOf<List<CatalogEntry>?>(null) }
    var make by remember(country, year) { mutableStateOf<String?>(null) }
    var models by remember(country, catalogYear, make) { mutableStateOf<List<CatalogEntry>?>(null) }
    var model by remember(country, year, make) { mutableStateOf<String?>(null) }
    var versions by remember(country, year, make, model) { mutableStateOf<List<EeaVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(country) {
        error = null
        runCatching { eea.years(country) }.onSuccess { ys ->
            years = ys
            val all = ys + olderYears(ys)
            year = (v.specs?.year ?: vinInfo?.modelYear)?.takeIf { it in all } ?: ys.firstOrNull()
        }.onFailure { error = it.message }
    }
    LaunchedEffect(country, catalogYear) {
        val y = catalogYear ?: return@LaunchedEffect
        runCatching { eea.makes(country, y) }.onSuccess { list ->
            makes = list
            if (make == null) make = list.firstOrNull { it.name == vinBrand || it.name == v.specs?.make?.uppercase() }?.name
        }.onFailure { error = it.message }
    }
    LaunchedEffect(country, catalogYear, make) {
        val y = catalogYear ?: return@LaunchedEffect; val mk = make ?: return@LaunchedEffect
        runCatching { eea.models(country, y, mk) }.onSuccess { models = it }.onFailure { error = it.message }
    }
    LaunchedEffect(country, year, make, model) {
        if (older) return@LaunchedEffect
        val y = year ?: return@LaunchedEffect; val mk = make ?: return@LaunchedEffect; val md = model ?: return@LaunchedEffect
        runCatching { eea.versions(country, y, mk, md) }.onSuccess { versions = it }.onFailure { error = it.message }
    }

    SectionCard(title = if (country != null) tr("Catalog of cars registered in %1\$s", countryName(country)) else tr("European catalog")) {
        error?.let { ErrorText(it) }
        val ys = years
        if (ys == null) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()); return@SectionCard }
        if (ys.isEmpty()) { Text(tr("No data for this country.")); return@SectionCard }
        Text(tr("Year of first registration"), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (ys + olderYears(ys)).forEach { y -> FilterChip(year == y, { year = y; make = null; model = null }, label = { Text(y.toString()) }) }
        }
        if (older) Text(tr("The European catalog starts in %1\$s. For older cars choose the make and model, then enter the engine from the registration certificate.", firstYear!!),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!older && ys.first() < Calendar.getInstance().get(Calendar.YEAR) - 1) {
            Text(tr("Newer cars: choose the latest year available; the model's specifications rarely change."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val mk = make
        when {
            makes == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            mk == null -> PickList(tr("Make"), makes!!) { make = it }
            else -> {
                StepHeader(tr("Make"), prettyName(mk)) { make = null; model = null }
                val md = model
                when {
                    models == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    md == null -> PickList(tr("Model"), models!!) { model = it }
                    older -> {
                        StepHeader(tr("Model"), prettyName(md)) { model = null }
                        Button(onClick = {
                            onFound(VehicleSpecs(make = mk, model = md, year = year, country = country.orEmpty(), source = "Manual", lookupDate = System.currentTimeMillis()))
                        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(tr("Continue")) }
                    }
                    else -> {
                        StepHeader(tr("Model"), prettyName(md)) { model = null }
                        val vs = versions
                        if (vs == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        else {
                            Text(tr("Choose your version (most common first)"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            val total = vs.sumOf { it.registrations }.coerceAtLeast(1)
                            vs.forEach { ver ->
                                val specs = eea.toSpecs(country, year!!, mk, md, ver, System.currentTimeMillis())
                                Column(Modifier.fillMaxWidth().clickable { onFound(specs) }.padding(vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tr(ver.fuel.label) + (specs.engineLabel().takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        Text("%.0f%%".format(ver.registrations * 100.0 / total), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(listOfNotNull(
                                        specs.consumptionL100?.let { tr("%1\$s L/100 km", "%.1f".format(it)) + if (specs.consumptionEstimated) "*" else "" },
                                        specs.electricKwh100?.let { tr("%1\$s kWh/100 km", "%.1f".format(it)) },
                                        specs.co2Gkm?.let { "%.0f g CO₂/km".format(it) },
                                        specs.massKg?.let { "$it kg" },
                                    ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                            }
                            Text(tr("* estimated from the official CO₂ figure"), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        Text(tr(EeaCatalog.ATTRIBUTION), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EpaSection(v: Vehicle, onFound: (VehicleSpecs) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val epa = remember { EpaCatalog(app.http) }
    val vinInfo = v.vin?.let { VinDecoder.decode(it) }
    var year by remember { mutableStateOf((vinInfo?.modelYear ?: Calendar.getInstance().get(Calendar.YEAR) - 3).coerceIn(1984, Calendar.getInstance().get(Calendar.YEAR))) }
    var makes by remember(year) { mutableStateOf<List<String>?>(null) }
    var make by remember(year) { mutableStateOf<String?>(null) }
    var models by remember(year, make) { mutableStateOf<List<String>?>(null) }
    var model by remember(year, make) { mutableStateOf<String?>(null) }
    var options by remember(year, make, model) { mutableStateOf<List<EpaOption>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(year) {
        runCatching { epa.makes(year) }.onSuccess { list -> makes = list; make = list.firstOrNull { it.equals(Brands.detect(v.vin)?.name, true) } }.onFailure { error = it.message }
    }
    LaunchedEffect(year, make) { make?.let { mk -> runCatching { epa.models(year, mk) }.onSuccess { models = it }.onFailure { error = it.message } } }
    LaunchedEffect(year, make, model) {
        val mk = make ?: return@LaunchedEffect; val md = model ?: return@LaunchedEffect
        runCatching { epa.options(year, mk, md) }.onSuccess { options = it }.onFailure { error = it.message }
    }
    SectionCard(title = tr("US catalog (EPA fuel economy)")) {
        error?.let { ErrorText(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Model year"), Modifier.weight(1f))
            TextButton(onClick = { year -= 1 }) { Text("−") }
            Text(year.toString(), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { if (year < Calendar.getInstance().get(Calendar.YEAR) + 1) year += 1 }) { Text("+") }
        }
        val mk = make
        when {
            makes == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            mk == null -> PickList(tr("Make"), makes!!.map { CatalogEntry(it, 0) }) { make = it }
            else -> {
                StepHeader(tr("Make"), mk) { make = null }
                val md = model
                when {
                    models == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    md == null -> PickList(tr("Model"), models!!.map { CatalogEntry(it, 0) }) { model = it }
                    else -> {
                        StepHeader(tr("Model"), md) { model = null }
                        options?.forEach { o ->
                            Text(o.label, Modifier.fillMaxWidth().clickable {
                                busy = true
                                scope.launch {
                                    try { onFound(epa.vehicle(o.id, System.currentTimeMillis()).copy(country = "US")) } catch (e: Exception) { error = e.message }
                                    busy = false
                                }
                            }.padding(vertical = 10.dp))
                            HorizontalDivider()
                        } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(tr("Data: U.S. EPA / DOE fueleconomy.gov"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------------------------------------------------------- summary & editor

private val dgtColors = mapOf(
    DgtLabel.CERO to Color(0xFF1565C0), DgtLabel.ECO to Color(0xFF2E7D32), DgtLabel.C to Color(0xFF43A047), DgtLabel.B to Color(0xFFF9A825),
    DgtLabel.NONE to Color(0xFF9E9E9E),
)

@Composable
fun SpecsSummary(s: VehicleSpecs, units: com.openobd.core.obd.UnitSystem) {
    SectionCard {
        Text(listOfNotNull(s.year?.toString(), s.title.ifBlank { null }).joinToString(" "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (s.version.isNotBlank()) Text(s.version, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        KeyValueRow(tr("Fuel"), tr(s.fuel.label))
        if (s.engineLabel().isNotEmpty()) KeyValueRow(tr("Engine"), s.engineLabel())
        s.consumptionL100?.let { KeyValueRow(tr("Official consumption"), fmtL100(it, units) + if (s.consumptionEstimated) " (" + tr("estimated from CO₂") + ")" else "") }
        s.cityL100?.let { c -> KeyValueRow(tr("City / highway"), fmtL100(c, units) + " / " + fmtL100(s.highwayL100, units)) }
        s.electricKwh100?.let { KeyValueRow(tr("Electric consumption"), "%.1f kWh/100 km".format(it)) }
        s.electricRangeKm?.let { KeyValueRow(tr("Electric range"), fmtKm(it.toDouble(), units)) }
        s.tankL?.let { KeyValueRow(tr("Fuel tank"), "%.0f L".format(it)) }
        s.co2Gkm?.let { KeyValueRow(tr("CO₂"), "%.0f g/km".format(it) + if (s.co2Cycle.isNotBlank()) " (${s.co2Cycle})" else "") }
        s.massKg?.let { KeyValueRow(tr("Weight"), "$it kg") }
        if (s.transmission.isNotBlank()) KeyValueRow(tr("Transmission"), s.transmission)
        if (s.euroNorm.isNotBlank()) KeyValueRow(tr("Emission standard"), s.euroNorm)
        if (s.plate.isNotBlank()) KeyValueRow(tr("Licence plate"), s.plate)
        s.firstRegistration?.let { KeyValueRow(tr("First registration"), fmtDay(it)) }
        s.inspectionDue?.let { KeyValueRow(tr("Inspection due"), fmtDay(it)) }
        if (s.country == "ES") {
            val label = Spain.dgtLabel(s.fuel, s.firstRegistration?.let(::yearOf) ?: s.year, s.euroNorm, s.electricRangeKm)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("DGT environmental label (estimated)"), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.background(dgtColors.getValue(label), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(tr(label.label), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            s.firstRegistration?.let { KeyValueRow(tr("Next ITV"), fmtDay(Spain.nextItv(it, System.currentTimeMillis()))) }
                ?: Text(tr("Add the first registration date to get your next ITV date."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(tr("Source: %1\$s", s.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpecsEditor(initial: VehicleSpecs, onCancel: () -> Unit, onSave: (VehicleSpecs) -> Unit) {
    var make by remember { mutableStateOf(prettyName(initial.make)) }
    var model by remember { mutableStateOf(prettyName(initial.model)) }
    var version by remember { mutableStateOf(initial.version) }
    var year by remember { mutableStateOf(initial.year?.toString().orEmpty()) }
    var fuel by remember { mutableStateOf(initial.fuel) }
    var cc by remember { mutableStateOf(initial.engineCc?.toString().orEmpty()) }
    var kw by remember { mutableStateOf(initial.powerKw?.let { "%.0f".format(Locale.US, it) }.orEmpty()) }
    var cons by remember { mutableStateOf(initial.consumptionL100?.let { "%.1f".format(Locale.US, it) }.orEmpty()) }
    var kwh by remember { mutableStateOf(initial.electricKwh100?.let { "%.1f".format(Locale.US, it) }.orEmpty()) }
    var range by remember { mutableStateOf(initial.electricRangeKm?.toString().orEmpty()) }
    var tank by remember { mutableStateOf(initial.tankL?.let { "%.0f".format(Locale.US, it) }.orEmpty()) }
    var plate by remember { mutableStateOf(initial.plate) }
    var firstReg by remember { mutableStateOf(initial.firstRegistration) }
    var inspection by remember { mutableStateOf(initial.inspectionDue) }
    var fuelMenu by remember { mutableStateOf(false) }
    var dateField by remember { mutableStateOf<Int?>(null) }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()

    SectionCard(title = tr("Review and edit")) {
        Text(tr("Check the details and correct anything that doesn't match your car."), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        @Composable fun field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) =
            OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
        field(tr("Make"), make) { make = it }
        field(tr("Model"), model) { model = it }
        field(tr("Version / trim"), version) { version = it }
        field(tr("Year"), year, true) { year = it.filter(Char::isDigit).take(4) }
        Box(Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { fuelMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(tr("Fuel") + ": " + tr(fuel.label)) }
            DropdownMenu(fuelMenu, { fuelMenu = false }) {
                SpecFuel.entries.forEach { f -> DropdownMenuItem(text = { Text(tr(f.label)) }, onClick = { fuel = f; fuelMenu = false }) }
            }
        }
        if (fuel != SpecFuel.ELECTRIC) field(tr("Engine size (cm³)"), cc, true) { cc = it.filter(Char::isDigit) }
        field(tr("Power (kW)") + (num(kw)?.let { " = %.0f CV".format(it * 1.35962) } ?: ""), kw, true) { kw = it }
        if (fuel != SpecFuel.ELECTRIC) field(tr("Official consumption (L/100 km)"), cons, true) { cons = it }
        if (fuel == SpecFuel.ELECTRIC || fuel.isPlugIn) {
            field(tr("Electric consumption (kWh/100 km)"), kwh, true) { kwh = it }
            field(tr("Electric range (km)"), range, true) { range = it.filter(Char::isDigit) }
        }
        if (fuel != SpecFuel.ELECTRIC) field(tr("Fuel tank (L)"), tank, true) { tank = it }
        field(tr("Licence plate"), plate) { plate = it.uppercase() }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(tr("First registration") + ": " + (firstReg?.let(::fmtDay) ?: "—"), Modifier.weight(1f))
            TextButton(onClick = { dateField = 0 }) { Text(tr("Change")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Inspection due") + ": " + (inspection?.let(::fmtDay) ?: "—"), Modifier.weight(1f))
            TextButton(onClick = { dateField = 1 }) { Text(tr("Change")) }
        }
        if (initial.country == "ES") Text(tr("In Spain the next ITV is calculated from the first registration date (on your registration certificate)."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(tr("Cancel")) }
            Button(onClick = {
                val consumption = num(cons)
                onSave(initial.copy(
                    make = make.trim(), model = model.trim(), version = version.trim(), year = year.toIntOrNull(), fuel = fuel,
                    engineCc = cc.toIntOrNull(), powerKw = num(kw), consumptionL100 = consumption,
                    consumptionEstimated = initial.consumptionEstimated && consumption != null && initial.consumptionL100 != null &&
                        Math.abs(consumption - initial.consumptionL100) < 0.05,
                    electricKwh100 = num(kwh), electricRangeKm = range.toIntOrNull(), tankL = num(tank), plate = plate.trim(),
                    firstRegistration = firstReg, inspectionDue = inspection, lookupDate = if (initial.lookupDate == 0L) System.currentTimeMillis() else initial.lookupDate,
                ))
            }, modifier = Modifier.weight(1f), enabled = make.isNotBlank()) { Text(tr("Save")) }
        }
    }
    dateField?.let { which ->
        val state = rememberDatePickerState(initialSelectedDateMillis = (if (which == 0) firstReg else inspection) ?: System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = { dateField = null },
            confirmButton = { TextButton(onClick = { if (which == 0) firstReg = state.selectedDateMillis else inspection = state.selectedDateMillis; dateField = null }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { dateField = null }) { Text(tr("Cancel")) } }) { DatePicker(state) }
    }
}
