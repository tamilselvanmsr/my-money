package com.example

import com.example.utils.SmsFilterUtility
import com.example.utils.SmsParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Unit tests verifying the strict regex-based SMS filtering utility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testValidTransactionSms() {
        val validSmsList = listOf(
            "Dear customer, SBI Card ending in 7281 is debited of INR 9,500.00 at AMZN",
            "HDFC account ending 9872 was credited with Rs 1,450.00",
            "Transaction recorded! spent INR 12,000.00 on card ending in 432",
            "Salary of Rs. 45,000.00 received in account ending in 012",
            "Amt Deducted Rs. 200 from a/c xx3421",
            "Dear UPI user A/C x5300 debited by 173on date 19Jun26 trf to J VERMA Refno 254123452345."
        )

        for (sms in validSmsList) {
            assertTrue("Should be classified as VALID transaction: '$sms'", SmsFilterUtility.isValidTransactionSms(sms))
        }
    }

    @Test
    fun testInvalidSmsExcluded() {
        val invalidSmsList = listOf(
            "Your OTP for login is 5432. Do not share.",
            "Your EMI payment of Rs. 15,000 is due on 2026-06-30.",
            "Apply for a pre-approved loan of up to Rs. 5 Lakhs!",
            "Mandate request registered for account 5432.",
            "This is just a general bank alert about maintenance.",
            "Dear 97912XX, Rs.50,000* load is ready to be credited to your bank A/c on 22.04.2026. Check your eligibility now http://h27.in/Rfc."
        )

        for (sms in invalidSmsList) {
            assertFalse("Should be classified as INVALID transaction: '$sms'", SmsFilterUtility.isValidTransactionSms(sms))
        }
    }

    @Test
    fun testParseUserSmsWithSquishedDetails() {
        val debitSms = "Dear UPI user A/C x5300 debited by 173on date 19Jun26 trf to J VERMA Refno 254123452345."
        val result = SmsParser.parseOffline(debitSms, "VM-SBI-S")
        
        assertNotNull("Debit SMS should be successfully parsed", result)
        assertEquals(173.0, result!!.amount, 0.0)
        assertEquals("5300", result.accountRef)
        assertEquals("EXPENSE", result.type)
        
        // Check date: June 19, 2026
        assertNotNull("Timestamp should not be null", result.parsedTimestamp)
        val cal = Calendar.getInstance().apply { timeInMillis = result.parsedTimestamp!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testParseUserCreditSms() {
        val creditSms = "Rs.173.00 credited to a/c *2045 on 19/06/2026 by a/c linked to VPA jkverma@oksbi (UPI Ref no 254123452345). Indian bank."
        val result = SmsParser.parseOffline(creditSms, "JD-INDBK-S")
        
        assertNotNull("Credit SMS should be successfully parsed", result)
        assertEquals(173.0, result!!.amount, 0.0)
        assertEquals("2045", result.accountRef)
        assertEquals("INCOME", result.type)
        
        // Check date: June 19, 2026
        assertNotNull("Timestamp should not be null", result.parsedTimestamp)
        val cal = Calendar.getInstance().apply { timeInMillis = result.parsedTimestamp!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testParseUserSbiCreditCardSms() {
        val sms = "Rs.165.00 spent on your SBI Credit Card ending with 6928 at GMART on 19-06-26 via UPI (Ref No. 4524532432)."
        assertTrue("SBI Credit Card SMS should be classified as valid transaction", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "AX-SBICC-S")
        assertNotNull("SBI Credit Card SMS should be successfully parsed", result)
        assertEquals(165.0, result!!.amount, 0.0)
        assertEquals("6928", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("Gmart", result.title)
        
        // Check date: June 19, 2026
        assertNotNull("Timestamp should not be null", result.parsedTimestamp)
        val cal = Calendar.getInstance().apply { timeInMillis = result.parsedTimestamp!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, cal.get(Calendar.MONTH))
        assertEquals(19, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testIndianBankAccDetection() {
        val sms = "A/c *2045 debited Rs.100.00 on 18-06-26 to CRED club. UPI: 13541234325. if not you Dial 1930 for cyber fraud - Indian Bank."
        assertTrue("Indian Bank SMS should be classified as valid transaction", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "JD-INDBK-S")
        assertNotNull("Indian Bank SMS should be successfully parsed", result)
        assertEquals(100.0, result!!.amount, 0.0)
        assertEquals("2045", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("Cred Club", result.title)
    }

    @Test
    fun testSbiPayeeSunilkumarDetection() {
        val sms = "Dear UPI user A/C X5300 debited by 110.0 on date 14Jun26 trf to Mr SUNILKUMAR SO Refno 1354123432 If not u? call 3451345 for other services-18001234-SBI."
        assertTrue("SBI transaction SMS should be classified as valid transaction", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "VM-SBI-S")
        assertNotNull("SBI transaction SMS should be successfully parsed", result)
        assertEquals(110.0, result!!.amount, 0.0)
        assertEquals("5300", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("Mr Sunilkumar So", result.title)
    }

    @Test
    fun testSbiCreditCardDreamplug() {
        val sms = "Rs. 3860.00 spent on your SBI Credit Card ending at 0562 at DREAMPLUG on 18/04/26."
        assertTrue("SBI Credit Card spent SMS should be valid", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "AX-SBICC-S")
        assertNotNull("Should parse", result)
        assertEquals(3860.0, result!!.amount, 0.0)
        assertEquals("0562", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("Dreamplug", result.title)
    }

    @Test
    fun testUdayBabyMesthaDetection() {
        val sms = "Dear UPI user A/C X5300 debited by 2.00 on date 19APr26 trf to Uday Baby Mestha Refno 151532"
        assertTrue("UPI debit SMS should be valid", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "VM-SBI-S")
        assertNotNull("Should parse", result)
        assertEquals(2.00, result!!.amount, 0.0)
        assertEquals("5300", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("Uday Baby Mestha", result.title)
    }

    @Test
    fun testMalliKanHavDetection() {
        val sms = "Sent Rs.40.00 From HDFC Bank A/C *9553 to MALLI KAN HAV on 20/04/26."
        assertTrue("HDFC Sent SMS should be valid", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "HD-HDFC-T")
        assertNotNull("Should parse", result)
        assertEquals(40.0, result!!.amount, 0.0)
        assertEquals("9553", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("Malli Kan Hav", result.title)
    }

    @Test
    fun testVigneshKumarDetection() {
        val sms = "Dear UPI User A/C x5300 debited by 2000.00 on date 03Jun2026 trf to Vignesh Kumar S Refno 2451245 If not u? call-13124 for other services-180011245-SBI."
        assertTrue("SBI transaction with hyphen call should be valid", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "VM-SBI-S")
        assertNotNull("Should parse", result)
        assertEquals(2000.0, result!!.amount, 0.0)
        assertEquals("5300", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("Vignesh Kumar S", result.title)
    }

    @Test
    fun testIndianBankImpsMobileDetection() {
        val sms = "Your a/c. XXXXX2045 is credited by Rs. 167000.00 on 18-04-26 by a/c linked mobile 9XXXXX25452 (IMPS Ref no. 2345235234). - IndianBank"
        assertTrue("Indian Bank credit message should be valid", SmsFilterUtility.isValidTransactionSms(sms))
        
        val result = SmsParser.parseOffline(sms, "JD-INDBK-S")
        assertNotNull("Should parse", result)
        assertEquals(167000.0, result!!.amount, 0.0)
        assertEquals("2045", result.accountRef)
        assertEquals("INCOME", result.type)
        assertEquals("IMPS - Mobile 9XXXXX25452", result.title)
    }

    @Test
    fun testTimestampMergingWithCarrierTime() {
        val sms = "Sent Rs.40.00 From HDFC Bank A/C *9553 to MALLI KAN HAV on 20/04/26."
        // Reference time corresponds to 2026-04-20 15:45:30
        val carrierCal = java.util.Calendar.getInstance()
        carrierCal.set(2026, java.util.Calendar.APRIL, 20, 15, 45, 30)
        val carrierTimestamp = carrierCal.timeInMillis
        
        val result = SmsParser.parseOffline(sms, "HD-HDFC-T", carrierTimestamp)
        assertNotNull(result)
        assertNotNull(result!!.parsedTimestamp)
        
        val parsedCal = java.util.Calendar.getInstance()
        parsedCal.timeInMillis = result.parsedTimestamp!!
        
        assertEquals(2026, parsedCal.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.APRIL, parsedCal.get(java.util.Calendar.MONTH))
        assertEquals(20, parsedCal.get(java.util.Calendar.DAY_OF_MONTH))
        // Verify time components were successfully merged from carrier timestamp
        assertEquals(15, parsedCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(45, parsedCal.get(java.util.Calendar.MINUTE))
        assertEquals(30, parsedCal.get(java.util.Calendar.SECOND))
    }
}
