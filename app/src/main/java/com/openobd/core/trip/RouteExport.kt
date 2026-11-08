package com.openobd.core.trip

import com.openobd.core.garage.TrackPoint
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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

    /**
     * A faithful smooth path in metres from the first point: GPS jitter closer than [minStepM] to the previous kept
     * point is dropped, then a centripetal Catmull-Rom curve passes through every kept point, sampled about every
     * [stepM]. Each sample carries the index of the GPS point its piece starts at (for speed colours).
     */
    fun smooth(points: List<TrackPoint>, minStepM: Double, stepM: Double): List<Triple<Double, Double, Int>> {
        val m = metres(points)
        if (m.size < 2) return m.mapIndexed { i, p -> Triple(p.first, p.second, i) }
        val keep = mutableListOf(0)
        for (i in 1 until m.size) {
            val (x0, y0) = m[keep.last()]
            if (sqrt((m[i].first - x0).let { it * it } + (m[i].second - y0).let { it * it }) >= minStepM) keep += i
        }
        if (keep.last() != m.lastIndex) { if (keep.size > 1) keep[keep.lastIndex] = m.lastIndex else keep += m.lastIndex }
        val p = keep.map { m[it] }
        if (p.size < 2) return listOf(Triple(m.first().first, m.first().second, 0), Triple(m.last().first, m.last().second, m.lastIndex))
        fun pt(i: Int): Pair<Double, Double> = when {
            i < 0 -> (2 * p[0].first - p[1].first) to (2 * p[0].second - p[1].second)
            i > p.lastIndex -> (2 * p.last().first - p[p.lastIndex - 1].first) to (2 * p.last().second - p[p.lastIndex - 1].second)
            else -> p[i]
        }
        fun knot(a: Pair<Double, Double>, b: Pair<Double, Double>) = sqrt(sqrt((b.first - a.first).let { it * it } + (b.second - a.second).let { it * it })).coerceAtLeast(1e-6)
        val out = mutableListOf<Triple<Double, Double, Int>>()
        for (i in 0 until p.lastIndex) {
            val p0 = pt(i - 1); val p1 = pt(i); val p2 = pt(i + 1); val p3 = pt(i + 2)
            val t0 = 0.0; val t1 = t0 + knot(p0, p1); val t2 = t1 + knot(p1, p2); val t3 = t2 + knot(p2, p3)
            val len = sqrt((p2.first - p1.first).let { it * it } + (p2.second - p1.second).let { it * it })
            val n = max(1, ceil(len / stepM).toInt())
            for (k in 0 until n) {
                val t = t1 + (t2 - t1) * k / n
                fun lerp(a: Pair<Double, Double>, b: Pair<Double, Double>, ta: Double, tb: Double) =
                    ((tb - t) / (tb - ta) * a.first + (t - ta) / (tb - ta) * b.first) to ((tb - t) / (tb - ta) * a.second + (t - ta) / (tb - ta) * b.second)
                val a1 = lerp(p0, p1, t0, t1); val a2 = lerp(p1, p2, t1, t2); val a3 = lerp(p2, p3, t2, t3)
                val b1 = lerp(a1, a2, t0, t2); val b2 = lerp(a2, a3, t1, t3)
                val c = lerp(b1, b2, t1, t2)
                out += Triple(c.first, c.second, keep[i])
            }
        }
        out += Triple(p.last().first, p.last().second, keep.last())
        return out
    }

    /** "40.74060° N, 4.05730° W" */
    fun coordinates(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.5f° %s, %.5f° %s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")

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
internal fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
internal fun f(v: Double, d: Int) = String.format(Locale.US, "%.${d}f", v)

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

/** Minimal PNG encoder (RGBA, 8 bits), so the core needs no imaging library. */
object Png {
    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        val raw = java.io.ByteArrayOutputStream(height * (1 + width * 4))
        for (y in 0 until height) {
            raw.write(1) // "Sub" filter: each byte minus the one to its left
            var pr = 0; var pg = 0; var pb = 0; var pa = 0
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF; val a = (p ushr 24) and 0xFF
                raw.write((r - pr) and 0xFF); raw.write((g - pg) and 0xFF); raw.write((b - pb) and 0xFF); raw.write((a - pa) and 0xFF)
                pr = r; pg = g; pb = b; pa = a
            }
        }
        val deflater = java.util.zip.Deflater(9)
        val idat = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(idat, deflater).use { it.write(raw.toByteArray()) }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            val len = data.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            val t = type.toByteArray(Charsets.US_ASCII)
            out.write(t); out.write(data)
            val crc = java.util.zip.CRC32().apply { update(t); update(data) }.value
            out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        }
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array()
        chunk("IHDR", ihdr)
        chunk("IDAT", idat.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
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
    /** A raster ([argb], [width] x [height] pixels) stretched over the rectangle; used for the relief. */
    class Image(val x: Double, val y: Double, val w: Double, val h: Double, val width: Int, val height: Int, val argb: IntArray) : Shape

    data class Poster(val widthMm: Double, val heightMm: Double, val shapes: List<Shape>)

    /**
     * [terrain]: shaded relief under the route (null = route only). [pxPerMm]: relief resolution (5 = 127 dpi, more
     * than the elevation data holds).
     */
    fun layout(
        info: RouteInfo, points: List<TrackPoint>, label: (String) -> String, units: Units = Units(), widthMm: Double = 297.0, heightMm: Double = 420.0,
        terrain: Terrain? = null, pxPerMm: Double = 5.0,
    ): Poster {
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
            // With relief, leave ground around the route.
            val fit = if (terrain != null) 0.86 else 1.0
            val scale = min(boxW / spanX, boxH / spanY) * fit // mm per metre
            val offX = margin + (boxW - spanX * scale) / 2; val offY = top + (boxH - spanY * scale) / 2
            fun xy(p: Pair<Double, Double>) = (offX + (p.first - minX) * scale) to (offY + (maxY - p.second) * scale)
            if (terrain != null) {
                val origin = simple.first()
                val kx = 111_320.0 * cos(Math.toRadians(origin.lat)); val ky = 110_540.0
                relief(terrain, boxW, boxH, pxPerMm) { mmX, mmY ->
                    // Box millimetres to the route's metres, then to latitude / longitude.
                    val xm = (margin + mmX - offX) / scale + minX; val ym = maxY - (top + mmY - offY) / scale
                    (origin.lat + ym / ky) to (origin.lon + xm / kx)
                }?.let { (w, h, px) ->
                    shapes += Image(margin, top, boxW, boxH, w, h, px)
                    shapes += Line(listOf(margin to top, margin + boxW to top, margin + boxW to top + boxH, margin to top + boxH, margin to top), "#D6D3CC", 0.3)
                }
            }
            val stroke = widthMm * 0.0065
            // A smooth curve through every GPS point (not straight lines between them), coloured by speed.
            val curve = RouteGeometry.smooth(points, max(3.0, 0.15 / scale), 0.25 / scale)
            var run = mutableListOf<Pair<Double, Double>>(); var band = -1
            for ((x, y, i) in curve) {
                val b = RouteGeometry.band(points[i].speedKmh)
                val pt = xy(x to y)
                if (b != band && run.isNotEmpty()) { run += pt; shapes += Line(run, RouteGeometry.bandColors[band], stroke); run = mutableListOf() }
                run += pt; band = b
            }
            if (run.size >= 2) shapes += Line(run, RouteGeometry.bandColors[band], stroke)
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
        if (shapes.any { it is Image }) shapes += Text(widthMm - margin, legendY + 5, label(Terrarium.ATTRIBUTION), widthMm * 0.011, muted, anchor = 2)
        return Poster(widthMm, heightMm, shapes)
    }

    /**
     * Shaded relief of the box: light from the north-west, a soft tint from low (sage) to high (sand), so the
     * coloured route stays the strongest thing on the page. Pixels without elevation are transparent.
     */
    private fun relief(terrain: Terrain, wMm: Double, hMm: Double, pxPerMm: Double, at: (Double, Double) -> Pair<Double, Double>): Triple<Int, Int, IntArray>? {
        val w = (wMm * pxPerMm).toInt().coerceIn(2, 2400); val h = (hMm * pxPerMm).toInt().coerceIn(2, 2400)
        // Elevation with a border for smoothing and slopes. Poster pixels are smaller than the elevation data's, so
        // the grid is smoothed and slopes are taken over a few pixels; otherwise the data's steps show as stripes.
        val bd = 4; val gw = w + 2 * bd; val gh = h + 2 * bd
        var e = DoubleArray(gw * gh)
        var missing = 0
        for (j in 0 until gh) for (i in 0 until gw) {
            val (lat, lon) = at((i - bd + 0.5) / pxPerMm, (j - bd + 0.5) / pxPerMm)
            val v = terrain.elevation(lat, lon)
            if (v == null) missing++
            e[j * gw + i] = v ?: Double.NaN
        }
        if (missing > e.size * 3 / 4) return null
        val known = e.filter { !it.isNaN() }
        val lo = known.min(); val hi = known.max(); val range = max(hi - lo, 1.0)
        val fill = known.average()
        val valid = BooleanArray(e.size) { !e[it].isNaN() }
        for (k in e.indices) if (!valid[k]) e[k] = fill
        val r = 2
        repeat(2) {
            val tmp = DoubleArray(e.size)
            for (j in 0 until gh) for (i in 0 until gw) { var sum = 0.0; var n = 0; for (d in -r..r) { val x = i + d; if (x in 0 until gw) { sum += e[j * gw + x]; n++ } }; tmp[j * gw + i] = sum / n }
            val out = DoubleArray(e.size)
            for (j in 0 until gh) for (i in 0 until gw) { var sum = 0.0; var n = 0; for (d in -r..r) { val y = j + d; if (y in 0 until gh) { sum += tmp[y * gw + i]; n++ } }; out[j * gw + i] = sum / n }
            e = out
        }
        // Metres per pixel, from two neighbouring pixels.
        val (la, loA) = at(0.0, 0.0); val (lb, loB) = at(1 / pxPerMm, 0.0)
        val cell = max(0.5, sqrt(((la - lb) * 110_540).let { it * it } + ((loA - loB) * 111_320 * cos(Math.toRadians(la))).let { it * it }))
        val zf = 1.6
        val light = doubleArrayOf(-0.5, 0.5, 0.7071)
        val low = intArrayOf(0xE6, 0xEC, 0xDD); val high = intArrayOf(0xEA, 0xDF, 0xCC)
        val px = IntArray(w * h)
        val st = 2
        for (j in 0 until h) for (i in 0 until w) {
            val gi = i + bd; val gj = j + bd
            if (!valid[gj * gw + gi]) continue
            val c = e[gj * gw + gi]
            val dx = (e[gj * gw + gi + st] - e[gj * gw + gi - st]) / (2 * st * cell) * zf
            val dy = (e[(gj - st) * gw + gi] - e[(gj + st) * gw + gi]) / (2 * st * cell) * zf
            val nl = sqrt(dx * dx + dy * dy + 1)
            val shade = ((-dx * light[0] - dy * light[1] + light[2]) / nl / light[2]).coerceIn(0.0, 1.35)
            val t = (c - lo) / range
            val k = 0.58 + 0.42 * min(shade, 1.0); val glow = (shade - 1).coerceAtLeast(0.0) * 0.6
            val rgb = IntArray(3) { ch ->
                val base = low[ch] + (high[ch] - low[ch]) * t
                (base * k + (255 - base * k) * glow).toInt().coerceIn(0, 255)
            }
            px[j * w + i] = (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        return Triple(w, h, px)
    }

    fun svg(p: Poster): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${f(p.widthMm, 0)}mm" height="${f(p.heightMm, 0)}mm" viewBox="0 0 ${f(p.widthMm, 2)} ${f(p.heightMm, 2)}" font-family="Helvetica, Arial, sans-serif">""").append('\n')
        for (s in p.shapes) when (s) {
            is Image -> append("""<image x="${f(s.x, 2)}" y="${f(s.y, 2)}" width="${f(s.w, 2)}" height="${f(s.h, 2)}" preserveAspectRatio="none" xlink:href="data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(Png.encode(s.width, s.height, s.argb))}"/>""")
            is Rect -> append("""<rect x="${f(s.x, 2)}" y="${f(s.y, 2)}" width="${f(s.w, 2)}" height="${f(s.h, 2)}" fill="${s.fill}"/>""")
            is Line -> append("""<polyline fill="none" stroke="${s.color}" stroke-width="${f(s.width, 2)}" stroke-linecap="round" stroke-linejoin="round" points="${s.points.joinToString(" ") { "${f(it.first, 2)},${f(it.second, 2)}" }}"/>""")
            is Circle -> append("""<circle cx="${f(s.x, 2)}" cy="${f(s.y, 2)}" r="${f(s.r, 2)}" fill="${s.fill}"${s.stroke?.let { " stroke=\"$it\" stroke-width=\"${f(s.strokeWidth, 2)}\"" } ?: ""}/>""")
            is Text -> append("""<text x="${f(s.x, 2)}" y="${f(s.y, 2)}" font-size="${f(s.size, 2)}" fill="${s.color}"${if (s.bold) " font-weight=\"bold\"" else ""}${when (s.anchor) { 1 -> " text-anchor=\"middle\""; 2 -> " text-anchor=\"end\""; else -> "" }}>${xml(s.text)}</text>""")
        }.also { append('\n') }
        append("</svg>\n")
    }
}

/** Every user-visible phrase used in exports, for translation coverage. */
val routeExportTexts = Route3mf.texts + listOf(Terrarium.ATTRIBUTION) + listOf(
    "Speed", "Engine speed", "Consumption", "Coolant", "Accelerator", "Boost", "Altitude", "Time into the trip",
    "Tap the route or move the slider to see what the car was doing there.", "The map needs an internet connection; the data below still works.",
    "Distance", "Duration", "Average speed (moving)", "Max speed", "Fuel used", "Average consumption", "Climb",
    "Save GPX for your GPS", "Made with OpenOBD", "Map data © OpenStreetMap contributors",
) + Units.texts
