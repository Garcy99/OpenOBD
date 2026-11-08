package com.openobd.core.trip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** A latitude / longitude pair. */
data class LatLon(val lat: Double, val lon: Double)

/** A road from OpenStreetMap; [rank] 0 = motorway / trunk, 1 primary, 2 secondary, 3 tertiary. */
data class MapRoad(val points: List<LatLon>, val rank: Int)

data class MapBuilding(val outline: List<LatLon>, val heightM: Double)

/** Water, roads and buildings around a route, for the 3D model. */
data class MapFeatures(val water: List<List<LatLon>>, val roads: List<MapRoad>, val buildings: List<MapBuilding>) {
    val isEmpty: Boolean get() = water.isEmpty() && roads.isEmpty() && buildings.isEmpty()

    companion object {
        val NONE = MapFeatures(emptyList(), emptyList(), emptyList())
    }
}

/**
 * OpenStreetMap data through the Overpass API (ODbL, © OpenStreetMap contributors): lakes, reservoirs and
 * riverbanks, main roads, and (for small areas) buildings with their heights.
 */
object Overpass {
    const val ATTRIBUTION = "Map data © OpenStreetMap contributors (ODbL)"
    val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    /** Buildings only make sense (and stay a reasonable download) for small areas. */
    const val MAX_BUILDINGS_SPAN_M = 6000.0

    fun query(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, buildings: Boolean): String {
        val b = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", minLat, minLon, maxLat, maxLon)
        return buildString {
            append("[out:json][timeout:90];(")
            append("way[\"natural\"=\"water\"]($b);relation[\"natural\"=\"water\"]($b);")
            append("way[\"landuse\"=\"reservoir\"]($b);way[\"waterway\"=\"riverbank\"]($b);")
            append("way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary)$\"]($b);")
            if (buildings) append("way[\"building\"]($b);")
            append(");out geom;")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): MapFeatures {
        val root = json.parseToJsonElement(text).jsonObject
        val water = mutableListOf<List<LatLon>>(); val roads = mutableListOf<MapRoad>(); val buildings = mutableListOf<MapBuilding>()
        for (el in (root["elements"] as? JsonArray).orEmpty()) {
            val e = el as? JsonObject ?: continue
            val tags = (e["tags"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
            when ((e["type"] as? JsonPrimitive)?.content) {
                "way" -> {
                    val pts = geometry(e["geometry"])
                    if (pts.size < 2) continue
                    val highway = tags["highway"]
                    when {
                        highway != null -> rank(highway)?.let { roads += MapRoad(pts, it) }
                        tags["building"] != null && closed(pts) -> buildings += MapBuilding(pts.dropLast(1), height(tags))
                        isWater(tags) && closed(pts) -> water += pts.dropLast(1)
                    }
                }
                "relation" -> if (isWater(tags)) {
                    val outers = (e["members"] as? JsonArray).orEmpty().mapNotNull { m ->
                        val mo = m as? JsonObject ?: return@mapNotNull null
                        if ((mo["role"] as? JsonPrimitive)?.content != "outer") null else geometry(mo["geometry"]).takeIf { it.size >= 2 }
                    }
                    water += rings(outers)
                }
            }
        }
        return MapFeatures(water, roads, buildings)
    }

    private fun geometry(g: kotlinx.serialization.json.JsonElement?): List<LatLon> = (g as? JsonArray).orEmpty().mapNotNull { p ->
        val o = p as? JsonObject ?: return@mapNotNull null
        val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull; val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull
        if (lat == null || lon == null) null else LatLon(lat, lon)
    }

    private fun closed(p: List<LatLon>) = p.size >= 4 && p.first() == p.last()
    private fun isWater(t: Map<String, String>) = t["natural"] == "water" || t["landuse"] == "reservoir" || t["waterway"] == "riverbank"

    fun rank(highway: String): Int? = when (highway) {
        "motorway", "trunk" -> 0
        "primary" -> 1
        "secondary" -> 2
        "tertiary" -> 3
        else -> null
    }

    /** Height from `height` ("12", "12 m"), else `building:levels` x 3 m, else 6 m. */
    fun height(t: Map<String, String>): Double {
        t["height"]?.let { Regex("[0-9]+([.,][0-9]+)?").find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }?.let { return it.coerceIn(2.0, 500.0) }
        t["building:levels"]?.toDoubleOrNull()?.let { return (it * 3.0 + 1).coerceIn(3.0, 500.0) }
        return 6.0
    }

    /** Joins outer member ways that share end points into closed rings. */
    fun rings(parts: List<List<LatLon>>): List<List<LatLon>> {
        val open = parts.map { it.toMutableList() }.toMutableList()
        val done = mutableListOf<List<LatLon>>()
        while (open.isNotEmpty()) {
            val ring = open.removeAt(0)
            var grown = true
            while (ring.first() != ring.last() && grown) {
                grown = false
                val i = open.indexOfFirst { it.first() == ring.last() || it.last() == ring.last() || it.last() == ring.first() || it.first() == ring.first() }
                if (i >= 0) {
                    val w = open.removeAt(i)
                    when {
                        w.first() == ring.last() -> ring.addAll(w.drop(1))
                        w.last() == ring.last() -> ring.addAll(w.reversed().drop(1))
                        w.last() == ring.first() -> ring.addAll(0, w.dropLast(1))
                        else -> ring.addAll(0, w.reversed().dropLast(1))
                    }
                    grown = true
                }
            }
            if (ring.size >= 4 && ring.first() == ring.last()) done += ring.dropLast(1)
        }
        return done
    }
}
