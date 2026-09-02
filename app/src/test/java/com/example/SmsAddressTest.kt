package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAddressTest {

    @Test
    fun dltSendersWithDifferentOperatorPrefixesAreTheSameBank() {
        assertTrue(SmsAddress.addressesMatch("BT-HDFCGI-S", "VM-HDFCGI-S"))
        assertTrue(SmsAddress.addressesMatch("BT-HDFCGI-S", "AD-HDFCGI-S"))
        assertTrue(SmsAddress.addressesMatch("bt-hdfcgi-s", "BT-HDFCGI-S"))
        assertTrue(SmsAddress.addressesMatch("BTHDFCGIS", "BT-HDFCGI-S"))
        assertTrue(SmsAddress.addressesMatch("HDFCGI", "BT-HDFCGI-S"))
    }

    @Test
    fun differentBanksDoNotMatch() {
        assertFalse(SmsAddress.addressesMatch("BT-HDFCGI-S", "BT-HDFCBI-S"))
        assertFalse(SmsAddress.addressesMatch("VK-SWIGGY-S", "BT-HDFCGI-S"))
    }

    @Test
    fun identicalAlphanumericMatchesEvenWithoutDigits() {
        assertTrue(SmsAddress.addressesMatch("AMAZON", "AMAZON"))
        assertTrue(SmsAddress.addressesMatch("AMAZON", "amazon"))
    }

    @Test
    fun entityKeyExtractsDltMiddleToken() {
        assertEquals("HDFCGI", SmsAddress.entityKey("BT-HDFCGI-S"))
        assertEquals("HDFCGI", SmsAddress.entityKey("vm-hdfcgi-s"))
        assertEquals("HDFCBI", SmsAddress.entityKey("JK-HDFCBI-S"))
        assertEquals("HDFCGI", SmsAddress.entityKey("HDFCGI"))
        assertEquals("HDFCGI", SmsAddress.entityKey("BTHDFCGIS"))
    }

    @Test
    fun sqlLikePatternsCoverOperatorPrefixVariants() {
        val patterns = SmsAddress.sqlLikePatterns("BT-HDFCGI-S")
        assertTrue(patterns.contains("BT-HDFCGI-S"))
        assertTrue(patterns.contains("%-HDFCGI-%"))
    }

    @Test
    fun phoneNumbersUseInjectedComparator() {
        var compared = false
        val match = SmsAddress.addressesMatch(
            "+919876543210",
            "9876543210",
            phoneCompare = { _, _ ->
                compared = true
                true
            }
        )
        assertTrue(match)
        assertTrue(compared)
    }
}
