package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpClassifierTest {

    // Transaction alerts that mention OTP only as anti-fraud boilerplate. Deleting
    // these is the reported data loss, so none of them may be auto-deletable.
    private val bankAlerts = listOf(
        "Rs.500.00 debited from A/c XX3083 on 02-09-26. Avl Bal Rs.12345.67. Never share OTP or PIN with anyone. -SBI",
        "Rs.2,499 spent on your HDFC Bank Card ending 4521 at AMAZON. Ref 845123. Bank never asks for OTP/CVV.",
        "INR 15000 debited via UPI txn 402931847263 on 02-09. Do not share your OTP with anyone.",
        "Rs.120.00 spent on your SBI Credit Card ending with 3083 at SRISAIGANESH on 01-09-26 via UPI (Ref No. 825578291518). Trxn. not done by you? Report at https://sbicard.com/Dispute",
        "Rs.5000 credited to your A/c XX1234 on 02-09-26. Avl Bal Rs.98765.43. Never share OTP with anyone."
    )

    private val genuineOtps = listOf(
        "123456 is your OTP for login. Valid for 10 minutes. Do not share.",
        "Your OTP is 483920. Valid for 5 minutes.",
        "Use one time password 8371 to complete your registration.",
        "Your verification code is 552134."
    )

    @Test
    fun bankTransactionAlertsAreNeverAutoDeleted() {
        for (body in bankAlerts) {
            assertFalse("must not auto-delete: $body", OtpClassifier.isAutoDeletable(body))
        }
    }

    @Test
    fun genuineOtpsAreAutoDeletable() {
        for (body in genuineOtps) {
            assertTrue("should auto-delete: $body", OtpClassifier.isAutoDeletable(body))
        }
    }

    @Test
    fun otpMustBeAWholeWord() {
        // Substring matching used to fire on any body containing the letters "otp".
        assertNull(OtpClassifier.extractOtp("Your parcel from SHOPTOPIA 4821 is out for delivery."))
        assertNull(OtpClassifier.extractOtp("Booking 4821 confirmed at HOTPOT KITCHEN."))
    }

    @Test
    fun slashDelimitedOtpWarningIsNotDeletable() {
        // Offered for copy, but " OTP " is not space delimited so it is never purged.
        val body = "Ref 845123. Bank never asks for OTP/CVV."
        assertEquals("845123", OtpClassifier.extractOtp(body))
        assertFalse(OtpClassifier.isAutoDeletable(body))
    }

    @Test
    fun otpAtStartOrEndOfBodyStillQualifies() {
        assertTrue(OtpClassifier.isAutoDeletable("OTP 447281 for your login request"))
        assertTrue(OtpClassifier.isAutoDeletable("Login code 447281, requested via OTP"))
    }

    @Test
    fun anyQuotedAmountBlocksAutoDeletion() {
        // Payment OTPs carry an amount the user may need as a record.
        assertFalse(OtpClassifier.isAutoDeletable("Your OTP is 447281 for a txn of Rs.2500 on your card."))
        assertTrue(OtpClassifier.isAutoDeletable("Your OTP is 447281 for your card."))
    }

    @Test
    fun extractionStillPrefersSixDigitCodes() {
        assertEquals("483920", OtpClassifier.extractOtp("Your OTP is 483920, ref 4821."))
    }

    @Test
    fun nonOtpMessagesAreIgnoredEntirely() {
        assertNull(OtpClassifier.extractOtp("Your order 12345 has shipped."))
        assertFalse(OtpClassifier.isAutoDeletable("Your order 12345 has shipped."))
    }
}
