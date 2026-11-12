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

    /** The fuel tank (see [FuelTankEstimator]): how full, how many litres, how far it goes. */
    val TANK = SensorDef("CALC_TANK", -1, "Fuel tank", "Fuel tank", ObdUnit.PERCENT, 0.0, 100.0, C, 0) { null }
    val TANK_L = SensorDef("CALC_TANK_L", -1, "Fuel in the tank", "Fuel left", ObdUnit.LITERS, 0.0, 120.0, C, 1) { null }
    val RANGE = SensorDef("CALC_RANGE", -1, "Range with the fuel left", "Range", ObdUnit.KM, 0.0, 1500.0, C, 0) { null }
    val tankKeys = setOf(TANK.key, TANK_L.key, RANGE.key)

    val all: List<SensorDef> = listOf(FUEL, CONSUMPTION, BOOST, PEDAL, POWER, TORQUE, AFR, AIRFLOW, TANK, TANK_L, RANGE)
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
    fun requirements(key: String, supported: Set<Int>, diesel: Boolean = false): List<String>? {
        // The tank: the level the car reports and the fuel burnt (either is enough to keep an estimate).
        if (key in tankKeys) {
            val fuel = requirements("CALC_FUEL", supported, diesel).orEmpty()
            val level = if (0x2F in supported) listOf("012F") else emptyList()
            if (level.isEmpty() && fuel.isEmpty()) return null
            return (level + fuel + (if (0x0D in supported) listOf("010D") else emptyList())).distinct()
        }
        return ((if (diesel) dieselInputs[key] else null) ?: inputs[key])?.firstOrNull { set -> set.all { pidOf(it) in supported } }
    }

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
 * and tyre pressures from the maker's (no standard PID exists), built in or imported (OBDb, PID packs).
 */
object VirtualSensors {
    private val C = SensorCategory.ENGINE
    val OIL_TEMP = SensorDef("OIL_TEMP", -3, "Engine oil temperature", "Oil temp", ObdUnit.CELSIUS, -40.0, 160.0, C, 0) { null }
    val OIL_PRESS = SensorDef("OIL_PRESS", -3, "Engine oil pressure", "Oil pressure", ObdUnit.BAR, 0.0, 7.0, C, 1) { null }
    private val V = SensorCategory.VEHICLE
    val TYRE_FL = SensorDef("TYRE_FL", -3, "Tyre pressure front left", "Tyre FL", ObdUnit.BAR, 0.0, 4.0, V, 2) { null }
    val TYRE_FR = SensorDef("TYRE_FR", -3, "Tyre pressure front right", "Tyre FR", ObdUnit.BAR, 0.0, 4.0, V, 2) { null }
    val TYRE_RL = SensorDef("TYRE_RL", -3, "Tyre pressure rear left", "Tyre RL", ObdUnit.BAR, 0.0, 4.0, V, 2) { null }
    val TYRE_RR = SensorDef("TYRE_RR", -3, "Tyre pressure rear right", "Tyre RR", ObdUnit.BAR, 0.0, 4.0, V, 2) { null }
    val tyres = listOf(TYRE_FL, TYRE_FR, TYRE_RL, TYRE_RR)
    // Diesel: the standard PIDs when the car has them, else the engine computer's own values (GM / Opel).
    private val A = SensorCategory.AIR
    val BOOST_TARGET = SensorDef("BOOST_TARGET", -3, "Boost pressure asked for (absolute)", "Boost target", ObdUnit.KPA, 0.0, 300.0, A, 0) { null }
    val BOOST_ACTUAL = SensorDef("BOOST_ACTUAL", -3, "Boost pressure measured (absolute)", "Boost actual", ObdUnit.KPA, 0.0, 300.0, A, 0) { null }
    private val E = SensorCategory.EMISSIONS
    val DPF_SOOT = SensorDef("DPF_SOOT", -3, "Particulate filter soot load", "DPF load", ObdUnit.PERCENT, 0.0, 100.0, E, 0) { null }
    val DPF_TEMP = SensorDef("DPF_TEMP", -3, "Particulate filter inlet temperature", "DPF temp", ObdUnit.CELSIUS, 0.0, 800.0, E, 0) { null }
    val DPF_KM = SensorDef("DPF_KM", -3, "Distance since the last filter regeneration", "Since regen", ObdUnit.KM, 0.0, 1500.0, E, 0) { null }
    val diesel = listOf(BOOST_TARGET, BOOST_ACTUAL, DPF_SOOT, DPF_TEMP, DPF_KM)
    val all = listOf(OIL_TEMP, OIL_PRESS) + tyres + diesel
    val byKey = all.associateBy { it.key }

    private val tyre = Regex("tire|tyre|reifen|neum|pneu|gomm", RegexOption.IGNORE_CASE)
    private val pressure = Regex("press|druck", RegexOption.IGNORE_CASE)
    /** Set points, warnings, temperatures, inner twin tyres, the spare ...: not the pressure of the wheel itself. */
    private val notTheWheel = Regex("set|warn|temp|diffus|inner|interior|spare|repuesto|sensor id|battery|bater|status|thresh|limit|adapt|nominal|target",
        RegexOption.IGNORE_CASE)
    private val front = Regex("front|delanter|vorn|avant|anterior")
    private val rear = Regex("rear|back|traser|hinten|arri[eè]re|posterior")
    private val left = Regex("left|izquierd|links|gauche|sinistr")
    private val right = Regex("right|derech|rechts|droit|destr")
    private val short = Regex("\\b(FL|FR|RL|RR|LF|RF|LR)\\b")

    /** Which wheel a sensor name speaks of ("FL", "FR", "RL", "RR"), from English or Spanish words or the usual short forms. */
    fun wheelOf(name: String): String? {
        val n = name.lowercase()
        val f = front.containsMatchIn(n); val r = rear.containsMatchIn(n); val l = left.containsMatchIn(n); val ri = right.containsMatchIn(n)
        if (f != r && l != ri) return (if (f) "F" else "R") + (if (l) "L" else "R")
        // "LF" (left front) is "FL"; "RF" is "FR"; "LR" is "RL". ("RR" reads the same either way.)
        return short.find(name.uppercase())?.value?.let { if (it == "LF" || it == "RF" || it == "LR") "${it[1]}${it[0]}" else it }
    }

    private fun maker(key: String, custom: Collection<SensorDef>) = key.takeIf { k -> custom.any { it.key == k } }

    private fun tyreSensor(wheel: String, custom: Collection<SensorDef>) =
        custom.firstOrNull { tyre.containsMatchIn(it.name) && pressure.containsMatchIn(it.name) && !notTheWheel.containsMatchIn(it.name) && wheelOf(it.name) == wheel }?.key

    private val temp = Regex("(oil|öl|aceite|huile|olio).*(temp)|(temp).*(oil|aceite|huile|olio)|\\beot\\b", RegexOption.IGNORE_CASE)
    private val press = Regex("(oil|öl|aceite|huile|olio).*(press|druck)|(press|druck).*(oil|aceite|huile|olio)|\\beop\\b", RegexOption.IGNORE_CASE)

    /** The sensor that feeds [key] on this car ([key] itself when it is not virtual), or null when it has none. */
    fun resolve(key: String, supported: Set<Int>, custom: Collection<SensorDef> = com.openobd.core.brand.CustomSensors.sensors): String? = when (key) {
        OIL_TEMP.key -> "015C".takeIf { 0x5C in supported } ?: custom.firstOrNull { temp.containsMatchIn(it.name) && !it.name.contains("calc", true) }?.key
        OIL_PRESS.key -> custom.firstOrNull { press.containsMatchIn(it.name) && !it.name.contains("control", true) && !it.name.contains("solenoid", true) }?.key
        TYRE_FL.key -> tyreSensor("FL", custom)
        TYRE_FR.key -> tyreSensor("FR", custom)
        TYRE_RL.key -> tyreSensor("RL", custom)
        TYRE_RR.key -> tyreSensor("RR", custom)
        BOOST_TARGET.key -> "0170_AC".takeIf { 0x70 in supported } ?: maker("X_gm_boost_target", custom)
        BOOST_ACTUAL.key -> "0170_AS".takeIf { 0x70 in supported } ?: maker("X_gm_boost_actual", custom) ?: "010B".takeIf { 0x0B in supported }
        DPF_SOOT.key -> "018B_T".takeIf { 0x8B in supported } ?: maker("X_gm_dpf_soot", custom)
        DPF_TEMP.key -> "017C_I".takeIf { 0x7C in supported } ?: maker("X_gm_dpf_inlet_t", custom)
        DPF_KM.key -> maker("X_gm_dpf_km", custom)
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
