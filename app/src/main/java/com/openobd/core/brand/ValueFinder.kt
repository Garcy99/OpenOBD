package com.openobd.core.brand

import kotlin.math.abs

/**
 * Finds which identifier of a module holds a value the driver can see elsewhere (another app such as ScanMyOpel, the
 * dashboard): every answer read from the module is decoded at each byte position with the scalings engine computers
 * use, and the readings that come out at that value are offered, simplest first. A second reading later (after the
 * value has changed) narrows them down.
 */
object ValueFinder {
    /** Raw bytes read at [offset]: one byte, two (high first or low first, signed or not) or three. */
    enum class Width(val bytes: Int) { U8(1), U16(2), U16_LE(2), S16(2), U24(3) }

    /** value = raw × [mul] + [add]. */
    data class Scale(val mul: Double, val add: Double = 0.0)

    data class Candidate(val kind: IdKind, val id: Int, val offset: Int, val width: Width, val scale: Scale, val value: Double, val score: Double) {
        val key: String get() = "${kind.name}:${kind.idHex(id)}:$offset:${width.name}:${scale.mul}:${scale.add}"
        fun decode(bytes: IntArray): Double? = raw(bytes, offset, width)?.let { it * scale.mul + scale.add }
    }

    /** What is being looked for: it sets how close a reading must be (temperatures move while the engine runs). */
    enum class Quantity(val label: String, val unit: String, val customId: String?, val min: Double, val max: Double) {
        DPF_SOOT("Particulate filter soot load", "%", "gm_dpf_soot", 0.0, 100.0),
        DPF_TEMP("Particulate filter inlet temperature", "°C", "gm_dpf_inlet_t", -40.0, 900.0),
        DPF_KM("Distance since the last filter regeneration", "km", "gm_dpf_km", 0.0, 3000.0),
        DPF_REGENS("Filter regenerations started (GM)", "", "gm_dpf_regens", 0.0, 10000.0),
        OTHER("Other value", "", null, -1e9, 1e9);

        fun tolerance(target: Double): Double = when (this) {
            DPF_SOOT -> maxOf(1.5, abs(target) * 0.04)
            DPF_TEMP -> maxOf(15.0, abs(target) * 0.06)
            DPF_KM -> maxOf(1.5, abs(target) * 0.02)
            DPF_REGENS -> 0.5
            OTHER -> maxOf(0.6, abs(target) * 0.03)
        }
    }

    /** The scalings tried, simplest first (their position counts against a candidate a little). */
    val scales = listOf(
        Scale(1.0), Scale(0.1), Scale(0.01), Scale(0.001), Scale(100.0 / 255), Scale(0.5), Scale(0.25), Scale(1.0 / 16),
        Scale(1.0, -40.0), Scale(0.1, -40.0), Scale(0.1, -273.15), Scale(0.02, -40.0), Scale(0.019608, -40.0),
        Scale(1.0 / 128), Scale(1.0 / 1024), Scale(1000.0 / 65535), Scale(1.0 / 650), Scale(100.0 / 65535), Scale(0.03125),
    )

    fun raw(b: IntArray, i: Int, w: Width): Double? = when (w) {
        Width.U8 -> b.getOrNull(i)?.toDouble()
        Width.U16 -> if (i + 1 < b.size) (b[i] * 256 + b[i + 1]).toDouble() else null
        Width.U16_LE -> if (i + 1 < b.size) (b[i + 1] * 256 + b[i]).toDouble() else null
        Width.S16 -> if (i + 1 < b.size) (b[i] * 256 + b[i + 1]).let { if (it >= 32768) it - 65536 else it }.toDouble() else null
        Width.U24 -> if (i + 2 < b.size) (b[i] * 65536 + b[i + 1] * 256 + b[i + 2]).toDouble() else null
    }

    /**
     * Every way [records] (identifier → bytes after its echo) can read as [target], best first, at most [limit].
     * Readings that are zero, or that would read the same whatever the byte (a constant), are skipped.
     */
    fun search(records: Map<Pair<IdKind, Int>, IntArray>, target: Double, q: Quantity, limit: Int = 40): List<Candidate> {
        val tol = q.tolerance(target)
        val out = ArrayList<Candidate>()
        for ((k, bytes) in records) {
            val (kind, id) = k
            for (i in bytes.indices) for (w in Width.entries) {
                val r = raw(bytes, i, w) ?: continue
                if (r == 0.0) continue
                scales.forEachIndexed { si, s ->
                    val v = r * s.mul + s.add
                    val err = abs(v - target)
                    if (err <= tol && v in q.min..q.max) {
                        // Closer and simpler (plain scalings, wider readings for wide values) first.
                        val score = err / tol + si * 0.04 + (if (w == Width.U16_LE) 0.3 else 0.0) + (if (w == Width.U8 && abs(target) > 255) 1.0 else 0.0) + i * 0.002
                        out += Candidate(kind, id, i, w, s, v, score)
                    }
                }
            }
        }
        return out.sortedBy { it.score }.distinctBy { it.key }.take(limit)
    }

    /** The candidates that still read as [target] in fresh [records] (the value has changed since). */
    fun refine(candidates: List<Candidate>, records: Map<Pair<IdKind, Int>, IntArray>, target: Double, q: Quantity): List<Candidate> {
        val tol = q.tolerance(target)
        return candidates.mapNotNull { c ->
            val v = records[c.kind to c.id]?.let { c.decode(it) } ?: return@mapNotNull null
            if (abs(v - target) <= tol) c.copy(value = v, score = abs(v - target) / tol) else null
        }.sortedBy { it.score }
    }

    /** Byte names of the equations ("A".."Z", then "AA", "AB" ...). */
    fun letter(i: Int): String = if (i < 26) ('A' + i).toString() else ('A' + (i / 26 - 1)).toString() + ('A' + i % 26)

    /** The candidate as a custom PID equation. */
    fun equation(c: Candidate): String {
        val a = letter(c.offset); val b = letter(c.offset + 1); val d = letter(c.offset + 2)
        val raw = when (c.width) {
            Width.U8 -> a
            Width.U16 -> "($a*256+$b)"
            Width.U16_LE -> "($b*256+$a)"
            Width.S16 -> "Int16($a,$b)"
            Width.U24 -> "($a*65536+$b*256+$d)"
        }
        val mul = if (c.scale.mul == 1.0) raw else "$raw*${trim(c.scale.mul)}"
        return when {
            c.scale.add == 0.0 -> mul
            c.scale.add < 0 -> "$mul-${trim(-c.scale.add)}"
            else -> "$mul+${trim(c.scale.add)}"
        }
    }

    private fun trim(v: Double) = java.math.BigDecimal(v).round(java.math.MathContext(8)).stripTrailingZeros().toPlainString()

    /** The custom PID of a candidate, under [Quantity.customId] for the filter values (so the app uses it everywhere). */
    fun toCustomPid(c: Candidate, q: Quantity, name: String, header: String = "7E0"): CustomPid = CustomPid(
        id = q.customId ?: ("found_" + c.kind.idHex(c.id).lowercase() + "_" + c.offset), name = name,
        shortName = when (q) { Quantity.DPF_SOOT -> "DPF soot"; Quantity.DPF_TEMP -> "DPF temp"; Quantity.DPF_KM -> "Since regen"; Quantity.DPF_REGENS -> "Regens"; Quantity.OTHER -> name.take(12) },
        request = c.kind.readRequest(c.id), equation = equation(c), min = if (q == Quantity.OTHER) minOf(0.0, c.value) else q.min,
        max = if (q == Quantity.OTHER) maxOf(100.0, c.value * 2) else q.max, unit = q.unit, header = header, source = "Found on this car",
    )

    /**
     * Where to look on an engine computer: the identifier groups engine computers keep live values in (GM / Opel and
     * others), the busiest first, and the KWP local identifiers older Opel diesels use.
     */
    val engineRanges: List<Pair<IdKind, IntRange>> = listOf(
        IdKind.KWP to 0x00..0xFF,
        IdKind.UDS to 0x1800..0x19FF, IdKind.UDS to 0x3800..0x39FF, IdKind.UDS to 0x5000..0x50FF, IdKind.UDS to 0xD500..0xD5FF,
        IdKind.UDS to 0xA000..0xA0FF, IdKind.UDS to 0x1100..0x15FF, IdKind.UDS to 0x2000..0x25FF, IdKind.UDS to 0x3000..0x37FF,
        IdKind.UDS to 0x4D00..0x4DFF, IdKind.UDS to 0x0100..0x02FF,
    )

    val texts = listOf("Other value") + Quantity.entries.map { it.label }
}
