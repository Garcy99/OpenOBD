package com.openobd.core.diesel

/** Live state of a guided particulate filter regeneration drive. */
data class RegenStatus(
    val coolantOk: Boolean?,
    val speedOk: Boolean?,
    val rpmOk: Boolean?,
    /** Seconds spent with all conditions met in the current run (short dips are tolerated). */
    val secondsInConditions: Double,
    val targetSeconds: Double,
    /** null = this car does not report exhaust / DPF temperatures over standard OBD. */
    val regenActive: Boolean?,
    val dpfTempC: Double?,
    val dpfDeltaKpa: Double?,
    val regensSeen: Int,
) {
    val allConditionsMet: Boolean get() = coolantOk == true && speedOk == true && rpmOk == true
    val progress: Float get() = (secondsInConditions / targetSeconds).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Helps the driver run a diesel particulate filter regeneration: the ECU starts one on its own when the filter is
 * loaded and the engine is warm and under steady load (typically 15-20 minutes above ~60 km/h at 2000-3000 rpm).
 * When the car reports DPF inlet or exhaust temperature, an active regeneration is detected from the temperature
 * rise (> 450 °C, ending below 380 °C).
 */
class RegenMonitor(private val targetSeconds: Double = 20 * 60.0, private val toleranceSeconds: Double = 60.0) {
    companion object {
        /** Live data keys the monitor needs (only the supported ones are polled). */
        val keys = listOf("0105", "010D", "010C", "017C_I", "0178_1", "017A_D")
    }

    private var lastTime: Long? = null
    private var inConditions = 0.0
    private var outOfConditions = 0.0
    private var active = false
    private var regens = 0

    fun onCycle(v: Map<String, Double>, now: Long): RegenStatus {
        val dt = lastTime?.let { ((now - it) / 1000.0).coerceIn(0.0, 10.0) } ?: 0.0
        lastTime = now
        val coolantOk = v["0105"]?.let { it >= 70 }
        val speedOk = v["010D"]?.let { it >= 60 }
        val rpmOk = v["010C"]?.let { it in 1800.0..3500.0 }
        if (coolantOk == true && speedOk == true && rpmOk == true) {
            inConditions += dt; outOfConditions = 0.0
        } else {
            outOfConditions += dt
            if (outOfConditions > toleranceSeconds) inConditions = 0.0
        }
        val temp = v["017C_I"] ?: v["0178_1"] ?: v[RegenWatch.GM_INLET_TEMP]?.takeIf { it in -40.0..1000.0 }
        if (temp != null) {
            if (!active && temp > 450) { active = true; regens++ }
            else if (active && temp < 380) active = false
        }
        return RegenStatus(coolantOk, speedOk, rpmOk, inConditions, targetSeconds, temp?.let { active }, temp, v["017A_D"], regens)
    }
}
