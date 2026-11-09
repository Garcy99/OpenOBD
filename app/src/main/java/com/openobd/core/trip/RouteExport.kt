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
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

    /** Distance (km from the start) and height (m) at every point: from [terrain] when given, else the GPS. */
    class Profile(val km: DoubleArray, val elevation: DoubleArray) {
        val min: Double get() = elevation.min()
        val max: Double get() = elevation.max()
    }

    /**
     * The route's height profile, or null without heights for most points. GPS heights are smoothed (they jump by
     * several metres); the terrain's are already smooth.
     */
    fun profile(points: List<TrackPoint>, terrain: Terrain? = null): Profile? {
        if (points.size < 2) return null
        val raw = points.map { p -> terrain?.elevation(p.lat, p.lon) ?: p.alt?.toDouble() }
        if (raw.count { it != null } < points.size / 2 + 1) return null
        // Fill gaps with the nearest known height.
        val filled = DoubleArray(raw.size)
        var last = raw.firstNotNullOf { it }
        for (i in raw.indices) { raw[i]?.let { last = it }; filled[i] = last }
        val smooth = if (terrain != null) filled else DoubleArray(filled.size) { i ->
            val a = max(0, i - 3); val b = min(filled.size - 1, i + 3); (a..b).sumOf { filled[it] } / (b - a + 1)
        }
        val km = DoubleArray(points.size)
        val m = metres(points)
        for (i in 1 until points.size) km[i] = km[i - 1] + sqrt((m[i].first - m[i - 1].first).let { it * it } + (m[i].second - m[i - 1].second).let { it * it }) / 1000
        return Profile(km, smooth)
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
    /**
     * [terrain]: also embeds a coloured relief of the area as a map layer that works without internet ([water]: lakes
     * on it). The map also offers aerial photos (online; shown first with [satelliteFirst]). [image]: how the relief
     * is embedded (a data: URI).
     */
    fun write(
        info: RouteInfo, points: List<TrackPoint>, label: (String) -> String, units: Units = Units(), terrain: Terrain? = null,
        water: List<List<LatLon>> = emptyList(), image: (Int, Int, IntArray) -> String = Png::dataUri, satelliteFirst: Boolean = false,
    ): String {
        val relief = terrain?.let { reliefLayer(points, it, water, image) } ?: "null"
        val photos = points.firstOrNull()?.let { Imagery.sourceFor(it.lat, it.lon) } ?: Imagery.Source.ESRI
        val gpx = java.util.Base64.getEncoder().encodeToString(Gpx.write(info, points).toByteArray(Charsets.UTF_8))
        fun n(v: Float?, d: Int) = v?.let { f(it.toDouble(), d) } ?: "null"
        val data = points.joinToString(",", "[", "]") { p ->
            "[${f(p.lat, 6)},${f(p.lon, 6)},${(p.t - info.start) / 1000},${n(p.speedKmh, 1)},${n(p.rpm, 0)},${n(p.l100, 1)},${n(p.coolantC, 0)},${n(p.pedal, 0)},${n(p.boostBar, 2)},${n(p.alt, 0)}]"
        }
        // Heights for the profile under the slider: the terrain's when there is one (smoother and truer than GPS).
        val prof = RouteGeometry.profile(points, terrain)
        val profJs = prof?.let { p -> p.km.indices.joinToString(",", "[", "]") { i -> "[${f(p.km[i], 3)},${f(p.elevation[i], 1)}]" } } ?: "null"
        val js = { s: String -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c").replace("\n", " ") + "\"" }
        val labels = listOf(
            "speed" to "Speed", "rpm" to "Engine speed", "cons" to "Consumption", "coolant" to "Coolant", "pedal" to "Accelerator",
            "boost" to "Boost", "alt" to "Altitude", "time" to "Time into the trip", "tap" to "Tap the route or move the slider to see what the car was doing there.",
            "offline" to "The map needs an internet connection; the data below still works.",
            "map" to "Map", "relief" to "Relief", "elev" to (terrain?.attribution ?: Terrarium.ATTRIBUTION), "sat" to "Satellite", "photos" to photos.attribution,
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
<div class="card"><div id="hint"></div><canvas id="prof" height="150" style="width:100%;height:150px;display:none;cursor:pointer"></canvas><input id="slider" type="range" min="0" max="${max(0, points.size - 1)}" value="0" aria-label="Position"><div id="pt"></div></div>
<div class="card"><div class="stats">$stats</div></div>
<p><a class="btn" download="${xml(fileName(info))}.gpx" href="data:application/gpx+xml;base64,$gpx">${xml(label("Save GPX for your GPS"))}</a></p>
<footer>${xml(label("Made with OpenOBD"))} · ${xml(label("Map data © OpenStreetMap contributors"))}</footer>
</main>
<script>
const D=$data,PR=$profJs,R=$relief,L$=$labels,C=${RouteGeometry.bandColors.joinToString(",", "[", "]") { "\"$it\"" }},U=${units.js()};
function band(s){return s==null?3:s<=50?0:s<=100?1:2}
function fmtT(t){const h=Math.floor(t/3600),m=Math.floor(t%3600/60),s=t%60;return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(s).padStart(2,'0')}
function row(k,v){return v==null?'':'<div class="s"><span>'+k+'</span><b>'+v+'</b></div>'}
const slider=document.getElementById('slider'),pt=document.getElementById('pt');document.getElementById('hint').textContent=L$.tap;
let map=null,marker=null;
function show(i){const p=D[i];if(!p)return;slider.value=i;
pt.innerHTML=row(L$.time,fmtT(p[2]))+row(L$.speed,p[3]==null?null:U.speed(p[3]))+row(L$.rpm,p[4]==null?null:p[4]+' rpm')+row(L$.cons,p[5]==null?null:U.cons(p[5]))
+row(L$.coolant,p[6]==null?null:U.temp(p[6]))+row(L$.pedal,p[7]==null?null:p[7]+' %')+row(L$.boost,p[8]==null?null:U.boost(p[8]))+row(L$.alt,p[9]==null?null:U.height(p[9]));
if(map){marker.setLatLng([p[0],p[1]]);}drawProf(i)}
// Height profile with the speed on top (colour of each stretch), and where the slider is.
const cv=document.getElementById('prof');if(PR){cv.style.display='block'}
function drawProf(sel){if(!PR)return;const r=window.devicePixelRatio||1,W=cv.clientWidth,H=150;cv.width=W*r;cv.height=H*r;const g=cv.getContext('2d');g.scale(r,r);g.clearRect(0,0,W,H);
const tot=PR[PR.length-1][0]||1;let lo=1e9,hi=-1e9;for(const q of PR){lo=Math.min(lo,q[1]);hi=Math.max(hi,q[1])}const sp=Math.max(hi-lo,10),pl=46,pb=18,ph=H-pb-8;
const X=k=>pl+(W-pl-6)*k/tot,Y=e=>8+ph-ph*(e-lo)/sp;const cs=getComputedStyle(document.documentElement);
g.beginPath();g.moveTo(X(0),8+ph);for(const q of PR)g.lineTo(X(q[0]),Y(q[1]));g.lineTo(X(tot),8+ph);g.closePath();g.fillStyle='rgba(140,120,90,.18)';g.fill();
g.lineWidth=2.5;for(let i=1;i<PR.length;i++){g.strokeStyle=C[band(D[i][3])];g.beginPath();g.moveTo(X(PR[i-1][0]),Y(PR[i-1][1]));g.lineTo(X(PR[i][0]),Y(PR[i][1]));g.stroke()}
g.fillStyle=cs.getPropertyValue('--muted');g.font='12px system-ui';g.textAlign='right';g.fillText(U.height(hi),pl-6,14);g.fillText(U.height(lo),pl-6,8+ph);
g.textAlign='left';g.fillText(L$.alt,pl,H-3);g.textAlign='right';g.fillText(U.dist(tot),W-6,H-3);
if(sel!=null&&PR[sel]){const x=X(PR[sel][0]);g.strokeStyle=cs.getPropertyValue('--ink');g.lineWidth=1;g.beginPath();g.moveTo(x,8);g.lineTo(x,8+ph);g.stroke();
g.fillStyle=cs.getPropertyValue('--ink');g.beginPath();g.arc(x,Y(PR[sel][1]),4,0,7);g.fill()}}
cv.addEventListener('click',e=>{if(!PR)return;const b=cv.getBoundingClientRect(),W=b.width,pl=46,tot=PR[PR.length-1][0]||1,k=(e.clientX-b.left-pl)/(W-pl-6)*tot;
let best=0;for(let i=0;i<PR.length;i++)if(Math.abs(PR[i][0]-k)<Math.abs(PR[best][0]-k))best=i;show(best)});
window.addEventListener('resize',()=>drawProf(+slider.value));
slider.addEventListener('input',e=>show(+e.target.value));
function nearest(lat,lon){let b=0,d=1e18;const k=Math.cos(lat*Math.PI/180);for(let i=0;i<D.length;i++){const x=(D[i][1]-lon)*k,y=D[i][0]-lat,q=x*x+y*y;if(q<d){d=q;b=i}}return b}
function init(){if(typeof L==='undefined'){document.getElementById('map').innerHTML='<p style="padding:16px">'+L$.offline+'</p>';show(0);return}
map=L.map('map');
// Map tiles that allow files opened from the phone (no web address to send as referrer, which OpenStreetMap's own
// servers require): OpenTopoMap, then Esri, then the relief embedded in this file.
const P=[['https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',{subdomains:'abc',maxZoom:17,attribution:'Map data &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors, SRTM | Map style &copy; <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)'}],
['https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'Tiles &copy; Esri'}]];
const relief=R?L.imageOverlay(R.url,R.b,{attribution:L$.elev}):null;let tiles=null,ctl=null;
// Aerial photos, down to single trees: the national orthophoto where there is one, Esri elsewhere.
const sat=L.tileLayer('${photos.leafletUrl}',{maxZoom:20,maxNativeZoom:${photos.maxZoom},attribution:L$.photos});
function setCtl(){if(ctl)map.removeControl(ctl);const base={};if(tiles)base[L$.map]=tiles;base[L$.sat]=sat;if(relief)base[L$.relief]=relief;ctl=L.control.layers(base,null,{collapsed:false}).addTo(map)}
function useTiles(k){if(tiles)map.removeLayer(tiles);tiles=null;if(k>=P.length){(relief||sat).addTo(map);setCtl();return}
let errors=0;tiles=L.tileLayer(P[k][0],P[k][1]);tiles.on('tileerror',()=>{if(++errors===3)useTiles(k+1)});tiles.addTo(map);setCtl()}
if(${satelliteFirst}){tiles=L.tileLayer(P[0][0],P[0][1]);sat.addTo(map);setCtl()}else useTiles(0);
L.polyline(D.map(p=>[p[0],p[1]]),{color:'#fff',weight:10,opacity:.9}).addTo(map);
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

    /** The coloured relief around the route as a Leaflet image layer: {url, b: [[south, west], [north, east]]}. */
    private fun reliefLayer(points: List<TrackPoint>, terrain: Terrain, water: List<List<LatLon>>, image: (Int, Int, IntArray) -> String, longest: Int = 2400): String? {
        if (points.size < 2) return null
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val pad = max(max(maxLat - minLat, (maxLon - minLon) * k) * 0.3, 0.005)
        val s = minLat - pad; val n = maxLat + pad
        // At least as wide as a 16:10 map view, so its sides are not left bare.
        val halfW = max((maxLon - minLon) / 2 + pad / k, (n - s) * 0.8 / k)
        val w = (minLon + maxLon) / 2 - halfW; val e = (minLon + maxLon) / 2 + halfW
        // Web Mercator rows, as the map draws the image.
        val yTop = Terrarium.tileY(n, 0); val yBottom = Terrarium.tileY(s, 0)
        val aspect = (yBottom - yTop) / (Terrarium.tileX(e, 0) - Terrarium.tileX(w, 0))
        val pw = if (aspect <= 1) longest else (longest / aspect).toInt(); val ph = if (aspect <= 1) (longest * aspect).toInt() else longest
        val (iw, ih, px) = RoutePoster.relief(terrain, pw.toDouble(), ph.toDouble(), 1.0, water) { x, y ->
            Terrarium.lat(yTop + (yBottom - yTop) * y / ph, 0) to (w + (e - w) * x / pw)
        } ?: return null
        return "{url:\"${image(iw, ih, px)}\",b:[[${f(s, 6)},${f(w, 6)}],[${f(n, 6)},${f(e, 6)}]]}"
    }
}

/** Minimal PNG encoder (RGBA, 8 bits), so the core needs no imaging library. */
object Png {
    fun dataUri(width: Int, height: Int, argb: IntArray): String =
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(encode(width, height, argb))

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
        val deflater = java.util.zip.Deflater(6)
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
    fun js(): String = if (imperial) "{speed:v=>(v*0.621371).toFixed(0)+' mph',cons:v=>(235.215/v).toFixed(1)+' mpg',temp:v=>(v*9/5+32).toFixed(0)+' °F',boost:v=>(v*14.5038).toFixed(1)+' psi',height:v=>(v*3.28084).toFixed(0)+' ft',dist:v=>(v*0.621371).toFixed(1)+' mi'}"
        else "{speed:v=>v.toFixed(0)+' km/h',cons:v=>v.toFixed(1)+' L/100 km',temp:v=>v.toFixed(0)+' °C',boost:v=>v.toFixed(2)+' bar',height:v=>v.toFixed(0)+' m',dist:v=>v.toFixed(1)+' km'}"

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
    data class Line(val points: List<Pair<Double, Double>>, val color: String, val width: Double, val opacity: Double = 1.0) : Shape
    data class Circle(val x: Double, val y: Double, val r: Double, val fill: String, val stroke: String?, val strokeWidth: Double = 0.0) : Shape
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double, val fill: String) : Shape
    /** [anchor]: 0 start, 1 middle, 2 end; [y] is the baseline. */
    data class Text(val x: Double, val y: Double, val text: String, val size: Double, val color: String, val bold: Boolean = false, val anchor: Int = 0) : Shape
    /** A filled shape (the area under the height profile). */
    data class Polygon(val points: List<Pair<Double, Double>>, val fill: String) : Shape
    /** A raster ([argb], [width] x [height] pixels) stretched over the rectangle; used for the relief. */
    class Image(val x: Double, val y: Double, val w: Double, val h: Double, val width: Int, val height: Int, val argb: IntArray) : Shape

    data class Poster(val widthMm: Double, val heightMm: Double, val shapes: List<Shape>)

    /** Where the route's map goes on the page: the box (millimetres) and how the route's metres map into it. */
    class Frame(
        val margin: Double, val top: Double, val boxW: Double, val boxH: Double, val bottom: Double,
        val scale: Double, val offX: Double, val offY: Double, val minX: Double, val maxY: Double, val origin: TrackPoint,
    ) {
        private val kx = 111_320.0 * cos(Math.toRadians(origin.lat)); private val ky = 110_540.0
        /** Box millimetres to latitude / longitude. */
        fun latLon(mmX: Double, mmY: Double): Pair<Double, Double> {
            val xm = (margin + mmX - offX) / scale + minX; val ym = maxY - (top + mmY - offY) / scale
            return (origin.lat + ym / ky) to (origin.lon + xm / kx)
        }
        /** The box as minLat, minLon, maxLat, maxLon. */
        fun area(): DoubleArray { val (n, w) = latLon(0.0, 0.0); val (s, e) = latLon(boxW, boxH); return doubleArrayOf(s, w, n, e) }
        /** Ground metres per millimetre of paper. */
        val metresPerMm: Double get() = 1 / scale
    }

    /** The map box for [points] on a page of [widthMm] x [heightMm] ([ground]: leave ground around the route). */
    fun frame(points: List<TrackPoint>, widthMm: Double = 297.0, heightMm: Double = 420.0, ground: Boolean = true, profile: Boolean = false): Frame? {
        val margin = widthMm * 0.08
        val top = margin + 34; val statsH = heightMm * 0.2; val bottom = heightMm - margin - statsH - (if (profile) profileBlock(heightMm) else 0.0)
        val boxW = widthMm - 2 * margin; val boxH = bottom - top
        val simple = RouteGeometry.simplify(points, 2.0)
        val m = RouteGeometry.metres(simple)
        if (m.size < 2) return null
        val minX = m.minOf { it.first }; val maxX = m.maxOf { it.first }; val minY = m.minOf { it.second }; val maxY = m.maxOf { it.second }
        val spanX = max(maxX - minX, 1.0); val spanY = max(maxY - minY, 1.0)
        val scale = min(boxW / spanX, boxH / spanY) * (if (ground) 0.86 else 1.0) // mm per metre
        val offX = margin + (boxW - spanX * scale) / 2; val offY = top + (boxH - spanY * scale) / 2
        return Frame(margin, top, boxW, boxH, bottom, scale, offX, offY, minX, maxY, simple.first())
    }

    private const val INK = "#16181D"
    private const val MUTED = "#6B7280"

    /** The relief or aerial photo under the map box, with its frame line and credits. */
    private fun ground(
        fr: Frame, terrain: Terrain?, imagery: TileImagery?, water: List<List<LatLon>>, pxPerMm: Double?, shapes: MutableList<Shape>, credits: MutableList<String>,
    ) {
        val image = imagery?.let { im -> photo(im, fr.boxW, fr.boxH, pxPerMm ?: PHOTO_PX_PER_MM, fr::latLon)?.also { credits += im.attributions } }
            ?: terrain?.let { t ->
                relief(t, fr.boxW, fr.boxH, pxPerMm ?: RELIEF_PX_PER_MM, water, at = fr::latLon)?.also {
                    credits += t.attribution; if (water.isNotEmpty()) credits += Overpass.ATTRIBUTION
                }
            }
        image?.let { (w, h, px) ->
            shapes += Image(fr.margin, fr.top, fr.boxW, fr.boxH, w, h, px)
            shapes += Line(listOf(fr.margin to fr.top, fr.margin + fr.boxW to fr.top, fr.margin + fr.boxW to fr.top + fr.boxH, fr.margin to fr.top + fr.boxH, fr.margin to fr.top), "#D6D3CC", 0.3)
        }
    }

    /** Scale bar (a round distance close to a fifth of the box) and north arrow under the map. */
    private fun scaleAndNorth(fr: Frame, units: Units, widthMm: Double, shapes: MutableList<Shape>) {
        val margin = fr.margin; val scale = fr.scale; val bottom = fr.bottom
        val target = fr.boxW / 5 / scale
        val nice = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0, 100000.0, 200000.0, 500000.0)
            .let { list -> if (units.imperial) list.map { it * 1.609344 } else list }.lastOrNull { it <= target } ?: 10.0
        val barY = bottom + 6
        shapes += Line(listOf(margin to barY, margin + nice * scale to barY), INK, 0.6)
        shapes += Line(listOf(margin to barY - 1.5, margin to barY + 1.5), INK, 0.6)
        shapes += Line(listOf(margin + nice * scale to barY - 1.5, margin + nice * scale to barY + 1.5), INK, 0.6)
        val niceLabel = if (units.imperial) (nice / 1609.344).let { if (it >= 1) f(it, 0) + " mi" else f(it * 5280, 0) + " ft" }
            else if (nice >= 1000) f(nice / 1000, 0) + " km" else f(nice, 0) + " m"
        shapes += Text(margin + nice * scale + 3, barY + 1.5, niceLabel, widthMm * 0.018, MUTED)
        val nx = widthMm - margin - 4; val ny = bottom + 2
        shapes += Line(listOf(nx to ny + 6, nx to ny - 3), INK, 0.6)
        shapes += Line(listOf(nx - 2.2 to ny, nx to ny - 3.5, nx + 2.2 to ny), INK, 0.6)
        shapes += Text(nx, ny - 5.5, "N", widthMm * 0.02, INK, bold = true, anchor = 1)
    }

    private fun simplifyAll(tracks: List<List<TrackPoint>>) = tracks.filter { it.size >= 2 }.map { RouteGeometry.simplify(it, 5.0) }

    /** The map box of [layoutMany] (to download the photos of exactly that area). */
    fun frameMany(tracks: List<List<TrackPoint>>, widthMm: Double = 297.0, heightMm: Double = 420.0): Frame? =
        frame(simplifyAll(tracks).flatten(), widthMm, heightMm, true)

    /**
     * Every trip of a period on one poster: the routes over the relief or aerial photos, drawn see-through so the
     * roads driven most stand out, with the period's totals ([stats]: label, value).
     */
    fun layoutMany(
        title: String, subtitle: String, tracks: List<List<TrackPoint>>, stats: List<Pair<String, String>>, label: (String) -> String,
        units: Units = Units(), widthMm: Double = 297.0, heightMm: Double = 420.0,
        terrain: Terrain? = null, pxPerMm: Double? = null, imagery: TileImagery? = null, water: List<List<LatLon>> = emptyList(),
    ): Poster {
        val shapes = mutableListOf<Shape>(Rect(0.0, 0.0, widthMm, heightMm, "#FFFFFF"))
        val margin = widthMm * 0.08
        shapes += Text(margin, margin + 12, title, widthMm * 0.055, INK, bold = true)
        shapes += Text(margin, margin + 22, subtitle, widthMm * 0.024, MUTED)
        val simple = simplifyAll(tracks)
        val fr = frame(simple.flatten(), widthMm, heightMm, terrain != null || imagery != null)
        val credits = mutableListOf<String>()
        val bottom = fr?.bottom ?: (heightMm - margin - heightMm * 0.2)
        if (fr != null) {
            ground(fr, terrain, imagery, water, pxPerMm, shapes, credits)
            val photo = imagery != null && shapes.any { it is Image }
            val color = if (photo) "#FFB020" else "#E4572E"
            val stroke = widthMm * 0.0045
            val origin = fr.origin
            val kx = 111_320.0 * cos(Math.toRadians(origin.lat)); val ky = 110_540.0
            fun xy(p: TrackPoint): Pair<Double, Double> {
                val xm = (p.lon - origin.lon) * kx; val ym = (p.lat - origin.lat) * ky
                return (fr.offX + (xm - fr.minX) * fr.scale) to (fr.offY + (fr.maxY - ym) * fr.scale)
            }
            for (t in simple) shapes += Line(t.map(::xy), color, stroke, opacity = if (simple.size > 1) 0.55 else 1.0)
            scaleAndNorth(fr, units, widthMm, shapes)
        }
        val cols = 3; val colW = (widthMm - 2 * margin) / cols
        stats.forEachIndexed { i, (k, v) ->
            val x = margin + (i % cols) * colW; val y = bottom + 20 + (i / cols) * widthMm * 0.08
            shapes += Text(x, y, k, widthMm * 0.017, MUTED)
            shapes += Text(x, y + widthMm * 0.035, v, widthMm * 0.03, INK, bold = true)
        }
        val legendY = heightMm - margin + 6
        shapes += Text(widthMm - margin, legendY, "OpenOBD", widthMm * 0.017, MUTED, anchor = 2)
        credits.forEachIndexed { i, c -> shapes += Text(widthMm - margin, legendY + 5 + i * 4, label(c), widthMm * 0.011, MUTED, anchor = 2) }
        return Poster(widthMm, heightMm, shapes)
    }

    /** Room for the height profile under the map (with its gap). */
    private fun profileBlock(heightMm: Double) = heightMm * 0.075 + 14

    /** Whether the poster will carry a height profile (GPS heights, or the terrain's): reserves room for it. */
    fun hasProfile(points: List<TrackPoint>, terrain: Terrain? = null) = RouteGeometry.profile(points, terrain) != null

    /** Resolution of the relief (8 px/mm = 203 dpi) and of the aerial photo (10 px/mm = 254 dpi). */
    const val RELIEF_PX_PER_MM = 8.0
    const val PHOTO_PX_PER_MM = 10.0

    /**
     * [terrain]: shaded relief under the route; [imagery]: aerial photo instead (null for both = route only).
     * [water]: lakes drawn on the relief. [pxPerMm]: resolution of the relief or photo.
     */
    fun layout(
        info: RouteInfo, points: List<TrackPoint>, label: (String) -> String, units: Units = Units(), widthMm: Double = 297.0, heightMm: Double = 420.0,
        terrain: Terrain? = null, pxPerMm: Double? = null, imagery: TileImagery? = null, water: List<List<LatLon>> = emptyList(),
    ): Poster {
        val ink = "#16181D"; val muted = "#6B7280"
        val shapes = mutableListOf<Shape>(Rect(0.0, 0.0, widthMm, heightMm, "#FFFFFF"))
        val margin = widthMm * 0.08
        shapes += Text(margin, margin + 12, info.title, widthMm * 0.055, ink, bold = true)
        val date = SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.getDefault()).format(Date(info.start))
        shapes += Text(margin, margin + 22, listOf(info.car, date).filter { it.isNotBlank() }.joinToString("  ·  "), widthMm * 0.024, muted)

        val ground = terrain != null || imagery != null
        val prof = RouteGeometry.profile(points, terrain)
        val fr = frame(points, widthMm, heightMm, ground, prof != null)
        val credits = mutableListOf<String>()
        val bottom = fr?.bottom ?: (heightMm - margin - heightMm * 0.2)
        if (fr != null) {
            val m = RouteGeometry.metres(RouteGeometry.simplify(points, 2.0))
            val top = fr.top; val boxW = fr.boxW; val boxH = fr.boxH; val scale = fr.scale
            fun xy(p: Pair<Double, Double>) = (fr.offX + (p.first - fr.minX) * scale) to (fr.offY + (fr.maxY - p.second) * scale)
            ground(fr, terrain, imagery, water, pxPerMm, shapes, credits)
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
            // On the coloured relief, a white edge keeps the route readable over greens and browns.
            if (shapes.any { it is Image }) {
                val casing = Line(curve.map { (x, y, _) -> xy(x to y) }, "#FFFFFF", stroke * 1.9)
                shapes.add(shapes.indexOfFirst { it is Line && it.color != "#D6D3CC" && it.width == stroke }.coerceAtLeast(0), casing)
            }
            val s = xy(m.first()); val e = xy(m.last())
            shapes += Circle(s.first, s.second, stroke * 1.6, "#16A34A", "#FFFFFF", stroke * 0.5)
            shapes += Circle(e.first, e.second, stroke * 1.6, ink, "#FFFFFF", stroke * 0.5)
            scaleAndNorth(fr, units, widthMm, shapes)
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
        if (prof != null && fr != null) {
            // Height profile: the area under the heights, its top edge coloured by speed like the route.
            val x0 = margin; val w = fr.boxW; val y0 = bottom + 14; val h = heightMm * 0.075
            val total = max(prof.km.last(), 1e-6)
            val lo = prof.min; val span = max(prof.max - lo, 10.0)
            fun px(i: Int) = (x0 + w * prof.km[i] / total) to (y0 + h - h * (prof.elevation[i] - lo) / span * 0.9)
            val step = max(1, points.size / 600)
            val idx = (points.indices step step).toMutableList().also { if (it.last() != points.lastIndex) it += points.lastIndex }
            shapes += Polygon(listOf(x0 to y0 + h) + idx.map(::px) + listOf((x0 + w) to y0 + h), "#ECE7DD")
            var run = mutableListOf<Pair<Double, Double>>(); var band = -1
            for (i in idx) {
                val b = RouteGeometry.band(points[i].speedKmh); val pt = px(i)
                if (b != band && run.isNotEmpty()) { run += pt; shapes += Line(run, RouteGeometry.bandColors[band], 0.7); run = mutableListOf() }
                run += pt; band = b
            }
            if (run.size >= 2) shapes += Line(run, RouteGeometry.bandColors[band], 0.7)
            shapes += Line(listOf(x0 to y0 + h, (x0 + w) to y0 + h), "#D6D3CC", 0.3)
            val small = widthMm * 0.013
            shapes += Text(x0 - 2, y0 + h * 0.1 + small / 2, units.height(prof.max), small, muted, anchor = 2)
            shapes += Text(x0 - 2, y0 + h, units.height(lo), small, muted, anchor = 2)
            shapes += Text(x0 + w, y0 + h + small + 1.5, units.distance(prof.km.last()), small, muted, anchor = 2)
            shapes += Text(x0, y0 + h + small + 1.5, label("Altitude") + " · " + label("colour: speed"), small, muted)
        }
        val cols = 3; val colW = (widthMm - 2 * margin) / cols
        val statsTop = bottom + 20 + (if (prof != null && fr != null) profileBlock(heightMm) else 0.0)
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
        credits.forEachIndexed { i, c -> shapes += Text(widthMm - margin, legendY + 5 + i * 4, label(c), widthMm * 0.011, muted, anchor = 2) }
        return Poster(widthMm, heightMm, shapes)
    }

    /**
     * Shaded relief of the box, like a printed relief map: light from the north-west (with softer light from the
     * west and north so no slope goes flat), ridges and valleys sharpened, colours by altitude (greens in the
     * lowlands, sand and browns on the slopes, pale peaks), contour lines, and lakes and the sea in blue. The
     * colours follow the share of the area at each height, so flat and mountainous areas both show the whole range.
     * Pixels without elevation are transparent. [at]: box millimetres to latitude / longitude, where latitude depends
     * only on the row and longitude only on the column.
     */
    internal fun relief(
        terrain: Terrain, wMm: Double, hMm: Double, pxPerMm: Double, water: List<List<LatLon>> = emptyList(), contours: Boolean = true,
        at: (Double, Double) -> Pair<Double, Double>,
    ): Triple<Int, Int, IntArray>? {
        val w = (wMm * pxPerMm).toInt().coerceIn(2, MAX_PX); val h = (hMm * pxPerMm).toInt().coerceIn(2, MAX_PX)
        // Metres per pixel, from two neighbouring pixels.
        val (la, loA) = at(0.0, 0.0); val (lb, loB) = at(1 / pxPerMm, 0.0)
        val cell = max(0.05, sqrt(((la - lb) * 110_540).let { it * it } + ((loA - loB) * 111_320 * cos(Math.toRadians(la))).let { it * it }))
        // The elevation data's samples are usually bigger than the poster's pixels: smooth over about one sample so
        // the steps between them do not show as facets, and no more, to keep every ridge the data has.
        val dataPx = (terrain.cellMetres(la) ?: cell) / cell
        val r = max(1, (dataPx * 0.5).roundToInt())
        val bd = 2 * r + 2; val gw = w + 2 * bd; val gh = h + 2 * bd
        val e = FloatArray(gw * gh)
        var missing = 0
        for (j in 0 until gh) for (i in 0 until gw) {
            val (lat, lon) = at((i - bd + 0.5) / pxPerMm, (j - bd + 0.5) / pxPerMm)
            val v = terrain.elevation(lat, lon)
            if (v == null) missing++
            e[j * gw + i] = v?.toFloat() ?: Float.NaN
        }
        if (missing > e.size * 3 / 4) return null
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; var sum = 0.0; var n = 0
        for (v in e) if (!v.isNaN()) { lo = min(lo, v.toDouble()); hi = max(hi, v.toDouble()); sum += v; n++ }
        val range = max(hi - lo, 1.0); val fill = (sum / n).toFloat()
        val valid = BooleanArray(e.size) { !e[it].isNaN() }
        val sea = if (lo <= 0.0) BooleanArray(e.size) { valid[it] && e[it] <= 0f } else null
        // Height -> 0..1, half by rank in the area and half by plain height, from a sample of the grid.
        val sample = DoubleArray((n + 6) / 7).let { a -> var k = 0; var c = 0; for (v in e) if (!v.isNaN()) { if (c++ % 7 == 0 && k < a.size) a[k++] = v.toDouble() }; a.copyOf(k) }.also { it.sort() }
        fun level(v: Double): Double {
            var a = 0; var b = sample.size
            while (a < b) { val m = (a + b) / 2; if (sample[m] < v) a = m + 1 else b = m }
            return 0.5 * a / max(1, sample.size) + 0.5 * (v - lo) / range
        }
        for (k in e.indices) if (!valid[k]) e[k] = fill
        repeat(2) { boxBlur(e, gw, gh, r) }
        // The land's shape at a larger scale: what is above it is a ridge, below it a valley.
        val big = e.copyOf(); val rBig = max(4 * r, 8)
        repeat(3) { boxBlur(big, gw, gh, rBig) }
        var dev = 0.0; var dn = 0
        for (k in e.indices step 11) { val d = e[k] - big[k]; dev += d * d; dn++ }
        val sdLocal = max(sqrt(dev / max(1, dn)), 0.5)
        val sharpen = 0.8f
        fun surf(k: Int) = e[k] + sharpen * (e[k] - big[k])

        val zf = 1.3 + 0.35 * log10(max(cell, 1.0))
        val lz = 0.7071
        // Light from the north-west, west and north (altitude 45°): x east, y north.
        val lights = arrayOf(doubleArrayOf(-0.5, 0.5, 0.55), doubleArrayOf(-0.7071, 0.0, 0.25), doubleArrayOf(0.0, 0.7071, 0.2))
        val ramp = listOf(0.0 to 0x7FAE72, 0.22 to 0xA6C98B, 0.42 to 0xD5DCA4, 0.6 to 0xE6D2A0, 0.76 to 0xC9A37C, 0.9 to 0xAE9481, 1.0 to 0xF3F0EA)
        val tintR = IntArray(3)
        fun tint(t: Double) {
            val k = ramp.indexOfLast { it.first <= t }.coerceIn(0, ramp.size - 2)
            val (t0, c0) = ramp[k]; val (t1, c1) = ramp[k + 1]
            val u = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
            for (ch in 0 until 3) { val sh = 16 - 8 * ch; tintR[ch] = ((c0 shr sh and 0xFF) * (1 - u) + (c1 shr sh and 0xFF) * u).toInt() }
        }
        // Contours: a round interval giving about 15-30 lines over the area, every fifth one stronger.
        val interval = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1000.0).firstOrNull { range / it <= 30 } ?: 2000.0
        val lake = waterCover(water, w, h, pxPerMm, at)
        val px = IntArray(w * h)
        val rgb = DoubleArray(3)
        for (j in 0 until h) for (i in 0 until w) {
            val k = (j + bd) * gw + i + bd
            if (!valid[k]) continue
            val c = e[k].toDouble()
            if (sea != null && sea[k]) { px[j * w + i] = WATER; continue }
            val dx = (surf(k + 1) - surf(k - 1)) / (2 * cell) * zf
            val dy = (surf(k - gw) - surf(k + gw)) / (2 * cell) * zf
            val nl = sqrt(dx * dx + dy * dy + 1)
            var shade = 0.0
            for (l in lights) shade += l[2] * (-dx * l[0] - dy * l[1] + lz) / nl / lz
            shade = shade.coerceIn(0.0, 1.35)
            tint(level(c))
            // Valleys a little darker, ridges a little lighter, for depth.
            val depth = ((e[k] - big[k]) / (2 * sdLocal)).coerceIn(-1.0, 1.0)
            val kk = (0.52 + 0.48 * min(shade, 1.0)) * (1 + 0.08 * depth); val glow = (shade - 1).coerceAtLeast(0.0) * 0.5
            for (ch in 0 until 3) rgb[ch] = tintR[ch] * kk + (255 - tintR[ch] * kk) * glow
            if (contours) {
                val g = max(sqrt(((e[k + 1] - e[k - 1]) / 2.0).let { it * it } + ((e[k + gw] - e[k - gw]) / 2.0).let { it * it }), 1e-6)
                val f = c / interval; val nearest = Math.round(f)
                val dpx = abs(f - nearest) * interval / g
                val index = nearest % 5 == 0L
                val spacing = interval / g * (if (index) 5 else 1)
                val half = (if (index) 0.09 else 0.05) * pxPerMm
                val cover = (half + 0.5 - dpx).coerceIn(0.0, 1.0) * ((spacing - 2.5) / 3).coerceIn(0.0, 1.0)
                val alpha = cover * (if (index) 0.5 else 0.28)
                if (alpha > 0) for (ch in 0 until 3) rgb[ch] = rgb[ch] * (1 - alpha) + CONTOUR[ch] * alpha
            }
            val wa = lake?.get(j * w + i)?.toDouble() ?: 0.0
            if (wa > 0) for (ch in 0 until 3) { val wc = (WATER shr (16 - 8 * ch)) and 0xFF; rgb[ch] = rgb[ch] * (1 - wa) + wc * wa }
            px[j * w + i] = (0xFF shl 24) or (rgb[0].toInt().coerceIn(0, 255) shl 16) or (rgb[1].toInt().coerceIn(0, 255) shl 8) or rgb[2].toInt().coerceIn(0, 255)
        }
        return Triple(w, h, px)
    }

    /** Aerial photo of the box (null when most of it has no photo). */
    internal fun photo(imagery: TileImagery, wMm: Double, hMm: Double, pxPerMm: Double, at: (Double, Double) -> Pair<Double, Double>): Triple<Int, Int, IntArray>? {
        val w = (wMm * pxPerMm).toInt().coerceIn(2, MAX_PX); val h = (hMm * pxPerMm).toInt().coerceIn(2, MAX_PX)
        // Latitude depends only on the row and longitude only on the column.
        val lats = DoubleArray(h) { at(0.5 / pxPerMm, (it + 0.5) / pxPerMm).first }
        val lons = DoubleArray(w) { at((it + 0.5) / pxPerMm, 0.5 / pxPerMm).second }
        val px = IntArray(w * h)
        var missing = 0
        for (j in 0 until h) for (i in 0 until w) {
            val c = imagery.color(lats[j], lons[i])
            if (c == null) missing++ else px[j * w + i] = c
        }
        return if (missing > px.size * 3 / 4) null else Triple(w, h, px)
    }

    /** Share of each pixel covered by water (lakes, reservoirs, riverbanks), or null when there is none. */
    private fun waterCover(water: List<List<LatLon>>, w: Int, h: Int, pxPerMm: Double, at: (Double, Double) -> Pair<Double, Double>): FloatArray? {
        val polys = water.filter { it.size >= 3 }
        if (polys.isEmpty()) return null
        val lon0 = at(0.5 / pxPerMm, 0.5 / pxPerMm).second; val dLon = at(1.5 / pxPerMm, 0.5 / pxPerMm).second - lon0
        val boxes = polys.map { p -> doubleArrayOf(p.minOf { it.lat }, p.maxOf { it.lat }) }
        val cover = FloatArray(w * h)
        var any = false
        val xs = ArrayList<Double>()
        for (j in 0 until h) for (sub in 0..1) {
            val lat = at(0.5 / pxPerMm, (j + 0.25 + 0.5 * sub) / pxPerMm).first
            for ((pi, p) in polys.withIndex()) {
                if (lat < boxes[pi][0] || lat > boxes[pi][1]) continue
                xs.clear()
                for (q in p.indices) {
                    val a = p[q]; val b = p[(q + 1) % p.size]
                    if ((a.lat > lat) != (b.lat > lat)) xs += (a.lon + (lat - a.lat) * (b.lon - a.lon) / (b.lat - a.lat) - lon0) / dLon + 0.5
                }
                xs.sort()
                for (q in 0 until xs.size - 1 step 2) {
                    val x0 = min(xs[q], xs[q + 1]).coerceIn(0.0, w.toDouble()); val x1 = max(xs[q], xs[q + 1]).coerceIn(0.0, w.toDouble())
                    var i = floor(x0).toInt()
                    while (i < x1 && i < w) {
                        val part = min(i + 1.0, x1) - max(i.toDouble(), x0)
                        if (part > 0) { val c = j * w + i; cover[c] = min(1f, cover[c] + 0.5f * part.toFloat()); any = true }
                        i++
                    }
                }
            }
        }
        return if (any) cover else null
    }

    /** Box blur of radius [r] in place (rows then columns), in time independent of [r]. */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val line = DoubleArray(max(w, h))
        for (j in 0 until h) {
            var s = 0.0; var cnt = 0
            for (i in 0..min(r, w - 1)) { s += a[j * w + i]; cnt++ }
            for (i in 0 until w) {
                line[i] = s / cnt
                val add = i + r + 1; if (add < w) { s += a[j * w + add]; cnt++ }
                val drop = i - r; if (drop >= 0) { s -= a[j * w + drop]; cnt-- }
            }
            for (i in 0 until w) a[j * w + i] = line[i].toFloat()
        }
        for (i in 0 until w) {
            var s = 0.0; var cnt = 0
            for (j in 0..min(r, h - 1)) { s += a[j * w + i]; cnt++ }
            for (j in 0 until h) {
                line[j] = s / cnt
                val add = j + r + 1; if (add < h) { s += a[add * w + i]; cnt++ }
                val drop = j - r; if (drop >= 0) { s -= a[drop * w + i]; cnt-- }
            }
            for (j in 0 until h) a[j * w + i] = line[j].toFloat()
        }
    }

    private const val MAX_PX = 4000
    private const val WATER = 0x9CC3E4
    private val CONTOUR = intArrayOf(0x5A, 0x48, 0x36)

    /** [image]: how the relief / photo is embedded (a data: URI; PNG by default, JPEG from the app for smaller files). */
    fun svg(p: Poster, image: (Int, Int, IntArray) -> String = Png::dataUri): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${f(p.widthMm, 0)}mm" height="${f(p.heightMm, 0)}mm" viewBox="0 0 ${f(p.widthMm, 2)} ${f(p.heightMm, 2)}" font-family="Helvetica, Arial, sans-serif">""").append('\n')
        for (s in p.shapes) when (s) {
            is Image -> append("""<image x="${f(s.x, 2)}" y="${f(s.y, 2)}" width="${f(s.w, 2)}" height="${f(s.h, 2)}" preserveAspectRatio="none" xlink:href="${image(s.width, s.height, s.argb)}"/>""")
            is Polygon -> append("""<polygon fill="${s.fill}" points="${s.points.joinToString(" ") { "${f(it.first, 2)},${f(it.second, 2)}" }}"/>""")
            is Rect -> append("""<rect x="${f(s.x, 2)}" y="${f(s.y, 2)}" width="${f(s.w, 2)}" height="${f(s.h, 2)}" fill="${s.fill}"/>""")
            is Line -> append("""<polyline fill="none" stroke="${s.color}"${if (s.opacity < 1) " stroke-opacity=\"${f(s.opacity, 2)}\"" else ""} stroke-width="${f(s.width, 2)}" stroke-linecap="round" stroke-linejoin="round" points="${s.points.joinToString(" ") { "${f(it.first, 2)},${f(it.second, 2)}" }}"/>""")
            is Circle -> append("""<circle cx="${f(s.x, 2)}" cy="${f(s.y, 2)}" r="${f(s.r, 2)}" fill="${s.fill}"${s.stroke?.let { " stroke=\"$it\" stroke-width=\"${f(s.strokeWidth, 2)}\"" } ?: ""}/>""")
            is Text -> append("""<text x="${f(s.x, 2)}" y="${f(s.y, 2)}" font-size="${f(s.size, 2)}" fill="${s.color}"${if (s.bold) " font-weight=\"bold\"" else ""}${when (s.anchor) { 1 -> " text-anchor=\"middle\""; 2 -> " text-anchor=\"end\""; else -> "" }}>${xml(s.text)}</text>""")
        }.also { append('\n') }
        append("</svg>\n")
    }
}

/** Every user-visible phrase used in exports, for translation coverage. */
val routeExportTexts = Route3mf.texts + listOf(Terrarium.ATTRIBUTION, Mapterhorn.ATTRIBUTION, Overpass.ATTRIBUTION) + Imagery.Source.entries.map { it.attribution } + listOf(
    "Speed", "Engine speed", "Consumption", "Coolant", "Accelerator", "Boost", "Altitude", "Time into the trip",
    "Tap the route or move the slider to see what the car was doing there.", "The map needs an internet connection; the data below still works.",
    "Distance", "Duration", "Average speed (moving)", "Max speed", "Fuel used", "Average consumption", "Climb",
    "Save GPX for your GPS", "Made with OpenOBD", "Map data © OpenStreetMap contributors", "Map", "Relief", "Satellite", "colour: speed", "Distance",
) + Units.texts
