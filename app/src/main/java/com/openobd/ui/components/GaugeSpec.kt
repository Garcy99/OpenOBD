package com.openobd.ui.components

import com.openobd.core.obd.UnitSystem

/** How one dashboard gauge is drawn. */
enum class GaugeStyle(val code: String, val label: String) {
    ARC("a", "Arc"), NEEDLE("n", "Needle"), BAR("b", "Bar"), DIGITAL("d", "Number"), TANK("t", "Tank");

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code }
    }
}

/**
 * One dashboard slot: the sensor and how the driver likes it shown. Stored as a single string in the
 * comma-separated dashboard setting, e.g. `010C@n;c=E53935;hi=6000;w=4500;dp=0`.
 * Older entries (`010C`, `010C@d`) read as the theme's default style and a big number.
 *
 * Range and warning values are in the display units of [units] when they were set; they are ignored if the
 * user later switches between metric and imperial, so a 120 °C warning never turns into 120 °F.
 */
data class GaugeSpec(
    val key: String,
    /** Null = the dashboard theme's style. */
    val style: GaugeStyle? = null,
    /** Accent colour as RRGGBB; null = the theme's. */
    val color: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    /** Turns the gauge red at or above this value. */
    val warnAbove: Double? = null,
    /** Turns the gauge red at or below this value (low voltage, low oil temperature ...). */
    val warnBelow: Double? = null,
    val decimals: Int? = null,
    val units: UnitSystem = UnitSystem.METRIC,
) {
    fun encode(): String = buildString {
        append(key)
        if (style != null) append('@').append(style.code)
        val opts = listOfNotNull(
            color?.let { "c=$it" }, min?.let { "lo=${num(it)}" }, max?.let { "hi=${num(it)}" },
            warnAbove?.let { "w=${num(it)}" }, warnBelow?.let { "wl=${num(it)}" }, decimals?.let { "dp=$it" },
            if (units == UnitSystem.IMPERIAL && (min != null || max != null || warnAbove != null || warnBelow != null)) "u=I" else null,
        )
        if (opts.isNotEmpty()) {
            if (style == null) append('@')
            opts.forEach { append(';').append(it) }
        }
    }

    /** Range, warnings and decimals only apply in the unit system they were set in. */
    fun appliesTo(current: UnitSystem) = units == current

    companion object {
        private fun num(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else v.toString()

        fun parse(entry: String): GaugeSpec {
            val key = entry.substringBefore('@').substringBefore(';')
            val rest = entry.substringAfter('@', "")
            val style = GaugeStyle.of(rest.substringBefore(';').ifBlank { null })
            val opts = rest.split(';').drop(1).mapNotNull { o -> o.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return GaugeSpec(
                key = key, style = style,
                color = opts["c"]?.takeIf { Regex("[0-9A-Fa-f]{6}").matches(it) }?.uppercase(),
                min = opts["lo"]?.toDoubleOrNull(), max = opts["hi"]?.toDoubleOrNull(),
                warnAbove = opts["w"]?.toDoubleOrNull(), warnBelow = opts["wl"]?.toDoubleOrNull(),
                decimals = opts["dp"]?.toIntOrNull()?.coerceIn(0, 3),
                units = if (opts["u"] == "I") UnitSystem.IMPERIAL else UnitSystem.METRIC,
            )
        }
    }
}

/** Whole-dashboard look. Colours are RRGGBB. */
enum class GaugeTheme(
    val id: String,
    val label: String,
    /** Value arc / bar gradient, start to end. */
    val accent: List<Long>,
    /** Card background; null = the app theme's surface. */
    val face: Long?,
    /** Numbers and labels on [face]; null = the app theme's. */
    val ink: Long?,
    val needle: Long,
    /** Style for gauges that do not choose one. */
    val defaultStyle: GaugeStyle,
) {
    NEON("neon", "Neon", listOf(0xFF22D3EE, 0xFF3B82F6, 0xFF8B5CF6), null, null, 0xFFFFFFFF, GaugeStyle.ARC),
    CLASSIC("classic", "Classic", listOf(0xFF212121, 0xFF212121), 0xFFF5F5F0, 0xFF1A1A1A, 0xFFD32F2F, GaugeStyle.NEEDLE),
    SPORT("sport", "Sport", listOf(0xFFFFB300, 0xFFFF6D00, 0xFFE53935), 0xFF161616, 0xFFF5F5F5, 0xFFFF3D00, GaugeStyle.NEEDLE),
    AMBER("amber", "Retro amber", listOf(0xFFFFB000, 0xFFFFB000), 0xFF0E0A02, 0xFFFFB000, 0xFFFFB000, GaugeStyle.DIGITAL),
    ICE("ice", "Ice", listOf(0xFFB3E5FC, 0xFF29B6F6, 0xFF0277BD), 0xFF0B1A26, 0xFFE1F5FE, 0xFFE1F5FE, GaugeStyle.ARC),
    RACING("racing", "Racing green", listOf(0xFFC6FF00, 0xFF64DD17, 0xFF00C853), 0xFF07170D, 0xFFE8F5E9, 0xFFC6FF00, GaugeStyle.BAR),
    MINIMAL("minimal", "Minimal", listOf(0xFF9E9E9E, 0xFFFFFFFF), null, null, 0xFFFFFFFF, GaugeStyle.ARC);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NEON
    }
}

/** Accent colours offered for a single gauge. */
val GaugeColors = listOf("22D3EE", "3B82F6", "8B5CF6", "EC4899", "E53935", "FF6D00", "FFB300", "C6FF00", "00C853", "FFFFFF")
