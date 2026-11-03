package com.openobd.core.battery

import kotlinx.serialization.Serializable

data class VoltSample(val t: Long, val v: Double)

enum class BatteryLevel(val label: String) { GOOD("Good"), WATCH("Keep an eye on it"), BAD("Needs attention"), UNKNOWN("Not measured") }

data class BatteryFinding(val level: BatteryLevel, val text: String, val args: List<String> = emptyList())

data class CrankResult(
    val restV: Double?,
    /** Lowest voltage while the starter turned the engine. */
    val minV: Double?,
    val crankMs: Long?,
    /** Voltage with the engine running, a few seconds after the start. */
    val chargingV: Double?,
    val adapterDropped: Boolean,
    val findings: List<BatteryFinding>,
) {
    val overall: BatteryLevel get() = findings.map { it.level }.filter { it != BatteryLevel.UNKNOWN }.maxByOrNull { it.ordinal } ?: BatteryLevel.UNKNOWN
}

/** A finished test, kept per car to compare over time. */
@Serializable
data class BatteryTestRecord(
    val id: String,
    val vehicleId: String,
    val time: Long,
    val restV: Double? = null,
    val minV: Double? = null,
    val crankMs: Long? = null,
    val chargingV: Double? = null,
    val level: String = BatteryLevel.UNKNOWN.name,
)

/**
 * Starting test with the adapter's own voltmeter (pin 16 of the OBD socket is wired straight to the battery):
 * the voltage at rest, the dip while the starter turns the engine and the charging voltage afterwards.
 * The cranking dip is the classic load test: a healthy 12 V battery stays above ~10 V, below 9.6 V it is
 * worn out or discharged (or the starter / cables draw too much).
 */
object CrankAnalysis {
    /** Below this the starter is turning (glow plugs and ignition loads alone do not pull a battery this low). */
    const val CRANK_START_V = 11.0
    /** Above this the alternator is charging, so the engine has started. */
    const val RUNNING_V = 13.0
    const val GOOD_CRANK_V = 10.0
    const val MIN_CRANK_V = 9.6

    val texts = listOf(
        "Good", "Keep an eye on it", "Needs attention", "Not measured",
        "The engine seems to be running already (%1\$s V). Switch it off, wait a minute and start the test again.",
        "Resting voltage %1\$s V: fully charged.",
        "Resting voltage %1\$s V: partly discharged. Short trips do not recharge the battery; a longer drive or a charger helps.",
        "Resting voltage %1\$s V: low. Charge the battery and test again.",
        "No engine start was detected.",
        "The voltage stayed above %1\$s V while starting: the battery delivers enough power.",
        "The voltage fell to %1\$s V while starting: still works, but the battery is getting weak. Cold mornings will be harder.",
        "The voltage fell to %1\$s V while starting: the battery is worn out or discharged (or the starter or its cables draw too much).",
        "The adapter switched off while starting, so the voltage fell very low (below about 8 V). Repeat the test; if it happens again, have the battery tested.",
        "The starter turned for %1\$s s. Long starts can point to glow plugs, fuel supply or sensors.",
        "Charging voltage %1\$s V: the alternator charges normally.",
        "Charging voltage %1\$s V: low. Many recent cars (Opel/Vauxhall among them) lower it on purpose to save fuel; check again after a few minutes of driving.",
        "Charging voltage %1\$s V: the alternator is not charging.",
        "Charging voltage %1\$s V: too high, the regulator may be faulty and can damage the battery.",
    )

    private fun f(v: Double) = "%.1f".format(v)
    private fun median(xs: List<Double>): Double? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    fun restOf(samples: List<VoltSample>): Double? = median(samples.map { it.v })

    /**
     * Analyses the samples taken after the rest measurement. [samples] start when the user is asked to start the
     * engine; [gaps] are the times the adapter did not answer.
     */
    fun analyze(
        restV: Double?,
        samples: List<VoltSample>,
        gaps: List<Long> = emptyList(),
        isDiesel: Boolean = false,
        /** When the engine computer first reported engine speed after the start (needed to spot a dead alternator). */
        rpmRunningAt: Long? = null,
    ): CrankResult {
        val findings = mutableListOf<BatteryFinding>()
        if (restV != null && restV > 13.2) {
            findings += BatteryFinding(BatteryLevel.UNKNOWN, "The engine seems to be running already (%1\$s V). Switch it off, wait a minute and start the test again.", listOf(f(restV)))
            return CrankResult(restV, null, null, null, false, findings)
        }
        restV?.let { r ->
            // Measured with the ignition on (instruments, fuel pump, glow plugs), so a little lower than a true rest.
            findings += when {
                r >= 12.4 -> BatteryFinding(BatteryLevel.GOOD, "Resting voltage %1\$s V: fully charged.", listOf(f(r)))
                r >= 12.1 -> BatteryFinding(BatteryLevel.WATCH, "Resting voltage %1\$s V: partly discharged. Short trips do not recharge the battery; a longer drive or a charger helps.", listOf(f(r)))
                else -> BatteryFinding(BatteryLevel.BAD, "Resting voltage %1\$s V: low. Charge the battery and test again.", listOf(f(r)))
            }
        }
        // Running: the alternator lifts the voltage clearly above the resting value (smart alternators may stay below 13 V).
        val runningV = minOf(RUNNING_V, (restV ?: 12.7) + 0.3)
        val start = samples.firstOrNull { it.v < CRANK_START_V }
        val gapStart = gaps.firstOrNull()
        // The adapter browning out during the start is itself a sign of a deep voltage dip.
        val dropped = gapStart != null && (start == null || gapStart >= start.t - 500) &&
            (samples.any { it.t > gapStart && it.v > runningV } || rpmRunningAt != null)
        val crankFrom = start?.t ?: if (dropped) gapStart else null
        if (crankFrom == null) {
            findings += BatteryFinding(BatteryLevel.UNKNOWN, "No engine start was detected.")
            return CrankResult(restV, null, null, null, false, findings)
        }
        val runningAt = listOfNotNull(samples.firstOrNull { it.t > crankFrom && it.v > runningV }?.t, rpmRunningAt?.takeIf { it > crankFrom }).minOrNull()
        val crankWindow = samples.filter { it.t >= crankFrom && (runningAt == null || it.t < runningAt) }
        // The first milliseconds of a start draw a brief inrush spike; the load-test limits apply to the sustained
        // cranking voltage, so single readings are smoothed out with a 3-sample sliding median.
        val minV = if (crankWindow.size >= 3) crankWindow.windowed(3).minOf { w -> w.map { it.v }.sorted()[1] } else crankWindow.minOfOrNull { it.v }
        val crankMs = runningAt?.let { it - crankFrom }
        when {
            dropped -> findings += BatteryFinding(BatteryLevel.BAD, "The adapter switched off while starting, so the voltage fell very low (below about 8 V). Repeat the test; if it happens again, have the battery tested.")
            minV == null -> {}
            minV >= GOOD_CRANK_V -> findings += BatteryFinding(BatteryLevel.GOOD, "The voltage stayed above %1\$s V while starting: the battery delivers enough power.", listOf(f(minV)))
            minV >= MIN_CRANK_V -> findings += BatteryFinding(BatteryLevel.WATCH, "The voltage fell to %1\$s V while starting: still works, but the battery is getting weak. Cold mornings will be harder.", listOf(f(minV)))
            else -> findings += BatteryFinding(BatteryLevel.BAD, "The voltage fell to %1\$s V while starting: the battery is worn out or discharged (or the starter or its cables draw too much).", listOf(f(minV)))
        }
        if (crankMs != null && crankMs > (if (isDiesel) 4000 else 3000)) {
            findings += BatteryFinding(BatteryLevel.WATCH, "The starter turned for %1\$s s. Long starts can point to glow plugs, fuel supply or sensors.", listOf(f(crankMs / 1000.0)))
        }
        // Charging: the last seconds of the recording, once the alternator has settled.
        val after = runningAt?.let { r -> samples.filter { it.t > r + 3000 } }.orEmpty()
        val chargingV = median(after.takeLast(maxOf(1, after.size / 2)).map { it.v })
        chargingV?.let { c ->
            findings += when {
                c > 15.0 -> BatteryFinding(BatteryLevel.BAD, "Charging voltage %1\$s V: too high, the regulator may be faulty and can damage the battery.", listOf(f(c)))
                c >= 13.4 -> BatteryFinding(BatteryLevel.GOOD, "Charging voltage %1\$s V: the alternator charges normally.", listOf(f(c)))
                c >= 12.7 -> BatteryFinding(BatteryLevel.WATCH, "Charging voltage %1\$s V: low. Many recent cars (Opel/Vauxhall among them) lower it on purpose to save fuel; check again after a few minutes of driving.", listOf(f(c)))
                else -> BatteryFinding(BatteryLevel.BAD, "Charging voltage %1\$s V: the alternator is not charging.", listOf(f(c)))
            }
        }
        return CrankResult(restV, minV, crankMs, chargingV, dropped, findings)
    }
}
