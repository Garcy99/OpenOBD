package com.openobd.core.diagnosis

import com.openobd.core.dtc.DtcKnowledge
import com.openobd.core.obd.TestResult

/** Whether it is reasonable to keep driving with a fault. */
enum class DriveAdvice(val label: String) {
    OK("You can keep driving"),
    CAUTION("Drive gently and get it checked soon"),
    STOP("Stop as soon as it is safe"),
}

data class RankedCause(val cause: String, val score: Int, val evidence: List<Evidence>)

/** A finding from the car's own data, as an English template with arguments (translated by the UI). */
data class Evidence(val text: String, val args: List<String> = emptyList(), val supports: Boolean = true)

data class Explanation(
    val code: String,
    val advice: DriveAdvice,
    val adviceReason: String,
    val causes: List<RankedCause>,
    val nextSteps: List<String>,
)

/**
 * What the car told us besides the code itself. Values are in metric units keyed like the sensor catalog
 * ("0105" coolant °C, "0106"/"0107" short/long term fuel trim bank 1 %, "010C" rpm, "0104" load %, "0142" volts...).
 */
data class DiagnosisContext(
    val freezeFrame: Map<String, Double> = emptyMap(),
    val live: Map<String, Double> = emptyMap(),
    val mode06: List<TestResult> = emptyList(),
    val otherCodes: List<String> = emptyList(),
    val isDiesel: Boolean = false,
    /** Average length of recent trips (km), from the trip history. */
    val averageTripKm: Double? = null,
    val batteryV: Double? = null,
    /** Minutes the engine has been running this session (PID 1F). */
    val runMinutes: Double? = null,
)

/**
 * Explains a trouble code using the car's own data: freeze frame, live values, on-board test results
 * (misfire counts per cylinder, catalyst tests) and the other codes present. Causes come from the code database
 * and are re-ranked by evidence; rules are deliberately simple and each one says what it saw.
 */
object Diagnosis {
    /** Templates used, for translation coverage. */
    val texts = listOf(
        "Long term fuel trim %1\$s %% at idle in the freeze frame: the engine adds fuel to compensate for unmetered air.",
        "Fuel trims are high at load too (%1\$s %%): more likely fuel delivery or air metering than a small leak.",
        "Fuel trims are high at idle but normal at load: typical of a vacuum leak.",
        "Fuel trims are negative (%1\$s %%): the engine runs rich.",
        "Misfires counted on cylinder %1\$s: %2\$s (on-board test).", "Misfires spread over %1\$s cylinders: more likely a common cause than one coil or plug.",
        "Only cylinder %1\$s misfires: swap its coil with a neighbour and see if the misfire follows.",
        "Misfire or fuel codes are also present (%1\$s): fix those first, they damage the catalyst.",
        "Catalyst test result %1\$s against limit %2\$s.",
        "Coolant only reached %1\$s °C after %2\$s minutes of running: the thermostat is probably stuck open.",
        "Coolant %1\$s °C: the engine is overheating.",
        "Average trip %1\$s km: too short for the particulate filter to regenerate.",
        "Battery voltage %1\$s V: low voltage causes communication and sensor codes in several modules.",
        "Several modules report communication codes (%1\$s): check the battery and the main connectors first.",
        "Low battery voltage",
        "Ongoing misfires overheat and destroy the catalytic converter; a flashing warning light means stop.",
        "Engine temperature or oil pressure problems can destroy the engine within minutes.",
        "Timing problems can let the valves hit the pistons.",
        "The engine may lose power or stall (limp mode); avoid motorways and overtaking.",
        "Brake assistance (ABS / ESP) may be off. The brakes still work, but drive carefully.",
        "The airbags may not deploy in a crash. Get it checked before long journeys.",
        "The particulate filter is filling up: drive 20 minutes at a steady 2500 rpm (motorway, a lower gear) so it can regenerate. If the warning stays on, have it cleaned before it clogs.",
        "A serious fault: get it checked soon and avoid long trips until then.",
        "Emissions or efficiency fault: safe to drive, fix it before the next inspection.",
        "Usually harmless to drive; fix it when convenient.",
        "Watch long term fuel trim in Live data at idle and at 2500 rpm: if it drops with rpm, look for a vacuum leak.",
        "Check the misfire counters per cylinder in On-board monitor tests after a drive.",
        "Check the coolant temperature in Live data after 15 minutes of driving: it should stay at 85-95 °C.",
        "Check the battery at rest (12.4 V or more) and while running (13.8-14.7 V) before replacing modules.",
        "Clear the code after the repair and check it does not come back after a few drives.",
    )

    private val misfire = Regex("^P03(0[0-9]|1[0-2])$")
    private val lean = setOf("P0171", "P0174")
    private val rich = setOf("P0172", "P0175")
    private val stopCodes = setOf("P0217", "P0218", "P0520", "P0521", "P0522", "P0523", "P0524", "P0016", "P0017", "P0018", "P0019", "P0008", "P0009")
    private val limpCodes = setOf("P0087", "P0088", "P0093", "P0191", "P0234", "P0299", "P2263", "P0101", "P0102", "P0103", "P0335", "P0336", "P0340", "P0341", "P0606")
    private val dpfCodes = setOf("P2002", "P2003", "P2463", "P242F", "P244A", "P244B", "P2452", "P2453", "P2454", "P2455", "P1463")

    fun explain(code: String, knowledge: DtcKnowledge?, ctx: DiagnosisContext): Explanation {
        val c = code.uppercase().substringBefore('-')
        val causes = (knowledge?.causes.orEmpty()).mapIndexed { i, cause ->
            // Database order is "most common first": start from a descending base score.
            Ranked(cause, 50 - i * 5)
        }.toMutableList()
        val general = mutableListOf<Evidence>()
        val steps = mutableListOf<String>()

        fun boost(keyword: Regex, by: Int, e: Evidence) {
            // Every matching cause gains score, the evidence is shown once (on the best match).
            val hits = causes.filter { keyword.containsMatchIn(it.cause) }
            hits.forEach { it.score += by }
            hits.maxByOrNull { it.score }?.evidence?.add(e) ?: general.add(e)
        }

        // ---- fuel trims (lean / rich)
        val ltftFf = ctx.freezeFrame["0107"]
        val rpmFf = ctx.freezeFrame["010C"]
        val ltftLive = ctx.live["0107"]
        val rpmLive = ctx.live["010C"]
        if (c in lean) {
            if (ltftFf != null && ltftFf > 10 && (rpmFf ?: 0.0) < 1200) {
                boost(Regex("(?i)vacuum|intake|PCV|leak"), 25, Evidence(Diagnosis.texts[0], listOf(fmt(ltftFf))))
            }
            if (ltftLive != null && rpmLive != null && rpmLive > 2000) {
                if (ltftLive > 10) {
                    boost(Regex("(?i)MAF|fuel pump|filter|injector"), 30, Evidence(texts[1], listOf(fmt(ltftLive))))
                    // Lean at load as well: a small leak would be swamped by the airflow, so it is less likely.
                    causes.filter { Regex("(?i)vacuum|PCV").containsMatchIn(it.cause) }.forEach { it.score -= 20 }
                }
                else if (ltftFf != null && ltftFf > 10) boost(Regex("(?i)vacuum|intake|leak"), 20, Evidence(texts[2]))
            }
            steps += texts[25]
        }
        if (c in rich) (ltftFf ?: ltftLive)?.takeIf { it < -10 }?.let { general += Evidence(texts[3], listOf(fmt(it))) }

        // ---- misfires (Mode 06 MIDs A2..AD = cylinders 1..12, TID 0B = EWMA misfire count)
        val perCylinder = ctx.mode06.filter { it.mid in 0xA2..0xAD && it.tid == 0x0B && it.value > 0 }
            .associate { (it.mid - 0xA1) to it.value }
        if (misfire.matches(c) || perCylinder.isNotEmpty()) {
            perCylinder.forEach { (cyl, n) -> general += Evidence(texts[4], listOf(cyl.toString(), fmt(n))) }
            val cylCode = c.takeLast(2).toIntOrNull()?.takeIf { c.startsWith("P03") && it in 1..12 }
            if (perCylinder.size >= 3 || c == "P0300") {
                boost(Regex("(?i)vacuum|fuel|pressure|compression|timing"), 20, Evidence(texts[5], listOf(perCylinder.size.coerceAtLeast(1).toString())))
            } else if (cylCode != null && (perCylinder.keys == setOf(cylCode) || perCylinder.isEmpty())) {
                boost(Regex("(?i)coil|plug|injector"), 15, Evidence(texts[6], listOf(cylCode.toString())))
            }
            steps += texts[26]
        }

        // ---- catalyst: fix the cause first
        if (c in setOf("P0420", "P0430")) {
            val upstream = ctx.otherCodes.filter { misfire.matches(it) || it in lean || it in rich }
            if (upstream.isNotEmpty()) boost(Regex("(?i)misfire|rich"), 30, Evidence(texts[7], listOf(upstream.joinToString(", "))))
            ctx.mode06.firstOrNull { it.mid == 0x21 || it.mid == 0x22 }?.let { t ->
                general += Evidence(texts[8], listOf(fmt(t.value), fmt(t.max)), supports = !t.passed)
            }
        }

        // ---- thermostat / overheating
        val coolant = ctx.live["0105"] ?: ctx.freezeFrame["0105"]
        if (c == "P0128" && coolant != null && (ctx.runMinutes ?: 0.0) >= 15 && coolant < 75) {
            boost(Regex("(?i)thermostat"), 30, Evidence(texts[9], listOf(fmt(coolant), fmt(ctx.runMinutes ?: 0.0))))
            steps += texts[27]
        }
        val overheating = coolant != null && coolant >= 115

        // ---- diesel particulate filter
        if (c in dpfCodes) {
            ctx.averageTripKm?.takeIf { it < 12 }?.let { boost(Regex("(?i)short trips"), 30, Evidence(texts[11], listOf(fmt(it)))) }
        }

        // ---- low voltage / network
        val uCodes = (ctx.otherCodes + c).filter { it.startsWith("U") }.distinct()
        val lowVolt = ctx.batteryV?.let { it < 12.0 } == true
        if (lowVolt) general += Evidence(texts[12], listOf("%.1f".format(ctx.batteryV)))
        if (uCodes.size >= 3) general += Evidence(texts[13], listOf(uCodes.joinToString(", ")))
        if ((lowVolt || uCodes.size >= 3) && (c.startsWith("U") || c.startsWith("B") || c.startsWith("C"))) {
            val proof = general.filter { e -> e.text == texts[12] || e.text == texts[13] }
            val existing = causes.firstOrNull { Regex("(?i)battery|voltage").containsMatchIn(it.cause) }
            if (existing != null) { existing.score += 40; existing.evidence += proof }
            else causes.add(0, Ranked(texts[14], 70).also { it.evidence += proof })
            general.removeAll(proof)
            steps += texts[28]
        }

        // ---- can I drive?
        val severity = knowledge?.severity ?: 0
        val (advice, reason) = when {
            overheating -> DriveAdvice.STOP to texts[16]
            c in stopCodes -> DriveAdvice.STOP to (if (c.startsWith("P001") || c.startsWith("P000")) texts[17] else texts[16])
            misfire.matches(c) && (perCylinder.values.any { it >= 20 } || c == "P0300") -> DriveAdvice.STOP to texts[15]
            misfire.matches(c) -> DriveAdvice.CAUTION to texts[15]
            c in dpfCodes -> DriveAdvice.CAUTION to texts[21]
            c in limpCodes -> DriveAdvice.CAUTION to texts[18]
            c.startsWith("C") -> DriveAdvice.CAUTION to texts[19]
            c.startsWith("B00") || c.startsWith("B01") -> DriveAdvice.CAUTION to texts[20]
            severity >= 3 -> DriveAdvice.CAUTION to texts[22]
            c.startsWith("P0") || c.startsWith("P2") -> DriveAdvice.OK to texts[23]
            else -> DriveAdvice.OK to texts[24]
        }
        steps += texts[29]

        val ranked = causes.sortedByDescending { it.score }.map { RankedCause(it.cause, it.score, it.evidence.toList()) }
        // General evidence (not tied to a cause) is attached to an empty-named entry the UI shows as "What the car shows".
        val withGeneral = if (general.isEmpty()) ranked else ranked + RankedCause("", 0, general.distinct())
        return Explanation(c, advice, reason, withGeneral, steps.distinct())
    }

    private class Ranked(val cause: String, var score: Int) { val evidence = mutableListOf<Evidence>() }

    private fun fmt(v: Double) = if (v == kotlin.math.floor(v)) "%.0f".format(v) else "%.1f".format(v)
}
