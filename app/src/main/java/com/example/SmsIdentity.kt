package com.example

import java.security.MessageDigest

/**
 * Stable identity for an SMS used by Finance. Not the telephony `_id`: that
 * value is reused when a top row is deleted, so a later message would inherit
 * someone else's debit or ignore record.
 *
 * The key is a hash of when it arrived, which bank sent it, and the same 160
 * character snippet already stored on each debit row. Existing history can
 * therefore be migrated without looking the row back up in the provider.
 */
object SmsIdentity {

    fun key(occurredAt: Long, sender: String, snippet: String): String {
        val payload = "$occurredAt\n${SmsAddress.entityKey(sender)}\n${normalizeSnippet(snippet)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun normalizeSnippet(snippet: String): String =
        snippet.trim().replace(Regex("\\s+"), " ").take(160)
}
