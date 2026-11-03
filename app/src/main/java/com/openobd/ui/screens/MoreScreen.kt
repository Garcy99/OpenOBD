package com.openobd.ui.screens

import com.openobd.ui.tr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import com.openobd.ui.Routes
import com.openobd.ui.components.ScreenTopBar

private data class MenuItem(val route: String, val title: String, val subtitle: String, val icon: ImageVector)

private val items = listOf(
    MenuItem(Routes.GARAGE, "Garage", tr("Your cars, each with its own history, brand settings and PIDs"), Icons.Default.Garage),
    MenuItem(Routes.MAINTENANCE, "Maintenance", tr("Service reminders by distance and time"), Icons.Default.Build),
    MenuItem(Routes.FUEL, "Fuel log", tr("Fill-ups, real consumption and costs"), Icons.Default.LocalGasStation),
    MenuItem(Routes.TRIPS, "Trip history", tr("Saved trips with GPS map, fuel and speed"), Icons.Default.Map),
    MenuItem(Routes.LIGHTS, "Identify a warning light", tr("Photo of the dashboard: which lights are on, how urgent, what to do (AI)"), Icons.Default.PhotoCamera),
    MenuItem(Routes.HEALTH, "Car health", tr("Battery, charging, fuel trims and warm-up over time, with early warnings"), Icons.Default.MonitorHeart),
    MenuItem(Routes.DIESEL, "Diesel particulate filter", tr("Guided regeneration drive with live conditions and DPF values"), Icons.Default.LocalFireDepartment),
    MenuItem(Routes.GUIDES, "Service guides", tr("Oil service reset, after a battery change, drive cycle for the ITV"), Icons.Default.MenuBook),
    MenuItem(Routes.EXPLORER, "Identifier explorer", tr("Advanced: discover and watch manufacturer data identifiers"), Icons.Default.TravelExplore),
    MenuItem(Routes.CHECK, "Used car & pre-ITV check", tr("Hidden or recently cleared faults, VIN and odometer, readiness · PDF report"), Icons.Default.FactCheck),
    MenuItem(Routes.BRAND, "Brand features & modules", tr("ABS, airbag, body, EV battery modules · manufacturer codes · brand PIDs"), Icons.Default.Hub),
    MenuItem(Routes.READINESS, "Emission readiness (I/M)", tr("Smog check monitors, since clear and this drive cycle"), Icons.Default.Checklist),
    MenuItem(Routes.FREEZE, "Freeze frame", tr("Sensor snapshot captured when a fault was stored"), Icons.Default.Flag),
    MenuItem(Routes.TESTS, "On-board monitor tests (Mode 06)", tr("Catalyst, O2 sensors, misfire counts per cylinder, EVAP"), Icons.Default.Science),
    MenuItem(Routes.VEHICLE, "Vehicle information", tr("VIN decoder, calibration IDs, CVN, ECU names, IPT counters"), Icons.Default.DirectionsCar),
    MenuItem(Routes.TRIP_LIVE, "Trip computer", tr("Distance, fuel used, average consumption, cost"), Icons.Default.Route),
    MenuItem(Routes.PERFORMANCE, "Performance timer", tr("0-100 km/h, 0-60 mph, 80-120, 1/4 mile"), Icons.Default.Timer),
    MenuItem(Routes.HUD, "Head-up display", tr("Mirrored display for the windshield"), Icons.Default.Fullscreen),
    MenuItem(Routes.LOGS, "Data logs", tr("Recorded CSV sessions, share or export"), Icons.Default.Storage),
    MenuItem(Routes.HISTORY, "Scan history", tr("Every scan and clear, so you know when codes came back"), Icons.Default.History),
    MenuItem(Routes.PIDS, "Custom PIDs", tr("Add or import (Torque CSV) manufacturer-specific PIDs"), Icons.Default.Tune),
    MenuItem(Routes.PACKS, "PID packs", tr("Share your PIDs or download packs for your car from the community"), Icons.Default.Inventory2),
    MenuItem(Routes.LOOKUP, "Trouble code lookup", tr("Offline database of 1300+ generic codes"), Icons.Default.ManageSearch),
    MenuItem(Routes.TERMINAL, "Terminal", tr("Send raw AT / OBD commands to the adapter"), Icons.Default.Terminal),
    MenuItem(Routes.SETTINGS, "Settings", tr("Units, adapter options, alerts, theme"), Icons.Default.Settings),
    MenuItem(Routes.ABOUT, "About", tr("Version, licenses and supported adapters"), Icons.Default.Info),
)

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("More"))
        LazyColumn {
            items(items, key = { it.route }) { m ->
                ListItem(
                    headlineContent = { Text(tr(m.title)) },
                    supportingContent = { Text(tr(m.subtitle), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(m.icon, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onNavigate(m.route) },
                )
                HorizontalDivider()
            }
        }
    }
}
