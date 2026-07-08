package com.example

import com.example.data.TransactionEntry
import com.example.utils.SmsFilterUtility
import com.example.utils.SmsParser
import com.example.utils.isDuplicateImportedTransaction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for isDuplicateImportedTransaction() in TransactionImportUtils and
 * related SmsParser / SmsFilterUtility scenarios involving balance updates,
 * same-timestamp SMS, and different-category dedup behaviour.
 *
 * Duplicate-check pipeline (in order):
 *   1. Exact smsBody match           (unless existing is BALANCE_UPDATE)
 *   2. Reference number match
 *   3. INCOME is receiver-leg of existing TRANSFER  (4-hour window)
 *   3b. EXPENSE re-scan of TRANSFER source account  (60-second window)
 *   4. Core-fields: amount+type+normalizedTitle+account  (60-second window)
 *   4b. smsBody=null (manual entry) — same core-fields  (24-hour window)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransactionDuplicateTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val T0 = 1_700_000_000_000L // fixed baseline timestamp

    private fun tx(
        title: String = "Test Merchant",
        amount: Double = 500.0,
        type: String = "EXPENSE",
        category: String = "OTHERS",
        timestamp: Long = T0,
        account: String = "HDFC 9553",
        smsBody: String? = "sms body",
        smsSender: String? = "HD-HDFC-T",
        note: String? = "[Acc: $account]"
    ) = TransactionEntry(
        title = title,
        amount = amount,
        category = category,
        type = type,
        timestamp = timestamp,
        smsSender = smsSender,
        smsBody = smsBody,
        note = note
    )

    private fun isDup(
        existing: TransactionEntry,
        body: String = "sms body",
        title: String = "Test Merchant",
        amount: Double = 500.0,
        type: String = "EXPENSE",
        timestamp: Long = T0,
        ref: String? = null,
        account: String = "HDFC 9553"
    ) = isDuplicateImportedTransaction(existing, body, title, amount, type, timestamp, ref, account)

    // ── Check 1: exact SMS body match ─────────────────────────────────────────

    @Test fun `check1 - identical smsBody is duplicate`() {
        val existing = tx(smsBody = "Rs.500 debited from a/c xx9553")
        assertTrue(isDup(existing, body = "Rs.500 debited from a/c xx9553"))
    }

    @Test fun `check1 - different smsBody is not duplicate by body alone`() {
        val existing = tx(smsBody = "Rs.500 debited from a/c xx9553")
        // Amount/type/title also differ so check4 won't catch it either
        assertFalse(isDup(existing, body = "Rs.200 debited from a/c xx9553", amount = 200.0))
    }

    @Test fun `check1 - same body but existing is BALANCE_UPDATE does not block`() {
        // A BALANCE_UPDATE created from the same SMS must not prevent a real EXPENSE
        // from being imported from that SMS later.
        val existing = tx(type = "BALANCE_UPDATE", smsBody = "Rs.500 debited from a/c xx9553")
        assertFalse(isDup(existing, body = "Rs.500 debited from a/c xx9553", type = "EXPENSE"))
    }

    // ── Check 1: same timestamp, same body, different sender ─────────────────

    @Test fun `check1 - same body different sender is still duplicate`() {
        // Duplicate detection is body-based, not sender-based.
        // If the same SMS body arrives from a different sender ID, it is the same SMS.
        val existing = tx(smsBody = "Rs.500 debited from a/c xx9553", smsSender = "HD-HDFC-T")
        assertTrue(isDup(existing, body = "Rs.500 debited from a/c xx9553"))
    }

    @Test fun `check1 - same timestamp different body different sender is not duplicate`() {
        // Two different SMS arriving at the same millisecond with different bodies —
        // NOT a duplicate unless core-fields match.
        val existing = tx(
            title = "Swiggy",
            amount = 300.0,
            smsBody = "Rs.300 debited to SWIGGY from a/c xx9553",
            smsSender = "HD-HDFC-T",
            timestamp = T0
        )
        assertFalse(isDup(
            existing,
            body = "Rs.500 debited from a/c xx9553 at AMAZON",
            title = "Amazon",
            amount = 500.0,
            timestamp = T0
        ))
    }

    // ── Check 2: reference number match ───────────────────────────────────────

    @Test fun `check2 - same ref number same type is duplicate`() {
        val body = "Rs.500 debited from a/c xx9553 UPI Ref 12345678"
        val existing = tx(smsBody = body, type = "EXPENSE")
        // Body differs slightly but ref matches
        assertTrue(isDup(
            existing,
            body = "Different body text",
            ref = "12345678",
            type = "EXPENSE"
        ))
    }

    @Test fun `check2 - same ref number existing is TRANSFER is duplicate`() {
        val body = "Rs.500 debited from a/c xx9553 UPI Ref 12345678"
        val existing = tx(smsBody = body, type = "TRANSFER")
        assertTrue(isDup(
            existing,
            body = "Rs.500 credited to a/c xx1234 UPI Ref 12345678",
            ref = "12345678",
            type = "INCOME"
        ))
    }

    @Test fun `check2 - same ref number different type not TRANSFER is not duplicate`() {
        // Existing is EXPENSE; incoming is INCOME with same ref — these are not the same record.
        val body = "Rs.500 debited from a/c xx9553 UPI Ref 12345678"
        val existing = tx(smsBody = body, type = "EXPENSE")
        assertFalse(isDup(
            existing,
            body = "Rs.500 credited to a/c xx1234 UPI Ref 12345678",
            ref = "12345678",
            type = "INCOME"
        ))
    }

    @Test fun `check2 - null ref does not cause false duplicate`() {
        // Bodies differ, refs both null — different titles ensure check 4 doesn't interfere,
        // so only check 2 is exercised: null+null must NOT produce a false duplicate.
        val existing = tx(title = "Merchant A", smsBody = "Rs.500 debited from a/c xx9553")
        assertFalse(isDup(existing, body = "Rs.500 debited from a/c xx9553 other text", ref = null, title = "Merchant B", amount = 500.0))
    }

    // ── Check 3: INCOME is receiver-leg of an existing TRANSFER ──────────────

    @Test fun `check3 - INCOME within 4h of matching TRANSFER is duplicate`() {
        val transferNote = "[Acc: HDFC 9553] [To: SBI 1234]"
        val existing = tx(type = "TRANSFER", amount = 5000.0, note = transferNote, smsBody = null)
        assertTrue(isDup(
            existing,
            body = "Rs.5000 credited to a/c xx1234",
            type = "INCOME",
            amount = 5000.0,
            account = "SBI 1234",
            timestamp = T0 + 2 * 60 * 60 * 1000L  // 2 hours later
        ))
    }

    @Test fun `check3 - INCOME beyond 4h of TRANSFER is not duplicate`() {
        val transferNote = "[Acc: HDFC 9553] [To: SBI 1234]"
        val existing = tx(type = "TRANSFER", amount = 5000.0, note = transferNote, smsBody = null)
        assertFalse(isDup(
            existing,
            body = "Rs.5000 credited to a/c xx1234",
            type = "INCOME",
            amount = 5000.0,
            account = "SBI 1234",
            timestamp = T0 + 5 * 60 * 60 * 1000L  // 5 hours later
        ))
    }

    // ── Check 3b: EXPENSE re-scan when TRANSFER already exists ───────────────

    @Test fun `check3b - EXPENSE within 60s of matching TRANSFER is duplicate`() {
        val transferNote = "[Acc: HDFC 9553] [To: SBI 1234]"
        val existing = tx(type = "TRANSFER", amount = 500.0, note = transferNote, smsBody = null)
        assertTrue(isDup(
            existing,
            body = "Rs.500 debited from HDFC a/c xx9553",
            type = "EXPENSE",
            amount = 500.0,
            account = "HDFC 9553",
            timestamp = T0 + 30_000L  // 30 seconds later
        ))
    }

    @Test fun `check3b - EXPENSE beyond 60s of TRANSFER is not duplicate`() {
        val transferNote = "[Acc: HDFC 9553] [To: SBI 1234]"
        val existing = tx(type = "TRANSFER", amount = 500.0, note = transferNote, smsBody = null)
        assertFalse(isDup(
            existing,
            body = "Rs.500 debited from HDFC a/c xx9553",
            type = "EXPENSE",
            amount = 500.0,
            account = "HDFC 9553",
            timestamp = T0 + 90_000L  // 90 seconds later
        ))
    }

    // ── Check 4: core-fields within 60-second window ──────────────────────────

    @Test fun `check4 - identical core-fields at same timestamp is duplicate`() {
        val existing = tx(smsBody = null)  // no body so check1 skipped
        assertTrue(isDup(existing, body = "different body", title = "Test Merchant", amount = 500.0))
    }

    @Test fun `check4 - identical core-fields 59 seconds apart is duplicate`() {
        val existing = tx(smsBody = null, timestamp = T0)
        assertTrue(isDup(existing, body = "new body", title = "Test Merchant", amount = 500.0, timestamp = T0 + 59_000L))
    }

    @Test fun `check4 - identical core-fields 61 seconds apart is NOT duplicate`() {
        // A non-null smsBody disables the 24h check-4b window so only the 60s check-4 applies.
        // 61 seconds > 60s window → NOT a duplicate.
        val existing = tx(smsBody = "stored sms body", timestamp = T0)
        assertFalse(isDup(existing, body = "new body", title = "Test Merchant", amount = 500.0, timestamp = T0 + 61_000L))
    }

    @Test fun `check4 - different amount is not duplicate`() {
        val existing = tx(smsBody = null, amount = 500.0)
        assertFalse(isDup(existing, body = "new body", amount = 501.0))
    }

    @Test fun `check4 - different type is not duplicate`() {
        val existing = tx(smsBody = null, type = "EXPENSE")
        assertFalse(isDup(existing, body = "new body", type = "INCOME"))
    }

    @Test fun `check4 - different account is not duplicate`() {
        val existing = tx(smsBody = null, account = "HDFC 9553")
        assertFalse(isDup(existing, body = "new body", account = "SBI 1234"))
    }

    @Test fun `check4 - different category same core-fields is still duplicate`() {
        // Category is NOT part of the duplicate check — two entries that differ only in
        // category are treated as the same financial event (prevents double-import).
        val existing = tx(smsBody = null, category = "FOOD")
        assertTrue(isDup(existing, body = "new body", title = "Test Merchant", amount = 500.0))
    }

    // ── Check 4: normalizeTitle strips legacy UPI-reference noise ─────────────

    @Test fun `check4 - 'Cred Club Upi dot' normalises to match 'Cred Club'`() {
        // Older parser versions appended ". Upi: ." to payee names; after normalisation
        // both should be considered the same title.
        val existing = tx(title = "Cred Club. Upi: .", smsBody = null)
        assertTrue(isDup(existing, body = "new body", title = "Cred Club"))
    }

    @Test fun `check4 - 'Gmart upi ref' normalises to match 'Gmart'`() {
        val existing = tx(title = "Gmart upi 4524532432", smsBody = null)
        assertTrue(isDup(existing, body = "new body", title = "Gmart"))
    }

    @Test fun `check4 - titles differing only in case are duplicate`() {
        val existing = tx(title = "DREAMPLUG", smsBody = null)
        assertTrue(isDup(existing, body = "new body", title = "Dreamplug"))
    }

    // ── Check 4b: manual entry (smsBody=null) — 24-hour window ───────────────

    @Test fun `check4b - manual entry same core-fields 8 hours apart is duplicate`() {
        // User manually entered the transaction at 08:00; SMS timestamp is 18/04/26 00:00 —
        // they are hours apart but are the same event.
        val existing = tx(smsBody = null, timestamp = T0)
        assertTrue(isDup(
            existing,
            body = "Rs.500 debited ending at 9553",
            title = "Test Merchant",
            amount = 500.0,
            timestamp = T0 + 8 * 60 * 60 * 1000L  // 8 hours later
        ))
    }

    @Test fun `check4b - manual entry same core-fields exactly 24h apart is duplicate`() {
        val existing = tx(smsBody = null, timestamp = T0)
        assertTrue(isDup(
            existing,
            body = "new body",
            title = "Test Merchant",
            amount = 500.0,
            timestamp = T0 + 24 * 60 * 60 * 1000L - 1  // just under 24h
        ))
    }

    @Test fun `check4b - manual entry same core-fields beyond 24h is NOT duplicate`() {
        val existing = tx(smsBody = null, timestamp = T0)
        assertFalse(isDup(
            existing,
            body = "new body",
            title = "Test Merchant",
            amount = 500.0,
            timestamp = T0 + 25 * 60 * 60 * 1000L  // 25 hours later
        ))
    }

    @Test fun `check4b - smsBody present entry uses 60s window not 24h`() {
        // Even if core-fields match, if the EXISTING record has an smsBody the 24-hour
        // check4b does not apply — only the 60-second check4 does.
        val existing = tx(smsBody = "stored sms body", timestamp = T0)
        assertFalse(isDup(
            existing,
            body = "different body",
            title = "Test Merchant",
            amount = 500.0,
            timestamp = T0 + 8 * 60 * 60 * 1000L  // 8 hours later — beyond 60s window
        ))
    }

    // ── SmsParser: balance update scenarios ───────────────────────────────────

    @Test fun `balance update SMS sets isBalanceUpdate true with correct balance`() {
        val result = SmsParser.parseOffline(
            "Your a/c XX5678 has avl bal of Rs.15,750.50 as on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertTrue("Pure balance SMS must set isBalanceUpdate", result!!.isBalanceUpdate)
        assertEquals(15750.50, result.availableBalance!!, 0.01)
        assertEquals("5678", result.accountRef)
    }

    @Test fun `transaction SMS with avl balance appended extracts both`() {
        // INCOME transaction that also carries an available-balance figure in the body.
        // The transaction should be INCOME, not BALANCE_UPDATE, and the balance should
        // be captured in availableBalance so the ViewModel can sync it.
        val result = SmsParser.parseOffline(
            "HDFC Bank: Rs.2000.00 credited to your account XX9872 via UPI. Avl Bal Rs.42,000.00",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertFalse("Should NOT be a balance-update record when a transaction action is present", result!!.isBalanceUpdate)
        assertEquals("INCOME", result.type)
        assertEquals(2000.0, result.amount, 0.01)
        assertEquals(42000.0, result.availableBalance!!, 0.01)
    }

    @Test fun `debit SMS with avl balance appended parses as EXPENSE`() {
        // Since v1.88 avlBalance is extracted for ALL transaction types (income + expense)
        // so the Balance Sync can be created from any debit+balance SMS.
        val result = SmsParser.parseOffline(
            "Rs.500.00 debited from HDFC Bank a/c XX9553 via UPI. Avl Bal Rs.12,000.00",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertEquals("EXPENSE", result!!.type)
        assertEquals(500.0, result.amount, 0.01)
        // availableBalance IS now extracted for EXPENSE — enables Balance Sync for debit+bal SMS
        assertEquals(12000.0, result.availableBalance ?: 0.0, 0.01)
    }

    // ── SmsParser: same timestamp, different senders ──────────────────────────

    @Test fun `two valid senders produce independent parse results`() {
        // Same SMS body, same timestamp concept — two different bank senders.
        // Each should parse successfully as an independent result.
        val body = "Rs.500.00 debited from a/c xx1234 on 01-Jul-26 via UPI."
        val r1 = SmsParser.parseOffline(body, "HD-HDFC-T")
        val r2 = SmsParser.parseOffline(body, "VM-SBI-S")
        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(r1!!.amount, r2!!.amount, 0.01)
    }

    @Test fun `valid -S sender and invalid sender on same body`() {
        val body = "Rs.500.00 debited from a/c xx1234 on 01-Jul-26 via UPI."
        assertNotNull(SmsParser.parseOffline(body, "JK-BANK-S"))  // valid
        assertNull(SmsParser.parseOffline(body, "JKBANK"))         // invalid — no -S/-T suffix
    }

    // ── SmsParser: different category / type edge cases ───────────────────────

    @Test fun `BALANCE_UPDATE type not returned as EXPENSE for pure balance SMS`() {
        val result = SmsParser.parseOffline(
            "Your a/c XX9553 has avl bal Rs.8,000.00 as on 01-Jul-26.",
            "HD-HDFC-T"
        )
        assertNotNull(result)
        assertNotEquals("EXPENSE", result!!.type.also {
            // type may be "BALANCE_UPDATE" or similar but must NOT be "EXPENSE"
        })
    }

    @Test fun `INCOME and EXPENSE with same amount at exact same timestamp are different events`() {
        // A debit and a credit of the same value at the same instant are NOT duplicates
        // because their types differ (check 4 requires type to match).
        val existingExpense = tx(type = "EXPENSE", amount = 1000.0, smsBody = null)
        assertFalse(isDup(existingExpense, body = "new body", type = "INCOME", amount = 1000.0))
    }

    @Test fun `same amount same type different title not duplicate via check4`() {
        val existing = tx(title = "Zomato", amount = 200.0, smsBody = null)
        assertFalse(isDup(existing, body = "new body", title = "Swiggy", amount = 200.0))
    }
}
