package com.openobd.core.obd

enum class ObdUnit(val symbol: String) {
    NONE(""), PERCENT("%"), CELSIUS("°C"), KMH("km/h"), KPA("kPa"), PA("Pa"), RPM("rpm"), DEGREES("°"),
    GRAMS_PER_SEC("g/s"), VOLTS("V"), MILLIAMPS("mA"), SECONDS("s"), MINUTES("min"), KM("km"), LAMBDA("λ"),
    LITERS_PER_HOUR("L/h"), NEWTON_METERS("N·m"), L_PER_100KM("L/100km"), KILOWATTS("kW"), PPM("ppm"),
    KG_PER_HOUR("kg/h"), MG_PER_STROKE("mg/st"), COUNT(""), RATIO(":1"), LITERS("L"), GEAR(""), AFR("AFR"),
    G_FORCE("g"), BAR("bar"),

    // Imperial counterparts
    FAHRENHEIT("°F"), MPH("mph"), PSI("psi"), INH2O("inH₂O"), MILES("mi"), MPG("mpg"), GAL_PER_HOUR("gal/h"),
    LB_FT("lb·ft"), HP("hp"), LB_PER_MIN("lb/min"), LB_PER_HOUR("lb/h"), GALLONS("gal");

    /** Converts a value in this (metric) unit to imperial, returning the new unit. */
    fun toImperial(v: Double): Pair<Double, ObdUnit> = when (this) {
        CELSIUS -> v * 9.0 / 5.0 + 32.0 to FAHRENHEIT
        KMH -> v * 0.621371 to MPH
        KPA -> v * 0.145038 to PSI
        BAR -> v * 14.5038 to PSI
        PA -> v * 0.00401865 to INH2O
        KM -> v * 0.621371 to MILES
        L_PER_100KM -> (if (v > 0.01) 235.215 / v else 0.0) to MPG
        LITERS_PER_HOUR -> v * 0.264172 to GAL_PER_HOUR
        NEWTON_METERS -> v * 0.737562 to LB_FT
        KILOWATTS -> v * 1.34102 to HP
        GRAMS_PER_SEC -> v * 0.132277 to LB_PER_MIN
        KG_PER_HOUR -> v * 2.20462 to LB_PER_HOUR
        LITERS -> v * 0.264172 to GALLONS
        else -> v to this
    }

    /** Converts min/max ranges; for inverted conversions (L/100km -> mpg) returns a sensible range. */
    fun rangeToImperial(min: Double, max: Double): Pair<Double, Double> = when (this) {
        L_PER_100KM -> 0.0 to 100.0
        else -> toImperial(min).first to toImperial(max).first
    }
}

enum class UnitSystem { METRIC, IMPERIAL }

data class DisplayValue(val value: Double, val unit: ObdUnit) {
    fun format(decimals: Int): String = "%.${decimals}f".format(value)
}

fun ObdUnit.display(v: Double, system: UnitSystem): DisplayValue =
    if (system == UnitSystem.IMPERIAL) toImperial(v).let { DisplayValue(it.first, it.second) } else DisplayValue(v, this)
