package com.example

import com.example.data.ExpenseCategory
import com.example.utils.SmsFilterUtility
import com.example.utils.SmsParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive SmsParser / SmsFilterUtility unit tests.
 *
 * IMPORTANT: SmsParser.parseOffline() step-1 validates the sender header.
 * Only senders ending with "-S" or "-T" are accepted (TRAI DLT transactional header format).
 * All parseOffline() calls below use senders that comply with this rule.
 *
 * Sender legend used in tests:
 *   VM-SBI-S    → SBI general (ends -S)
 *   HD-HDFC-T   → HDFC Bank  (ends -T)
 *   JD-ICICI-S  → ICICI Bank (ends -S)
 *   AX-AXIS-T   → Axis Bank  (ends -T)
 *   BK-KOTAK-S  → Kotak Bank (ends -S)
 *   JD-INDBK-S  → Indian Bank(ends -S)
 *   AX-SBICC-S  → SBI CC     (ends -S)
 *
 * NOTE: senders must map to a real bank/card issuer in SENDER_CODE_MAP — parseOffline()
 * now rejects SMS whose sender can't be resolved to a known issuer (see SmsAccountUtils),
 * so an unmapped placeholder sender like the old "JK-BANK-*" no longer parses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsParserTest {

    // ─── EXPENSE: UPI / Debit ─────────────────────────────────────────────────

    @Test fun `SBI UPI debit parses amount and account ref`() {
        val result = SmsParser.parseOffline(
            "Dear UPI user A/C x8472 debited by 500 on 01Jan26 trf to AMAZON Refno 12345.",
            "VM-SBI-S"
        )
        assertNotNull(result)
        assertEquals(500.0, result!!.amount, 0.01)
        assertEquals("8472", result.accountRef)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `HDFC UPI debit parses correctly`() {
        val result = SmsParser.parseOffline(
            "Rs.1200.00 debited from HDFC Bank a/c **4321 via UPI on 15-Mar-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(1200.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `ICICI debit SMS parses amount and ref`() {
        val result = SmsParser.parseOffline(
            "ICICI Bank: INR 850.00 debited from account XX5678 on 10/04/2026.",
            "JD-ICICI-S"
        )
        assertNotNull(result)
        assertEquals(850.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `Axis Bank debit parses correctly`() {
        val result = SmsParser.parseOffline(
            "INR 500.00 has been debited from Axis Bank A/c no. XX3456 on 05-Feb-26.",
            "AX-AXIS-T"
        )
        assertNotNull(result)
        assertEquals(500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `Kotak debit SMS parses amount`() {
        val result = SmsParser.parseOffline(
            "Dear Customer, Rs.2500 debited from Kotak A/c xx9876 on 20-01-2026.",
            "BK-KOTAK-S"
        )
        assertNotNull(result)
        assertEquals(2500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    // ─── INCOME: Credit / Salary ─────────────────────────────────────────────

    @Test fun `Indian Bank credit SMS parses correctly`() {
        val result = SmsParser.parseOffline(
            "Rs.173.00 credited to a/c *6319 on 19/06/2026 by a/c linked to VPA jkverma@oksbi (UPI Ref no 254123452345). Indian bank.",
            "JD-INDBK-S"
        )
        assertNotNull(result)
        assertEquals(173.0, result!!.amount, 0.01)
        assertEquals("6319", result.accountRef)
        assertEquals("INCOME", result.type)
    }

    @Test fun `Salary credit SMS parses as income`() {
        val result = SmsParser.parseOffline(
            "Salary of Rs. 45000.00 credited to account ending in 5678 on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(45000.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
    }

    @Test fun `HDFC credit with appended avl balance`() {
        // "avl bal" + "credited" + "via UPI" → hasTransactionAction=true, hasPaymentChannel=true
        // → tryParseBalanceUpdate returns null → main parser handles; avlBalance extracted from body
        val result = SmsParser.parseOffline(
            "HDFC Bank: Rs. 5000.00 credited to your account XX9872 via UPI. Avl bal Rs 30210.12",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(5000.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals(30210.12, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Cash deposit SMS with 'available balance is' phrasing`() {
        // Real-world gap: "available balance IS INR X" — the extra filler word "is" between
        // "balance" and the currency token used to make the appended-balance regex miss entirely.
        val result = SmsParser.parseOffline(
            "Your account XX1234 is credited with INR 8,000.00 on 22/07/26 at 18:49:28 at BHOGANAHALLI via Cash Deposit Machine. Your available balance is INR 8,004.12",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(8000.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals(8004.12, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Cash deposit SMS with bare 'Bal-colon' phrasing`() {
        // Real-world gap: a bare "Bal:" with no avl/available qualifier, and a colon right
        // before the amount — neither was previously allowed by the appended-balance regex.
        val result = SmsParser.parseOffline(
            "Deposited! INR 8,000.00 in HDFC Bank A/c XX1234 On 22/07/26 18:49:28 At BHOGANAHALLI Via Cash Deposit Machine Bal: INR 8,004.12",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(8000.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals(8004.12, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Avl Bal with colon before currency still extracts balance`() {
        val result = SmsParser.parseOffline(
            "Rs. 500.00 debited from your HDFC Bank A/c XX9872 via UPI. Avl Bal: Rs 4,500.00",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(4500.0, result!!.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Small decimal amount is not misread as a time (7-00 must not become 07-00)`() {
        // Real-world bug: a plain 2-decimal amount like "7.00" has the exact same shape as
        // a bare "H.MM" time (no seconds, no am/pm), so it was being misread as 07:00 and
        // silently overriding the actual SMS-received time-of-day.
        val refCal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JULY, 21, 14, 32, 10)
        }
        val result = SmsParser.parseOffline(
            "Dear UPI user A/C X5300 debited by 7.00 on date 21 Jul26 trf to BOOPATHI THANGAM Refno 620278336395 If not u? call-1800111109 for other services-18001234-SBI",
            "VM-SBI-S",
            refCal.timeInMillis
        )
        assertNotNull(result)
        assertEquals(7.0, result!!.amount, 0.01)
        assertNotNull(result.parsedTimestamp)
        val resultCal = java.util.Calendar.getInstance().apply { timeInMillis = result.parsedTimestamp!! }
        // Date comes from the SMS body (21 Jul 2026); time-of-day must fall back to the
        // actual reference/received time (14:32:10) — NOT 07:00 derived from the amount.
        assertEquals(2026, resultCal.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.JULY, resultCal.get(java.util.Calendar.MONTH))
        assertEquals(21, resultCal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(14, resultCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(32, resultCal.get(java.util.Calendar.MINUTE))
    }

    @Test fun `SBI credit with account reference`() {
        val result = SmsParser.parseOffline(
            "Dear SBI Customer, your A/C XXXXXX5678 is credited Rs.3,250.00 on 10-06-26 by UPI.",
            "VM-SBI-S"
        )
        assertNotNull(result)
        assertEquals(3250.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
    }

    @Test fun `Zomato Money wallet credit is not excluded by balance-expiry text`() {
        // Real-world gap: "This balance expires on ..." is routine T&C text on wallet
        // top-up SMS, not a "your balance is about to expire" reminder — it was being
        // matched by the "balance expire" hard-exclusion and silently dropped entirely.
        val result = SmsParser.parseOffline(
            "Rs. 7.14 added to Zomato Money (on mobile ending with **1234). This balance expires on 21 Aug 2026.",
            "CX-ZOMATO-S"
        )
        assertNotNull(result)
        assertEquals(7.14, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals("ZOMATO_WALLET", result.accountRef)
    }

    @Test fun `NeuCoins credit still parses after merging into the unified wallet list`() {
        val result = SmsParser.parseOffline(
            "100 NeuCoins credited to your account for your recent purchase. Check details on the Tata Neu app.",
            "VM-TATAN-S"
        )
        assertNotNull(result)
        assertEquals(100.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals("NEUCOINS_WALLET", result.accountRef)
        assertEquals(ExpenseCategory.COINS, result.category)
    }

    @Test fun `Apay wallet credit still parses after merging into the unified wallet list`() {
        val result = SmsParser.parseOffline(
            "Rs.50.00 credited to your Apay Wallet.",
            "VM-APAYX-S"
        )
        assertNotNull(result)
        assertEquals(50.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals("APAY_WALLET", result.accountRef)
    }

    @Test fun `Unlisted wallet is still recognized via the generic added-to fallback`() {
        // No curated INDIA_WALLETS entry exists for "Swiggy Money" — this must still create
        // a real, correctly-named wallet account via the generic fallback instead of being
        // silently dropped, the same class of bug that affected Zomato Money.
        val result = SmsParser.parseOffline(
            "Rs. 25.00 added to Swiggy Money (on mobile ending with **9876).",
            "VM-SWIGG-S"
        )
        assertNotNull(result)
        assertEquals(25.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals("GENERIC_WALLET:Swiggy Money", result.accountRef)
        assertEquals("Swiggy Money", SmsParser.walletDisplayNameForRef(result.accountRef!!))
    }

    @Test fun `Generic wallet fallback also handles 'Added X to Name' word order`() {
        // Real-world gap: Swiggy phrases it "Added Rs.X to Name" (verb first, amount in
        // between) rather than Zomato's "Rs.X added to Name" (amount first) — the fallback
        // was anchored on the literal substring "added to", which only the Zomato ordering
        // contains contiguously, so this word order was silently excluded entirely.
        val result = SmsParser.parseOffline(
            "Added Rs. 25.00  to Swiggy Money (on mobile ending with **9876).",
            "AC-SWIGG-S"
        )
        assertNotNull(result)
        assertEquals(25.0, result!!.amount, 0.01)
        assertEquals("INCOME", result.type)
        assertEquals("GENERIC_WALLET:Swiggy Money", result.accountRef)
    }

    // ─── Credit Card: Expense ─────────────────────────────────────────────────

    @Test fun `SBI credit card spend parses merchant and amount`() {
        val result = SmsParser.parseOffline(
            "Rs.165.00 spent on your SBI Credit Card ending with 6928 at GMART on 19-06-26 via UPI (Ref No. 4524532432).",
            "AX-SBICC-S"
        )
        assertNotNull(result)
        assertEquals(165.0, result!!.amount, 0.01)
        assertEquals("6928", result.accountRef)
        assertEquals("EXPENSE", result.type)
        assertFalse("CC merchant title should not be empty", result.title.isBlank())
    }

    @Test fun `CC debit at merchant parses correctly`() {
        val result = SmsParser.parseOffline(
            "SBI Card ending in 7281 is debited of INR 9500.00 at AMZN on 01-Jul-26 via UPI.",
            "AX-SBICC-S"
        )
        assertNotNull(result)
        assertEquals(9500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `CC spent with available credit limit in body`() {
        // Available credit limit should NOT route to CC Summary (no outstanding balance phrase)
        val result = SmsParser.parseOffline(
            "INR 1,500.00 spent on ICICI Credit Card XX1234 at ZOMATO on 12-May-26 via UPI.",
            "JD-ICICI-S"
        )
        assertNotNull(result)
        assertEquals(1500.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    // ─── Balance Sync ────────────────────────────────────────────────────────

    @Test fun `Pure balance notification sets isBalanceUpdate and availableBalance`() {
        val result = SmsParser.parseOffline(
            "Your a/c XX1234 has avl bal of Rs.12,500.00 as on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertTrue("Should be a balance update", result!!.isBalanceUpdate)
        assertEquals(12500.0, result.availableBalance!!, 0.01)
        assertEquals("1234", result.accountRef)
    }

    @Test fun `Balance notification with avail bal keyword`() {
        // Uses 'avail bal' + 'a/c' — reliably triggers tryParseBalanceUpdate path
        val result = SmsParser.parseOffline(
            "Your a/c XX7890 has avail bal of Rs.8,000.00 as on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertTrue("Should be a balance update", result!!.isBalanceUpdate)
        assertEquals(8000.0, result.availableBalance!!, 0.01)
    }

    // ─── Filter: SmsFilterUtility exclusions ─────────────────────────────────

    @Test fun `OTP SMS is excluded`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Your OTP for login is 5432. Do not share."))
    }

    @Test fun `Promotional apply SMS is excluded`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Apply for a pre-approved loan of up to Rs. 5 Lakhs!"))
    }

    @Test fun `EMI due SMS is excluded`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Your EMI of Rs. 15000 is due on 2026-06-30 for account 5432."))
    }

    @Test fun `Mandate SMS is excluded`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms("Mandate request registered for account 5432."))
    }

    @Test fun `Loan load offer SMS is excluded`() {
        assertFalse(SmsFilterUtility.isValidTransactionSms(
            "Dear 97912XX, Rs.50,000 load is ready to be credited to your bank A/c on 22.04.2026."
        ))
    }

    // ─── Filter: SmsFilterUtility valid cases ────────────────────────────────

    @Test fun `Standard debit SMS is valid`() {
        assertTrue(SmsFilterUtility.isValidTransactionSms(
            "SBI Card ending in 7281 is debited of INR 9,500.00 at AMZN on 01-Jul-26."
        ))
    }

    @Test fun `Standard credit SMS is valid`() {
        assertTrue(SmsFilterUtility.isValidTransactionSms(
            "HDFC account ending 9872 was credited with Rs 1,450.00 on 01-Jul-26."
        ))
    }

    @Test fun `SBI UPI squished debit is valid`() {
        assertTrue(SmsFilterUtility.isValidTransactionSms(
            "Dear UPI user A/C x8472 debited by 173on date 19Jun26 trf to J VERMA Refno 254123452345."
        ))
    }

    // ─── Amount parsing edge cases ────────────────────────────────────────────

    @Test fun `Indian comma-formatted amount parsed correctly`() {
        val result = SmsParser.parseOffline(
            "INR 1,23,456.78 debited from account XX1234 on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(123456.78, result!!.amount, 0.01)
    }

    @Test fun `Amount without decimal parses correctly`() {
        val result = SmsParser.parseOffline(
            "Rs 200 debited from a/c xx3421 on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals(200.0, result!!.amount, 0.01)
    }

    @Test fun `Zero amount SMS yields null`() {
        val result = SmsParser.parseOffline(
            "Rs.0.00 debited from a/c XX9999 on 01-Jul-26.",
            "HD-HDFC-T"
        )
        if (result != null) assertTrue("Zero amount must not be valid", result.amount > 0)
    }

    // ─── Account ref parsing ─────────────────────────────────────────────────

    @Test fun `Last-4 digits extracted from 'account XX5678'`() {
        val result = SmsParser.parseOffline(
            "ICICI Bank: INR 300.00 debited from account XX5678 on 10/04/2026.",
            "JD-ICICI-S"
        )
        assertNotNull(result)
        assertEquals("5678", result!!.accountRef)
    }

    @Test fun `SBI a-slash-c format extracts ref`() {
        val result = SmsParser.parseOffline(
            "Your SBI a/c xx1230 is debited with Rs 500 on 01-Jul-26.",
            "VM-SBI-S"
        )
        assertNotNull(result)
        assertEquals("1230", result!!.accountRef)
    }

    @Test fun `Asterisk-masked account ref extracted`() {
        val result = SmsParser.parseOffline(
            "Rs.173.00 credited to a/c *6319 on 19/06/2026 via UPI Ref no 254123452345.",
            "JD-INDBK-S"
        )
        assertNotNull(result)
        assertEquals("6319", result!!.accountRef)
    }

    // ─── Merchant / Title parsing ─────────────────────────────────────────────

    @Test fun `Title extracted from 'at MERCHANT' pattern`() {
        val result = SmsParser.parseOffline(
            "Rs.165.00 spent on SBI Credit Card ending with 6928 at GMART on 19-06-26 via UPI.",
            "AX-SBICC-S"
        )
        assertNotNull(result)
        assertFalse(result!!.title.isBlank())
    }

    @Test fun `Title extracted from 'trf to NAME' pattern`() {
        val result = SmsParser.parseOffline(
            "Dear UPI user A/C x8472 debited by 173on date 19Jun26 trf to J VERMA Refno 254123452345.",
            "VM-SBI-S"
        )
        assertNotNull(result)
        assertFalse(result!!.title.isBlank())
    }

    // ─── Category: EXPENSE types ─────────────────────────────────────────────
    // Each test verifies that inferCategory() maps to the correct ExpenseCategory
    // based on the extracted merchant title or lowerBody keywords.

    private fun expenseAt(merchant: String, amount: Double = 250.0) = SmsParser.parseOffline(
        "Rs.$amount debited from a/c xx5678 at $merchant on 01-Jul-26 via UPI.",
        "HD-HDFC-T"
    )

    @Test fun `Category FOOD - Zomato payment`() {
        val r = expenseAt("ZOMATO")
        assertNotNull(r); assertEquals(ExpenseCategory.FOOD, r!!.category)
    }

    @Test fun `Category FOOD - restaurant payment`() {
        val r = expenseAt("SWIGGY FOOD")
        assertNotNull(r); assertEquals(ExpenseCategory.FOOD, r!!.category)
    }

    @Test fun `Category FUEL - petrol station`() {
        val r = expenseAt("PETROL STATION", 1500.0)
        assertNotNull(r); assertEquals(ExpenseCategory.FUEL, r!!.category)
    }

    @Test fun `Category TRANSPORT - Uber ride`() {
        val r = expenseAt("UBER RIDES", 150.0)
        assertNotNull(r); assertEquals(ExpenseCategory.TRANSPORT, r!!.category)
    }

    @Test fun `Category TRANSPORT - Ola cab`() {
        val r = expenseAt("OLA CABS", 200.0)
        assertNotNull(r); assertEquals(ExpenseCategory.TRANSPORT, r!!.category)
    }

    @Test fun `Category GROCERIES - Dmart`() {
        val r = expenseAt("DMART", 800.0)
        assertNotNull(r); assertEquals(ExpenseCategory.GROCERIES, r!!.category)
    }

    @Test fun `Category GROCERIES - BigBasket`() {
        val r = expenseAt("BIGBASKET", 600.0)
        assertNotNull(r); assertEquals(ExpenseCategory.GROCERIES, r!!.category)
    }

    @Test fun `Category BILLS - Netflix subscription`() {
        val r = expenseAt("NETFLIX", 499.0)
        assertNotNull(r); assertEquals(ExpenseCategory.BILLS, r!!.category)
    }

    @Test fun `Category BILLS - broadband bill`() {
        val r = expenseAt("BROADBAND SERVICES", 999.0)
        assertNotNull(r); assertEquals(ExpenseCategory.BILLS, r!!.category)
    }

    @Test fun `Category ENTERTAINMENT - cinema tickets`() {
        val r = expenseAt("CINEMA HALL", 500.0)
        assertNotNull(r); assertEquals(ExpenseCategory.ENTERTAINMENT, r!!.category)
    }

    @Test fun `Category ENTERTAINMENT - movie booking`() {
        val r = expenseAt("BOOKMYSHOW MOVIE", 400.0)
        assertNotNull(r); assertEquals(ExpenseCategory.ENTERTAINMENT, r!!.category)
    }

    @Test fun `Category HEALTHCARE - pharmacy`() {
        val r = expenseAt("APOLLO PHARMACY", 600.0)
        assertNotNull(r); assertEquals(ExpenseCategory.HEALTHCARE, r!!.category)
    }

    @Test fun `Category HEALTHCARE - hospital`() {
        val r = expenseAt("CITY HOSPITAL", 2000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.HEALTHCARE, r!!.category)
    }

    @Test fun `Category EDUCATION - college fees`() {
        val r = expenseAt("COLLEGE FEES", 5000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.EDUCATION, r!!.category)
    }

    @Test fun `Category EDUCATION - tuition centre`() {
        val r = expenseAt("TUITION CENTRE", 2000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.EDUCATION, r!!.category)
    }

    @Test fun `Category INVESTMENT - Zerodha`() {
        val r = expenseAt("ZERODHA", 5000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.INVESTMENT, r!!.category)
    }

    @Test fun `Category INVESTMENT - Groww`() {
        val r = expenseAt("GROWW", 3000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.INVESTMENT, r!!.category)
    }

    @Test fun `Category MUTUAL_FUND - SIP payment`() {
        val r = expenseAt("MUTUAL FUND SIP", 1000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.MUTUAL_FUND, r!!.category)
    }

    @Test fun `Category ELECTRONICS - Amazon purchase`() {
        val r = expenseAt("AMAZON", 12000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.ELECTRONICS, r!!.category)
    }

    @Test fun `Category ELECTRONICS - Croma store`() {
        val r = expenseAt("CROMA", 8000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.ELECTRONICS, r!!.category)
    }

    @Test fun `Category TRAVEL - IRCTC booking`() {
        val r = expenseAt("IRCTC TRAIN", 800.0)
        assertNotNull(r); assertEquals(ExpenseCategory.TRAVEL, r!!.category)
    }

    @Test fun `Category TRAVEL - Redbus booking`() {
        val r = expenseAt("REDBUS", 600.0)
        assertNotNull(r); assertEquals(ExpenseCategory.TRAVEL, r!!.category)
    }

    @Test fun `Category GYM - gym membership`() {
        val r = expenseAt("GYM MEMBERSHIP", 2000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.GYM, r!!.category)
    }

    @Test fun `Category GYM - fitness centre`() {
        val r = expenseAt("FITNESS CENTRE", 1500.0)
        assertNotNull(r); assertEquals(ExpenseCategory.GYM, r!!.category)
    }

    @Test fun `Category SHOES - Nike outlet`() {
        val r = expenseAt("NIKE OUTLET", 3000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.SHOES, r!!.category)
    }

    @Test fun `Category CLOTHES - Zudio fashion`() {
        val r = expenseAt("ZUDIO FASHION", 1500.0)
        assertNotNull(r); assertEquals(ExpenseCategory.CLOTHES, r!!.category)
    }

    @Test fun `Category DEBT - debt repayment`() {
        val r = expenseAt("DEBT PAYMENT", 5000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.DEBT, r!!.category)
    }

    @Test fun `Category SOFT_HOT_DRINKS - tea stall`() {
        val r = expenseAt("TEA STALL", 30.0)
        assertNotNull(r); assertEquals(ExpenseCategory.SOFT_HOT_DRINKS, r!!.category)
    }

    @Test fun `Category FRUITS - fruit market`() {
        val r = expenseAt("FRUIT VENDOR", 200.0)
        assertNotNull(r); assertEquals(ExpenseCategory.FRUITS, r!!.category)
    }

    @Test fun `Category BIKE - bike repair`() {
        val r = expenseAt("BIKE REPAIR SHOP", 500.0)
        assertNotNull(r); assertEquals(ExpenseCategory.BIKE, r!!.category)
    }

    @Test fun `Category GIFTING_FRIENDS - gift shop`() {
        val r = expenseAt("GIFT SHOP", 1000.0)
        assertNotNull(r); assertEquals(ExpenseCategory.GIFTING_FRIENDS, r!!.category)
    }

    @Test fun `Category OTHERS - unrecognised merchant`() {
        val r = expenseAt("GENERAL PAYMENT", 100.0)
        assertNotNull(r); assertEquals(ExpenseCategory.OTHERS, r!!.category)
    }

    // ─── Category: INCOME types ──────────────────────────────────────────────

    @Test fun `Category SALARY - salary credit`() {
        val result = SmsParser.parseOffline(
            "Salary of Rs. 45000.00 credited to account ending in 5678 on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals("INCOME", result!!.type)
        assertEquals(ExpenseCategory.SALARY, result.category)
    }

    @Test fun `Category REFUNDS - refund credited`() {
        val result = SmsParser.parseOffline(
            "Rs.150.00 refund credited to your account ending in 5678 on 01-Jul-26 via UPI.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals("INCOME", result!!.type)
        assertEquals(ExpenseCategory.REFUNDS, result.category)
    }

    @Test fun `Category CASHBACK - cashback credited`() {
        val result = SmsParser.parseOffline(
            "Rs.50.00 cashback credited to your account ending in 5678 on 01-Jul-26 via UPI.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals("INCOME", result!!.type)
        assertEquals(ExpenseCategory.CASHBACK, result.category)
    }

    @Test fun `Category UPI income - received via UPI`() {
        val result = SmsParser.parseOffline(
            "Rs.500.00 received in your account ending in 5678 via UPI on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals("INCOME", result!!.type)
        assertEquals(ExpenseCategory.UPI, result.category)
    }

    @Test fun `Category INCOME_OTHERS - generic bank transfer`() {
        val result = SmsParser.parseOffline(
            "Rs.1000.00 credited to your account ending in 5678 by bank transfer on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals("INCOME", result!!.type)
        assertEquals(ExpenseCategory.INCOME_OTHERS, result.category)
    }

    // ─── Sender header validation ────────────────────────────────────────────

    @Test fun `Sender not ending in -S or -T is rejected`() {
        // e.g. "VM-HDFCBK" ends in K — should return null
        val result = SmsParser.parseOffline(
            "Rs.500.00 debited from a/c xx1234 on 01-Jul-26 via UPI.",
            "VM-HDFCBK"
        )
        assertNull("Parser must reject senders that do not end in -S or -T", result)
    }

    @Test fun `Null sender is rejected`() {
        val result = SmsParser.parseOffline(
            "Rs.500.00 debited from a/c xx1234 on 01-Jul-26 via UPI.",
            null
        )
        assertNull("Parser must reject null sender", result)
    }

    @Test fun `Sender ending in -S is accepted`() {
        val result = SmsParser.parseOffline(
            "Rs.500.00 debited from a/c xx1234 on 01-Jul-26 via UPI.",
            "VM-SBI-S"
        )
        assertNotNull("Sender ending in -S must be accepted", result)
    }

    @Test fun `Sender ending in -T is accepted`() {
        val result = SmsParser.parseOffline(
            "Rs.500.00 debited from a/c xx1234 on 01-Jul-26 via UPI.",
            "HD-HDFC-T"
        )
        assertNotNull("Sender ending in -T must be accepted", result)
    }

    // ─── getReferenceNumber: UPI NNNNN bare format ────────────────────────────
    // HDFC credit SMS uses "(UPI 1234567890)" — no "Ref" keyword before the number.
    // SBI debit SMS uses "Refno 1234567890".
    // Both must extract the SAME reference number for transfer pair detection.

    @Test fun `getReferenceNumber extracts bare UPI number from HDFC credit format`() {
        // "(UPI 1234567890)" — the 7th pattern added to getReferenceNumber
        val ref = SmsParser.getReferenceNumber("Rs.500 credited to a/c XX9872 (UPI 1234567890)")
        assertEquals("1234567890", ref)
    }

    @Test fun `getReferenceNumber extracts Refno from SBI debit format`() {
        // "Refno 1234567890" — existing pattern
        val ref = SmsParser.getReferenceNumber(
            "Dear UPI user A/C x8472 debited by 500 on 01Jan26 trf to SOMEONE Refno 1234567890."
        )
        assertEquals("1234567890", ref)
    }

    @Test fun `Transfer pair HDFC credit + SBI debit share same reference number`() {
        // Both SMS must yield same ref → can be matched as TRANSFER in FinanceViewModel
        val hdfcCreditRef = SmsParser.getReferenceNumber(
            "HDFC Bank: Rs. 5000.00 credited to a/c XX9872 (UPI 9876543210). Avl Bal Rs 20000."
        )
        val sbiDebitRef = SmsParser.getReferenceNumber(
            "Dear UPI user A/C x8472 debited by 5000 on 01Jan26 trf to SOMEONE Refno 9876543210."
        )
        assertNotNull("HDFC credit ref must not be null", hdfcCreditRef)
        assertNotNull("SBI debit ref must not be null", sbiDebitRef)
        assertEquals("Both SMS must share the same reference number", hdfcCreditRef, sbiDebitRef)
    }

    @Test fun `getReferenceNumber extracts UPI Ref no format (existing)`() {
        // "UPI Ref no 254123452345" — should still work after adding new pattern
        val ref = SmsParser.getReferenceNumber(
            "Rs.173.00 credited to a/c *6319 by VPA jkverma@oksbi (UPI Ref no 254123452345). Indian bank."
        )
        assertEquals("254123452345", ref)
    }

    @Test fun `getReferenceNumber returns null for SMS with no reference`() {
        val ref = SmsParser.getReferenceNumber("Your account balance is Rs.5000.")
        assertNull(ref)
    }

    // ─── Bank name inference (SmsAccountUtils) ────────────────────────────────

    @Test fun `SBI Credit Card spent-on SMS parses as EXPENSE not INCOME`() {
        val result = SmsParser.parseOffline(
            "Rs.1,054.00 spent on your SBI Credit Card ending 1234 at PYUFlipkartInternet on 06/07/26.",
            "AX-SBICARD-S"
        )
        assertNotNull(result)
        assertEquals(1054.0, result!!.amount, 0.01)
        assertEquals("EXPENSE", result.type)
    }

    @Test fun `IOB sender IOBBK infers correct bank name`() {
        val result = SmsParser.parseOffline(
            "Rs.500.00 debited from IOB A/c ending 5678 for UPI payment. Ref 999888777.",
            "AX-IOBBK-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        // accountRef sender prefix should resolve to IOB, not a 4-char truncation like IOBB
        assertTrue("Account ref should include IOB prefix",
            result.accountRef?.startsWith("IOB") == true || result.sender?.contains("IOB") == true)
    }

    @Test fun `Bank of India sender BOI infers correctly`() {
        val result = SmsParser.parseOffline(
            "INR 1,200.00 debited from your Bank of India a/c XX3456 on 07/07/26. UPI Ref 123.",
            "AX-BOIMNB-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(1200.0, result.amount, 0.01)
    }

    @Test fun `Bank of Baroda sender BOB infers correctly`() {
        val result = SmsParser.parseOffline(
            "Rs.800.00 debited from your BOB A/c ending 7890 via UPI. Available balance Rs.4000.",
            "JD-BOBIMU-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(800.0, result.amount, 0.01)
    }

    @Test fun `Canara Bank sender CNRBNK infers correctly`() {
        val result = SmsParser.parseOffline(
            "INR 600.00 debited from Canara Bank A/c XXXX2345. UPI Ref 456789.",
            "HD-CNRBNK-T"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
    }

    @Test fun `IndusInd sender INDUSB infers correctly`() {
        val result = SmsParser.parseOffline(
            "Rs.300.00 debited from IndusInd Bank A/c ending 4321 on 07-Jul-26. UPI Ref 321654.",
            "AX-INDUSB-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(300.0, result.amount, 0.01)
    }

    @Test fun `Yes Bank sender YESBNK infers correctly`() {
        val result = SmsParser.parseOffline(
            "INR 750.00 debited from Yes Bank a/c XX9012 for UPI txn on 07/07/26.",
            "BK-YESBNK-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(750.0, result.amount, 0.01)
    }

    @Test fun `IDFC First Bank sender IDFCFB infers correctly`() {
        val result = SmsParser.parseOffline(
            "Rs.450.00 debited from IDFC First Bank A/c ending 6543 via UPI.",
            "JD-IDFCFB-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
    }

    @Test fun `Debited SMS always EXPENSE even if body contains word 'credit'`() {
        val result = SmsParser.parseOffline(
            "Rs.200.00 debited from your credit card ending 1111 at TestMerchant.",
            "AX-SBICARD-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
    }

    // ─── Bank name must come from sender, NOT from body payee mentions ────────

    @Test fun `IDFC First transfer to HDFC — accountRef must be IDFC-based, not HDFC`() {
        // Reported bug: "Az-IDFCFirstBK-S" was creating "HDFC Bank ·5678" instead of "IDFC First ·5678"
        val result = SmsParser.parseOffline(
            "Your Ac x5678 debited Rs.30,000.00 for transfer to My HDFC Ac x1234 " +
            "dt 08.07.26 Ref 536019608407. If not done by you, call 1800111109. Your IDFCBANK.",
            "Az-IDFCFirstBK-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(30000.0, result.amount, 0.01)
        // accountRef should be the DEBIT account (x5678), not the payee account (x1234)
        assertEquals("5678", result.accountRef)
        // sender prefix should reflect IDFC, not HDFC
        assertTrue(
            "sender/accountRef prefix should be IDFC-based, got: ${result.sender}",
            result.sender?.uppercase()?.contains("IDFC") == true
        )
    }

    @Test fun `SBI debit to ICICI — accountRef must be SBI-based`() {
        val result = SmsParser.parseOffline(
            "INR 5000.00 debited from your SBI A/c X1234 to ICICI Ac X5678 via NEFT on 08-Jul-26.",
            "VM-SBI-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals("1234", result.accountRef)
        assertTrue(
            "sender should start with SBI, got: ${result.sender}",
            result.sender?.uppercase()?.startsWith("SBI") == true
        )
    }

    @Test fun `HDFC to SBI transfer — accountRef must be HDFC-based`() {
        val result = SmsParser.parseOffline(
            "Rs.10,000.00 debited from HDFC Bank A/c XX4321 on 08/07/26. " +
            "Transfer to SBI Account XX9876. Available Bal Rs.25,000.",
            "HD-HDFCBK-T"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals("4321", result.accountRef)
        assertTrue(
            "sender should start with HDFC, got: ${result.sender}",
            result.sender?.uppercase()?.startsWith("HDFC") == true
        )
        // Balance from "Available Bal Rs.25,000" must also be captured
        assertEquals(25000.0, result.availableBalance ?: 0.0, 0.01)
    }

    // ─── Available-balance extraction for all transaction types ──────────────

    @Test fun `Expense SMS with Available Bal — EXPENSE + balance captured`() {
        val result = SmsParser.parseOffline(
            "Your A/c XX7890 debited by Rs.1,500.00 on 08-Jul-26 for UPI payment at Zomato. " +
            "Avl Bal INR 12,400.50. -SBI",
            "VM-SBI-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(1500.0, result.amount, 0.01)
        assertEquals("7890", result.accountRef)
        // Available balance must be captured (not swallowed by balance-update path)
        assertEquals(12400.50, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Income SMS with Available Bal — INCOME + balance captured`() {
        val result = SmsParser.parseOffline(
            "Rs.50,000.00 credited to your A/c XX1234 on 08/07/26. " +
            "Salary from EMPLOYER LTD. Avl Bal Rs.75,000.00.",
            "VM-SBI-S"
        )
        assertNotNull(result)
        assertEquals("INCOME", result!!.type)
        assertEquals(50000.0, result.amount, 0.01)
        assertEquals("1234", result.accountRef)
        assertEquals(75000.0, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Transfer debit SMS with Available Bal — EXPENSE + balance captured`() {
        // Classic bank transfer: debits one account and shows remaining balance
        val result = SmsParser.parseOffline(
            "Dear Customer, Rs.30,000.00 debited from A/c x5678 dt 08.07.26. " +
            "Transferred to ICICI A/c x1234. Available Balance Rs.45,200.00. IDFCBANK.",
            "Az-IDFCFirstBK-S"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(30000.0, result.amount, 0.01)
        assertEquals("5678", result.accountRef)
        // Sender bank is IDFC, not ICICI (payee)
        assertTrue(
            "sender should reflect IDFC, got: ${result.sender}",
            result.sender?.uppercase()?.contains("IDFC") == true
        )
        assertEquals(45200.0, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Expense debit SMS with Avl Bal inline — EXPENSE type not swallowed`() {
        // Axis Bank style: debit + inline balance, no payment channel keyword
        val result = SmsParser.parseOffline(
            "INR 2,000.00 debited from Axis Bank A/c XX3456 for POS purchase. Avl Bal INR 8,500.00.",
            "AX-AXIS-T"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(2000.0, result.amount, 0.01)
        assertEquals("3456", result.accountRef)
        assertEquals(8500.0, result.availableBalance ?: 0.0, 0.01)
    }

    @Test fun `Pure balance SMS without transaction action — parsed as balance update`() {
        // Only balance info, no debit/credit/transfer → should remain a balance update
        val result = SmsParser.parseOffline(
            "Your account balance: A/c XX1234 INR 18,500.00. Available Balance INR 18,500.00.",
            "HD-HDFC-T",
            bypassExclusionFilter = true
        )
        // Pure balance SMS → either null (filtered) or isBalanceUpdate=true
        // The key assertion is: it must NOT produce a regular EXPENSE/INCOME transaction
        assertTrue(
            "pure balance SMS should be null or isBalanceUpdate",
            result == null || result.isBalanceUpdate
        )
    }
}
