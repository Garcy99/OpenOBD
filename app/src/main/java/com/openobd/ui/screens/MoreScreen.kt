package com.openobd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
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
    MenuItem(Routes.GARAGE, "Garage", "Your cars, each with its own history, brand settings and PIDs", Icons.Default.Garage),
    MenuItem(Routes.MAINTENANCE, "Maintenance", "Service reminders by distance and time", Icons.Default.Build),
    MenuItem(Routes.FUEL, "Fuel log", "Fill-ups, real consumption and costs", Icons.Default.LocalGasStation),
    MenuItem(Routes.TRIPS, "Trip history", "Saved trips with GPS map, fuel and speed", Icons.Default.Map),
    MenuItem(Routes.BRAND, "Brand features & modules", "ABS, airbag, body, EV battery modules · manufacturer codes · brand PIDs", Icons.Default.Hub),
    MenuItem(Routes.READINESS, "Emission readiness (I/M)", "Smog check monitors, since clear and this drive cycle", Icons.Default.Checklist),
    MenuItem(Routes.FREEZE, "Freeze frame", "Sensor snapshot captured when a fault was stored", Icons.Default.Flag),
    MenuItem(Routes.TESTS, "On-board monitor tests (Mode 06)", "Catalyst, O2 sensors, misfire counts per cylinder, EVAP", Icons.Default.Science),
    MenuItem(Routes.VEHICLE, "Vehicle information", "VIN decoder, calibration IDs, CVN, ECU names, IPT counters", Icons.Default.DirectionsCar),
    MenuItem(Routes.TRIP_LIVE, "Trip computer", "Distance, fuel used, average consumption, cost", Icons.Default.Route),
    MenuItem(Routes.PERFORMANCE, "Performance timer", "0-100 km/h, 0-60 mph, 80-120, 1/4 mile", Icons.Default.Timer),
    MenuItem(Routes.HUD, "Head-up display", "Mirrored display for the windshield", Icons.Default.Fullscreen),
    MenuItem(Routes.LOGS, "Data logs", "Recorded CSV sessions, share or export", Icons.Default.Storage),
    MenuItem(Routes.HISTORY, "Scan history", "Every scan and clear, so you know when codes came back", Icons.Default.History),
    MenuItem(Routes.PIDS, "Custom PIDs", "Add or import (Torque CSV) manufacturer-specific PIDs", Icons.Default.Tune),
    MenuItem(Routes.LOOKUP, "Trouble code lookup", "Offline database of 1300+ generic codes", Icons.Default.ManageSearch),
    MenuItem(Routes.TERMINAL, "Terminal", "Send raw AT / OBD commands to the adapter", Icons.Default.Terminal),
    MenuItem(Routes.SETTINGS, "Settings", "Units, adapter options, alerts, theme", Icons.Default.Settings),
    MenuItem(Routes.ABOUT, "About", "Version, licenses and supported adapters", Icons.Default.Info),
)

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("More")
        LazyColumn {
            items(items, key = { it.route }) { m ->
                ListItem(
                    headlineContent = { Text(m.title) },
                    supportingContent = { Text(m.subtitle, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(m.icon, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onNavigate(m.route) },
                )
                HorizontalDivider()
            }
        }
    }
}
