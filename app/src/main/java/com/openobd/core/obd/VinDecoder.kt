package com.openobd.core.obd

data class VinInfo(
    val vin: String,
    val wmi: String,
    val manufacturer: String?,
    val region: String,
    val country: String?,
    val modelYear: Int?,
    val checkDigitValid: Boolean?,
    val plantCode: Char?,
    val serial: String,
)

object VinDecoder {
    private val translit: Map<Char, Int> = buildMap {
        "ABCDEFGH".forEachIndexed { i, c -> put(c, i + 1) }
        "JKLMN".forEachIndexed { i, c -> put(c, i + 1) }
        put('P', 7); put('R', 9)
        "STUVWXYZ".forEachIndexed { i, c -> put(c, i + 2) }
        for (d in 0..9) put('0' + d, d)
    }
    private val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
    private const val yearCodes = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun isValidFormat(vin: String) = vin.length == 17 && vin.all { it.isLetterOrDigit() && it !in "IOQ" }

    fun checkDigitOk(vin: String): Boolean? {
        if (!isValidFormat(vin)) return null
        val sum = vin.mapIndexed { i, c -> (translit[c] ?: return null) * weights[i] }.sum()
        val r = sum % 11
        val expected = if (r == 10) 'X' else '0' + r
        return vin[8] == expected
    }

    /**
     * Model year from position 10. The 30-year cycle is ambiguous; position 7 being a letter indicates
     * 2010+ for North American vehicles, otherwise we pick the most recent year not in the future.
     */
    fun modelYear(vin: String, currentYear: Int = java.time.Year.now().value): Int? {
        if (vin.length < 10) return null
        val idx = yearCodes.indexOf(vin[9])
        if (idx < 0) return null
        val y1 = 1980 + idx
        val y2 = y1 + 30
        val northAmerica = vin[0] in "12345"
        return when {
            northAmerica && vin[6].isLetter() -> y2
            northAmerica && vin[6].isDigit() -> y1
            y2 <= currentYear + 1 -> y2
            else -> y1
        }
    }

    fun decode(raw: String): VinInfo {
        val vin = raw.trim().uppercase()
        val wmi = vin.take(3)
        return VinInfo(
            vin = vin,
            wmi = wmi,
            manufacturer = manufacturer(wmi),
            region = region(vin.firstOrNull() ?: ' '),
            country = country(vin.take(2)),
            modelYear = modelYear(vin),
            checkDigitValid = if (vin.firstOrNull() in listOf('1', '4', '5')) checkDigitOk(vin) else checkDigitOk(vin)?.takeIf { it },
            plantCode = vin.getOrNull(10),
            serial = vin.drop(11),
        )
    }

    private fun region(c: Char) = when (c) {
        in 'A'..'H' -> "Africa"
        in 'J'..'R' -> "Asia"
        in 'S'..'Z' -> "Europe"
        in '1'..'5' -> "North America"
        '6', '7' -> "Oceania"
        '8', '9', '0' -> "South America"
        else -> "Unknown"
    }

    private fun country(p: String): String? {
        if (p.length < 2) return null
        val a = p[0]; val b = p[1]
        return when (a) {
            '1', '4', '5' -> "United States"
            '2' -> "Canada"
            '3' -> if (b in 'A'..'W') "Mexico" else "Costa Rica / Cayman"
            '6' -> "Australia"
            '7' -> "New Zealand"
            '8' -> when (b) { in 'A'..'E' -> "Argentina"; in 'F'..'K' -> "Chile"; in 'L'..'R' -> "Ecuador"; in 'S'..'W' -> "Peru"; else -> "Venezuela" }
            '9' -> when (b) { in 'A'..'E', in '3'..'9' -> "Brazil"; in 'F'..'K' -> "Colombia"; in 'L'..'R' -> "Paraguay"; in 'S'..'W' -> "Uruguay"; else -> null }
            'J' -> "Japan"
            'K' -> when (b) { in 'L'..'R' -> "South Korea"; in 'A'..'E' -> "Sri Lanka"; in 'F'..'K' -> "Israel"; else -> "Kazakhstan" }
            'L' -> "China"
            'M' -> when (b) { in 'A'..'E' -> "India"; in 'F'..'K' -> "Indonesia"; in 'L'..'R' -> "Thailand"; in 'S'..'W' -> "Myanmar"; else -> null }
            'N' -> when (b) { in 'L'..'R' -> "Turkey"; in 'A'..'E' -> "Iran"; in 'F'..'K' -> "Pakistan"; else -> null }
            'P' -> when (b) { in 'A'..'E' -> "Philippines"; in 'F'..'K' -> "Singapore"; in 'L'..'R' -> "Malaysia"; else -> null }
            'R' -> when (b) { in 'A'..'E' -> "United Arab Emirates"; in 'F'..'K' -> "Taiwan"; in 'L'..'R' -> "Vietnam"; in 'S'..'W' -> "Saudi Arabia"; else -> null }
            'S' -> when (b) { in 'A'..'M' -> "United Kingdom"; in 'N'..'T' -> "Germany (East)"; in 'U'..'Z' -> "Poland"; else -> "Latvia" }
            'T' -> when (b) { in 'A'..'H' -> "Switzerland"; in 'J'..'P' -> "Czech Republic"; in 'R'..'V' -> "Hungary"; else -> "Portugal" }
            'U' -> when (b) { in 'H'..'M' -> "Denmark"; in 'N'..'T' -> "Ireland"; in 'U'..'Z' -> "Romania"; in '5'..'7' -> "Slovakia"; else -> null }
            'V' -> when (b) { in 'A'..'E' -> "Austria"; in 'F'..'R' -> "France"; in 'S'..'W' -> "Spain"; in 'X'..'Z', in '1'..'2' -> "Serbia"; in '3'..'5' -> "Croatia"; else -> "Estonia" }
            'W' -> "Germany"
            'X' -> when (b) { in 'A'..'E' -> "Bulgaria"; in 'F'..'K' -> "Greece"; in 'L'..'R' -> "Netherlands"; in 'S'..'W' -> "Russia"; else -> "Russia / Ukraine" }
            'Y' -> when (b) { in 'A'..'E' -> "Belgium"; in 'F'..'K' -> "Finland"; in 'S'..'W' -> "Sweden"; in 'X'..'Z', in '1'..'2' -> "Norway"; else -> null }
            'Z' -> when (b) { in 'A'..'R' -> "Italy"; in 'X'..'Z', in '1'..'2' -> "Slovenia"; else -> null }
            'A' -> "South Africa"
            else -> null
        }
    }

    fun manufacturer(wmi: String): String? = wmis[wmi] ?: wmis[wmi.take(2)]

    // WMI (or 2-char prefix) -> manufacturer. Covers the large majority of passenger vehicles.
    private val wmis: Map<String, String> = mapOf(
        "1F" to "Ford", "1FA" to "Ford", "1FB" to "Ford", "1FC" to "Ford", "1FD" to "Ford", "1FM" to "Ford", "1FT" to "Ford",
        "1G" to "General Motors", "1G1" to "Chevrolet", "1GC" to "Chevrolet Truck", "1GT" to "GMC Truck", "1G4" to "Buick",
        "1G6" to "Cadillac", "1GM" to "Pontiac", "1GY" to "Cadillac", "1G8" to "Saturn", "1GK" to "GMC",
        "1C" to "Chrysler / Stellantis", "1C3" to "Chrysler", "1C4" to "Chrysler / Jeep / Dodge", "1C6" to "Ram", "1B3" to "Dodge",
        "1D7" to "Dodge", "1J4" to "Jeep", "1J8" to "Jeep", "1HG" to "Honda (USA)", "1HD" to "Harley-Davidson", "1N4" to "Nissan (USA)",
        "1N6" to "Nissan Truck (USA)", "1VW" to "Volkswagen (USA)", "1YV" to "Mazda (USA)", "1ZV" to "Ford / Mazda (AutoAlliance)",
        "1L" to "Lincoln", "1LN" to "Lincoln", "1ME" to "Mercury", "1NX" to "Toyota (NUMMI)", "19U" to "Acura (USA)", "19X" to "Honda (USA)",
        "2C" to "Chrysler (Canada)", "2FA" to "Ford (Canada)", "2FM" to "Ford (Canada)", "2FT" to "Ford (Canada)", "2G" to "General Motors (Canada)",
        "2HG" to "Honda (Canada)", "2HK" to "Honda (Canada)", "2HM" to "Hyundai (Canada)", "2T" to "Toyota (Canada)", "2T1" to "Toyota (Canada)",
        "2T3" to "Toyota (Canada)", "2A4" to "Chrysler (Canada)", "2D" to "Dodge (Canada)",
        "3FA" to "Ford (Mexico)", "3FE" to "Ford (Mexico)", "3G" to "General Motors (Mexico)", "3N" to "Nissan (Mexico)", "3VW" to "Volkswagen (Mexico)",
        "3HG" to "Honda (Mexico)", "3C" to "Chrysler (Mexico)", "3D" to "Dodge (Mexico)", "3MZ" to "Mazda (Mexico)", "3TM" to "Toyota (Mexico)",
        "4F" to "Mazda (USA)", "4S" to "Subaru-Isuzu (USA)", "4S3" to "Subaru (USA)", "4S4" to "Subaru (USA)", "4T" to "Toyota (USA)",
        "4T1" to "Toyota (USA)", "4T3" to "Toyota (USA)", "4US" to "BMW (USA)", "4JG" to "Mercedes-Benz (USA)", "4A3" to "Mitsubishi (USA)",
        "5FN" to "Honda (USA)", "5J6" to "Honda (USA)", "5J8" to "Acura (USA)", "5L" to "Lincoln", "5N1" to "Nissan (USA)", "5NP" to "Hyundai (USA)",
        "5NM" to "Hyundai (USA)", "5T" to "Toyota (USA)", "5TD" to "Toyota (USA)", "5TF" to "Toyota Truck (USA)", "5UX" to "BMW (USA)",
        "5UM" to "BMW M (USA)", "5YJ" to "Tesla", "7SA" to "Tesla", "5XX" to "Kia (USA)", "5XY" to "Kia (USA)", "5YF" to "Toyota (USA)",
        "6F" to "Ford (Australia)", "6G" to "General Motors Holden", "6T1" to "Toyota (Australia)", "6MM" to "Mitsubishi (Australia)",
        "7A" to "New Zealand", "8AP" to "Fiat (Argentina)", "8AF" to "Ford (Argentina)", "8AG" to "Chevrolet (Argentina)", "8AW" to "Volkswagen (Argentina)",
        "8AC" to "Mercedes-Benz (Argentina)", "8AD" to "Peugeot (Argentina)", "8A1" to "Renault (Argentina)", "8AJ" to "Toyota (Argentina)",
        "93H" to "Honda (Brazil)", "93R" to "Toyota (Brazil)", "93Y" to "Renault (Brazil)", "94D" to "Nissan (Brazil)", "9BD" to "Fiat (Brazil)",
        "9BF" to "Ford (Brazil)", "9BG" to "Chevrolet (Brazil)", "9BW" to "Volkswagen (Brazil)", "9BM" to "Mercedes-Benz (Brazil)",
        "936" to "Peugeot (Brazil)", "935" to "Citroën (Brazil)", "9FB" to "Renault (Colombia)", "9GA" to "Chevrolet (Colombia)",
        "JA" to "Isuzu", "JA3" to "Mitsubishi", "JA4" to "Mitsubishi", "JF" to "Subaru", "JF1" to "Subaru", "JF2" to "Subaru",
        "JH" to "Honda", "JHM" to "Honda", "JHL" to "Honda", "JH4" to "Acura", "JK" to "Kawasaki", "JM" to "Mazda", "JM1" to "Mazda",
        "JM3" to "Mazda", "JN" to "Nissan", "JN1" to "Nissan", "JN8" to "Nissan", "JNK" to "Infiniti", "JNR" to "Infiniti",
        "JS" to "Suzuki", "JT" to "Toyota", "JTD" to "Toyota", "JTE" to "Toyota", "JTH" to "Lexus", "JTJ" to "Lexus", "JTM" to "Toyota",
        "JTN" to "Toyota", "JY" to "Yamaha", "JMB" to "Mitsubishi", "JMZ" to "Mazda (Europe)", "JSA" to "Suzuki", "JYA" to "Yamaha",
        "KL" to "Daewoo / GM Korea", "KLA" to "Daewoo / Chevrolet", "KM" to "Hyundai", "KMH" to "Hyundai", "KM8" to "Hyundai",
        "KN" to "Kia", "KNA" to "Kia", "KND" to "Kia", "KNM" to "Renault Samsung", "KP" to "SsangYong", "KPT" to "SsangYong",
        "LB" to "China", "LFV" to "FAW-Volkswagen", "LSV" to "SAIC Volkswagen", "LSG" to "SAIC General Motors", "LVS" to "Changan Ford",
        "LVV" to "Chery", "LGX" to "BYD", "LC0" to "BYD", "LRW" to "Tesla (China)", "LYV" to "Volvo (China)", "LBV" to "BMW Brilliance",
        "LE4" to "Beijing Benz", "LHG" to "GAC Honda", "LTV" to "FAW Toyota", "LPS" to "Polestar", "L6T" to "Geely", "LJD" to "Dongfeng Yueda Kia",
        "MA1" to "Mahindra", "MA3" to "Suzuki (India) / Maruti", "MAL" to "Hyundai (India)", "MAT" to "Tata", "MAJ" to "Ford (India)",
        "MHF" to "Toyota (Indonesia)", "MMB" to "Mitsubishi (Thailand)", "MMC" to "Mitsubishi (Thailand)", "MNB" to "Ford (Thailand)",
        "MNT" to "Nissan (Thailand)", "MR0" to "Toyota (Thailand)", "MRH" to "Honda (Thailand)", "MPA" to "Isuzu (Thailand)",
        "NLA" to "Honda (Turkey)", "NM0" to "Ford Otosan (Turkey)", "NMT" to "Toyota (Turkey)", "NM4" to "Tofaş (Turkey)", "NLH" to "Hyundai Assan",
        "SAJ" to "Jaguar", "SAL" to "Land Rover", "SAD" to "Jaguar (SUV)", "SAR" to "Rover", "SAT" to "Triumph", "SB1" to "Toyota (UK)",
        "SCA" to "Rolls-Royce", "SCB" to "Bentley", "SCC" to "Lotus", "SCF" to "Aston Martin", "SFD" to "Alexander Dennis", "SHH" to "Honda (UK)",
        "SHS" to "Honda (UK)", "SJN" to "Nissan (UK)", "SMT" to "Triumph", "SUF" to "Fiat (Poland)", "SUP" to "FSO / Daewoo (Poland)",
        "SU9" to "Solaris", "SWV" to "TA-NO",
        "TMB" to "Škoda", "TMA" to "Hyundai (Czech)", "TMK" to "Karosa", "TRU" to "Audi (Hungary)", "TSM" to "Suzuki (Hungary)",
        "TSE" to "Ikarus", "TCC" to "Micro Compact Car (smart)", "U5Y" to "Kia (Slovakia)", "U6Y" to "Kia (Slovakia)", "UU1" to "Dacia",
        "VA0" to "ÖAF", "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "VF8" to "Matra", "VF6" to "Renault Trucks",
        "VF9" to "Bugatti", "VNK" to "Toyota (France)", "VR1" to "DS Automobiles", "VR3" to "Peugeot", "VR7" to "Citroën",
        "VS5" to "Renault (Spain)", "VS6" to "Ford (Spain)", "VS7" to "Citroën (Spain)", "VSS" to "SEAT / Cupra", "VSK" to "Nissan (Spain)",
        "VSE" to "Suzuki (Spain)", "VWV" to "Volkswagen (Spain)", "VV9" to "Tauro Sport", "VX1" to "Zastava / Yugo",
        "W0L" to "Opel / Vauxhall", "W0V" to "Opel / Vauxhall", "WAU" to "Audi", "WA1" to "Audi SUV", "WAP" to "Alpina", "WBA" to "BMW",
        "WBS" to "BMW M", "WBX" to "BMW SUV", "WBY" to "BMW i", "WDB" to "Mercedes-Benz", "WDC" to "Mercedes-Benz SUV",
        "WDD" to "Mercedes-Benz", "WDF" to "Mercedes-Benz Van", "W1K" to "Mercedes-Benz", "W1N" to "Mercedes-Benz SUV", "W1V" to "Mercedes-Benz Van",
        "WEB" to "EvoBus", "WF0" to "Ford (Germany)", "WF1" to "Ford (Germany)", "WMA" to "MAN", "WME" to "smart", "WMW" to "MINI",
        "WP0" to "Porsche", "WP1" to "Porsche SUV", "WUA" to "Audi Sport", "WVG" to "Volkswagen SUV", "WVW" to "Volkswagen",
        "WV1" to "Volkswagen Commercial", "WV2" to "Volkswagen Bus/Van", "WV3" to "Volkswagen Trucks", "W09" to "Ruf / Specialist",
        "XL9" to "Spyker", "XLR" to "DAF", "XTA" to "Lada / AvtoVAZ", "XTT" to "UAZ", "XW8" to "Volkswagen (Russia)", "X7L" to "Renault (Russia)",
        "YK1" to "Saab (Finland)", "YS2" to "Scania", "YS3" to "Saab", "YS4" to "Scania (bus)", "YV1" to "Volvo", "YV4" to "Volvo SUV",
        "YV2" to "Volvo Trucks", "YV3" to "Volvo Buses", "YSM" to "Polestar",
        "ZAM" to "Maserati", "ZAP" to "Piaggio", "ZAR" to "Alfa Romeo", "ZCF" to "Iveco", "ZDM" to "Ducati", "ZFA" to "Fiat", "ZFC" to "Fiat Commercial",
        "ZFF" to "Ferrari", "ZHW" to "Lamborghini", "ZLA" to "Lancia", "ZAC" to "Jeep / Fiat (Italy)", "ZFB" to "Fiat",
        "AAV" to "Volkswagen (South Africa)", "AFA" to "Ford (South Africa)", "AHT" to "Toyota (South Africa)", "ADM" to "General Motors (South Africa)",
    )
}
