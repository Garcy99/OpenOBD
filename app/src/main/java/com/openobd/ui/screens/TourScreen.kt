package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobd.ui.LocalApp
import com.openobd.ui.Routes
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Orange
import com.openobd.ui.theme.Violet
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.launch

/** One page of a tour: what a part of the app is for, and (optionally) a button that opens it. */
class TourPage(val icon: ImageVector, val tint: Color?, val section: String?, val title: String, val body: String, val route: String? = null)

/**
 * The tours: a basic one shown after the first setup (the essentials, a couple of minutes) and an optional full one
 * that goes through every feature, each with a button to open it. Both can be taken again from More.
 */
object Tours {
    val basic = listOf(
        TourPage(Icons.Default.DirectionsCar, null, null, "What OpenOBD does",
            "OpenOBD and an ordinary OBD2 adapter turn your phone into a diagnostic tool for your car. This short tour shows the essentials; the full tour explains every feature."),
        TourPage(Icons.Default.Bluetooth, null, null, "Plug in and connect",
            "Plug the adapter into the car's OBD port, under the dashboard near the steering column, and switch the ignition on. Then press Connect on Home.\n\nAlmost any OBD2 adapter works; one with a good ELM327 chip, or an OBDLink, works best. Bluetooth adapters are paired first in Android's Bluetooth settings. No adapter yet? Choose Demo in Settings to try everything with a simulated car.",
            Routes.SETTINGS),
        TourPage(Icons.Default.Home, null, null, "Home and the tabs",
            "Home shows your car at a glance: connection, warnings, the car's health and reminders. The tabs at the bottom take you everywhere: Home, Live, Diagnose, Garage and More."),
        TourPage(Icons.Default.Speed, Good, null, "Live data",
            "Live shows speed, revs, temperatures, boost and consumption as gauges while you drive. Touch a gauge to change what it shows, its style and its warning limit. Swipe for every sensor, the current trip and performance timers.",
            Routes.LIVE),
        TourPage(Icons.Default.Warning, Warn, null, "Trouble codes",
            "When a warning light comes on, open Diagnose and read the codes of the engine and every module: ABS, airbag, gearbox and more. Each code is explained: what it means, whether you can keep driving and what to check. Clear them once it is repaired.",
            Routes.DIAGNOSTICS),
        TourPage(Icons.Default.Garage, Violet, null, "Your garage",
            "The Garage keeps your cars, every trip with its route on a map, fuel fill-ups, maintenance and ITV reminders, and the car's health over time. Trips are recorded by themselves while you are connected.",
            Routes.GARAGE_HUB),
        TourPage(Icons.Default.Share, Orange, null, "Share and print your routes",
            "Open a trip and press Share to make a GPX for any GPS, an interactive web map, a poster to print or a colour 3D map for your 3D printer."),
        TourPage(Icons.Default.CloudSync, null, null, "Your data",
            "Everything stays on your phone. If you want, connect your Google account to keep the same data on all your phones through your own Google Drive, or save a backup file.",
            Routes.ACCOUNT),
    )

    val advanced = listOf(
        TourPage(Icons.Default.Bluetooth, null, "Connection", "Adapters and connection",
            "OpenOBD works with the usual OBD2 adapters over Bluetooth, Bluetooth LE, Wi-Fi and USB cable (handy on car head units). Almost all of them speak the ELM327 language; a good ELM327 or an OBDLink works best. Adapters tied to their own app, such as Carista or BlueDriver, do not work. Choose the type and the adapter in Settings → Connection.\n\nOBDLink and other STN adapters are detected and run faster. Settings also has a speed test, the protocol and timing options for cars that answer slowly, and the HS/MS-CAN switch that some Ford, Mazda and Opel adapters use to reach comfort modules.\n\nDemo mode simulates a car, faults included, to practise without one.",
            Routes.SETTINGS),
        TourPage(Icons.Default.Speed, Good, "Live data", "Gauges your way",
            "In Live → Gauges, touch any gauge to choose its sensor, style (arc, needle, bar or number), colours, range, decimals and the value at which it turns red; the arrows move it. Dashboard layouts change the whole set at once.\n\nThe button at the top opens a floating window that keeps the gauges visible over other apps, such as your navigation app.",
            Routes.LIVE),
        TourPage(Icons.Default.ShowChart, Good, "Live data", "Every sensor, charts and recording",
            "Live → Sensors lists every value your car reports, including manufacturer values added with PIDs or packs. Charts plot several values over time to spot one that misbehaves.\n\nPress Record to save the session as a CSV file with every value; find the files in More → Data logs to open them in a spreadsheet or share them.",
            Routes.CHART),
        TourPage(Icons.Default.NotificationsActive, Good, "Live data", "Alerts and voice",
            "In Settings → Alerts, set limits such as coolant above 105 °C: you get a warning on screen and, with voice alerts on (Settings → Voice), spoken out loud, so you never have to look at the phone while driving.",
            Routes.SETTINGS),
        TourPage(Icons.Default.Timer, Good, "Live data", "Performance and head-up display",
            "Live → Performance times 0-100 km/h, 80-120 km/h, 0-200 km/h and 1/8 or 1/4 mile using the car's own speed.\n\nThe head-up display (More) mirrors big numbers so you can place the phone under the windscreen and read them on the glass at night.",
            Routes.PERFORMANCE),
        TourPage(Icons.Default.Map, Violet, "Trips", "Trips and eco score",
            "While connected, every drive is saved as a trip: distance, time, consumption, speeds and, with GPS on, its route. The eco score rates smooth driving: harsh acceleration and braking, high revs and long idling.\n\nOpen a trip and touch any point of its route to see what the car was doing there. Give it a name: it becomes the title of its exports.",
            Routes.TRIPS),
        TourPage(Icons.Default.Print, Orange, "Trips", "GPX, web map and poster",
            "From a trip, Share makes:\n• a GPX file for Garmin, OsmAnd, Komoot or Google Earth;\n• an interactive web page anyone can open, with the car's data at every point and the relief built in;\n• an A3 poster (PDF or SVG) with the route over the relief coloured by altitude."),
        TourPage(Icons.Default.ViewInAr, Orange, "Trips", "3D maps to print",
            "The 3D map turns the terrain around the route into a model: colours by altitude, lakes, roads and buildings, a hexagon, circle or square, the title and coordinates. Turn it around in the preview before saving.\n\nChoose all colours, 6 or 4 filaments, or mix five spools on the surface (CMYKW or Landscape). Every part comes painted for Bambu Studio, PrusaSlicer and Orca, and the app lists which colour goes with each filament number."),
        TourPage(Icons.Default.Warning, Warn, "Diagnose", "Codes in every module",
            "Diagnose reads the codes of the engine and every module the car has, with manufacturer codes for many brands. Each code shows whether it is active, pending or stored, the freeze frame captured when it appeared and, when it helps, the likely causes ranked with your car's own data.",
            Routes.DIAGNOSTICS),
        TourPage(Icons.Default.Checklist, Warn, "Diagnose", "Inspection (ITV) and used cars",
            "Emission readiness shows whether the car's self-tests are complete, as the emissions inspection requires. The used car check gathers codes, readiness, mileage clues and signs of recently cleared codes into a PDF report: very useful before buying a car.",
            Routes.CHECK),
        TourPage(Icons.Default.Science, Warn, "Diagnose", "Deeper tests",
            "Under Diagnose you also find the on-board monitor tests (Mode 06: catalyst, oxygen sensors, misfires per cylinder, EVAP) and vehicle information (VIN, calibration IDs, module names).",
            Routes.TESTS),
        TourPage(Icons.Default.PhotoCamera, Warn, "Diagnose", "Code lookup, history and warning lights",
            "The code lookup and the scan history work offline, so you can see when a code came back. Don't know a dashboard light? Take a photo and the AI assistant tells you which lights are on and what to do.",
            Routes.LOOKUP),
        TourPage(Icons.Default.SmartToy, Warn, "Diagnose", "AI assistant (optional)",
            "With your own Anthropic API key (Settings → AI assistant) you can ask about a code or a warning light, with your car's data as context. It is off by default and nothing is sent until you use it.",
            Routes.SETTINGS),
        TourPage(Icons.Default.DirectionsCar, Violet, "Garage", "Cars and specifications",
            "Add every car you drive. OpenOBD reads the VIN when it connects; Model & specifications finds the exact version, official consumption and environmental label from public registries.",
            Routes.GARAGE_HUB),
        TourPage(Icons.Default.LocalGasStation, Violet, "Garage", "Fuel, maintenance and ITV",
            "Log fill-ups to know your real consumption and cost; the trip estimate learns from them. Maintenance reminders warn by kilometres or date: oil, timing belt, tyres, ITV and anything you add.",
            Routes.MAINTENANCE),
        TourPage(Icons.Default.MonitorHeart, Violet, "Garage", "Car health and battery",
            "Car health follows battery voltage, fuel trims, warm-up time and more over months, so slow problems show early. The battery & starting test measures how the battery copes when the engine starts.",
            Routes.HEALTH),
        TourPage(Icons.Default.Hub, null, "For your car", "Brand features and modules",
            "Brand features scans the modules of your make (ABS, airbag, body, gearbox, EV battery…) with the brand's own codes and values.",
            Routes.BRAND),
        TourPage(Icons.Default.Inventory2, null, "For your car", "Custom PIDs, packs and community data",
            "Add manufacturer values yourself or import a Torque CSV list. Share them as packs or download packs for your car. Community data (OBDb) brings ready-made values for many makes and models: battery, DPF, tyres, gearbox…",
            Routes.PACKS),
        TourPage(Icons.Default.Build, null, "For your car", "Codings and adaptations",
            "Codings change module settings, such as enabling a comfort feature. Before every change OpenOBD reads and saves the current value, writes, checks, and puts it back if anything fails; every change can be undone. Risky ones explain the risk and ask twice.",
            Routes.CODINGS),
        TourPage(Icons.Default.LocalFireDepartment, null, "For your car", "Diesel filter and service guides",
            "The DPF helper guides a regeneration drive with live conditions and filter values. Service guides walk you through the oil service reset, what to do after a battery change and the drive cycle before the ITV.",
            Routes.DIESEL),
        TourPage(Icons.Default.Terminal, null, "For your car", "Expert tools",
            "The identifier explorer discovers and watches manufacturer data identifiers, and the terminal sends raw commands to the adapter. They are for people who know what they are doing.",
            Routes.EXPLORER),
        TourPage(Icons.Default.CloudSync, null, "The app", "Account, sync and backup",
            "Your data stays on your phone. Connect your Google account to sync cars, trips with their routes, maintenance and history across your phones through a private folder of your own Google Drive; no OpenOBD server is involved. You can also save a backup file and restore it on another phone.",
            Routes.ACCOUNT),
        TourPage(Icons.Default.Widgets, null, "The app", "Widget, settings and help",
            "Add the OpenOBD widget to your home screen to see your car at a glance. In Settings choose units, theme and language. If something does not work with your car or adapter, share a debug report from Settings → Troubleshooting.",
            Routes.SETTINGS),
    )

    /** Every phrase, for translation coverage. */
    val texts: List<String> get() = (basic + advanced).flatMap { listOfNotNull(it.section, it.title, it.body) } + listOf(
        "App tour", "Full tour", "You're ready", "That's the basics. The full tour goes through every feature, one by one, with a button to open each. You can take either tour again from More.",
        "Take the full tour", "Start using OpenOBD", "You've seen everything", "That's all of OpenOBD. Take the tours again any time from More. Enjoy the drive!",
        "Open", "Close", "Next", "Back", "Finish", "%1\$s of %2\$s",
        "Get to know OpenOBD", "A short tour of the basics, and an optional full tour of everything the app can do.", "Take the tour", "No, thanks",
        "The basics in two minutes", "Every feature, step by step", "Google account and sync", "Optional: the same data on all your phones", "Synced as %1\$s",
    )
}

/**
 * Shows a tour page by page. [onOpen] opens a feature (the tour stays underneath, so Back returns to the same page);
 * [onFullTour] starts the full tour from the end of the basic one.
 */
@Composable
fun TourScreen(advanced: Boolean, onClose: () -> Unit, onOpen: (String) -> Unit, onFullTour: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val pages = if (advanced) Tours.advanced else Tours.basic
    val total = pages.size + 1 // plus the closing page
    val pager = rememberPagerState { total }
    LaunchedEffect(Unit) { app.settings.update { it.copy(tourSeen = true) } }
    fun go(page: Int) { scope.launch { pager.animateScrollToPage(page) } }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (advanced) tr("Full tour") else tr("App tour"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(tr("%1\$s of %2\$s", pager.currentPage + 1, total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClose, modifier = Modifier.testTag("tourClose")) { Text(tr("Close")) }
        }
        // Progress: a thin bar rather than 25 dots.
        Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
            Box(Modifier.fillMaxWidth((pager.currentPage + 1f) / total).height(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("tourPager")) { i ->
            val p = pages.getOrNull(i)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val tint = p?.tint ?: MaterialTheme.colorScheme.primary
                Box(Modifier.size(84.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(p?.icon ?: if (advanced) Icons.Default.EmojiEvents else Icons.Default.Explore, null, Modifier.size(44.dp), tint = tint)
                }
                p?.section?.let { Text(tr(it).uppercase(), style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold) }
                if (p != null) {
                    Text(tr(p.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr(p.body), style = MaterialTheme.typography.bodyLarge)
                    p.route?.let { r -> OutlinedButton(onClick = { onOpen(r) }, modifier = Modifier.testTag("tourOpen")) { Text(tr("Open")) } }
                } else if (!advanced) {
                    Text(tr("You're ready"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr("That's the basics. The full tour goes through every feature, one by one, with a button to open each. You can take either tour again from More."),
                        style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onFullTour, modifier = Modifier.fillMaxWidth().testTag("fullTour")) { Text(tr("Take the full tour")) }
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(tr("Start using OpenOBD")) }
                } else {
                    Text(tr("You've seen everything"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr("That's all of OpenOBD. Take the tours again any time from More. Enjoy the drive!"), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (pager.currentPage > 0) TextButton(onClick = { go(pager.currentPage - 1) }) { Text(tr("Back")) }
            Spacer(Modifier.weight(1f))
            if (pager.currentPage < total - 1) Button(onClick = { go(pager.currentPage + 1) }, modifier = Modifier.testTag("tourNext")) { Text(tr("Next")) }
            else Button(onClick = onClose, modifier = Modifier.testTag("tourFinish")) { Text(tr("Finish")) }
        }
    }
}

/** Settings → Google account and sync, on its own (from More). */
@Composable
fun AccountScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        com.openobd.ui.components.ScreenTopBar(tr("Google account and sync"), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            com.openobd.ui.components.SectionCard { SyncSection() }
            com.openobd.ui.components.SectionCard(title = tr("Backup")) { BackupSection() }
        }
    }
}
