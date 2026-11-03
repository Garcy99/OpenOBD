package com.openobd.session

import com.openobd.core.brand.ModuleScanResult
import com.openobd.core.obd.DtcType
import com.openobd.core.obd.ObdClient
import com.openobd.core.report.CheckInput
import com.openobd.core.report.VehicleCheck
import com.openobd.core.report.VehicleCheckReport
import kotlinx.coroutines.flow.MutableStateFlow

/** Used car / pre-inspection check: everything the car reports about hidden or recent faults. */
class VehicleCheckFeature internal constructor(private val s: ObdSession) {
    val result = MutableStateFlow<Op<VehicleCheckReport>>(Op.Idle)

    fun run() = s.runOp(result, "Reading monitors") { c -> build(c) }

    private suspend fun build(c: ObdClient): VehicleCheckReport {
        fun step(text: String) { result.value = Op.Loading(text) }
        val readiness = runCatching { c.readReadiness() }.getOrNull()
        step("Reading trouble codes")
        val all = c.readAllDtcs()
        fun codes(t: DtcType) = all[t]?.takeIf { it.error == null }?.codes?.map { it.code }?.distinct()
        val supported = s.vehicle?.supportedPids ?: emptySet()
        val wanted = listOf(0x21, 0x30, 0x31, 0x0C, 0xA6).filter { it in supported }
        val p = if (wanted.isEmpty()) emptyMap() else c.readPids(wanted)
        fun word(pid: Int) = p[pid]?.takeIf { it.size >= 2 }?.let { ((it[0] shl 8) or it[1]).toDouble() }
        val odo = p[0xA6]?.takeIf { it.size >= 4 }?.let { b -> b.fold(0L) { acc, x -> (acc shl 8) or x.toLong() } / 10.0 }
        val running = word(0x0C)?.let { it / 4 > 300 }
        val voltage = s.adapter?.readVoltage()
        var results = emptyList<ModuleScanResult>()
        val vins = linkedMapOf<String, String>()
        val mc = runCatching { s.modules.client() }.getOrNull()
        if (mc != null) {
            step("Scanning control modules")
            results = s.modules.modulesForBrand().filter { !it.secondaryBus }.map { mc.scan(it) }
            step("Reading VIN from modules")
            for (r in results.filter { it.online && it.module.tx != 0x7E0 }.take(6)) {
                runCatching { mc.readVin(r.module) }.getOrNull()?.let { vins[r.module.shortName] = it }
            }
        }
        val active = s.garage.data.value.active
        val input = CheckInput(
            vin = s.vehicle?.vin, readiness = readiness,
            stored = codes(DtcType.STORED), pending = codes(DtcType.PENDING), permanent = codes(DtcType.PERMANENT),
            modules = results,
            distanceSinceClearKm = word(0x31), warmupsSinceClear = p[0x30]?.firstOrNull(), distanceWithMilKm = word(0x21),
            obdOdometerKm = odo, recordedOdometerKm = active?.odometerKm?.takeIf { !active.odometerFromCar },
            moduleVins = vins, batteryV = voltage, engineRunning = running,
        )
        s.debug.event("Vehicle check: $input")
        return VehicleCheck.evaluate(input)
    }
}
