package com.openobd.core.brand

import kotlinx.serialization.Serializable

enum class ModuleCategory(val label: String) {
    POWERTRAIN("Powertrain"), CHASSIS("Chassis & safety"), BODY("Body & comfort"), INFOTAINMENT("Infotainment"),
    ELECTRIC("Hybrid / EV"), NETWORK("Network"), OTHER("Other");
}

/**
 * A diagnosable control module reached by physical CAN addressing.
 * 11-bit: [tx] request ID (e.g. 0x7B0), [rx] response ID (e.g. 0x7B8).
 * 29-bit (ISO 15765-4 normal fixed addressing): [tx] / [rx] are the target / tester bytes are derived from [address].
 */
@Serializable
data class EcuModule(
    val name: String,
    val shortName: String,
    val tx: Int,
    val rx: Int,
    val category: ModuleCategory = ModuleCategory.OTHER,
    val is29Bit: Boolean = false,
    /** Modules on a secondary bus (e.g. Ford MS-CAN) need an adapter with a bus switch. */
    val secondaryBus: Boolean = false,
    /**
     * ISO 15765 extended addressing: the first data byte of every frame is the target address (BMW / MINI send
     * everything to 0x6F1 with the module address first; replies come from 0x600 + address with 0xF1 first).
     */
    val extAddr: Int? = null,
) {
    val id: String get() = (if (secondaryBus) "ms:" else "") + (if (is29Bit) "29:" else "") + "%03X".format(tx) + (extAddr?.let { ":%02X".format(it) } ?: "")
    val addressLabel: String get() = when {
        is29Bit -> "18DA%02XF1".format(tx)
        extAddr != null -> "%03X/%02X → %03X".format(tx, extAddr, rx)
        else -> "%03X → %03X".format(tx, rx)
    }
}

/**
 * A second, slower CAN bus on OBD pins 3/11 that "HS/MS-CAN switch" adapters can reach.
 * The ELM327 talks to it as protocol B (User1 CAN) configured with ATPB [pbOptions] [pbDivisor]:
 * options bit 4 multiplies the 500 kbps base by 8/7, so Opel's 95.24 kbps is 500 * 8/7 / 6.
 */
data class SecondaryBus(val label: String, val kbps: String, val pbOptions: Int, val pbDivisor: Int, val modulesHint: String) {
    val atpb: String get() = "ATPB %02X %02X".format(pbOptions, pbDivisor)
}

/** Brands that share diagnostic architecture (addresses, conventions, manufacturer code meanings). */
class BrandFamily(
    val id: String,
    val label: String,
    val modules: List<EcuModule>,
    val notes: String,
    /** Medium-speed bus reachable with a switch adapter, if the brand has one. */
    val secondaryBus: SecondaryBus? = null,
    /** Address ranges probed by "Discover modules": request IDs and the receive filter for their replies. */
    val discoveryRanges: List<Pair<IntRange, String>> = listOf(0x700..0x7F7 to "7XX"),
    /** Manufacturer values every car of the family offers (oil pressure, ...), shown with the car's own PIDs. */
    val builtinPids: List<CustomPid> = emptyList(),
)

private const val OBDB_SRC = "OBDb (CC BY-SA 4.0)"

/** Engine oil temperature and pressure of VW group engine modules (Golf, Polo, Passat, Tiguan, Leon, A3 ...). */
val VAG_OIL_PIDS = listOf(
    CustomPid("vag_eot", "Engine oil temperature (VW group)", "Oil temp", "2211BE", "(A*256+B)/10-273.1", -40.0, 160.0, "°C", "7E0", source = OBDB_SRC),
    CustomPid("vag_eop", "Engine oil pressure (VW group)", "Oil press", "22381D", "Int16(A,B)/1000", 0.0, 7.0, "bar", "7E0", source = OBDB_SRC),
)

/** Engine oil temperature and pressure of BMW / MINI engine electronics (DME, address 12 behind the gateway). */
val BMW_OIL_PIDS = listOf(
    CustomPid("bmw_eot", "Engine oil temperature (BMW)", "Oil temp", "224408", "(A*256+B)/10-273.15", -40.0, 160.0, "°C", "6F1", receive = "612", source = OBDB_SRC, extAddr = "12"),
    CustomPid("bmw_eop", "Engine oil pressure (BMW)", "Oil press", "22586F", "(A*256+B)/1000", 0.0, 7.0, "bar", "6F1", receive = "612", source = OBDB_SRC, extAddr = "12"),
)

/** Engine oil temperature and pressure of Renault / Dacia engine modules (Clio, Captur, Megane, Duster ...). */
val RENAULT_OIL_PIDS = listOf(
    CustomPid("ren_eot", "Engine oil temperature (Renault)", "Oil temp", "222007", "(A*256+B)/10-273", -40.0, 160.0, "°C", "7E0", source = OBDB_SRC),
    CustomPid("ren_eop", "Engine oil pressure (Renault)", "Oil press", "2225B9", "(A*256+B)/1000", 0.0, 7.0, "bar", "7E0", source = OBDB_SRC),
)

/** Engine oil temperature and pressure of Peugeot / Citroën / DS / Opel (Stellantis) engine modules (208, 308, 3008 ...). */
val PSA_OIL_PIDS = listOf(
    CustomPid("psa_eot", "Engine oil temperature (Peugeot / Citroën)", "Oil temp", "22D47D", "Int16(A,B)/10-273.14", -40.0, 160.0, "°C", "6A8", receive = "688", source = OBDB_SRC),
    CustomPid("psa_eop", "Engine oil pressure (Peugeot / Citroën)", "Oil press", "22D429", "(A*256+B)/1000", 0.0, 7.0, "bar", "6A8", receive = "688", source = OBDB_SRC),
)

/**
 * Engine oil temperature and pressure from GM engine control modules (Global A: Opel / Vauxhall Astra J, Insignia,
 * Chevrolet Cruze ...), read by identifier ($22) as in the OBDb community data. Engines without an oil pressure
 * sensor do not answer that one.
 */
val GM_OIL_PIDS = listOf(
    CustomPid("gm_eot", "Engine oil temperature (GM)", "Oil temp", "22005C", "A-40", -40.0, 160.0, "°C", "7E0", source = "GM ECM, OBDb (CC BY-SA 4.0)"),
    CustomPid("gm_eop", "Engine oil pressure (GM)", "Oil press", "222345", "A*0.04", 0.0, 7.0, "bar", "7E0", source = "GM ECM, OBDb (CC BY-SA 4.0)"),
)

data class Brand(val id: String, val name: String, val family: BrandFamily, val wmis: List<String>)

private fun m(name: String, short: String, tx: Int, rx: Int, cat: ModuleCategory, secondary: Boolean = false) =
    EcuModule(name, short, tx, rx, cat, secondaryBus = secondary)

/** A BMW / MINI module behind the gateway: requests on 0x6F1 with [addr] first, replies on 0x600 + [addr]. */
private fun bmw(name: String, short: String, addr: Int, cat: ModuleCategory) =
    EcuModule(name, short, 0x6F1, 0x600 + addr, cat, extAddr = addr)

private fun bmwModules() = listOf(
    bmw("Engine (DME / DDE)", "DME", 0x12, ModuleCategory.POWERTRAIN),
    bmw("Transmission (EGS)", "EGS", 0x18, ModuleCategory.POWERTRAIN),
    bmw("Stability control (DSC)", "DSC", 0x29, ModuleCategory.CHASSIS),
    bmw("Airbag (ACSM)", "ACSM", 0x01, ModuleCategory.CHASSIS),
    bmw("Car access / front electronics (CAS / FEM)", "CAS", 0x40, ModuleCategory.BODY),
    bmw("Instrument cluster (KOMBI)", "KOMBI", 0x60, ModuleCategory.BODY),
    bmw("Head unit", "HU", 0x63, ModuleCategory.INFOTAINMENT),
    bmw("Footwell / lights module (FRM)", "FRM", 0x72, ModuleCategory.BODY),
    bmw("Climate (IHKA)", "IHKA", 0x78, ModuleCategory.BODY),
    bmw("Gateway (ZGW)", "ZGW", 0x10, ModuleCategory.NETWORK),
)

object Families {
    /** Ford / Mazda MS-CAN: 125 kbps, ISO 15765 formatting. */
    val FORD_MS = SecondaryBus("MS-CAN", "125", 0xE1, 0x04, "climate, doors, SYNC/infotainment and some body modules")
    /** Opel / Vauxhall (GM Europe, ~2004-2017) MS-CAN on pins 3/11: 95.24 kbps. */
    val OPEL_MS = SecondaryBus("MS-CAN", "95.2", 0xF1, 0x06, "radio / infotainment, display, climate control and phone module")

    private val P = ModuleCategory.POWERTRAIN; private val C = ModuleCategory.CHASSIS; private val B = ModuleCategory.BODY
    private val I = ModuleCategory.INFOTAINMENT; private val E = ModuleCategory.ELECTRIC; private val N = ModuleCategory.NETWORK

    private val obdBase = listOf(
        m("Engine control module", "ECM", 0x7E0, 0x7E8, P),
        m("Transmission control module", "TCM", 0x7E1, 0x7E9, P),
    )

    val GENERIC = BrandFamily(
        "generic", "Generic OBD-II", obdBase + m("Hybrid / auxiliary powertrain module", "AUX", 0x7E2, 0x7EA, E),
        "Uses the standard OBD-II addresses. Run \"Discover modules\" to find the other control modules on your car's diagnostic CAN bus.",
    )

    val FORD = BrandFamily(
        "ford", "Ford / Lincoln", listOf(
            m("Powertrain control module", "PCM", 0x7E0, 0x7E8, P),
            m("Transmission control module", "TCM", 0x7E1, 0x7E9, P),
            m("Anti-lock brake system", "ABS", 0x760, 0x768, C),
            m("Restraint control module (airbags)", "RCM", 0x737, 0x73F, C),
            m("Occupant classification sensor", "OCSM", 0x765, 0x76D, C),
            m("Power steering control module", "PSCM", 0x730, 0x738, C),
            m("Steering column control module", "SCCM", 0x724, 0x72C, C),
            m("Instrument panel cluster", "IPC", 0x720, 0x728, B),
            m("Body control module", "BCM", 0x726, 0x72E, B),
            m("Gateway module", "GWM", 0x716, 0x71E, N),
            m("Parking aid module", "PAM", 0x736, 0x73E, C),
            m("Image processing module (camera)", "IPMA", 0x706, 0x70E, C),
            m("Climate control (HVAC)", "HVAC", 0x733, 0x73B, B, true),
            m("Driver door module", "DDM", 0x740, 0x748, B, true),
            m("Passenger door module", "PDM", 0x741, 0x749, B, true),
            m("Accessory protocol interface (SYNC)", "APIM", 0x7D0, 0x7D8, I, true),
            m("Audio control module", "ACM", 0x727, 0x72F, I, true),
            m("GPS module", "GPSM", 0x701, 0x709, I, true),
        ),
        "Ford uses UDS on its high-speed CAN bus, reachable by any CAN adapter. Modules marked \"MS-CAN\" sit on the medium-speed bus " +
            "and only answer through adapters with an HS/MS-CAN switch (OBDLink EX/MX+, vLinker FS/MC+).",
        secondaryBus = FORD_MS,
    )

    val MAZDA = BrandFamily(
        "mazda", "Mazda", listOf(
            m("Powertrain control module", "PCM", 0x7E0, 0x7E8, P),
            m("Transmission control module", "TCM", 0x7E1, 0x7E9, P),
            m("Dynamic stability control / ABS", "DSC", 0x760, 0x768, C),
            m("Airbag (SRS)", "RCM", 0x737, 0x73F, C),
            m("Electric power steering", "EPS", 0x730, 0x738, C),
            m("Instrument cluster", "IC", 0x720, 0x728, B),
            m("Body control module", "BCM", 0x726, 0x72E, B),
        ),
        "Mazda shares Ford-style addressing on most CAN models from 2008 onwards. Use Discover modules if some do not respond.",
        secondaryBus = FORD_MS,
    )

    val VAG = BrandFamily(
        "vag", "Volkswagen group", listOf(
            m("01 Engine electronics", "01", 0x7E0, 0x7E8, P),
            m("02 Transmission electronics", "02", 0x7E1, 0x7E9, P),
            m("03 Brake electronics (ABS/ESC)", "03", 0x713, 0x77D, C),
            m("15 Airbag", "15", 0x715, 0x77F, C),
            m("44 Steering assist", "44", 0x712, 0x77C, C),
            m("16 Steering column electronics", "16", 0x70C, 0x776, C),
            m("17 Dash board (instruments)", "17", 0x714, 0x77E, B),
            m("09 Central electrics (BCM)", "09", 0x70E, 0x778, B),
            m("08 Air conditioning", "08", 0x746, 0x7B0, B),
            m("10 Park/steer assist", "10", 0x70A, 0x774, C),
            m("13 Adaptive cruise control", "13", 0x757, 0x7C1, C),
            m("A5 Front camera / driver assist", "A5", 0x74F, 0x7B9, C),
            m("42 Door electronics, driver", "42", 0x74A, 0x7B4, B),
            m("52 Door electronics, passenger", "52", 0x74B, 0x7B5, B),
            m("5F Information electronics", "5F", 0x773, 0x7DD, I),
            m("19 CAN gateway", "19", 0x710, 0x77A, N),
        ),
        "Addresses are for UDS platforms (MQB/MLB/MEB, roughly 2012+). Older PQ-platform cars use VW TP2.0 / KWP1281, which ELM327 adapters can't speak; " +
            "on those only the engine and generic codes are available.",
        builtinPids = VAG_OIL_PIDS,
    )

    val HMG = BrandFamily(
        "hmg", "Hyundai / Kia / Genesis", listOf(
            m("Engine control unit", "ECU", 0x7E0, 0x7E8, P),
            m("Transmission control unit", "TCU", 0x7E1, 0x7E9, P),
            m("ABS / ESC", "ESC", 0x7D1, 0x7D9, C),
            m("Airbag (SRS)", "SRS", 0x7D2, 0x7DA, C),
            m("Motor-driven power steering", "MDPS", 0x7D4, 0x7DC, C),
            m("Instrument cluster", "CLU", 0x7C6, 0x7CE, B),
            m("Body control (IGPM)", "BCM", 0x770, 0x778, B),
            m("Tire pressure monitoring", "TPMS", 0x7A0, 0x7A8, C),
            m("Climate control", "A/C", 0x7B3, 0x7BB, B),
            m("Vehicle control unit (EV/hybrid)", "VCU", 0x7E2, 0x7EA, E),
            m("Motor control unit", "MCU", 0x7E3, 0x7EB, E),
            m("Battery management system", "BMS", 0x7E4, 0x7EC, E),
            m("On-board charger", "OBC", 0x7E5, 0x7ED, E),
        ),
        "Hyundai, Kia and Genesis share addressing. EV/hybrid modules (BMS, MCU, OBC) answer only on electrified models. " +
            "Import a community PID pack (Torque CSV) for battery health, cell voltages and tire pressures.",
    )

    val TOYOTA = BrandFamily(
        "toyota", "Toyota / Lexus", listOf(
            m("Engine control module", "ECM", 0x7E0, 0x7E8, P),
            m("Transmission control module", "ECT", 0x7E1, 0x7E9, P),
            m("Hybrid control (HV ECU)", "HV", 0x7E2, 0x7EA, E),
            m("ABS / VSC / brake", "ABS", 0x7B0, 0x7B8, C),
            m("Airbag (SRS)", "SRS", 0x780, 0x788, C),
            m("Electric power steering", "EPS", 0x7A1, 0x7A9, C),
            m("Combination meter", "METER", 0x7C0, 0x7C8, B),
            m("Air conditioning", "A/C", 0x7C4, 0x7CC, B),
        ),
        "Body ECUs on many Toyota/Lexus models sit behind the main body gateway with extended addressing and are not reachable with ELM327.",
    )

    val NISSAN = BrandFamily(
        "nissan", "Nissan / Infiniti", listOf(
            m("Engine control module", "ECM", 0x7E0, 0x7E8, P),
            m("Transmission (CVT/AT)", "TCM", 0x7E1, 0x7E9, P),
            m("ABS / VDC", "ABS", 0x740, 0x760, C),
            m("Airbag", "AIRBAG", 0x752, 0x772, C),
            m("Electric power steering", "EPS", 0x742, 0x762, C),
            m("Body control module", "BCM", 0x745, 0x765, B),
            m("Combination meter", "METER", 0x743, 0x763, B),
            m("IPDM E/R (power distribution)", "IPDM", 0x74D, 0x76D, B),
            m("Vehicle control module (Leaf)", "VCM", 0x797, 0x79A, E),
            m("Li-ion battery controller (Leaf)", "LBC", 0x79B, 0x7BB, E),
            m("On-board charger (Leaf)", "OBC", 0x792, 0x793, E),
        ),
        "Nissan modules mostly speak KWP2000 on CAN; OpenOBD falls back to it automatically.",
    )

    val RENAULT = BrandFamily(
        "renault", "Renault / Dacia", listOf(
            m("Injection", "INJ", 0x7E0, 0x7E8, P),
            m("Automatic gearbox", "BVA", 0x7E1, 0x7E9, P),
            m("ABS / ESP", "ABS", 0x740, 0x760, C),
            m("Airbag", "AIRBAG", 0x752, 0x772, C),
            m("Electric power steering", "DAE", 0x742, 0x762, C),
            m("UCH / body control", "UCH", 0x745, 0x765, B),
            m("Instrument panel", "TDB", 0x743, 0x763, B),
            m("Battery controller (EV)", "LBC", 0x79B, 0x7BB, E),
        ),
        "Renault-Nissan alliance addressing. Older Renaults with KWP over K-line are not covered by module scanning.",
        builtinPids = RENAULT_OIL_PIDS,
    )

    val GM = BrandFamily(
        "gm", "General Motors", listOf(
            m("Engine control module", "ECM", 0x7E0, 0x7E8, P),
            m("Transmission control module", "TCM", 0x7E2, 0x7EA, P),
            m("Electronic brake control (ABS)", "EBCM", 0x243, 0x643, C),
            m("Airbag (SDM)", "SDM", 0x247, 0x647, C),
            m("Power steering", "EPS", 0x242, 0x642, C),
            m("Body control module", "BCM", 0x241, 0x641, B),
            m("Instrument panel cluster", "IPC", 0x24C, 0x64C, B),
            m("HVAC", "HVAC", 0x251, 0x651, B),
            m("Radio", "RADIO", 0x244, 0x644, I),
        ),
        "GMLAN physical addressing. Modules that do not speak UDS are read with the GMLAN service (\$A9) and cleared with \$04. " +
            "Global B vehicles (2020+) may block access through the gateway.",
        discoveryRanges = listOf(0x240..0x25F to "6XX", 0x700..0x7F7 to "7XX"),
        builtinPids = GM_OIL_PIDS,
    )

    /** Opel / Vauxhall on GM's Global A architecture (Astra H/J/K, Insignia A, Corsa D/E, Zafira B/C, Meriva B, Mokka). */
    val OPEL = BrandFamily(
        "gm-europe", "Opel / Vauxhall (GM)", listOf(
            m("Engine control module", "ECM", 0x7E0, 0x7E8, P),
            m("Transmission control module", "TCM", 0x7E2, 0x7EA, P),
            m("Electronic brake control (ABS / ESP)", "EBCM", 0x243, 0x643, C),
            m("Airbag (SDM)", "SDM", 0x247, 0x647, C),
            m("Power steering", "EPS", 0x242, 0x642, C),
            m("Body control module", "BCM", 0x241, 0x641, B),
            m("Instrument panel cluster", "IPC", 0x24C, 0x64C, B),
            m("Radio / infotainment", "RADIO", 0x244, 0x644, I, true),
            m("Climate control (HVAC)", "HVAC", 0x251, 0x651, B, true),
        ),
        "GMLAN addressing (requests 0x24x, replies 0x64x). Many modules of this era speak GMLAN rather than UDS: OpenOBD reads them " +
            "with the GMLAN service (\$A9) and clears them with \$04. Infotainment and climate sit on MS-CAN (95.2 kbps, pins 3/11), " +
            "reachable with an HS/MS-CAN switch adapter; some body modules use single-wire CAN (pin 1), which ELM327 adapters cannot reach.",
        secondaryBus = OPEL_MS,
        discoveryRanges = listOf(0x240..0x25F to "6XX", 0x700..0x7F7 to "7XX"),
        builtinPids = GM_OIL_PIDS,
    )

    val STELLANTIS = BrandFamily("stellantis", "Stellantis / FCA / PSA", obdBase,
        "Stellantis brands use a secure gateway (2018+) that blocks clearing codes on most modules without an authorised unlock. " +
            "Use Discover modules to see which modules answer on your car.", builtinPids = PSA_OIL_PIDS)
    val BMW = BrandFamily("bmw", "BMW / MINI", obdBase + bmwModules(),
        "BMW / MINI modules are reached through the central gateway with extended addressing (requests to 6F1 with the module address first). " +
            "OpenOBD frames these by hand, so they work with ordinary ELM327 adapters too; not every module exists on every model. " +
            "Use an adapter set to 500 kbps CAN (D-CAN, 2008+).", builtinPids = BMW_OIL_PIDS)
    val MERCEDES = BrandFamily("mercedes", "Mercedes-Benz / smart", obdBase,
        "Module addresses differ between model series. Use Discover modules to find the modules on your car.")
    val HONDA = BrandFamily("honda", "Honda / Acura", obdBase,
        "Most Honda body and chassis modules use Honda-proprietary protocols. Engine, transmission, manufacturer codes and custom PIDs work.")
    val SUBARU = BrandFamily("subaru", "Subaru", obdBase,
        "Subaru modules use the proprietary SSM protocol; engine/transmission data and manufacturer codes work.")
    val VOLVO = BrandFamily("volvo", "Volvo / Polestar", obdBase, "Use Discover modules to find the modules that answer on your car.")
    val OTHER_ASIA = BrandFamily("asia", "Mitsubishi / Suzuki / others", obdBase, GENERIC.notes)

    val all = listOf(GENERIC, FORD, MAZDA, VAG, HMG, TOYOTA, NISSAN, RENAULT, GM, OPEL, STELLANTIS, BMW, MERCEDES, HONDA, SUBARU, VOLVO, OTHER_ASIA)
}

object Brands {
    private fun b(id: String, name: String, f: BrandFamily, vararg wmi: String) = Brand(id, name, f, wmi.toList())

    val all: List<Brand> = listOf(
        b("acura", "Acura", Families.HONDA, "JH4", "19U", "5J8"),
        b("alfa", "Alfa Romeo", Families.STELLANTIS, "ZAR"),
        b("audi", "Audi", Families.VAG, "WAU", "WA1", "WUA", "TRU"),
        b("bmw", "BMW", Families.BMW, "WBA", "WBS", "WBX", "WBY", "4US", "5UX", "5UM", "5YM", "LBV"),
        b("buick", "Buick", Families.GM, "1G4", "2G4", "LRB"),
        b("cadillac", "Cadillac", Families.GM, "1G6", "1GY"),
        b("chevrolet", "Chevrolet", Families.GM, "1G1", "1GC", "1GN", "2G1", "3G1", "3GN", "KL1", "KL7", "9BG", "8AG", "1GB"),
        b("chrysler", "Chrysler", Families.STELLANTIS, "1C3", "2C3", "2A4", "2C4"),
        b("citroen", "Citroën", Families.STELLANTIS, "VF7", "VR7", "935"),
        b("cupra", "Cupra", Families.VAG),
        b("dacia", "Dacia", Families.RENAULT, "UU1"),
        b("dodge", "Dodge", Families.STELLANTIS, "1B3", "1D7", "2B3", "2D3", "3D7", "2C7"),
        b("ds", "DS", Families.STELLANTIS, "VR1"),
        b("fiat", "Fiat", Families.STELLANTIS, "ZFA", "ZFB", "ZFC", "9BD", "NM4", "3C3"),
        b("ford", "Ford", Families.FORD, "1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "1FV", "2FA", "2FM", "2FT", "3FA", "3FE", "3FT", "WF0", "WF1", "MAJ", "NM0", "6FP", "8AF", "9BF", "MNB", "LVS"),
        b("genesis", "Genesis", Families.HMG, "KMT", "KMU"),
        b("gmc", "GMC", Families.GM, "1GT", "1GK", "2GT", "3GT"),
        b("holden", "Holden", Families.GM, "6G1", "6G2", "6H8"),
        b("honda", "Honda", Families.HONDA, "JHM", "JHL", "1HG", "2HG", "2HK", "2HJ", "5FN", "5J6", "19X", "SHH", "SHS", "93H", "MRH", "NLA", "LHG"),
        b("hyundai", "Hyundai", Families.HMG, "KMH", "KM8", "5NP", "5NM", "MAL", "NLH", "TMA", "2HM", "KMF"),
        b("infiniti", "Infiniti", Families.NISSAN, "JNK", "JNR", "JNX", "5N3"),
        b("jaguar", "Jaguar", Families.GENERIC, "SAJ", "SAD"),
        b("jeep", "Jeep", Families.STELLANTIS, "1C4", "1J4", "1J8", "ZAC", "3C4"),
        b("kia", "Kia", Families.HMG, "KNA", "KND", "KNC", "5XX", "5XY", "U5Y", "U6Y", "LJD"),
        b("landrover", "Land Rover", Families.GENERIC, "SAL"),
        b("lexus", "Lexus", Families.TOYOTA, "JTH", "JTJ", "2T2", "58A"),
        b("lincoln", "Lincoln", Families.FORD, "1LN", "5LM", "2LM", "3LN"),
        b("mazda", "Mazda", Families.MAZDA, "JM1", "JM3", "JMZ", "JM7", "4F2", "4F4", "3MZ", "3MV", "1YV", "MM0"),
        b("mercedes", "Mercedes-Benz", Families.MERCEDES, "WDB", "WDC", "WDD", "WDF", "W1K", "W1N", "W1V", "W1W", "4JG", "55S", "LE4"),
        b("mercury", "Mercury", Families.FORD, "1ME", "2ME", "4M2"),
        b("mini", "MINI", Families.BMW, "WMW", "WMZ"),
        b("mitsubishi", "Mitsubishi", Families.OTHER_ASIA, "JA3", "JA4", "JMB", "JMY", "4A3", "4A4", "MMB", "MMC", "ML3"),
        b("nissan", "Nissan", Families.NISSAN, "JN1", "JN6", "JN8", "1N4", "1N6", "5N1", "3N1", "3N6", "SJN", "VSK", "MNT", "94D", "VNV"),
        b("opel", "Opel / Vauxhall", Families.OPEL, "W0L", "W0V", "VXK", "SXA"),
        b("peugeot", "Peugeot", Families.STELLANTIS, "VF3", "VR3", "936", "8AD"),
        b("polestar", "Polestar", Families.VOLVO, "LPS", "YSM"),
        b("porsche", "Porsche", Families.VAG, "WP0", "WP1"),
        b("ram", "Ram", Families.STELLANTIS, "1C6", "3C6", "3C7", "1D3"),
        b("renault", "Renault", Families.RENAULT, "VF1", "VS5", "93Y", "8A1", "X7L", "KNM", "VF6"),
        b("seat", "SEAT", Families.VAG, "VSS"),
        b("skoda", "Škoda", Families.VAG, "TMB", "TMP"),
        b("smart", "smart", Families.MERCEDES, "WME", "TCC"),
        b("subaru", "Subaru", Families.SUBARU, "JF1", "JF2", "JF3", "4S3", "4S4", "4S6"),
        b("suzuki", "Suzuki", Families.OTHER_ASIA, "JS1", "JS2", "JS3", "JSA", "TSM", "MA3", "VSE", "KL5"),
        b("tesla", "Tesla", Families.GENERIC, "5YJ", "7SA", "LRW", "XP7"),
        b("toyota", "Toyota", Families.TOYOTA, "JTD", "JTE", "JTM", "JTN", "JTK", "JTL", "JT2", "JT3", "4T1", "4T3", "4T4", "5TD", "5TF", "5TB", "5YF", "2T1", "2T3", "MR0", "MR2", "NMT", "SB1", "AHT", "VNK", "MHF", "6T1", "8AJ", "93R", "LTV", "3TM"),
        b("volkswagen", "Volkswagen", Families.VAG, "WVW", "WVG", "WV1", "WV2", "WV3", "3VW", "1VW", "9BW", "8AW", "AAV", "XW8", "LFV", "LSV"),
        b("volvo", "Volvo", Families.VOLVO, "YV1", "YV4", "LYV", "7JR", "7JD"),
        b("other", "Other / generic", Families.GENERIC),
    )

    val byId: Map<String, Brand> = all.associateBy { it.id }

    /** Detects the brand from a VIN's World Manufacturer Identifier. */
    fun detect(vin: String?): Brand? {
        val v = vin?.trim()?.uppercase() ?: return null
        if (v.length < 3) return null
        val wmi = v.take(3)
        return all.firstOrNull { b -> wmi in b.wmis }
    }
}
