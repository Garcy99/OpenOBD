package com.openobd.core.obd

enum class SensorCategory(val label: String) {
    ENGINE("Engine"), FUEL("Fuel"), AIR("Air & Boost"), OXYGEN("Oxygen sensors"), EMISSIONS("Emissions"),
    TEMPERATURE("Temperatures"), VEHICLE("Vehicle"), ELECTRICAL("Electrical & Hybrid"), DIESEL("Diesel"),
    STATUS("Status"), CALCULATED("Calculated"), BRAND("Brand / custom");
}

/** A single numeric channel. One PID may yield several sensors (e.g. O2 voltage and trim). */
class SensorDef(
    val key: String,
    val pid: Int,
    val name: String,
    val shortName: String,
    val unit: ObdUnit,
    val min: Double,
    val max: Double,
    val category: SensorCategory,
    val decimals: Int = 0,
    /** Polled less often (temperatures, levels ...). */
    val slow: Boolean = false,
    /** For enumerated values: renders a text label instead of a number. */
    val labeler: ((Double) -> String)? = null,
    /** Free-text unit for custom PIDs whose unit is not a known [ObdUnit]. */
    val customUnit: String? = null,
    val decode: (IntArray) -> Double?,
) {
    fun decodeSafe(data: IntArray): Double? = try {
        decode(data)?.takeIf { !it.isNaN() && !it.isInfinite() }
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}

class PidInfo(val pid: Int, val bytes: Int, val description: String, val sensors: List<SensorDef>)

/** Complete SAE J1979 / ISO 15031-5 service 01 PID table. */
object Pids {
    val all: Map<Int, PidInfo> by lazy { PidTableBuilder().build().associateBy { it.pid } }
    val sensors: Map<String, SensorDef> by lazy { all.values.flatMap { it.sensors }.associateBy { it.key } }

    /** PIDs 0x00, 0x20, 0x40 ... are "supported PIDs" bitmasks. */
    fun isSupportRequest(pid: Int) = pid % 0x20 == 0

    fun key(pid: Int, suffix: String = "") = "01%02X".format(pid) + if (suffix.isEmpty()) "" else "_$suffix"

    val obdStandards = mapOf(
        1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD and OBD-II", 4 to "OBD-I", 5 to "Not OBD compliant",
        6 to "EOBD (Europe)", 7 to "EOBD and OBD-II", 8 to "EOBD and OBD", 9 to "EOBD, OBD and OBD-II",
        10 to "JOBD (Japan)", 11 to "JOBD and OBD-II", 12 to "JOBD and EOBD", 13 to "JOBD, EOBD and OBD-II",
        17 to "EMD (Engine Manufacturer Diagnostics)", 18 to "EMD+", 19 to "HD OBD-C", 20 to "HD OBD",
        21 to "WWH OBD", 23 to "HD EOBD-I", 24 to "HD EOBD-I N", 25 to "HD EOBD-II", 26 to "HD EOBD-II N",
        28 to "OBDBr-1", 29 to "OBDBr-2", 30 to "KOBD (Korea)", 31 to "IOBD I (India)", 32 to "IOBD II (India)",
        33 to "HD EOBD-IV",
    )

    val fuelTypes = mapOf(
        0 to "Not available", 1 to "Gasoline", 2 to "Methanol", 3 to "Ethanol", 4 to "Diesel", 5 to "LPG", 6 to "CNG",
        7 to "Propane", 8 to "Electric", 9 to "Bifuel gasoline", 10 to "Bifuel methanol", 11 to "Bifuel ethanol",
        12 to "Bifuel LPG", 13 to "Bifuel CNG", 14 to "Bifuel propane", 15 to "Bifuel electricity",
        16 to "Bifuel electric/combustion", 17 to "Hybrid gasoline", 18 to "Hybrid ethanol", 19 to "Hybrid diesel",
        20 to "Hybrid electric", 21 to "Hybrid electric/combustion", 22 to "Hybrid regenerative", 23 to "Bifuel diesel",
    )

    fun fuelSystemStatus(v: Int): String = when (v) {
        0 -> "Off"; 1 -> "Open loop (cold)"; 2 -> "Closed loop"; 4 -> "Open loop (load/decel)"
        8 -> "Open loop (fault)"; 16 -> "Closed loop (sensor fault)"; else -> "0x%02X".format(v)
    }

    fun secondaryAirStatus(v: Int): String = when (v) {
        1 -> "Upstream"; 2 -> "Downstream of catalyst"; 4 -> "Atmosphere / off"; 8 -> "Pump on for diagnostics"; else -> "0x%02X".format(v)
    }
}

private class PidTableBuilder {
    private val list = mutableListOf<PidInfo>()
    private var current: MutableList<SensorDef>? = null

    fun pid(pid: Int, bytes: Int, description: String, block: () -> Unit = {}) {
        current = mutableListOf()
        block()
        list += PidInfo(pid, bytes, description, current!!.toList())
        current = null
    }

    fun s(
        pid: Int, suffix: String, name: String, short: String, unit: ObdUnit, min: Double, max: Double,
        cat: SensorCategory, decimals: Int = 0, slow: Boolean = false, labeler: ((Double) -> String)? = null,
        decode: (IntArray) -> Double?,
    ) {
        current!! += SensorDef(Pids.key(pid, suffix), pid, name, short, unit, min, max, cat, decimals, slow, labeler, decode = decode)
    }

    /** One-sensor PID. */
    fun single(
        pid: Int, bytes: Int, name: String, short: String, unit: ObdUnit, min: Double, max: Double, cat: SensorCategory,
        decimals: Int = 0, slow: Boolean = false, labeler: ((Double) -> String)? = null, decode: (IntArray) -> Double?,
    ) = pid(pid, bytes, name) { s(pid, "", name, short, unit, min, max, cat, decimals, slow, labeler, decode) }

    fun percent(pid: Int, name: String, short: String, cat: SensorCategory, slow: Boolean = false) =
        single(pid, 1, name, short, ObdUnit.PERCENT, 0.0, 100.0, cat, 1, slow) { pct(it[0]) }

    fun temperature(pid: Int, name: String, short: String, max: Double = 150.0) =
        single(pid, 1, name, short, ObdUnit.CELSIUS, -40.0, max, SensorCategory.TEMPERATURE, slow = true) { temp(it[0]) }

    fun torquePct(pid: Int, name: String, short: String) =
        single(pid, 1, name, short, ObdUnit.PERCENT, -125.0, 130.0, SensorCategory.ENGINE) { torque(it[0]) }

    fun fuelTrim(pid: Int, name: String, short: String) =
        single(pid, 1, name, short, ObdUnit.PERCENT, -25.0, 25.0, SensorCategory.FUEL, 1) { trim(it[0]) }

    // Formula helpers
    fun u16(a: IntArray, i: Int = 0) = (a[i] shl 8) or a[i + 1]
    fun s16(a: IntArray, i: Int = 0) = u16(a, i).let { if (it >= 0x8000) it - 0x10000 else it }
    fun u32(a: IntArray, i: Int = 0): Long =
        (a[i].toLong() shl 24) or (a[i + 1].toLong() shl 16) or (a[i + 2].toLong() shl 8) or a[i + 3].toLong()
    fun pct(a: Int) = a * 100.0 / 255.0
    fun trim(a: Int) = (a - 128) * 100.0 / 128.0
    fun temp(a: Int) = a - 40.0
    fun torque(a: Int) = a - 125.0
    fun bit(a: Int, b: Int) = (a shr b) and 1 == 1

    private val onOff: (Double) -> String = { if (it > 0.5) "ON" else "OFF" }

    fun build(): List<PidInfo> {
        val E = SensorCategory.ENGINE; val F = SensorCategory.FUEL; val A = SensorCategory.AIR
        val O = SensorCategory.OXYGEN; val EM = SensorCategory.EMISSIONS; val T = SensorCategory.TEMPERATURE
        val V = SensorCategory.VEHICLE; val EL = SensorCategory.ELECTRICAL; val D = SensorCategory.DIESEL
        val ST = SensorCategory.STATUS

        for (p in 0x00..0xE0 step 0x20) pid(p, 4, "PIDs supported [%02X-%02X]".format(p + 1, p + 0x20))

        pid(0x01, 4, "Monitor status since DTCs cleared") {
            s(0x01, "MIL", "Check engine light (MIL)", "MIL", ObdUnit.NONE, 0.0, 1.0, ST, slow = true, labeler = onOff) { if (bit(it[0], 7)) 1.0 else 0.0 }
            s(0x01, "DTC", "Number of confirmed DTCs", "DTCs", ObdUnit.COUNT, 0.0, 20.0, ST, slow = true) { (it[0] and 0x7F).toDouble() }
        }
        pid(0x02, 2, "DTC that caused freeze frame")
        pid(0x03, 2, "Fuel system status") {
            s(0x03, "A", "Fuel system 1 status", "Fuel sys 1", ObdUnit.NONE, 0.0, 16.0, F, slow = true, labeler = { Pids.fuelSystemStatus(it.toInt()) }) { it[0].toDouble() }
            s(0x03, "B", "Fuel system 2 status", "Fuel sys 2", ObdUnit.NONE, 0.0, 16.0, F, slow = true, labeler = { Pids.fuelSystemStatus(it.toInt()) }) { it[1].takeIf { v -> v > 0 }?.toDouble() }
        }
        percent(0x04, "Calculated engine load", "Load", E)
        temperature(0x05, "Engine coolant temperature", "Coolant", 130.0)
        fuelTrim(0x06, "Short term fuel trim - Bank 1", "STFT B1")
        fuelTrim(0x07, "Long term fuel trim - Bank 1", "LTFT B1")
        fuelTrim(0x08, "Short term fuel trim - Bank 2", "STFT B2")
        fuelTrim(0x09, "Long term fuel trim - Bank 2", "LTFT B2")
        single(0x0A, 1, "Fuel pressure (gauge)", "Fuel press", ObdUnit.KPA, 0.0, 765.0, F) { it[0] * 3.0 }
        single(0x0B, 1, "Intake manifold absolute pressure", "MAP", ObdUnit.KPA, 0.0, 255.0, A) { it[0].toDouble() }
        single(0x0C, 2, "Engine RPM", "RPM", ObdUnit.RPM, 0.0, 8000.0, E) { u16(it) / 4.0 }
        single(0x0D, 1, "Vehicle speed", "Speed", ObdUnit.KMH, 0.0, 240.0, V) { it[0].toDouble() }
        single(0x0E, 1, "Timing advance", "Timing", ObdUnit.DEGREES, -64.0, 64.0, E, 1) { it[0] / 2.0 - 64.0 }
        temperature(0x0F, "Intake air temperature", "IAT", 100.0)
        single(0x10, 2, "Mass air flow rate", "MAF", ObdUnit.GRAMS_PER_SEC, 0.0, 300.0, A, 2) { u16(it) / 100.0 }
        percent(0x11, "Throttle position", "Throttle", E)
        single(0x12, 1, "Commanded secondary air status", "Sec. air", ObdUnit.NONE, 0.0, 8.0, EM, slow = true, labeler = { Pids.secondaryAirStatus(it.toInt()) }) { it[0].toDouble() }
        single(0x13, 1, "Oxygen sensors present (2 banks)", "O2 present", ObdUnit.NONE, 0.0, 255.0, O, slow = true, labeler = { "0x%02X".format(it.toInt()) }) { it[0].toDouble() }
        for (i in 0 until 8) {
            val p = 0x14 + i; val b = i / 4 + 1; val sn = i % 4 + 1
            pid(p, 2, "Oxygen sensor ${i + 1} (Bank $b Sensor $sn) voltage & trim") {
                s(p, "V", "O2 Bank $b Sensor $sn voltage", "O2 B${b}S$sn", ObdUnit.VOLTS, 0.0, 1.275, O, 3) { it[0] / 200.0 }
                s(p, "T", "O2 Bank $b Sensor $sn short term fuel trim", "O2 B${b}S$sn trim", ObdUnit.PERCENT, -100.0, 100.0, O, 1) { if (it[1] == 0xFF) null else trim(it[1]) }
            }
        }
        single(0x1C, 1, "OBD standard this vehicle conforms to", "OBD std", ObdUnit.NONE, 0.0, 255.0, ST, slow = true, labeler = { Pids.obdStandards[it.toInt()] ?: "Unknown (${it.toInt()})" }) { it[0].toDouble() }
        single(0x1D, 1, "Oxygen sensors present (4 banks)", "O2 present", ObdUnit.NONE, 0.0, 255.0, O, slow = true, labeler = { "0x%02X".format(it.toInt()) }) { it[0].toDouble() }
        single(0x1E, 1, "Auxiliary input status (PTO)", "PTO", ObdUnit.NONE, 0.0, 1.0, ST, slow = true, labeler = onOff) { if (bit(it[0], 0)) 1.0 else 0.0 }
        single(0x1F, 2, "Run time since engine start", "Run time", ObdUnit.SECONDS, 0.0, 65535.0, ST, slow = true) { u16(it).toDouble() }
        single(0x21, 2, "Distance traveled with MIL on", "Dist MIL", ObdUnit.KM, 0.0, 65535.0, ST, slow = true) { u16(it).toDouble() }
        single(0x22, 2, "Fuel rail pressure (relative to manifold)", "Rail press", ObdUnit.KPA, 0.0, 5177.0, F, 1) { u16(it) * 0.079 }
        single(0x23, 2, "Fuel rail gauge pressure (diesel / GDI)", "Rail press", ObdUnit.KPA, 0.0, 655350.0, F) { u16(it) * 10.0 }
        for (i in 0 until 8) {
            val p = 0x24 + i
            pid(p, 4, "Oxygen sensor ${i + 1} wide range (lambda & voltage)") {
                s(p, "L", "O2 sensor ${i + 1} equivalence ratio (lambda)", "λ S${i + 1}", ObdUnit.LAMBDA, 0.0, 2.0, O, 3) { u16(it) * 2.0 / 65536.0 }
                s(p, "V", "O2 sensor ${i + 1} voltage (wide range)", "O2 S${i + 1} V", ObdUnit.VOLTS, 0.0, 8.0, O, 3) { u16(it, 2) * 8.0 / 65536.0 }
            }
        }
        percent(0x2C, "Commanded EGR", "EGR cmd", EM)
        single(0x2D, 1, "EGR error", "EGR err", ObdUnit.PERCENT, -100.0, 100.0, EM, 1) { trim(it[0]) }
        percent(0x2E, "Commanded evaporative purge", "Evap purge", EM)
        percent(0x2F, "Fuel tank level input", "Fuel level", F, slow = true)
        single(0x30, 1, "Warm-ups since codes cleared", "Warm-ups", ObdUnit.COUNT, 0.0, 255.0, ST, slow = true) { it[0].toDouble() }
        single(0x31, 2, "Distance traveled since codes cleared", "Dist clr", ObdUnit.KM, 0.0, 65535.0, ST, slow = true) { u16(it).toDouble() }
        single(0x32, 2, "Evap system vapor pressure", "Evap press", ObdUnit.PA, -8192.0, 8192.0, EM, 2, slow = true) { s16(it) / 4.0 }
        single(0x33, 1, "Absolute barometric pressure", "Baro", ObdUnit.KPA, 0.0, 255.0, A, slow = true) { it[0].toDouble() }
        for (i in 0 until 8) {
            val p = 0x34 + i
            pid(p, 4, "Oxygen sensor ${i + 1} wide range (lambda & current)") {
                s(p, "L", "O2 sensor ${i + 1} equivalence ratio (lambda)", "λ S${i + 1}", ObdUnit.LAMBDA, 0.0, 2.0, O, 3) { u16(it) * 2.0 / 65536.0 }
                s(p, "C", "O2 sensor ${i + 1} current", "O2 S${i + 1} mA", ObdUnit.MILLIAMPS, -128.0, 128.0, O, 2) { u16(it, 2) / 256.0 - 128.0 }
            }
        }
        val catNames = listOf("Bank 1 Sensor 1", "Bank 2 Sensor 1", "Bank 1 Sensor 2", "Bank 2 Sensor 2")
        val catShort = listOf("B1S1", "B2S1", "B1S2", "B2S2")
        for (i in 0 until 4) single(0x3C + i, 2, "Catalyst temperature ${catNames[i]}", "Cat ${catShort[i]}", ObdUnit.CELSIUS, -40.0, 1000.0, T, 1, slow = true) { u16(it) / 10.0 - 40.0 }
        pid(0x41, 4, "Monitor status this drive cycle")
        single(0x42, 2, "Control module voltage", "Module V", ObdUnit.VOLTS, 0.0, 18.0, EL, 2) { u16(it) / 1000.0 }
        single(0x43, 2, "Absolute load value", "Abs load", ObdUnit.PERCENT, 0.0, 300.0, E, 1) { u16(it) * 100.0 / 255.0 }
        single(0x44, 2, "Commanded air-fuel equivalence ratio", "Cmd λ", ObdUnit.LAMBDA, 0.0, 2.0, F, 3) { u16(it) * 2.0 / 65536.0 }
        percent(0x45, "Relative throttle position", "Rel throttle", E)
        temperature(0x46, "Ambient air temperature", "Ambient", 60.0)
        percent(0x47, "Absolute throttle position B", "Throttle B", E)
        percent(0x48, "Absolute throttle position C", "Throttle C", E)
        percent(0x49, "Accelerator pedal position D", "Pedal D", E)
        percent(0x4A, "Accelerator pedal position E", "Pedal E", E)
        percent(0x4B, "Accelerator pedal position F", "Pedal F", E)
        percent(0x4C, "Commanded throttle actuator", "Cmd throttle", E)
        single(0x4D, 2, "Time run with MIL on", "Time MIL", ObdUnit.MINUTES, 0.0, 65535.0, ST, slow = true) { u16(it).toDouble() }
        single(0x4E, 2, "Time since trouble codes cleared", "Time clr", ObdUnit.MINUTES, 0.0, 65535.0, ST, slow = true) { u16(it).toDouble() }
        pid(0x4F, 4, "Maximum values (lambda, O2 voltage, O2 current, MAP)") {
            s(0x4F, "L", "Max equivalence ratio", "Max λ", ObdUnit.LAMBDA, 0.0, 255.0, O, slow = true) { it[0].toDouble() }
            s(0x4F, "V", "Max O2 sensor voltage", "Max O2 V", ObdUnit.VOLTS, 0.0, 255.0, O, slow = true) { it[1].toDouble() }
            s(0x4F, "C", "Max O2 sensor current", "Max O2 mA", ObdUnit.MILLIAMPS, 0.0, 255.0, O, slow = true) { it[2].toDouble() }
            s(0x4F, "M", "Max intake manifold pressure", "Max MAP", ObdUnit.KPA, 0.0, 2550.0, A, slow = true) { it[3] * 10.0 }
        }
        single(0x50, 4, "Maximum MAF value", "Max MAF", ObdUnit.GRAMS_PER_SEC, 0.0, 2550.0, A, slow = true) { it[0] * 10.0 }
        single(0x51, 1, "Fuel type", "Fuel type", ObdUnit.NONE, 0.0, 255.0, F, slow = true, labeler = { Pids.fuelTypes[it.toInt()] ?: "Unknown" }) { it[0].toDouble() }
        percent(0x52, "Ethanol fuel percentage", "Ethanol", F, slow = true)
        single(0x53, 2, "Absolute evap system vapor pressure", "Abs evap", ObdUnit.KPA, 0.0, 327.675, EM, 3, slow = true) { u16(it) / 200.0 }
        single(0x54, 2, "Evap system vapor pressure (wide)", "Evap press", ObdUnit.PA, -32768.0, 32767.0, EM, slow = true) { s16(it).toDouble() }
        val secTrim = listOf(0x55 to "Short term secondary O2 trim", 0x56 to "Long term secondary O2 trim", 0x57 to "Short term secondary O2 trim", 0x58 to "Long term secondary O2 trim")
        secTrim.forEachIndexed { idx, (p, n) ->
            val banks = if (idx < 2) 1 to 3 else 2 to 4
            pid(p, 2, "$n (banks ${banks.first} & ${banks.second})") {
                s(p, "A", "$n - Bank ${banks.first}", "2nd O2 B${banks.first}", ObdUnit.PERCENT, -100.0, 100.0, O, 1) { trim(it[0]) }
                s(p, "B", "$n - Bank ${banks.second}", "2nd O2 B${banks.second}", ObdUnit.PERCENT, -100.0, 100.0, O, 1) { trim(it[1]) }
            }
        }
        single(0x59, 2, "Fuel rail absolute pressure", "Rail abs", ObdUnit.KPA, 0.0, 655350.0, F) { u16(it) * 10.0 }
        percent(0x5A, "Relative accelerator pedal position", "Pedal", E)
        percent(0x5B, "Hybrid battery pack remaining life", "HV batt", EL, slow = true)
        temperature(0x5C, "Engine oil temperature", "Oil temp", 160.0)
        single(0x5D, 2, "Fuel injection timing", "Inj timing", ObdUnit.DEGREES, -210.0, 302.0, F, 2) { u16(it) / 128.0 - 210.0 }
        single(0x5E, 2, "Engine fuel rate", "Fuel rate", ObdUnit.LITERS_PER_HOUR, 0.0, 100.0, F, 2) { u16(it) / 20.0 }
        single(0x5F, 1, "Emission requirements", "Emission std", ObdUnit.NONE, 0.0, 255.0, ST, slow = true, labeler = { "0x%02X".format(it.toInt()) }) { it[0].toDouble() }
        torquePct(0x61, "Driver's demand engine torque", "Demand torque")
        torquePct(0x62, "Actual engine torque", "Torque")
        single(0x63, 2, "Engine reference torque", "Ref torque", ObdUnit.NEWTON_METERS, 0.0, 65535.0, E, slow = true) { u16(it).toDouble() }
        pid(0x64, 5, "Engine percent torque data") {
            s(0x64, "I", "Torque at idle (point 1)", "Torque idle", ObdUnit.PERCENT, -125.0, 130.0, E, slow = true) { torque(it[0]) }
            for (i in 1..4) s(0x64, "P$i", "Torque point ${i + 1}", "Torque pt${i + 1}", ObdUnit.PERCENT, -125.0, 130.0, E, slow = true) { torque(it[i]) }
        }
        pid(0x65, 2, "Auxiliary input / output supported")
        pid(0x66, 5, "Mass air flow sensor") {
            s(0x66, "A", "MAF sensor A", "MAF A", ObdUnit.GRAMS_PER_SEC, 0.0, 300.0, A, 2) { if (bit(it[0], 0)) u16(it, 1) / 32.0 else null }
            s(0x66, "B", "MAF sensor B", "MAF B", ObdUnit.GRAMS_PER_SEC, 0.0, 300.0, A, 2) { if (bit(it[0], 1)) u16(it, 3) / 32.0 else null }
        }
        pid(0x67, 3, "Engine coolant temperature (sensors)") {
            s(0x67, "1", "Engine coolant temperature sensor 1", "Coolant 1", ObdUnit.CELSIUS, -40.0, 130.0, T, slow = true) { if (bit(it[0], 0)) temp(it[1]) else null }
            s(0x67, "2", "Engine coolant temperature sensor 2", "Coolant 2", ObdUnit.CELSIUS, -40.0, 130.0, T, slow = true) { if (bit(it[0], 1)) temp(it[2]) else null }
        }
        pid(0x68, 3, "Intake air temperature sensors") {
            s(0x68, "1", "Intake air temperature sensor 1", "IAT 1", ObdUnit.CELSIUS, -40.0, 100.0, T, slow = true) { if (bit(it[0], 0)) temp(it[1]) else null }
            s(0x68, "2", "Intake air temperature sensor 2", "IAT 2", ObdUnit.CELSIUS, -40.0, 100.0, T, slow = true) { if (bit(it[0], 1)) temp(it[2]) else null }
        }
        pid(0x69, 7, "Commanded EGR and EGR error") {
            s(0x69, "AC", "Commanded EGR A duty cycle", "EGR A cmd", ObdUnit.PERCENT, 0.0, 100.0, EM, 1) { if (bit(it[0], 0)) pct(it[1]) else null }
            s(0x69, "AA", "Actual EGR A duty cycle", "EGR A act", ObdUnit.PERCENT, 0.0, 100.0, EM, 1) { if (bit(it[0], 1)) pct(it[2]) else null }
            s(0x69, "AE", "EGR A error", "EGR A err", ObdUnit.PERCENT, -100.0, 100.0, EM, 1) { if (bit(it[0], 2)) trim(it[3]) else null }
        }
        pid(0x6A, 5, "Commanded diesel intake air flow control") {
            s(0x6A, "AC", "Commanded intake air flow A", "Air flow A cmd", ObdUnit.PERCENT, 0.0, 100.0, D, 1) { if (bit(it[0], 0)) pct(it[1]) else null }
            s(0x6A, "AR", "Relative intake air flow A position", "Air flow A pos", ObdUnit.PERCENT, 0.0, 100.0, D, 1) { if (bit(it[0], 1)) pct(it[2]) else null }
        }
        pid(0x6B, 5, "Exhaust gas recirculation temperature") {
            s(0x6B, "A1", "EGR temperature Bank 1 Sensor 1", "EGR temp", ObdUnit.CELSIUS, -40.0, 600.0, T, slow = true) { if (bit(it[0], 0)) temp(it[1]) else null }
        }
        pid(0x6C, 5, "Commanded throttle actuator control & relative position") {
            s(0x6C, "AC", "Commanded throttle actuator A", "Thr A cmd", ObdUnit.PERCENT, 0.0, 100.0, E, 1) { if (bit(it[0], 0)) pct(it[1]) else null }
            s(0x6C, "AR", "Relative throttle A position", "Thr A pos", ObdUnit.PERCENT, 0.0, 100.0, E, 1) { if (bit(it[0], 1)) pct(it[2]) else null }
        }
        pid(0x6D, 11, "Fuel pressure control system") {
            s(0x6D, "C", "Commanded fuel rail pressure A", "Rail cmd", ObdUnit.KPA, 0.0, 655350.0, F) { if (bit(it[0], 0)) u16(it, 1) * 10.0 else null }
            s(0x6D, "P", "Fuel rail pressure A", "Rail act", ObdUnit.KPA, 0.0, 655350.0, F) { if (bit(it[0], 1)) u16(it, 3) * 10.0 else null }
            s(0x6D, "T", "Fuel rail temperature A", "Rail temp", ObdUnit.CELSIUS, -40.0, 215.0, T, slow = true) { if (bit(it[0], 2)) temp(it[5]) else null }
        }
        pid(0x6E, 9, "Injection pressure control system") {
            s(0x6E, "C", "Commanded injection control pressure A", "Inj cmd", ObdUnit.KPA, 0.0, 655350.0, D) { if (bit(it[0], 0)) u16(it, 1) * 10.0 else null }
            s(0x6E, "P", "Injection control pressure A", "Inj press", ObdUnit.KPA, 0.0, 655350.0, D) { if (bit(it[0], 2)) u16(it, 5) * 10.0 else null }
        }
        pid(0x6F, 3, "Turbocharger compressor inlet pressure") {
            s(0x6F, "A", "Turbo A compressor inlet pressure", "Turbo A in", ObdUnit.KPA, 0.0, 255.0, A) { if (bit(it[0], 0)) it[1].toDouble() else null }
            s(0x6F, "B", "Turbo B compressor inlet pressure", "Turbo B in", ObdUnit.KPA, 0.0, 255.0, A) { if (bit(it[0], 1)) it[2].toDouble() else null }
        }
        pid(0x70, 10, "Boost pressure control") {
            s(0x70, "AC", "Commanded boost pressure A", "Boost cmd", ObdUnit.KPA, 0.0, 400.0, A, 1) { if (bit(it[0], 0)) u16(it, 1) * 0.03125 else null }
            s(0x70, "AS", "Boost pressure sensor A", "Boost A", ObdUnit.KPA, 0.0, 400.0, A, 1) { if (bit(it[0], 1)) u16(it, 3) * 0.03125 else null }
            s(0x70, "BC", "Commanded boost pressure B", "Boost B cmd", ObdUnit.KPA, 0.0, 400.0, A, 1) { if (bit(it[0], 2)) u16(it, 5) * 0.03125 else null }
            s(0x70, "BS", "Boost pressure sensor B", "Boost B", ObdUnit.KPA, 0.0, 400.0, A, 1) { if (bit(it[0], 3)) u16(it, 7) * 0.03125 else null }
        }
        pid(0x71, 6, "Variable geometry turbo (VGT) control") {
            s(0x71, "AC", "Commanded VGT A position", "VGT cmd", ObdUnit.PERCENT, 0.0, 100.0, A, 1) { if (bit(it[0], 0)) pct(it[1]) else null }
            s(0x71, "AP", "VGT A position", "VGT pos", ObdUnit.PERCENT, 0.0, 100.0, A, 1) { if (bit(it[0], 1)) pct(it[2]) else null }
        }
        pid(0x72, 5, "Wastegate control") {
            s(0x72, "AC", "Commanded wastegate A position", "WG cmd", ObdUnit.PERCENT, 0.0, 100.0, A, 1) { if (bit(it[0], 0)) pct(it[1]) else null }
            s(0x72, "AP", "Wastegate A position", "WG pos", ObdUnit.PERCENT, 0.0, 100.0, A, 1) { if (bit(it[0], 1)) pct(it[2]) else null }
        }
        pid(0x73, 5, "Exhaust pressure") {
            s(0x73, "1", "Exhaust pressure Bank 1", "Exh press B1", ObdUnit.KPA, 0.0, 655.35, EM, 2) { if (bit(it[0], 0)) u16(it, 1) * 0.01 else null }
            s(0x73, "2", "Exhaust pressure Bank 2", "Exh press B2", ObdUnit.KPA, 0.0, 655.35, EM, 2) { if (bit(it[0], 1)) u16(it, 3) * 0.01 else null }
        }
        pid(0x74, 5, "Turbocharger RPM") {
            s(0x74, "A", "Turbocharger A speed", "Turbo A rpm", ObdUnit.RPM, 0.0, 250000.0, A) { if (bit(it[0], 0)) u16(it, 1) * 10.0 else null }
            s(0x74, "B", "Turbocharger B speed", "Turbo B rpm", ObdUnit.RPM, 0.0, 250000.0, A) { if (bit(it[0], 1)) u16(it, 3) * 10.0 else null }
        }
        pid(0x75, 7, "Turbocharger A temperature") {
            s(0x75, "CI", "Turbo A compressor inlet temperature", "Turbo A in T", ObdUnit.CELSIUS, -40.0, 215.0, T, slow = true) { if (bit(it[0], 0)) temp(it[1]) else null }
            s(0x75, "CO", "Turbo A compressor outlet temperature", "Turbo A out T", ObdUnit.CELSIUS, -40.0, 215.0, T, slow = true) { if (bit(it[0], 1)) temp(it[2]) else null }
            s(0x75, "TI", "Turbo A turbine inlet temperature", "Turbine A in T", ObdUnit.CELSIUS, -40.0, 1200.0, T, 1, slow = true) { if (bit(it[0], 2)) u16(it, 3) / 10.0 - 40.0 else null }
            s(0x75, "TO", "Turbo A turbine outlet temperature", "Turbine A out T", ObdUnit.CELSIUS, -40.0, 1200.0, T, 1, slow = true) { if (bit(it[0], 3)) u16(it, 5) / 10.0 - 40.0 else null }
        }
        pid(0x76, 7, "Turbocharger B temperature") {
            s(0x76, "CI", "Turbo B compressor inlet temperature", "Turbo B in T", ObdUnit.CELSIUS, -40.0, 215.0, T, slow = true) { if (bit(it[0], 0)) temp(it[1]) else null }
            s(0x76, "CO", "Turbo B compressor outlet temperature", "Turbo B out T", ObdUnit.CELSIUS, -40.0, 215.0, T, slow = true) { if (bit(it[0], 1)) temp(it[2]) else null }
        }
        pid(0x77, 5, "Charge air cooler temperature") {
            s(0x77, "1", "Charge air cooler temperature Bank 1 Sensor 1", "CAC temp B1", ObdUnit.CELSIUS, -40.0, 215.0, T, slow = true) { if (bit(it[0], 0)) temp(it[1]) else null }
            s(0x77, "2", "Charge air cooler temperature Bank 1 Sensor 2", "CAC temp B1S2", ObdUnit.CELSIUS, -40.0, 215.0, T, slow = true) { if (bit(it[0], 1)) temp(it[2]) else null }
        }
        for ((p, bank) in listOf(0x78 to 1, 0x79 to 2)) {
            pid(p, 9, "Exhaust gas temperature Bank $bank") {
                for (i in 0 until 4) s(p, "${i + 1}", "EGT Bank $bank Sensor ${i + 1}", "EGT B${bank}S${i + 1}", ObdUnit.CELSIUS, -40.0, 1200.0, T, 1) { if (bit(it[0], i)) u16(it, 1 + i * 2) / 10.0 - 40.0 else null }
            }
        }
        for ((p, bank) in listOf(0x7A to 1, 0x7B to 2)) {
            pid(p, 7, "Diesel particulate filter Bank $bank") {
                s(p, "D", "DPF Bank $bank delta pressure", "DPF ΔP B$bank", ObdUnit.KPA, -327.68, 327.67, D, 2) { if (bit(it[0], 0)) s16(it, 1) * 0.01 else null }
                s(p, "I", "DPF Bank $bank inlet pressure", "DPF in B$bank", ObdUnit.KPA, 0.0, 655.35, D, 2) { if (bit(it[0], 1)) u16(it, 3) * 0.01 else null }
                s(p, "O", "DPF Bank $bank outlet pressure", "DPF out B$bank", ObdUnit.KPA, 0.0, 655.35, D, 2) { if (bit(it[0], 2)) u16(it, 5) * 0.01 else null }
            }
        }
        pid(0x7C, 9, "Diesel particulate filter temperature") {
            s(0x7C, "I", "DPF Bank 1 inlet temperature", "DPF in T", ObdUnit.CELSIUS, -40.0, 1200.0, D, 1) { if (bit(it[0], 0)) u16(it, 1) / 10.0 - 40.0 else null }
            s(0x7C, "O", "DPF Bank 1 outlet temperature", "DPF out T", ObdUnit.CELSIUS, -40.0, 1200.0, D, 1) { if (bit(it[0], 1)) u16(it, 3) / 10.0 - 40.0 else null }
        }
        pid(0x7D, 1, "NOx NTE control area status")
        pid(0x7E, 1, "PM NTE control area status")
        pid(0x7F, 13, "Engine run time") {
            s(0x7F, "T", "Total engine run time", "Engine hours", ObdUnit.SECONDS, 0.0, 4.29e9, ST, slow = true) { u32(it, 1).toDouble() }
            s(0x7F, "I", "Total idle run time", "Idle time", ObdUnit.SECONDS, 0.0, 4.29e9, ST, slow = true) { u32(it, 5).toDouble() }
            s(0x7F, "P", "Total run time with PTO active", "PTO time", ObdUnit.SECONDS, 0.0, 4.29e9, ST, slow = true) { u32(it, 9).toDouble() }
        }
        pid(0x81, 21, "Engine run time for AECD #1-#5")
        pid(0x82, 21, "Engine run time for AECD #6-#10")
        pid(0x83, 9, "NOx sensor") {
            s(0x83, "1", "NOx sensor concentration Bank 1 Sensor 1", "NOx B1S1", ObdUnit.PPM, 0.0, 3000.0, D) { if (bit(it[0], 0)) u16(it, 1).toDouble() else null }
            s(0x83, "2", "NOx sensor concentration Bank 2 Sensor 1", "NOx B2S1", ObdUnit.PPM, 0.0, 3000.0, D) { if (bit(it[0], 1)) u16(it, 3).toDouble() else null }
        }
        temperature(0x84, "Manifold surface temperature", "Manifold T", 215.0)
        pid(0x85, 10, "NOx reagent system") {
            s(0x85, "L", "Reagent (AdBlue/DEF) tank level", "DEF level", ObdUnit.PERCENT, 0.0, 100.0, D, 1, slow = true) { pct(it[5]) }
        }
        pid(0x86, 5, "Particulate matter (PM) sensor") {
            s(0x86, "1", "PM sensor Bank 1 Sensor 1 mass concentration", "PM B1", ObdUnit.NONE, 0.0, 1000.0, D, 2) { if (bit(it[0], 0)) u16(it, 1) * 0.0125 else null }
        }
        pid(0x87, 5, "Intake manifold absolute pressure (sensors)") {
            s(0x87, "A", "Intake manifold absolute pressure A", "MAP A", ObdUnit.KPA, 0.0, 400.0, A, 1) { if (bit(it[0], 0)) u16(it, 1) / 32.0 else null }
            s(0x87, "B", "Intake manifold absolute pressure B", "MAP B", ObdUnit.KPA, 0.0, 400.0, A, 1) { if (bit(it[0], 1)) u16(it, 3) / 32.0 else null }
        }
        pid(0x88, 13, "SCR induce system")
        pid(0x89, 41, "Run time for AECD #11-#15")
        pid(0x8A, 41, "Run time for AECD #16-#20")
        // Diesel aftertreatment status (SAE J1979): which fields the car fills in (A), whether the particulate filter is
        // regenerating (B), how loaded it is towards the next regeneration (C), and the average time and distance between them.
        pid(0x8B, 7, "Diesel aftertreatment") {
            s(0x8B, "R", "DPF regeneration in progress", "DPF regen", ObdUnit.NONE, 0.0, 1.0, D, labeler = { if (it >= 0.5) "Yes" else "No" }) {
                if (bit(it[0], 0)) (it[1] and 1).toDouble() else null
            }
            s(0x8B, "T", "DPF regeneration trigger (load towards the next one)", "DPF load", ObdUnit.PERCENT, 0.0, 100.0, D, 1) {
                if (bit(it[0], 4)) pct(it[2]) else null
            }
            s(0x8B, "M", "Average time between DPF regenerations", "Regen every", ObdUnit.MINUTES, 0.0, 65535.0, D, slow = true) {
                if (bit(it[0], 5)) u16(it, 3).toDouble() else null
            }
            s(0x8B, "K", "Average distance between DPF regenerations", "Regen every", ObdUnit.KM, 0.0, 65535.0, D, slow = true) {
                if (bit(it[0], 6)) u16(it, 5).toDouble() else null
            }
        }
        pid(0x8C, 17, "O2 sensor (wide range)")
        percent(0x8D, "Throttle position G", "Throttle G", E)
        torquePct(0x8E, "Engine friction - percent torque", "Friction torque")
        pid(0x8F, 7, "PM sensor bank 1 & 2")
        pid(0x90, 3, "WWH-OBD vehicle OBD system information")
        pid(0x91, 5, "WWH-OBD vehicle OBD system information")
        pid(0x92, 2, "Fuel system control")
        pid(0x93, 3, "WWH-OBD vehicle OBD counters support")
        pid(0x94, 12, "NOx warning and inducement system")
        for ((p, bank) in listOf(0x98 to 1, 0x99 to 2)) {
            pid(p, 9, "Exhaust gas temperature sensor Bank $bank (sensors 5-8)") {
                for (i in 0 until 4) s(p, "${i + 5}", "EGT Bank $bank Sensor ${i + 5}", "EGT B${bank}S${i + 5}", ObdUnit.CELSIUS, -40.0, 1200.0, T, 1) { if (bit(it[0], i)) u16(it, 1 + i * 2) / 10.0 - 40.0 else null }
            }
        }
        pid(0x9A, 6, "Hybrid/EV vehicle system data, battery, voltage")
        pid(0x9B, 4, "Diesel exhaust fluid sensor data") {
            s(0x9B, "L", "DEF tank level", "DEF level", ObdUnit.PERCENT, 0.0, 100.0, D, 1, slow = true) { pct(it[3]) }
        }
        pid(0x9C, 17, "O2 sensor data")
        pid(0x9D, 4, "Engine fuel rate (mass)") {
            s(0x9D, "E", "Engine fuel rate (mass)", "Fuel g/s", ObdUnit.GRAMS_PER_SEC, 0.0, 1310.0, F, 2) { u16(it) * 0.02 }
            s(0x9D, "V", "Vehicle fuel rate (mass)", "Veh fuel g/s", ObdUnit.GRAMS_PER_SEC, 0.0, 1310.0, F, 2) { u16(it, 2) * 0.02 }
        }
        single(0x9E, 2, "Engine exhaust flow rate", "Exh flow", ObdUnit.KG_PER_HOUR, 0.0, 13107.0, EM, 1) { u16(it) / 5.0 }
        pid(0x9F, 9, "Fuel system percentage use")
        pid(0xA1, 9, "NOx sensor corrected data")
        single(0xA2, 2, "Cylinder fuel rate", "Cyl fuel", ObdUnit.MG_PER_STROKE, 0.0, 2048.0, F, 2) { u16(it) / 32.0 }
        pid(0xA3, 9, "Evap system vapor pressure")
        pid(0xA4, 4, "Transmission actual gear") {
            s(0xA4, "R", "Transmission actual gear ratio", "Gear ratio", ObdUnit.RATIO, 0.0, 65.0, V, 3) { if (bit(it[0], 1)) u16(it, 2) / 1000.0 else null }
        }
        pid(0xA5, 4, "Commanded diesel exhaust fluid dosing") {
            s(0xA5, "D", "Commanded DEF dosing", "DEF dosing", ObdUnit.PERCENT, 0.0, 127.5, D, 1) { if (bit(it[0], 0)) it[1] / 2.0 else null }
        }
        single(0xA6, 4, "Odometer", "Odometer", ObdUnit.KM, 0.0, 429496729.5, V, 1, slow = true) { u32(it) / 10.0 }
        pid(0xA7, 4, "NOx sensor concentration sensors 3 and 4")
        pid(0xA8, 4, "NOx sensor corrected concentration sensors 3 and 4")
        pid(0xA9, 4, "ABS disable switch state")
        pid(0xC3, 2, "Fuel level input A/B")
        pid(0xC4, 8, "Exhaust particulate control system diagnostic time/count")
        pid(0xC5, 4, "Fuel pressure A and B")
        pid(0xC6, 7, "Particulate control - driver inducement system status")
        pid(0xC7, 2, "Distance since reflash or module replacement")
        pid(0xC8, 1, "NOx control diagnostic (NCD) and particulate control diagnostic (PCD) warning lamp status")
        return list.sortedBy { it.pid }
    }
}
