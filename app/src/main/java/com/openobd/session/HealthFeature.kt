package com.openobd.session

import com.openobd.core.health.HealthRecorder
import com.openobd.core.health.HealthReport
import com.openobd.core.health.HealthTrends

/** One health sample per drive and the long-term trends built from them. */
class HealthFeature internal constructor(private val s: ObdSession) {
    @Volatile private var recorder: HealthRecorder? = null

    /** Live data keys the recorder needs. */
    val keys = listOf("010C", "0105", "010D", "0142", "0106", "0107")

    internal fun start(vehicleId: String?, restingV: Double?) {
        recorder = vehicleId?.let { HealthRecorder(it, restingV, System.currentTimeMillis()) }
    }

    internal fun onCycle(values: Map<String, Double>, now: Long) { recorder?.onCycle(values, now) }

    /** Stores the sample of the drive that just ended (misfire counts included when Mode 06 was read). */
    internal suspend fun finish() {
        val rec = recorder ?: return
        recorder = null
        (s.monitorTests.value as? Op.Done)?.value?.let { tests ->
            rec.misfires = tests.filter { it.mid in 0xA2..0xAD && it.tid == 0x0B }.associate { (it.mid - 0xA1) to it.value }
        }
        rec.result()?.let { runCatching { s.garage.addHealthSample(it) } }
    }

    /** Long-term health of the active car. */
    fun report(): HealthReport {
        val id = s.garage.data.value.active?.id
        return HealthTrends.analyze(s.garage.data.value.health.filter { it.vehicleId == id })
    }
}
