package com.openobd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openobd.core.garage.MonthlySummary
import com.openobd.core.garage.TripRecord
import com.openobd.core.obd.UnitSystem
import com.openobd.ui.screens.writeMonthlyPdf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The monthly summary is drawn into a real PDF that can be shared. */
@RunWith(AndroidJUnit4::class)
class MonthlyPdfDeviceTest {
    @Test
    fun writesThePdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()
        val trips = (0 until 6).map { TripRecord(id = "t$it", vehicleId = "c", start = now - it * 86_400_000L, end = now - it * 86_400_000L + 3600_000, distanceKm = 40.0 + it, fuelL = 2.5 + it * 0.2, ecoScore = 70) }
        val cal = java.util.Calendar.getInstance()
        val s = MonthlySummary.of(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), trips, emptyList(), diesel = true, lpg = false,
            officialL100 = 4.5, fallbackPrice = 1.5)
        val f = writeMonthlyPdf(context, s, "Opel Astra", UnitSystem.METRIC)
        val head = f.readBytes().take(5).toByteArray().toString(Charsets.US_ASCII)
        assertTrue("$head ${f.length()}", head == "%PDF-" && f.length() > 1000)
    }
}
