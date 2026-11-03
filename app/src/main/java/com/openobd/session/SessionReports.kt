package com.openobd.session

import com.openobd.core.debug.DebugReport
import com.openobd.core.obd.Pids
import com.openobd.data.ConnectionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Text reports about the current session: the shareable diagnostic report and the debug report sections. */
class SessionReports internal constructor(private val s: ObdSession) {
    /** Session details for a debug report (no personal data beyond a masked VIN). */
    fun debugSections(): List<Pair<String, List<Pair<String, String?>>>> {
        val st = s.settings.value
        val v = s.vehicle
        val c = s.client
        val settings = "Settings" to listOf(
            "Connection" to st.connectionType.name,
            "Adapter" to st.deviceName,
            "Protocol setting" to st.protocol.label,
            "Adaptive timing" to st.adaptiveTiming.toString(),
            "Timeout ms" to st.elmTimeoutMs.toString(),
            "Multi-PID / fast responses" to "${st.batching} / ${st.fastMode}",
            "HS/MS-CAN switch" to st.msCanSwitch.toString(),
            "Brand" to (st.brandId ?: "auto (${s.brand.value?.name ?: "-"})"),
            "Custom PIDs" to st.customPids.size.toString(),
            "Demo simulator" to if (st.connectionType == ConnectionType.DEMO) "${st.demoProtocol.label}, faults ${st.demoFaults}" else null,
        )
        val conn = "Connection" to listOf(
            "State" to s.state.value.let { it::class.simpleName + ((it as? ConnectionState.Failed)?.let { f -> ": " + f.message } ?: "") },
            "Transport" to v?.transport,
            "ELM version" to v?.elm?.version,
            "Device description" to v?.elm?.deviceDescription,
            "STN" to v?.elm?.let { if (it.isStn) it.stnVersion else "no" },
            "Protocol" to v?.elm?.protocol?.label,
            "Bus" to s.modules.bus.value.name,
            "ECUs" to v?.ecus?.joinToString { "${it.address} ${it.name}" },
            "Supported PIDs" to v?.supportedPids?.joinToString(" ") { "%02X".format(it) },
            "VIN" to DebugReport.maskVin(v?.vin),
            "Battery" to v?.voltage?.let { "%.1f V".format(Locale.US, it) },
            "Multi-PID active / fast active" to c?.let { "${it.batchingEnabled} / ${it.fastResponses}" },
            "STN fast mode verified" to c?.stnFastVerified?.toString(),
        )
        return listOf(conn, settings)
    }

    /** Plain-text diagnostic report for sharing. */
    fun text(): String {
        val v = s.vehicle
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.appendLine("OpenOBD diagnostic report - ${df.format(Date())}")
        sb.appendLine("=".repeat(48))
        if (v != null) {
            sb.appendLine("VIN: ${v.vin ?: "not available"}")
            s.brand.value?.let { sb.appendLine("Brand: ${it.name}") }
            sb.appendLine("Protocol: ${v.elm.protocol.label}")
            sb.appendLine("Adapter: ${v.elm.version} via ${v.transport}")
            v.voltage?.let { sb.appendLine("Battery: %.1f V".format(it)) }
            sb.appendLine("ECUs: " + v.ecus.joinToString { "${it.address} ${it.name}" })
        }
        (s.dtcScan.value as? Op.Done)?.value?.let { scan ->
            scan.readiness?.let { r ->
                sb.appendLine()
                sb.appendLine("Check engine light: ${if (r.milOn) "ON" else "OFF"} (${r.dtcCount} confirmed)")
            }
            sb.appendLine()
            sb.appendLine("Trouble codes:")
            if (scan.codes.isEmpty()) sb.appendLine("  none")
            for (d in scan.codes) sb.appendLine("  ${d.code} [${d.type.label}, ECU ${d.ecu}] ${s.describe(d.code).description}")
            scan.readiness?.let { r ->
                sb.appendLine()
                sb.appendLine("Readiness monitors:")
                for (m in r.monitors) sb.appendLine("  ${m.name}: ${m.state.label}")
            }
        }
        (s.modules.moduleScan.value as? Op.Done)?.value?.let { results ->
            sb.appendLine()
            sb.appendLine("Control modules (${s.brand.value?.family?.label ?: "generic"}):")
            for (r in results) {
                sb.appendLine("  ${r.module.name} [${r.module.addressLabel}]: " + when {
                    !r.online -> "no answer"
                    r.dtcs.isEmpty() -> "OK, no codes"
                    else -> "${r.dtcs.size} code(s)"
                })
                for (d in r.dtcs) sb.appendLine("    ${d.fullCode} ${s.describe(d.code).description} (${d.failureTypeText ?: d.statusText})")
            }
        }
        (s.freezeFrame.value as? Op.Done)?.value?.let { ff ->
            sb.appendLine()
            sb.appendLine("Freeze frame (DTC ${ff.dtc ?: "-"}):")
            for ((k, value) in ff.values) {
                val def = Pids.sensors[k] ?: continue
                sb.appendLine("  ${def.name}: ${def.labeler?.invoke(value) ?: "%.${def.decimals}f %s".format(value, def.unit.symbol)}")
            }
        }
        return sb.toString()
    }
}
