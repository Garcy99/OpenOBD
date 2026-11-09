package com.openobd.core.obd

/** Sensors calculated from standard PIDs (fuel economy, boost, power ...). Keys start with "CALC_". */
object DerivedSensors {
    data class Params(
        val stoichAfr: Double = 14.7,
        val fuelDensityGPerL: Double = 745.0,
        val displacementL: Double = 2.0,
        val volumetricEfficiency: Double = 0.85,
        /**
         * Diesels run with far more air than stoichiometric (lambda 1.3 at full load, 5+ at idle), so air flow
         * says little about fuel: without the fuel-rate PID their fuel is estimated from torque or load instead.
         */
        val diesel: Boolean = false,
        /** Rated power from the car's specifications, for the load-based diesel estimate. */
        val maxPowerKw: Double? = null,
        /** Real / estimated consumption learnt from the fuel log; applied to estimates, not to the fuel-rate PID. */
        val calibration: Double = 1.0,
    )

    /** Version of the fuel estimate; trips store it so calibration only compares like with like. */
    const val FUEL_MODEL = 2
    /** Brake-specific fuel consumption of a modern turbo diesel, g/kWh. */
    private const val DIESEL_BSFC = 215.0

    private val C = SensorCategory.CALCULATED

    val FUEL = SensorDef("CALC_FUEL", -1, "Fuel flow (calculated)", "Fuel flow", ObdUnit.LITERS_PER_HOUR, 0.0, 30.0, C, 2) { null }
    val CONSUMPTION = SensorDef("CALC_CONS", -1, "Instant fuel consumption", "Economy", ObdUnit.L_PER_100KM, 0.0, 30.0, C, 1) { null }
    /** Relative boost in bar, from the widest-range pressure sensor the car has (PID 0B stops at 255 kPa absolute). */
    val BOOST = SensorDef("CALC_BOOST", -1, "Boost / vacuum (MAP - barometric)", "Boost", ObdUnit.BAR, -1.0, 2.5, C, 2) { null }
    /** What the driver asks for: the accelerator pedal when reported (diesels' throttle flap barely moves), else the throttle. */
    val PEDAL = SensorDef("CALC_PEDAL", -1, "Accelerator (pedal, or throttle when not reported)", "Accelerator", ObdUnit.PERCENT, 0.0, 100.0, C, 0) { null }
    val POWER = SensorDef("CALC_POWER", -1, "Engine power (from torque PIDs)", "Power", ObdUnit.KILOWATTS, 0.0, 300.0, C, 0) { null }
    val TORQUE = SensorDef("CALC_TORQUE", -1, "Engine torque (from torque PIDs)", "Torque", ObdUnit.NEWTON_METERS, 0.0, 600.0, C, 0) { null }
    val AFR = SensorDef("CALC_AFR", -1, "Commanded air-fuel ratio", "AFR", ObdUnit.AFR, 8.0, 22.0, C, 1) { null }
    val AIRFLOW = SensorDef("CALC_AIRFLOW", -1, "Air flow (MAF or speed-density estimate)", "Air flow", ObdUnit.GRAMS_PER_SEC, 0.0, 300.0, C, 1) { null }

    val all: List<SensorDef> = listOf(FUEL, CONSUMPTION, BOOST, PEDAL, POWER, TORQUE, AFR, AIRFLOW)
    val byKey: Map<String, SensorDef> = all.associateBy { it.key }

    /** Alternative sets of input sensor keys; the first set whose PIDs are all supported is used. */
    private val inputs: Map<String, List<List<String>>> = mapOf(
        "CALC_AIRFLOW" to listOf(listOf("0110"), listOf("0166_A"), listOf("010B", "010C", "010F")),
        "CALC_FUEL" to listOf(listOf("015E"), listOf("0110", "0144"), listOf("0110"), listOf("0166_A"), listOf("010B", "010C", "010F")),
        "CALC_CONS" to listOf(listOf("015E", "010D"), listOf("0110", "0144", "010D"), listOf("0110", "010D"), listOf("0166_A", "010D"), listOf("010B", "010C", "010F", "010D")),
        "CALC_BOOST" to listOf(listOf("0170_AS", "0133"), listOf("0187_A", "0133"), listOf("010B", "0133"), listOf("0170_AS"), listOf("0187_A"), listOf("010B")),
        "CALC_PEDAL" to listOf(listOf("015A"), listOf("0149"), listOf("0145"), listOf("0111")),
        "CALC_POWER" to listOf(listOf("0162", "0163", "010C")),
        "CALC_TORQUE" to listOf(listOf("0162", "0163")),
        "CALC_AFR" to listOf(listOf("0144")),
    )

    private fun pidOf(key: String) = key.substring(2, 4).toInt(16)

    private val dieselInputs: Map<String, List<List<String>>> = mapOf(
        "CALC_FUEL" to listOf(listOf("015E"), listOf("0162", "0163", "010C"), listOf("0104", "010C")),
        "CALC_CONS" to listOf(listOf("015E", "010D"), listOf("0162", "0163", "010C", "010D"), listOf("0104", "010C", "010D")),
    )

    /** Input sensor keys needed for [key] given the supported PIDs, or null if it cannot be computed. */
    fun requirements(key: String, supported: Set<Int>, diesel: Boolean = false): List<String>? =
        ((if (diesel) dieselInputs[key] else null) ?: inputs[key])?.firstOrNull { set -> set.all { pidOf(it) in supported } }

    fun isAvailable(key: String, supported: Set<Int>) = requirements(key, supported) != null

    /**
     * Diesel fuel flow in L/h without the fuel-rate PID: power from actual torque (or, failing that, from
     * calculated load at this engine speed), times a typical BSFC, plus friction losses while fuelling.
     * Null when neither is available.
     */
    fun dieselFuelLh(v: Map<String, Double>, p: Params): Double? {
        val rpm = v["010C"] ?: return null
        if (rpm < 300) return 0.0
        val tq = v["0162"]; val ref = v["0163"]
        val ratedKw = p.maxPowerKw ?: (p.displacementL * 55.0)
        val powerKw = when {
            tq != null && ref != null -> (tq / 100.0 * ref * rpm / 9549.0).coerceAtLeast(0.0)
            v["0104"] != null -> v.getValue("0104") / 100.0 * ratedKw * (rpm / 4000.0).coerceAtMost(1.1)
            else -> return null
        }
        val load = v["0104"]
        if (load != null && load < 2.0 && rpm > 1100) return 0.0 // overrun fuel cut
        val friction = p.displacementL * 0.25 * (rpm / 800.0)
        return (friction + powerKw * DIESEL_BSFC / p.fuelDensityGPerL).coerceAtMost(ratedKw * DIESEL_BSFC * 1.3 / p.fuelDensityGPerL)
    }

    fun compute(v: Map<String, Double>, p: Params): Map<String, Double> {
        val out = HashMap<String, Double>()
        val airflow = v["0110"] ?: v["0166_A"] ?: run {
            val map = v["010B"]; val rpm = v["010C"]; val iat = v["010F"]
            if (map != null && rpm != null && iat != null)
                map * p.displacementL * rpm * p.volumetricEfficiency / (287.05 * (iat + 273.15) * 120.0) * 1000.0
            else null
        }
        airflow?.let { out["CALC_AIRFLOW"] = it }
        val lambda = v["0144"]?.takeIf { it in 0.5..3.0 } ?: 1.0
        val estimate = if (p.diesel) dieselFuelLh(v, p) else airflow?.let { it / (p.stoichAfr * lambda) / p.fuelDensityGPerL * 3600.0 }
        val fuelLh = v["015E"] ?: estimate?.let { it * p.calibration }
        fuelLh?.let { out["CALC_FUEL"] = it }
        val speed = v["010D"]
        if (fuelLh != null && speed != null && speed > 3.0) out["CALC_CONS"] = (fuelLh / speed * 100.0).coerceAtMost(99.9)
        val map = v["0170_AS"] ?: v["0187_A"] ?: v["010B"]
        if (map != null) out["CALC_BOOST"] = (map - (v["0133"]?.takeIf { it in 60.0..110.0 } ?: 101.3)) / 100.0
        val pedal = v["015A"] ?: v["0149"]?.let { ((it - 15.0) / 65.0 * 100.0).coerceIn(0.0, 100.0) } ?: v["0145"] ?: v["0111"]
        pedal?.let { out["CALC_PEDAL"] = it }
        val tq = v["0162"]; val ref = v["0163"]
        if (tq != null && ref != null) {
            val nm = tq / 100.0 * ref
            out["CALC_TORQUE"] = nm.coerceAtLeast(0.0)
            v["010C"]?.let { rpm -> out["CALC_POWER"] = (nm * rpm / 9549.0).coerceAtLeast(0.0) }
        }
        v["0144"]?.let { out["CALC_AFR"] = it * p.stoichAfr }
        return out
    }
}

/** Unified lookup over PID sensors and calculated sensors. */
/**
 * Gauges that read whichever source the car has: oil temperature from the standard PID or the maker's, oil pressure
 * from the maker's (no standard PID exists).
 */
object VirtualSensors {
    private val C = SensorCategory.ENGINE
    val OIL_TEMP = SensorDef("OIL_TEMP", -3, "Engine oil temperature", "Oil temp", ObdUnit.CELSIUS, -40.0, 160.0, C, 0) { null }
    val OIL_PRESS = SensorDef("OIL_PRESS", -3, "Engine oil pressure", "Oil pressure", ObdUnit.BAR, 0.0, 7.0, C, 1) { null }
    val all = listOf(OIL_TEMP, OIL_PRESS)
    val byKey = all.associateBy { it.key }

    private val temp = Regex("(oil|öl|aceite|huile|olio).*(temp)|(temp).*(oil|aceite|huile|olio)|\\beot\\b", RegexOption.IGNORE_CASE)
    private val press = Regex("(oil|öl|aceite|huile|olio).*(press|druck)|(press|druck).*(oil|aceite|huile|olio)|\\beop\\b", RegexOption.IGNORE_CASE)

    /** The sensor that feeds [key] on this car ([key] itself when it is not virtual), or null when it has none. */
    fun resolve(key: String, supported: Set<Int>, custom: Collection<SensorDef> = com.openobd.core.brand.CustomSensors.sensors): String? = when (key) {
        OIL_TEMP.key -> "015C".takeIf { 0x5C in supported } ?: custom.firstOrNull { temp.containsMatchIn(it.name) && !it.name.contains("calc", true) }?.key
        OIL_PRESS.key -> custom.firstOrNull { press.containsMatchIn(it.name) && !it.name.contains("control", true) && !it.name.contains("solenoid", true) }?.key
        else -> key
    }
}

object SensorCatalog {
    fun get(key: String): SensorDef? = Pids.sensors[key] ?: DerivedSensors.byKey[key] ?: VirtualSensors.byKey[key] ?: com.openobd.core.brand.CustomSensors.get(key)

    /** All sensors (PID based + calculated) available for the given supported PID set. */
    fun available(supported: Set<Int>): List<SensorDef> {
        val base = Pids.all.values.filter { it.pid in supported }.flatMap { it.sensors }
        val calc = DerivedSensors.all.filter { DerivedSensors.isAvailable(it.key, supported) }
        return calc + com.openobd.core.brand.CustomSensors.sensors + base
    }
}
