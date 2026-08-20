package com.example

object CopySuggestionsParser {

    private val urlPattern = Regex(
        """(https?://[^\s<>"{}|\\^`]+|www\.[^\s<>"{}|\\^`]+)""",
        RegexOption.IGNORE_CASE
    )

    private val emailPattern = Regex(
        """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""
    )

    private val hashtagPattern = Regex(
        """#[a-zA-Z0-9_-]+"""
    )

    private val currencyPattern = Regex(
        """(?:₹|Rs\.?|INR|\$|€|£)\s*[\d,]+(?:\.\d{1,2})?""",
        RegexOption.IGNORE_CASE
    )

    // Numbers with hyphens / slashes / dates (e.g. 20-AUG-26, 2026-08-20, TXN-12345, 1800-123-4567, 1234-5678-9012)
    private val hyphenOrSlashPattern = Regex(
        """\b[A-Za-z0-9]+(?:[-/][A-Za-z0-9]+)+\b"""
    )

    // Formatted phone numbers with spaces / brackets (e.g. +91 98765 43210, (555) 123-4567)
    private val phoneFormattedPattern = Regex(
        """(?:\+\d{1,3}[-.\s]?)?\(?\d{2,4}\)?[-.\s]?\d{3,5}[-.\s]?\d{3,5}"""
    )

    // Pure numeric sequences (OTP, tracking numbers, account numbers, etc.) of length >= 3
    private val pureNumberPattern = Regex(
        """\b\d{3,}\b"""
    )

    // Alphanumeric mixed codes (e.g. SAVE50, SBI001, TXN987, REF1234A) length >= 4 with at least 1 digit & 1 letter
    private val alphanumericCodePattern = Regex(
        """\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9_-]{4,}\b"""
    )

    private val trailingPunctuation = charArrayOf(
        '.', ',', '!', '?', ';', ':', ')', ']', '}', '"', '\'', '>', '<'
    )
    private val leadingPunctuation = charArrayOf(
        '(', '[', '{', '"', '\'', '<', ':', ';'
    )

    /**
     * Extracts all copyable suggestions (numbers, codes, hashtags, URLs, dates, amounts, etc.)
     * from the given SMS text, in the order they appear in the message.
     */
    fun extract(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val results = mutableListOf<Pair<Int, String>>()
        val seen = mutableSetOf<String>()

        fun addResult(start: Int, rawValue: String) {
            var cleaned = rawValue.trim()
            while (cleaned.isNotEmpty() && cleaned.first() in leadingPunctuation) {
                cleaned = cleaned.substring(1)
            }
            while (cleaned.isNotEmpty() && cleaned.last() in trailingPunctuation) {
                cleaned = cleaned.substring(0, cleaned.length - 1)
            }
            if (cleaned.isNotBlank() && !seen.contains(cleaned)) {
                seen.add(cleaned)
                results.add(start to cleaned)
            }
        }

        // 1. URLs
        for (match in urlPattern.findAll(text)) {
            var url = match.value
            while (url.isNotEmpty() && url.last() in trailingPunctuation) {
                url = url.substring(0, url.length - 1)
            }
            if (url.isNotBlank() && !seen.contains(url)) {
                seen.add(url)
                results.add(match.range.first to url)
            }
        }

        // 2. Email addresses
        for (match in emailPattern.findAll(text)) {
            addResult(match.range.first, match.value)
        }

        // 3. Currency amounts (e.g. Rs. 4,500.00, ₹1500)
        for (match in currencyPattern.findAll(text)) {
            addResult(match.range.first, match.value)
        }

        // 4. Hashtags / # codes (e.g. #36332863501, #ORDER123)
        for (match in hashtagPattern.findAll(text)) {
            val raw = match.value
            addResult(match.range.first, raw)
            // Also add number without '#' if it contains digits
            val withoutHash = raw.removePrefix("#")
            if (withoutHash.any { it.isDigit() } && withoutHash.length >= 3) {
                addResult(match.range.first + 1, withoutHash)
            }
        }

        // 5. Hyphenated / Slashed codes & Dates (e.g. 20-AUG-26, TXN-987213, 1800-123-4567)
        for (match in hyphenOrSlashPattern.findAll(text)) {
            val value = match.value
            // Ensure it contains at least one digit or uppercase alphanumeric structure
            if (value.any { it.isDigit() } || value.all { it.isUpperCase() || it == '-' || it == '/' }) {
                addResult(match.range.first, value)
            }
        }

        // 6. Formatted phone numbers with spaces/dashes (e.g. +91 98765 43210)
        for (match in phoneFormattedPattern.findAll(text)) {
            val digitsCount = match.value.count { it.isDigit() }
            if (digitsCount >= 7) {
                addResult(match.range.first, match.value)
            }
        }

        // 7. Alphanumeric codes (e.g. SAVE50, TXN1234, SBI001)
        for (match in alphanumericCodePattern.findAll(text)) {
            addResult(match.range.first, match.value)
        }

        // 8. Pure numbers (length >= 3, e.g. OTPs, tracking IDs, amounts)
        for (match in pureNumberPattern.findAll(text)) {
            addResult(match.range.first, match.value)
        }

        // Sort by their appearance index in text, preserving natural reading order
        return results
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }
}
