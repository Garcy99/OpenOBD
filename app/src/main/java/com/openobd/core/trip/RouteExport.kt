package com.openobd.core.trip

import com.openobd.core.garage.TrackPoint
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a route export says about the trip besides the track itself. */
data class RouteInfo(
    val title: String,
    val car: String,
    val start: Long,
    val end: Long,
    val distanceKm: Double,
    val fuelL: Double? = null,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val avgL100: Double? = null,
    val creator: String = "OpenOBD",
)

/** Shared geometry: local metres, simplification, speed bands. */
object RouteGeometry {
    /** Speed bands used for colours everywhere: city, road, motorway, unknown. */
    const val SLOW = 0; const val MEDIUM = 1; const val FAST = 2; const val UNKNOWN = 3
    val bandColors = listOf("#2E9E5B", "#F2A900", "#E0393E", "#3B82F6")
    val bandLabels = listOf("Up to 50 km/h", "50-100 km/h", "Over 100 km/h", "Speed unknown")

    fun band(speedKmh: Float?): Int = when {
        speedKmh == null -> UNKNOWN
        speedKmh <= 50f -> SLOW
        speedKmh <= 100f -> MEDIUM
        else -> FAST
    }

    /** Points as (east, north) metres from the first point (equirectangular: exact enough for a drive). */
    fun metres(points: List<TrackPoint>): List<Pair<Double, Double>> {
        if (points.isEmpty()) return emptyList()
        val lat0 = points.first().lat; val lon0 = points.first().lon
        val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 110_540.0
        return points.map { (it.lon - lon0) * kx to (it.lat - lat0) * ky }
    }

    /** Douglas-Peucker in metres; keeps the first and last point. */
    fun simplify(points: List<TrackPoint>, toleranceM: Double): List<TrackPoint> {
        if (points.size < 3 || toleranceM <= 0) return points
        val m = metres(points)
        val keep = BooleanArray(points.size); keep[0] = true; keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1; var dist = 0.0
            for (i in a + 1 until b) {
                val d = segmentDistance(m[i], m[a], m[b])
                if (d > dist) { dist = d; best = i }
            }
            if (best >= 0 && dist > toleranceM) { keep[best] = true; stack.add(a to best); stack.add(best to b) }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun segmentDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first; val dy = b.second - a.second
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
        val x = a.first + t * dx - p.first; val y = a.second + t * dy - p.second
        return sqrt(x * x + y * y)
    }

    /** Consecutive points with the same speed band; neighbouring runs share their boundary point so they touch. */
    fun runs(points: List<TrackPoint>): List<Pair<Int, List<TrackPoint>>> {
        if (points.size < 2) return emptyList()
        val out = mutableListOf<Pair<Int, List<TrackPoint>>>()
        var current = mutableListOf(points[0]); var b = band(points[0].speedKmh)
        for (p in points.drop(1)) {
            val pb = band(p.speedKmh)
            current += p
            if (pb != b) { if (current.size >= 2) out += b to current; current = mutableListOf(p); b = pb }
        }
        if (current.size >= 2) out += b to current
        return out
    }

    /** Climb in metres (sum of rises larger than GPS noise), or null without altitude. */
    fun climb(points: List<TrackPoint>): Double? {
        val alts = points.mapNotNull { it.alt?.toDouble() }
        if (alts.size < 2) return null
        var total = 0.0; var ref = alts[0]
        for (a in alts) { if (a - ref > 3) { total += a - ref; ref = a } else if (a < ref) ref = a }
        return total
    }
}

private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private fun isoTime(t: Long) = synchronized(iso) { iso.format(Date(t)) }
private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun f(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

/**
 * GPX 1.1, readable by Garmin, OsmAnd, Komoot, Google Earth, Strava and most GPS apps. Speed uses Garmin's
 * TrackPointExtension; the car's own values (rpm, consumption, coolant, accelerator, boost) are in an OpenOBD
 * extension that other apps ignore and OpenOBD's HTML map reads.
 */
object Gpx {
    const val NS = "https://github.com/Garcy99/OpenOBD/gpx/1"

    fun write(info: RouteInfo, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="${xml(info.creator)}" xmlns="http://www.topografix.com/GPX/1/1" """)
        append("""xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2" xmlns:openobd="$NS" """)
        append("""xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">""").append('\n')
        append("  <metadata><name>${xml(info.title)}</name><desc>${xml(info.car)}</desc><time>${isoTime(info.start)}</time></metadata>\n")
        points.firstOrNull()?.let { append("  <wpt lat=\"${f(it.lat, 7)}\" lon=\"${f(it.lon, 7)}\"><time>${isoTime(it.t)}</time><name>Start</name><sym>Flag, Green</sym></wpt>\n") }
        points.lastOrNull()?.takeIf { points.size > 1 }?.let { append("  <wpt lat=\"${f(it.lat, 7)}\" lon=\"${f(it.lon, 7)}\"><time>${isoTime(it.t)}</time><name>End</name><sym>Flag, Red</sym></wpt>\n") }
        append("  <trk><name>${xml(info.title)}</name><desc>${xml(info.car)}</desc><type>driving</type><trkseg>\n")
        for (p in points) {
            append("    <trkpt lat=\"${f(p.lat, 7)}\" lon=\"${f(p.lon, 7)}\">")
            p.alt?.let { append("<ele>${f(it.toDouble(), 1)}</ele>") }
            append("<time>${isoTime(p.t)}</time>")
            val car = listOfNotNull(
                p.rpm?.let { "<openobd:rpm>${f(it.toDouble(), 0)}</openobd:rpm>" },
                p.l100?.let { "<openobd:l100km>${f(it.toDouble(), 1)}</openobd:l100km>" },
                p.coolantC?.let { "<openobd:coolant>${f(it.toDouble(), 0)}</openobd:coolant>" },
                p.pedal?.let { "<openobd:accelerator>${f(it.toDouble(), 0)}</openobd:accelerator>" },
                p.boostBar?.let { "<openobd:boost>${f(it.toDouble(), 2)}</openobd:boost>" },
            )
            if (p.speedKmh != null || car.isNotEmpty()) {
                append("<extensions>")
                p.speedKmh?.let { append("<gpxtpx:TrackPointExtension><gpxtpx:speed>${f(it / 3.6, 2)}</gpxtpx:speed></gpxtpx:TrackPointExtension>") }
                car.forEach { append(it) }
                append("</extensions>")
            }
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }
}

/**
 * One HTML file anyone can open in a browser: the route on an OpenStreetMap map coloured by speed, tap (or slide
 * along) the route to see what the car was doing at each point, the trip summary, and a button to save the GPX
 * for their own GPS. Map tiles and the Leaflet library load from the internet; without it the data still shows.
 */
object RouteHtml {
    fun write(info: RouteInfo, points: List<TrackPoint>, label: (String) -> String, units: Units = Units()): String {
        val gpx = java.util.Base64.getEncoder().encodeToString(Gpx.write(info, points).toByteArray(Charsets.UTF_8))
        fun n(v: Float?, d: Int) = v?.let { f(it.toDouble(), d) } ?: "null"
        val data = points.joinToString(",", "[", "]") { p ->
            "[${f(p.lat, 6)},${f(p.lon, 6)},${(p.t - info.start) / 1000},${n(p.speedKmh, 1)},${n(p.rpm, 0)},${n(p.l100, 1)},${n(p.coolantC, 0)},${n(p.pedal, 0)},${n(p.boostBar, 2)},${n(p.alt, 0)}]"
        }
        val js = { s: String -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c").replace("\n", " ") + "\"" }
        val labels = listOf(
            "speed" to "Speed", "rpm" to "Engine speed", "cons" to "Consumption", "coolant" to "Coolant", "pedal" to "Accelerator",
            "boost" to "Boost", "alt" to "Altitude", "time" to "Time into the trip", "tap" to "Tap the route or move the slider to see what the car was doing there.",
            "offline" to "The map needs an internet connection; the data below still works.",
        ).joinToString(",", "{", "}") { (k, v) -> "$k:${js(label(v))}" }
        val stats = listOfNotNull(
            label("Distance") to units.distance(info.distanceKm),
            label("Duration") to duration((info.end - info.start) / 1000),
            label("Average speed (moving)") to units.speed(info.avgSpeedKmh),
            label("Max speed") to units.speed(info.maxSpeedKmh),
            info.fuelL?.let { label("Fuel used") to units.volume(it) },
            info.avgL100?.let { label("Average consumption") to units.consumption(it) },
            RouteGeometry.climb(points)?.let { label("Climb") to units.height(it) },
        ).joinToString("") { (k, v) -> "<div class=\"s\"><span>${xml(k)}</span><b>${xml(v)}</b></div>" }
        val legend = RouteGeometry.bandColors.indices.filter { b -> points.any { RouteGeometry.band(it.speedKmh) == b } }
            .joinToString("") { b -> "<span><i style=\"background:${RouteGeometry.bandColors[b]}\"></i>${xml(label(units.bandLabel(b)))}</span>" }
        val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(info.start))
        return """<!doctype html>
<html lang="${Locale.getDefault().language}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${xml(info.title)}</title>
<meta name="generator" content="${xml(info.creator)}">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
:root{--bg:#f6f7f9;--card:#fff;--ink:#16181d;--muted:#5d6470;--line:#e3e6eb;--accent:#0f766e}
@media (prefers-color-scheme:dark){:root{--bg:#0e1116;--card:#171b22;--ink:#eef1f5;--muted:#9aa3af;--line:#262c36;--accent:#2dd4bf}}
*{box-sizing:border-box}body{margin:0;font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--ink)}
main{max-width:980px;margin:0 auto;padding:16px}h1{font-size:22px;margin:4px 0 2px}.sub{color:var(--muted);margin-bottom:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:14px;margin-bottom:14px}
#map{height:58vh;min-height:320px;border-radius:12px}.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px}
.s span{display:block;color:var(--muted);font-size:13px}.s b{font-size:18px}.legend{display:flex;flex-wrap:wrap;gap:12px;color:var(--muted);font-size:13px;margin-top:8px}
.legend i{display:inline-block;width:18px;height:6px;border-radius:3px;margin-right:6px;vertical-align:middle}
#pt{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:8px}#hint{color:var(--muted);font-size:13px}
input[type=range]{width:100%;accent-color:var(--accent)}a.btn{display:inline-block;background:var(--accent);color:#fff;text-decoration:none;padding:10px 16px;border-radius:999px;font-weight:600}
footer{color:var(--muted);font-size:12px;text-align:center;margin:18px 0}
</style></head><body><main>
<h1>${xml(info.title)}</h1><div class="sub">${xml(info.car)} · ${xml(date)}</div>
<div class="card"><div id="map"></div><div class="legend">$legend</div></div>
<div class="card"><div id="hint"></div><input id="slider" type="range" min="0" max="${max(0, points.size - 1)}" value="0" aria-label="Position"><div id="pt"></div></div>
<div class="card"><div class="stats">$stats</div></div>
<p><a class="btn" download="${xml(fileName(info))}.gpx" href="data:application/gpx+xml;base64,$gpx">${xml(label("Save GPX for your GPS"))}</a></p>
<footer>${xml(label("Made with OpenOBD"))} · ${xml(label("Map data © OpenStreetMap contributors"))}</footer>
</main>
<script>
const D=$data,L$=$labels,C=${RouteGeometry.bandColors.joinToString(",", "[", "]") { "\"$it\"" }},U=${units.js()};
function band(s){return s==null?3:s<=50?0:s<=100?1:2}
function fmtT(t){const h=Math.floor(t/3600),m=Math.floor(t%3600/60),s=t%60;return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(s).padStart(2,'0')}
function row(k,v){return v==null?'':'<div class="s"><span>'+k+'</span><b>'+v+'</b></div>'}
const slider=document.getElementById('slider'),pt=document.getElementById('pt');document.getElementById('hint').textContent=L$.tap;
let map=null,marker=null;
function show(i){const p=D[i];if(!p)return;slider.value=i;
pt.innerHTML=row(L$.time,fmtT(p[2]))+row(L$.speed,p[3]==null?null:U.speed(p[3]))+row(L$.rpm,p[4]==null?null:p[4]+' rpm')+row(L$.cons,p[5]==null?null:U.cons(p[5]))
+row(L$.coolant,p[6]==null?null:U.temp(p[6]))+row(L$.pedal,p[7]==null?null:p[7]+' %')+row(L$.boost,p[8]==null?null:U.boost(p[8]))+row(L$.alt,p[9]==null?null:U.height(p[9]));
if(map){marker.setLatLng([p[0],p[1]]);}}
slider.addEventListener('input',e=>show(+e.target.value));
function nearest(lat,lon){let b=0,d=1e18;const k=Math.cos(lat*Math.PI/180);for(let i=0;i<D.length;i++){const x=(D[i][1]-lon)*k,y=D[i][0]-lat,q=x*x+y*y;if(q<d){d=q;b=i}}return b}
function init(){if(typeof L==='undefined'){document.getElementById('map').innerHTML='<p style="padding:16px">'+L$.offline+'</p>';show(0);return}
map=L.map('map');L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap contributors'}).addTo(map);
let seg=[],b=band(D[0]&&D[0][3]);for(let i=0;i<D.length;i++){const p=D[i],pb=band(p[3]);seg.push([p[0],p[1]]);
if(pb!==b||i===D.length-1){const l=L.polyline(seg,{color:C[b],weight:6,opacity:.95}).addTo(map);l.on('click',e=>show(nearest(e.latlng.lat,e.latlng.lng)));seg=[[p[0],p[1]]];b=pb}}
L.circleMarker([D[0][0],D[0][1]],{radius:7,color:'#fff',weight:2,fillColor:'#16a34a',fillOpacity:1}).addTo(map);
L.circleMarker([D[D.length-1][0],D[D.length-1][1]],{radius:7,color:'#fff',weight:2,fillColor:'#111',fillOpacity:1}).addTo(map);
marker=L.circleMarker([D[0][0],D[0][1]],{radius:9,color:'#111',weight:3,fillColor:'#fff',fillOpacity:1}).addTo(map);
map.fitBounds(D.map(p=>[p[0],p[1]]),{padding:[20,20]});map.on('click',e=>show(nearest(e.latlng.lat,e.latlng.lng)));show(0)}
</script>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" onload="init()" onerror="init()"></script>
</body></html>
"""
    }
}

/** Display units for exports (metric or imperial), kept here so the core stays free of Android. */
data class Units(val imperial: Boolean = false) {
    fun distance(km: Double) = if (imperial) f(km * 0.621371, 1) + " mi" else f(km, 1) + " km"
    fun speed(kmh: Double) = if (imperial) f(kmh * 0.621371, 0) + " mph" else f(kmh, 0) + " km/h"
    fun volume(l: Double) = if (imperial) f(l * 0.264172, 2) + " gal" else f(l, 2) + " L"
    fun consumption(l100: Double) = if (imperial) f(235.215 / l100, 1) + " mpg" else f(l100, 1) + " L/100 km"
    fun height(m: Double) = if (imperial) f(m * 3.28084, 0) + " ft" else f(m, 0) + " m"
    fun bandLabel(b: Int) = if (imperial) listOf("Up to 30 mph", "30-60 mph", "Over 60 mph", "Speed unknown")[b] else RouteGeometry.bandLabels[b]

    /** The same conversions for the HTML map's script. */
    fun js(): String = if (imperial) "{speed:v=>(v*0.621371).toFixed(0)+' mph',cons:v=>(235.215/v).toFixed(1)+' mpg',temp:v=>(v*9/5+32).toFixed(0)+' °F',boost:v=>(v*14.5038).toFixed(1)+' psi',height:v=>(v*3.28084).toFixed(0)+' ft'}"
        else "{speed:v=>v.toFixed(0)+' km/h',cons:v=>v.toFixed(1)+' L/100 km',temp:v=>v.toFixed(0)+' °C',boost:v=>v.toFixed(2)+' bar',height:v=>v.toFixed(0)+' m'}"

    companion object {
        val texts = listOf("Up to 30 mph", "30-60 mph", "Over 60 mph") + RouteGeometry.bandLabels
    }
}

private fun duration(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

/** A safe file name for a trip: "OpenOBD 2026-11-08 0842". */
fun fileName(info: RouteInfo): String = "OpenOBD " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(info.start))

/**
 * A printable poster of the route, as simple vector shapes in millimetres so it can be written as SVG here and
 * drawn into a PDF by the app. A3 portrait by default; vector, so it prints sharp at any size.
 */
object RoutePoster {
    sealed interface Shape
    data class Line(val points: List<Pair<Double, Double>>, val color: String, val width: Double) : Shape
    data class Circle(val x: Double, val y: Double, val r: Double, val fill: String, val stroke: String?, val strokeWidth: Double = 0.0) : Shape
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double, val fill: String) : Shape
    /** [anchor]: 0 start, 1 middle, 2 end; [y] is the baseline. */
    data class Text(val x: Double, val y: Double, val text: String, val size: Double, val color: String, val bold: Boolean = false, val anchor: Int = 0) : Shape

    data class Poster(val widthMm: Double, val heightMm: Double, val shapes: List<Shape>)

    fun layout(info: RouteInfo, points: List<TrackPoint>, label: (String) -> String, units: Units = Units(), widthMm: Double = 297.0, heightMm: Double = 420.0): Poster {
        val ink = "#16181D"; val muted = "#6B7280"
        val shapes = mutableListOf<Shape>(Rect(0.0, 0.0, widthMm, heightMm, "#FFFFFF"))
        val margin = widthMm * 0.08
        shapes += Text(margin, margin + 12, info.title, widthMm * 0.055, ink, bold = true)
        val date = SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.getDefault()).format(Date(info.start))
        shapes += Text(margin, margin + 22, listOf(info.car, date).filter { it.isNotBlank() }.joinToString("  ·  "), widthMm * 0.024, muted)

        // Route box
        val top = margin + 34; val statsH = heightMm * 0.2; val bottom = heightMm - margin - statsH
        val boxW = widthMm - 2 * margin; val boxH = bottom - top
        val simple = RouteGeometry.simplify(points, 2.0)
        val m = RouteGeometry.metres(simple)
        if (m.size >= 2) {
            val minX = m.minOf { it.first }; val maxX = m.maxOf { it.first }; val minY = m.minOf { it.second }; val maxY = m.maxOf { it.second }
            val spanX = max(maxX - minX, 1.0); val spanY = max(maxY - minY, 1.0)
            val scale = min(boxW / spanX, boxH / spanY) // mm per metre
            val offX = margin + (boxW - spanX * scale) / 2; val offY = top + (boxH - spanY * scale) / 2
            fun xy(p: Pair<Double, Double>) = (offX + (p.first - minX) * scale) to (offY + (maxY - p.second) * scale)
            val index = simple.indices.associateBy { simple[it] }
            val stroke = widthMm * 0.0065
            for ((b, run) in RouteGeometry.runs(simple)) shapes += Line(run.map { xy(m[index.getValue(it)]) }, RouteGeometry.bandColors[b], stroke)
            val s = xy(m.first()); val e = xy(m.last())
            shapes += Circle(s.first, s.second, stroke * 1.6, "#16A34A", "#FFFFFF", stroke * 0.5)
            shapes += Circle(e.first, e.second, stroke * 1.6, ink, "#FFFFFF", stroke * 0.5)
            // Scale bar: a round distance close to a fifth of the box
            val target = boxW / 5 / scale
            val nice = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0, 100000.0)
                .let { list -> if (units.imperial) list.map { it * 1.609344 } else list }.lastOrNull { it <= target } ?: 10.0
            val barY = bottom + 6
            shapes += Line(listOf(margin to barY, margin + nice * scale to barY), ink, 0.6)
            shapes += Line(listOf(margin to barY - 1.5, margin to barY + 1.5), ink, 0.6)
            shapes += Line(listOf(margin + nice * scale to barY - 1.5, margin + nice * scale to barY + 1.5), ink, 0.6)
            val niceLabel = if (units.imperial) (nice / 1609.344).let { if (it >= 1) f(it, 0) + " mi" else f(it * 5280, 0) + " ft" }
                else if (nice >= 1000) f(nice / 1000, 0) + " km" else f(nice, 0) + " m"
            shapes += Text(margin + nice * scale + 3, barY + 1.5, niceLabel, widthMm * 0.018, muted)
            // North arrow
            val nx = widthMm - margin - 4; val ny = bottom + 2
            shapes += Line(listOf(nx to ny + 6, nx to ny - 3), ink, 0.6)
            shapes += Line(listOf(nx - 2.2 to ny, nx to ny - 3.5, nx + 2.2 to ny), ink, 0.6)
            shapes += Text(nx, ny - 5.5, "N", widthMm * 0.02, ink, bold = true, anchor = 1)
        }

        // Stats
        val stats = listOfNotNull(
            label("Distance") to units.distance(info.distanceKm),
            label("Duration") to duration((info.end - info.start) / 1000),
            label("Average speed (moving)") to units.speed(info.avgSpeedKmh),
            label("Max speed") to units.speed(info.maxSpeedKmh),
            info.fuelL?.let { label("Fuel used") to units.volume(it) },
            info.avgL100?.let { label("Average consumption") to units.consumption(it) },
            RouteGeometry.climb(points)?.let { label("Climb") to units.height(it) },
        )
        val cols = 3; val colW = (widthMm - 2 * margin) / cols
        val statsTop = bottom + 20
        stats.forEachIndexed { i, (k, v) ->
            val x = margin + (i % cols) * colW; val y = statsTop + (i / cols) * widthMm * 0.08
            shapes += Text(x, y, k, widthMm * 0.017, muted)
            shapes += Text(x, y + widthMm * 0.035, v, widthMm * 0.03, ink, bold = true)
        }
        // Legend and credit
        val legendY = heightMm - margin + 6
        var lx = margin
        for (b in RouteGeometry.bandColors.indices.filter { b -> points.any { RouteGeometry.band(it.speedKmh) == b } }) {
            shapes += Line(listOf(lx to legendY - 1.2, lx + 8 to legendY - 1.2), RouteGeometry.bandColors[b], 1.6)
            val t = label(units.bandLabel(b))
            shapes += Text(lx + 10, legendY, t, widthMm * 0.017, muted)
            lx += 14 + t.length * widthMm * 0.0085
        }
        shapes += Text(widthMm - margin, legendY, "OpenOBD", widthMm * 0.017, muted, anchor = 2)
        return Poster(widthMm, heightMm, shapes)
    }

    fun svg(p: Poster): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<svg xmlns="http://www.w3.org/2000/svg" width="${f(p.widthMm, 0)}mm" height="${f(p.heightMm, 0)}mm" viewBox="0 0 ${f(p.widthMm, 2)} ${f(p.heightMm, 2)}" font-family="Helvetica, Arial, sans-serif">""").append('\n')
        for (s in p.shapes) when (s) {
            is Rect -> append("""<rect x="${f(s.x, 2)}" y="${f(s.y, 2)}" width="${f(s.w, 2)}" height="${f(s.h, 2)}" fill="${s.fill}"/>""")
            is Line -> append("""<polyline fill="none" stroke="${s.color}" stroke-width="${f(s.width, 2)}" stroke-linecap="round" stroke-linejoin="round" points="${s.points.joinToString(" ") { "${f(it.first, 2)},${f(it.second, 2)}" }}"/>""")
            is Circle -> append("""<circle cx="${f(s.x, 2)}" cy="${f(s.y, 2)}" r="${f(s.r, 2)}" fill="${s.fill}"${s.stroke?.let { " stroke=\"$it\" stroke-width=\"${f(s.strokeWidth, 2)}\"" } ?: ""}/>""")
            is Text -> append("""<text x="${f(s.x, 2)}" y="${f(s.y, 2)}" font-size="${f(s.size, 2)}" fill="${s.color}"${if (s.bold) " font-weight=\"bold\"" else ""}${when (s.anchor) { 1 -> " text-anchor=\"middle\""; 2 -> " text-anchor=\"end\""; else -> "" }}>${xml(s.text)}</text>""")
        }.also { append('\n') }
        append("</svg>\n")
    }
}

/**
 * A 3D-printable model of the route as a 3MF file (millimetres), with separate parts so a multi-colour printer
 * can give each its own filament: the base plate, the route split by speed band, and start / end markers.
 * With altitude, the route rises and falls with the road. Single-colour printers simply print it all in one.
 */
object Route3mf {
    data class Options(
        val sizeMm: Double = 150.0,
        val marginMm: Double = 8.0,
        val baseMm: Double = 3.0,
        val routeWidthMm: Double = 2.4,
        val routeHeightMm: Double = 2.0,
        val reliefMm: Double = 6.0,
    )

    class Mesh(val name: String, val color: String) {
        val v = mutableListOf<DoubleArray>()
        val t = mutableListOf<IntArray>()
        fun vertex(x: Double, y: Double, z: Double): Int { v += doubleArrayOf(x, y, z); return v.size - 1 }
        fun tri(a: Int, b: Int, c: Int) { t += intArrayOf(a, b, c) }
        fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }
    }

    fun meshes(points: List<TrackPoint>, o: Options = Options()): List<Mesh> {
        val raw = RouteGeometry.metres(points)
        if (raw.size < 2) return emptyList()
        val spanM = max(raw.maxOf { it.first } - raw.minOf { it.first }, raw.maxOf { it.second } - raw.minOf { it.second }).coerceAtLeast(1.0)
        val inner = o.sizeMm - 2 * o.marginMm
        // Keep detail the printer can show (~0.3 mm), which also removes GPS jitter.
        val simple = RouteGeometry.simplify(points, 0.3 * spanM / inner)
        val m = RouteGeometry.metres(simple)
        val minX = m.minOf { it.first }; val minY = m.minOf { it.second }
        val scale = inner / spanM
        val xy = m.map { (o.marginMm + (it.first - minX) * scale) to (o.marginMm + (it.second - minY) * scale) }
        val w = xy.maxOf { it.first } + o.marginMm; val h = xy.maxOf { it.second } + o.marginMm
        // Relief: route height follows altitude, interpolated where it is missing.
        val alts = simple.map { it.alt?.toDouble() }
        val known = alts.filterNotNull()
        val range = if (known.size >= 2) known.max() - known.min() else 0.0
        val relief = DoubleArray(simple.size) { i ->
            if (range < 5) 0.0 else {
                val a = alts[i] ?: run {
                    val before = (i downTo 0).firstOrNull { alts[it] != null }; val after = (i until alts.size).firstOrNull { alts[it] != null }
                    when {
                        before != null && after != null && after != before -> alts[before]!! + (alts[after]!! - alts[before]!!) * (i - before) / (after - before)
                        before != null -> alts[before]!!
                        else -> alts[after!!]!!
                    }
                }
                (a - known.min()) / range * o.reliefMm
            }
        }
        val out = mutableListOf<Mesh>()
        out += box("Base", "#2B2D31", 0.0, 0.0, 0.0, w, h, o.baseMm)
        val index = simple.indices.associateBy { simple[it] }
        val names = listOf("Route up to 50 km/h", "Route 50-100 km/h", "Route over 100 km/h", "Route")
        val byBand = RouteGeometry.runs(simple).groupBy({ it.first }, { it.second })
        for ((band, runs) in byBand.toSortedMap()) {
            val mesh = Mesh(names[band], RouteGeometry.bandColors[band])
            for (run in runs) {
                val ids = run.map { index.getValue(it) }
                ribbon(mesh, ids.map { xy[it] }, ids.map { relief[it] }, o.baseMm - 0.2, o.baseMm + o.routeHeightMm, o.routeWidthMm / 2)
            }
            out += mesh
        }
        val top = o.baseMm + o.routeHeightMm + 1.2
        out += cylinder("Start", "#F5F5F5", xy.first().first, xy.first().second, o.routeWidthMm * 1.3, o.baseMm - 0.2, top + relief.first())
        out += cylinder("End", "#111111", xy.last().first, xy.last().second, o.routeWidthMm * 1.3, o.baseMm - 0.2, top + relief.last())
        return out
    }

    private fun box(name: String, color: String, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = Mesh(name, color).apply {
        val p = listOf(
            vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x0, y1, z0),
            vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1),
        )
        quad(p[0], p[3], p[2], p[1]) // bottom (normal down)
        quad(p[4], p[5], p[6], p[7]) // top
        quad(p[0], p[1], p[5], p[4]); quad(p[1], p[2], p[6], p[5]); quad(p[2], p[3], p[7], p[6]); quad(p[3], p[0], p[4], p[7])
    }

    private fun cylinder(name: String, color: String, cx: Double, cy: Double, r: Double, z0: Double, z1: Double, n: Int = 24) = Mesh(name, color).apply {
        val bc = vertex(cx, cy, z0); val tc = vertex(cx, cy, z1)
        val b = (0 until n).map { val a = 2 * Math.PI * it / n; vertex(cx + r * cos(a), cy + r * Math.sin(a), z0) }
        val t = (0 until n).map { val a = 2 * Math.PI * it / n; vertex(cx + r * cos(a), cy + r * Math.sin(a), z1) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            tri(bc, b[j], b[i]); tri(tc, t[i], t[j]); quad(b[i], b[j], t[j], t[i])
        }
    }

    /** A closed strip along [path]: half-width [hw], from [z0] to [z1] (+ relief per point). */
    private fun ribbon(mesh: Mesh, path: List<Pair<Double, Double>>, relief: List<Double>, z0: Double, z1: Double, hw: Double) {
        // Drop points closer than 0.05 mm, which would give zero-length segments.
        val pts = mutableListOf<Int>()
        path.indices.forEach { i -> if (pts.isEmpty() || dist(path[pts.last()], path[i]) > 0.05) pts += i }
        if (pts.size < 2) return
        val p = pts.map { path[it] }; val rz = pts.map { relief[it] }
        fun normal(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
            val dx = b.first - a.first; val dy = b.second - a.second; val l = sqrt(dx * dx + dy * dy)
            return -dy / l to dx / l
        }
        val left = mutableListOf<Pair<Double, Double>>(); val right = mutableListOf<Pair<Double, Double>>()
        for (i in p.indices) {
            val n1 = if (i > 0) normal(p[i - 1], p[i]) else normal(p[0], p[1])
            val n2 = if (i < p.lastIndex) normal(p[i], p[i + 1]) else n1
            var nx = n1.first + n2.first; var ny = n1.second + n2.second
            val l = sqrt(nx * nx + ny * ny)
            if (l < 1e-6) { nx = n1.first; ny = n1.second } else { nx /= l; ny /= l }
            // Miter length, limited so sharp turns don't spike.
            val cosHalf = (nx * n1.first + ny * n1.second).coerceAtLeast(0.35)
            val k = hw / cosHalf
            left += (p[i].first + nx * k) to (p[i].second + ny * k)
            right += (p[i].first - nx * k) to (p[i].second - ny * k)
        }
        val lb = left.map { mesh.vertex(it.first, it.second, z0) }; val rb = right.map { mesh.vertex(it.first, it.second, z0) }
        val lt = left.mapIndexed { i, it -> mesh.vertex(it.first, it.second, z1 + rz[i]) }; val rt = right.mapIndexed { i, it -> mesh.vertex(it.first, it.second, z1 + rz[i]) }
        for (i in 0 until p.lastIndex) {
            val j = i + 1
            mesh.quad(lt[i], rt[i], rt[j], lt[j])   // top
            mesh.quad(lb[i], lb[j], rb[j], rb[i])   // bottom
            mesh.quad(lb[i], lt[i], lt[j], lb[j])   // left side
            mesh.quad(rb[i], rb[j], rt[j], rt[i])   // right side
        }
        val e = p.lastIndex
        mesh.quad(lb[0], rb[0], rt[0], lt[0])       // start cap
        mesh.quad(lb[e], lt[e], rt[e], rb[e])       // end cap
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = sqrt((a.first - b.first).let { it * it } + (a.second - b.second).let { it * it })

    fun write(meshes: List<Mesh>, out: OutputStream, title: String) {
        val zip = ZipOutputStream(out)
        fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
        val model = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">""").append('\n')
            append("""<metadata name="Title">${xml(title)}</metadata><metadata name="Application">OpenOBD</metadata>""").append('\n')
            append("<resources>\n<basematerials id=\"1\">")
            meshes.forEach { append("<base name=\"${xml(it.name)}\" displaycolor=\"${it.color}\"/>") }
            append("</basematerials>\n")
            meshes.forEachIndexed { i, mesh ->
                append("<object id=\"${i + 2}\" name=\"${xml(mesh.name)}\" type=\"model\" pid=\"1\" pindex=\"$i\"><mesh><vertices>")
                mesh.v.forEach { append("<vertex x=\"${f(it[0], 3)}\" y=\"${f(it[1], 3)}\" z=\"${f(it[2], 3)}\"/>") }
                append("</vertices><triangles>")
                mesh.t.forEach { append("<triangle v1=\"${it[0]}\" v2=\"${it[1]}\" v3=\"${it[2]}\"/>") }
                append("</triangles></mesh></object>\n")
            }
            append("</resources>\n<build>")
            meshes.indices.forEach { append("<item objectid=\"${it + 2}\"/>") }
            append("</build>\n</model>\n")
        }
        entry("3D/3dmodel.model", model)
        zip.finish()
    }
}

/** Every user-visible phrase used in exports, for translation coverage. */
val routeExportTexts = listOf(
    "Speed", "Engine speed", "Consumption", "Coolant", "Accelerator", "Boost", "Altitude", "Time into the trip",
    "Tap the route or move the slider to see what the car was doing there.", "The map needs an internet connection; the data below still works.",
    "Distance", "Duration", "Average speed (moving)", "Max speed", "Fuel used", "Average consumption", "Climb",
    "Save GPX for your GPS", "Made with OpenOBD", "Map data © OpenStreetMap contributors",
) + Units.texts
