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
    /** Aerial photos of an area (minLat, minLon, maxLat, maxLon) at about the given metres per pixel; replaceable in tests. */
    var imagery: suspend (DoubleArray, Double, (Int, Int) -> Unit) -> com.openobd.core.trip.TileImagery? = { area, mpp, progress ->
        com.openobd.data.ImageryDownloader(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)").around(area, mpp, progress)
    }
    /** Lakes from OpenStreetMap for an area (minLat, minLon, maxLat, maxLon); replaceable in tests. */
    var water: suspend (DoubleArray) -> List<List<com.openobd.core.trip.LatLon>>? = { area ->
        com.openobd.data.MapDataDownloader(app, "OpenOBD/${com.openobd.BuildConfig.VERSION_NAME} (Android; open source)").water(area)
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
        }
    }

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
            container.sync.syncIfConnected(force = true)
            // Not in tests: they must not depend on what is published on GitHub.
            if (android.os.Build.FINGERPRINT != "robolectric") container.updater.check()
        }
        keepWidgetUpdated()
        speakAlerts()
        // Keep a foreground service alive while connected so logging continues with the screen off.
        container.scope.launch {
            var wasConnected = false
            container.session.state.collect { s ->
                val connected = s is ConnectionState.Connected
                if (connected && !wasConnected) startService()
                if (!connected && wasConnected) stopService(Intent(this@OpenObdApp, ObdService::class.java))
                wasConnected = connected
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

    /** Speaks the session's voice alerts in the app's language. */
    private fun speakAlerts() {
        val speaker = com.openobd.data.Speaker(this)
        container.scope.launch {
            container.session.voice.announcements.collect { a ->
                speaker.language = com.openobd.ui.I18n.language
                speaker.speak(com.openobd.ui.tr(a.text, *a.args.map { com.openobd.ui.tr(it) }.toTypedArray()))
            }
        }
        container.scope.launch {
            container.settings.settings.collect { if (!it.voiceAlerts) speaker.shutdown() }
        }
    }

    private fun startService() {
        val i = Intent(this, ObdService::class.java)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, i) }
    }
}
