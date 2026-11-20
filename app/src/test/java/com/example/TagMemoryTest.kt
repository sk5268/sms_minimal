package com.example

import com.example.finance.Fuel
import com.example.finance.TagMemory
import com.example.finance.TagMemory.Fix
import com.example.finance.TagMemory.TaggedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagMemoryTest {

    private val stall = Fix(17.4401, 78.3489, 12.0)

    private fun point(cat: Long, payee: String? = null, lat: Double? = null, lng: Double? = null, at: Long = 0, acc: Double = 10.0) =
        TaggedPoint(cat, payee, lat, lng, acc, at)

    @Test
    fun samePayeeAgreeingTagsAutoTag() {
        val history = listOf(point(5, "V:stall@ybl", at = 1), point(5, "V:stall@ybl", at = 2))
        val r = TagMemory.resolve("V:stall@ybl", null, history, emptyList())
        assertEquals(5L, r.categoryId)
        assertEquals(TagMemory.SOURCE_PAYEE, r.source)
    }

    @Test
    fun conflictingPayeeTagsStopAutoTagging() {
        val history = listOf(point(5, "V:ravi@ybl", at = 1), point(7, "V:ravi@ybl", at = 2))
        assertNull(TagMemory.resolve("V:ravi@ybl", null, history, emptyList()).categoryId)
    }

    @Test
    fun placeWithin35mAutoTags() {
        // ~20 m north of the stall
        val nearby = listOf(point(9, lat = 17.44028, lng = 78.3489, at = 1))
        val r = TagMemory.resolve(null, stall, emptyList(), nearby)
        assertEquals(9L, r.categoryId)
        assertEquals(TagMemory.SOURCE_PLACE, r.source)
    }

    @Test
    fun placeBeyondRadiusIsIgnored() {
        // ~80 m away
        val far = listOf(point(9, lat = 17.44082, lng = 78.3489, at = 1))
        assertNull(TagMemory.resolve(null, stall, emptyList(), far).categoryId)
    }

    @Test
    fun poorFixIsNotTrusted() {
        val nearby = listOf(point(9, lat = 17.4401, lng = 78.3489, at = 1))
        assertNull(TagMemory.resolve(null, stall.copy(accuracyM = 150.0), emptyList(), nearby).categoryId)
    }

    @Test
    fun payeeBeatsPlace() {
        val payee = listOf(point(3, "N:HPCL", at = 1))
        val nearby = listOf(point(9, lat = 17.4401, lng = 78.3489, at = 1))
        assertEquals(3L, TagMemory.resolve("N:HPCL", stall, payee, nearby).categoryId)
    }

    @Test
    fun suggestionsRankPayeeThenPlaceThenKeywordThenFrequent() {
        val s = TagMemory.suggestions(
            payeeKey = "V:ravi@ybl",
            fix = stall,
            payeeHistory = listOf(point(5, "V:ravi@ybl", at = 1), point(7, "V:ravi@ybl", at = 2), point(7, "V:ravi@ybl", at = 3)),
            nearbyHistory = listOf(point(9, lat = 17.4401, lng = 78.3489)),
            keywordHint = 11,
            frequentCategoryIds = listOf(12, 5),
            excludeIds = setOf(1),
            limit = 4
        )
        assertEquals(listOf(7L, 5L, 9L, 11L), s)
    }

    @Test
    fun mileagePairsTripWithPreviousFill() {
        val day = 86_400_000L
        val fills = listOf(
            Fuel.Fill(0, 50000, 4.0, null),
            Fuel.Fill(5 * day, 50000, 4.0, 180.0),
            Fuel.Fill(10 * day, 50000, 4.0, 200.0)
        )
        val stats = Fuel.stats(fills, 0, 30 * day)
        assertEquals(50.0, stats.lastKmpl!!, 0.01)
        assertEquals(47.5, stats.avgKmpl!!, 0.01)
        assertEquals(5.0, stats.avgDaysBetweenFills!!, 0.01)
        assertEquals(150000L, stats.monthSpendPaise)
    }

    @Test
    fun litresFromAmount() {
        assertTrue(kotlin.math.abs(Fuel.litres(57800, 115.6) - 5.0) < 0.001)
    }
}
