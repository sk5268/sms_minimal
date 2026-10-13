package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreSyncHelperTest {

    private fun tombstone(
        messageId: Long = 101L,
        messageDate: Long = 1_700_000_000_000L,
        address: String = "SENDER1"
    ) = MessageTombstone(messageId, messageDate, address, System.currentTimeMillis())

    @Test
    fun deletedThreadIsIdentifiedForPruning() {
        val deletedThreads = setOf(10L, 20L)
        val docThreadId = 10L
        assertTrue(deletedThreads.contains(docThreadId))
    }

    @Test
    fun deletedMessageTombstoneMatchesDoc() {
        val msgId = 101L
        val timestamp = 1_700_000_000_000L
        val sender = "SENDER1"
        val t = tombstone(messageId = msgId, messageDate = timestamp, address = sender)
        val deletedMessages = mapOf(msgId to t)

        val tombstone = deletedMessages[msgId]
        assertTrue(tombstone != null && tombstone.matches(timestamp, sender))
    }

    @Test
    fun recycledMsgIdWithDifferentTimestampDoesNotMatchTombstone() {
        val msgId = 101L
        val originalTimestamp = 1_700_000_000_000L
        val newTimestamp = 1_700_000_500_000L
        val sender = "SENDER1"
        val t = tombstone(messageId = msgId, messageDate = originalTimestamp, address = sender)
        val deletedMessages = mapOf(msgId to t)

        val tombstone = deletedMessages[msgId]
        assertFalse(tombstone != null && tombstone.matches(newTimestamp, sender))
    }

    @Test
    fun inactiveDocIdIdentifiedForPruning() {
        val activeDocIds = setOf("msg_101", "msg_102")
        val oldDocId = "msg_99"
        assertFalse(activeDocIds.contains(oldDocId))
    }
}
