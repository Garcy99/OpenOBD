package com.openobd.core.brand

/**
 * Manufacturer-specific trouble code meanings that are well established for each family.
 * Meanings can still vary by model and year, so the UI always labels them as manufacturer-specific.
 */
object ManufacturerCodes {
    private val ford = mapOf(
        "P1000" to "OBD-II monitor testing not complete (normal after clearing codes or battery disconnect)",
        "P1131" to "Lack of upstream HO2S switch - sensor indicates lean (Bank 1)",
        "P1132" to "Lack of upstream HO2S switch - sensor indicates rich (Bank 1)",
        "P1151" to "Lack of upstream HO2S switch - sensor indicates lean (Bank 2)",
        "P1152" to "Lack of upstream HO2S switch - sensor indicates rich (Bank 2)",
        "P1260" to "Theft detected - vehicle immobilized",
        "P1299" to "Cylinder head over-temperature protection active",
        "P1401" to "DPFE (EGR pressure feedback) sensor circuit high voltage",
        "P1450" to "Unable to bleed up fuel tank vacuum",
        "P1633" to "Keep alive memory (KAM) voltage too low",
        "P1635" to "Tire / axle ratio out of acceptable range",
        "P1744" to "Torque converter clutch system performance",
    )
    private val toyota = mapOf(
        "P1130" to "Air/fuel ratio sensor circuit range/performance (Bank 1 Sensor 1)",
        "P1133" to "Air/fuel ratio sensor circuit response (Bank 1 Sensor 1)",
        "P1135" to "Air/fuel ratio sensor heater circuit (Bank 1 Sensor 1)",
        "P1300" to "Igniter circuit malfunction (No. 1)",
        "P1335" to "Crankshaft position sensor circuit (engine running)",
        "P1349" to "VVT system malfunction (Bank 1)",
        "P1600" to "ECM BATT (backup power) malfunction",
        "P1656" to "Oil control valve (OCV) circuit malfunction (Bank 1)",
        "P1780" to "Park/neutral position switch malfunction",
    )
    private val honda = mapOf(
        "P1259" to "VTEC system malfunction",
        "P1361" to "TDC sensor intermittent interruption",
        "P1362" to "TDC sensor no signal",
        "P1381" to "Cylinder position sensor intermittent interruption",
        "P1382" to "Cylinder position sensor no signal",
        "P1456" to "EVAP emission control system leakage (fuel tank system)",
        "P1457" to "EVAP emission control system leakage (EVAP canister system)",
        "P1491" to "EGR valve lift insufficient",
    )
    private val nissan = mapOf(
        "P1148" to "Closed loop control function (Bank 1)",
        "P1168" to "Closed loop control function (Bank 2)",
        "P1217" to "Engine over temperature (overheat)",
        "P1320" to "Ignition signal",
        "P1610" to "NATS (immobilizer) lock mode detected",
        "P1612" to "NATS: chain of ECM-IMMU communication",
        "P1614" to "NATS: chain of IMMU-key communication",
        "P1716" to "Turbine revolution sensor",
        "P1805" to "Brake switch",
    )
    private val gm = mapOf(
        "P1101" to "Intake airflow system performance",
        "P1133" to "HO2S insufficient switching (Bank 1 Sensor 1)",
        "P1134" to "HO2S transition time ratio (Bank 1 Sensor 1)",
        "P1345" to "Crankshaft / camshaft position correlation",
        "P1380" to "Misfire detected - rough road data unusable",
        "P1381" to "Misfire detected - no communication with brake control module",
        "P1406" to "EGR valve pintle position circuit",
        "P1441" to "EVAP system flow during non-purge",
        "P1626" to "Theft deterrent fuel enable signal not received",
        "P1631" to "Theft deterrent password incorrect",
        "P1870" to "Transmission component slipping",
    )
    private val chrysler = mapOf(
        "P1281" to "Engine is cold too long",
        "P1294" to "Target idle not reached",
        "P1391" to "Intermittent loss of CMP or CKP signal",
        "P1494" to "Leak detection pump switch or mechanical fault",
        "P1495" to "Leak detection pump solenoid circuit",
        "P1684" to "Battery has been disconnected (within the last 50 starts)",
        "P1698" to "No CCD/J1850 messages received from TCM",
        "P1762" to "Governor pressure sensor offset volts too low or high",
    )
    private val hmg = mapOf(
        "P1326" to "Knock sensor detection system (KSDS) - possible connecting rod bearing wear; see recall / extended warranty campaign",
    )

    private val byFamily: Map<String, Map<String, String>> = mapOf(
        "ford" to ford, "mazda" to emptyMap(), "toyota" to toyota, "honda" to honda, "nissan" to nissan,
        "gm" to gm, "stellantis" to chrysler, "hmg" to hmg,
    )

    fun lookup(family: BrandFamily?, code: String): String? {
        val c = code.uppercase().substringBefore('-')
        return family?.let { byFamily[it.id]?.get(c) }
    }

    fun count(family: BrandFamily?): Int = family?.let { byFamily[it.id]?.size } ?: 0

    fun all(family: BrandFamily?): Map<String, String> = family?.let { byFamily[it.id] } ?: emptyMap()
}
