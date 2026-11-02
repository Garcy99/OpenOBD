package com.openobd.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogFile(val file: File, val sizeBytes: Long, val modified: Long)

/** Records live data to CSV files that can be opened in Excel, Google Sheets, or plotted later. */
class LogRepository(context: Context) {
    val dir: File = File(context.filesDir, "logs").apply { mkdirs() }
    private val _files = MutableStateFlow<List<LogFile>>(emptyList())
    val files: StateFlow<List<LogFile>> = _files

    private var writer: BufferedWriter? = null
    private var columns: List<String> = emptyList()
    private var startedAt = 0L
    private var rows = 0

    val isRecording: Boolean get() = writer != null

    fun refresh() {
        _files.value = dir.listFiles { f -> f.extension == "csv" }.orEmpty()
            .map { LogFile(it, it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }
    }

    @Synchronized
    fun start(keys: List<String>, headerNames: List<String>, vin: String?): File {
        stop()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "obd_$name.csv")
        val w = f.bufferedWriter()
        vin?.let { w.write("# VIN: $it\n") }
        w.write("time_s," + headerNames.joinToString(",") { "\"" + it.replace("\"", "'") + "\"" } + "\n")
        writer = w
        columns = keys
        startedAt = System.currentTimeMillis()
        rows = 0
        return f
    }

    @Synchronized
    fun append(values: Map<String, Double?>) {
        val w = writer ?: return
        val t = (System.currentTimeMillis() - startedAt) / 1000.0
        w.write("%.3f".format(Locale.US, t))
        for (k in columns) {
            w.write(",")
            values[k]?.let { w.write("%.4f".format(Locale.US, it).trimEnd('0').trimEnd('.')) }
        }
        w.write("\n")
        if (++rows % 20 == 0) w.flush()
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        refresh()
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) { file.delete(); refresh() }
}
