package com.openobd.core.obd

/** Sensors calculated from standard PIDs (fuel economy, boost, power ...). Keys start with "CALC_". */
object DerivedSensors {
    data class Params(val stoichAfr: Double = 14.7, val fuelDensityGPerL: Double = 745.0, val displacementL: Double = 2.0, val volumetricEfficiency: Double = 0.85)

    private val C = SensorCategory.CALCULATED

    val FUEL = SensorDef("CALC_FUEL", -1, "Fuel flow (calculated)", "Fuel flow", ObdUnit.LITERS_PER_HOUR, 0.0, 30.0, C, 2) { null }
    val CONSUMPTION = SensorDef("CALC_CONS", -1, "Instant fuel consumption", "Economy", ObdUnit.L_PER_100KM, 0.0, 30.0, C, 1) { null }
    val BOOST = SensorDef("CALC_BOOST", -1, "Boost / vacuum (MAP - barometric)", "Boost", ObdUnit.KPA, -100.0, 250.0, C, 0) { null }
    val POWER = SensorDef("CALC_POWER", -1, "Engine power (from torque PIDs)", "Power", ObdUnit.KILOWATTS, 0.0, 300.0, C, 0) { null }
    val TORQUE = SensorDef("CALC_TORQUE", -1, "Engine torque (from torque PIDs)", "Torque", ObdUnit.NEWTON_METERS, 0.0, 600.0, C, 0) { null }
    val AFR = SensorDef("CALC_AFR", -1, "Commanded air-fuel ratio", "AFR", ObdUnit.AFR, 8.0, 22.0, C, 1) { null }
    val AIRFLOW = SensorDef("CALC_AIRFLOW", -1, "Air flow (MAF or speed-density estimate)", "Air flow", ObdUnit.GRAMS_PER_SEC, 0.0, 300.0, C, 1) { null }

    val all: List<SensorDef> = listOf(FUEL, CONSUMPTION, BOOST, POWER, TORQUE, AFR, AIRFLOW)
    val byKey: Map<String, SensorDef> = all.associateBy { it.key }

    /** Alternative sets of input sensor keys; the first set whose PIDs are all supported is used. */
    private val inputs: Map<String, List<List<String>>> = mapOf(
        "CALC_AIRFLOW" to listOf(listOf("0110"), listOf("0166_A"), listOf("010B", "010C", "010F")),
        "CALC_FUEL" to listOf(listOf("015E"), listOf("0110", "0144"), listOf("0110"), listOf("0166_A"), listOf("010B", "010C", "010F")),
        "CALC_CONS" to listOf(listOf("015E", "010D"), listOf("0110", "0144", "010D"), listOf("0110", "010D"), listOf("0166_A", "010D"), listOf("010B", "010C", "010F", "010D")),
        "CALC_BOOST" to listOf(listOf("010B", "0133"), listOf("0187_A", "0133"), listOf("010B")),
        "CALC_POWER" to listOf(listOf("0162", "0163", "010C")),
        "CALC_TORQUE" to listOf(listOf("0162", "0163")),
        "CALC_AFR" to listOf(listOf("0144")),
    )

    private fun pidOf(key: String) = key.substring(2, 4).toInt(16)

    /** Input sensor keys needed for [key] given the supported PIDs, or null if it cannot be computed. */
    fun requirements(key: String, supported: Set<Int>): List<String>? =
        inputs[key]?.firstOrNull { set -> set.all { pidOf(it) in supported } }

    fun isAvailable(key: String, supported: Set<Int>) = requirements(key, supported) != null

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
        val fuelLh = v["015E"] ?: airflow?.let { it / (p.stoichAfr * lambda) / p.fuelDensityGPerL * 3600.0 }
        fuelLh?.let { out["CALC_FUEL"] = it }
        val speed = v["010D"]
        if (fuelLh != null && speed != null && speed > 3.0) out["CALC_CONS"] = (fuelLh / speed * 100.0).coerceAtMost(99.9)
        val map = v["010B"] ?: v["0187_A"]
        if (map != null) out["CALC_BOOST"] = map - (v["0133"] ?: 101.3)
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
object SensorCatalog {
    fun get(key: String): SensorDef? = Pids.sensors[key] ?: DerivedSensors.byKey[key] ?: com.openobd.core.brand.CustomSensors.get(key)

    /** All sensors (PID based + calculated) available for the given supported PID set. */
    fun available(supported: Set<Int>): List<SensorDef> {
        val base = Pids.all.values.filter { it.pid in supported }.flatMap { it.sensors }
        val calc = DerivedSensors.all.filter { DerivedSensors.isAvailable(it.key, supported) }
        return calc + com.openobd.core.brand.CustomSensors.sensors + base
    }
}
