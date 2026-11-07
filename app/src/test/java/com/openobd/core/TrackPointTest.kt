package com.openobd.core

import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.nearest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPointTest {
    @Test
    fun storesWhatTheCarWasDoing() {
        val live = mapOf("010D" to 87.0, "010C" to 2150.0, "CALC_CONS" to 5.4, "0105" to 88.0, "CALC_PEDAL" to 23.0, "CALC_BOOST" to 0.62)
        val p = TrackPoint.of(1000, 40.0, -3.0, gpsSpeedKmh = 80f) { live[it] }
        assertEquals(87f, p.speedKmh) // the car's speed wins over GPS
        assertEquals(2150f, p.rpm); assertEquals(5.4f, p.l100); assertEquals(0.62f, p.boostBar)
        assertEquals(80f, TrackPoint.of(1000, 40.0, -3.0, 80f) { null }.speedKmh)
    }

    @Test
    fun oldTracksStillLoad() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<List<TrackPoint>>("""[{"t":1,"lat":40.0,"lon":-3.0,"speedKmh":50.0}]""")
        assertEquals(50f, old.single().speedKmh); assertNull(old.single().rpm)
    }

    @Test
    fun nearestPointToATap() {
        val route = (0..10).map { TrackPoint(it * 1000L, 40.0 + it * 0.001, -3.0) } // ~111 m apart
        assertEquals(5000L, route.nearest(40.0051, -3.0, 50.0)!!.t)
        assertNull(route.nearest(40.2, -3.0, 50.0)) // far from the route
    }
}
