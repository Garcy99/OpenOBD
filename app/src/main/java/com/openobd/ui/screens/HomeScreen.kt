package com.openobd.ui.screens

import com.openobd.ui.tr

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.garage.Maintenance
import com.openobd.core.health.HealthLevel
import com.openobd.core.obd.DtcType
import com.openobd.core.report.CarScore
import com.openobd.core.report.ScoreInput
import com.openobd.data.ConnectionType
import com.openobd.session.ConnectionState
import com.openobd.session.Op
import com.openobd.transport.DeviceScanner
import com.openobd.transport.FoundDevice
import com.openobd.ui.LocalApp
import com.openobd.ui.Permissions
import com.openobd.ui.Routes
import com.openobd.ui.SubscribeSensors
import com.openobd.ui.appSettings
import com.openobd.ui.components.ActionGrid
import com.openobd.ui.components.ActionTile
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.GradientButton
import com.openobd.ui.components.HeroCard
import com.openobd.ui.components.IconBadge
import com.openobd.ui.components.NoticeCard
import com.openobd.ui.components.ScoreRing
import com.openobd.ui.components.ScreenColumn
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.SectionLabel
import com.openobd.ui.components.SoftButton
import com.openobd.ui.components.StatTile
import com.openobd.ui.components.StatusChip
import com.openobd.ui.components.TabHeader
import com.openobd.ui.components.scoreColor
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Orange
import com.openobd.ui.theme.Violet
import com.openobd.ui.theme.Warn
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..19 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onTab: (String) -> Unit, onLive: (Int) -> Unit = {}) {
    val app = LocalApp.current
    val session = app.session
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsStateWithLifecycle()
    val settings = appSettings()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showAdapter by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf(false) }

    val needsBt = settings.connectionType == ConnectionType.BLUETOOTH || settings.connectionType == ConnectionType.BLE
    val ble = settings.connectionType == ConnectionType.BLE
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val btOk = Permissions.bluetooth(ble).all { result[it] ?: Permissions.granted(context, arrayOf(it)) }
        if (btOk || (pendingConnect && !needsBt)) {
            if (pendingConnect) session.connect() else showPicker = true
        } else {
            Toast.makeText(context, tr("Bluetooth permission is required to talk to the adapter"), Toast.LENGTH_LONG).show()
        }
        pendingConnect = false
    }

    fun connect() {
        // Ask for everything in one dialog sequence: Bluetooth (if needed), notifications, location for trip tracks.
        val wanted = (if (needsBt) Permissions.bluetooth(ble).toList() else emptyList()) + Permissions.notifications() +
            (if (settings.tripGps) Permissions.location().toList() else emptyList())
        val missing = wanted.filter { !Permissions.granted(context, arrayOf(it)) }
        if (missing.isNotEmpty()) {
            pendingConnect = true
            btLauncher.launch(missing.toTypedArray())
        } else session.connect()
    }

    fun pick() {
        if (!Permissions.granted(context, Permissions.bluetooth(ble))) btLauncher.launch(Permissions.bluetooth(ble)) else showPicker = true
    }

    LaunchedEffect(Unit) {
        if (settings.autoConnect && state is ConnectionState.Disconnected &&
            (settings.deviceAddress != null || !needsBt) && (!needsBt || Permissions.granted(context, Permissions.bluetooth(ble)))
        ) session.connect()
    }

    val car = garage.active
    Column(Modifier.fillMaxSize()) {
        TabHeader(car?.name ?: "OpenOBD", subtitle = tr(greeting()), actions = {
            IconButton(onClick = { onNavigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, tr("Settings")) }
        })
        ScreenColumn {
            CarHero(state, onConnect = ::connect, onAdapter = { showAdapter = true }, onNavigate = onNavigate)
            CarScoreCard(onDiagnose = { onTab(Routes.DIAGNOSTICS) })
            AttentionFeed(state, onNavigate, onTab, onStartSetup = { onNavigate(Routes.WELCOME) },
                onSkipSetup = { scope.launch { app.settings.update { it.copy(onboarded = true) } } })
            SectionLabel(tr("Quick actions"))
            val codes = ((session.dtcScan.collectAsStateWithLifecycle().value as? Op.Done)?.value?.codes?.distinctBy { it.code }?.size ?: 0)
            ActionGrid(4, listOf(
                { m -> ActionTile(Icons.Default.MedicalServices, tr("Scan codes"), m, badge = codes.takeIf { it > 0 }?.toString()) { onTab(Routes.DIAGNOSTICS) } },
                { m -> ActionTile(Icons.Default.Speed, tr("Gauges"), m) { onLive(LivePages.GAUGES) } },
                { m -> ActionTile(Icons.Default.Route, tr("Trip"), m, tint = Violet) { onLive(LivePages.TRIP) } },
                { m -> ActionTile(Icons.Default.FactCheck, tr("Car check"), m, tint = Good) { onNavigate(Routes.CHECK) } },
                { m -> ActionTile(Icons.Default.PhotoCamera, tr("Warning light"), m, tint = Orange) { onNavigate(Routes.LIGHTS) } },
                { m -> ActionTile(Icons.Default.BatteryChargingFull, tr("Battery test"), m, tint = Good) { onNavigate(Routes.BATTERY) } },
                { m -> ActionTile(Icons.Default.Hub, tr("Modules"), m, tint = Violet) { onNavigate(Routes.BRAND) } },
                { m -> ActionTile(Icons.Default.Fullscreen, tr("HUD"), m) { onNavigate(Routes.HUD) } },
            ))
            RecentTrip(onNavigate)
            if (state !is ConnectionState.Connected && garage.trips.isEmpty()) GettingStarted()
        }
    }

    if (showAdapter) AdapterSheet(
        state = state,
        onDismiss = { showAdapter = false },
        onPick = ::pick,
        onConnect = { showAdapter = false; connect() },
    )
    if (showPicker) {
        DevicePickerDialog(
            ble = settings.connectionType == ConnectionType.BLE,
            onDismiss = { showPicker = false },
            onPick = { d ->
                showPicker = false
                scope.launch {
                    app.settings.update {
                        it.copy(deviceAddress = d.address, deviceName = d.name,
                            connectionType = if (d.ble && it.connectionType == ConnectionType.BLE) ConnectionType.BLE else it.connectionType)
                    }
                }
            },
        )
    }
}

/** The car, its connection and the main call to action. */
@Composable
private fun CarHero(state: ConnectionState, onConnect: () -> Unit, onAdapter: () -> Unit, onNavigate: (String) -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val settings = appSettings()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val car = garage.active
    val connected = state is ConnectionState.Connected
    if (connected) SubscribeSensors("home", listOf("0105", "010C"))
    val readings by session.live.readings.collectAsStateWithLifecycle()
    val scan by session.dtcScan.collectAsStateWithLifecycle()

    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is ConnectionState.Connected -> StatusChip(tr("Connected"), Good)
                is ConnectionState.Connecting -> StatusChip(tr("Connecting…"), Warn)
                is ConnectionState.Failed -> StatusChip(tr("Connection failed"), Bad)
                ConnectionState.Disconnected -> StatusChip(tr("Not connected"), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAdapter).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(when (settings.connectionType) {
                    ConnectionType.WIFI -> Icons.Default.Wifi; ConnectionType.DEMO -> Icons.Default.SettingsInputAntenna
                    ConnectionType.USB -> Icons.Default.Usb; else -> Icons.Default.Bluetooth
                }, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    when (settings.connectionType) {
                        ConnectionType.DEMO -> tr("Demo car")
                        ConnectionType.WIFI -> settings.wifiHost
                        ConnectionType.USB -> tr("USB cable")
                        else -> settings.deviceName ?: tr("Choose adapter")
                    },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        val specs = car?.specs
        if (specs != null) {
            Text(listOfNotNull(specs.year?.toString(), specs.title.ifBlank { null }).joinToString(" "), style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(tr(specs.fuel.label), specs.engineLabel().ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (car != null) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onNavigate(Routes.findCar(car.id)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(tr("Identify your car (model & specs)"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        car?.odometerKm?.let { km ->
            Text(tr("Odometer ") + fmtKm(km, settings.units), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(18.dp))
        when (state) {
            is ConnectionState.Connected -> {
                val v = state.vehicle
                val codes = (scan as? Op.Done)?.value?.codes?.distinctBy { it.code }?.size
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(tr("Battery"), v.voltage?.let { "%.1f".format(it) } ?: "–", Modifier.weight(1f), unit = "V",
                        tint = v.voltage?.let { if (it < 11.8) Bad else if (it < 12.3) Warn else MaterialTheme.colorScheme.onSurface } ?: MaterialTheme.colorScheme.onSurface)
                    StatTile(tr("Coolant"), readings["0105"]?.value?.let { "%.0f".format(it) } ?: "–", Modifier.weight(1f), unit = "°C")
                    StatTile(tr("Codes"), codes?.toString() ?: "–", Modifier.weight(1f), tint = if ((codes ?: 0) > 0) Bad else Good)
                }
                Spacer(Modifier.height(14.dp))
                Text(listOf(v.elm.protocol.label, tr(v.transport)).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                SoftButton(tr("Disconnect"), session::disconnect, Modifier.fillMaxWidth(), icon = Icons.Default.PowerSettingsNew)
            }
            is ConnectionState.Connecting -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                Text(tr(state.step), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                SoftButton(tr("Cancel"), session::disconnect, Modifier.fillMaxWidth())
            }
            else -> {
                if (state is ConnectionState.Failed) ErrorText(state.message)
                val ready = settings.connectionType in setOf(ConnectionType.DEMO, ConnectionType.WIFI, ConnectionType.USB) || settings.deviceAddress != null
                if (ready) GradientButton(if (state is ConnectionState.Failed) tr("Retry") else tr("Connect"), onConnect, Modifier.fillMaxWidth(),
                    icon = Icons.Default.PowerSettingsNew)
                else GradientButton(tr("Choose adapter"), onAdapter, Modifier.fillMaxWidth(), icon = Icons.Default.Bluetooth)
            }
        }
        val bus by session.modules.bus.collectAsStateWithLifecycle()
        if (connected && bus != com.openobd.session.Bus.HS) {
            Text(tr("Adapter is on MS-CAN: live data is paused. Return to HS-CAN in Brand features."), color = Warn,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        }
    }
}


/** One number for the state of the car, with the main reasons. */
@Composable
private fun CarScoreCard(onDiagnose: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val scan by session.dtcScan.collectAsStateWithLifecycle()
    val modules by session.modules.moduleScan.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val readings by session.live.readings.collectAsStateWithLifecycle()
    val car = garage.active
    val health = remember(garage.health, car?.id) { session.health.report() }
    val now = System.currentTimeMillis()
    val result = remember(scan, modules, state, garage, health, readings["010C"]?.value?.let { it > 400 }) {
        val s = (scan as? Op.Done)?.value
        val v = (state as? ConnectionState.Connected)?.vehicle
        CarScore.compute(ScoreInput(
            milOn = s?.readiness?.milOn,
            storedCodes = s?.codes?.filter { it.type == DtcType.STORED }?.distinctBy { it.code }?.size,
            pendingCodes = s?.codes?.filter { it.type == DtcType.PENDING }?.distinctBy { it.code }?.size,
            permanentCodes = s?.codes?.filter { it.type == DtcType.PERMANENT }?.distinctBy { it.code }?.size,
            monitorsNotReady = s?.readiness?.incomplete?.size,
            moduleCodes = (modules as? Op.Done)?.value?.sumOf { it.dtcs.size },
            batteryV = v?.voltage,
            engineRunning = readings["010C"]?.value?.let { it > 400 },
            healthAct = if (health.samples >= 3) health.findings.count { it.level == HealthLevel.ACT } else 0,
            healthWatch = if (health.samples >= 3) health.findings.count { it.level == HealthLevel.WATCH } else 0,
            maintenanceOverdue = car?.let { c -> garage.maintenance.filter { it.vehicleId == c.id }.count { Maintenance.status(it, c.odometerKm, now).overdue } } ?: 0,
        ))
    }
    if (result.score == null) return
    SectionCard(Modifier.clickable(onClick = onDiagnose)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(result.score, size = 96.dp, stroke = 9.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Car score"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr(result.label), style = MaterialTheme.typography.titleLarge, color = scoreColor(result.score))
                Spacer(Modifier.height(4.dp))
                if (result.reasons.isEmpty()) Text(tr("No faults found. Keep it up!"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                result.reasons.take(3).forEach { r ->
                    Text("• " + tr(r.text, *r.args.toTypedArray()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Things that need the driver: codes, overdue service, the inspection date, a crash report, setup. */
@Composable
private fun AttentionFeed(state: ConnectionState, onNavigate: (String) -> Unit, onTab: (String) -> Unit, onStartSetup: () -> Unit, onSkipSetup: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val scan by app.session.dtcScan.collectAsStateWithLifecycle()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val crash by app.diagnostics.pendingCrash.collectAsStateWithLifecycle()
    val car = garage.active
    val now = System.currentTimeMillis()
    val notices = mutableListOf<@Composable () -> Unit>()

    if (crash != null) notices += {
        NoticeCard(Icons.Default.BugReport, tr("OpenOBD closed unexpectedly"),
            tr("Sharing the crash report helps fix the problem. It includes the error and the recent adapter traffic."), Bad,
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        scope.launch {
                            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                app.diagnostics.writeReport(app.session.reports.debugSections(), includeCrash = true)
                            }
                            com.openobd.ui.shareFile(context, f, "text/plain")
                            app.diagnostics.dismissCrash()
                        }
                    }) { Text(tr("Share report")) }
                    TextButton(onClick = { app.diagnostics.dismissCrash() }) { Text(tr("Dismiss")) }
                }
            })
    }
    if (!settings.onboarded && state !is ConnectionState.Connected) notices += {
        NoticeCard(Icons.Default.WavingHand, tr("Welcome to OpenOBD"), tr("A two-minute setup: your adapter, your car and what the app can do for it."),
            MaterialTheme.colorScheme.primary,
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onStartSetup) { Text(tr("Start setup")) }
                    TextButton(onClick = onSkipSetup) { Text(tr("Skip")) }
                }
            })
    }
    if (settings.onboarded && !settings.tourSeen) notices += {
        NoticeCard(Icons.Default.Explore, tr("Get to know OpenOBD"), tr("A short tour of the basics, and an optional full tour of everything the app can do."),
            MaterialTheme.colorScheme.primary,
            trailing = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onNavigate(Routes.tour(false)) }, modifier = Modifier.testTag("startTour")) { Text(tr("Take the tour")) }
                    TextButton(onClick = { scope.launch { app.settings.update { it.copy(tourSeen = true) } } }) { Text(tr("No, thanks")) }
                }
            })
    }
    (scan as? Op.Done)?.value?.let { s ->
        val codes = s.codes.distinctBy { it.code }
        if (s.readiness?.milOn == true || codes.isNotEmpty()) notices += {
            NoticeCard(Icons.Default.Error,
                if (s.readiness?.milOn == true) tr("Check engine light ON") else tr("Trouble codes present"),
                codes.take(3).joinToString(" · ") { it.code } + if (codes.size > 3) " …" else "", Bad) { onTab(Routes.DIAGNOSTICS) }
        }
    }
    if (car != null) {
        val due = garage.maintenance.filter { it.vehicleId == car.id }.map { it to Maintenance.status(it, car.odometerKm, now) }
            .filter { it.second.overdue || it.second.dueSoon }
        if (due.isNotEmpty()) notices += {
            val overdue = due.count { it.second.overdue }
            NoticeCard(Icons.Default.Build, if (overdue > 0) tr("Service overdue") else tr("Service due soon"),
                due.take(3).joinToString(" · ") { it.first.name }, if (overdue > 0) Bad else Warn) { onNavigate(Routes.MAINTENANCE) }
        }
        // Connected, but the car keeps its VIN to itself and nobody told us what it is: ask once, it unlocks brand features.
        val connected = state as? ConnectionState.Connected
        if (connected != null && connected.vehicle.vin == null && !connected.vehicle.isDemo && car.vin == null && car.specs == null && car.brandId == null) notices += {
            NoticeCard(Icons.Default.DirectionsCar, tr("Which car is this?"),
                tr("This car does not report its VIN (normal for many cars before 2005). Choose the make and model to get its brand features and specifications."),
                MaterialTheme.colorScheme.primary) { onNavigate(Routes.findCar(car.id)) }
        }
        Maintenance.nextInspection(car.specs, garage.maintenance.filter { it.vehicleId == car.id }, now, tr(Maintenance.INSPECTION)).let { itv ->
            if (itv != null && itv - now < 45L * 86_400_000) notices += {
                NoticeCard(Icons.Default.CalendarMonth, tr("Inspection due"), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(itv)), Warn) {
                    onNavigate(Routes.CHECK)
                }
            }
        }
        val health = app.session.health.report()
        if (health.samples >= 3 && health.overall == HealthLevel.ACT) notices += {
            NoticeCard(Icons.Default.MonitorHeart, tr("Car health needs attention"), tr(health.findings.maxBy { it.level }.text, *health.findings.maxBy { it.level }.args.toTypedArray()),
                Bad) { onNavigate(Routes.HEALTH) }
        }
    }
    if (notices.isEmpty()) return
    SectionLabel(tr("Needs your attention"))
    notices.forEach { it() }
}

@Composable
private fun RecentTrip(onNavigate: (String) -> Unit) {
    val app = LocalApp.current
    val settings = appSettings()
    val garage by app.garage.data.collectAsStateWithLifecycle()
    val t = garage.trips.filter { it.vehicleId == garage.active?.id }.maxByOrNull { it.start } ?: return
    SectionLabel(tr("Last trip"), action = tr("All trips"), onAction = { onNavigate(Routes.TRIPS) })
    SectionCard(Modifier.clickable { onNavigate(Routes.trip(t.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.Route, Violet, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.start)), style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(fmtKm(t.distanceKm, settings.units, 1), com.openobd.ui.components.formatDuration((t.end - t.start) / 1000.0),
                    t.avgL100?.let { fmtL100(it, settings.units) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            t.ecoScore?.let { ScoreRing(it, size = 48.dp, stroke = 5.dp) }
        }
    }
}

@Composable
private fun GettingStarted() {
    SectionLabel(tr("Getting started"))
    SectionCard {
        val steps = listOf(
            tr("Plug the ELM327 adapter into the OBD-II port (usually under the dashboard, near the steering column)."),
            tr("Turn the ignition ON (engine running is best for live data)."),
            tr("Bluetooth classic: pair the adapter in Android settings. BLE: just scan. Wi-Fi: join its network."),
            tr("Choose the adapter above and press Connect."),
        )
        steps.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(24.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(tr("No adapter? Choose Demo to try the app."), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Adapter type and device, in a sheet so the home screen stays clean. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdapterSheet(state: ConnectionState, onDismiss: () -> Unit, onPick: () -> Unit, onConnect: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val busy = state is ConnectionState.Connecting || state is ConnectionState.Connected
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("Adapter"), style = MaterialTheme.typography.headlineSmall)
            if (busy) Text(tr("Disconnect first to change the adapter."), style = MaterialTheme.typography.bodySmall, color = Warn)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ConnectionType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = settings.connectionType == t,
                        onClick = { scope.launch { app.settings.update { it.copy(connectionType = t) } } },
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(i, ConnectionType.entries.size),
                        label = { Text(if (t == ConnectionType.BLE) "BLE" else if (t == ConnectionType.BLUETOOTH) "BT" else tr(t.label), maxLines = 1) },
                    )
                }
            }
            when (settings.connectionType) {
                ConnectionType.BLUETOOTH, ConnectionType.BLE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Bluetooth)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(settings.deviceName ?: if (settings.deviceAddress != null) tr("Unnamed adapter") else tr("No adapter selected"), style = MaterialTheme.typography.titleSmall)
                            settings.deviceAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        SoftButton(tr("Choose"), onPick, enabled = !busy, icon = Icons.Default.SwapHoriz)
                    }
                    if (settings.connectionType == ConnectionType.BLUETOOTH) {
                        Text(tr("Classic adapters must be paired first in Android Bluetooth settings (PIN is usually 1234 or 0000)."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ConnectionType.WIFI -> {
                    var host by remember(settings.wifiHost) { mutableStateOf(settings.wifiHost) }
                    var port by remember(settings.wifiPort) { mutableStateOf(settings.wifiPort.toString()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it; scope.launch { app.settings.update { s -> s.copy(wifiHost = it.trim()) } } },
                            label = { Text(tr("IP address")) }, singleLine = true, modifier = Modifier.weight(2f), enabled = !busy)
                        OutlinedTextField(port, { v -> port = v.filter(Char::isDigit); port.toIntOrNull()?.let { p -> scope.launch { app.settings.update { s -> s.copy(wifiPort = p) } } } },
                            label = { Text(tr("Port")) }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text(tr("Join the adapter's Wi-Fi network (often \"WiFi_OBDII\" or \"V-LINK\") before connecting."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConnectionType.USB -> UsbCablePanel(busy)
                ConnectionType.DEMO -> Text(tr("Explore every feature with a simulated car: live data, trouble codes, freeze frames and more."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val ready = settings.connectionType in setOf(ConnectionType.DEMO, ConnectionType.WIFI, ConnectionType.USB) || settings.deviceAddress != null
            if (!busy) GradientButton(tr("Connect"), onConnect, Modifier.fillMaxWidth(), enabled = ready, icon = Icons.Default.PowerSettingsNew)
        }
    }
}

/** ELM327 USB cables plugged in (car head units), with the one in use highlighted. */
@Composable
private fun UsbCablePanel(busy: Boolean) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    var refresh by remember { mutableStateOf(0) }
    val cables = remember(refresh) { com.openobd.transport.UsbSerialTransport.find(context) }
    if (cables.isEmpty()) {
        Text(tr(com.openobd.transport.UsbSerialTransport.NO_CABLE), style = MaterialTheme.typography.bodyMedium)
    }
    cables.forEach { c ->
        val chosen = c.key == settings.usbDevice || (settings.usbDevice == null && c == cables.first())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !busy) { scope.launch { app.settings.update { it.copy(usbDevice = c.key, usbBaud = if (c.key == it.usbDevice) it.usbBaud else null) } } }
            .padding(vertical = 6.dp)) {
            IconBadge(Icons.Default.Usb)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                Text(listOfNotNull(c.chip, c.key, settings.usbBaud?.takeIf { chosen }?.let { "$it baud" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (chosen) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("For car head units: their Bluetooth often only talks to phones. The serial speed is detected automatically."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = { refresh++ }, enabled = !busy) { Text(tr("Search again")) }
    }
}

@Composable
private fun DevicePickerDialog(ble: Boolean, onDismiss: () -> Unit, onPick: (FoundDevice) -> Unit) {
    val context = LocalContext.current
    val devices = remember { mutableStateListOf<FoundDevice>() }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val btOn = DeviceScanner.bluetoothEnabled(context)
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(ble, attempt) {
        devices.clear()
        error = null
        if (!btOn) return@LaunchedEffect
        devices += DeviceScanner.bondedDevices(context).filter { !ble || it.ble }
        if (ble) {
            scanning = true
            try {
                kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    DeviceScanner.scanBle(context).collect { d ->
                        val idx = devices.indexOfFirst { it.address == d.address }
                        if (idx >= 0) devices[idx] = d else devices += d
                        devices.sortByDescending { it.looksLikeObd }
                    }
                }
            } catch (e: Exception) {
                error = e.message
            }
            scanning = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (ble) Icons.Default.BluetoothSearching else Icons.Default.Bluetooth, null) },
        title = { Text(if (ble) tr("Bluetooth LE adapters") else tr("Paired adapters")) },
        text = {
            Column {
                if (!btOn) {
                    Text(tr("Bluetooth is off. Turn it on and try again."))
                } else {
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    error?.let { ErrorText(it) }
                    if (ble && DeviceScanner.bleNeedsLocationOn(context)) Text(tr("On Android 11 and older, Bluetooth LE searching only works with Location turned on (quick settings)."),
                        style = MaterialTheme.typography.bodySmall, color = Warn)
                    if (devices.isEmpty() && !scanning) Text(if (ble) tr("No BLE devices found.") else tr("No paired devices. Pair your adapter in Bluetooth settings first."))
                    if (ble && !scanning) Text(tr("Most ELM327 adapters are Bluetooth Classic: if yours does not show up, pair it in Android's Bluetooth settings and choose BT instead."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(devices, key = { it.address }) { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Build, null, tint = if (d.looksLikeObd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name ?: tr("Unnamed device"), fontWeight = if (d.looksLikeObd) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(d.address + (d.rssi?.let { tr("  ·  %1\$s dBm", it) } ?: ""), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
        dismissButton = {
            if (!ble) TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text(tr("Pair new")) }
            else if (!scanning && btOn) TextButton(onClick = { attempt++ }) { Text(tr("Search again")) }
        },
    )
}
