package com.openobd.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openobd.core.garage.GarageData
import com.openobd.core.sync.SyncMerge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Preferences that follow the user to other devices. Adapter, connection and API keys stay on each phone.
 */
@Serializable
data class SyncedPrefs(
    val units: com.openobd.core.obd.UnitSystem = com.openobd.core.obd.UnitSystem.METRIC,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = true,
    val fuel: FuelKind = FuelKind.AUTO,
    val displacementL: Float = 2.0f,
    val dashboard: List<String> = AppSettings.DEFAULT_DASHBOARD,
    val dashboardTheme: String = "neon",
    val alerts: List<AlertRule> = emptyList(),
    val shiftLightRpm: Int = 0,
    val fuelPricePerL: Float = 0f,
    val tripGps: Boolean = true,
    val language: String = "system",
    val specsCountry: String = "",
    val voiceAlerts: Boolean = false,
) {
    fun applyTo(s: AppSettings) = s.copy(
        units = units, theme = theme, keepScreenOn = keepScreenOn, fuel = fuel, displacementL = displacementL, dashboard = dashboard,
        dashboardTheme = dashboardTheme, alerts = alerts, shiftLightRpm = shiftLightRpm, fuelPricePerL = fuelPricePerL, tripGps = tripGps,
        language = language, specsCountry = specsCountry, voiceAlerts = voiceAlerts,
    )

    companion object {
        fun of(s: AppSettings) = SyncedPrefs(
            s.units, s.theme, s.keepScreenOn, s.fuel, s.displacementL, s.dashboard, s.dashboardTheme, s.alerts, s.shiftLightRpm,
            s.fuelPricePerL, s.tripGps, s.language, s.specsCountry, s.voiceAlerts,
        )
    }
}

/** Google sign-in for the Drive app folder: a token now, or what the user must see to allow it. */
interface GoogleAuth {
    class Result(val token: String?, val consent: PendingIntent?)
    /** Without showing anything when access was already given. */
    suspend fun authorize(context: Context): Result
    /** The token after the consent screen (null if the user declined). */
    fun fromConsent(context: Context, data: Intent?): String?
    suspend fun revoke(token: String)
}

/**
 * Syncs the user's data with a private folder in their own Google Drive, so every phone signed in to the same
 * account (and later a desktop program) sees the same cars, trips and history. Nothing goes to any OpenOBD server.
 *
 * In the Drive folder: garage.json (cars, maintenance, fuel, trips, health, battery tests, coding history),
 * history.json (trouble-code scans), preferences.json, and track-<trip id>.json for each GPS route. Each sync
 * merges record by record against the last synced copy (kept on the phone, outside backups), so changes on
 * different devices all survive.
 */
class GoogleSync(
    private val context: Context,
    private val garage: GarageRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
) {
    data class State(
        val connected: Boolean = false,
        val email: String? = null,
        val syncing: Boolean = false,
        val lastSync: Long = 0,
        /** English message key (translated by the app). */
        val error: String? = null,
        val needsSignIn: Boolean = false,
    )

    /** Replaceable in tests. */
    var auth: GoogleAuth = PlayServicesAuth
    var driveFor: (String) -> DriveApi = { HttpDriveApi(it) }

    private val prefs = context.getSharedPreferences("google_sync", Context.MODE_PRIVATE)
    private val dir = File(context.noBackupFilesDir, "sync").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State(prefs.getBoolean("connected", false), prefs.getString("email", null), lastSync = prefs.getLong("last", 0)))
    val state: StateFlow<State> = _state

    private fun save(s: State) {
        _state.value = s
        prefs.edit().putBoolean("connected", s.connected).putString("email", s.email).putLong("last", s.lastSync).apply()
    }

    /** After the user allowed access: remembers the account and syncs for the first time. */
    suspend fun connect(token: String) {
        val drive = driveFor(token)
        save(_state.value.copy(connected = true, email = drive.account(), error = null, needsSignIn = false))
        run(drive)
    }

    /** Stops syncing on this phone and withdraws the app's access; the data stays in Drive and on the phone. */
    suspend fun disconnect() {
        runCatching { auth.authorize(context).token?.let { auth.revoke(it) } }
        dir.listFiles()?.forEach { it.delete() }
        save(State())
    }

    /**
     * Syncs when connected. [force] = the user asked; otherwise at most every [minIntervalMs] (on leaving the
     * app). Returns false when it could not (offline, access withdrawn).
     */
    suspend fun syncIfConnected(force: Boolean = false, minIntervalMs: Long = 2 * 60_000): Boolean {
        val s = _state.value
        if (!s.connected || s.syncing) return false
        if (!force && System.currentTimeMillis() - s.lastSync < minIntervalMs) return true
        val token = runCatching { auth.authorize(context) }.getOrNull()?.token
        if (token == null) { save(s.copy(needsSignIn = true, error = "Sign in to Google again to keep syncing.")); return false }
        return run(driveFor(token))
    }

    private suspend fun run(drive: DriveApi): Boolean = mutex.withLock {
        _state.value = _state.value.copy(syncing = true)
        val result = runCatching { syncWith(drive) }
        val e = result.exceptionOrNull()
        save(_state.value.copy(
            syncing = false,
            lastSync = if (e == null) System.currentTimeMillis() else _state.value.lastSync,
            error = e?.let { if (it is DriveAuthException) "Sign in to Google again to keep syncing." else "Could not sync: %1\$s" },
            needsSignIn = e is DriveAuthException,
        ))
        lastErrorDetail = e?.message
        e == null
    }

    /** Detail of the last error, for the message. */
    var lastErrorDetail: String? = null
        private set

    private fun <T> read(name: String, ser: KSerializer<T>): T? = runCatching { json.decodeFromString(ser, File(dir, name).readText()) }.getOrNull()
    private fun <T> keep(name: String, ser: KSerializer<T>, value: T) {
        val tmp = File(dir, "$name.tmp"); tmp.writeText(json.encodeToString(ser, value)); tmp.renameTo(File(dir, name))
    }

    private suspend fun syncWith(drive: DriveApi) {
        val historySer = ListSerializer(ScanRecord.serializer())
        repeat(3) { attempt ->
            val files = drive.list().groupBy { it.name }.mapValues { (_, v) -> v.maxBy { it.version } }
            suspend fun <T> remote(name: String, ser: KSerializer<T>): T? =
                files[name]?.let { f -> runCatching { json.decodeFromString(ser, String(drive.download(f.id))) }.getOrNull() }
            val remoteGarage = remote(GARAGE, GarageData.serializer())
            val remoteHistory = remote(HISTORY, historySer)
            val remotePrefs = remote(PREFS, SyncedPrefs.serializer())
            val baseGarage = read(GARAGE, GarageData.serializer())
            val baseHistory = read(HISTORY, historySer)
            val basePrefs = read(PREFS, SyncedPrefs.serializer())

            // Merge into this phone's data (atomically against edits happening meanwhile).
            var merged = GarageData()
            garage.update { cur ->
                // First sync of a fresh install: its automatic "My car" is not worth a duplicate next to the real cars.
                val local = if (baseGarage == null && remoteGarage != null && remoteGarage.vehicles.isNotEmpty() && pristine(cur)) GarageData() else cur
                SyncMerge.garage(baseGarage, local, remoteGarage).also { merged = it }
            }
            var mergedHistory = emptyList<ScanRecord>()
            if (remoteHistory != null) history.replace { cur ->
                SyncMerge.list(baseHistory.orEmpty(), cur, remoteHistory) { "${it.timestamp}|${it.vin}|${it.vehicleId}" }
                    .sortedByDescending { it.timestamp }.also { mergedHistory = it }
            } else mergedHistory = history.records.value
            val localPrefs = SyncedPrefs.of(settings.readOnce())
            // A phone joining for the first time takes the account's preferences rather than its own defaults.
            val mergedPrefs = if (basePrefs == null && remotePrefs != null) remotePrefs else SyncMerge.pick(basePrefs, localPrefs, remotePrefs) ?: localPrefs
            if (mergedPrefs != localPrefs) settings.update { mergedPrefs.applyTo(it) }

            // GPS tracks: one file per trip, never changed once recorded.
            val trips = merged.trips.map { it.id }.toSet()
            val localTracks = garage.trackIds()
            val remoteTracks = files.filterKeys { it.startsWith(TRACK) }.mapKeys { it.key.removePrefix(TRACK).removeSuffix(".json") }
            for (id in trips) {
                val r = remoteTracks[id]
                if (id in localTracks && r == null) garage.trackBytes(id)?.let { drive.create("$TRACK$id.json", it) }
                else if (id !in localTracks && r != null) garage.putTrack(id, drive.download(r.id))
            }
            for ((id, f) in remoteTracks) if (id !in trips) drive.delete(f.id)
            for (id in localTracks) if (id !in trips) garage.removeTrack(id)

            // Upload what changed, unless another device uploaded since we read (then merge again).
            val now = drive.list().groupBy { it.name }.mapValues { (_, v) -> v.maxBy { it.version } }
            val moved = listOf(GARAGE, HISTORY, PREFS).any { now[it]?.version != files[it]?.version }
            if (moved && attempt < 2) return@repeat
            suspend fun <T> push(name: String, ser: KSerializer<T>, value: T, old: T?) {
                if (value == old && files[name] != null) return
                val bytes = json.encodeToString(ser, value).toByteArray()
                val f = now[name]
                if (f == null) drive.create(name, bytes) else drive.update(f.id, bytes)
            }
            push(GARAGE, GarageData.serializer(), merged, remoteGarage)
            push(HISTORY, historySer, mergedHistory, remoteHistory)
            push(PREFS, SyncedPrefs.serializer(), mergedPrefs, remotePrefs)
            keep(GARAGE, GarageData.serializer(), merged)
            keep(HISTORY, historySer, mergedHistory)
            keep(PREFS, SyncedPrefs.serializer(), mergedPrefs)
            return
        }
    }

    /** Only the car a new install creates by itself, untouched. */
    private fun pristine(d: GarageData) = d.vehicles.size == 1 && d.vehicles[0].name == GarageData.DEFAULT_NAME && d.vehicles[0].vin == null &&
        d.trips.isEmpty() && d.fuel.isEmpty() && d.maintenance.isEmpty() && d.health.isEmpty() && d.batteryTests.isEmpty() && d.writes.isEmpty() &&
        d.vehicles[0].specs == null && d.vehicles[0].customPids.isEmpty()

    companion object {
        const val GARAGE = "garage.json"
        const val HISTORY = "history.json"
        const val PREFS = "preferences.json"
        const val TRACK = "track-"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        val texts = listOf("Sign in to Google again to keep syncing.", "Could not sync: %1\$s")
    }
}

/** Google Play services' authorization for the drive.appdata scope (no password ever reaches the app). */
object PlayServicesAuth : GoogleAuth {
    private fun request() = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
        .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(GoogleSync.SCOPE))).build()

    override suspend fun authorize(context: Context): GoogleAuth.Result = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { r -> cont.resumeWith(kotlin.Result.success(GoogleAuth.Result(r.accessToken, if (r.hasResolution()) r.pendingIntent else null))) }
            .addOnFailureListener { cont.resumeWith(kotlin.Result.failure(it)) }
    }

    override fun fromConsent(context: Context, data: Intent?): String? = runCatching {
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()

    override suspend fun revoke(token: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = java.net.URL("https://oauth2.googleapis.com/revoke").openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("token=" + java.net.URLEncoder.encode(token, "UTF-8")).toByteArray()) }
                c.responseCode
            } finally { c.disconnect() }
        }
    }
}
