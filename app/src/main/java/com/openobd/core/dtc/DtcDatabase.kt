package com.openobd.core.dtc

import com.openobd.core.obd.DtcDecoder
import java.io.InputStream

data class DtcKnowledge(val severity: Int, val causes: List<String>, val advice: String)

data class DtcDescription(
    val code: String,
    val description: String,
    val known: Boolean,
    val category: String,
    val knowledge: DtcKnowledge?,
) {
    val severityLabel: String
        get() = when (knowledge?.severity) {
            1 -> "Low"; 2 -> "Moderate"; 3 -> "High"; else -> "Unknown"
        }
}

/** In-memory DTC description database loaded from TSV assets. */
class DtcDatabase(descriptions: InputStream?, causes: InputStream?) {
    private val descs: Map<String, String> = descriptions?.bufferedReader()?.useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().uppercase() to it[1].trim() } }
            .toMap()
    } ?: emptyMap()

    private val knowledge: Map<String, DtcKnowledge> = causes?.bufferedReader()?.useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 4) null
                else p[0].trim().uppercase() to DtcKnowledge(p[1].trim().toIntOrNull() ?: 0, p[2].split(';').map { it.trim() }, p[3].trim())
            }.toMap()
    } ?: emptyMap()

    val size: Int get() = descs.size

    fun lookup(code: String): DtcDescription {
        val c = code.uppercase()
        val d = descs[c]
        return DtcDescription(
            code = c,
            description = d ?: DtcDecoder.genericCategory(c),
            known = d != null,
            category = DtcDecoder.genericCategory(c),
            knowledge = knowledge[c],
        )
    }

    fun search(query: String, limit: Int = 100): List<DtcDescription> {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        return descs.asSequence()
            .filter { (k, v) -> k.startsWith(q) || v.uppercase().contains(q) }
            .take(limit)
            .map { lookup(it.key) }
            .toList()
    }
}
