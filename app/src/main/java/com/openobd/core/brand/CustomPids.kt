package com.openobd.core.brand

import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.SensorCategory
import com.openobd.core.obd.SensorDef
import kotlinx.serialization.Serializable

/**
 * A user-defined or imported manufacturer PID (Torque compatible).
 * [request] is the hex request, e.g. "221E1C" or "2101"; [header] the CAN request ID ("7E0", "7E4"),
 * blank for the default functional OBD header.
 */
@Serializable
data class CustomPid(
    val id: String,
    val name: String,
    val shortName: String,
    val request: String,
    val equation: String,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val unit: String = "",
    val header: String = "",
    /** Response CAN ID; blank = header + 8 (the usual convention). */
    val receive: String = "",
) {
    val key: String get() = "X_$id"
    val requestBytes: List<Int> get() = request.chunked(2).map { it.toInt(16) }
    val service: Int get() = requestBytes.first()
    val headerId: Int? get() = header.trim().takeIf { it.isNotEmpty() }?.toIntOrNull(16)
    val receiveId: Int? get() = receive.trim().takeIf { it.isNotEmpty() }?.toIntOrNull(16) ?: headerId?.let { if (it <= 0x7FF) it + 8 else null }

    /** Validates the definition; returns an error message or null. */
    fun validate(): String? {
        if (name.isBlank()) return "Name is required"
        if (!Regex("^([0-9A-Fa-f]{2}){1,8}$").matches(request)) return "Request must be hex bytes, e.g. 221E1C"
        // Custom PIDs are polled again and again: only services that read data are allowed, never clear / write / routines.
        if (service !in READ_ONLY_SERVICES) return "Only read requests are allowed (services 01, 02, 09, 21, 22, 1A, AA)"
        if (header.isNotBlank() && !Regex("^[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?([0-9A-Fa-f]{2})?$").matches(header.trim())) return "Header must be a CAN ID like 7E0"
        return try { Equation(equation); null } catch (e: Exception) { "Equation: ${e.message}" }
    }

    fun toSensor(): SensorDef {
        val eq = runCatching { Equation(equation) }.getOrNull()
        val (u, label) = unitFor(unit)
        return SensorDef(
            key = key, pid = -2, name = name, shortName = shortName.ifBlank { name.take(12) }, unit = u,
            min = min, max = if (max > min) max else min + 100, category = SensorCategory.BRAND,
            decimals = if (max - min <= 20) 2 else if (max - min <= 300) 1 else 0, customUnit = label,
        ) { bytes -> eq?.evaluate(bytes) }
    }

    companion object {
        /** OBD read, freeze frame, vehicle info, KWP read by local id, UDS read by identifier, GMLAN read by id / packet. */
        val READ_ONLY_SERVICES = setOf(0x01, 0x02, 0x09, 0x21, 0x22, 0x1A, 0xAA)

        /** Maps a free-text unit to a known unit (so imperial conversion works) or keeps it as a label. */
        fun unitFor(raw: String): Pair<ObdUnit, String?> {
            val u = raw.trim()
            val known = when (u.lowercase().replace("deg", "°").replace(" ", "")) {
                "°c", "c", "celsius" -> ObdUnit.CELSIUS
                "km/h", "kph", "kmh" -> ObdUnit.KMH
                "kpa" -> ObdUnit.KPA
                "%" -> ObdUnit.PERCENT
                "v", "volt", "volts" -> ObdUnit.VOLTS
                "rpm" -> ObdUnit.RPM
                "km" -> ObdUnit.KM
                "nm", "n·m", "n.m" -> ObdUnit.NEWTON_METERS
                "kw" -> ObdUnit.KILOWATTS
                "l/h" -> ObdUnit.LITERS_PER_HOUR
                "g/s" -> ObdUnit.GRAMS_PER_SEC
                "l", "litres", "liters" -> ObdUnit.LITERS
                else -> null
            }
            return if (known != null) known to null else ObdUnit.NONE to u
        }
    }
}

/** Reads and writes the Torque Pro custom PID CSV format, so community PID packs can be imported. */
object TorqueCsv {
    private val BOM = Char(0xFEFF).toString()
    const val HEADER = "Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header"

    data class ImportResult(val pids: List<CustomPid>, val skipped: List<String>)

    fun parse(text: String, idPrefix: String = System.currentTimeMillis().toString(36)): ImportResult {
        val pids = mutableListOf<CustomPid>()
        val skipped = mutableListOf<String>()
        text.lineSequence().forEachIndexed { lineNo, raw ->
            val line = raw.trim().removePrefix(BOM)
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            val f = splitCsv(line)
            if (f.isEmpty() || f[0].equals("Name", true) || f[0].startsWith("Name", true) && f.getOrNull(2)?.contains("PID", true) == true) return@forEachIndexed
            if (f.size < 4) { skipped += "Line ${lineNo + 1}: too few columns"; return@forEachIndexed }
            val request = f[2].replace(" ", "").removePrefix("0x").uppercase()
            val pid = CustomPid(
                id = "$idPrefix${pids.size}",
                name = f[0].trim(),
                shortName = f.getOrElse(1) { "" }.trim(),
                request = request,
                equation = f[3].trim(),
                min = f.getOrNull(4)?.trim()?.toDoubleOrNull() ?: 0.0,
                max = f.getOrNull(5)?.trim()?.toDoubleOrNull() ?: 100.0,
                unit = f.getOrElse(6) { "" }.trim(),
                header = f.getOrElse(7) { "" }.trim().removePrefix("0x").uppercase(),
            )
            val err = pid.validate()
            if (err == null) pids += pid else skipped += "Line ${lineNo + 1} (${pid.name}): $err"
        }
        return ImportResult(pids, skipped)
    }

    fun export(pids: List<CustomPid>): String = buildString {
        appendLine(HEADER)
        for (p in pids) {
            appendLine(listOf(p.name, p.shortName, p.request, p.equation, fmt(p.min), fmt(p.max), p.unit, p.header).joinToString(",") { quote(it) })
        }
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private fun quote(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}

/** Registry of active custom sensors so they show up everywhere standard sensors do. */
object CustomSensors {
    @Volatile
    var pids: List<CustomPid> = emptyList()
        private set
    @Volatile
    private var defs: Map<String, SensorDef> = emptyMap()

    fun update(list: List<CustomPid>) {
        pids = list
        defs = list.associate { it.key to it.toSensor() }
    }

    fun get(key: String): SensorDef? = defs[key]
    fun pid(key: String): CustomPid? = pids.firstOrNull { it.key == key }
    val sensors: Collection<SensorDef> get() = defs.values
}
