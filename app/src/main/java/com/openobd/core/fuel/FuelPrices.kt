package com.openobd.core.fuel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Fuels the stations price, with the field of the Spanish ministry's data. */
enum class PumpFuel(val label: String, val field: String) {
    DIESEL("Diesel", "Precio Gasoleo A"),
    DIESEL_PREMIUM("Premium diesel", "Precio Gasoleo Premium"),
    PETROL_95("Petrol 95", "Precio Gasolina 95 E5"),
    PETROL_98("Petrol 98", "Precio Gasolina 98 E5"),
    LPG("LPG", "Precio Gases licuados del petróleo"),
    ;

    companion object {
        fun of(name: String?): PumpFuel? = entries.firstOrNull { it.name == name }
        val texts = entries.map { it.label }
    }
}

/** A filling station and its prices in euros per litre (only the fuels it sells). */
data class Station(
    val id: String, val brand: String, val address: String, val town: String,
    val lat: Double, val lon: Double, val hours: String, val prices: Map<PumpFuel, Double>,
)

/** A station with its distance in km from where it was looked up (or from the route). */
data class StationNear(val station: Station, val km: Double) {
    fun price(kind: PumpFuel): Double? = station.prices[kind]
}

/** What a lookup found: the stations and when the ministry last updated their prices. */
data class PriceList(val updatedAt: Long, val stations: List<Station>)

/**
 * Prices of every filling station in Spain, from the open data of the Ministry for the Ecological Transition
 * (updated every half hour, free and without a key). Requests go by province, so only the area around the car is
 * downloaded.
 */
object FuelPricesEs {
    const val ATTRIBUTION = "Prices: Ministerio para la Transición Ecológica (Geoportal de gasolineras), open data"
    private const val BASE = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/"

    fun provinceUrl(id: String) = BASE + "FiltroProvincia/" + id

    /** Where each province's stations are (minLat, minLon, maxLat, maxLon), to know which ones to ask for. */
    val provinces: Map<String, DoubleArray> = mapOf(
        "01" to doubleArrayOf(42.49, -3.12, 43.15, -2.31),
        "02" to doubleArrayOf(38.15, -2.75, 39.37, -0.94),
        "03" to doubleArrayOf(37.86, -1.05, 38.86, 0.19),
        "04" to doubleArrayOf(36.71, -3.00, 37.86, -1.66),
        "05" to doubleArrayOf(40.14, -5.34, 41.13, -4.33),
        "06" to doubleArrayOf(38.03, -7.17, 39.36, -5.05),
        "07" to doubleArrayOf(38.71, 1.30, 40.01, 4.27),
        "08" to doubleArrayOf(0.00, 0.00, 42.25, 2.76),
        "09" to doubleArrayOf(41.57, -4.25, 43.17, -2.70),
        "10" to doubleArrayOf(39.13, -7.30, 40.38, -5.20),
        "11" to doubleArrayOf(36.02, -6.43, 36.93, -5.16),
        "12" to doubleArrayOf(39.74, -0.70, 40.66, 0.48),
        "13" to doubleArrayOf(38.43, -4.97, 39.46, -2.69),
        "14" to doubleArrayOf(37.23, -5.44, 38.57, -4.10),
        "15" to doubleArrayOf(42.55, -9.27, 43.73, -7.85),
        "16" to doubleArrayOf(39.33, -3.08, 40.45, -1.27),
        "17" to doubleArrayOf(41.68, 1.92, 42.46, 3.28),
        "18" to doubleArrayOf(36.70, -4.28, 37.98, -2.33),
        "19" to doubleArrayOf(40.30, -3.45, 41.21, -1.68),
        "20" to doubleArrayOf(42.95, -2.54, 43.36, -1.75),
        "21" to doubleArrayOf(37.02, -7.41, 38.14, -6.22),
        "22" to doubleArrayOf(41.41, -0.79, 42.77, 0.56),
        "23" to doubleArrayOf(37.41, -4.26, 38.41, -2.56),
        "24" to doubleArrayOf(42.08, -6.92, 43.01, -4.98),
        "25" to doubleArrayOf(41.37, 0.39, 42.83, 1.85),
        "26" to doubleArrayOf(41.98, -3.01, 42.58, -1.73),
        "27" to doubleArrayOf(42.47, -7.93, 43.69, -7.02),
        "28" to doubleArrayOf(0.00, -4.48, 41.13, 0.00),
        "29" to doubleArrayOf(36.33, -5.34, 37.27, -3.88),
        "30" to doubleArrayOf(0.00, -1.97, 38.62, 0.00),
        "31" to doubleArrayOf(41.93, -2.37, 43.31, -0.94),
        "32" to doubleArrayOf(41.83, -8.25, 42.51, -6.98),
        "33" to doubleArrayOf(43.04, -7.15, 43.61, -4.56),
        "34" to doubleArrayOf(41.79, -4.97, 42.89, -4.08),
        "35" to doubleArrayOf(27.75, -15.80, 29.15, -13.46),
        "36" to doubleArrayOf(-8.66, -8.88, 42.79, 42.04),
        "37" to doubleArrayOf(40.34, -6.82, 41.24, -5.20),
        "38" to doubleArrayOf(27.71, -18.01, 28.83, -16.21),
        "39" to doubleArrayOf(0.00, -4.61, 43.49, 0.00),
        "40" to doubleArrayOf(40.71, -4.70, 41.44, -3.38),
        "41" to doubleArrayOf(36.85, -6.32, 38.09, -4.68),
        "42" to doubleArrayOf(41.17, -3.19, 42.03, -1.93),
        "43" to doubleArrayOf(40.53, 0.28, 41.54, 1.62),
        "44" to doubleArrayOf(40.06, -1.65, 41.22, 0.21),
        "45" to doubleArrayOf(39.35, -5.33, 40.24, -2.99),
        "46" to doubleArrayOf(38.74, -1.44, 40.13, -0.07),
        "47" to doubleArrayOf(41.18, -5.33, 42.16, -4.11),
        "48" to doubleArrayOf(42.99, -3.36, 43.42, -2.50),
        "49" to doubleArrayOf(41.17, -6.84, 42.09, -5.32),
        "50" to doubleArrayOf(41.12, -2.12, 42.49, 0.25),
        "51" to doubleArrayOf(35.88, -5.34, 35.89, -5.30),
        "52" to doubleArrayOf(35.27, -2.95, 35.30, -2.93)
    )

    /** True when the point is in (or close to) Spain, where these prices exist. */
    fun covers(lat: Double, lon: Double): Boolean = provincesNear(lat, lon, 15.0).isNotEmpty()

    /** Provinces with stations within about [km] of the point. */
    fun provincesNear(lat: Double, lon: Double, km: Double): List<String> {
        val dLat = km / 111.0; val dLon = km / (111.0 * cos(Math.toRadians(lat)))
        return provinces.filter { (_, b) -> lat + dLat >= b[0] && lat - dLat <= b[2] && lon + dLon >= b[1] && lon - dLon <= b[3] }.keys.sorted()
    }

    /** Provinces a route passes through (or near), from its points. */
    fun provincesAlong(points: List<Pair<Double, Double>>, km: Double): List<String> {
        val step = max(1, points.size / 200)
        return points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
            .flatMap { (la, lo) -> provincesNear(la, lo, km) }.distinct().sorted()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The ministry's answer (JSON) to a list of stations; prices written with a decimal comma. */
    fun parse(text: String): PriceList {
        val o = json.parseToJsonElement(text).jsonObject
        val date = (o["Fecha"] as? JsonPrimitive)?.content?.let { d ->
            runCatching {
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Madrid") }.parse(d)?.time
            }.getOrNull()
        } ?: 0L
        val list = (o["ListaEESSPrecio"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e.jsonObject
            fun f(k: String) = (s[k] as? JsonPrimitive)?.content?.trim().orEmpty()
            fun num(k: String) = f(k).replace(',', '.').toDoubleOrNull()
            val lat = num("Latitud") ?: return@mapNotNull null
            val lon = num("Longitud (WGS84)") ?: return@mapNotNull null
            val prices = PumpFuel.entries.mapNotNull { k -> num(k.field)?.takeIf { it > 0.3 && it < 5 }?.let { k to it } }.toMap()
            if (prices.isEmpty()) return@mapNotNull null
            Station(f("IDEESS"), titleCase(f("Rótulo")), titleCase(f("Dirección")), titleCase(f("Localidad")), lat, lon, f("Horario"), prices)
        }
        return PriceList(date, list)
    }

    /** "REPSOL" -> "Repsol", "AVENIDA DE LA CONSTITUCIÓN, 3" -> "Avenida De La Constitución, 3". */
    private fun titleCase(s: String) = s.lowercase(Locale.ROOT).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    /** Great-circle distance in km. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * 6371.0 * asin(min(1.0, sqrt(a)))
    }

    /** Stations selling [kind] within [radiusKm], nearest first. */
    fun near(stations: List<Station>, lat: Double, lon: Double, kind: PumpFuel, radiusKm: Double): List<StationNear> =
        stations.asSequence().filter { kind in it.prices }.map { StationNear(it, km(lat, lon, it.lat, it.lon)) }
            .filter { it.km <= radiusKm }.sortedBy { it.km }.toList()

    /** The usual price around a point: the median of the nearest stations (up to 8, within 20 km). */
    fun localPrice(stations: List<Station>, lat: Double, lon: Double, kind: PumpFuel): Double? {
        val prices = near(stations, lat, lon, kind, 20.0).take(8).mapNotNull { it.price(kind) }.sorted()
        if (prices.isEmpty()) return null
        return if (prices.size % 2 == 1) prices[prices.size / 2] else (prices[prices.size / 2 - 1] + prices[prices.size / 2]) / 2
    }

    /**
     * Stations within [corridorKm] of a route ([points]: latitude, longitude), with their distance from it, cheapest
     * first.
     */
    fun alongRoute(stations: List<Station>, points: List<Pair<Double, Double>>, kind: PumpFuel, corridorKm: Double = 1.5): List<StationNear> {
        if (points.isEmpty()) return emptyList()
        val step = max(1, points.size / 400)
        val pts = points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
        val minLat = pts.minOf { it.first } - corridorKm / 111; val maxLat = pts.maxOf { it.first } + corridorKm / 111
        val k = cos(Math.toRadians(pts[0].first))
        val minLon = pts.minOf { it.second } - corridorKm / (111 * k); val maxLon = pts.maxOf { it.second } + corridorKm / (111 * k)
        return stations.asSequence().filter { kind in it.prices && it.lat in minLat..maxLat && it.lon in minLon..maxLon }
            .map { s -> StationNear(s, pts.minOf { (la, lo) -> km(la, lo, s.lat, s.lon) }) }
            .filter { it.km <= corridorKm }.sortedBy { it.price(kind) }.toList()
    }

    /** Money saved filling [litres] at [price] instead of at [reference]. */
    fun saving(litres: Double, price: Double, reference: Double) = (reference - price) * litres

    val texts = PumpFuel.texts + listOf(ATTRIBUTION)
}
