package com.openobd.core.specs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Minimal HTTP abstraction so providers can be tested offline. */
interface Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): String
}

class HttpException(val code: Int, message: String) : Exception(message)

class SpecsException(message: String) : Exception(message)

private val json = Json { ignoreUnknownKeys = true }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
private fun JsonObject.s(key: String) = this[key].str()
private fun JsonObject.d(key: String) = s(key)?.replace(',', '.')?.toDoubleOrNull()
private fun JsonObject.i(key: String) = d(key)?.toInt()
private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

private fun parseDate(v: String?, pattern: String): Long? = v?.let {
    runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(it)?.time }.getOrNull()
}

private fun yearOf(t: Long?): Int? = t?.let { java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }.get(java.util.Calendar.YEAR) }

// ------------------------------------------------------------------ EEA: every new car registered in the EU

data class CatalogEntry(val name: String, val count: Long)

data class EeaVersion(
    val fuel: SpecFuel, val engineCc: Int?, val powerKw: Double?, val massKg: Int?, val co2Wltp: Double?, val co2Nedc: Double?,
    val consumption: Double?, val electricWhKm: Double?, val electricRangeKm: Int?, val registrations: Long,
)

/**
 * European Environment Agency CO2 monitoring data (cars registered 2010+ in each EU country, Iceland, Norway).
 * Queried through the public DISCODATA SQL service. Data © EEA, free re-use with attribution.
 */
class EeaCatalog(private val http: Http) {
    companion object {
        const val URL = "https://discodata.eea.europa.eu/sql"
        const val TABLE = "[CO2Emission].[latest].[co2cars]"
        const val ATTRIBUTION = "Data: European Environment Agency (EEA), CO2 emissions from new passenger cars"
        /** The EEA's service refuses the app's questions (its firewall, since late 2026) or is down. */
        const val UNAVAILABLE = "The European registration catalogue (EEA) is not answering the app right now. Decode the VIN or enter the data by hand below."

        fun sqlString(v: String) = "'" + v.replace("'", "''") + "'"

        fun fuelOf(ft: String?, fm: String?): SpecFuel {
            val f = ft.orEmpty().uppercase()
            val plugIn = fm.equals("P", true)
            return when {
                f == "ELECTRIC" -> SpecFuel.ELECTRIC
                f.contains("HYDROGEN") -> SpecFuel.HYDROGEN
                f.contains("DIESEL") && f.contains("ELECTRIC") -> if (plugIn) SpecFuel.PHEV_DIESEL else SpecFuel.HYBRID_DIESEL
                f.contains("PETROL") && f.contains("ELECTRIC") -> if (plugIn) SpecFuel.PHEV_PETROL else SpecFuel.HYBRID_PETROL
                f.startsWith("DIESEL") -> SpecFuel.DIESEL
                f.startsWith("LPG") -> SpecFuel.LPG
                f.startsWith("NG") -> SpecFuel.CNG
                f.startsWith("E85") -> SpecFuel.E85
                f.startsWith("PETROL") -> SpecFuel.PETROL
                else -> SpecFuel.OTHER
            }
        }
    }

    private suspend fun query(sql: String, rows: Int = 300): List<JsonObject> {
        // GET: the service's firewall now refuses every POST.
        val text = try {
            http.get("$URL?query=" + enc(sql) + "&p=1&nrOfHits=$rows")
        } catch (e: HttpException) {
            if (e.code == 403 || e.code == 429 || e.code >= 500) throw SpecsException(UNAVAILABLE) else throw e
        }
        val root = json.parseToJsonElement(text).jsonObject
        root["errors"]?.let { e -> throw SpecsException("EEA: " + ((e as? JsonArray)?.firstOrNull()?.jsonObject?.s("error") ?: e.toString())) }
        return root["results"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    private fun where(country: String?, year: Int?, make: String? = null, model: String? = null) = buildList {
        country?.let { add("MS = " + sqlString(SpecCountries.eeaCode(it))) }
        year?.let { add("Year = $it") }
        make?.let { add("UPPER(Mk) = " + sqlString(it.uppercase())) }
        model?.let { add("UPPER(Cn) = " + sqlString(it.uppercase())) }
    }.joinToString(" AND ").ifEmpty { "1 = 1" }

    // The service's firewall rejects queries with ORDER BY (it takes them for an attack), so rows are fetched whole
    // and sorted here.

    suspend fun years(country: String?): List<Int> =
        query("SELECT DISTINCT Year FROM $TABLE WHERE ${where(country, null)}").mapNotNull { it.i("Year") }.sortedDescending()

    suspend fun makes(country: String?, year: Int): List<CatalogEntry> =
        query("SELECT UPPER(Mk) AS mk, SUM(R) AS n FROM $TABLE WHERE ${where(country, year)} AND Mk IS NOT NULL GROUP BY UPPER(Mk)", rows = 2000)
            .mapNotNull { r -> r.s("mk")?.let { CatalogEntry(it, r.d("n")?.toLong() ?: 0) } }.sortedByDescending { it.count }.take(300)

    suspend fun models(country: String?, year: Int, make: String): List<CatalogEntry> =
        query("SELECT UPPER(Cn) AS cn, SUM(R) AS n FROM $TABLE WHERE ${where(country, year, make)} AND Cn IS NOT NULL GROUP BY UPPER(Cn)", rows = 2000)
            .mapNotNull { r -> r.s("cn")?.let { CatalogEntry(it, r.d("n")?.toLong() ?: 0) } }.sortedByDescending { it.count }.take(300)

    suspend fun versions(country: String?, year: Int, make: String, model: String): List<EeaVersion> =
        query(
            "SELECT UPPER(Ft) AS ft, Fm AS fm, [Ec (cm3)] AS ec, [Ep (KW)] AS kw, AVG([M (kg)]) AS mass, " +
                "AVG([Ewltp (g/km)]) AS wltp, AVG([Enedc (g/km)]) AS nedc, AVG(Fc) AS fc, AVG([Z (Wh/km)]) AS z, AVG(Zr) AS zr, SUM(R) AS n " +
                "FROM $TABLE WHERE ${where(country, year, make, model)} GROUP BY UPPER(Ft), Fm, [Ec (cm3)], [Ep (KW)]",
            rows = 2000,
        ).map { r ->
            EeaVersion(fuelOf(r.s("ft"), r.s("fm")), r.i("ec"), r.d("kw"), r.i("mass"), r.d("wltp")?.takeIf { it > 0 },
                r.d("nedc")?.takeIf { it > 0 }, r.d("fc")?.takeIf { it in 0.5..40.0 }, r.d("z")?.takeIf { it > 0 }, r.i("zr")?.takeIf { it > 0 }, r.d("n")?.toLong() ?: 0)
        }.sortedByDescending { it.registrations }.take(80)

    fun toSpecs(country: String?, year: Int, make: String, model: String, v: EeaVersion, now: Long): VehicleSpecs {
        val co2 = v.co2Wltp ?: v.co2Nedc
        val estimated = v.consumption == null
        return VehicleSpecs(
            make = make, model = model, year = year, fuel = v.fuel, engineCc = v.engineCc, powerKw = v.powerKw, massKg = v.massKg,
            co2Gkm = co2, co2Cycle = if (v.co2Wltp != null) "WLTP" else if (v.co2Nedc != null) "NEDC" else "",
            consumptionL100 = v.consumption ?: Consumption.fromCo2(v.fuel, co2), consumptionEstimated = estimated && co2 != null,
            electricKwh100 = v.electricWhKm?.let { it / 10.0 }, electricRangeKm = v.electricRangeKm,
            country = country ?: "", source = "EEA" + (country?.let { " ($it, $year)" } ?: " ($year)"), lookupDate = now,
        )
    }
}

// ------------------------------------------------------------------ RDW (Netherlands): licence plate lookup

class RdwClient(private val http: Http) {
    companion object {
        const val CARS = "https://opendata.rdw.nl/resource/m9d7-ebf2.json"
        const val FUEL = "https://opendata.rdw.nl/resource/8ys7-d773.json"
        fun normalize(plate: String) = plate.uppercase().filter { it.isLetterOrDigit() }
    }

    suspend fun lookup(plate: String, now: Long): VehicleSpecs {
        val p = normalize(plate)
        if (p.length !in 4..8) throw SpecsException("Enter a valid Dutch licence plate")
        val car = json.parseToJsonElement(http.get("$CARS?kenteken=$p")).jsonArray.firstOrNull()?.jsonObject
            ?: throw SpecsException("No vehicle found for this licence plate")
        val fuels = runCatching { json.parseToJsonElement(http.get("$FUEL?kenteken=$p")).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
        return parse(car, fuels, now)
    }

    fun parse(car: JsonObject, fuels: List<JsonObject>, now: Long): VehicleSpecs {
        val kinds = fuels.mapNotNull { it.s("brandstof_omschrijving")?.uppercase() }
        val plugIn = fuels.any { it.s("klasse_hybride_elektrisch_voertuig")?.startsWith("OVC") == true }
        val burner = fuels.firstOrNull { it.s("brandstof_omschrijving")?.uppercase() != "ELEKTRICITEIT" } ?: fuels.firstOrNull()
        val hasElectric = "ELEKTRICITEIT" in kinds
        val base = when (burner?.s("brandstof_omschrijving")?.uppercase()) {
            "DIESEL" -> SpecFuel.DIESEL
            "LPG" -> SpecFuel.LPG
            "CNG" -> SpecFuel.CNG
            "ALCOHOL" -> SpecFuel.E85
            "WATERSTOF" -> SpecFuel.HYDROGEN
            "ELEKTRICITEIT" -> SpecFuel.ELECTRIC
            null -> SpecFuel.OTHER
            else -> SpecFuel.PETROL
        }
        val fuel = when {
            base == SpecFuel.ELECTRIC -> SpecFuel.ELECTRIC
            hasElectric && base == SpecFuel.DIESEL -> if (plugIn) SpecFuel.PHEV_DIESEL else SpecFuel.HYBRID_DIESEL
            hasElectric -> if (plugIn) SpecFuel.PHEV_PETROL else SpecFuel.HYBRID_PETROL
            else -> base
        }
        val wltpCo2 = burner?.d("emissie_co2_gecombineerd_wltp")?.takeIf { it > 0 }
        val nedcCo2 = burner?.d("co2_uitstoot_gecombineerd")?.takeIf { it > 0 }
        val published = (burner?.d("brandstof_verbruik_gecombineerd_wltp") ?: burner?.d("brandstofverbruik_gecombineerd"))?.takeIf { it in 1.0..40.0 }
        val co2 = wltpCo2 ?: nedcCo2
        val electric = fuels.firstOrNull { it.s("brandstof_omschrijving")?.uppercase() == "ELEKTRICITEIT" }
        val firstReg = parseDate(car.s("datum_eerste_toelating"), "yyyyMMdd")
        return VehicleSpecs(
            make = car.s("merk").orEmpty(), model = car.s("handelsbenaming").orEmpty(), year = yearOf(firstReg), fuel = fuel,
            engineCc = car.i("cilinderinhoud")?.takeIf { it > 0 },
            powerKw = (burner?.d("nettomaximumvermogen") ?: electric?.d("netto_max_vermogen_elektrisch") ?: electric?.d("nettomaximumvermogen"))?.takeIf { it > 0 },
            cylinders = car.i("aantal_cilinders")?.takeIf { it > 0 }, bodyType = car.s("inrichting").orEmpty(), colour = car.s("eerste_kleur").orEmpty(),
            massKg = car.i("massa_rijklaar"), co2Gkm = co2, co2Cycle = if (wltpCo2 != null) "WLTP" else if (nedcCo2 != null) "NEDC" else "",
            consumptionL100 = published ?: Consumption.fromCo2(fuel, co2), consumptionEstimated = published == null && co2 != null,
            electricKwh100 = electric?.d("elektrisch_verbruik_enkel_elektrisch_wltp")?.takeIf { it > 0 }?.let { it / 10.0 },
            electricRangeKm = electric?.i("actie_radius_enkel_elektrisch_wltp")?.takeIf { it > 0 },
            plate = car.s("kenteken").orEmpty(), country = "NL", firstRegistration = firstReg,
            inspectionDue = parseDate(car.s("vervaldatum_apk"), "yyyyMMdd"), euroNorm = burner?.s("uitlaatemissieniveau").orEmpty(),
            source = "RDW", lookupDate = now,
        )
    }
}

// ------------------------------------------------------------------ DVLA (United Kingdom): licence plate lookup (API key required)

class DvlaClient(private val http: Http, private val apiKey: String) {
    companion object { const val URL = "https://driver-vehicle-licensing.api.gov.uk/vehicle-enquiry/v1/vehicles" }

    suspend fun lookup(plate: String, now: Long): VehicleSpecs {
        if (apiKey.isBlank()) throw SpecsException("A DVLA Vehicle Enquiry API key is needed for UK plates")
        val body = buildJsonObject { put("registrationNumber", plate.uppercase().filter { it.isLetterOrDigit() }) }.toString()
        val text = try {
            http.post(URL, body, "application/json", mapOf("x-api-key" to apiKey))
        } catch (e: HttpException) {
            throw SpecsException(if (e.code == 404) "No vehicle found for this licence plate" else if (e.code == 403) "The DVLA rejected the API key" else "DVLA error ${e.code}")
        }
        return parse(json.parseToJsonElement(text).jsonObject, now)
    }

    fun parse(o: JsonObject, now: Long): VehicleSpecs {
        val fuel = when (o.s("fuelType")?.uppercase()) {
            "DIESEL" -> SpecFuel.DIESEL
            "ELECTRICITY" -> SpecFuel.ELECTRIC
            "HYBRID ELECTRIC" -> SpecFuel.HYBRID_PETROL
            "GAS BI-FUEL", "GAS" -> SpecFuel.LPG
            else -> SpecFuel.PETROL
        }
        val co2 = o.d("co2Emissions")?.takeIf { it > 0 }
        val firstReg = parseDate(o.s("monthOfFirstRegistration"), "yyyy-MM")
        return VehicleSpecs(
            make = o.s("make").orEmpty(), year = o.i("yearOfManufacture") ?: yearOf(firstReg), fuel = fuel,
            engineCc = o.i("engineCapacity")?.takeIf { it > 0 }, colour = o.s("colour").orEmpty(),
            co2Gkm = co2, co2Cycle = if (co2 != null) "UK" else "", consumptionL100 = Consumption.fromCo2(fuel, co2), consumptionEstimated = co2 != null,
            plate = o.s("registrationNumber").orEmpty(), country = "GB", firstRegistration = firstReg,
            inspectionDue = parseDate(o.s("motExpiryDate"), "yyyy-MM-dd"), euroNorm = o.s("euroStatus").orEmpty(), source = "DVLA", lookupDate = now,
        )
    }
}

// ------------------------------------------------------------------ EPA fueleconomy.gov (US / Canada models)

data class EpaOption(val id: String, val label: String)

class EpaCatalog(private val http: Http) {
    companion object { const val BASE = "https://www.fueleconomy.gov/ws/rest/vehicle" }

    private val accept = mapOf("Accept" to "application/json")

    private suspend fun menu(path: String): List<Pair<String, String>> {
        val text = http.get("$BASE/$path", accept)
        if (text.isBlank() || text == "null") return emptyList()
        val item = json.parseToJsonElement(text).jsonObject["menuItem"] ?: return emptyList()
        val list = if (item is JsonArray) item.map { it.jsonObject } else listOf(item.jsonObject) // single results are not wrapped
        return list.mapNotNull { o -> o.s("value")?.let { v -> v to (o.s("text") ?: v) } }
    }

    suspend fun years(): List<Int> = menu("menu/year").mapNotNull { it.first.toIntOrNull() }
    suspend fun makes(year: Int): List<String> = menu("menu/make?year=$year").map { it.first }
    suspend fun models(year: Int, make: String): List<String> = menu("menu/model?year=$year&make=${enc(make)}").map { it.first }
    suspend fun options(year: Int, make: String, model: String): List<EpaOption> =
        menu("menu/options?year=$year&make=${enc(make)}&model=${enc(model)}").map { EpaOption(it.first, it.second) }

    suspend fun vehicle(id: String, now: Long): VehicleSpecs = parse(json.parseToJsonElement(http.get("$BASE/$id", accept)).jsonObject, now)

    fun parse(o: JsonObject, now: Long): VehicleSpecs {
        val atv = o.s("atvType").orEmpty()
        val f1 = o.s("fuelType1").orEmpty()
        val diesel = f1.contains("Diesel", true)
        val fuel = when {
            atv.equals("EV", true) || f1 == "Electricity" -> SpecFuel.ELECTRIC
            atv.contains("Plug-in", true) -> if (diesel) SpecFuel.PHEV_DIESEL else SpecFuel.PHEV_PETROL
            atv.equals("Hybrid", true) -> if (diesel) SpecFuel.HYBRID_DIESEL else SpecFuel.HYBRID_PETROL
            atv.equals("CNG", true) || f1.contains("Natural Gas", true) -> SpecFuel.CNG
            diesel -> SpecFuel.DIESEL
            f1.contains("Hydrogen", true) -> SpecFuel.HYDROGEN
            else -> SpecFuel.PETROL
        }
        val mile = 1.609344
        val combE = o.d("combE")?.takeIf { it > 0 }
        return VehicleSpecs(
            make = o.s("make").orEmpty(), model = o.s("model").orEmpty(), year = o.i("year"), fuel = fuel,
            version = listOfNotNull(o.s("trany"), o.s("VClass")).joinToString(" · "),
            engineCc = o.d("displ")?.takeIf { it > 0 }?.let { (it * 1000).toInt() }, cylinders = o.i("cylinders"), transmission = o.s("trany").orEmpty(),
            co2Gkm = o.d("co2TailpipeGpm")?.takeIf { it > 0 }?.let { it / mile }, co2Cycle = "EPA",
            consumptionL100 = if (fuel == SpecFuel.ELECTRIC) null else Consumption.mpgUsToL100(o.d("comb08")),
            cityL100 = if (fuel == SpecFuel.ELECTRIC) null else Consumption.mpgUsToL100(o.d("city08")),
            highwayL100 = if (fuel == SpecFuel.ELECTRIC) null else Consumption.mpgUsToL100(o.d("highway08")),
            electricKwh100 = combE?.let { it / mile }, electricRangeKm = o.d("range")?.takeIf { it > 0 }?.let { (it * mile).toInt() },
            country = "US", source = "EPA", lookupDate = now,
        )
    }
}

// ------------------------------------------------------------------ NHTSA vPIC: VIN decoding (best for North-American vehicles)

class NhtsaVin(private val http: Http) {
    suspend fun decode(vin: String, now: Long): VehicleSpecs {
        val text = http.get("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/${enc(vin.trim().uppercase())}?format=json")
        val r = json.parseToJsonElement(text).jsonObject["Results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw SpecsException("The VIN could not be decoded")
        return parse(r, now)
    }

    fun parse(r: JsonObject, now: Long): VehicleSpecs {
        val make = r.s("Make") ?: throw SpecsException("This VIN isn't in the NHTSA database (common for cars sold only outside North America)")
        val primary = r.s("FuelTypePrimary").orEmpty()
        val level = r.s("ElectrificationLevel").orEmpty()
        val diesel = primary.contains("Diesel", true)
        val fuel = when {
            level.contains("BEV", true) || primary.equals("Electric", true) -> SpecFuel.ELECTRIC
            level.contains("PHEV", true) -> if (diesel) SpecFuel.PHEV_DIESEL else SpecFuel.PHEV_PETROL
            level.contains("HEV", true) -> if (diesel) SpecFuel.HYBRID_DIESEL else SpecFuel.HYBRID_PETROL
            diesel -> SpecFuel.DIESEL
            primary.contains("Natural Gas", true) -> SpecFuel.CNG
            primary.contains("LPG", true) || primary.contains("propane", true) -> SpecFuel.LPG
            primary.contains("Flexible", true) -> SpecFuel.E85
            primary.contains("Hydrogen", true) -> SpecFuel.HYDROGEN
            else -> SpecFuel.PETROL
        }
        val kw = r.d("EngineKW") ?: r.d("EngineHP")?.let { it / 1.34102 }
        return VehicleSpecs(
            make = make, model = r.s("Model").orEmpty(), version = listOfNotNull(r.s("Trim"), r.s("Series")).joinToString(" "),
            year = r.i("ModelYear"), fuel = fuel, engineCc = r.d("DisplacementCC")?.let { Math.round(it).toInt() }, powerKw = kw,
            cylinders = r.i("EngineCylinders"), transmission = listOfNotNull(r.s("TransmissionStyle"), r.s("TransmissionSpeeds")?.let { "$it-speed" }).joinToString(" "),
            bodyType = r.s("BodyClass").orEmpty(), source = "NHTSA", lookupDate = now,
        )
    }
}
