package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTombstoneTest {

    private fun tombstone(
        messageId: Long = 4211L,
        messageDate: Long = 1_756_730_000_000L,
        address: String = "BT-SBICRD-S",
        deletedAt: Long = 1_756_730_500_000L
    ) = MessageTombstone(messageId, messageDate, address, deletedAt)

    @Test
    fun matchesTheRowItWasCreatedFor() {
        val t = tombstone()
        assertTrue(t.matches(t.messageDate, "BT-SBICRD-S"))
    }

    @Test
    fun operatorPrefixChangeStillMatchesSameSender() {
        val t = tombstone(address = "BT-SBICRD-S")
        assertTrue(t.matches(t.messageDate, "VM-SBICRD-S"))
    }

    @Test
    fun recycledIdIsNotHidden() {
        // The provider reuses _id of a deleted top row, so a later message can
        // inherit the id of a deleted OTP. Different date means different message.
        val deletedOtp = tombstone(messageId = 4211L, messageDate = 1_756_730_000_000L, address = "VM-HDFCBI-S")
        val bankMessageThatReusedTheId = 1_756_744_000_000L
        assertFalse(deletedOtp.matches(bankMessageThatReusedTheId, "BT-SBICRD-S"))
    }

    @Test
    fun sameTimestampDifferentSenderIsNotHidden() {
        val t = tombstone(address = "VM-HDFCBI-S")
        assertFalse(t.matches(t.messageDate, "BT-SBICRD-S"))
    }

    @Test
    fun unknownDateNeverHidesAnything() {
        assertFalse(tombstone(messageDate = 0L).matches(0L, "BT-SBICRD-S"))
        assertFalse(tombstone(messageDate = -1L).matches(-1L, "BT-SBICRD-S"))
    }

    @Test
    fun roundTripsThroughPreferences() {
        val original = tombstone()
        val restored = MessageTombstone.deserialize(original.messageId, original.serialize())
        assertEquals(original, restored)
    }

    @Test
    fun roundTripsSenderContainingSeparator() {
        val original = tombstone(address = "WEIRD|SENDER|ID")
        val restored = MessageTombstone.deserialize(original.messageId, original.serialize())
        assertEquals(original, restored)
    }

    @Test
    fun legacyBareTimestampEntriesAreDropped() {
        // v1 stored a bare Long against the id with no way to tell messages apart.
        assertNull(MessageTombstone.deserialize(4211L, 1_756_730_500_000L))
    }

    @Test
    fun malformedEntriesAreDropped() {
        assertNull(MessageTombstone.deserialize(4211L, "garbage"))
        assertNull(MessageTombstone.deserialize(4211L, "1|123|456|SENDER"))
        assertNull(MessageTombstone.deserialize(4211L, "2|123|0|SENDER"))
        assertNull(MessageTombstone.deserialize(4211L, "2|abc|456|SENDER"))
        assertNull(MessageTombstone.deserialize(4211L, null))
    }
}
