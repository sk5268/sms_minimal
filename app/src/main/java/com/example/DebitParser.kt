package com.example

object DebitParser {

    data class DebitParseResult(
        val amountPaise: Long,
        val snippet: String
    )

    private val creditCues = listOf(
        "credited", "received", "refund", "cashback", "deposited",
        " cr ", "credit of", "salary", "interest credited"
    )

    private val debitCues = listOf(
        "debited", "spent", "paid", "withdrawn", "purchase", "txn",
        "transaction", "upi", " dr", "sent to", "deducted", "payment",
        "transferred to", "transfer to", "paid to", "purchase at"
    )

    private val amountPatterns = listOf(
        Regex("""₹\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""Rs\.?\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""INR\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        Regex("""Rupees\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    )

    fun parse(body: String, sender: String = ""): DebitParseResult? {
        val lower = body.lowercase()
        if (creditCues.any { lower.contains(it) }) return null
        if (!debitCues.any { lower.contains(it) }) return null

        val amountPaise = extractAmountPaise(body) ?: return null
        if (amountPaise <= 0) return null

        val snippet = body.trim().replace(Regex("\\s+"), " ").take(160)
        return DebitParseResult(amountPaise, snippet)
    }

    fun normalizeSenderKey(sender: String): String {
        return sender.trim().uppercase().replace(Regex("[^A-Z0-9-]"), "")
    }

    private fun extractAmountPaise(body: String): Long? {
        for (pattern in amountPatterns) {
            val match = pattern.find(body) ?: continue
            val raw = match.groupValues[1].replace(",", "")
            val paise = parseToPaise(raw) ?: continue
            if (paise > 0) return paise
        }
        return null
    }

    private fun parseToPaise(raw: String): Long? {
        return try {
            if (raw.contains('.')) {
                val parts = raw.split('.')
                val rupees = parts[0].toLongOrNull() ?: return null
                val frac = parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2)
                val paisePart = frac.toLongOrNull() ?: return null
                rupees * 100 + paisePart
            } else {
                val rupees = raw.toLongOrNull() ?: return null
                rupees * 100
            }
        } catch (_: Exception) {
            null
        }
    }
}
