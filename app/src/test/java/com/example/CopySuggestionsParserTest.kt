package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopySuggestionsParserTest {

    @Test
    fun testScreenshotMessage() {
        val msg = "Your SBI Card is sent on 20-AUG-26 vide BlueDart AWB #36332863501. You can track the same on https://acl.cc/BLUDRT/OaKqmVNK"
        val suggestions = CopySuggestionsParser.extract(msg)
        
        println("Extracted suggestions: $suggestions")
        assertTrue(suggestions.contains("20-AUG-26"))
        assertTrue(suggestions.contains("#36332863501"))
        assertTrue(suggestions.contains("36332863501"))
        assertTrue(suggestions.contains("https://acl.cc/BLUDRT/OaKqmVNK"))
    }

    @Test
    fun testOtpAndAmounts() {
        val msg = "Your OTP for txn of Rs. 1,450.00 on card ending 4321 is 849201. Valid for 10 mins."
        val suggestions = CopySuggestionsParser.extract(msg)
        
        println("Extracted OTP suggestions: $suggestions")
        assertTrue(suggestions.contains("Rs. 1,450.00"))
        assertTrue(suggestions.contains("4321"))
        assertTrue(suggestions.contains("849201"))
    }

    @Test
    fun testPhoneNumberAndCodes() {
        val msg = "Call +91 98765 43210 or reference TXN-998822 for queries."
        val suggestions = CopySuggestionsParser.extract(msg)
        
        println("Extracted phone suggestions: $suggestions")
        assertTrue(suggestions.contains("+91 98765 43210") || suggestions.contains("98765 43210"))
        assertTrue(suggestions.contains("TXN-998822"))
    }
}
