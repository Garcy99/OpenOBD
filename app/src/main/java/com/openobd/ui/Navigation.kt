package com.openobd.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.openobd.ui.screens.DiagnosticsScreen
import com.openobd.ui.screens.DtcLookupScreen
import com.openobd.ui.screens.FreezeFrameScreen
import com.openobd.ui.screens.HistoryScreen
import com.openobd.ui.screens.HomeScreen
import com.openobd.ui.screens.HudScreen
import com.openobd.ui.screens.LogsScreen
import com.openobd.ui.screens.MoreScreen
import com.openobd.ui.screens.MonitorTestsScreen
import com.openobd.ui.screens.PerformanceScreen
import com.openobd.ui.screens.ReadinessScreen
import com.openobd.ui.screens.SettingsScreen
import com.openobd.ui.screens.TerminalScreen
import com.openobd.ui.screens.TripScreen
import com.openobd.ui.screens.VehicleInfoScreen

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

object Routes {
    const val HOME = "home"
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
    const val GARAGE_HUB = "garagehub"
    const val FINDCAR = "findcar/{id}"
    fun findCar(id: String) = "findcar/$id"
    const val MAINTENANCE = "maintenance"
    const val FUEL = "fuel"
    const val TRIPS = "trips"
    const val MONTH = "month"
    const val TRIP = "trip/{id}"
    fun trip(id: String) = "trip/$id"
    const val TRIP_3D = "trip3d/{id}"
    fun trip3d(id: String) = "trip3d/$id"
    const val PIDS = "pids"
    const val CHECK = "check"
    const val HEALTH = "health"
    const val LIGHTS = "lights"
    const val PACKS = "packs"
    const val BATTERY = "battery"
    const val WELCOME = "welcome"
    const val TOUR = "tour/{level}"
    fun tour(advanced: Boolean) = "tour/" + if (advanced) "full" else "basic"
    const val ACCOUNT = "account"
    const val BRIDGE = "bridge"
    const val FUEL_PRICES = "fuel_prices"
    const val AUTO_TRIPS = "auto_trips"
    const val LOG = "log/{name}"
    fun log(name: String) = "log/" + android.net.Uri.encode(name)
    const val DIESEL = "diesel"
    const val VALUE_FINDER = "value_finder"
    const val GUIDES = "guides"
    const val EXPLORER = "explorer"
    const val CODINGS = "codings"
    const val CODE = "code/{code}"
    fun code(c: String) = "code/$c"
    const val ASSISTANT = "assistant/{code}"
    fun assistant(c: String) = "assistant/$c"
    const val MODULE = "module/{id}"
    fun module(id: String) = "module/" + android.net.Uri.encode(id)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.LIVE, "Live", Icons.Outlined.Speed, Icons.Filled.Speed),
    Tab(Routes.DIAGNOSTICS, "Diagnose", Icons.Outlined.MedicalServices, Icons.Filled.MedicalServices),
    Tab(Routes.GARAGE_HUB, "Garage", Icons.Outlined.Garage, Icons.Filled.Garage),
    Tab(Routes.MORE, "More", Icons.Outlined.GridView, Icons.Filled.GridView),
)

private val tabRoutes = tabs.map { it.route }.toSet()

/** Opens a page of the Live tab (gauges, sensors, trip, performance). */
fun NavHostController.openLive(page: Int) {
    com.openobd.ui.screens.LivePages.selected = page
    goTab(Routes.LIVE)
}

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

    // Screens asked for by a notification.
    LaunchedEffect(Unit) {
        app.openRequest.collect { r -> if (r != null) { runCatching { nav.navigate(r) }; app.openRequest.value = null } }
    }
    LaunchedEffect(Unit) {
        app.session.alerts.collect { e ->
            val unit = com.openobd.core.obd.SensorCatalog.get(e.rule.sensorKey)?.unit?.symbol ?: ""
            snackbar.showSnackbar("⚠ ${e.sensorName}: ${formatNumber(e.value, 1)} $unit " +
                (if (e.rule.above) "above " else "below ") + formatNumber(e.rule.threshold, 1))
        }
    }

    // A new version on GitHub pops up over any screen (except the first setup and the head-up display).
    if (current != Routes.WELCOME && current != Routes.HUD) UpdateDialog()

    // Tablets and landscape phones get a side rail instead of the bottom bar.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val settings = appSettings()
    // Room the car's own controls take (climate bars drawn over the apps), set by hand in Settings.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .padding(start = settings.marginLeftDp.dp, bottom = settings.marginBottomDp.dp).testTag("appContent")) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current != Routes.HUD && !wide) Column {
                HorizontalDivider(color = com.openobd.ui.theme.LocalExtras.current.hairline)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        val on = current == t.route
                        NavigationBarItem(
                            selected = on,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(if (on) t.selectedIcon else t.icon, null) },
                            label = { Text(tr(t.label), maxLines = 1, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        val back: () -> Unit = { nav.popBackStack() }
        val toHome: () -> Unit = { nav.goTab(Routes.HOME) }
        // Bars and cut-outs the system reports are kept clear: at the bottom by the tab bar (narrow screens),
        // otherwise here (car screens, tablets, the head-up display).
        val bottomBarShown = current != Routes.HUD && !wide
        val safe = WindowInsets.safeDrawing.only(if (bottomBarShown) WindowInsetsSides.Horizontal else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
            if (wide && current != Routes.HUD) androidx.compose.material3.NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                tabs.forEach { t ->
                    androidx.compose.material3.NavigationRailItem(
                        selected = current == t.route,
                        onClick = { nav.goTab(t.route) },
                        icon = { Icon(if (current == t.route) t.selectedIcon else t.icon, null) },
                        label = { Text(tr(t.label), maxLines = 1) },
                    )
                }
            }
            NavHost(
                nav, startDestination = Routes.HOME, modifier = Modifier.weight(1f).padding(bottom = pad.calculateBottomPadding()),
                // Tabs cross-fade; inner screens slide in slightly from the side, like a native push.
                enterTransition = {
                    if (targetState.destination.route in tabRoutes) fadeIn(tween(220))
                    else fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 8 }
                },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = {
                    if (initialState.destination.route in tabRoutes) fadeOut(tween(160))
                    else fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 8 }
                },
            ) {
                composable(Routes.HOME) { HomeScreen(onNavigate = { nav.navigate(it) }, onTab = { nav.goTab(it) }, onLive = { nav.openLive(it) }) }
                composable(Routes.LIVE) { com.openobd.ui.screens.LiveHubScreen(onConnect = toHome, onNavigate = { nav.navigate(it) }) }
                composable(Routes.GARAGE_HUB) { com.openobd.ui.screens.GarageHubScreen(onNavigate = { nav.navigate(it) }) }
                composable(Routes.CHART) { ChartScreen(onBack = back, onConnect = toHome) }
                composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onConnect = toHome, onNavigate = { nav.navigate(it) }) }
                composable(Routes.MORE) { MoreScreen(onNavigate = { nav.navigate(it) }) }
                composable(Routes.READINESS) { ReadinessScreen(onBack = back, onConnect = toHome) }
                composable(Routes.CODE) { e ->
                    val code = e.arguments?.getString("code").orEmpty()
                    com.openobd.ui.screens.CodeScreen(code, onBack = back, onAssistant = { nav.navigate(Routes.assistant(code)) })
                }
                composable(Routes.ASSISTANT) { e ->
                    com.openobd.ui.screens.AssistantScreen(e.arguments?.getString("code").orEmpty(), onBack = back, onSettings = { nav.navigate(Routes.SETTINGS) })
                }
                composable(Routes.DIESEL) { com.openobd.ui.screens.DieselScreen(onBack = back, onConnect = toHome, onGuides = { nav.navigate(Routes.GUIDES) }, onPacks = { nav.navigate(Routes.PACKS) },
                    onFinder = { nav.navigate(Routes.VALUE_FINDER) }) }
                composable(Routes.VALUE_FINDER) { com.openobd.ui.screens.ValueFinderScreen(onBack = back, onConnect = toHome) }
                composable(Routes.GUIDES) { com.openobd.ui.screens.ServiceGuidesScreen(onBack = back, onDieselHelper = { nav.navigate(Routes.DIESEL) }) }
                composable(Routes.CODINGS) { com.openobd.ui.screens.CodingsScreen(onBack = back, onConnect = toHome) }
                composable(Routes.EXPLORER) { com.openobd.ui.screens.IdExplorerScreen(onBack = back, onConnect = toHome) }
                // After the first setup comes the basic tour (in place of the setup, so Back does not return to it).
                composable(Routes.WELCOME) {
                    com.openobd.ui.screens.WelcomeScreen(
                        onDone = { nav.navigate(Routes.tour(false)) { popUpTo(Routes.WELCOME) { inclusive = true } } },
                        onGarage = { nav.navigate(Routes.GARAGE) },
                    )
                }
                composable(Routes.TOUR) { e ->
                    com.openobd.ui.screens.TourScreen(
                        advanced = e.arguments?.getString("level") == "full",
                        onClose = { if (!nav.popBackStack()) nav.goTab(Routes.HOME) },
                        // Opened on top of the tour, so Back returns to the same page of it.
                        onOpen = { r -> nav.navigate(r) },
                        onFullTour = { nav.navigate(Routes.tour(true)) { popUpTo(Routes.TOUR) { inclusive = true } } },
                    )
                }
                composable(Routes.ACCOUNT) { com.openobd.ui.screens.AccountScreen(onBack = back) }
                composable(Routes.BRIDGE) { com.openobd.ui.screens.BridgeScreen(onBack = back) }
                composable(Routes.FUEL_PRICES) { com.openobd.ui.screens.FuelPricesScreen(onBack = back) }
                composable(Routes.AUTO_TRIPS) { com.openobd.ui.screens.AutoTripsScreen(onBack = back) }
            composable(Routes.PACKS) { com.openobd.ui.screens.PacksScreen(onBack = back) }
            composable(Routes.LIGHTS) { com.openobd.ui.screens.WarningLightScreen(onBack = back, onSettings = { nav.navigate(Routes.SETTINGS) }) }
            composable(Routes.HEALTH) { com.openobd.ui.screens.HealthScreen(onBack = back) }
            composable(Routes.BATTERY) { com.openobd.ui.screens.BatteryTestScreen(onBack = back, onConnect = toHome) }
                composable(Routes.CHECK) { com.openobd.ui.screens.VehicleCheckScreen(onBack = back, onConnect = toHome) }
                composable(Routes.VEHICLE) { VehicleInfoScreen(onBack = back, onConnect = toHome) }
                composable(Routes.FREEZE) { FreezeFrameScreen(onBack = back, onConnect = toHome) }
                composable(Routes.TESTS) { MonitorTestsScreen(onBack = back, onConnect = toHome) }
                composable(Routes.PERFORMANCE) { PerformanceScreen(onBack = back, onConnect = toHome) }
                composable(Routes.TRIP_LIVE) { TripScreen(onBack = back, onConnect = toHome, onHistory = { nav.navigate(Routes.TRIPS) }) }
                composable(Routes.LOGS) { LogsScreen(onBack = back, onOpen = { nav.navigate(Routes.log(it)) }) }
                composable(Routes.LOG) { e -> com.openobd.ui.screens.LogViewerScreen(e.arguments?.getString("name").orEmpty(), onBack = back) }
                composable(Routes.TERMINAL) { TerminalScreen(onBack = back) }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = back) }
                composable(Routes.HUD) { HudScreen(onBack = back) }
                composable(Routes.LOOKUP) { DtcLookupScreen(onBack = back) }
                composable(Routes.HISTORY) { HistoryScreen(onBack = back) }
                composable(Routes.ABOUT) { AboutScreen(onBack = back) }
                composable(Routes.GARAGE) { com.openobd.ui.screens.GarageScreen(onBack = back, onNavigate = { nav.navigate(it) }) }
                composable(Routes.FINDCAR) { e -> com.openobd.ui.screens.FindCarScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable(Routes.MONTH) { com.openobd.ui.screens.MonthlySummaryScreen(onBack = back) }
                composable(Routes.MAINTENANCE) { com.openobd.ui.screens.MaintenanceScreen(onBack = back) }
                composable(Routes.FUEL) { com.openobd.ui.screens.FuelLogScreen(onBack = back) }
                composable(Routes.TRIPS) { com.openobd.ui.screens.TripsScreen(onBack = back, onOpen = { nav.navigate(Routes.trip(it)) }) }
                composable(Routes.TRIP) { e ->
                    val id = e.arguments?.getString("id").orEmpty()
                    com.openobd.ui.screens.TripDetailScreen(id, onBack = back, onModel3d = { nav.navigate(Routes.trip3d(id)) })
                }
                composable(Routes.TRIP_3D) { e -> com.openobd.ui.screens.Route3dScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable(Routes.BRAND) { BrandScreen(onBack = back, onNavigate = { nav.navigate(it) }) }
                composable(Routes.PIDS) { CustomPidsScreen(onBack = back, onPacks = { nav.navigate(Routes.PACKS) }) }
                composable(Routes.MODULE) { e -> ModuleScreen(e.arguments?.getString("id").orEmpty(), onBack = back, onConnect = toHome) }
            }
        }
    }
    }
}
