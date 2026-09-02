package com.example

/**
 * Identity of a soft-deleted SMS row.
 *
 * The telephony `sms` table declares `_id INTEGER PRIMARY KEY` without
 * AUTOINCREMENT, so SQLite hands out `max(rowid) + 1` and reuses the id of a
 * deleted top row. The newest message always holds the highest id, and that is
 * exactly the row this app removes (OTP expiry, notification delete), so the
 * next incoming SMS routinely inherits a freed id. A tombstone keyed on the id
 * alone would then hide, and six hours later permanently delete, an unrelated
 * message. Every tombstone therefore records the date and address of the row it
 * was created for and must be matched against the live row before it is honoured.
 */
data class MessageTombstone(
    val messageId: Long,
    val messageDate: Long,
    val address: String,
    val deletedAt: Long
) {
    /** True only when [rowDate]/[rowAddress] belong to the row this tombstone was created for. */
    fun matches(rowDate: Long, rowAddress: String?): Boolean {
        if (messageDate <= 0L || rowDate != messageDate) return false
        if (address.isBlank()) return true
        val candidate = rowAddress?.trim().orEmpty()
        if (candidate.isBlank()) return true
        return SmsAddress.addressesMatch(candidate, address)
    }

    fun serialize(): String =
        listOf(VERSION.toString(), deletedAt.toString(), messageDate.toString(), address).joinToString(SEPARATOR)

    companion object {
        private const val VERSION = 2
        private const val SEPARATOR = "|"

        /**
         * Returns null for anything that cannot be identity-checked, including
         * v1 entries which stored a bare `Long` timestamp against the id. Those
         * are the entries capable of destroying an unrelated message, so they
         * are dropped rather than migrated.
         */
        fun deserialize(messageId: Long, raw: Any?): MessageTombstone? {
            val text = raw as? String ?: return null
            val parts = text.split(SEPARATOR, limit = 4)
            if (parts.size < 4 || parts[0].toIntOrNull() != VERSION) return null
            val deletedAt = parts[1].toLongOrNull() ?: return null
            val messageDate = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
            return MessageTombstone(messageId, messageDate, parts[3], deletedAt)
        }
    }
}
