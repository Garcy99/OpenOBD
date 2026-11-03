package com.openobd.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openobd.AppContainer
import com.openobd.ui.components.formatNumber
import com.openobd.ui.screens.AboutScreen
import com.openobd.ui.screens.BrandScreen
import com.openobd.ui.screens.ChartScreen
import com.openobd.ui.screens.CustomPidsScreen
import com.openobd.ui.screens.ModuleScreen
import com.openobd.ui.screens.DashboardScreen
import com.openobd.ui.screens.DiagnosticsScreen
import com.openobd.ui.screens.DtcLookupScreen
import com.openobd.ui.screens.FreezeFrameScreen
import com.openobd.ui.screens.HistoryScreen
import com.openobd.ui.screens.HomeScreen
import com.openobd.ui.screens.HudScreen
import com.openobd.ui.screens.LiveDataScreen
import com.openobd.ui.screens.LogsScreen
import com.openobd.ui.screens.MonitorTestsScreen
import com.openobd.ui.screens.MoreScreen
import com.openobd.ui.screens.PerformanceScreen
import com.openobd.ui.screens.ReadinessScreen
import com.openobd.ui.screens.SettingsScreen
import com.openobd.ui.screens.TerminalScreen
import com.openobd.ui.screens.TripScreen
import com.openobd.ui.screens.VehicleInfoScreen

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

object Routes {
    const val HOME = "home"
    const val DASHBOARD = "dashboard"
    const val LIVE = "live"
    const val DIAGNOSTICS = "diagnostics"
    const val MORE = "more"
    const val CHART = "chart"
    const val READINESS = "readiness"
    const val VEHICLE = "vehicle"
    const val FREEZE = "freeze"
    const val TESTS = "tests"
    const val PERFORMANCE = "performance"
    const val TRIP_LIVE = "trip"
    const val LOGS = "logs"
    const val TERMINAL = "terminal"
    const val SETTINGS = "settings"
    const val HUD = "hud"
    const val LOOKUP = "lookup"
    const val HISTORY = "history"
    const val ABOUT = "about"
    const val BRAND = "brand"
    const val GARAGE = "garage"
    const val MAINTENANCE = "maintenance"
    const val FUEL = "fuel"
    const val TRIPS = "trips"
    const val TRIP = "trip/{id}"
    fun trip(id: String) = "trip/$id"
    const val PIDS = "pids"
    const val MODULE = "module/{id}"
    fun module(id: String) = "module/" + android.net.Uri.encode(id)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Vehicle", Icons.Default.DirectionsCar),
    Tab(Routes.DASHBOARD, "Dashboard", Icons.Default.Speed),
    Tab(Routes.LIVE, "Live data", Icons.Default.ShowChart),
    Tab(Routes.DIAGNOSTICS, "Codes", Icons.Default.Build),
    Tab(Routes.MORE, "More", Icons.Default.Apps),
)

fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun OpenObdNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val app = LocalApp.current

    LaunchedEffect(Unit) {
        app.session.alerts.collect { e ->
            val unit = com.openobd.core.obd.SensorCatalog.get(e.rule.sensorKey)?.unit?.symbol ?: ""
            snackbar.showSnackbar("⚠ ${e.sensorName}: ${formatNumber(e.value, 1)} $unit " +
                (if (e.rule.above) "above " else "below ") + formatNumber(e.rule.threshold, 1))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current != Routes.HUD) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { nav.goTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { pad ->
        val back: () -> Unit = { nav.popBackStack() }
        val toHome: () -> Unit = { nav.goTab(Routes.HOME) }
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable(Routes.HOME) { HomeScreen(onNavigate = { nav.navigate(it) }, onTab = { nav.goTab(it) }) }
            composable(Routes.DASHBOARD) { DashboardScreen(onConnect = toHome, onHud = { nav.navigate(Routes.HUD) }) }
            composable(Routes.LIVE) { LiveDataScreen(onConnect = toHome, onChart = { nav.navigate(Routes.CHART) }) }
            composable(Routes.CHART) { ChartScreen(onBack = back, onConnect = toHome) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onConnect = toHome, onNavigate = { nav.navigate(it) }) }
            composable(Routes.MORE) { MoreScreen(onNavigate = { nav.navigate(it) }) }
            composable(Routes.READINESS) { ReadinessScreen(onBack = back, onConnect = toHome) }
            composable(Routes.VEHICLE) { VehicleInfoScreen(onBack = back, onConnect = toHome) }
            composable(Routes.FREEZE) { FreezeFrameScreen(onBack = back, onConnect = toHome) }
            composable(Routes.TESTS) { MonitorTestsScreen(onBack = back, onConnect = toHome) }
            composable(Routes.PERFORMANCE) { PerformanceScreen(onBack = back, onConnect = toHome) }
            composable(Routes.TRIP_LIVE) { TripScreen(onBack = back, onConnect = toHome, onHistory = { nav.navigate(Routes.TRIPS) }) }
            composable(Routes.LOGS) { LogsScreen(onBack = back) }
            composable(Routes.TERMINAL) { TerminalScreen(onBack = back) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = back) }
            composable(Routes.HUD) { HudScreen(onBack = back) }
            composable(Routes.LOOKUP) { DtcLookupScreen(onBack = back) }
            composable(Routes.HISTORY) { HistoryScreen(onBack = back) }
            composable(Routes.ABOUT) { AboutScreen(onBack = back) }
            composable(Routes.GARAGE) { com.openobd.ui.screens.GarageScreen(onBack = back) }
            composable(Routes.MAINTENANCE) { com.openobd.ui.screens.MaintenanceScreen(onBack = back) }
            composable(Routes.FUEL) { com.openobd.ui.screens.FuelLogScreen(onBack = back) }
            composable(Routes.TRIPS) { com.openobd.ui.screens.TripsScreen(onBack = back, onOpen = { nav.navigate(Routes.trip(it)) }) }
            composable(Routes.TRIP) { e -> com.openobd.ui.screens.TripDetailScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
            composable(Routes.BRAND) { BrandScreen(onBack = back, onNavigate = { nav.navigate(it) }) }
            composable(Routes.PIDS) { CustomPidsScreen(onBack = back) }
            composable(Routes.MODULE) { e -> ModuleScreen(e.arguments?.getString("id").orEmpty(), onBack = back, onConnect = toHome) }
        }
    }
}
