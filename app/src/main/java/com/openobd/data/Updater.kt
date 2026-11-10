package com.openobd.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.openobd.BuildConfig
import com.openobd.core.update.Release
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer version, downloads its APK and hands it to Android's installer, so updates can be done
 * from inside the app. Not in Google Play builds (the store updates them, and its policy forbids self-updates).
 * Android only accepts the update if it is signed with the same key as the installed app.
 */
class Updater(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val done: Long, val total: Long) : State
        data class Ready(val release: Release, val file: File) : State
        /** English message key (translated by the app). */
        data class Failed(val message: String, val release: Release? = null) : State
    }

    val enabled: Boolean get() = BuildConfig.SELF_UPDATE
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val dir = File(context.cacheDir, "updates")

    /** Replaceable in tests: the latest release, or null if it cannot be read. */
    var latest: suspend () -> Release? = {
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    c.setRequestProperty("User-Agent", "OpenOBD/" + BuildConfig.VERSION_NAME)
                    if (c.responseCode != 200) null else Release.parse(c.inputStream.use { String(it.readBytes()) })
                } finally { c.disconnect() }
            }.getOrNull()
        }
    }

    /** Replaceable in tests: downloads [url] into [to], reporting progress. */
    var download: suspend (String, File, (Long, Long) -> Unit) -> Unit = { url, to, progress ->
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "OpenOBD/" + BuildConfig.VERSION_NAME)
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                c.inputStream.use { input ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n; progress(done, total)
                        }
                    }
                }
            } finally { c.disconnect() }
        }
    }

    /** The last answer from GitHub and when it came (automatic checks reuse it for a few minutes). */
    @Volatile private var cached: Pair<Long, Release>? = null
    /** When the last automatic check ran (tests set it back to pretend the app was opened again later). */
    @Volatile internal var lastAuto = 0L

    /**
     * Checks for a newer version. Automatic checks ([manual] false) run every time the app is opened, so a new
     * version pops up then even if it was put off before ("Later"); only a version the user chose to skip stays
     * quiet. GitHub is asked at most every few minutes (the answer is kept meanwhile).
     */
    suspend fun check(manual: Boolean = false) {
        if (!enabled) return
        if (_state.value.let { it is State.Downloading || it is State.Checking || it is State.Ready }) return
        val now = System.currentTimeMillis()
        if (!manual) {
            if (_state.value is State.Available) return
            // The same opening (the screen turning, a dialog closing) is not a new one.
            if (now - lastAuto < MIN_BETWEEN) return
            lastAuto = now
        }
        val fresh = cached?.takeIf { !manual && now - it.first < KEEP }?.second
        if (fresh == null) _state.value = State.Checking
        val r = fresh ?: latest()?.also { cached = now to it }
        if (r != null) prefs.edit().putLong("last", now).apply()
        _state.value = when {
            r == null -> if (manual) State.Failed("Could not check for updates. Check your internet connection.") else State.Idle
            !r.isNewerThan(BuildConfig.VERSION_NAME) -> if (manual) State.UpToDate else State.Idle
            !manual && prefs.getString("skipped", null) == r.version -> State.Idle
            else -> State.Available(r)
        }
    }

    /** Hides the offer until the app is opened again (or a manual check). */
    fun later() { _state.value = State.Idle }

    /** Never offers this version again on its own. */
    fun skip(release: Release) { prefs.edit().putString("skipped", release.version).apply(); _state.value = State.Idle }

    /** Downloads the APK and checks it is signed like this app (otherwise Android would refuse to update). */
    suspend fun fetch(release: Release) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "OpenOBD-${release.version}.apk")
        _state.value = State.Downloading(release, 0, release.apkSize)
        try {
            download(release.apkUrl, file) { done, total -> _state.value = State.Downloading(release, done, if (total > 0) total else release.apkSize) }
        } catch (e: Exception) {
            file.delete()
            _state.value = State.Failed("The download failed. Try again later.", release); return
        }
        if (!sameSigner(file)) {
            _state.value = State.Failed("This version is signed with a different key than the app installed on this phone, so Android cannot install it over it. Back up your data (Settings → Backup), uninstall OpenOBD and install the new version from GitHub.", release)
            return
        }
        _state.value = State.Ready(release, file)
    }

    /** Whether Android lets this app install packages (the user enables it once, in a screen we open). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user allows OpenOBD to install apps. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    /** Android's installer for the downloaded APK. */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
    }

    private fun sameSigner(file: File): Boolean = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return@runCatching true // unreadable here: let Android decide
        val mine = pm.getPackageInfo(context.packageName, flags)
        @Suppress("DEPRECATION")
        fun sigs(p: android.content.pm.PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        val a = sigs(archive)
        a.isEmpty() || a == sigs(mine)
    }.getOrDefault(true)

    companion object {
        const val REPO = "Garcy99/OpenOBD"
        /** Automatic checks closer together than this count as one opening. */
        const val MIN_BETWEEN = 30_000L
        /** How long GitHub's answer is reused by automatic checks. */
        const val KEEP = 10 * 60_000L
        val texts = listOf(
            "Could not check for updates. Check your internet connection.", "The download failed. Try again later.",
            "This version is signed with a different key than the app installed on this phone, so Android cannot install it over it. Back up your data (Settings → Backup), uninstall OpenOBD and install the new version from GitHub.",
        )
    }
}
