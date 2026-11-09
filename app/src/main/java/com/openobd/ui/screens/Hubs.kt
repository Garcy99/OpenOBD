package com.openobd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.battery.BatteryLevel
import com.openobd.core.garage.FuelStats
import com.openobd.core.garage.Maintenance
import com.openobd.core.health.HealthLevel
import com.openobd.ui.LocalApp
import com.openobd.ui.Routes
import com.openobd.ui.appSettings
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.GroupCard
import com.openobd.ui.components.GroupDivider
import com.openobd.ui.components.HeroCard
import com.openobd.ui.components.NavRow
import com.openobd.ui.components.PillTabs
import com.openobd.ui.components.ScreenColumn
import com.openobd.ui.components.SectionLabel
import com.openobd.ui.components.SoftButton
import com.openobd.ui.components.StatTile
import com.openobd.ui.components.StatusChip
import com.openobd.ui.components.TabHeader
import com.openobd.ui.components.TopBarSlot
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Orange
import com.openobd.ui.theme.Violet
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.launch

/** Which page of the Live tab is shown; other screens set it before switching to the tab. */
object LivePages {
    const val GAUGES = 0
    const val SENSORS = 1
    const val TRIP = 2
    const val PERFORMANCE = 3
    var selected by mutableIntStateOf(GAUGES)
}

/** Live tab: gauges, the full sensor list, the trip computer and the performance timer behind one row of pills. */
@Composable
fun LiveHubScreen(onConnect: () -> Unit, onNavigate: (String) -> Unit) {
    val pages = listOf(tr("Gauges"), tr("Sensors"), tr("Trip"), tr("Performance"))
    val header: TopBarSlot = { _, actions ->
        Column {
            TabHeader(tr("Live"), actions = actions)
            PillTabs(pages, LivePages.selected, { LivePages.selected = it })
        }
    }
    when (LivePages.selected) {
        LivePages.SENSORS -> LiveDataScreen(onConnect = onConnect, onChart = { onNavigate(Routes.CHART) }, topBar = header)
        LivePages.TRIP -> TripScreen(onBack = {}, onConnect = onConnect, onHistory = { onNavigate(Routes.TRIPS) }, topBar = header)
        LivePages.PERFORMANCE -> PerformanceScreen(onBack = {}, onConnect = onConnect, topBar = header)
        else -> DashboardScreen(onConnect = onConnect, onHud = { onNavigate(Routes.HUD) }, topBar = header)
    }
}

/** Garage tab: the active car with its records, and the other cars. */
@Composable
fun GarageHubScreen(onNavigate: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val v = g.active
    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("Garage"), actions = {
            IconButton(onClick = { onNavigate(Routes.GARAGE) }) { Icon(Icons.Default.Add, tr("Add car")) }
        })
        if (v == null) {
            CenteredMessage(Icons.Default.Garage, tr("Add your car to keep its service, fuel, trips and health in one place."), title = tr("Your garage is empty")) {
                SoftButton(tr("Add car"), { onNavigate(Routes.GARAGE) }, icon = Icons.Default.Add)
            }
            return@Column
        }
        val now = System.currentTimeMillis()
        val statuses = g.maintenance.filter { it.vehicleId == v.id }.map { Maintenance.status(it, v.odometerKm, now) }
        val overdue = statuses.count { it.overdue }
        val soon = statuses.count { it.dueSoon }
        val fuel = remember(g.fuel, v.id) { FuelStats.compute(g.fuel.filter { it.vehicleId == v.id }) }
        val trips = g.trips.filter { it.vehicleId == v.id }.sortedByDescending { it.start }
        val month = trips.filter { now - it.start < 30L * 86_400_000 }.sumOf { it.distanceKm }
        val eco = trips.take(10).mapNotNull { it.ecoScore }.takeIf { it.isNotEmpty() }?.average()
        val health = remember(g.health, v.id) { app.session.health.report() }
        val battery = g.batteryTests.filter { it.vehicleId == v.id }.maxByOrNull { it.time }

        ScreenColumn {
            HeroCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(if (overdue > 0) tr("Service overdue") else if (soon > 0) tr("Service due soon") else tr("Up to date"),
                        if (overdue > 0) Bad else if (soon > 0) Warn else Good)
                    Spacer(Modifier.weight(1f))
                    if (g.vehicles.size > 1) Text(tr("%1\$s cars", g.vehicles.size), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Text(v.name, style = MaterialTheme.typography.headlineMedium)
                Text(v.specs?.let { s -> listOfNotNull(tr(s.fuel.label), s.engineLabel().ifBlank { null }).joinToString(" · ") }
                    ?: tr("Model not identified yet"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(tr("Odometer"), fmtKm(v.odometerKm, settings.units).substringBeforeLast(' '), Modifier.weight(1f),
                        unit = fmtKm(v.odometerKm, settings.units).substringAfterLast(' ').takeIf { v.odometerKm != null })
                    StatTile(tr("Last 30 days"), fmtKm(month, settings.units).substringBeforeLast(' '), Modifier.weight(1f),
                        unit = fmtKm(month, settings.units).substringAfterLast(' '))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(tr("Real consumption"), fmtL100(fuel.averageL100, settings.units).substringBeforeLast(' '), Modifier.weight(1f),
                        unit = fmtL100(fuel.averageL100, settings.units).substringAfterLast(' ').takeIf { fuel.averageL100 != null })
                    StatTile(tr("Eco score"), eco?.let { "%.0f".format(it) } ?: "–", Modifier.weight(1f), unit = eco?.let { "/100" })
                }
            }

            SectionLabel(tr("Records"))
            GroupCard {
                NavRow(Icons.Default.DirectionsCar, tr("Model & specifications"),
                    v.specs?.let { s -> listOfNotNull(s.year?.toString(), s.title.ifBlank { null }).joinToString(" ") } ?: tr("Identify your car (model & specs)"),
                    tint = MaterialTheme.colorScheme.primary) { onNavigate(Routes.findCar(v.id)) }
                GroupDivider()
                NavRow(Icons.Default.Build, tr("Maintenance"),
                    when { overdue > 0 -> tr("%1\$s overdue", overdue); soon > 0 -> tr("%1\$s due soon", soon); statuses.isEmpty() -> tr("Add service reminders"); else -> tr("All up to date") },
                    tint = if (overdue > 0) Bad else if (soon > 0) Warn else Good) { onNavigate(Routes.MAINTENANCE) }
                GroupDivider()
                NavRow(Icons.Default.LocalGasStation, tr("Fuel log"),
                    if (fuel.totalLiters > 0) g.fuel.count { it.vehicleId == v.id }.let { n -> if (n == 1) tr("1 fill-up") else tr("%1\$s fill-ups", n) } + " · " + fmtL100(fuel.averageL100, settings.units) else tr("Log fill-ups to see your real consumption"),
                    tint = Orange) { onNavigate(Routes.FUEL) }
                GroupDivider()
                NavRow(Icons.Default.Map, tr("Trips"),
                    if (trips.isNotEmpty()) (if (trips.size == 1) tr("1 trip") else tr("%1\$s trips", trips.size)) + " · " + fmtKm(trips.sumOf { it.distanceKm }, settings.units) else tr("Trips are saved automatically while connected"),
                    tint = Violet) { onNavigate(Routes.TRIPS) }
            }

            SectionLabel(tr("Condition"))
            GroupCard {
                NavRow(Icons.Default.MonitorHeart, tr("Car health"),
                    if (health.samples < 3) tr("Builds up over your next drives") else when (health.overall) {
                        HealthLevel.GOOD -> tr("Everything looks normal"); HealthLevel.WATCH -> tr("Something to keep an eye on"); HealthLevel.ACT -> tr("Needs attention")
                    }, tint = if (health.samples < 3) MaterialTheme.colorScheme.primary else when (health.overall) { HealthLevel.GOOD -> Good; HealthLevel.WATCH -> Warn; HealthLevel.ACT -> Bad },
                ) { onNavigate(Routes.HEALTH) }
                GroupDivider()
                val level = battery?.let { runCatching { BatteryLevel.valueOf(it.level) }.getOrNull() }
                NavRow(Icons.Default.BatteryChargingFull, tr("Battery & starting test"),
                    battery?.let { b -> tr("Last test: %1\$s", tr(level?.label ?: "Not measured")) + (b.minV?.let { " · %.1f V".format(it) } ?: "") } ?: tr("Check the battery before winter"),
                    tint = when (level) { BatteryLevel.GOOD -> Good; BatteryLevel.WATCH -> Warn; BatteryLevel.BAD -> Bad; else -> MaterialTheme.colorScheme.primary },
                ) { onNavigate(Routes.BATTERY) }
            }

            if (g.vehicles.size > 1) {
                SectionLabel(tr("Your cars"), action = tr("Manage"), onAction = { onNavigate(Routes.GARAGE) })
                GroupCard {
                    g.vehicles.forEachIndexed { i, car ->
                        if (i > 0) GroupDivider()
                        NavRow(Icons.Default.DirectionsCar, car.name, car.specs?.title?.ifBlank { null } ?: car.vin ?: tr("No VIN yet"),
                            tint = if (car.id == v.id) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                            trailing = { if (car.id == v.id) Icon(Icons.Default.CheckCircle, tr("Selected"), tint = Good, modifier = Modifier.size(20.dp)) },
                        ) { scope.launch { app.garage.update { it.copy(activeVehicleId = car.id) } } }
                    }
                }
            } else {
                SectionLabel(tr("Your cars"))
                GroupCard { NavRow(Icons.Default.Garage, tr("Manage cars"), tr("Add another car, rename or set its VIN")) { onNavigate(Routes.GARAGE) } }
            }
        }
    }
}

/** More tab: everything else, grouped by purpose instead of one long list. */
@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val app = LocalApp.current
    val brand by app.session.brand.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TabHeader(tr("More"))
        ScreenColumn {
            HeroCard(contentPadding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OpenOBD " + com.openobd.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleLarge)
                        Text(tr("Open source · No ads · Account optional · Your data stays yours"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            com.openobd.ui.components.DonateCard()
            SectionLabel(tr("For your car"))
            GroupCard {
                NavRow(Icons.Default.Hub, brand?.let { tr("%1\$s features", it.name) } ?: tr("Brand features & modules"),
                    tr("ABS, airbag, body, EV battery modules · manufacturer codes · brand PIDs")) { onNavigate(Routes.BRAND) }
                GroupDivider()
                NavRow(Icons.Default.Build, tr("Codings & adaptations"), tr("Change module settings, with a backup and undo for every change"),
                    tint = Violet) { onNavigate(Routes.CODINGS) }
                GroupDivider()
                NavRow(Icons.Default.CloudDownload, tr("Community data (OBDb)"), tr("Manufacturer values for your make: battery, DPF, tyres, gearbox …"),
                    tint = Violet) { onNavigate(Routes.PACKS) }
                GroupDivider()
                NavRow(Icons.Default.LocalFireDepartment, tr("Diesel particulate filter"), tr("Guided regeneration drive with live conditions and DPF values"),
                    tint = Orange) { onNavigate(Routes.DIESEL) }
                GroupDivider()
                NavRow(Icons.Default.MenuBook, tr("Service guides"), tr("Oil service reset, after a battery change, drive cycle for the ITV"),
                    tint = Good) { onNavigate(Routes.GUIDES) }
                GroupDivider()
                NavRow(Icons.Default.Fullscreen, tr("Head-up display"), tr("Mirrored display for the windshield"), tint = Violet) { onNavigate(Routes.HUD) }
            }
            SectionLabel(tr("Records"))
            GroupCard {
                NavRow(Icons.Default.Storage, tr("Data logs"), tr("Recorded CSV sessions, share or export")) { onNavigate(Routes.LOGS) }
                GroupDivider()
                NavRow(Icons.Default.History, tr("Scan history"), tr("Every scan and clear, so you know when codes came back")) { onNavigate(Routes.HISTORY) }
                GroupDivider()
                NavRow(Icons.Default.ManageSearch, tr("Trouble code lookup"), tr("Offline database of 1300+ generic codes")) { onNavigate(Routes.LOOKUP) }
            }
            SectionLabel(tr("Advanced"))
            GroupCard {
                NavRow(Icons.Default.Tune, tr("Custom PIDs"), tr("Add or import (Torque CSV) manufacturer-specific PIDs"), tint = Violet) { onNavigate(Routes.PIDS) }
                GroupDivider()
                NavRow(Icons.Default.Inventory2, tr("PID packs"), tr("Share your PIDs or download packs for your car from the community"), tint = Violet) { onNavigate(Routes.PACKS) }
                GroupDivider()
                NavRow(Icons.Default.TravelExplore, tr("Identifier explorer"), tr("Advanced: discover and watch manufacturer data identifiers"), tint = Violet) { onNavigate(Routes.EXPLORER) }
                GroupDivider()
                NavRow(Icons.Default.Terminal, tr("Terminal"), tr("Send raw AT / OBD commands to the adapter"), tint = Violet) { onNavigate(Routes.TERMINAL) }
            }
            SectionLabel(tr("App"))
            GroupCard {
                val sync by app.sync.state.collectAsStateWithLifecycle()
                NavRow(Icons.Default.CloudSync, tr("Google account and sync"),
                    if (sync.connected) tr("Synced as %1\$s", sync.email ?: "Google") else tr("Optional: the same data on all your phones"),
                    tint = MaterialTheme.colorScheme.primary) { onNavigate(Routes.ACCOUNT) }
                GroupDivider()
                NavRow(Icons.Default.Explore, tr("App tour"), tr("The basics in two minutes"), tint = MaterialTheme.colorScheme.primary) { onNavigate(Routes.tour(false)) }
                GroupDivider()
                NavRow(Icons.Default.EmojiEvents, tr("Full tour"), tr("Every feature, step by step"), tint = MaterialTheme.colorScheme.primary) { onNavigate(Routes.tour(true)) }
                GroupDivider()
                NavRow(Icons.Default.Settings, tr("Settings"), tr("Units, adapter options, alerts, theme"), tint = MaterialTheme.colorScheme.onSurfaceVariant) { onNavigate(Routes.SETTINGS) }
                GroupDivider()
                NavRow(Icons.Default.Info, tr("About"), tr("Version, licenses and supported adapters"), tint = MaterialTheme.colorScheme.onSurfaceVariant) { onNavigate(Routes.ABOUT) }
            }
        }
    }
}
