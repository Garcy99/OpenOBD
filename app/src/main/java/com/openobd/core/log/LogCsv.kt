package com.openobd.core.log

/** A recorded data log: time in seconds and one column per sensor (null = no value in that row). */
class LogData(val columns: List<String>, val times: DoubleArray, val values: List<Array<Double?>>, val vin: String?) {
    val durationS: Double get() = times.lastOrNull() ?: 0.0
    fun column(i: Int): List<Pair<Double, Double>> = times.indices.mapNotNull { r -> values[i][r]?.let { times[r] to it } }

    /** The value of column [i] at time [t] (last value at or before t). */
    fun valueAt(i: Int, t: Double): Double? {
        var idx = times.indices.lastOrNull { times[it] <= t } ?: return null
        while (idx >= 0) { values[i][idx]?.let { return it }; idx-- }
        return null
    }

    data class Stats(val min: Double, val max: Double, val avg: Double)
    fun stats(i: Int): Stats? = values[i].filterNotNull().takeIf { it.isNotEmpty() }?.let { Stats(it.min(), it.max(), it.average()) }
}

/** Reads the CSV files written by the data logger (and similar files from other apps). */
object LogCsv {
    fun parse(text: String): LogData {
        var vin: String? = null
        val rows = mutableListOf<List<String>>()
        var header: List<String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) { if (line.startsWith("# VIN:")) vin = line.substringAfter(":").trim(); continue }
            val cells = split(line)
            if (header == null) header = cells else rows += cells
        }
        val h = header ?: throw IllegalArgumentException("Empty log")
        require(h.isNotEmpty() && h[0].contains("time", ignoreCase = true)) { "Not an OpenOBD log (first column must be time)" }
        val cols = h.drop(1)
        val times = DoubleArray(rows.size)
        val values = cols.map { arrayOfNulls<Double>(rows.size) }
        rows.forEachIndexed { r, cells ->
            times[r] = cells.getOrNull(0)?.toDoubleOrNull() ?: (if (r > 0) times[r - 1] else 0.0)
            for (c in cols.indices) values[c][r] = cells.getOrNull(c + 1)?.toDoubleOrNull()
        }
        return LogData(cols, times, values, vin)
    }

    /** CSV split that understands double-quoted cells (sensor names contain commas). */
    private fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in line) {
            when {
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out += sb.toString().trim()
        return out
    }
}
