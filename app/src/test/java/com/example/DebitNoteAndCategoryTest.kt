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
}
