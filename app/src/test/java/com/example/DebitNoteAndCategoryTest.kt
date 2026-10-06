package com.example

import com.example.finance.CategoryChartPalette
import com.example.finance.CategoryEntity
import com.example.finance.DebitEntity
import com.example.finance.pickNextCategoryColor
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.graphics.toArgb

class DebitNoteAndCategoryTest {

    @Test
    fun pickNextCategoryColorSelectsUnusedColor() {
        val cat1 = CategoryEntity(id = 1, name = "Food", colorArgb = CategoryChartPalette[0].toArgb(), sortOrder = 0)
        val cat2 = CategoryEntity(id = 2, name = "Fuel", colorArgb = CategoryChartPalette[1].toArgb(), sortOrder = 1)

        val nextColor = pickNextCategoryColor(listOf(cat1, cat2))
        assertEquals(CategoryChartPalette[2].toArgb(), nextColor)
    }

    @Test
    fun pickNextCategoryColorCyclesWhenPaletteExhausted() {
        val allCats = CategoryChartPalette.mapIndexed { idx, color ->
            CategoryEntity(id = idx.toLong(), name = "Cat $idx", colorArgb = color.toArgb(), sortOrder = idx)
        }
        val nextColor = pickNextCategoryColor(allCats)
        assertEquals(CategoryChartPalette[0].toArgb(), nextColor)
    }

    @Test
    fun debitEntityPrefersNoteOverSnippetWhenNoteIsPresent() {
        val debitWithNote = DebitEntity(
            id = 10,
            messageKey = "key1",
            amountPaise = 50000,
            sender = "HDFCBK",
            snippet = "Rs 500.00 debited for Starbucks",
            categoryId = 2,
            occurredAt = 1000L,
            note = "Team coffee meeting"
        )

        val noteText = debitWithNote.note?.trim()
        val displayText = if (!noteText.isNullOrBlank()) {
            noteText
        } else {
            debitWithNote.snippet.takeIf { it.isNotBlank() && it != "Manual entry" }
        }
        assertEquals("Team coffee meeting", displayText)
    }

    @Test
    fun categorySortingUncategorizedFirstThenAlphabetical() {
        val categories = listOf(
            CategoryEntity(id = 1, name = "Shopping", colorArgb = 0, sortOrder = 0),
            CategoryEntity(id = 2, name = "Uncategorized", colorArgb = 0, sortOrder = 1),
            CategoryEntity(id = 3, name = "Bills", colorArgb = 0, sortOrder = 2),
            CategoryEntity(id = 4, name = "automobile", colorArgb = 0, sortOrder = 3)
        )

        val sorted = categories.sortedWith(
            compareBy<CategoryEntity> { if (it.name.equals("Uncategorized", ignoreCase = true)) 0 else 1 }
                .thenBy { it.name.lowercase() }
        )

        assertEquals(listOf("Uncategorized", "automobile", "Bills", "Shopping"), sorted.map { it.name })
    }

    @Test
    fun debitEntityFallsBackToSnippetWhenNoteIsNull() {
        val legacyDebit = DebitEntity(
            id = 11,
            messageKey = "key2",
            amountPaise = 25000,
            sender = "SWIGGY",
            snippet = "Rs 250.00 debited from account",
            categoryId = 1,
            occurredAt = 2000L,
            note = null
        )

        val noteText = legacyDebit.note?.trim()
        val displayText = if (!noteText.isNullOrBlank()) {
            noteText
        } else {
            legacyDebit.snippet.takeIf { it.isNotBlank() && it != "Manual entry" }
        }
        assertEquals("Rs 250.00 debited from account", displayText)
    }

    @Test
    fun parentCategoryGroupAggregatesSubcategorySpending() {
        val officeParent = CategoryEntity(id = 1, name = "Office", colorArgb = 0, sortOrder = 0)
        val partySub = CategoryEntity(id = 2, name = "Party", colorArgb = 0, sortOrder = 1, parentCategoryId = 1)
        val accessoriesSub = CategoryEntity(id = 3, name = "Accessories", colorArgb = 0, sortOrder = 2, parentCategoryId = 1)

        val categories = listOf(officeParent, partySub, accessoriesSub)
        val subcategoryParentMap = categories.filter { it.parentCategoryId != null }.groupBy { it.parentCategoryId!! }

        val debits = listOf(
            DebitEntity(id = 1, messageKey = "k1", amountPaise = 100000, sender = "Bank", snippet = "Party", categoryId = 2, occurredAt = 1000L),
            DebitEntity(id = 2, messageKey = "k2", amountPaise = 200000, sender = "Bank", snippet = "Combo", categoryId = 3, occurredAt = 2000L)
        )

        val subs = subcategoryParentMap[officeParent.id].orEmpty()
        val allIds = setOf(officeParent.id) + subs.map { it.id }
        val totalAmount = debits.filter { it.categoryId in allIds }.sumOf { it.amountPaise }
        val subTotals = subs.map { sub -> sub to debits.filter { it.categoryId == sub.id }.sumOf { it.amountPaise } }

        assertEquals(300000L, totalAmount)
        assertEquals(2, subTotals.size)
        assertEquals("Party" to 100000L, subTotals[0].first.name to subTotals[0].second)
        assertEquals("Accessories" to 200000L, subTotals[1].first.name to subTotals[1].second)
    }

    @Test
    fun categoryEntitySupportsParentCategoryIdAssignment() {
        val standaloneCat = CategoryEntity(id = 10, name = "Groceries", colorArgb = 0, sortOrder = 0)
        assertEquals(null, standaloneCat.parentCategoryId)

        val childCat = standaloneCat.copy(parentCategoryId = 1)
        assertEquals(1L, childCat.parentCategoryId)
    }
}
