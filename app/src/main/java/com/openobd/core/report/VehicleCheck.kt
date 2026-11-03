package com.openobd.core.report

import com.openobd.core.brand.ModuleScanResult
import com.openobd.core.obd.ReadinessStatus

enum class CheckLevel(val rank: Int) { OK(0), INFO(1), UNKNOWN(1), WARN(2), FAIL(3) }

enum class CheckSection(val label: String) {
    CODES("Trouble codes"), CLEARING("Recently cleared?"), READINESS("Emission monitors"), IDENTITY("Identity (VIN)"),
    ODOMETER("Odometer"), BATTERY("Battery and charging"), MODULES("Control modules"),
}

/**
 * One finding. [text] is an English template with %1$s-style placeholders filled from [args], so the UI and the
 * PDF can translate it. [itv] marks findings that matter for the emissions part of a roadworthiness inspection.
 */
data class CheckItem(
    val section: CheckSection,
    val level: CheckLevel,
    val text: String,
    val args: List<String> = emptyList(),
    val itv: Boolean = false,
)

enum class Verdict(val label: String, val detail: String) {
    GOOD("No red flags", "Nothing in the car's own diagnostics suggests hidden problems."),
    CHECK("Check before deciding", "Some findings deserve a question to the seller or a closer look by a mechanic."),
    BAD("Serious warning signs", "There are active faults or signs that faults were hidden. Get a professional inspection."),
}

enum class ItvVerdict(val label: String, val detail: String) {
    PASS("Likely to pass", "Warning light off, no emission faults and the monitors are ready."),
    NOT_READY("Not ready yet", "Monitors are still incomplete: drive 50-100 km mixed (town and road) and check again."),
    FAIL("Likely to fail", "Warning light on or emission-related faults stored. Repair them before the inspection."),
}

data class VehicleCheckReport(
    val items: List<CheckItem>,
    val verdict: Verdict,
    val itv: ItvVerdict,
    val generatedAt: Long,
) {
    fun section(s: CheckSection) = items.filter { it.section == s }
}

data class CheckInput(
    val vin: String?,
    val readiness: ReadinessStatus?,
    /** null = could not be read. */
    val stored: List<String>?,
    val pending: List<String>?,
    val permanent: List<String>?,
    val modules: List<ModuleScanResult> = emptyList(),
    /** PID 31: distance since codes were cleared (0-65535 km). */
    val distanceSinceClearKm: Double? = null,
    /** PID 30: warm-ups since codes were cleared (0-255). */
    val warmupsSinceClear: Int? = null,
    /** PID 21: distance driven with the warning light on. */
    val distanceWithMilKm: Double? = null,
    /** PID A6 (only on newer cars). */
    val obdOdometerKm: Double? = null,
    /** Odometer the user typed into the garage / saw on the dashboard. */
    val recordedOdometerKm: Double? = null,
    /** VIN reported by individual modules, by module short name. */
    val moduleVins: Map<String, String> = emptyMap(),
    val batteryV: Double? = null,
    val engineRunning: Boolean? = null,
)

/**
 * Turns everything the car reports about itself into a buyer's / pre-inspection checklist. All thresholds are
 * conservative rules of thumb; the report says so.
 */
object VehicleCheck {
    const val DISCLAIMER = "Based on what the car's own control units report. It is not an official inspection and cannot see mechanical wear, " +
        "bodywork or faults that the electronics do not detect."

    /** Every template used, for translation coverage. */
    val texts = listOf(
        "The warning light (MIL) is on.", "Warning light off.",
        "Stored codes: %1\$s", "No stored codes in the engine computer.", "Codes could not be read: %1\$s",
        "Pending codes (fault seen once, not confirmed yet): %1\$s", "Permanent codes: %1\$s",
        "Permanent codes remain although there are no stored codes: a fault was present and the codes were cleared without repairing it.",
        "Driven %1\$s km with the warning light on since the last clear.",
        "Codes were cleared very recently: %1\$s km and %2\$s warm-ups ago. Ask the seller why.",
        "Codes were cleared %1\$s km and %2\$s warm-ups ago. Normal after a service; ask what was repaired.",
        "No sign of recent clearing (%1\$s km since the last clear).", "This car does not report when codes were last cleared.",
        "%1\$s monitors not ready: %2\$s", "Typical right after clearing codes or disconnecting the battery.",
        "All supported monitors are ready (%1\$s).", "Monitor status could not be read.",
        "The same VIN everywhere (%1\$s modules): %2\$s", "Different VINs between modules: %1\$s. A module may come from another car.",
        "VIN reported by the car: %1\$s. Check it against the registration papers and the plate on the windscreen.",
        "The car did not report its VIN.",
        "The car reports %1\$s km but %2\$s km were recorded. Check the dashboard and the service history.",
        "The car reports %1\$s km, matching the recorded %2\$s km.",
        "The car reports %1\$s km. Compare with the dashboard.",
        "This car does not report its odometer over OBD: compare the dashboard with the service book and inspection records.",
        "Battery at rest: %1\$s V (weak, below 12.2 V).", "Battery at rest: %1\$s V (good).",
        "Charging voltage %1\$s V is low: alternator or wiring problem.", "Charging voltage %1\$s V is too high: voltage regulator problem.",
        "Charging voltage %1\$s V (good).", "Battery voltage could not be read.",
        "Faults in %1\$s module(s): %2\$s", "No faults in %1\$s module(s) that answered.",
        "Did not answer: %1\$s (normal for modules the car does not have).", "Module scan not available on this car.",
    )

    fun evaluate(i: CheckInput, now: Long = System.currentTimeMillis()): VehicleCheckReport {
        val items = mutableListOf<CheckItem>()
        fun add(s: CheckSection, l: CheckLevel, t: String, vararg a: Any?, itv: Boolean = false) {
            items += CheckItem(s, l, t, a.map { it.toString() }, itv)
        }
        fun km(v: Double) = "%,.0f".format(java.util.Locale.US, v).replace(',', ' ')

        // ---- codes
        val mil = i.readiness?.milOn
        if (mil == true) add(CheckSection.CODES, CheckLevel.FAIL, "The warning light (MIL) is on.", itv = true)
        else if (mil == false) add(CheckSection.CODES, CheckLevel.OK, "Warning light off.", itv = true)
        when {
            i.stored == null -> add(CheckSection.CODES, CheckLevel.UNKNOWN, "Codes could not be read: %1\$s", "stored")
            i.stored.isNotEmpty() -> add(CheckSection.CODES, CheckLevel.WARN, "Stored codes: %1\$s", i.stored.joinToString(", "),
                itv = i.stored.any { emissionRelated(it) })
            else -> add(CheckSection.CODES, CheckLevel.OK, "No stored codes in the engine computer.")
        }
        if (!i.pending.isNullOrEmpty()) add(CheckSection.CODES, CheckLevel.INFO, "Pending codes (fault seen once, not confirmed yet): %1\$s", i.pending.joinToString(", "))
        if (!i.permanent.isNullOrEmpty()) add(CheckSection.CODES, CheckLevel.WARN, "Permanent codes: %1\$s", i.permanent.joinToString(", "), itv = true)
        i.distanceWithMilKm?.takeIf { it > 0 }?.let { add(CheckSection.CODES, CheckLevel.WARN, "Driven %1\$s km with the warning light on since the last clear.", km(it)) }

        // ---- recently cleared
        val hidden = !i.permanent.isNullOrEmpty() && i.stored?.isEmpty() == true
        if (hidden) add(CheckSection.CLEARING, CheckLevel.FAIL,
            "Permanent codes remain although there are no stored codes: a fault was present and the codes were cleared without repairing it.")
        val dist = i.distanceSinceClearKm
        val warm = i.warmupsSinceClear
        when {
            dist == null && warm == null -> add(CheckSection.CLEARING, CheckLevel.UNKNOWN, "This car does not report when codes were last cleared.")
            (dist != null && dist < 200) || (warm != null && warm < 10) ->
                add(CheckSection.CLEARING, CheckLevel.WARN, "Codes were cleared very recently: %1\$s km and %2\$s warm-ups ago. Ask the seller why.",
                    dist?.let { km(it) } ?: "?", warm ?: "?")
            (dist != null && dist < 1000) || (warm != null && warm < 40) ->
                add(CheckSection.CLEARING, CheckLevel.INFO, "Codes were cleared %1\$s km and %2\$s warm-ups ago. Normal after a service; ask what was repaired.",
                    dist?.let { km(it) } ?: "?", warm ?: "?")
            else -> add(CheckSection.CLEARING, CheckLevel.OK, "No sign of recent clearing (%1\$s km since the last clear).",
                dist?.let { if (it >= 65535) "65 535+" else km(it) } ?: "?")
        }

        // ---- readiness
        val r = i.readiness
        if (r == null) add(CheckSection.READINESS, CheckLevel.UNKNOWN, "Monitor status could not be read.", itv = true)
        else {
            val notReady = r.incomplete.filter { !it.continuous }
            if (notReady.isEmpty()) add(CheckSection.READINESS, CheckLevel.OK, "All supported monitors are ready (%1\$s).", r.supported.size, itv = true)
            else {
                add(CheckSection.READINESS, if (notReady.size > 1) CheckLevel.WARN else CheckLevel.INFO, "%1\$s monitors not ready: %2\$s",
                    notReady.size, notReady.joinToString(", ") { it.name }, itv = true)
                if (dist != null && dist < 200) add(CheckSection.READINESS, CheckLevel.INFO, "Typical right after clearing codes or disconnecting the battery.")
            }
        }

        // ---- identity
        val vins = (listOfNotNull(i.vin?.let { "ECM (OBD)" to it }) + i.moduleVins.toList())
            .map { it.first to it.second.trim().uppercase() }.filter { it.second.length >= 11 }
        val distinct = vins.map { it.second }.distinct()
        when {
            vins.isEmpty() -> add(CheckSection.IDENTITY, CheckLevel.UNKNOWN, "The car did not report its VIN.")
            distinct.size > 1 -> add(CheckSection.IDENTITY, CheckLevel.WARN, "Different VINs between modules: %1\$s. A module may come from another car.",
                vins.joinToString("; ") { "${it.first} ${it.second}" })
            vins.size > 1 -> add(CheckSection.IDENTITY, CheckLevel.OK, "The same VIN everywhere (%1\$s modules): %2\$s", vins.size, distinct.single())
            else -> add(CheckSection.IDENTITY, CheckLevel.INFO,
                "VIN reported by the car: %1\$s. Check it against the registration papers and the plate on the windscreen.", distinct.single())
        }

        // ---- odometer
        val odo = i.obdOdometerKm
        val rec = i.recordedOdometerKm
        when {
            odo != null && rec != null && kotlin.math.abs(odo - rec) > maxOf(1000.0, rec * 0.02) ->
                add(CheckSection.ODOMETER, CheckLevel.WARN, "The car reports %1\$s km but %2\$s km were recorded. Check the dashboard and the service history.", km(odo), km(rec))
            odo != null && rec != null -> add(CheckSection.ODOMETER, CheckLevel.OK, "The car reports %1\$s km, matching the recorded %2\$s km.", km(odo), km(rec))
            odo != null -> add(CheckSection.ODOMETER, CheckLevel.INFO, "The car reports %1\$s km. Compare with the dashboard.", km(odo))
            else -> add(CheckSection.ODOMETER, CheckLevel.UNKNOWN,
                "This car does not report its odometer over OBD: compare the dashboard with the service book and inspection records.")
        }

        // ---- battery
        val v = i.batteryV
        when {
            v == null -> add(CheckSection.BATTERY, CheckLevel.UNKNOWN, "Battery voltage could not be read.")
            i.engineRunning == true && v < 13.2 -> add(CheckSection.BATTERY, CheckLevel.WARN, "Charging voltage %1\$s V is low: alternator or wiring problem.", "%.1f".format(v))
            i.engineRunning == true && v > 14.9 -> add(CheckSection.BATTERY, CheckLevel.WARN, "Charging voltage %1\$s V is too high: voltage regulator problem.", "%.1f".format(v))
            i.engineRunning == true -> add(CheckSection.BATTERY, CheckLevel.OK, "Charging voltage %1\$s V (good).", "%.1f".format(v))
            v < 12.2 -> add(CheckSection.BATTERY, CheckLevel.WARN, "Battery at rest: %1\$s V (weak, below 12.2 V).", "%.1f".format(v))
            else -> add(CheckSection.BATTERY, CheckLevel.OK, "Battery at rest: %1\$s V (good).", "%.1f".format(v))
        }

        // ---- modules
        if (i.modules.isEmpty()) add(CheckSection.MODULES, CheckLevel.UNKNOWN, "Module scan not available on this car.")
        else {
            val online = i.modules.filter { it.online }
            val faulty = online.filter { it.dtcs.isNotEmpty() }
            if (faulty.isNotEmpty()) add(CheckSection.MODULES, CheckLevel.WARN, "Faults in %1\$s module(s): %2\$s", faulty.size,
                faulty.joinToString("; ") { m -> m.module.shortName + " " + m.dtcs.joinToString(", ") { it.code } })
            else add(CheckSection.MODULES, CheckLevel.OK, "No faults in %1\$s module(s) that answered.", online.size)
            val silent = i.modules.filter { !it.online }
            if (silent.isNotEmpty()) add(CheckSection.MODULES, CheckLevel.INFO, "Did not answer: %1\$s (normal for modules the car does not have).",
                silent.joinToString(", ") { it.module.shortName })
        }

        val worst = items.maxOf { it.level.rank }
        val verdict = when {
            worst >= CheckLevel.FAIL.rank -> Verdict.BAD
            worst >= CheckLevel.WARN.rank -> Verdict.CHECK
            else -> Verdict.GOOD
        }
        val itvItems = items.filter { it.itv }
        val itv = when {
            itvItems.any { it.level == CheckLevel.FAIL } || (i.stored.orEmpty().any { emissionRelated(it) }) || !i.permanent.isNullOrEmpty() -> ItvVerdict.FAIL
            r == null || r.incomplete.count { !it.continuous } > 1 -> ItvVerdict.NOT_READY
            else -> ItvVerdict.PASS
        }
        return VehicleCheckReport(items, verdict, itv, now)
    }

    /** Powertrain codes (P0xxx generic, P2xxx) are the ones emission inspections care about. */
    fun emissionRelated(code: String) = code.length == 5 && code[0] == 'P' && code[1] in setOf('0', '2', '3')
}
