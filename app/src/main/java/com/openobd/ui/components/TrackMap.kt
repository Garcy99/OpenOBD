package com.openobd.ui.components

import android.graphics.Color as AColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.openobd.core.garage.TrackPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import java.io.File

/**
 * OpenStreetMap view (osmdroid, no API key needed) showing a trip's GPS track. Tapping near the route selects
 * the closest recorded point ([onSelect]), which is marked on the map.
 */
@Composable
fun TrackMap(points: List<TrackPoint>, modifier: Modifier = Modifier, selected: TrackPoint? = null, onSelect: (TrackPoint?) -> Unit = {}) {
    val select = androidx.compose.runtime.rememberUpdatedState(onSelect)
    val context = LocalContext.current
    val geo = remember(points) { points.map { GeoPoint(it.lat, it.lon) } }
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    val fitted = remember(points) { booleanArrayOf(false) }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        // Added last so it sees taps first (osmdroid hands events to the top overlay first, and the route line would take them)
        val taps = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                // The closest point, if it is within ~40 px of the tap at the current zoom
                val tap = m.projection.toPixels(p, null)
                val best = points.minByOrNull { it.distanceMetersTo(p.latitude, p.longitude) }
                val px = best?.let { m.projection.toPixels(GeoPoint(it.lat, it.lon), null) }
                val near = px != null && Math.hypot((px.x - tap.x).toDouble(), (px.y - tap.y).toDouble()) <= 40 * m.resources.displayMetrics.density
                select.value(if (near) best else null)
                return true
            }
            override fun longPressHelper(p: GeoPoint) = false
        })
        if (geo.size >= 2) {
            m.overlays += Polyline(m).apply {
                setPoints(geo)
                outlinePaint.color = AColor.rgb(0x0E, 0x74, 0x90)
                outlinePaint.strokeWidth = 10f
            }
            if (!fitted[0]) {
                val box = BoundingBox.fromGeoPoints(geo)
                m.addOnFirstLayoutListener { _, _, _, _, _ -> m.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
                if (m.width > 0) m.zoomToBoundingBox(box.increaseByScale(1.3f), false)
                fitted[0] = true
            }
            selected?.let { sp ->
                m.overlays += Marker(m).apply {
                    position = GeoPoint(sp.lat, sp.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> true }
                }
            }
        } else if (geo.size == 1) {
            m.controller.setZoom(15.0)
            m.controller.setCenter(geo.first())
        }
        m.overlays += taps
        m.invalidate()
    })
}
