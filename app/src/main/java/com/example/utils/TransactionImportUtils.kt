package com.example.utils

import com.example.data.TransactionEntry
import kotlin.math.abs

private const val DUPLICATE_MATCH_WINDOW_MS = 60_000L
// Cross-bank transfers (NEFT/RTGS) can settle minutes to hours apart; use a wide window
// only when checking whether an INCOME is the receiver leg of an existing TRANSFER.
private const val CROSS_BANK_TRANSFER_WINDOW_MS = 4 * 60 * 60 * 1000L  // 4 hours

fun isDuplicateImportedTransaction(
    existing: TransactionEntry,
    incomingSmsBody: String,
    incomingTitle: String,
    incomingAmount: Double,
    incomingType: String,
    incomingTimestamp: Long,
    incomingReference: String?,
    incomingAccountName: String
): Boolean {
    // 1. Exact SMS body match — same physical SMS re-scanned.
    //    A BALANCE_UPDATE entry stored from the same SMS (e.g. "Balance Sync" created from an
    //    HDFC "Received!" notification that also carries "Avl bal") must NOT block the same SMS
    //    from being later imported as a real INCOME/EXPENSE transaction.
    if (existing.smsBody == incomingSmsBody && existing.type != "BALANCE_UPDATE") return true

    // 2. Reference-number match.
    val existingReference = SmsParser.getReferenceNumber(existing.smsBody)
    if (incomingReference != null && existingReference != null && incomingReference == existingReference) {
        // Same ref no and same type → direct duplicate.
        // Same ref no and existing is TRANSFER → both legs were already consumed; this
        // incoming SMS is the receiver/sender leg that was merged into the TRANSFER.
        return existing.type == incomingType || existing.type == "TRANSFER"
    }

    // 3. Cross-bank NEFT/RTGS: the INCOME leg (bank deposit) arrives minutes or hours after
    //    the EXPENSE leg (bank debit). They carry different reference numbers, so check 2
    //    can't catch them. If an existing TRANSFER has [To: <account>] matching the incoming
    //    INCOME's account, with the same amount and within a 4-hour window, it's the receiver
    //    leg of that transfer — skip re-insertion.
    if (incomingType == "INCOME" && existing.type == "TRANSFER") {
        if (existing.amount == incomingAmount &&
            existing.note?.contains("[To: $incomingAccountName]") == true &&
            abs(existing.timestamp - incomingTimestamp) < CROSS_BANK_TRANSFER_WINDOW_MS) {
            return true
        }
    }

    // 3b. EXPENSE re-scan when a TRANSFER already covers the source account.
    //     When backup is restored the TRANSFER entry has smsBody=null (not in CSV), so checks
    //     1 and 2 cannot fire. The [Acc: ...] tag in the note IS preserved, so we use it.
    if (incomingType == "EXPENSE" && existing.type == "TRANSFER") {
        if (existing.amount == incomingAmount &&
            existing.note?.contains("[Acc: $incomingAccountName]") == true &&
            abs(existing.timestamp - incomingTimestamp) < DUPLICATE_MATCH_WINDOW_MS) {
            return true
        }
    }

    // 4. Core-fields match within 60-second window (same amount + type + title + account).
    val sameCoreFields = existing.amount == incomingAmount &&
        existing.type == incomingType &&
        existing.title.equals(incomingTitle, ignoreCase = true) &&
        existing.getAccountName().equals(incomingAccountName, ignoreCase = true)

    return sameCoreFields && abs(existing.timestamp - incomingTimestamp) < DUPLICATE_MATCH_WINDOW_MS
}