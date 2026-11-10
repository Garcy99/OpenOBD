package com.openobd.core.garage

import java.util.Calendar
import java.util.TimeZone

/**
 * A month of driving one car: distance, time, fuel, what it cost (the refuels of the month when there are, else the
 * trips' own cost), CO2, the eco score, how it compares with the month before and with the car's official
 * consumption, and what driving like in the best trips would save.
 */
data class MonthlySummary(
    val year: Int,
    /** 0 = January. */
    val month: Int,
    val trips: Int,
    val km: Double,
    val driveSeconds: Double,
    /** Litres the trips used (only trips that measured or estimated it). */
    val fuelL: Double?,
    /** Distance of the trips with fuel data (for the average consumption). */
    val kmWithFuel: Double,
    /** Money: the refuels of the month when there are, else the trips' cost. */
    val cost: Double?,
    val costFromRefuels: Boolean,
    val co2Kg: Double?,
    /** Km-weighted eco score of the trips that have one. */
    val ecoScore: Int?,
    val previous: MonthlySummary?,
    /** The car's official combined consumption (L/100 km), when known. */
    val officialL100: Double?,
    /** L/100 km of the thriftiest quarter of the recent trips (≥ 5 km). */
    val bestL100: Double?,
    /** Average price per litre used for the saving. */
    val pricePerL: Double?,
) {
    val avgL100: Double? get() = fuelL?.takeIf { kmWithFuel >= 1 }?.let { it / kmWithFuel * 100 }

    /** Real consumption against the official one, in percent (+38 = 38 % more). */
    val vsOfficialPct: Double? get() = avgL100?.let { a -> officialL100?.takeIf { it > 0 }?.let { (a / it - 1) * 100 } }

    /** Litres and money a month like this would have saved driving like in the best trips. */
    val savingL: Double? get() = avgL100?.let { a -> bestL100?.takeIf { it < a }?.let { (a - it) * km / 100 } }
    val savingMoney: Double? get() = savingL?.let { l -> pricePerL?.let { l * it } }

    companion object {
        /** kg of CO2 per litre burnt. */
        fun co2PerLitre(diesel: Boolean, lpg: Boolean) = when { diesel -> 2.64; lpg -> 1.66; else -> 2.31 }

        fun monthOf(t: Long, tz: TimeZone = TimeZone.getDefault()): Pair<Int, Int> =
            Calendar.getInstance(tz).apply { timeInMillis = t }.let { it.get(Calendar.YEAR) to it.get(Calendar.MONTH) }

        fun previousMonth(year: Int, month: Int) = if (month == 0) year - 1 to 11 else year to month - 1

        fun of(
            year: Int, month: Int, trips: List<TripRecord>, refuels: List<FuelEntry>, diesel: Boolean, lpg: Boolean,
            officialL100: Double?, fallbackPrice: Double?, tz: TimeZone = TimeZone.getDefault(), withPrevious: Boolean = true,
        ): MonthlySummary {
            val inMonth = trips.filter { monthOf(it.start, tz) == year to month }
            val fuelTrips = inMonth.filter { it.fuelL != null && it.distanceKm > 0 }
            val fuel = fuelTrips.sumOf { it.fuelL!! }.takeIf { fuelTrips.isNotEmpty() }
            val refuelsIn = refuels.filter { monthOf(it.date, tz) == year to month }
            val tripCost = inMonth.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()
            val cost = refuelsIn.takeIf { it.isNotEmpty() }?.sumOf { it.totalCost } ?: tripCost
            val scored = inMonth.filter { it.ecoScore != null && it.distanceKm > 0 }
            val eco = scored.takeIf { it.isNotEmpty() }?.let { s -> Math.round(s.sumOf { it.ecoScore!! * it.distanceKm } / s.sumOf { it.distanceKm }).toInt() }
            // The thriftiest quarter of the trips of the last three months (long enough to mean something).
            val monthEnd = Calendar.getInstance(tz).apply { clear(); set(year, month, 1); add(Calendar.MONTH, 1) }.timeInMillis
            val recent = trips.filter { it.start < monthEnd && it.start >= monthEnd - 92L * 24 * 3600_000 && it.distanceKm >= 5 && it.fuelL != null }
                .map { it.fuelL!! / it.distanceKm * 100 to it.distanceKm }.filter { it.first in 0.5..40.0 }.sortedBy { it.first }
            val best = recent.takeIf { it.size >= 4 }?.let { r -> r.take(maxOf(1, r.size / 4)).let { q -> q.sumOf { it.first * it.second } / q.sumOf { it.second } } }
            val price = refuelsIn.filter { it.liters > 0 }.takeIf { it.isNotEmpty() }?.let { r -> r.sumOf { it.totalCost } / r.sumOf { it.liters } }
                ?: inMonth.filter { it.fuelL != null && it.cost != null && it.fuelL > 0 }.takeIf { it.isNotEmpty() }?.let { t -> t.sumOf { it.cost!! } / t.sumOf { it.fuelL!! } }
                ?: fallbackPrice?.takeIf { it > 0 }
            val prev = if (withPrevious) previousMonth(year, month).let { (py, pm) ->
                of(py, pm, trips, refuels, diesel, lpg, officialL100, fallbackPrice, tz, withPrevious = false).takeIf { it.trips > 0 }
            } else null
            return MonthlySummary(
                year, month, inMonth.size, inMonth.sumOf { it.distanceKm }, inMonth.sumOf { (it.end - it.start) / 1000.0 },
                fuel, fuelTrips.sumOf { it.distanceKm }, cost, refuelsIn.isNotEmpty(), fuel?.let { it * co2PerLitre(diesel, lpg) }, eco, prev,
                officialL100, best, price,
            )
        }
    }
}
