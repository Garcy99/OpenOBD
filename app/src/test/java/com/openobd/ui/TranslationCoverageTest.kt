package com.openobd.ui

import com.openobd.core.brand.Families
import com.openobd.core.brand.FailureTypes
import com.openobd.core.brand.ModuleCategory
import com.openobd.core.elm.ElmError
import com.openobd.core.garage.Maintenance
import com.openobd.core.obd.DerivedSensors
import com.openobd.core.obd.DtcSystem
import com.openobd.core.obd.DtcType
import com.openobd.core.obd.MonitorState
import com.openobd.core.obd.Pids
import com.openobd.core.obd.Readiness
import com.openobd.core.obd.SensorCategory
import com.openobd.data.ConnectionType
import com.openobd.data.FuelKind
import com.openobd.data.ThemeMode
import com.openobd.session.MS_CAN_BUSY
import com.openobd.session.PerfTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every user-visible English phrase must have a Spanish translation. Prints the missing ones as JSON. */
class TranslationCoverageTest {
    private val root = File("src/main").takeIf { it.exists() } ?: File("app/src/main")

    private fun unescape(s: String) = s.replace("\\$", "$").replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")

    fun requiredKeys(): Set<String> {
        val keys = linkedSetOf<String>()
        val trCall = Regex("""\btr\("((?:[^"\\]|\\.)*)"""")
        val menu = Regex("""(?:MenuItem|Tab)\(Routes\.\w+, "((?:[^"\\]|\\.)*)"(?:, "((?:[^"\\]|\\.)*)")?""")
        File(root, "java/com/openobd/ui").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            trCall.findAll(src).forEach { keys += unescape(it.groupValues[1]) }
            menu.findAll(src).forEach { m -> keys += unescape(m.groupValues[1]); if (m.groupValues[2].isNotEmpty()) keys += unescape(m.groupValues[2]) }
        }
        keys.remove("") // tr(variable) calls
        SensorCategory.entries.forEach { keys += it.label }
        (Pids.sensors.values + DerivedSensors.all).forEach { keys += it.name; keys += it.shortName }
        listOf(0, 1, 2, 4, 8, 16).forEach { keys += Pids.fuelSystemStatus(it) }
        listOf(1, 2, 4, 8).forEach { keys += Pids.secondaryAirStatus(it) }
        keys += Pids.fuelTypes.values; keys += listOf("ON", "OFF")
        DtcType.entries.forEach { keys += it.label; keys += it.description }
        DtcSystem.entries.forEach { keys += it.label }
        MonitorState.entries.forEach { keys += it.label }
        listOf(intArrayOf(0x80, 0x77, 0xFF, 0x00), intArrayOf(0x80, 0x7F, 0xFF, 0x00)).forEach { d -> Readiness.decode(d).monitors.forEach { keys += it.name } }
        ConnectionType.entries.forEach { keys += it.label }
        FuelKind.entries.forEach { keys += it.label }
        ThemeMode.entries.filter { it != ThemeMode.AMOLED }.forEach { keys += it.name.lowercase().replaceFirstChar(Char::uppercase) }
        com.openobd.ui.screens.DashboardPresets.all.forEach { keys += it.first }
        ModuleCategory.entries.forEach { keys += it.label }
        Families.all.forEach { f -> keys += f.notes; f.modules.forEach { keys += it.name } }
        keys += Families.GENERIC.label
        PerfTarget.entries.forEach { keys += it.label }
        Maintenance.presets.forEach { keys += it.first }
        for (ftb in 0..0xEF) { FailureTypes.describe(ftb).takeIf { !it.startsWith("Failure type 0x") }?.let { keys += it }; FailureTypes.hint(ftb)?.let { keys += it } }
        keys += "Manufacturer-specific failure type"
        ElmError.entries.forEach { keys += it.userMessage }
        keys += listOf(com.openobd.core.elm.Elm327.NO_PROTOCOL_MESSAGE, "Trying other protocols")
        keys += listOf(com.openobd.transport.DeviceScanner.SCAN_BUSY, com.openobd.transport.DeviceScanner.SCAN_UNSUPPORTED)
        com.openobd.ui.components.GaugeTheme.entries.forEach { keys += it.label }
        com.openobd.ui.components.GaugeStyle.entries.forEach { keys += it.label }
        keys += listOf(com.openobd.transport.UsbSerialTransport.NO_CABLE, com.openobd.transport.UsbSerialTransport.NO_PERMISSION,
            com.openobd.transport.UsbSerialTransport.NO_ANSWER)
        keys += MS_CAN_BUSY
        com.openobd.core.specs.SpecFuel.entries.forEach { keys += it.label }
        com.openobd.core.specs.DgtLabel.entries.forEach { keys += it.label }
        keys += com.openobd.core.specs.EeaCatalog.ATTRIBUTION
        keys += listOf("Low", "Moderate", "High", "Unknown", "Demo car (simulated)")
        val client = com.openobd.core.obd.ObdClient(com.openobd.core.elm.Elm327(com.openobd.core.transport.DemoTransport()))
        listOf("7E8", "7E9", "7EA", "7EB", "7EC", "7ED", "7EE", "7EF", "ECU").forEach { keys += client.ecuName(it) }
        keys += listOf("Active now", "Confirmed", "Pending", "Warning lamp on", "Intermittent / history", "Stored")
        keys += listOf("Resetting adapter", "Configuring adapter", "Connecting to vehicle (protocol search can take ~15s)", "Reading supported sensors", "Reading VIN",
            "Reading trouble codes", "Clearing trouble codes", "Reading monitors", "Reading freeze frame", "Reading vehicle information",
            "Reading on-board test results", "Starting", "Probing CAN addresses", "Not connected to a vehicle", "The adapter stopped responding.")
        keys += com.openobd.core.report.VehicleCheck.texts + com.openobd.core.report.VehicleCheck.DISCLAIMER
        com.openobd.core.report.CheckSection.entries.forEach { keys += it.label }
        com.openobd.core.report.Verdict.entries.forEach { keys += it.label; keys += it.detail }
        com.openobd.core.report.ItvVerdict.entries.forEach { keys += it.label; keys += it.detail }
        keys += listOf("Scanning control modules", "Reading VIN from modules")
        keys += com.openobd.core.diagnosis.Diagnosis.texts
        keys += com.openobd.core.health.HealthTrends.texts
        keys += com.openobd.core.service.Procedures.texts
        keys += com.openobd.core.brand.PidPacks.texts
        keys += com.openobd.core.brand.Obdb.texts
        keys += com.openobd.core.trip.routeExportTexts + com.openobd.ui.screens.RouteFormat.texts
        keys += com.openobd.core.brand.Coding.texts + com.openobd.core.brand.CodingEngine.texts + com.openobd.core.brand.WriteSafety.texts
        keys += listOf("Applied", "Undone", "Not changed", "Restored", "Needs restore", "Interrupted", "Started", "Failed", "Identifier explorer", "Typed in")
        keys += com.openobd.core.specs.CrossCheck.texts
        keys += com.openobd.core.voice.VoiceWatch.texts
        keys += com.openobd.core.trip.EcoScorer.texts
        keys += com.openobd.core.report.CarScore.texts
        keys += listOf("Good morning", "Good afternoon", "Good evening", "Home", "Live", "Diagnose", "Garage", "More")
        keys += com.openobd.core.battery.CrankAnalysis.texts + com.openobd.session.BatteryTestFeature.texts
        keys += listOf("Name is required", "Request must be hex bytes, e.g. 221E1C",
            "Only read requests are allowed (services 01, 02, 09, 21, 22, 1A, AA)", "Header must be a CAN ID like 7E0")
        // Causes and advice of the code database (shown translated in the code screen)
        File(root, "assets/dtc/causes.tsv").readLines().filter { !it.startsWith("#") && it.contains('\t') }.forEach { l ->
            val p = l.split('\t'); keys += p[2].split(';').map { it.trim() }; keys += p[3].trim()
        }
        // Code categories, translated part by part
        for (sys in "PCBU") for (c in "0123456789ABCDEF") {
            val cat = com.openobd.core.obd.DtcDecoder.genericCategory("${sys}0${c}00").removePrefix("Manufacturer-specific code. ")
            cat.split(" - ").forEach { keys += it }
        }
        keys += listOf("Manufacturer-specific (%1\$s). ", "Manufacturer-specific code. ", "Unknown code")
        com.openobd.core.health.HealthMetric.entries.forEach { keys += it.label }
        com.openobd.core.diagnosis.DriveAdvice.entries.forEach { keys += it.label }
        keys += listOf("The Claude API key was rejected. Check it in Settings.", "This API key is not allowed to use the model.",
            "Too many requests right now. Wait a minute and try again.", "No connection to the assistant. Check your internet connection.",
            "The assistant declined to answer this request.", "The assistant returned an empty answer.", "The assistant is not available right now. Try again later.")
        return keys
    }

    /** User-visible Text("...") literals must go through tr(). */
    @Test
    fun noUntranslatedTextLiterals() {
        val bad = Regex("""\b(?:Text|ScreenTopBar|ErrorText|Pill)\("([^"]*[a-z][^"]*)"""")
        // Ignore pure formatting (values, units, numbers): only flag literals containing actual words.
        val word = Regex("[A-Za-z]*[a-z]{3,}[A-Za-z]*")
        val allowed = setOf("OpenOBD")
        val offenders = File(root, "java/com/openobd/ui").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            bad.findAll(f.readText()).map { it.groupValues[1] }
                .filter { lit -> word.findAll(lit.replace(Regex("\\$\\{[^}]*\\}?|\\$\\w+|%[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]"), " ")).any { it.value !in allowed } }
                .map { "${f.name}: $it" }
        }.toList()
        assertTrue("Wrap these in tr(): $offenders", offenders.isEmpty())
    }

    /** Every language in the picker has a complete table, with the same placeholders, and its widget strings. */
    @Test
    fun everyLanguageCoversEverything() {
        val keys = requiredKeys()
        val ph = Regex("%\\d+\\\$s|%%|%[dsf]")
        val problems = mutableListOf<String>()
        val widgetNames = Regex("<string name=\"(widget_[a-z_]+)\"").findAll(File(root, "res/values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        for (lang in I18n.languages.map { it.first }.filter { it != "system" && it != "en" }) {
            val file = File(root, "assets/i18n/$lang.json")
            if (!file.exists()) { problems += "$lang: assets/i18n/$lang.json missing"; continue }
            val table = Json.decodeFromString<Map<String, String>>(file.readText())
            val missing = keys.filter { it !in table }
            if (missing.isNotEmpty()) {
                File("build/i18n-missing-$lang.json").apply { parentFile.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(missing))
                if (lang == "es") File("build/i18n-missing.json").writeText(Json { prettyPrint = true }.encodeToString(missing))
                problems += "$lang: ${missing.size} untranslated phrases, see app/build/i18n-missing-$lang.json"
            }
            table.forEach { (k, v) ->
                val a = ph.findAll(k).map { it.value }.toList().sorted()
                val b = ph.findAll(v).map { it.value }.toList().sorted()
                if (a != b) problems += "$lang: placeholder mismatch: $k -> $v"
                if (v.isBlank() && k.isNotBlank()) problems += "$lang: empty translation for $k"
            }
            val res = File(root, "res/values-$lang/strings.xml")
            val names = if (res.exists()) Regex("<string name=\"(widget_[a-z_]+)\"").findAll(res.readText()).map { it.groupValues[1] }.toSet() else emptySet()
            if (names != widgetNames) problems += "$lang: widget strings missing in res/values-$lang: ${widgetNames - names}"
        }
        assertTrue(problems.take(40).joinToString("\n"), problems.isEmpty())
    }
}
