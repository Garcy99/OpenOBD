package com.openobd.core.debug

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bounded record of everything that happened on the adapter link: raw commands ('>'), replies ('<'),
 * errors/timeouts ('!') and session events ('i'). Shared as a text report when something goes wrong
 * with a real car, so problems can be reproduced in the simulator.
 */
class DebugLog(private val capacity: Int = 4000, private val clock: () -> Long = { System.currentTimeMillis() }) {
    data class Entry(val time: Long, val kind: Char, val text: String)

    private val entries = ArrayDeque<Entry>()
    /** Entries dropped because the log was full (the report says so). */
    var dropped = 0L
        private set

    @Synchronized
    fun add(kind: Char, text: String) {
        if (entries.size >= capacity) { entries.removeFirst(); dropped++ }
        entries.addLast(Entry(clock(), kind, text.take(2000)))
    }

    fun event(text: String) = add('i', text)

    fun error(text: String, e: Throwable? = null) = add('!', if (e == null) text else "$text: ${e.javaClass.simpleName}: ${e.message}")

    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() { entries.clear(); dropped = 0 }

    val size: Int @Synchronized get() = entries.size

    /** The log as text, one entry per line, e.g. "12:01:02.345 > 010C". */
    fun format(): String {
        val f = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        if (dropped > 0) sb.append("... $dropped older entries dropped ...\n")
        for (e in entries()) sb.append(f.format(Date(e.time))).append(' ').append(e.kind).append(' ').append(e.text.replace("\n", " | ")).append('\n')
        return sb.toString()
    }
}

object DebugReport {
    /** VINs are shared with the report, but without the serial number part (last 6 characters). */
    fun maskVin(vin: String?): String? = vin?.let { if (it.length == 17) it.take(11) + "******" else it }

    /** Removes things that should never leave the phone if a report is posted publicly. */
    fun redact(text: String): String = text
        .replace(Regex("\\b([0-9A-F]{2}:){5}[0-9A-F]{2}\\b", RegexOption.IGNORE_CASE), "XX:XX:XX:XX:XX:XX")
        .replace(Regex("\\b([A-HJ-NPR-Z0-9]{11})[A-HJ-NPR-Z0-9]{6}\\b")) { it.groupValues[1] + "******" }

    fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    fun build(
        title: String,
        sections: List<Pair<String, List<Pair<String, String?>>>>,
        log: DebugLog?,
        crash: String? = null,
        now: Long = System.currentTimeMillis(),
    ): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("# ").append(title).append('\n')
        sb.append("Generated: ").append(iso.format(Date(now))).append("\n\n")
        for ((name, rows) in sections) {
            sb.append("## ").append(name).append('\n')
            for ((k, v) in rows) sb.append("- ").append(k).append(": ").append(v ?: "-").append('\n')
            sb.append('\n')
        }
        crash?.let { sb.append("## Crash\n").append(it.trim()).append("\n\n") }
        log?.let {
            sb.append("## Adapter log (").append(it.size).append(" entries; > sent, < received, ! error, i event)\n")
            sb.append(it.format())
        }
        return redact(sb.toString())
    }
}
