package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayeeParserTest {

    private fun name(body: String) = PayeeParser.parse(body)?.name
    private fun key(body: String) = PayeeParser.parse(body)?.key

    @Test
    fun axisUpiMerchant() {
        val body = "INR 100.00 debited A/c no. XX5713 16-08-26, 11:50:15 UPI/P2M/123456789012/PADMAVATHI TRADERS Not you? SMS BLOCKUPI Cust ID to 919999999999 Axis Bank"
        assertEquals("PADMAVATHI TRADERS", name(body))
        assertEquals("N:PADMAVATHI TRADERS", key(body))
    }

    @Test
    fun axisUpiPerson() {
        val body = "INR 469.39 debited A/c no. XX5713 13-08-26, 11:02:25 UPI/P2A/123456789012/H AKSHAY KUMAR Not you? SMS BLOCKUPI Cust ID to 919999999999 Axis Bank"
        assertEquals("H AKSHAY KUMAR", name(body))
    }

    @Test
    fun sbiCardUpi() {
        val body = "Rs.120.00 spent on your SBI Credit Card ending with 3083 at SRIBALAJISWEETSANDC on 24-08-26 via UPI (Ref No. 123456789012). Trxn. not done by you? Report at https://sbicard.com/Dispute"
        assertEquals("SRIBALAJISWEETSANDC", name(body))
    }

    @Test
    fun sbiCardSwipeWithSlashDate() {
        val body = "Rs.1,188.92 spent on your SBI Credit Card ending 3083 at JubilantFoodWorksLimi on 23/08/26. Trxn. not done by you? Report at https://sbicard.com/Dispute"
        assertEquals("JubilantFoodWorksLimi", name(body))
        assertEquals("N:JUBILANTFOODWORKSLIMI", key(body))
    }

    @Test
    fun idfcCardMerchantNotTime() {
        val body = "Delicious Purchase! INR 20.00 spent on your IDFC FIRST Bank Credit Card ending XX1345 at Mr Bhagvan Singh on 17 AUG 2026 at 06:41 PM Avbl Limit: INR 12618.5"
        assertEquals("Mr Bhagvan Singh", name(body))
    }

    @Test
    fun rblCard() {
        val body = "INR765.00 spent at DAYANAND NAGAR on RBL Bank credit card (2530) on 27-08-2026.AVL limit- INR68,263.00. Not you? Call 18001234567"
        assertEquals("DAYANAND NAGAR", name(body))
    }

    @Test
    fun phonePeTo() {
        val body = "You've paid Rs.30 via PhonePe gift card to SRI SAI BABA CHAT BHANDAR 02 on Aug 28, 2026 at 8:07:12 PM. Not you? Call us on 022-12345678."
        assertEquals("SRI SAI BABA CHAT BHANDAR 02", name(body))
    }

    @Test
    fun idfcStandingInstruction() {
        val body = "INR 824.82 for WWW AIRTEL IN paid from your IDFC FIRST Bank Credit Card XX0335 on 05/08/2026 basis standing instruction (SI)."
        assertEquals("WWW AIRTEL IN", name(body))
    }

    @Test
    fun sbiTrfTo() {
        val body = "Dear UPI user A/C X1234 debited by 120.0 on date 18Nov26 trf to RAVI KUMAR Refno 612345678901. If not u? call 1800111109. -SBI"
        assertEquals("RAVI KUMAR", name(body))
    }

    @Test
    fun hdfcSentTo() {
        val body = "Sent Rs.120.00 From HDFC Bank A/C *1234 To RAVI KUMAR On 18/11/26 Ref 612345678901 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
        assertEquals("RAVI KUMAR", name(body))
    }

    @Test
    fun iciciCredited() {
        val body = "ICICI Bank Acct XX123 debited for Rs 250.00 on 18-Nov-26; ABC STORE credited. UPI:612345678901. Call 18002662 for dispute."
        assertEquals("ABC STORE", name(body))
    }

    @Test
    fun upiIdIsTheKeyWhenPresent() {
        val body = "Sent Rs.100.00 from Kotak Bank AC X1234 to paytmqr2810050501011abc@paytm on 18-11-26.UPI Ref 612345678901."
        assertEquals("V:paytmqr2810050501011abc@paytm", key(body))
    }

    @Test
    fun emailIsNotAUpiId() {
        val body = "Rs.500 debited for your order. Mail support@example.com for help"
        assertEquals(null, PayeeParser.parse(body)?.key?.takeIf { it.startsWith("V:") })
    }

    @Test
    fun junkIsRejected() {
        assertNull(name("Thank you for payment of Rs. 915 towards your HDFC ERGO OS policy with reference no. ABC."))
    }
}
