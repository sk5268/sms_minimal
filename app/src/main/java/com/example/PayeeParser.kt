package com.example

/**
 * Pulls "who was paid" out of an Indian bank debit SMS.
 *
 * The SMS sender is the bank (AX-HDFCBK), so it can't tell a pani puri stall from
 * a petrol pump. The payee can: a UPI ID is unique per merchant QR or person, and a
 * card swipe names the merchant ("At HPCL SRI SAI FILLING").
 *
 * [Payee.key] is what tag memory matches on: `V:` + the UPI ID when there is one,
 * otherwise `N:` + the normalised merchant name.
 */
object PayeeParser {

    data class Payee(val key: String, val name: String)

    private val vpaRegex = Regex(
        """(?<![\w.\-@])([a-zA-Z0-9][\w.\-]{1,63}@[a-zA-Z][a-zA-Z0-9]{1,30})(?![\w@.]*\.[a-z]{2,})(?![\w@])"""
    )

    // A payee name: letters/digits plus a few joiners, short, captured lazily.
    private const val NAME = """([A-Za-z0-9][A-Za-z0-9&'.*\- ]{1,48}?)"""

    // What usually follows the name in bank SMS templates.
    private const val END =
        """(?=\s+(?:on|ref|refno|ref\s*no|upi|avl|avbl|bal|not\s+you|via|using|from|for|dt|date|txn|value|is)\b|\s*[.;,(]\s|\s*[.;,]?\s*$|\s+\d{1,2}[-/]\d|\s+-)"""

    private val namedPatterns = listOf(
        // Axis / Federal style: UPI/P2M/123456789/SWIGGY
        Regex("""UPI/(?:P2[AM]|DR|CR|MOB)/\d+/([A-Za-z0-9&'.\- ]{2,40}?)(?=/|\s+not\s+you|\s*[.;]|\s+(?:on|ref|avl|bal)\b|\s*$)""", RegexOption.IGNORE_CASE),
        // SBI "trf to", generic "paid to", "transferred to", "sent to"
        Regex("""\b(?:trf|transfer(?:red)?|sent|paid|payment)\s+to\s+$NAME$END""", RegexOption.IGNORE_CASE),
        // ICICI: "...; ABC STORE credited"
        Regex(""";\s*$NAME\s+credited""", RegexOption.IGNORE_CASE),
        // Card swipes: "at HPCL SRI SAI FILLING on ..."
        Regex("""\bat\s+$NAME$END""", RegexOption.IGNORE_CASE),
        // HDFC UPI: "To RAVI KUMAR On 18/11/26"
        Regex("""\bto\s+$NAME$END""", RegexOption.IGNORE_CASE),
        Regex("""\btowards\s+$NAME$END""", RegexOption.IGNORE_CASE),
        // IDFC standing instructions: "INR 824.82 for WWW AIRTEL IN paid from ..."
        Regex("""\bfor\s+$NAME\s+paid\b""", RegexOption.IGNORE_CASE),
        Regex("""\bInfo:?\s*$NAME$END""", RegexOption.IGNORE_CASE)
    )

    private val junkWords = listOf(
        "a/c", "acct", "account", "your", "block", "report", "call", "bank card", "card no",
        "customer care", "sms", "dispute", "the bank", "avl", "balance", "self"
    )

    fun parse(body: String): Payee? {
        val text = body.replace(Regex("\\s+"), " ").trim()
        val vpa = vpaRegex.find(text)?.groupValues?.get(1)?.lowercase()
        val name = namedPatterns.firstNotNullOfOrNull { pattern ->
            pattern.findAll(text)
                .map { cleanName(it.groupValues[1]) }
                .firstOrNull { it != null && (vpa == null || !it.contains('@')) }
        }

        return when {
            vpa != null -> Payee(key = "V:$vpa", name = name ?: vpa)
            name != null -> Payee(key = "N:" + normaliseKey(name), name = name)
            else -> null
        }
    }

    private fun cleanName(raw: String): String? {
        val name = raw.trim().trim('.', ',', '-', '*', ' ').replace(Regex("\\s+"), " ")
        if (name.length < 2) return null
        if (name.count { it.isLetter() } < 2) return null
        val lower = name.lowercase()
        if (junkWords.any { lower.contains(it) }) return null
        if (Regex("""^(?:x+|\*+)?\d+$""").matches(lower)) return null
        return name
    }

    fun normaliseKey(name: String): String =
        name.uppercase().replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
