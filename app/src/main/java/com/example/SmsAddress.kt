package com.example

/**
 * SMS address matching that works for both phone numbers and alphanumeric
 * sender IDs (Indian DLT IDs like BT-HDFCGI-S, VM-HDFCBI-S).
 *
 * PhoneNumberUtils.compare() is built for dialable numbers. Letters are not
 * dialable, so it returns false even when two bank sender IDs are identical.
 * Operator prefixes also change per SMSC (BT vs VM vs AD) while the entity
 * in the middle is the same bank — those must be treated as one conversation.
 */
object SmsAddress {

    private val dltHyphen = Regex("^[A-Za-z]{2}-([A-Za-z0-9]{3,9})-[A-Za-z]$")
    // Compact DLT (BTHDFCGIS). Trailing letter must be a TRAI template type so
    // bare 6-letter entity IDs like HDFCGI are not mis-parsed as prefix+entity.
    private val dltCompact = Regex("^[A-Za-z]{2}([A-Za-z0-9]{3,9})[STPGLstpgl]$")

    fun looksLikePhoneNumber(address: String): Boolean {
        return address.filter { it.isDigit() }.length >= 7
    }

    fun isAlphanumericSender(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty() || looksLikePhoneNumber(trimmed)) return false
        return trimmed.any { it.isLetter() }
    }

    fun entityKey(address: String): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return ""
        dltHyphen.matchEntire(trimmed)?.let { return it.groupValues[1].uppercase() }
        dltCompact.matchEntire(trimmed)?.let { return it.groupValues[1].uppercase() }
        return trimmed.uppercase().replace("-", "").replace(" ", "")
    }

    fun sqlLikePatterns(address: String): List<String> {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return emptyList()
        val patterns = linkedSetOf<String>()
        patterns.add(trimmed)
        val entity = dltHyphen.matchEntire(trimmed)?.groupValues?.get(1)
            ?: dltCompact.matchEntire(trimmed)?.groupValues?.get(1)
        if (!entity.isNullOrBlank()) {
            patterns.add("%-$entity-%")
            patterns.add("%$entity%")
        }
        return patterns.toList()
    }

    fun addressesMatch(
        a: String?,
        b: String?,
        phoneCompare: (String, String) -> Boolean = { x, y -> x.equals(y, ignoreCase = true) }
    ): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        val keyA = entityKey(a)
        val keyB = entityKey(b)
        if (keyA.isNotEmpty() && keyA == keyB) return true
        if (looksLikePhoneNumber(a) && looksLikePhoneNumber(b)) {
            return phoneCompare(a, b)
        }
        return false
    }
}
