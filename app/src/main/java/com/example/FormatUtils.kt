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
