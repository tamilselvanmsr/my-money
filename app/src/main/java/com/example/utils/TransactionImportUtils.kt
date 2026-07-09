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

/** Extracts the 3-4 digit account/card suffix embedded in a [To: ...] or [Acc: ...] note tag. */
private fun lastFourFromNoteTag(note: String?, tag: String): String? {
    val regex = "\\[$tag: [^\\]]*(\\d{3,4})\\]".toRegex()
    return regex.find(note ?: "")?.groupValues?.getOrNull(1)
}

/** Returns the value stored inside [IncRef: XXX] in a TRANSFER note, or null. */
private fun incRefFromNote(note: String?): String? {
    return "\\[IncRef: ([^\\]]+)\\]".toRegex().find(note ?: "")?.groupValues?.getOrNull(1)
}

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
    //    can't catch them.
    //    Matching strategy (most to least robust):
    //      a) [IncRef: XXX] in note  — income SMS ref stored at transfer creation (survives all restores)
    //      b) last-4 digits in [To: ]  — immune to account rename after restore
    //      c) exact account name string  — legacy fallback
    //    Guard: only apply for SMS-auto-detected transfers ([T:A] in note or live smsBody).
    if (incomingType == "INCOME" && existing.type == "TRANSFER") {
        val isAutoTransfer = existing.smsBody != null ||
            existing.note?.contains("[T:A]") == true
        if (isAutoTransfer && existing.amount == incomingAmount &&
            abs(existing.timestamp - incomingTimestamp) < CROSS_BANK_TRANSFER_WINDOW_MS) {

            val storedIncRef  = incRefFromNote(existing.note)
            val toLast4       = lastFourFromNoteTag(existing.note, "To")
            val incomingLast4 = "\\d{3,4}$".toRegex().find(incomingAccountName.trim())?.value

            val accountMatches =
                existing.note?.contains("[To: $incomingAccountName]") == true ||       // exact
                (toLast4 != null && toLast4 == incomingLast4) ||                        // last-4 digits
                (storedIncRef != null && storedIncRef == incomingReference)             // IncRef tag

            if (accountMatches) return true
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