package com.openobd.core.specs

import com.openobd.core.brand.Brand
import com.openobd.core.brand.Brands
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

enum class SpecFuel(val label: String) {
    PETROL("Petrol"), DIESEL("Diesel"), LPG("LPG"), CNG("Natural gas (CNG)"), E85("Ethanol E85"),
    HYBRID_PETROL("Hybrid (petrol)"), HYBRID_DIESEL("Hybrid (diesel)"), PHEV_PETROL("Plug-in hybrid (petrol)"),
    PHEV_DIESEL("Plug-in hybrid (diesel)"), ELECTRIC("Electric"), HYDROGEN("Hydrogen"), OTHER("Other");

    val burnsDiesel: Boolean get() = this == DIESEL || this == HYBRID_DIESEL || this == PHEV_DIESEL
    val isPlugIn: Boolean get() = this == PHEV_PETROL || this == PHEV_DIESEL
    val isElectricOnly: Boolean get() = this == ELECTRIC || this == HYDROGEN
}

/** Everything known about a vehicle model. Filled by a lookup and editable by the user afterwards. */
@Serializable
data class VehicleSpecs(
    val make: String = "",
    val model: String = "",
    val version: String = "",
    val year: Int? = null,
    val fuel: SpecFuel = SpecFuel.PETROL,
    val engineCc: Int? = null,
    val powerKw: Double? = null,
    val cylinders: Int? = null,
    val transmission: String = "",
    val bodyType: String = "",
    val colour: String = "",
    val massKg: Int? = null,
    /** Official CO2 in g/km and the test cycle it comes from (WLTP, NEDC, EPA). */
    val co2Gkm: Double? = null,
    val co2Cycle: String = "",
    /** Official combined consumption in L/100 km (or kg/100 km for CNG). */
    val consumptionL100: Double? = null,
    /** True when [consumptionL100] was derived from CO2 rather than published. */
    val consumptionEstimated: Boolean = false,
    val cityL100: Double? = null,
    val highwayL100: Double? = null,
    val electricKwh100: Double? = null,
    val electricRangeKm: Int? = null,
    val tankL: Double? = null,
    val plate: String = "",
    val country: String = "",
    val firstRegistration: Long? = null,
    val inspectionDue: Long? = null,
    val euroNorm: String = "",
    val source: String = "",
    val lookupDate: Long = 0,
) {
    val powerCv: Double? get() = powerKw?.let { it * 1.35962 }
    val powerHp: Double? get() = powerKw?.let { it * 1.34102 }
    val displacementL: Double? get() = engineCc?.let { it / 1000.0 }
    val title: String get() = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ") { prettyName(it) }

    /** Short engine description, e.g. "1.5 L · 110 kW (150 CV)". */
    fun engineLabel(): String = listOfNotNull(
        engineCc?.takeIf { it > 0 }?.let { "%.1f L".format(it / 1000.0) },
        powerKw?.let { "%.0f kW (%.0f CV)".format(it, it * 1.35962) },
    ).joinToString(" · ")
}

/** Title-cases registry names ("SEAT LEON" -> "Seat Leon") while keeping short acronyms (BMW, DS, GTI). */
fun prettyName(raw: String): String = raw.trim().split(Regex("\\s+")).joinToString(" ") { w ->
    if (w.length <= 3 && w.all { it.isUpperCase() || it.isDigit() }) w
    else w.lowercase().split('-').joinToString("-") { p -> p.replaceFirstChar { it.uppercase() } }
}

object Consumption {
    /**
     * Fuel use from tailpipe CO2 (g/km), using typical carbon content per litre:
     * petrol ~2,320 g/L, diesel ~2,640 g/L, LPG ~1,630 g/L, E85 ~1,610 g/L, CNG ~2,740 g/kg (result in kg/100 km).
     */
    fun fromCo2(fuel: SpecFuel, co2: Double?): Double? {
        if (co2 == null || co2 <= 0) return null
        val factor = when (fuel) {
            SpecFuel.PETROL, SpecFuel.HYBRID_PETROL, SpecFuel.PHEV_PETROL -> 23.2
            SpecFuel.DIESEL, SpecFuel.HYBRID_DIESEL, SpecFuel.PHEV_DIESEL -> 26.4
            SpecFuel.LPG -> 16.3
            SpecFuel.E85 -> 16.1
            SpecFuel.CNG -> 27.4
            else -> return null
        }
        return co2 / factor
    }

    fun mpgUsToL100(mpg: Double?): Double? = mpg?.takeIf { it > 0 }?.let { 235.215 / it }
}

enum class DgtLabel(val label: String) { CERO("0 emisiones"), ECO("ECO"), C("C"), B("B"), NONE("Sin etiqueta") }

/** Spanish rules (Real Decreto 920/2017 for ITV; DGT criteria for environmental labels). */
object Spain {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun addYears(t: Long, years: Int): Long = Calendar.getInstance(utc).apply { timeInMillis = t; add(Calendar.YEAR, years) }.timeInMillis

    /**
     * Next ITV for a private passenger car: first at 4 years, then every 2 years until 10 years old,
     * then every year.
     */
    fun nextItv(firstRegistration: Long, now: Long): Long {
        var due = addYears(firstRegistration, 4)
        while (due < now) {
            val ageYears = yearsBetween(firstRegistration, due)
            due = addYears(due, if (ageYears < 10) 2 else 1)
        }
        return due
    }

    private fun yearsBetween(a: Long, b: Long): Int {
        val ca = Calendar.getInstance(utc).apply { timeInMillis = a }
        val cb = Calendar.getInstance(utc).apply { timeInMillis = b }
        var y = cb.get(Calendar.YEAR) - ca.get(Calendar.YEAR)
        if (cb.get(Calendar.DAY_OF_YEAR) < ca.get(Calendar.DAY_OF_YEAR)) y--
        return y
    }

    private fun euroLevel(norm: String): Int? = Regex("(?i)euro\\s*([0-9]|I{1,3}V?|VI?)").find(norm)?.groupValues?.get(1)?.let { n ->
        n.toIntOrNull() ?: mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6)[n.uppercase()]
    }

    /**
     * Estimated DGT environmental label for a passenger car. The official one depends on the Euro norm in the
     * technical sheet; without it the DGT's registration-year criteria are used.
     */
    fun dgtLabel(fuel: SpecFuel, registrationYear: Int?, euroNorm: String = "", electricRangeKm: Int? = null): DgtLabel {
        val euro = euroLevel(euroNorm)
        fun petrolLike(): DgtLabel = when {
            euro != null -> if (euro >= 4) DgtLabel.C else if (euro == 3) DgtLabel.B else DgtLabel.NONE
            registrationYear == null -> DgtLabel.C
            registrationYear >= 2006 -> DgtLabel.C
            registrationYear >= 2001 -> DgtLabel.B
            else -> DgtLabel.NONE
        }
        fun dieselLike(): DgtLabel = when {
            euro != null -> if (euro >= 6) DgtLabel.C else if (euro >= 4) DgtLabel.B else DgtLabel.NONE
            registrationYear == null -> DgtLabel.C
            registrationYear >= 2014 -> DgtLabel.C
            registrationYear >= 2006 -> DgtLabel.B
            else -> DgtLabel.NONE
        }
        return when (fuel) {
            SpecFuel.ELECTRIC, SpecFuel.HYDROGEN -> DgtLabel.CERO
            SpecFuel.PHEV_PETROL, SpecFuel.PHEV_DIESEL -> if ((electricRangeKm ?: 0) >= 40) DgtLabel.CERO else DgtLabel.ECO
            SpecFuel.HYBRID_PETROL, SpecFuel.CNG, SpecFuel.LPG -> if (petrolLike() == DgtLabel.C) DgtLabel.ECO else petrolLike()
            SpecFuel.HYBRID_DIESEL -> if (dieselLike() == DgtLabel.C) DgtLabel.ECO else dieselLike()
            SpecFuel.DIESEL -> dieselLike()
            else -> petrolLike()
        }
    }
}

/** Matches a registry make ("MERCEDES-BENZ", "CITROEN", "SKODA") to the app's brand catalog. */
fun brandForMake(make: String): Brand? {
    fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").uppercase().replace(Regex("[^A-Z0-9]"), "")
    val m = norm(make)
    if (m.isEmpty()) return null
    val aliases = mapOf("VW" to "volkswagen", "MERCEDES" to "mercedes", "MERCEDESBENZ" to "mercedes", "LANDROVER" to "landrover", "VAUXHALL" to "opel", "OPEL" to "opel")
    aliases[m]?.let { return Brands.byId[it] }
    return Brands.all.firstOrNull { b -> b.id != "other" && (norm(b.name) == m || norm(b.name).split("VAUXHALL").first() == m) }
}

/** Where a country's data can come from. */
enum class SpecSource { EEA_CATALOG, RDW_PLATE, DVLA_PLATE, EPA_CATALOG, NHTSA_VIN }

object SpecCountries {
    /** EU member states plus Iceland and Norway report every new registration to the EEA. */
    val eea = listOf("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU",
        "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "NO")

    /** The EEA dataset uses ISO codes except Greece. */
    fun eeaCode(country: String): String = if (country == "GR") "EL" else country

    fun sources(country: String): List<SpecSource> = buildList {
        if (country == "NL") add(SpecSource.RDW_PLATE)
        if (country == "GB") add(SpecSource.DVLA_PLATE)
        if (country == "US" || country == "CA") { add(SpecSource.NHTSA_VIN); add(SpecSource.EPA_CATALOG) }
        if (country in eea || country == "GB") add(SpecSource.EEA_CATALOG)
        if (isEmpty()) { add(SpecSource.EEA_CATALOG); add(SpecSource.EPA_CATALOG); add(SpecSource.NHTSA_VIN) }
    }

    /** Countries offered in the picker (others can still use the EU-wide or US catalogs). */
    val all: List<String> = (eea + listOf("GB", "CH", "US", "CA", "MX", "AR", "CL", "CO", "TR", "AU")).distinct()
}
