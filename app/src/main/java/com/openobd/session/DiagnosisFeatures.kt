package com.openobd.session

import com.openobd.core.diagnosis.AssistantContext
import com.openobd.core.diagnosis.Diagnosis
import com.openobd.core.diagnosis.DiagnosisContext
import com.openobd.core.diagnosis.Explanation
import com.openobd.core.obd.SensorCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Explained trouble codes and the data given to the AI assistant. */
class DiagnosisFeatures internal constructor(private val s: ObdSession) {
    /** What this session already knows that helps explain a code (no new requests). */
    fun context(code: String): DiagnosisContext {
        val live = s.live.readings.value.mapValues { it.value.value }
        val scan = (s.dtcScan.value as? Op.Done)?.value
        val active = s.garage.data.value.active
        val trips = s.garage.data.value.trips.filter { it.vehicleId == active?.id && it.distanceKm > 0.5 }.sortedByDescending { it.start }.take(10)
        return DiagnosisContext(
            freezeFrame = (s.freezeFrame.value as? Op.Done)?.value?.values.orEmpty(),
            live = live,
            mode06 = (s.monitorTests.value as? Op.Done)?.value.orEmpty(),
            otherCodes = scan?.codes?.map { it.code }?.filter { it != code }?.distinct().orEmpty(),
            isDiesel = scan?.readiness?.isDiesel == true || active?.specs?.fuel?.name?.contains("DIESEL") == true,
            averageTripKm = trips.takeIf { it.size >= 3 }?.map { it.distanceKm }?.average(),
            batteryV = s.vehicle?.voltage,
            runMinutes = live["011F"]?.let { it / 60 },
        )
    }

    fun explain(code: String): Explanation = Diagnosis.explain(code, s.describe(code).knowledge, context(code))

    /** Reads the freeze frame and on-board tests so the explanation has more evidence. */
    fun readMoreData() {
        if (s.freezeFrame.value !is Op.Done) s.loadFreezeFrame()
        s.appScope.launch {
            while (s.freezeFrame.value is Op.Loading) delay(100)
            if (s.monitorTests.value !is Op.Done && s.client?.isCan == true) s.loadMonitorTests()
        }
    }

    /** Make, model, engine and fuel of the active car as known to the app (no VIN). */
    fun carDescription(): AssistantContext.Car {
        val specs = s.garage.data.value.active?.specs
        return AssistantContext.Car(
            description = specs?.let { sp -> listOf(sp.make, sp.model, sp.version, sp.year?.toString(), sp.engineCc?.let { "$it cc" }, sp.powerKw?.let { "%.0f kW".format(it) })
                .filter { !it.isNullOrBlank() }.joinToString(" ") }?.takeIf { it.isNotBlank() } ?: s.brand.value?.name,
            fuel = specs?.fuel?.label,
            protocol = s.vehicle?.elm?.protocol?.label,
        )
    }

    /** The car data sent to the AI assistant for [code] (no VIN, no location). */
    fun assistantContext(code: String): String {
        fun c(code: String, source: String) = AssistantContext.Code(code, s.describe(code).description, source)
        val scan = (s.dtcScan.value as? Op.Done)?.value
        val focusType = scan?.codes?.firstOrNull { it.code == code }?.type?.label ?: "code"
        val others = scan?.codes?.filter { it.code != code }?.distinctBy { it.code }?.map { c(it.code, it.type.label) }.orEmpty()
        val ctx = context(code)
        val failed = ctx.mode06.filter { !it.passed }.map { "${it.monitorName} / ${it.testName}: ${it.value} (limits ${it.min}..${it.max} ${it.unit})" }
        val readiness = scan?.readiness?.let { r -> "${r.complete.size}/${r.supported.size} ready" + if (r.incomplete.isNotEmpty()) ", not ready: " + r.incomplete.joinToString { it.name } else "" }
        return AssistantContext.build(carDescription(), c(code, focusType), explain(code), others, named(ctx.freezeFrame), named(ctx.live), failed, readiness, ctx.batteryV)
    }

    /** Sensor values by human-readable name, for text sent to the assistant. */
    fun named(values: Map<String, Double>): Map<String, String> = values.entries.take(30).associate { (k, v) ->
        val def = SensorCatalog.get(k)
        (def?.name ?: k) to ("%.1f".format(java.util.Locale.US, v).removeSuffix(".0") + " " + (def?.customUnit ?: def?.unit?.symbol ?: "")).trim()
    }
}
