package com.example

import java.util.Locale

fun formatRupees(paise: Long): String {
    val rupees = paise / 100
    val frac = paise % 100
    return if (frac == 0L) {
        String.format(Locale.US, "₹%,d", rupees)
    } else {
        String.format(Locale.US, "₹%,d.%02d", rupees, frac)
    }
}

fun parseRupeesInput(input: String): Long? {
    val cleaned = input.trim()
        .replace("₹", "")
        .replace(",", "")
        .replace(" ", "")
    if (cleaned.isEmpty()) return null
    val parts = cleaned.split(".")
    if (parts.size > 2) return null
    val rupees = parts[0].toLongOrNull() ?: return null
    if (rupees < 0) return null
    val paise = when {
        parts.size == 1 -> 0L
        else -> {
            val frac = parts[1]
            if (frac.isEmpty() || frac.length > 2 || !frac.all { it.isDigit() }) return null
            when (frac.length) {
                1 -> frac.toLong() * 10
                else -> frac.toLong()
            }
        }
    }
    return rupees * 100 + paise
}
