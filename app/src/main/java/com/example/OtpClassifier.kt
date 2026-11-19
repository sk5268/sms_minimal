package com.example

/**
 * Splits OTP handling into two decisions with very different costs.
 *
 * [extractOtp] only decides whether to offer a copy button, so it can be
 * generous. [isAutoDeletable] decides whether to destroy the message half an
 * hour later, so it has to be certain. Bank transaction alerts routinely carry
 * boilerplate like "Never share OTP or PIN with anyone", which reads as an OTP
 * to any keyword test; deleting those loses money records the user needs.
 */
object OtpClassifier {

    private val phraseKeywords = listOf(
        "one time password",
        "one-time password",
        "verification code",
        "one time pin",
        "one-time pin"
    )

    // Word boundaries, so merchant names and words containing "otp" do not match.
    private val otpWord = Regex("""\bOTP\b""", RegexOption.IGNORE_CASE)

    // Uppercase and space delimited. "asks for OTP/CVV" and "yourotp" are excluded.
    private val strictOtpWord = Regex(""" OTP """)

    private val standaloneCode = Regex("""\b\d{4,8}\b""")
    private val labelledCode = Regex("""(?i)\b(?:code|otp)\s*[:= ]\s*([a-zA-Z0-9]{4,8})\b""")

    /** The code to surface on the notification's copy action, if this looks like an OTP at all. */
    fun extractOtp(body: String): String? {
        if (!mentionsOtp(body)) return null

        val digits = standaloneCode.findAll(body).map { it.value }.toList()
        if (digits.isNotEmpty()) {
            return digits.find { it.length == 6 } ?: digits.first()
        }

        val candidate = labelledCode.find(body)?.groupValues?.get(1) ?: return null
        val containsDigit = candidate.any { it.isDigit() }
        val allCaps = candidate.all { it.isUpperCase() || it.isDigit() }
        return if (containsDigit || (allCaps && candidate.length >= 4)) candidate else null
    }

    /**
     * Whether this message may be auto-expired after the OTP is used. Anything
     * that quotes an amount or parses as a transaction is kept, even when it
     * genuinely mentions an OTP.
     */
    fun isAutoDeletable(body: String, sender: String = ""): Boolean {
        if (extractOtp(body) == null) return false
        if (!strictlyMentionsOtp(body)) return false
        if (DebitParser.containsCurrencyAmount(body)) return false
        if (DebitParser.parse(body, sender) != null) return false
        return true
    }

    private fun mentionsOtp(body: String): Boolean {
        if (otpWord.containsMatchIn(body)) return true
        val lower = body.lowercase()
        return phraseKeywords.any { lower.contains(it) }
    }

    private fun strictlyMentionsOtp(body: String): Boolean {
        // Padded so a code at the very start or end of the body still qualifies.
        if (strictOtpWord.containsMatchIn(" $body ")) return true
        val lower = body.lowercase()
        return phraseKeywords.any { lower.contains(it) }
    }
}
