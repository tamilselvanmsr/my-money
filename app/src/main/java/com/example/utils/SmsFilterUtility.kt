package com.example.utils

import android.util.Log
import java.util.regex.Pattern

object SmsFilterUtility {
    private const val TAG = "SmsFilterUtility"

    // 1. Mandatory transaction inclusion keywords
    private val INCLUSION_KEYWORDS = listOf("debited", "credited", "spent", "received", "deducted", "sent", "paid", "withdrawn", "transfer", "transfered", "transferred", "payment", "charge", "charged", "txn", "refund")

    // 2. Strict exclusion keywords (including loan offers, credit line/load spam, and pre-approved/eligibility promos)
    private val EXCLUSION_KEYWORDS = listOf("due", "emi", "loan", "otp", "mandate", "load", "eligibility", "apply", "approved")

    // 3. Accounts or card numbers typically show last 3 or 4 digits.
    // This matches:
    // - explicit patterns like a/c, acct, ending in, card, ending, xx, etc. followed by 3-4 digits (with optional stars/x)
    // We STRICTLY do not match standalone 3 or 4 digit numbers to avoid misclassifying years (like 2026) or dates.
    private val ACC_CARD_PATTERN = Pattern.compile(
        "(?i)(?:a/c|acct|acc|account|card|ending in|ending with|ending|ended with|ended|vpa|xx|\\*+|-|no\\.?)\\s*(?:no\\.?\\s*)?([xX*]*\\d{3,4})\\b"
    )

    /**
     * Strictly validates whether an SMS body represents a valid transaction message.
     */
    fun isValidTransactionSms(body: String): Boolean {
        val cleanBody = body.replace("\\s+".toRegex(), " ").trim()
        val lowerBody = cleanBody.lowercase()

        // A. Explicit ignore/exclusion check
        for (ex in EXCLUSION_KEYWORDS) {
            if (lowerBody.contains(ex)) {
                Log.d(TAG, "Filtering: SMS rejected. Found ignored/excluded pattern: '$ex'")
                return false
            }
        }

        // B. Presence check of mandatory past-tense transactional keywords
        var hasInclusion = false
        for (inc in INCLUSION_KEYWORDS) {
            if (lowerBody.contains(inc)) {
                hasInclusion = true
                break
            }
        }
        if (!hasInclusion) {
            Log.d(TAG, "Filtering: SMS rejected. Missing required inclusion keywords like debited/credited/spent/received/deducted.")
            return false
        }

        // C. Verify presence of last 3 or 4 digit a/c number or card number
        val matcher = ACC_CARD_PATTERN.matcher(cleanBody)
        if (!matcher.find()) {
            Log.d(TAG, "Filtering: SMS rejected. No 3 or 4 digit account/card number pattern detected.")
            return false
        }

        Log.d(TAG, "Filtering: SMS successfully validated as transaction.")
        return true
    }
}
