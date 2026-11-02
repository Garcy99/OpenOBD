package com.openobd.core.obd

enum class MonitorState(val label: String) { COMPLETE("Complete"), INCOMPLETE("Not ready"), NOT_SUPPORTED("N/A") }

data class Monitor(val name: String, val state: MonitorState, val continuous: Boolean)

data class ReadinessStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val isDiesel: Boolean,
    val monitors: List<Monitor>,
) {
    val supported get() = monitors.filter { it.state != MonitorState.NOT_SUPPORTED }
    val incomplete get() = monitors.filter { it.state == MonitorState.INCOMPLETE }
    val complete get() = monitors.filter { it.state == MonitorState.COMPLETE }

    /** US I/M rule of thumb (MY2001+): at most one non-continuous monitor incomplete (EVAP usually). */
    val likelyPassesInspection get() = !milOn && incomplete.count { !it.continuous } <= 1
}

object Readiness {
    private val spark = listOf("Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
        "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT system")
    private val diesel = listOf("NMHC catalyst", "NOx/SCR aftertreatment", "Reserved", "Boost pressure",
        "Reserved", "Exhaust gas sensor", "PM filter (DPF)", "EGR / VVT system")

    /**
     * Decodes PID 01 (since DTCs cleared) or PID 41 (this drive cycle) A..D bytes.
     * For PID 41 byte A is reserved, so MIL/DTC count are meaningless.
     */
    fun decode(d: IntArray): ReadinessStatus {
        val a = d[0]; val b = d[1]; val c = d[2]; val e = d[3]
        val isDiesel = (b shr 3) and 1 == 1
        fun state(availBit: Boolean, incompleteBit: Boolean) = when {
            !availBit -> MonitorState.NOT_SUPPORTED
            incompleteBit -> MonitorState.INCOMPLETE
            else -> MonitorState.COMPLETE
        }
        val monitors = mutableListOf<Monitor>()
        listOf("Misfire", "Fuel system", "Comprehensive components").forEachIndexed { i, n ->
            monitors += Monitor(n, state((b shr i) and 1 == 1, (b shr (i + 4)) and 1 == 1), true)
        }
        val names = if (isDiesel) diesel else spark
        names.forEachIndexed { i, n ->
            if (n == "Reserved" || (n == "A/C refrigerant" && (c shr i) and 1 == 0)) return@forEachIndexed
            monitors += Monitor(n, state((c shr i) and 1 == 1, (e shr i) and 1 == 1), false)
        }
        return ReadinessStatus((a shr 7) and 1 == 1, a and 0x7F, isDiesel, monitors)
    }
}
