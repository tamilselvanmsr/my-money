package com.example.utils

import com.example.data.TransactionEntry
import kotlin.math.abs

private const val DUPLICATE_MATCH_WINDOW_MS = 60_000L
// Cross-bank transfers (NEFT/RTGS) can settle minutes to hours apart; use a wide window
// only when checking whether an INCOME is the receiver leg of an existing TRANSFER.
private const val CROSS_BANK_TRANSFER_WINDOW_MS = 4 * 60 * 60 * 1000L  // 4 hours

// Wider window for matching manual entries (no smsBody) against newly-parseable SMS patterns.
// A manual entry for April 18 and an SMS with timestamp extracted as "18/04/26 00:00" may
// be several hours apart — 24 h is the safe outer bound.
private const val MANUAL_ENTRY_DUPLICATE_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 hours

/**
 * Normalises a transaction title before comparison: strips trailing UPI reference noise
 * (e.g. "Cred Club. Upi: ." → "Cred Club") introduced by older parser versions.
 */
private fun normalizeTitle(t: String): String =
    t.split(Regex("\\bupi\\b", RegexOption.IGNORE_CASE))[0]
        .trimEnd('.', ',', ':', ';', ' ')
        .trim()

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
    //    IMPORTANT: only apply this when the TRANSFER was auto-created from SMS (smsBody != null).
    //    A manually-created TRANSFER to the same account must NOT block a legitimate external
    //    INCOME arriving to that account (e.g. salary/IMPS to an account that also received an
    //    internal transfer on the same day).
    if (incomingType == "INCOME" && existing.type == "TRANSFER" && existing.smsBody != null) {
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
    //    Title is normalised to strip legacy UPI-reference noise before comparing.
    val sameCoreFields = existing.amount == incomingAmount &&
        existing.type == incomingType &&
        normalizeTitle(existing.title).equals(normalizeTitle(incomingTitle), ignoreCase = true) &&
        existing.getAccountName().equals(incomingAccountName, ignoreCase = true)

    if (sameCoreFields && abs(existing.timestamp - incomingTimestamp) < DUPLICATE_MATCH_WINDOW_MS) return true

    // 4b. Wider 24-hour window for records with no stored SMS body (manually entered or old
    //     backup format). A newly-parseable SMS pattern (e.g. "ending at N") may produce a
    //     transaction matching a manual entry that was backed up — their timestamps differ by
    //     hours (user entry time vs SMS date), so the 60-second window above misses them.
    if (sameCoreFields && existing.smsBody == null &&
        abs(existing.timestamp - incomingTimestamp) < MANUAL_ENTRY_DUPLICATE_WINDOW_MS) return true

    return false
}