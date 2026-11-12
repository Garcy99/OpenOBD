package com.openobd.core.trip

import com.openobd.core.garage.PURPOSE_PERSONAL
import com.openobd.core.garage.PURPOSE_WORK
import com.openobd.core.garage.TripRecord
import java.util.Calendar
import java.util.TimeZone

/** A month's trips as a log of kilometres and costs (for expenses: work, personal or all of them). */
object TripReport {
    enum class Filter { ALL, WORK, PERSONAL }

    data class Totals(val trips: Int, val km: Double, val seconds: Double, val fuelL: Double?, val cost: Double?)

    fun tripsOf(trips: List<TripRecord>, year: Int, month: Int, filter: Filter, tz: TimeZone = TimeZone.getDefault()): List<TripRecord> =
        trips.filter { t ->
            val c = Calendar.getInstance(tz).apply { timeInMillis = t.start }
            c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month && when (filter) {
                Filter.ALL -> true
                Filter.WORK -> t.purpose == PURPOSE_WORK
                Filter.PERSONAL -> t.purpose == PURPOSE_PERSONAL
            }
        }.sortedBy { it.start }

    fun totals(trips: List<TripRecord>) = Totals(
        trips.size, trips.sumOf { it.distanceKm }, trips.sumOf { (it.end - it.start) / 1000.0 },
        trips.mapNotNull { it.fuelL }.takeIf { it.isNotEmpty() }?.sum(), trips.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum(),
    )

    /**
     * CSV (semicolon separated, decimal comma: opens as columns in Spanish / European spreadsheets) with a totals row.
     * [labels] are the column titles and words, already translated.
     */
    fun csv(trips: List<TripRecord>, labels: Labels, tz: TimeZone = TimeZone.getDefault()): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = tz }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).apply { timeZone = tz }
        fun num(v: Double?, d: Int) = v?.let { String.format(java.util.Locale.ROOT, "%.${d}f", it).replace('.', ',') }.orEmpty()
        fun cell(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder()
        sb.append(listOf(labels.date, labels.start, labels.end, labels.name, labels.purpose, labels.km, labels.minutes, labels.litres, labels.cost)
            .joinToString(";") { cell(it) }).append('\n')
        for (t in trips) {
            val purpose = when (t.purpose) { PURPOSE_WORK -> labels.work; PURPOSE_PERSONAL -> labels.personal; else -> "" }
            sb.append(listOf(date.format(t.start), time.format(t.start), time.format(t.end), t.name, purpose, num(t.distanceKm, 1),
                num((t.end - t.start) / 60_000.0, 0), num(t.fuelL, 2), num(t.cost, 2)).joinToString(";") { cell(it) }).append('\n')
        }
        val tot = totals(trips)
        sb.append(listOf(labels.total, "", "", "", "", num(tot.km, 1), num(tot.seconds / 60, 0), num(tot.fuelL, 2), num(tot.cost, 2))
            .joinToString(";") { cell(it) }).append('\n')
        return sb.toString()
    }

    data class Labels(
        val date: String = "Date", val start: String = "Start", val end: String = "End", val name: String = "Name",
        val purpose: String = "Purpose", val km: String = "km", val minutes: String = "Minutes", val litres: String = "Litres",
        val cost: String = "Cost", val work: String = "Work", val personal: String = "Personal", val total: String = "Total",
    )
}
