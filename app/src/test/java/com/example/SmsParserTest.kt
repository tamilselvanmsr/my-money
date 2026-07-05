package com.example

import com.example.utils.SmsFilterUtility
import com.example.utils.SmsParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive SmsParser unit tests covering all transaction categories, banks, and edge cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsParserTest {

    // ─── EXPENSE: UPI / Debit ─────────────────────────────────────────────────

    @Test fun `SBI UPI debit parses amount and account ref`() {
        val result = SmsParser.parseOffline(
            "Dear UPI user A/C x5300 debited by 500 on 01Jan26 trf to AMAZON Refno 12345.",
            "VM-SBIUPI"
        )
        assertNotNull(result)
        assertEquals(500.0, result!!.amount, 0.01)
        assertEquals("5300", result.accountRef)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `HDFC UPI debit parses correctly`() {
        val result = SmsParser.parseOffline(
            "Rs.1200.00 debited from HDFC Bank a/c **4321 via UPI on 15-Mar-26.",
            "VM-HDFCBK"
        )
        assertNotNull(result)
        assertEquals(1200.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `ICICI debit SMS parses amount and ref`() {
        val result = SmsParser.parseOffline(
            "ICICI Bank: INR 850.00 debited from account XX5678 on 10/04/2026.",
            "VM-ICICIB"
        )
        assertNotNull(result)
        assertEquals(850.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `Axis Bank debit parses correctly`() {
        val result = SmsParser.parseOffline(
            "INR 500.00 has been debited from Axis Bank A/c no. XX3456 on 05-Feb-26.",
            "VM-AXISBK"
        )
        assertNotNull(result)
        assertEquals(500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `Kotak debit SMS parses amount`() {
        val result = SmsParser.parseOffline(
            "Dear Customer, Rs.2500 debited from Kotak A/c xx9876 on 20-01-2026.",
            "VM-KOTAKB"
        )
        assertNotNull(result)
        assertEquals(2500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    // ─── INCOME: Credit / Salary ─────────────────────────────────────────────

    @Test fun `Indian Bank credit SMS parses correctly`() {
        val result = SmsParser.parseOffline(
            "Rs.173.00 credited to a/c *2045 on 19/06/2026 by a/c linked to VPA tamilselvanmsr@oksbi (UPI Ref no 254123452345). Indian bank.",
            "VM-INDBNK"
        )
        assertNotNull(result)
        assertEquals(173.0, result!!.amount, 0.01)
        assertEquals("2045", result.accountRef)
        assertEquals("INCOME", result.type)
    }

    @Test fun `Salary credit SMS parses as income`() {
        val result = SmsParser.parseOffline(
            "Salary of Rs. 45000.00 received in account ending in 0123 on 01-Jul-26.",
            "VM-HDFCBK"
        )
        assertNotNull(result)
        assertEquals(45000.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
    }

    @Test fun `HDFC credit with balance notification`() {
        val result = SmsParser.parseOffline(
            "HDFC Bank: Rs. 5000.00 credited to your account XX9872. Available balance: Rs. 30210.12.",
            "VM-HDFCBK"
        )
        assertNotNull(result)
        assertEquals(5000.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals(30210.12, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `SBI credit with account reference`() {
        val result = SmsParser.parseOffline(
            "Dear SBI Customer,your A/C XXXXXX5678 is credited Rs.3,250.00 on 10-06-26 by UPI.",
            "VM-SBINB"
        )
        assertNotNull(result)
        assertEquals(3250.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
    }

    // ─── Credit Card: Expense ─────────────────────────────────────────────────

    @Test fun `SBI credit card spend parses merchant and amount`() {
        val result = SmsParser.parseOffline(
            "Rs.165.00 spent on your SBI Credit Card ending with 6928 at GMART on 19-06-26 via UPI (Ref No. 4524532432).",
            "VM-SBICRD"
        )
        assertNotNull(result)
        assertEquals(165.0, result!!.amount, 0.01)
        assertEquals("6928", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertEquals("GMART", result.title)
    }

    @Test fun `HDFC credit card debit parses correctly`() {
        val result = SmsParser.parseOffline(
            "Dear customer, SBI Card ending in 7281 is debited of INR 9500.00 at AMZN.",
            "VM-SBIUPI"
        )
        assertNotNull(result)
        assertEquals(9500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `CC payment with available limit`() {
        val result = SmsParser.parseOffline(
            "ICICI Bank Credit Card XX1234: INR 1,500.00 spent at ZOMATO on 12-May-26. Available credit limit: INR 28,500.00.",
            "VM-ICICIB"
        )
        assertNotNull(result)
        assertEquals(1500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    // ─── Balance Sync / Available Balance SMS ────────────────────────────────

    @Test fun `Balance notification SMS returns availableBalance`() {
        val result = SmsParser.parseOffline(
            "Your a/c XX1234 has avl bal of Rs.12,500.00 as on 01-Jul-26.",
            "VM-HDFCBK"
        )
        assertNotNull(result)
        assertNotNull(result!!.availableBalance)
        assertEquals(12500.0, result.availableBalance!!, 0.01)
    }

    @Test fun `Balance SMS with credited transaction also sets availableBalance`() {
        val result = SmsParser.parseOffline(
            "HDFC Bank: Rs. 1000.00 credited to your account XX9872. Available balance: Rs. 30210.12.",
            "VM-HDFCBK"
        )
        assertNotNull(result)
        assertEquals(30210.12, result!!.availableBalance ?: 0.0, 0.01)
    }

    // ─── Filter: should be excluded ──────────────────────────────────────────

    @Test fun `OTP SMS should not be a valid transaction`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Your OTP for login is 5432. Do not share."))
    }

    @Test fun `Promotional SMS should not be a valid transaction`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Apply for a pre-approved loan of up to Rs. 5 Lakhs!"))
    }

    @Test fun `EMI reminder should not be a valid transaction`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Your EMI payment of Rs. 15000 is due on 2026-06-30."))
    }

    @Test fun `Mandate registration should not be a valid transaction`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Mandate request registered for account 5432."))
    }

    @Test fun `Loan offer with URL should not be a valid transaction`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms(
            "Dear 97912XX, Rs.50,000* load is ready to be credited to your bank A/c on 22.04.2026. Check eligibility http://h27.in/Rfc."
        ))
    }

    // ─── Amount parsing edge cases ────────────────────────────────────────────

    @Test fun `Parses comma-formatted amount correctly`() {
        val result = SmsParser.parseOffline(
            "INR 1,23,456.78 debited from account XX1234.",
            "VM-SBINB"
        )
        assertNotNull(result)
        assertEquals(123456.78, result!!.amount, 0.01)
    }

    @Test fun `Parses amount without decimal places`() {
        val result = SmsParser.parseOffline(
            "Rs 200 debited from a/c xx3421.",
            "VM-INDBNK"
        )
        assertNotNull(result)
        assertEquals(200.0, result!!.amount, 0.01)
    }

    @Test fun `Zero amount SMS is rejected or yields null`() {
        val result = SmsParser.parseOffline(
            "Rs.0.00 debited from a/c XX9999.",
            "VM-AXISBK"
        )
        // Either null or amount > 0
        if (result != null) assertTrue("Zero amount should not be treated as valid", result.amount > 0)
    }

    // ─── Account ref parsing ─────────────────────────────────────────────────

    @Test fun `Account ref with last-4 digits extracted`() {
        val result = SmsParser.parseOffline(
            "ICICI Bank: INR 300.00 debited from account XX5678 on 10/04/2026.",
            "VM-ICICIB"
        )
        assertNotNull(result)
        assertEquals("5678", result!!.accountRef)
    }

    @Test fun `SBI Ac with slash format extracts ref`() {
        val result = SmsParser.parseOffline(
            "Your SBI a/c xx1230 is debited with Rs 500 on 01Jul26.",
            "VM-SBINB"
        )
        assertNotNull(result)
        assertNotNull(result!!.accountRef)
        assertTrue(result.accountRef!!.endsWith("1230") || result.accountRef == "1230")
    }

    // ─── Wallet / Paytm / PhonePe ────────────────────────────────────────────

    @Test fun `Paytm debit parses as EXPENSE`() {
        val result = SmsParser.parseOffline(
            "Paytm: Rs.350.00 paid to BIGBASKET on 12-Jun-26. UPI Ref: 9876543210.",
            "PAYTM"
        )
        assertNotNull(result)
        assertEquals(350.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `PhonePe debit parses as EXPENSE`() {
        val result = SmsParser.parseOffline(
            "PhonePe: Rs.150.00 debited for payment to SWIGGY on 05-Jul-26.",
            "PHONEPE"
        )
        assertNotNull(result)
        assertEquals(150.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    // ─── SmsFilterUtility: valid cases ──────────────────────────────────────

    @Test fun `Standard debit SMS is classified as valid`() {
        assertTrue(SmsFilterUtility.isValidTransactionSms(
            "Dear customer, SBI Card ending in 7281 is debited of INR 9,500.00 at AMZN"
        ))
    }

    @Test fun `Standard credit SMS is classified as valid`() {
        assertTrue(SmsFilterUtility.isValidTransactionSms(
            "HDFC account ending 9872 was credited with Rs 1,450.00"
        ))
    }

    @Test fun `UPI squished debit SMS is classified as valid`() {
        assertTrue(SmsFilterUtility.isValidTransactionSms(
            "Dear UPI user A/C x5300 debited by 173on date 19Jun26 trf to M TAMILSELVAN Refno 254123452345."
        ))
    }

    // ─── Merchant / Title parsing ────────────────────────────────────────────

    @Test fun `Merchant name extracted from 'at MERCHANT'`() {
        val result = SmsParser.parseOffline(
            "Rs.165.00 spent on your SBI Credit Card ending with 6928 at GMART on 19-06-26.",
            "VM-SBICRD"
        )
        assertNotNull(result)
        assertFalse("Title should not be empty", result!!.title.isBlank())
    }

    @Test fun `Transfer payee extracted from 'trf to NAME'`() {
        val result = SmsParser.parseOffline(
            "Dear UPI user A/C x5300 debited by 173on date 19Jun26 trf to M TAMILSELVAN Refno 254123452345.",
            "VM-SBIUPI"
        )
        assertNotNull(result)
        assertFalse("Title should not be empty", result!!.title.isBlank())
    }
}
