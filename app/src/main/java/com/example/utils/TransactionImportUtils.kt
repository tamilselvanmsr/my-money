package com.example.utils

import com.example.data.TransactionEntry
import kotlin.math.abs

private const val DUPLICATE_MATCH_WINDOW_MS = 60_000L

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
    if (existing.smsBody == incomingSmsBody) {
        return true
    }

    val existingReference = SmsParser.getReferenceNumber(existing.smsBody)
    if (incomingReference != null && existingReference != null) {
        return incomingReference == existingReference
    }

    val sameCoreFields = existing.amount == incomingAmount &&
        existing.type == incomingType &&
        existing.title.equals(incomingTitle, ignoreCase = true) &&
        existing.getAccountName().equals(incomingAccountName, ignoreCase = true)

    return sameCoreFields && abs(existing.timestamp - incomingTimestamp) < DUPLICATE_MATCH_WINDOW_MS
}