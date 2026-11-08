package com.openobd.core.brand

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One make / model of the OBDb catalog (a GitHub repository "Make" or "Make-Model"); [make] and [model] are readable names. */
@Serializable
data class ObdbEntry(val repo: String, val make: String, val model: String = "", val signals: Int = 0) {
    /** "Vauxhall / Opel Mokka", "Land Rover Defender". */
    val title: String get() = (Obdb.makeTitle(make) + " " + model.replace('-', ' ')).trim()
}

/**
 * OBDb (github.com/OBDb, obdb.community) is an open, community-made database of manufacturer parameters,
 * CC BY-SA 4.0. Its "signalset v3" files are converted here into read-only custom PIDs: every signal becomes a
 * formula over the reply bytes, so no new decoder is needed.
 *
 * Format (OBDb/.schemas signals.json and its reference decoder): a command is a header ("7E0", or "DA10" for
 * 29-bit 18DA10F1), an optional reply address, and one request ({"22": "18E4"}, {"21": "48"}, {"01": "0C"}).
 * Each signal reads `len` bits starting at bit `bix` of the data after the service byte and identifier,
 * most significant bit first (`blsb` = bytes in little-endian order), then value = raw * mul / div + add.
 */
object Obdb {
    const val ATTRIBUTION = "Data: OBDb contributors (obdb.community), CC BY-SA 4.0"
    const val SOURCE = "OBDb (CC BY-SA 4.0)"

    fun rawUrl(repo: String) = "https://raw.githubusercontent.com/OBDb/$repo/main/signalsets/v3/default.json"
    fun pageUrl(repo: String) = "https://github.com/OBDb/$repo"

    private val json = Json { ignoreUnknownKeys = true }

    fun makeTitle(make: String) = when (make) {
        "VauxhallOpel" -> "Vauxhall / Opel"
        "NissanInfiniti" -> "Nissan / Infiniti"
        else -> make
    }

    fun decodeIndex(text: String): List<ObdbEntry> = json.decodeFromString(text)

    /** "Škoda" -> "skoda", "Mercedes-Benz" -> "mercedesbenz", "Land Rover" -> "landrover". */
    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    /** Names the app or a registry may use for a make, normalised, to OBDb's make names (normalised). */
    private val aliases = mapOf(
        "opel" to "vauxhallopel", "vauxhall" to "vauxhallopel", "opelvauxhall" to "vauxhallopel", "vauxhallopel" to "vauxhallopel",
        "mercedes" to "mercedesbenz", "mercedesbenz" to "mercedesbenz", "mb" to "mercedesbenz",
        "vw" to "volkswagen", "landrover" to "landrover", "rangerover" to "landrover",
        "alfa" to "alfaromeo", "rollsroyce" to "rollsroyce", "citroen" to "citroen", "chevy" to "chevrolet",
    )

    /**
     * Makes that share platforms and engine computers, so a related make's data often works too
     * (a Seat Leon and a Golf, a Citroën and a Peugeot, a Lexus and a Toyota ...).
     */
    private val groups: List<Set<String>> = listOf(
        setOf("volkswagen", "audi", "seat", "cupra", "skoda", "porsche"),
        setOf("peugeot", "citroen", "ds", "vauxhallopel"),
        setOf("fiat", "alfaromeo", "jeep", "chrysler", "dodge", "ram", "abarth", "lancia"),
        setOf("chevrolet", "gmc", "buick", "cadillac", "holden", "vauxhallopel"),
        setOf("toyota", "lexus", "scion", "daihatsu"),
        setOf("hyundai", "kia", "genesis"),
        setOf("renault", "dacia"),
        setOf("nissan", "infiniti", "nissaninfiniti"),
        setOf("bmw", "mini", "rollsroyce"),
        setOf("mercedesbenz", "smart"),
        setOf("ford", "lincoln", "mercury"),
        setOf("honda", "acura"),
        setOf("jaguar", "landrover"),
        setOf("volvo", "polestar"),
        setOf("mg", "saic"),
    )

    private fun canonical(make: String): Set<String> {
        val n = norm(make)
        val a = aliases[n] ?: n
        // "Nissan" matches both Nissan and the shared Nissan / Infiniti set.
        return setOf(a) + (if (a == "nissan" || a == "infiniti") setOf("nissaninfiniti") else emptySet())
    }

    private fun modelScore(e: ObdbEntry, model: String?): Int {
        val md = model?.let(::norm)?.takeIf { it.isNotEmpty() }
        val firstWord = model?.trim()?.split(' ', '-')?.firstOrNull()?.let(::norm).orEmpty()
        val em = norm(e.model)
        return when {
            em.isEmpty() -> 2
            md != null && md.startsWith(em) -> 0
            firstWord.isNotEmpty() && em.startsWith(firstWord) -> 1
            else -> 3
        }
    }

    /** Catalog entries for a car, most specific first: the model's own repository, then the make's general one. */
    fun forCar(index: List<ObdbEntry>, make: String?, model: String?): List<ObdbEntry> {
        if (make.isNullOrBlank()) return emptyList()
        val makes = canonical(make)
        val sameMake = index.filter { e -> canonical(e.make).any { it in makes } }
        // Model unknown or not in the catalog: the make's largest sets (other models often share engines and modules).
        val matching = sameMake.filter { modelScore(it, model) < 3 }.ifEmpty { sameMake }
        return matching.sortedWith(compareBy<ObdbEntry> { modelScore(it, model) }.thenByDescending { it.signals })
    }

    /**
     * Entries of makes in the same group (platform sharing), for when the car's own make has little or nothing:
     * same model name first, then the related makes' general sets and their largest models.
     */
    fun related(index: List<ObdbEntry>, make: String?, model: String?, limit: Int = 4): List<ObdbEntry> {
        if (make.isNullOrBlank()) return emptyList()
        val own = canonical(make)
        val others = groups.filter { g -> own.any { it in g } }.flatten().toSet() - own
        return index.filter { e -> canonical(e.make).any { it in others } }
            .sortedWith(compareBy<ObdbEntry> { modelScore(it, model).let { s -> if (s == 3) 4 else s } }.thenByDescending { it.signals })
            .take(limit)
    }

    /** Every entry, sorted for browsing. */
    fun search(index: List<ObdbEntry>, query: String): List<ObdbEntry> {
        val q = norm(query)
        return index.filter { q.isEmpty() || norm(it.title).contains(q) || norm(it.repo).contains(q) }.sortedBy { it.title.lowercase() }
    }

    private fun varName(i: Int): String = if (i < 26) ('A' + i).toString() else ('A' + (i / 26 - 1)).toString() + ('A' + i % 26)

    /**
     * Formula for `len` bits at `bix` (MSB first), optionally signed, with OBDb's scaling. Values are clamped to
     * [min, max] like the reference decoder.
     */
    fun formula(bix: Int, len: Int, blsb: Boolean, signed: Boolean, mul: Double, div: Double, add: Double, min: Double?, max: Double?): String {
        val first = bix / 8
        val last = (bix + len - 1) / 8
        val bytes = (first..last).toList().let { if (blsb && len > 8) it.reversed() else it }
        val n = bytes.size
        // Bytes as one big-endian number
        var raw = bytes.mapIndexed { k, b -> val shift = 8 * (n - 1 - k); if (shift == 0) varName(b) else "(${varName(b)}<<$shift)" }.joinToString("|")
        if (n > 1) raw = "($raw)"
        val drop = if (blsb && len > 8) 0 else 8 * n - (bix % 8) - len
        if (len == 1 && n == 1) raw = "{${varName(first)}:${7 - bix % 8}}"
        else {
            if (drop > 0) raw = "($raw>>$drop)"
            if (len % 8 != 0 || drop > 0) raw = "($raw&${(1L shl len) - 1})"
        }
        if (signed) raw = "($raw-($raw>=${1L shl (len - 1)})*${1L shl len})"
        var expr = raw
        if (mul != 1.0) expr = "$expr*${num(mul)}"
        if (div != 1.0) expr = "$expr/${num(div)}"
        if (add != 0.0) expr = "$expr+${num(add)}"
        if (min != null && max != null && max > min) expr = "Min(Max($expr,${num(min)}),${num(max)})"
        return expr
    }

    /** Plain decimal text: the equation parser does not read exponents such as 1.0E-4. */
    private fun num(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()

    /** OBDb units as shown in the app (known ones convert to imperial). */
    fun unit(u: String?): String = when (u) {
        "celsius" -> "°C"; "fahrenheit" -> "°F"; "percent" -> "%"; "volts" -> "V"; "millivolts" -> "mV"; "amps" -> "A"; "milliamps" -> "mA"
        "rpm" -> "rpm"; "kilometersPerHour" -> "km/h"; "milesPerHour" -> "mph"; "kilometers" -> "km"; "miles" -> "mi"; "meters" -> "m"
        "centimeters" -> "cm"; "millimeters" -> "mm"; "kilopascal" -> "kPa"; "bars" -> "bar"; "psi" -> "psi"; "newtonMeters" -> "Nm"
        "degrees" -> "°"; "seconds" -> "s"; "milliseconds" -> "ms"; "minutes" -> "min"; "hours" -> "h"; "liters" -> "L"; "gallons" -> "gal"
        "kilowatts" -> "kW"; "watts" -> "W"; "kilowattHours" -> "kWh"; "ampereHours" -> "Ah"; "gramsPerSecond" -> "g/s"; "litersPerHour" -> "L/h"
        "kilogramsPerHour" -> "kg/h"; "milligramsPerStroke" -> "mg/st"; "ohms" -> "Ω"; "kiloohms" -> "kΩ"; "megaohms" -> "MΩ"; "milliohms" -> "mΩ"
        "hertz" -> "Hz"; "gravity" -> "g"; "metersPerSecondSquared" -> "m/s²"; "metersPerSecond" -> "m/s"; "radians" -> "rad"; "gramsPerLiter" -> "g/L"
        "kilowattHoursPer100Kilometers" -> "kWh/100km"; "kilowattHoursPer100Miles" -> "kWh/100mi"
        else -> ""
    }

    private val onOff = mapOf("0" to "Off", "1" to "On")
    private val noYes = mapOf("0" to "No", "1" to "Yes")

    private fun filterMatches(f: JsonObject?, year: Int?): Boolean {
        if (f == null || year == null) return true
        val from = (f["from"] as? JsonPrimitive)?.intOrNull
        val to = (f["to"] as? JsonPrimitive)?.intOrNull
        val years = (f["years"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        if (year in years) return true
        return when {
            from != null && to != null -> if (from < to) year in from..to else year >= from || year <= to
            from != null -> year >= from
            to != null -> year <= to
            else -> years.isEmpty()
        }
    }

    /**
     * Converts one signal set into read-only custom PIDs. [year] drops commands whose year filter excludes the car.
     * Services other than reads (01, 21, 22) are skipped.
     */
    fun signals(text: String, year: Int?): List<CustomPid> {
        val root = json.parseToJsonElement(text).jsonObject
        val pids = mutableListOf<CustomPid>()
        var n = 0
        for (c in (root["commands"] as? JsonArray).orEmpty()) {
            val cmd = c as? JsonObject ?: continue
            // Extended addressing (BMW / MINI behind the 6F1 gateway, some Toyota modules): the address goes first in every frame.
            val eax = (cmd["eax"] as? JsonPrimitive)?.content?.uppercase().orEmpty()
            if (eax.isNotEmpty() && !Regex("[0-9A-F]{2}").matches(eax)) continue
            if (!filterMatches(cmd["filter"] as? JsonObject, year)) continue
            val hdr = (cmd["hdr"] as? JsonPrimitive)?.content?.uppercase() ?: continue
            val rax = (cmd["rax"] as? JsonPrimitive)?.content?.uppercase().orEmpty()
            val (service, id) = (cmd["cmd"] as? JsonObject)?.entries?.firstOrNull()
                ?.let { it.key to ((it.value as? JsonPrimitive)?.content?.uppercase() ?: "") } ?: continue
            if (service !in setOf("01", "21", "22") || !Regex("([0-9A-F]{2}){1,2}").matches(id)) continue
            val header = when (hdr.length) { 3 -> hdr; 4 -> "${hdr}F1"; else -> continue }
            if (eax.isNotEmpty() && hdr.length != 3) continue
            val receive = if (hdr.length == 3 && rax.length == 3) rax else ""
            for (sig in (cmd["signals"] as? JsonArray).orEmpty()) {
                val so = sig as? JsonObject ?: continue
                if ((so["hidden"] as? JsonPrimitive)?.booleanOrNull == true) continue
                val fmt = so["fmt"] as? JsonObject ?: continue
                fun d(k: String) = (fmt[k] as? JsonPrimitive)?.doubleOrNull
                val len = d("len")?.toInt()?.takeIf { it in 1..32 } ?: continue
                val bix = d("bix")?.toInt()?.takeIf { it >= 0 } ?: 0
                val unitName = (fmt["unit"] as? JsonPrimitive)?.content
                val map = (fmt["map"] as? JsonObject)?.entries?.associate { (k, v) ->
                    k to ((v as? JsonObject)?.get("description")?.let { (it as? JsonPrimitive)?.content } ?: (v as? JsonPrimitive)?.content ?: k)
                }
                val labels = map ?: when (unitName) { "offon", "onoff" -> onOff; "noyes", "yesno" -> noYes; else -> emptyMap() }
                val enumerated = map != null
                val min = d("min") ?: 0.0
                val max = d("max") ?: if (enumerated) ((1L shl len) - 1).toDouble() else continue
                val formula = if (enumerated) formula(bix, len, false, false, 1.0, 1.0, 0.0, null, null)
                    else formula(bix, len, (fmt["blsb"] as? JsonPrimitive)?.booleanOrNull == true, (fmt["sign"] as? JsonPrimitive)?.booleanOrNull == true,
                        d("mul") ?: 1.0, d("div")?.takeIf { it != 0.0 } ?: 1.0, d("add") ?: 0.0, min, max)
                val name = (so["name"] as? JsonPrimitive)?.content ?: (so["id"] as? JsonPrimitive)?.content ?: continue
                val path = (so["path"] as? JsonPrimitive)?.content.orEmpty()
                pids += CustomPid(
                    id = "obdb${n++}", name = name, shortName = name.take(14), request = service + id, equation = formula,
                    min = if (enumerated) 0.0 else min, max = max, unit = if (enumerated) "" else unit(unitName),
                    header = header, receive = receive, labels = labels, source = SOURCE + (if (path.isNotBlank()) " · $path" else ""),
                    extAddr = eax,
                )
            }
        }
        return pids
    }

    /** OBDb's category of a converted PID ("Engine", "Battery", ...). */
    fun group(p: CustomPid): String = p.source.substringAfter(" · ", "").substringBefore('.').ifBlank { "Other" }

    private val dpfWords = listOf("soot", "regen", "particulate", "dpf", "fap", "gpf", "opf")

    /** Particulate filter values (diesel DPF / FAP, petrol GPF / OPF): soot, regenerations, differential pressure ... */
    fun isDpf(p: CustomPid): Boolean = p.name.lowercase().let { n -> dpfWords.any { n.contains(it) } }

    /** Wraps chosen PIDs as a pack, with OBDb's attribution. */
    fun pack(entry: ObdbEntry, pids: List<CustomPid>) = PidPack(
        name = entry.title + " (OBDb)", description = ATTRIBUTION, author = "OBDb contributors",
        makes = listOf(makeTitle(entry.make)), models = listOfNotNull(entry.model.takeIf { it.isNotBlank() }?.replace('-', ' ')),
        pids = pids.take(PidPacks.MAX_PIDS),
    )

    fun toPack(entry: ObdbEntry, text: String, year: Int?): PidPack = pack(entry, signals(text, year))

    /** OBDb categories, translated in the app. */
    val GROUPS = listOf(
        "Engine", "Battery", "Transmission", "Movement", "Tires", "Fuel", "Trips", "Control", "Climate", "ECU", "DTCs", "Doors", "Lights",
        "Orientation", "Drivetrain", "Seatbelts", "Electrical", "Cruise control", "Stats", "Emissions", "Fluids", "Clocks", "GPS",
        "Suspension", "Details", "Brakes", "Windows", "Monitors", "Keys", "Other",
    )

    val texts = GROUPS
}
