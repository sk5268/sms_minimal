package com.example.finance

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tag memory learned only from the owner's own tags. No guessing.
 *
 * A debit is tagged automatically when the most recent manual tags for the same
 * payee (UPI ID / merchant) all agree, or, failing that, when the most recent
 * manual tags made within [PLACE_RADIUS_M] of where the payment happened all agree.
 * As soon as the owner tags the same payee or place two different ways, auto-tagging
 * stops for it and the categories become suggestions instead.
 */
object TagMemory {

    const val SOURCE_MANUAL = "manual"
    const val SOURCE_PAYEE = "payee"
    const val SOURCE_PLACE = "place"
    const val SOURCE_NONE = "none"

    /** GPS in a city is often off by 10–30 m, so 10 m would miss most repeat visits. */
    const val PLACE_RADIUS_M = 35.0

    /** A fix worse than this can't tell two neighbouring shops apart. */
    const val MAX_FIX_ACCURACY_M = 60.0

    /** How many recent manual tags are compared. */
    const val HISTORY = 5

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Double)

    /** A past manual tag: the debit's payee/place and the category the owner chose. */
    data class TaggedPoint(
        val categoryId: Long,
        val payeeKey: String?,
        val lat: Double?,
        val lng: Double?,
        val accuracyM: Double?,
        val occurredAt: Long
    )

    data class Resolution(val categoryId: Long?, val source: String)

    fun resolve(
        payeeKey: String?,
        fix: Fix?,
        payeeHistory: List<TaggedPoint>,
        nearbyHistory: List<TaggedPoint>
    ): Resolution {
        if (!payeeKey.isNullOrBlank()) {
            agreed(payeeHistory.filter { it.payeeKey == payeeKey })?.let {
                return Resolution(it, SOURCE_PAYEE)
            }
        }
        if (fix != null && fix.accuracyM <= MAX_FIX_ACCURACY_M) {
            agreed(within(fix, nearbyHistory))?.let { return Resolution(it, SOURCE_PLACE) }
        }
        return Resolution(null, SOURCE_NONE)
    }

    /**
     * Categories to offer first, best first: what this payee and this place were
     * tagged as before, then the keyword hint, then the owner's most used categories.
     */
    fun suggestions(
        payeeKey: String?,
        fix: Fix?,
        payeeHistory: List<TaggedPoint>,
        nearbyHistory: List<TaggedPoint>,
        keywordHint: Long?,
        frequentCategoryIds: List<Long>,
        excludeIds: Set<Long>,
        limit: Int = 3
    ): List<Long> {
        val ranked = LinkedHashSet<Long>()
        if (!payeeKey.isNullOrBlank()) {
            ranked += byFrequency(payeeHistory.filter { it.payeeKey == payeeKey })
        }
        if (fix != null && fix.accuracyM <= MAX_FIX_ACCURACY_M) {
            ranked += byFrequency(within(fix, nearbyHistory))
        }
        keywordHint?.let { ranked += it }
        ranked += frequentCategoryIds
        return ranked.filter { it !in excludeIds }.take(limit)
    }

    fun within(fix: Fix, points: List<TaggedPoint>): List<TaggedPoint> = points.filter { p ->
        val lat = p.lat ?: return@filter false
        val lng = p.lng ?: return@filter false
        val acc = p.accuracyM ?: Double.MAX_VALUE
        acc <= MAX_FIX_ACCURACY_M && distanceM(fix.lat, fix.lng, lat, lng) <= PLACE_RADIUS_M
    }

    private fun agreed(points: List<TaggedPoint>): Long? {
        val recent = points.sortedByDescending { it.occurredAt }.take(HISTORY)
        if (recent.isEmpty()) return null
        val first = recent.first().categoryId
        return if (recent.all { it.categoryId == first }) first else null
    }

    private fun byFrequency(points: List<TaggedPoint>): List<Long> =
        points.sortedByDescending { it.occurredAt }
            .take(HISTORY * 2)
            .groupingBy { it.categoryId }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }

    /** Rough box for the SQL prefilter: degrees of latitude/longitude spanning [meters]. */
    fun boundingBox(lat: Double, lng: Double, meters: Double): DoubleArray {
        val dLat = meters / 111_320.0
        val dLng = meters / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return doubleArrayOf(lat - dLat, lat + dLat, lng - dLng, lng + dLng)
    }

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
