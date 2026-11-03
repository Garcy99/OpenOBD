package com.openobd.core.specs

import com.openobd.core.obd.VinDecoder

/** What the car itself reports, read over OBD (each part may be missing). */
data class ObservedCar(
    val vin: String? = null,
    /** PID 01 51, SAE J1979 fuel type code. */
    val fuelType: Int? = null,
    /** PID 01 01: the engine computer runs compression-ignition (diesel) monitors. */
    val compressionIgnition: Boolean? = null,
)

enum class CrossLevel { MATCH, INFO, MISMATCH }

data class CrossFinding(val level: CrossLevel, val text: String, val args: List<String> = emptyList())

/**
 * Compares the saved model and specifications with independent sources: the VIN (maker, model year, check digit),
 * what the engine computer reports (fuel / ignition type, VIN) and the EU registration catalog. A lookup by licence
 * plate can return the wrong car and a hand-made entry can have typos; this tells the user before they rely on it.
 */
object CrossCheck {
    private const val DIESEL = "Diesel"
    private const val SPARK = "Petrol or gas (spark ignition)"
    private const val ELECTRIC = "Electric"

    /** Engine family from the J1979 fuel type code, null when not reported. */
    fun familyOfFuelCode(code: Int?): String? = when (code) {
        null, 0 -> null
        4, 19, 23 -> DIESEL
        8 -> ELECTRIC
        in 1..23 -> SPARK
        else -> null
    }

    fun familyOf(fuel: SpecFuel): String? = when {
        fuel == SpecFuel.OTHER -> null
        fuel.burnsDiesel -> DIESEL
        fuel.isElectricOnly -> ELECTRIC
        else -> SPARK
    }

    /** Brand id for a VIN maker such as "Opel / Vauxhall" or "Volkswagen SUV" (null when unknown). */
    fun vinBrandId(vin: String): String? {
        com.openobd.core.brand.Brands.detect(vin)?.let { if (it.id != "other") return it.id }
        val maker = VinDecoder.manufacturer(vin.trim().uppercase().take(3)) ?: return null
        val candidates = listOf(maker) + maker.split('/', '(', ')').map { it.trim() } + maker.split(' ').take(1)
        return candidates.filter { it.isNotBlank() }.firstNotNullOfOrNull { brandForMake(it)?.id }
    }

    fun check(
        specs: VehicleSpecs,
        savedVin: String?,
        observed: ObservedCar?,
        /** Versions of this make / model / year in the EEA catalog; null when not looked up. */
        catalog: List<EeaVersion>? = null,
        currentYear: Int = java.time.Year.now().value,
    ): List<CrossFinding> = buildList {
        val carVin = observed?.vin?.trim()?.uppercase()?.takeIf { VinDecoder.isValidFormat(it) }
        val stored = savedVin?.trim()?.uppercase()?.takeIf { VinDecoder.isValidFormat(it) }
        if (carVin != null && stored != null && carVin != stored) {
            add(CrossFinding(CrossLevel.MISMATCH, "The VIN read from the car (%1\$s) is not the one saved for this car (%2\$s). Is the right car selected in the garage?", listOf(carVin, stored)))
        }
        val vin = carVin ?: stored
        val specBrand = brandForMake(specs.make)
        if (vin != null) {
            val info = VinDecoder.decode(vin)
            val northAmerica = vin.first() in "12345"
            if (northAmerica && info.checkDigitValid == false) {
                add(CrossFinding(CrossLevel.MISMATCH, "The VIN check digit is wrong: the VIN may be misread or altered."))
            }
            val vinBrand = vinBrandId(vin)
            if (vinBrand != null && specBrand != null) {
                if (vinBrand == specBrand.id) add(CrossFinding(CrossLevel.MATCH, "The VIN maker matches the saved make (%1\$s).", listOf(specBrand.name)))
                else add(CrossFinding(CrossLevel.MISMATCH, "The VIN belongs to a %1\$s, but the saved model is a %2\$s.", listOf(info.manufacturer.orEmpty(), prettyName(specs.make))))
            }
            val vinYear = VinDecoder.modelYear(vin, currentYear)
            val year = specs.year
            if (vinYear != null && year != null) {
                when {
                    kotlin.math.abs(vinYear - year) <= 1 -> add(CrossFinding(CrossLevel.MATCH, "The VIN model year (%1\$s) matches the saved year.", listOf(vinYear.toString())))
                    // Many European makers leave the year position unused or fill it with their own codes.
                    northAmerica -> add(CrossFinding(CrossLevel.MISMATCH, "The VIN model year is %1\$s, but the saved year is %2\$s.", listOf(vinYear.toString(), year.toString())))
                    else -> add(CrossFinding(CrossLevel.INFO, "The VIN suggests model year %1\$s and the saved year is %2\$s. Not every European maker codes the year in the VIN: check the registration certificate.", listOf(vinYear.toString(), year.toString())))
                }
            }
        }

        val reported = familyOfFuelCode(observed?.fuelType)
            ?: observed?.compressionIgnition?.let { if (it) DIESEL else SPARK }
        val saved = familyOf(specs.fuel)
        if (reported != null && saved != null) {
            // An electric code from a hybrid's engine computer is not a contradiction.
            val hybridElectric = reported == ELECTRIC && (specs.fuel.name.contains("HYBRID") || specs.fuel.isPlugIn)
            if (reported == saved || hybridElectric) add(CrossFinding(CrossLevel.MATCH, "The engine computer confirms the fuel type (%1\$s).", listOf(specs.fuel.label)))
            else add(CrossFinding(CrossLevel.MISMATCH, "The engine computer reports a %1\$s engine, but the saved fuel is %2\$s.", listOf(reported, specs.fuel.label)))
        }

        if (catalog != null && !specs.source.startsWith("EEA")) {
            val sameFuel = catalog.filter { familyOf(it.fuel) == saved || saved == null }
            val match = sameFuel.filter { v ->
                (specs.engineCc == null || v.engineCc == null || kotlin.math.abs(v.engineCc - specs.engineCc) <= 60) &&
                    (specs.powerKw == null || v.powerKw == null || kotlin.math.abs(v.powerKw - specs.powerKw) <= 4)
            }
            when {
                catalog.isEmpty() -> add(CrossFinding(CrossLevel.INFO, "This make and model were not found in the EU registration catalog for that year, so the engine could not be compared."))
                match.isNotEmpty() -> add(CrossFinding(CrossLevel.MATCH, "The EU registration catalog lists this engine for this model (%1\$s cars registered).", listOf(match.sumOf { it.registrations }.toString())))
                else -> add(CrossFinding(CrossLevel.INFO, "No car with this fuel and engine (%1\$s) was registered as this model that year in the EU catalog. Check the engine data.",
                    listOf(listOf(specs.fuel.label, specs.engineLabel()).filter { it.isNotBlank() }.joinToString(", "))))
            }
        }
        if (isEmpty()) add(CrossFinding(CrossLevel.INFO, "Connect to the car or save its VIN to compare the saved data with what the car reports."))
    }

    /** Every user-visible text, for translation coverage. */
    val texts = listOf(
        "The VIN read from the car (%1\$s) is not the one saved for this car (%2\$s). Is the right car selected in the garage?",
        "The VIN check digit is wrong: the VIN may be misread or altered.",
        "The VIN maker matches the saved make (%1\$s).",
        "The VIN belongs to a %1\$s, but the saved model is a %2\$s.",
        "The VIN model year (%1\$s) matches the saved year.",
        "The VIN model year is %1\$s, but the saved year is %2\$s.",
        "The VIN suggests model year %1\$s and the saved year is %2\$s. Not every European maker codes the year in the VIN: check the registration certificate.",
        "The engine computer confirms the fuel type (%1\$s).",
        "The engine computer reports a %1\$s engine, but the saved fuel is %2\$s.",
        "This make and model were not found in the EU registration catalog for that year, so the engine could not be compared.",
        "The EU registration catalog lists this engine for this model (%1\$s cars registered).",
        "No car with this fuel and engine (%1\$s) was registered as this model that year in the EU catalog. Check the engine data.",
        "Connect to the car or save its VIN to compare the saved data with what the car reports.",
        DIESEL, SPARK, ELECTRIC,
    )
}
