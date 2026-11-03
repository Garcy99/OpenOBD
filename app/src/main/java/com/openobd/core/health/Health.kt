package com.openobd.core.health

import kotlinx.serialization.Serializable

/** What one drive (connection session) told us about the car's health. All values metric. */
@Serializable
data class HealthSample(
    val vehicleId: String,
    val time: Long,
    /** Battery at rest, engine off (V). */
    val restingV: Double? = null,
    /** Average charging voltage, engine running warm (V). */
    val chargingV: Double? = null,
    /** Average long / short term fuel trim bank 1 at warm idle (%). */
    val ltftIdle: Double? = null,
    val stftIdle: Double? = null,
    /** Minutes from a cold start (< 40 °C) to 80 °C. */
    val warmupMin: Double? = null,
    val maxCoolant: Double? = null,
    /** Average warm idle speed (rpm). */
    val idleRpm: Double? = null,
    /** Misfire counts per cylinder from Mode 06, when read this session. */
    val misfires: Map<Int, Double> = emptyMap(),
    val drivenMin: Double = 0.0,
)

/**
 * Builds a [HealthSample] from the live data cycle. Feed it every cycle; [result] gives the sample once enough
 * was seen (null for very short sessions).
 */
class HealthRecorder(private val vehicleId: String, private val restingV: Double?, private val start: Long) {
    private var chargingSum = 0.0; private var chargingN = 0
    private var ltftSum = 0.0; private var ltftN = 0
    private var stftSum = 0.0; private var stftN = 0
    private var idleSum = 0.0; private var idleN = 0
    private var coldStartAt: Long? = null
    private var warmup: Double? = null
    private var maxCoolant: Double? = null
    private var firstSeen = false
    private var lastTime = start
    var misfires: Map<Int, Double> = emptyMap()

    fun onCycle(v: Map<String, Double>, now: Long) {
        lastTime = now
        val rpm = v["010C"]; val coolant = v["0105"]; val speed = v["010D"]; val volts = v["0142"]
        if (coolant != null) {
            if (!firstSeen) { firstSeen = true; if (coolant < 40) coldStartAt = now }
            maxCoolant = maxOf(maxCoolant ?: coolant, coolant)
            coldStartAt?.let { t0 -> if (warmup == null && coolant >= 80) warmup = (now - t0) / 60000.0 }
        }
        val running = rpm != null && rpm > 400
        val warm = coolant != null && coolant >= 75
        if (running && warm && volts != null) { chargingSum += volts; chargingN++ }
        val idle = running && rpm!! < 1100 && (speed == null || speed < 1) && warm
        if (idle) {
            idleSum += rpm!!; idleN++
            v["0107"]?.let { ltftSum += it; ltftN++ }
            v["0106"]?.let { stftSum += it; stftN++ }
        }
    }

    fun result(): HealthSample? {
        val minutes = (lastTime - start) / 60000.0
        if (minutes < 3 && chargingN == 0 && restingV == null) return null
        return HealthSample(
            vehicleId, start, restingV,
            chargingV = chargingN.takeIf { it >= 5 }?.let { chargingSum / it },
            ltftIdle = ltftN.takeIf { it >= 5 }?.let { ltftSum / it },
            stftIdle = stftN.takeIf { it >= 5 }?.let { stftSum / it },
            warmupMin = warmup, maxCoolant = maxCoolant,
            idleRpm = idleN.takeIf { it >= 5 }?.let { idleSum / it },
            misfires = misfires, drivenMin = minutes,
        )
    }
}

enum class HealthLevel { GOOD, WATCH, ACT }

enum class HealthMetric(val label: String, val unit: String) {
    RESTING_V("Battery at rest", "V"), CHARGING_V("Charging voltage", "V"), LTFT("Fuel trim at idle", "%"),
    WARMUP("Warm-up time", "min"), IDLE_RPM("Idle speed", "rpm"), MISFIRES("Misfires", ""),
}

data class HealthFinding(val metric: HealthMetric, val level: HealthLevel, val text: String, val args: List<String> = emptyList())

data class HealthSeries(val metric: HealthMetric, val points: List<Pair<Long, Double>>) {
    val latest: Double? get() = points.lastOrNull()?.second
}

data class HealthReport(val series: List<HealthSeries>, val findings: List<HealthFinding>, val samples: Int) {
    val overall: HealthLevel get() = findings.maxOfOrNull { it.level } ?: HealthLevel.GOOD
}

/** Trend rules over the recorded samples. They compare recent drives with the car's own earlier baseline. */
object HealthTrends {
    const val MIN_SAMPLES = 3

    val texts = listOf(
        "Battery at rest %1\$s V: weak, it may not start the engine on a cold morning.",
        "Battery at rest dropped from %1\$s V to %2\$s V: it is ageing; test it before winter.",
        "Battery at rest %1\$s V: good.",
        "Charging voltage %1\$s V is low: check the alternator and its belt.",
        "Charging voltage %1\$s V is too high: voltage regulator problem, it can damage the battery.",
        "Charging voltage %1\$s V: good.",
        "Fuel trim at idle %1\$s %%: the engine compensates a lot (air leak, injector or sensor). Check before it sets a code.",
        "Fuel trim at idle moved from %1\$s %% to %2\$s %%: something is changing in air or fuel delivery.",
        "Fuel trim at idle %1\$s %%: normal.",
        "Warm-up now takes %1\$s min (was %2\$s min): the thermostat may be opening too early.",
        "Warm-up %1\$s min: normal.",
        "Idle speed rose from %1\$s to %2\$s rpm: often a sign of an air leak or a dirty throttle / idle valve.",
        "Misfires on cylinder %1\$s are increasing (%2\$s): check its plug / glow plug, coil and injector.",
        "Drive a few more times so OpenOBD can learn your car's normal values (%1\$s of %2\$s drives).",
    )

    fun analyze(samples: List<HealthSample>): HealthReport {
        val s = samples.sortedBy { it.time }
        fun series(m: HealthMetric, f: (HealthSample) -> Double?) = HealthSeries(m, s.mapNotNull { x -> f(x)?.let { x.time to it } })
        val all = listOf(
            series(HealthMetric.RESTING_V) { it.restingV }, series(HealthMetric.CHARGING_V) { it.chargingV },
            series(HealthMetric.LTFT) { it.ltftIdle }, series(HealthMetric.WARMUP) { it.warmupMin },
            series(HealthMetric.IDLE_RPM) { it.idleRpm },
            series(HealthMetric.MISFIRES) { x -> x.misfires.values.takeIf { it.isNotEmpty() }?.sum() },
        )
        val out = mutableListOf<HealthFinding>()
        fun f(v: Double) = if (kotlin.math.abs(v) >= 100) "%.0f".format(v) else "%.1f".format(v)
        fun recent(p: List<Pair<Long, Double>>) = p.takeLast(3).map { it.second }.average()
        fun baseline(p: List<Pair<Long, Double>>) = p.dropLast(3).takeLast(10).map { it.second }.takeIf { it.size >= 2 }?.average()

        if (s.size < MIN_SAMPLES) out += HealthFinding(HealthMetric.RESTING_V, HealthLevel.GOOD, texts[13], listOf(s.size.toString(), MIN_SAMPLES.toString()))

        all.first { it.metric == HealthMetric.RESTING_V }.points.takeIf { it.isNotEmpty() }?.let { p ->
            val now = recent(p); val base = baseline(p)
            out += when {
                now < 12.2 -> HealthFinding(HealthMetric.RESTING_V, HealthLevel.ACT, texts[0], listOf(f(now)))
                base != null && base - now > 0.25 -> HealthFinding(HealthMetric.RESTING_V, HealthLevel.WATCH, texts[1], listOf(f(base), f(now)))
                else -> HealthFinding(HealthMetric.RESTING_V, HealthLevel.GOOD, texts[2], listOf(f(now)))
            }
        }
        all.first { it.metric == HealthMetric.CHARGING_V }.points.takeIf { it.isNotEmpty() }?.let { p ->
            val now = recent(p)
            out += when {
                now < 13.3 -> HealthFinding(HealthMetric.CHARGING_V, HealthLevel.ACT, texts[3], listOf(f(now)))
                now > 14.9 -> HealthFinding(HealthMetric.CHARGING_V, HealthLevel.ACT, texts[4], listOf(f(now)))
                else -> HealthFinding(HealthMetric.CHARGING_V, HealthLevel.GOOD, texts[5], listOf(f(now)))
            }
        }
        all.first { it.metric == HealthMetric.LTFT }.points.takeIf { it.isNotEmpty() }?.let { p ->
            val now = recent(p); val base = baseline(p)
            out += when {
                kotlin.math.abs(now) > 12 -> HealthFinding(HealthMetric.LTFT, HealthLevel.ACT, texts[6], listOf(f(now)))
                base != null && kotlin.math.abs(now - base) > 6 -> HealthFinding(HealthMetric.LTFT, HealthLevel.WATCH, texts[7], listOf(f(base), f(now)))
                else -> HealthFinding(HealthMetric.LTFT, HealthLevel.GOOD, texts[8], listOf(f(now)))
            }
        }
        all.first { it.metric == HealthMetric.WARMUP }.points.takeIf { it.isNotEmpty() }?.let { p ->
            val now = recent(p); val base = baseline(p)
            out += if (base != null && now > base * 1.6 && now - base > 3) HealthFinding(HealthMetric.WARMUP, HealthLevel.WATCH, texts[9], listOf(f(now), f(base)))
            else HealthFinding(HealthMetric.WARMUP, HealthLevel.GOOD, texts[10], listOf(f(now)))
        }
        all.first { it.metric == HealthMetric.IDLE_RPM }.points.let { p ->
            val base = baseline(p)
            if (p.isNotEmpty() && base != null && recent(p) - base > 120) out += HealthFinding(HealthMetric.IDLE_RPM, HealthLevel.WATCH, texts[11], listOf(f(base), f(recent(p))))
        }
        // Misfires per cylinder: rising over the last samples that have Mode 06 data.
        val withMisfires = s.filter { it.misfires.isNotEmpty() }
        if (withMisfires.size >= 3) {
            val cylinders = withMisfires.flatMap { it.misfires.keys }.toSet()
            for (cyl in cylinders) {
                val counts = withMisfires.map { it.misfires[cyl] ?: 0.0 }
                val last = counts.takeLast(3)
                if (last.zipWithNext().all { (a, b) -> b >= a } && last.last() >= 10 && last.last() > last.first()) {
                    out += HealthFinding(HealthMetric.MISFIRES, HealthLevel.WATCH, texts[12], listOf(cyl.toString(), last.joinToString(" → ") { f(it) }))
                }
            }
        }
        return HealthReport(all.filter { it.points.isNotEmpty() }, out, s.size)
    }
}
