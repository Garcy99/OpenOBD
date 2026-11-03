package com.openobd.session

import com.openobd.core.specs.ObservedCar
import kotlinx.coroutines.flow.MutableStateFlow

/** Reads what the car says about itself (VIN, fuel type, ignition type) to cross-check the saved specifications. */
class SpecsCheckFeature internal constructor(private val s: ObdSession) {
    val observed = MutableStateFlow<Op<ObservedCar>>(Op.Idle)

    fun read() = s.runOp(observed, "Reading vehicle information") { c ->
        val supported = s.vehicle?.supportedPids ?: emptySet()
        val fuel = if (0x51 in supported) runCatching { c.readPids(listOf(0x51))[0x51]?.firstOrNull() }.getOrNull() else null
        val readiness = runCatching { c.readReadiness() }.getOrNull()
        ObservedCar(vin = s.vehicle?.vin, fuelType = fuel, compressionIgnition = readiness?.isDiesel)
    }
}
