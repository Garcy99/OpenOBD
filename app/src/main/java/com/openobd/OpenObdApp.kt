package com.openobd

import android.app.Application
import android.content.Intent
import com.openobd.core.dtc.DtcDatabase
import com.openobd.data.GarageRepository
import com.openobd.data.HistoryRepository
import com.openobd.data.LogRepository
import com.openobd.data.SettingsRepository
import com.openobd.service.ObdService
import com.openobd.session.ConnectionState
import com.openobd.session.ObdSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Simple manual dependency container. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app, scope)
    val history = HistoryRepository(app)
    val logs = LogRepository(app)
    val garage = GarageRepository(app)
    val diagnostics = com.openobd.data.Diagnostics(app)
    /** Creates the AI assistant backend for an API key; replaceable in tests. */
    var assistantBackend: (String) -> com.openobd.data.ChatBackend = { key -> com.openobd.data.ClaudeBackend(key) }
    /** Network client for vehicle lookups; replaceable in tests. */
    var http: com.openobd.core.specs.Http = com.openobd.core.specs.UrlHttp("OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)")
    /** Elevation around a route for 3D models and posters ([Int]: detail in pixels across the area); replaceable in tests. */
    var terrain: suspend (List<com.openobd.core.garage.TrackPoint>, Int, (Int, Int) -> Unit) -> com.openobd.core.trip.Terrain? = { pts, detail, progress ->
        com.openobd.data.TerrainDownloader(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)").around(pts, detail, progress)
    }
    /** Refuels the tank gauge noticed, waiting to be confirmed in the fuel log. */
    val refuelSuggestions = com.openobd.data.RefuelSuggestions(app)
    /** Pump prices around the car (Spain). */
    val fuelPrices = com.openobd.data.FuelPriceRepository(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)")
    /** Aerial photos of an area (minLat, minLon, maxLat, maxLon) at about the given metres per pixel; replaceable in tests. */
    var imagery: suspend (DoubleArray, Double, (Int, Int) -> Unit) -> com.openobd.core.trip.TileImagery? = { area, mpp, progress ->
        com.openobd.data.ImageryDownloader(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)").around(area, mpp, progress)
    }
    /** Lakes from OpenStreetMap for an area (minLat, minLon, maxLat, maxLon); replaceable in tests. */
    var water: suspend (DoubleArray) -> List<List<com.openobd.core.trip.LatLon>>? = { area ->
        com.openobd.data.MapDataDownloader(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)").water(area)
    }
    /** Buildings, woods, trees and water from OpenStreetMap in squares along a route (for the drone video); replaceable in tests. */
    var scene: suspend (List<DoubleArray>, (Int, Int) -> Unit) -> com.openobd.core.trip.SceneFeatures? = { boxes, progress ->
        com.openobd.data.MapDataDownloader(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)").scene(boxes, progress)
    }
    /** Water, roads and buildings from OpenStreetMap for an area (minLat, minLon, maxLat, maxLon); replaceable in tests. */
    var mapFeatures: suspend (DoubleArray, Boolean) -> com.openobd.core.trip.MapFeatures? = { area, buildings ->
        com.openobd.data.MapDataDownloader(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)").around(area, buildings)
    }
    val dtcDatabase: DtcDatabase by lazy {
        DtcDatabase(
            runCatching { app.assets.open("dtc/generic.tsv") }.getOrNull(),
            runCatching { app.assets.open("dtc/causes.tsv") }.getOrNull(),
            runCatching { app.assets.open("dtc/generic.es.tsv") }.getOrNull(),
        ).also { it.useLocalized = com.openobd.ui.I18n.language == "es" }
    }
    private val backupJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Writes a backup of all user data (API keys excluded). Returns the number of files written. */
    suspend fun backupTo(out: java.io.OutputStream): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val s = settings.settings.value.copy(claudeApiKey = "", dvlaApiKey = "")
        com.openobd.data.Backup.write(out, app.filesDir, backupJson.encodeToString(com.openobd.data.AppSettings.serializer(), s), com.openobd.BuildConfig.VERSION_NAME)
    }

    /** Restores a backup, keeping this phone's API keys. Returns the number of files restored. */
    suspend fun restoreFrom(input: java.io.InputStream): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val c = com.openobd.data.Backup.read(input, app.filesDir)
        c.settingsJson?.let { j -> runCatching { backupJson.decodeFromString(com.openobd.data.AppSettings.serializer(), j) }.getOrNull() }?.let { r ->
            settings.update { cur -> r.copy(claudeApiKey = cur.claudeApiKey, dvlaApiKey = cur.dvlaApiKey) }
        }
        garage.load(null)
        history.load()
        logs.refresh()
        c.files
    }

    /** Sync with the user's own Google Drive (off until they connect). */
    val sync = com.openobd.data.GoogleSync(app, garage, history, settings)

    val session: ObdSession by lazy {
        ObdSession(app, scope, settings, history, logs, dtcDatabase, garage, diagnostics.log).also { s ->
            // While the phone lends its adapter to the car screen, it cannot use it itself.
            s.blocker = { st -> if (bridge.running && st.connectionType != com.openobd.data.ConnectionType.PHONE) com.openobd.session.PHONE_BRIDGE_BUSY else null }
            // Trips cost what fuel costs where they are driven (Spain), unless turned off in Settings.
            s.fuelPriceAt = { lat, lon ->
                val st = settings.settings.value
                if (!st.fuelPrices) null else {
                    val (la, lo) = if (lat != null && lon != null) lat to lon
                        else com.openobd.data.CurrentLocation.get(app)?.let { it.latitude to it.longitude } ?: (null to null)
                    if (la == null || lo == null) null
                    else fuelPrices.localPrice(la, lo, com.openobd.data.FuelPriceRepository.fuelFor(st, s.diesel.isDiesel))
                }
            }
        }
    }

    /** A screen a notification asked to open (taken by the navigation once shown). */
    val openRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Where the car was left at the end of its last trip. */
    val parking = com.openobd.data.Parking(app)

    /** Oil and air filter life left, as the car's own computer counts them (GM / Opel). */
    val serviceLife = com.openobd.data.ServiceLife(app)

    /** New versions from GitHub, installed from inside the app. */
    val updater = com.openobd.data.Updater(app)

    /** Lends this phone's adapter to the car's head unit over Wi-Fi. */
    val bridge: com.openobd.data.PhoneBridge by lazy { com.openobd.data.PhoneBridge(app, scope, session, settings) }
}

class OpenObdApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.diagnostics.installCrashHandler()
        com.openobd.ui.I18n.load(this, "system")
        container.scope.launch {
            container.garage.load(container.settings.readOnce())
            container.history.load()
            container.logs.refresh()
            container.dtcDatabase.size // warm up off the main thread
            // Once: the oil temperature and pressure gauges join dashboards made before they existed.
            container.settings.readOnce().takeIf { !it.oilGaugesAdded }?.let {
                container.settings.update { s ->
                    val missing = listOf("OIL_TEMP", "OIL_PRESS").filter { k -> s.dashboard.none { com.openobd.ui.components.GaugeSpec.parse(it).key == k } }
                    s.copy(dashboard = s.dashboard + missing, oilGaugesAdded = true)
                }
            }
            // Once: the fuel tank gauge joins dashboards made before it existed.
            container.settings.readOnce().takeIf { !it.tankGaugeAdded }?.let {
                container.settings.update { s ->
                    s.copy(dashboard = if (s.dashboard.none { com.openobd.ui.components.GaugeSpec.parse(it).key == "CALC_TANK" }) s.dashboard + "CALC_TANK" else s.dashboard,
                        tankGaugeAdded = true)
                }
            }
            container.sync.syncIfConnected(force = true)
        }
        keepWidgetUpdated()
        // A trip left behind when the app was closed mid-drive is saved as it was at its last checkpoint.
        container.scope.launch {
            while (!container.garage.loaded) kotlinx.coroutines.delay(100)
            runCatching { container.session.recoverTrip() }
        }
        notifySavedTrips()
        proposeRefuels()
        notifyRegenerations()
        rememberParking()
        readServiceLife()
        notifyMonthlySummary()
        speakAlerts()
        // Keep a foreground service alive while connected so logging continues with the screen off.
        container.scope.launch {
            var wasConnected = false
            container.session.state.collect { s ->
                val connected = s is ConnectionState.Connected
                if (connected && !wasConnected) startService()
                // Not stopped here when the link goes: the service stops itself once nothing is left to do, and keeps the
                // app alive meanwhile while it reconnects or a trip waits to be saved (else Android may close the app
                // with the phone locked and the trip is lost).
                wasConnected = connected
            }
        }
    }

    /** After an automatic trip (or any trip while the app is in the background): what it was, in a notification. */
    private fun notifySavedTrips() {
        container.scope.launch {
            container.session.savedTrips.collect { t ->
                // Every saved trip is told (automatic or not), so it is clear it was not lost.
                val nm = getSystemService(android.app.NotificationManager::class.java) ?: return@collect
                if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                    android.app.NotificationChannel("trips", com.openobd.ui.tr("Trip saved"), android.app.NotificationManager.IMPORTANCE_DEFAULT))
                val text = listOfNotNull("%.1f km".format(t.distanceKm), com.openobd.ui.components.formatDuration((t.end - t.start) / 1000.0),
                    t.fuelL?.let { "%.2f L".format(it) }, t.cost?.let { "%.2f €".format(it) }, t.ecoScore?.let { "Eco $it" }).joinToString(" · ")
                val open = android.app.PendingIntent.getActivity(this@OpenObdApp, 2, Intent(this@OpenObdApp, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE)
                runCatching {
                    nm.notify(t.id.hashCode(), androidx.core.app.NotificationCompat.Builder(this@OpenObdApp, "trips").setSmallIcon(R.drawable.ic_stat_car)
                        .setContentTitle(com.openobd.ui.tr("Trip saved")).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
                }
            }
        }
    }

    /**
     * A refuel the tank gauge noticed becomes a fill-up ready to confirm: the litres added, whether it was filled up,
     * the odometer and the price at the station the car is at. A notification takes the driver to it.
     */
    private fun proposeRefuels() {
        container.scope.launch {
            container.session.refuels.collect { r ->
                if (com.openobd.data.RefuelSuggestions.alreadyLogged(container.garage.data.value.fuel, r.vehicleId, r.at)) return@collect
                val st = container.settings.settings.value
                // The price where the car is (it is still at the pump): the station's, else the usual one around.
                val price: Pair<Double, String>? = if (!st.fuelPrices) null else runCatching {
                    val here = com.openobd.data.CurrentLocation.get(this@OpenObdApp, maxAgeMs = 5 * 60_000L, timeoutMs = 15_000L) ?: return@runCatching null
                    val fuel = com.openobd.data.FuelPriceRepository.fuelFor(st, container.session.diesel.isDiesel)
                    val list = container.fuelPrices.around(here.latitude, here.longitude, 5.0) ?: return@runCatching null
                    com.openobd.core.fuel.FuelPricesEs.near(list.stations, here.latitude, here.longitude, fuel, 5.0).firstOrNull()?.takeIf { it.km < 0.3 }
                        ?.let { s -> s.price(fuel)!! to s.station.brand }
                        ?: com.openobd.core.fuel.FuelPricesEs.localPrice(list.stations, here.latitude, here.longitude, fuel)?.let { it to "" }
                }.getOrNull()
                val s = com.openobd.data.RefuelSuggestions.Suggestion(r.vehicleId, r.at, r.litres, r.full, r.odometerKm,
                    price?.first ?: st.fuelPricePerL.toDouble().takeIf { it > 0 }, price?.second.orEmpty())
                container.refuelSuggestions.set(s)
                val nm = getSystemService(android.app.NotificationManager::class.java) ?: return@collect
                if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                    android.app.NotificationChannel("trips", com.openobd.ui.tr("Trip saved"), android.app.NotificationManager.IMPORTANCE_DEFAULT))
                val open = android.app.PendingIntent.getActivity(this@OpenObdApp, 4,
                    Intent(this@OpenObdApp, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, com.openobd.ui.Routes.FUEL)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                val text = com.openobd.ui.tr(com.openobd.data.RefuelSuggestions.NOTICE, "%.0f".format(r.litres)) +
                    (s.pricePerL?.let { " · " + "%.3f €/L".format(it) + if (s.station.isNotEmpty()) " (${s.station})" else "" } ?: "")
                runCatching {
                    nm.notify(REFUEL_NOTIFICATION, androidx.core.app.NotificationCompat.Builder(this@OpenObdApp, "trips").setSmallIcon(R.drawable.ic_stat_car)
                        .setContentTitle(com.openobd.ui.tr("Refuel detected")).setContentText(text)
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
                }
            }
        }
    }

    /** Refreshes the home-screen widget whenever the car, scan results or garage data change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun keepWidgetUpdated() {
        val c = container
        container.scope.launch {
            kotlinx.coroutines.flow.combine(c.session.state, c.session.dtcScan, c.garage.data, c.history.records) { _, _, _, _ -> }
                .debounce(1000)
                .collect { runCatching { com.openobd.widget.CarWidget.updateAll(this@OpenObdApp, c) } }
        }
    }

    /**
     * The particulate filter regenerating: a lasting notification while it does (keep the engine running), then
     * whether it finished or was cut short. Each one is remembered for the diesel screen.
     */
    private fun notifyRegenerations() {
        // The filter's load as the car reports it, kept for Home while not connected (when it changes by a point).
        container.scope.launch {
            var last = -1
            container.session.diesel.regen.collect { st ->
                val pct = st?.loadPct ?: return@collect
                if (pct.toInt() != last) { last = pct.toInt(); com.openobd.data.RegenHistory(this@OpenObdApp).saveLoad(pct) }
            }
        }
        container.scope.launch {
            container.session.diesel.regenEvents.collect { e ->
                com.openobd.data.RegenHistory(this@OpenObdApp).record(e)
                val nm = getSystemService(android.app.NotificationManager::class.java) ?: return@collect
                if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                    android.app.NotificationChannel("regen", com.openobd.ui.tr("Particulate filter"), android.app.NotificationManager.IMPORTANCE_HIGH))
                val open = android.app.PendingIntent.getActivity(this@OpenObdApp, 3, Intent(this@OpenObdApp, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN, com.openobd.ui.Routes.DIESEL), android.app.PendingIntent.FLAG_IMMUTABLE)
                val (title, text, ongoing) = when (e) {
                    is com.openobd.core.diesel.RegenWatch.Event.Started -> Triple(com.openobd.ui.tr("Filter regenerating"),
                        com.openobd.ui.tr("Keep the engine running and, if you can, drive on at a steady pace until it ends."), true)
                    is com.openobd.core.diesel.RegenWatch.Event.Ended -> Triple(com.openobd.ui.tr("Filter regeneration finished"),
                        com.openobd.ui.tr("It took %1\$s.", com.openobd.ui.components.formatDuration(e.seconds.toDouble())), false)
                    is com.openobd.core.diesel.RegenWatch.Event.Interrupted -> Triple(com.openobd.ui.tr("Filter regeneration interrupted"),
                        com.openobd.ui.tr("The engine stopped after %1\$s. Plan a steady 15-20 minute drive soon so the filter can finish cleaning itself.",
                            com.openobd.ui.components.formatDuration(e.seconds.toDouble())), false)
                }
                runCatching {
                    nm.notify(REGEN_NOTIFICATION, androidx.core.app.NotificationCompat.Builder(this@OpenObdApp, "regen").setSmallIcon(R.drawable.ic_stat_car)
                        .setContentTitle(title).setContentText(text)
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                        .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(false).setContentIntent(open).build())
                }
            }
        }
    }

    /** At the start of a month (the first time the app runs in it): last month's figures in a notification. */
    private fun notifyMonthlySummary() {
        container.scope.launch {
            kotlinx.coroutines.delay(5_000)
            val prefs = getSharedPreferences("summary", MODE_PRIVATE)
            val now = java.util.Calendar.getInstance()
            val key = now.get(java.util.Calendar.YEAR) * 12 + now.get(java.util.Calendar.MONTH)
            if (prefs.getInt("notified", 0) >= key) return@launch
            prefs.edit().putInt("notified", key).apply()
            val car = container.garage.data.value.active ?: return@launch
            val (py, pm) = com.openobd.core.garage.MonthlySummary.previousMonth(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH))
            val g = container.garage.data.value
            val s = com.openobd.core.garage.MonthlySummary.of(py, pm, g.trips.filter { it.vehicleId == car.id }, g.fuel.filter { it.vehicleId == car.id },
                diesel = car.specs?.fuel?.burnsDiesel == true, lpg = car.specs?.fuel == com.openobd.core.specs.SpecFuel.LPG,
                officialL100 = car.specs?.consumptionL100, fallbackPrice = container.settings.settings.value.fuelPricePerL.toDouble())
            if (s.trips == 0) return@launch
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return@launch
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                android.app.NotificationChannel("summary", com.openobd.ui.tr("Monthly summary"), android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val text = listOfNotNull("%.0f km".format(s.km), s.fuelL?.let { "%.1f L".format(it) }, s.cost?.let { "%.2f €".format(it) },
                s.co2Kg?.let { "%.0f kg CO₂".format(it) }).joinToString(" · ")
            val open = android.app.PendingIntent.getActivity(this@OpenObdApp, 4, Intent(this@OpenObdApp, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_OPEN, com.openobd.ui.Routes.MONTH), android.app.PendingIntent.FLAG_IMMUTABLE)
            runCatching {
                nm.notify(78, androidx.core.app.NotificationCompat.Builder(this@OpenObdApp, "summary").setSmallIcon(R.drawable.ic_stat_car)
                    .setContentTitle(com.openobd.ui.tr("Your %1\$s", com.openobd.ui.screens.monthName(py, pm))).setContentText(text)
                    .setContentIntent(open).setAutoCancel(true).build())
            }
        }
    }

    /** Once per connection to a GM / Opel car: how much oil and air filter life its computer says is left. */
    private fun readServiceLife() {
        container.scope.launch {
            var last: ConnectionState? = null
            container.session.state.collect { st ->
                val fresh = st is ConnectionState.Connected && last !is ConnectionState.Connected
                last = st
                if (!fresh) return@collect
                val pids = container.session.brand.value?.family?.builtinPids.orEmpty().filter { it in com.openobd.core.brand.GM_SERVICE_PIDS }
                val car = container.garage.data.value.active ?: return@collect
                if (pids.isEmpty()) return@collect
                container.scope.launch {
                    // After the first live values, so it does not hold up the gauges.
                    kotlinx.coroutines.delay(8_000)
                    val v = runCatching { container.session.modules.readCustomValues(pids) }.getOrNull() ?: return@launch
                    val oil = (v["X_gm_oil_life"] ?: v["X_gm_oil_life_b"])?.takeIf { it in 0.0..100.0 }
                    val air = v["X_gm_air_filter"]?.takeIf { it in 0.0..100.0 }
                    container.serviceLife.save(com.openobd.data.ServiceLife.Reading(car.id, oil, air, System.currentTimeMillis()))
                }
            }
        }
    }

    /** Each saved trip leaves the car where its GPS track ends (or where the phone is then, without a track). */
    private fun rememberParking() {
        container.scope.launch {
            container.session.savedTrips.collect { t ->
                val end = runCatching { container.garage.loadTrack(t.id).lastOrNull() }.getOrNull()
                val at = end?.let { it.lat to it.lon }
                    ?: runCatching { com.openobd.data.CurrentLocation.get(this@OpenObdApp, maxAgeMs = 3 * 60_000L, timeoutMs = 15_000L) }.getOrNull()?.let { it.latitude to it.longitude }
                    ?: return@collect
                container.parking.set(com.openobd.data.Parking.Spot(at.first, at.second, t.end, t.vehicleId))
            }
        }
    }

    /** Speaks the session's voice alerts in the app's language. */
    private fun speakAlerts() {
        val speaker = com.openobd.data.Speaker(this)
        container.scope.launch {
            container.session.voice.announcements.collect { a ->
                val text = com.openobd.ui.tr(a.text, *a.args.map { com.openobd.ui.tr(it) }.toTypedArray())
                if (container.settings.settings.value.voiceAlerts) {
                    speaker.language = com.openobd.ui.I18n.language
                    speaker.speak(text)
                }
                if (a.notify) notifyDriveAlert(a.text, text)
            }
        }
        container.scope.launch {
            container.settings.settings.collect { if (!it.voiceAlerts) speaker.shutdown() }
        }
    }

    /** An important warning while driving (overheating, not charging, low fuel ...), as a heads-up notification. */
    private fun notifyDriveAlert(key: String, text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            android.app.NotificationChannel("drive_alerts", com.openobd.ui.tr("Alerts while driving"), android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = android.app.PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            // One notification per kind of warning: a repeat replaces the previous one.
            nm.notify(DRIVE_ALERT + (key.hashCode() and 0xFF), androidx.core.app.NotificationCompat.Builder(this, "drive_alerts").setSmallIcon(R.drawable.ic_stat_car)
                .setContentTitle(com.openobd.ui.tr("Alerts while driving")).setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setContentIntent(open).setAutoCancel(true).build())
        }
    }

    private fun startService() {
        val i = Intent(this, ObdService::class.java)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, i) }
    }

    companion object {
        private const val REGEN_NOTIFICATION = 77
        private const val DRIVE_ALERT = 2000
        private const val REFUEL_NOTIFICATION = 78
    }
}
