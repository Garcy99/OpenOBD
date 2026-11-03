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
import java.io.File

/** OpenStreetMap view (osmdroid, no API key needed) showing a trip's GPS track. */
@Composable
fun TrackMap(points: List<TrackPoint>, modifier: Modifier = Modifier) {
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
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        if (geo.size >= 2) {
            m.overlays += Polyline(m).apply {
                setPoints(geo)
                outlinePaint.color = AColor.rgb(0x0E, 0x74, 0x90)
                outlinePaint.strokeWidth = 10f
            }
            val box = BoundingBox.fromGeoPoints(geo)
            m.addOnFirstLayoutListener { _, _, _, _, _ -> m.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
            if (m.width > 0) m.zoomToBoundingBox(box.increaseByScale(1.3f), false)
        } else if (geo.size == 1) {
            m.controller.setZoom(15.0)
            m.controller.setCenter(geo.first())
        }
        m.invalidate()
    })
}
