package com.openobd.core.obd

enum class DtcType(val label: String, val mode: Int, val description: String) {
    STORED("Confirmed", 0x03, "Confirmed / stored codes. These usually turn on the check engine light."),
    PENDING("Pending", 0x07, "Detected during the current or last drive cycle but not yet confirmed."),
    PERMANENT("Permanent", 0x0A, "Cannot be erased with a scan tool; they clear themselves once the ECU verifies the repair."),
}

data class Dtc(val code: String, val type: DtcType, val ecu: String) {
    val system: DtcSystem get() = DtcSystem.of(code)
    val isGeneric: Boolean get() = code.length == 5 && (code[1] == '0' || (code[0] == 'P' && (code[1] == '2' || (code[1] == '3' && code[2] >= '4'))))
}

enum class DtcSystem(val letter: Char, val label: String) {
    POWERTRAIN('P', "Powertrain"), CHASSIS('C', "Chassis"), BODY('B', "Body"), NETWORK('U', "Network");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.letter == code.firstOrNull() } ?: POWERTRAIN
    }
}

object DtcDecoder {
    private val letters = charArrayOf('P', 'C', 'B', 'U')

    /** Decodes the two-byte SAE J2012 encoding ("01 33" -> "P0133"). */
    fun decode(a: Int, b: Int): String {
        val letter = letters[(a shr 6) and 0x03]
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        return "%c%d%X%02X".format(letter, d1, d2, b)
    }

    /** Encodes a code back into its two bytes (useful for tests and the simulator). */
    fun encode(code: String): Pair<Int, Int> {
        val letter = letters.indexOf(code[0].uppercaseChar())
        require(letter >= 0 && code.length == 5) { "Bad DTC $code" }
        val d1 = code[1].digitToInt()
        val d2 = code[2].digitToInt(16)
        return ((letter shl 6) or (d1 shl 4) or d2) to code.substring(3).toInt(16)
    }

    /**
     * Parses the data of a service 03/07/0A positive response (starting with 0x43/0x47/0x4A).
     * On CAN the second byte is the number of DTCs; on legacy protocols there is no count and each message
     * carries 3 DTCs padded with 00 00.
     */
    fun parseResponse(data: IntArray, isCan: Boolean): List<String> {
        if (data.isEmpty()) return emptyList()
        var i = 1
        var count = Int.MAX_VALUE
        if (isCan) {
            if (data.size < 2) return emptyList()
            count = data[1]
            i = 2
        }
        val out = mutableListOf<String>()
        while (i + 1 < data.size && out.size < count) {
            val a = data[i]; val b = data[i + 1]
            i += 2
            if (a == 0 && b == 0) continue
            out += decode(a, b)
        }
        return out
    }

    /** Category-based description used when a code is not in the database. */
    fun genericCategory(code: String): String {
        if (code.length != 5) return "Unknown code"
        val sys = DtcSystem.of(code)
        val manufacturer = when (sys) {
            DtcSystem.POWERTRAIN -> code[1] == '1' || (code[1] == '3' && code[2] < '4')
            else -> code[1] == '1' || code[1] == '2'
        }
        val area = if (sys == DtcSystem.POWERTRAIN) when (code[2]) {
            '0' -> "Fuel and air metering / auxiliary emission controls"
            '1', '2' -> "Fuel and air metering"
            '3' -> "Ignition system or misfire"
            '4' -> "Auxiliary emission controls"
            '5' -> "Vehicle speed, idle control and auxiliary inputs"
            '6' -> "Computer and output circuits"
            '7', '8', '9' -> "Transmission"
            'A', 'B', 'C', 'D', 'E', 'F' -> "Hybrid / electric propulsion"
            else -> "Powertrain"
        } else if (sys == DtcSystem.NETWORK) when (code[2]) {
            '0' -> "Network electrical / communication"
            '1', '2' -> "Network communication (lost communication)"
            '3' -> "Network software / data"
            '4' -> "Invalid data received"
            else -> "Network"
        } else sys.label
        return (if (manufacturer) "Manufacturer-specific code. " else "") + "${sys.label} - $area"
    }
}
