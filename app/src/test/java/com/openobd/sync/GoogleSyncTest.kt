package com.openobd.sync

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.core.garage.FuelEntry
import com.openobd.core.garage.GarageData
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.core.garage.Vehicle
import com.openobd.data.GarageRepository
import com.openobd.data.GoogleAuth
import com.openobd.data.GoogleSync
import com.openobd.data.HistoryRepository
import com.openobd.data.MemoryDriveApi
import com.openobd.data.SettingsRepository
import com.openobd.data.SyncedPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** One phone syncing with a Drive folder another phone already filled, then both changing things. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GoogleSyncTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val fakeAuth = object : GoogleAuth {
        var token: String? = "token"
        override suspend fun authorize(context: Context) = GoogleAuth.Result(token, null)
        override fun fromConsent(context: Context, data: Intent?) = token
        override suspend fun revoke(token: String) { this.token = null }
    }

    private fun MemoryDriveApi.text(name: String) = files.values.first { it.first.name == name }.second.toString(Charsets.UTF_8)
    private fun MemoryDriveApi.garage() = json.decodeFromString(GarageData.serializer(), text(GoogleSync.GARAGE))

    @Test fun `phones share cars, trips, routes and preferences`() = runBlocking {
        val garage = GarageRepository(ctx).apply { load(null) }
        val history = HistoryRepository(ctx).apply { load() }
        val settings = SettingsRepository(ctx, CoroutineScope(Dispatchers.Default))
        val drive = MemoryDriveApi()
        // The other phone: an Astra with one trip and its route, imperial units.
        val astra = Vehicle("va", "Astra", vin = "W0LPD8EC8E8000001")
        val oldTrip = TripRecord(id = "t-old", vehicleId = "va", start = 1_000, end = 2_000, distanceKm = 12.0)
        drive.create(GoogleSync.GARAGE, json.encodeToString(GarageData.serializer(), GarageData(listOf(astra), "va", trips = listOf(oldTrip))).toByteArray())
        drive.create("track-t-old.json", """[{"t":1000,"lat":40.4,"lon":-3.7}]""".toByteArray())
        drive.create(GoogleSync.PREFS, json.encodeToString(SyncedPrefs.serializer(), SyncedPrefs(units = com.openobd.core.obd.UnitSystem.IMPERIAL)).toByteArray())

        val sync = GoogleSync(ctx, garage, history, settings).apply { auth = fakeAuth; driveFor = { drive } }
        assertTrue(garage.data.value.vehicles.single().name == GarageData.DEFAULT_NAME) // fresh install
        sync.connect("token")
        val st = sync.state.value
        assertTrue(st.connected && st.error == null && st.lastSync > 0)
        assertEquals("driver@example.com", st.email)
        // The fresh install's automatic car is not duplicated next to the real one; the route came along.
        assertEquals(listOf("va"), garage.data.value.vehicles.map { it.id })
        assertEquals(1, garage.loadTrack("t-old").size)
        assertEquals(com.openobd.core.obd.UnitSystem.IMPERIAL, settings.readOnce().units)

        // This phone records a trip and logs fuel; the other renames the old trip at the same time.
        garage.saveTrip(TripRecord(id = "t-new", vehicleId = "va", start = 5_000, end = 6_000, distanceKm = 3.0), listOf(TrackPoint(5_000, 40.5, -3.6)))
        garage.upsertFuel(FuelEntry("f1", "va", 5_500, 1000.0, 40.0, 60.0))
        val theirs = drive.garage().let { it.copy(trips = it.trips.map { t -> if (t.id == "t-old") t.copy(name = "Sunday drive") else t }) }
        val gid = drive.files.values.first { it.first.name == GoogleSync.GARAGE }.first.id
        drive.update(gid, json.encodeToString(GarageData.serializer(), theirs).toByteArray())

        assertTrue(sync.syncIfConnected(force = true))
        for (g in listOf(garage.data.value, drive.garage())) {
            assertEquals(listOf("t-new", "t-old"), g.trips.map { it.id })
            assertEquals("Sunday drive", g.trips.last().name)
            assertEquals(listOf("f1"), g.fuel.map { it.id })
        }
        assertTrue(drive.files.values.any { it.first.name == "track-t-new.json" })

        // Deleting a trip on the other phone removes it, and its route, here too.
        val gone = drive.garage().let { it.copy(trips = it.trips.filter { t -> t.id != "t-old" }) }
        drive.update(gid, json.encodeToString(GarageData.serializer(), gone).toByteArray())
        assertTrue(sync.syncIfConnected(force = true))
        assertEquals(listOf("t-new"), garage.data.value.trips.map { it.id })
        assertTrue(garage.loadTrack("t-old").isEmpty())
        assertTrue(drive.files.values.none { it.first.name == "track-t-old.json" })

        // Access withdrawn: it asks to sign in again instead of failing silently.
        fakeAuth.token = null
        assertTrue(!sync.syncIfConnected(force = true))
        assertTrue(sync.state.value.needsSignIn)
        sync.disconnect()
        assertTrue(!sync.state.value.connected)
    }
}
