package com.openobd.ui.components

import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.SensorDef
import com.openobd.core.obd.UnitSystem
import com.openobd.core.obd.display

data class Formatted(val value: String, val unit: String)

fun formatSensor(def: SensorDef, value: Double?, units: UnitSystem): Formatted {
    if (value == null) return Formatted("—", unitLabel(def, units))
    def.labeler?.let { return Formatted(it(value), "") }
    def.customUnit?.let { return Formatted(formatNumber(value, def.decimals), it) }
    val d = def.unit.display(value, units)
    val decimals = when {
        d.unit == ObdUnit.MPG -> 1
        d.unit == ObdUnit.PSI && def.decimals == 0 -> 1
        else -> def.decimals
    }
    return Formatted(formatNumber(d.value, decimals), d.unit.symbol)
}

fun unitLabel(def: SensorDef, units: UnitSystem): String = def.customUnit ?: def.unit.display(0.0, units).unit.symbol

fun formatNumber(v: Double, decimals: Int): String = if (decimals <= 0) "%.0f".format(v) else "%.${decimals}f".format(v)

/** Gauge range converted to the display unit system. */
fun displayRange(def: SensorDef, units: UnitSystem): Pair<Double, Double> =
    if (units == UnitSystem.IMPERIAL) def.unit.rangeToImperial(def.min, def.max) else def.min to def.max

fun displayValue(def: SensorDef, value: Double, units: UnitSystem): Double = def.unit.display(value, units).value

fun formatDuration(seconds: Double): String {
    val s = seconds.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
