package com.example.finance

import android.content.Context

/**
 * Bike fuel maths, done on the phone so Jarvis receives finished numbers.
 *
 * The owner resets the trip meter at every refuel and types the reading at the next
 * one, so a fuel debit's [DebitEntity.tripKm] is the distance ridden on the fuel
 * bought before it. Single-fill km/L is only exact for full-tank fills; the rolling
 * average over several fills is robust to partial fills.
 */
object Fuel {
    private const val PREFS = "fuel_prefs"
    private const val KEY_PRICE = "price_per_litre"
    const val DEFAULT_PRICE_PER_LITRE = 115.6

    private val fuelCategoryNames = setOf("fuel", "petrol", "diesel")

    fun isFuelCategory(name: String?): Boolean =
        name != null && name.trim().lowercase() in fuelCategoryNames

    fun pricePerLitre(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_PRICE, DEFAULT_PRICE_PER_LITRE.toFloat()).toDouble()

    fun setPricePerLitre(context: Context, price: Double) {
        if (price <= 0.0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_PRICE, price.toFloat()).apply()
    }

    fun litres(amountPaise: Long, pricePerLitre: Double): Double =
        if (pricePerLitre <= 0.0) 0.0 else (amountPaise / 100.0) / pricePerLitre

    data class Fill(val occurredAt: Long, val amountPaise: Long, val litres: Double?, val tripKm: Double?)

    data class Stats(
        val fills: Int,
        val lastFillAt: Long?,
        val avgDaysBetweenFills: Double?,
        /** km/L of the latest fill that has a trip reading (full-to-full estimate). */
        val lastKmpl: Double?,
        /** Sum of trip km / sum of litres over the last [WINDOW] fills with readings. */
        val avgKmpl: Double?,
        val monthSpendPaise: Long
    )

    const val WINDOW = 5

    /**
     * The trip reading typed at fill N covers the ride since fill N-1, so it is paired
     * with the litres bought at fill N-1 (the fuel actually burned).
     */
    fun stats(fills: List<Fill>, monthStart: Long, monthEnd: Long): Stats {
        val sorted = fills.sortedBy { it.occurredAt }
        val gaps = sorted.zipWithNext { a, b -> (b.occurredAt - a.occurredAt) / 86_400_000.0 }
            .takeLast(WINDOW)
        val pairs = sorted.zipWithNext().mapNotNull { (prev, cur) ->
            val km = cur.tripKm ?: return@mapNotNull null
            val litres = prev.litres ?: return@mapNotNull null
            if (km <= 0.0 || litres <= 0.0) null else km to litres
        }
        val window = pairs.takeLast(WINDOW)
        return Stats(
            fills = sorted.size,
            lastFillAt = sorted.lastOrNull()?.occurredAt,
            avgDaysBetweenFills = if (gaps.isEmpty()) null else gaps.average(),
            lastKmpl = pairs.lastOrNull()?.let { it.first / it.second },
            avgKmpl = if (window.isEmpty()) null else window.sumOf { it.first } / window.sumOf { it.second },
            monthSpendPaise = sorted.filter { it.occurredAt in monthStart until monthEnd }.sumOf { it.amountPaise }
        )
    }
}
