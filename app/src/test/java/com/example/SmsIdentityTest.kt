package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SmsIdentityTest {

    @Test
    fun sameMessageProducesTheSameKey() {
        val key = SmsIdentity.key(1_756_730_000_000L, "BT-SBICRD-S", "Rs.120.00 spent on your SBI Credit Card")
        val again = SmsIdentity.key(1_756_730_000_000L, "BT-SBICRD-S", "Rs.120.00 spent on your SBI Credit Card")
        assertEquals(key, again)
        assertEquals(64, key.length)
    }

    @Test
    fun operatorPrefixDoesNotChangeTheKey() {
        val hyphen = SmsIdentity.key(1_756_730_000_000L, "BT-SBICRD-S", "Rs.120.00 spent")
        val otherPrefix = SmsIdentity.key(1_756_730_000_000L, "VM-SBICRD-S", "Rs.120.00 spent")
        assertEquals(hyphen, otherPrefix)
    }

    @Test
    fun snippetWhitespaceIsNormalized() {
        val compact = SmsIdentity.key(1L, "BT-HDFCGI-S", "Rs.20 spent   at SHOP")
        val padded = SmsIdentity.key(1L, "BT-HDFCGI-S", "  Rs.20 spent at SHOP  ")
        assertEquals(compact, padded)
    }

    @Test
    fun differentTimestampIsADifferentMessage() {
        val first = SmsIdentity.key(1L, "BT-SBICRD-S", "Rs.120.00 spent")
        val second = SmsIdentity.key(2L, "BT-SBICRD-S", "Rs.120.00 spent")
        assertNotEquals(first, second)
    }

    @Test
    fun recycledProviderIdWouldHaveCollidedButContentDoesNot() {
        val otp = SmsIdentity.key(1_756_730_000_000L, "VM-HDFCBI-S", "123456 is your OTP")
        val debit = SmsIdentity.key(1_756_744_000_000L, "BT-SBICRD-S", "Rs.120.00 spent on your SBI Credit Card")
        assertNotEquals(otp, debit)
    }
}
