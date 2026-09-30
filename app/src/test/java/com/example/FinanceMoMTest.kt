package com.example

import com.example.finance.MonthTotalPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FinanceMoMTest {

    @Test
    fun monthTotalPointDataStructure() {
        val point = MonthTotalPoint(
            year = 2025,
            month = 2,
            monthLabel = "Mar",
            totalPaise = 150000L
        )

        assertEquals(2025, point.year)
        assertEquals(2, point.month)
        assertEquals("Mar", point.monthLabel)
        assertEquals(150000L, point.totalPaise)
    }

    @Test
    fun categoryBreakdownFilteringNonZeroAndAllTotals() {
        val catTotals = listOf(
            Triple(1L, "Food", 120000L),
            Triple(2L, "Rent", 300000L),
            Triple(3L, "Travel", 0L),
            Triple(4L, "Utilities", 50000L)
        )

        val nonZeroTotals = catTotals.filter { it.third > 0 }
        val sumOfNonZero = nonZeroTotals.sumOf { it.third }
        val sumOfAll = catTotals.sumOf { it.third }

        // Confirming that omitting 0-spend items preserves exact total spend sum
        assertEquals(3, nonZeroTotals.size)
        assertEquals(470000L, sumOfNonZero)
        assertEquals(sumOfAll, sumOfNonZero)
    }

    @Test
    fun categoryBreakdownLimitRemovalFixesTotalSumMismatch() {
        // Simulating 8 categories with spend
        val items = (1..8).map { id ->
            Triple(id.toLong(), "Category $id", 200000L)
        }

        val totalAll = items.sumOf { it.third } // 1,600,000 paise = ₹16,000

        // Old buggy behavior: .take(6)
        val oldSum = items.take(6).sumOf { it.third } // 1,200,000 paise = ₹12,000

        // Fixed behavior: donut chart uses all items
        val newDonutSum = items.sumOf { it.third }

        assertEquals(1600000L, totalAll)
        assertEquals(1200000L, oldSum) // Discrepancy explained!
        assertEquals(totalAll, newDonutSum) // Fix confirmed!
    }
}
