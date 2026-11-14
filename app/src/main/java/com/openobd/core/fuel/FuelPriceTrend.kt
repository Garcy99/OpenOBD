package com.openobd.core.fuel

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** How the price of a fuel has gone around a place, day by day: the usual price (median) and the cheapest. */
object FuelPriceTrend {
    data class Day(val day: Long, val usual: Double, val cheapest: Double, val stations: Int)

    data class Summary(
        val today: Day,
        /** Change of the usual price since the first day shown (€/L; negative = cheaper now). */
        val change: Double?,
        val lowest: Day,
        val highest: Day,
    )

    private val madrid: TimeZone = TimeZone.getTimeZone("Europe/Madrid")
    private const val HIST = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestresHist/"

    /** The ministry's prices of a province on a past day ([day]: any time in it). */
    fun historyUrl(provinceId: String, day: Long): String =
        HIST + "FiltroProvincia/" + SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { timeZone = madrid }.format(java.util.Date(day)) + "/" + provinceId

    /** Midnight (Madrid) of the day [t] falls in, and [n] days before. */
    fun dayStart(t: Long, n: Int = 0): Long = Calendar.getInstance(madrid).apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -n)
    }.timeInMillis

    /** The day's prices of [fuel] within [radiusKm] of a point, or null when no station there sold it. */
    fun day(day: Long, stations: List<Station>, lat: Double, lon: Double, fuel: PumpFuel, radiusKm: Double): Day? {
        val p = FuelPricesEs.near(stations, lat, lon, fuel, radiusKm).mapNotNull { it.price(fuel) }.sorted()
        if (p.isEmpty()) return null
        val median = if (p.size % 2 == 1) p[p.size / 2] else (p[p.size / 2 - 1] + p[p.size / 2]) / 2
        return Day(day, median, p.first(), p.size)
    }

    fun summary(days: List<Day>): Summary? {
        val d = days.sortedBy { it.day }
        if (d.isEmpty()) return null
        return Summary(d.last(), if (d.size >= 2) d.last().usual - d.first().usual else null, d.minBy { it.usual }, d.maxBy { it.usual })
    }

    val texts = listOf(
        "Price trend", "%1\$s days", "Usual price", "Cheapest", "%1\$s cents in %2\$s days", "Same as %1\$s days ago",
        "Lowest %1\$s on %2\$s · highest %3\$s on %4\$s", "Downloading earlier prices… %1\$s of %2\$s days",
        "Earlier prices could not be downloaded.",
    )
}
