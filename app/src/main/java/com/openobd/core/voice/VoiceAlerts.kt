package com.openobd.core.voice

import com.openobd.core.diesel.RegenMonitor
import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.UnitSystem
import com.openobd.core.obd.display

/** Something to say. [text] is an English template; it and each of [args] are translated just before speaking. */
data class Announcement(
    val text: String, val args: List<String> = emptyList(),
    /** Important enough to also show as a notification (even with spoken alerts off). */
    val notify: Boolean = false,
)

/**
 * Built-in spoken warnings that need no setup: engine overheating, battery not charging and particulate filter
 * regenerations (when the car reports exhaust temperatures). Each condition must last a moment before it is
 * spoken and is not repeated for several minutes, so a noisy reading never nags the driver.
 */
class VoiceWatch(private val units: UnitSystem = UnitSystem.METRIC, private val diesel: Boolean = false) {
    companion object {
        /** Live data keys the watch needs (only supported ones are polled). */
        val keys = listOf("0105", "010C", "0142", "010D") + RegenMonitor.keys
        const val OVERHEAT_C = 110.0
        const val OVERHEAT_CLEAR_C = 104.0
        /** Well below any charging voltage, including the low setpoints of "smart" alternators. */
        const val NOT_CHARGING_V = 12.4
        private const val OVERHEAT_HOLD_MS = 5_000L
        private const val CHARGE_HOLD_MS = 30_000L
        private const val REPEAT_MS = 5 * 60_000L
        /** A cold engine pushed hard: coolant under this with the revs high for a few seconds. */
        const val COLD_C = 60.0
        private const val COLD_HOLD_MS = 4_000L
        /** Low fuel: under this range, or this share of the tank when the range is not known. */
        const val LOW_RANGE_KM = 60.0
        const val LOW_TANK_PCT = 10.0
        private const val LONG_REPEAT_MS = 20 * 60_000L

        val texts = listOf(
            "Warning: engine temperature %1\$s %2\$s. Stop safely and let the engine cool down.",
            "The battery is not charging: %1\$s %2\$s with the engine running. Check the alternator and its belt.",
            "Particulate filter cleaning started. Keep driving if you can and do not switch off the engine.",
            "Particulate filter cleaning finished.",
            "Alert: %1\$s %2\$s %3\$s.",
            "Connection to the car lost.",
            "Voice alerts are on.",
            "The engine is still cold (%1\$s %2\$s): take it easy until it warms up.",
            "Low fuel: about %1\$s %2\$s left.", "Low fuel: %1\$s %2\$s left in the tank.", "kilometres", "miles", "litres",
            "The particulate filter is still cleaning itself: if you can, do not switch the engine off yet.",
            "degrees", "kilometres per hour", "volts", "percent", "kilopascals", "revolutions per minute", "miles per hour", "degrees Fahrenheit", "psi",
        )

        /** Value with its unit written out, so a speech engine reads it naturally. */
        fun spoken(v: Double, unit: ObdUnit, system: UnitSystem, decimals: Int = 0): Pair<String, String> {
            val d = unit.display(v, system)
            val number = if (decimals == 0) "%.0f".format(d.value) else "%.${decimals}f".format(d.value)
            val word = when (d.unit) {
                ObdUnit.CELSIUS -> "degrees"; ObdUnit.FAHRENHEIT -> "degrees Fahrenheit"; ObdUnit.KMH -> "kilometres per hour"
                ObdUnit.MPH -> "miles per hour"; ObdUnit.VOLTS -> "volts"; ObdUnit.PERCENT -> "percent"; ObdUnit.KPA -> "kilopascals"
                ObdUnit.PSI -> "psi"; ObdUnit.RPM -> "revolutions per minute"; else -> d.unit.symbol
            }
            return number to word
        }
    }

    private val regen = RegenMonitor()
    private var regenActive: Boolean? = null
    private var hotSince: Long? = null
    private var lowVoltSince: Long? = null
    private var coldRevSince: Long? = null
    private val lastSaid = HashMap<String, Long>()

    private fun once(id: String, now: Long, repeatMs: Long = REPEAT_MS, a: () -> Announcement): Announcement? {
        val last = lastSaid[id]
        if (last != null && now - last < repeatMs) return null
        lastSaid[id] = now
        return a()
    }

    fun onCycle(v: Map<String, Double>, now: Long): List<Announcement> = buildList {
        val coolant = v["0105"]
        if (coolant != null && coolant >= OVERHEAT_C) {
            val since = hotSince ?: now.also { hotSince = it }
            if (now - since >= OVERHEAT_HOLD_MS) once("hot", now) {
                val (n, w) = spoken(coolant, ObdUnit.CELSIUS, units)
                Announcement("Warning: engine temperature %1\$s %2\$s. Stop safely and let the engine cool down.", listOf(n, w), notify = true)
            }?.let(::add)
        } else if (coolant == null || coolant < OVERHEAT_CLEAR_C) hotSince = null

        val volts = v["0142"]
        val running = (v["010C"] ?: 0.0) > 700
        if (volts != null && running && volts < NOT_CHARGING_V) {
            val since = lowVoltSince ?: now.also { lowVoltSince = it }
            if (now - since >= CHARGE_HOLD_MS) once("charge", now) {
                val (n, w) = spoken(volts, ObdUnit.VOLTS, units, 1)
                Announcement("The battery is not charging: %1\$s %2\$s with the engine running. Check the alternator and its belt.", listOf(n, w), notify = true)
            }?.let(::add)
        } else lowVoltSince = null

        // A cold engine revved hard (oil still thick): once a drive or so.
        val rpm = v["010C"]
        if (coolant != null && rpm != null && coolant < COLD_C && rpm > (if (diesel) 3000.0 else 3500.0)) {
            val since = coldRevSince ?: now.also { coldRevSince = it }
            if (now - since >= COLD_HOLD_MS) once("cold", now, LONG_REPEAT_MS) {
                val (n, w) = spoken(coolant, ObdUnit.CELSIUS, units)
                Announcement("The engine is still cold (%1\$s %2\$s): take it easy until it warms up.", listOf(n, w), notify = true)
            }?.let(::add)
        } else coldRevSince = null

        // Low fuel, from the tank estimate (range when the consumption is known, else the level).
        val range = v["CALC_RANGE"]
        val tank = v["CALC_TANK"]
        val litres = v["CALC_TANK_L"]
        if (range != null && range < LOW_RANGE_KM) once("fuel", now, LONG_REPEAT_MS) {
            val d = ObdUnit.KM.display(range, units)
            Announcement("Low fuel: about %1\$s %2\$s left.", listOf("%.0f".format(d.value), if (units == UnitSystem.IMPERIAL) "miles" else "kilometres"), notify = true)
        }?.let(::add)
        else if (range == null && tank != null && tank < LOW_TANK_PCT) once("fuel", now, LONG_REPEAT_MS) {
            if (litres != null) Announcement("Low fuel: %1\$s %2\$s left in the tank.", listOf("%.0f".format(litres), "litres"), notify = true)
            else Announcement("Low fuel: %1\$s %2\$s left in the tank.", listOf("%.0f".format(tank), "percent"), notify = true)
        }?.let(::add)

        val active = regen.onCycle(v, now).regenActive
        val was = regenActive
        if (active != null && was != null && active != was) {
            add(if (active) Announcement("Particulate filter cleaning started. Keep driving if you can and do not switch off the engine.")
                else Announcement("Particulate filter cleaning finished."))
        }
        if (active != null) regenActive = active
    }

    /** A user-defined threshold alert, e.g. "Alert: coolant temperature 112 degrees". */
    fun alert(sensorName: String, value: Double, unit: ObdUnit, decimals: Int): Announcement {
        val (n, w) = spoken(value, unit, units, decimals.coerceAtMost(1))
        return Announcement("Alert: %1\$s %2\$s %3\$s.", listOf(sensorName, n, w))
    }
}
