package com.openobd.data

import android.content.Context
import android.os.Build
import com.openobd.BuildConfig
import com.openobd.core.debug.DebugLog
import com.openobd.core.debug.DebugReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/** Debug log of the adapter link, crash capture and shareable reports. */
class Diagnostics(private val context: Context) {
    val log = DebugLog()
    private val dir = File(context.filesDir, "debug").apply { mkdirs() }
    private val crashFile = File(dir, "crash.txt")
    private val _pendingCrash = MutableStateFlow<String?>(null)
    /** Crash report from the previous run, until it is shared or dismissed. */
    val pendingCrash: StateFlow<String?> = _pendingCrash

    /** Writes uncaught exceptions (plus the recent adapter log) to disk before the app dies. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            recordCrash(thread, e)
            previous?.uncaughtException(thread, e)
        }
        reload()
    }

    fun recordCrash(thread: Thread, e: Throwable) {
        runCatching {
            crashFile.writeText(
                "Thread: ${thread.name}\nTime: ${System.currentTimeMillis()}\n\n" + DebugReport.stackTrace(e) +
                    "\n--- adapter log before the crash ---\n" + log.format().takeLast(48_000),
            )
        }
    }

    /** Picks up a crash report written by a previous run. */
    fun reload() {
        _pendingCrash.value = crashFile.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
    }

    fun dismissCrash() {
        crashFile.delete()
        _pendingCrash.value = null
    }

    fun appInfo(): List<Pair<String, String?>> = listOf(
        "App" to "OpenOBD ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Locale" to Locale.getDefault().toLanguageTag(),
    )

    /** Builds a report and writes it to a shareable file. */
    fun writeReport(sections: List<Pair<String, List<Pair<String, String?>>>>, includeCrash: Boolean): File {
        val text = DebugReport.build(
            "OpenOBD debug report",
            listOf("App and phone" to appInfo()) + sections,
            log,
            crash = if (includeCrash) _pendingCrash.value else null,
        )
        val out = File(context.cacheDir, "reports").apply { mkdirs() }
        out.listFiles()?.filter { it.name.startsWith("openobd-debug-") }?.forEach { it.delete() }
        return File(out, "openobd-debug-${System.currentTimeMillis() / 1000}.txt").apply { writeText(text) }
    }
}
