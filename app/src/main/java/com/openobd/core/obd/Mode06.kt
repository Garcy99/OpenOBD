package com.openobd.core.obd

/** One on-board monitoring test result (service 06, CAN format). */
data class TestResult(
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val value: Double,
    val min: Double,
    val max: Double,
    val unit: String,
    val ecu: String,
) {
    val passed: Boolean get() = value >= min && value <= max
    val monitorName: String get() = Mode06.midName(mid)
    val testName: String get() = Mode06.tidName(mid, tid)
}

object Mode06 {
    private class Scale(val factor: Double, val unit: String, val offset: Double = 0.0)

    private val scales: Map<Int, Scale> = mapOf(
        0x01 to Scale(1.0, ""), 0x02 to Scale(0.1, ""), 0x03 to Scale(0.01, ""), 0x04 to Scale(0.001, ""),
        0x05 to Scale(0.0000305, ""), 0x06 to Scale(0.000305, ""), 0x07 to Scale(0.25, "rpm"), 0x08 to Scale(0.01, "km/h"),
        0x09 to Scale(1.0, "km/h"), 0x0A to Scale(0.000122, "V"), 0x0B to Scale(0.001, "V"), 0x0C to Scale(0.01, "V"),
        0x0D to Scale(0.00390625, "mA"), 0x0E to Scale(0.001, "A"), 0x0F to Scale(0.01, "A"), 0x10 to Scale(1.0, "ms"),
        0x11 to Scale(100.0, "ms"), 0x12 to Scale(1.0, "s"), 0x13 to Scale(1.0, "mΩ"), 0x14 to Scale(1.0, "Ω"),
        0x15 to Scale(1.0, "kΩ"), 0x16 to Scale(0.1, "°C", -40.0), 0x17 to Scale(0.01, "kPa"), 0x18 to Scale(0.0117, "kPa"),
        0x19 to Scale(0.079, "kPa"), 0x1A to Scale(1.0, "kPa"), 0x1B to Scale(10.0, "kPa"), 0x1C to Scale(0.01, "°"),
        0x1D to Scale(0.5, "°"), 0x1E to Scale(0.0000305, "λ"), 0x1F to Scale(0.05, "AFR"), 0x20 to Scale(0.0039062, ""),
        0x21 to Scale(1.0, "mHz"), 0x22 to Scale(1.0, "Hz"), 0x23 to Scale(1.0, "kHz"), 0x24 to Scale(1.0, "counts"),
        0x25 to Scale(1.0, "km"), 0x26 to Scale(0.1, "mV/ms"), 0x27 to Scale(0.01, "g/s"), 0x28 to Scale(1.0, "g/s"),
        0x29 to Scale(0.25, "Pa/s"), 0x2A to Scale(0.001, "kg/h"), 0x2B to Scale(1.0, "switches"), 0x2C to Scale(0.01, "g/cyl"),
        0x2D to Scale(0.01, "mg/stroke"), 0x2E to Scale(1.0, "bool"), 0x2F to Scale(0.01, "%"), 0x30 to Scale(0.001526, "%"),
        0x31 to Scale(0.001, "L"), 0x32 to Scale(0.0000305, "in"), 0x33 to Scale(0.00024414, ""), 0x34 to Scale(1.0, "min"),
        0x35 to Scale(10.0, "ms"), 0x36 to Scale(0.01, "g"), 0x37 to Scale(0.1, "g"), 0x38 to Scale(1.0, "g"),
        0x39 to Scale(0.01, "%", -327.68), 0x3A to Scale(0.001, "g"), 0x3B to Scale(0.0001, "g"), 0x3C to Scale(0.1, "µs"),
        0x3D to Scale(0.01, "mA"), 0x3E to Scale(0.00006103516, "mm²"), 0x3F to Scale(0.01, "L"),
        // Signed
        0x81 to Scale(1.0, ""), 0x82 to Scale(0.1, ""), 0x83 to Scale(0.01, ""), 0x84 to Scale(0.001, ""),
        0x85 to Scale(0.0000305, ""), 0x86 to Scale(0.000305, ""), 0x8A to Scale(0.000122, "V"), 0x8B to Scale(0.001, "V"),
        0x8C to Scale(0.01, "V"), 0x8D to Scale(0.00390625, "mA"), 0x8E to Scale(0.001, "A"), 0x90 to Scale(1.0, "ms"),
        0x96 to Scale(0.1, "°C"), 0x99 to Scale(0.1, "kPa"), 0x9C to Scale(0.01, "°"), 0x9D to Scale(0.5, "°"),
        0xA8 to Scale(1.0, "g/s"), 0xA9 to Scale(0.25, "Pa/s"), 0xAD to Scale(0.01, "mg/stroke"), 0xAE to Scale(0.1, "mg/stroke"),
        0xAF to Scale(0.01, "%"), 0xB0 to Scale(0.003052, "%"), 0xB1 to Scale(2.0, "mV/s"), 0xFC to Scale(0.01, "kPa"),
        0xFD to Scale(0.001, "kPa"), 0xFE to Scale(0.25, "Pa"),
    )

    fun scale(uasid: Int, raw: Int): Pair<Double, String> {
        val signed = uasid >= 0x80
        val v = if (signed && raw >= 0x8000) raw - 0x10000 else raw
        val s = scales[uasid] ?: return v.toDouble() to ""
        return v * s.factor + s.offset to s.unit
    }

    /** Parses a CAN service 06 response (data starts with 0x46). */
    fun parse(data: IntArray, ecu: String): List<TestResult> {
        val out = mutableListOf<TestResult>()
        var i = 1
        while (i + 8 < data.size) {
            val mid = data[i]; val tid = data[i + 1]; val uas = data[i + 2]
            val (v, unit) = scale(uas, (data[i + 3] shl 8) or data[i + 4])
            val (mn, _) = scale(uas, (data[i + 5] shl 8) or data[i + 6])
            val (mx, _) = scale(uas, (data[i + 7] shl 8) or data[i + 8])
            out += TestResult(mid, tid, uas, v, mn, mx, unit, ecu)
            i += 9
        }
        return out
    }

    private fun bankSensor(n: Int) = "Bank ${n / 4 + 1} Sensor ${n % 4 + 1}"

    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> "O2 sensor monitor ${bankSensor(mid - 0x01)}"
        in 0x21..0x24 -> "Catalyst monitor Bank ${mid - 0x20}"
        in 0x31..0x34 -> "EGR monitor Bank ${mid - 0x30}"
        in 0x35..0x38 -> "VVT monitor Bank ${mid - 0x34}"
        0x39 -> "EVAP monitor (cap off / 0.150\")"
        0x3A -> "EVAP monitor (0.090\")"
        0x3B -> "EVAP monitor (0.040\")"
        0x3C -> "EVAP monitor (0.020\")"
        0x3D -> "Purge flow monitor"
        in 0x41..0x50 -> "O2 sensor heater monitor ${bankSensor(mid - 0x41)}"
        in 0x61..0x64 -> "Heated catalyst monitor Bank ${mid - 0x60}"
        in 0x71..0x74 -> "Secondary air monitor ${mid - 0x70}"
        in 0x81..0x84 -> "Fuel system monitor Bank ${mid - 0x80}"
        in 0x85..0x88 -> "Boost pressure control monitor Bank ${mid - 0x84}"
        0x90, 0x91 -> "NOx adsorber monitor Bank ${mid - 0x8F}"
        0x98, 0x99 -> "NOx catalyst monitor Bank ${mid - 0x97}"
        0xA1 -> "Misfire monitor (general)"
        in 0xA2..0xAD -> "Misfire cylinder ${mid - 0xA1}"
        0xB0, 0xB1 -> "PM filter monitor Bank ${mid - 0xAF}"
        else -> "Monitor 0x%02X".format(mid)
    }

    fun tidName(mid: Int, tid: Int): String {
        if (mid in 0xA1..0xAD) when (tid) {
            0x0B -> return "Misfire count (EWMA, last 10 cycles)"
            0x0C -> return "Misfire count (last/current cycle)"
        }
        return when (tid) {
            0x01 -> "Rich to lean sensor threshold voltage"
            0x02 -> "Lean to rich sensor threshold voltage"
            0x03 -> "Low sensor voltage for switch time"
            0x04 -> "High sensor voltage for switch time"
            0x05 -> "Rich to lean switch time"
            0x06 -> "Lean to rich switch time"
            0x07 -> "Minimum sensor voltage"
            0x08 -> "Maximum sensor voltage"
            0x09 -> "Time between transitions"
            0x0A -> "Sensor period"
            else -> "Test 0x%02X (manufacturer defined)".format(tid)
        }
    }
}
