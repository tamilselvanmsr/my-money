package com.example.utils

import android.util.Log
import com.example.data.ExpenseCategory
import com.example.data.TransactionEntry
import java.util.regex.Pattern

data class SmsParsingResult(
    val title: String,
    val amount: Double,
    val category: ExpenseCategory,
    val type: String, // EXPENSE or INCOME
    val accountRef: String? = null, // Linked account ending/identifier e.g. "4321"
    val sender: String? = null,     // Source of transfer
    val receiver: String? = null,    // Destination of transfer
    val parsedTimestamp: Long? = null,
    val availableLimit: Double? = null, // Parsed available credit limit from CC SMS
    val totalCreditLimit: Double? = null, // Parsed total credit limit from CC Summary SMS
    val isBalanceUpdate: Boolean = false, // True when SMS is a bank balance notification
    val availableBalance: Double? = null,  // Parsed actual account balance from bank balance SMS
    val allBalancePairs: List<Pair<String, Double>> = emptyList() // All (accountRef-digits, balance) pairs for multi-account balance SMS
)

object SmsParser {
    private const val TAG = "SmsParser"

    // parseOffline() runs a 12-step pipeline. Private helpers below handle each concern:
    //   1  Header validation   — only -S / -T senders allowed
    //   2  Hard exclusions     — OTP, beneficiary ack, bill-due, requests, balance expiry
    //   3  Balance-update      — early-exit for balance-notification SMS
    //   4  Special wallets     — Apay, NeuCoins, EPFO (no standard last-4 ref)
    //   5  Soft validation     — bypassable filters (promo, reminder, utility check)
    //   6  Transaction keyword — at least one active transactional word required
    //   7  Amount extraction
    //   8  Transaction type    — INCOME or EXPENSE
    //   9  Account reference   — last 3–4 digits of account/card
    //  10  Timestamp
    //  11  Special types       — CC ACK, CC bill payment
    //  12  Payee / title

    fun parseOffline(
        body: String,
        senderId: String?,
        smsTimestamp: Long? = null,
        bypassExclusionFilter: Boolean = false
    ): SmsParsingResult? {
        val cleanBody = body.replace("\\s+".toRegex(), " ").trim()
        val lowerBody = cleanBody.lowercase()
        Log.d(TAG, "Parsing: '$cleanBody' from '$senderId'")

        // 1. Header — only verified -S / -T senders (TRAI DLT transactional format)
        if (senderId.isNullOrBlank()) {
            Log.d(TAG, "Excluded: sender is null/blank"); return null
        }
        val upper = senderId.trim().uppercase()
        if (!upper.endsWith("-S") && !upper.endsWith("-T")) {
            Log.d(TAG, "Excluded: sender '$senderId' is not -S/-T"); return null
        }

        // 2. Hard exclusions (non-bypassable)
        if (isOtpSms(lowerBody))            { Log.d(TAG, "Excluded: OTP"); return null }
        if (isBeneficiaryAck(lowerBody))    { Log.d(TAG, "Excluded: beneficiary ack"); return null }
        // CC Summary must be routed before bill-due check (it contains "outstanding" text)
        if (lowerBody.contains("credit card") && (lowerBody.contains("total outstanding balance") ||
                lowerBody.contains("outstanding balance") || lowerBody.contains("outstanding bal"))) {
            return tryParseBalanceUpdate(cleanBody, lowerBody, senderId, smsTimestamp)
        }
        if (isBillDueSms(lowerBody))        { Log.d(TAG, "Excluded: bill-due"); return null }
        if (isPaymentRequestSms(lowerBody)) { Log.d(TAG, "Excluded: payment request"); return null }
        if (lowerBody.contains("balance expire")) { Log.d(TAG, "Excluded: balance expiry"); return null }

        // 3. Balance-update early-exit
        tryParseBalanceUpdate(cleanBody, lowerBody, senderId, smsTimestamp)?.let { return it }

        // 4. Special wallets (no standard last-4 account ref)
        parseApayWallet(cleanBody, lowerBody, smsTimestamp)?.let { return it }
        parseNeuCoins(cleanBody, lowerBody, smsTimestamp)?.let { return it }
        parseEpfoContribution(cleanBody, lowerBody, smsTimestamp)?.let { return it }

        // 5. Soft validation (bypassable)
        if (!bypassExclusionFilter) {
            if (!SmsFilterUtility.isValidTransactionSms(body)) { Log.d(TAG, "Excluded: SmsFilterUtility"); return null }
            if (isPromoOrReminderSms(lowerBody)) { Log.d(TAG, "Excluded: promo/reminder"); return null }
        }

        // 6. Transaction keywords
        if (!hasTransactionKeywords(lowerBody)) { Log.d(TAG, "Excluded: no transactional keywords"); return null }

        // 7. Amount
        val amount = extractAmount(lowerBody) ?: run { Log.d(TAG, "Excluded: no amount"); return null }

        // 8. Type — check explicit EXPENSE signals FIRST so credit-card "spent on" SMS
        //    can never be mis-classified as INCOME even if the body mentions "credit"
        val type = when {
            lowerBody.contains("debited") ||
            lowerBody.contains("spent on") ||
            lowerBody.contains("charged to") ||
            lowerBody.contains("withdrawn") ||
            lowerBody.contains("deducted") -> "EXPENSE"
            lowerBody.contains("credited") ||
            lowerBody.contains("received") ||
            lowerBody.contains("deposited") ||
            lowerBody.contains("salary") ||
            lowerBody.contains("added to your wallet") ||
            lowerBody.contains("refund") -> "INCOME"
            else -> "EXPENSE"
        }

        // 9. Account reference (last 3–4 digits)
        val last4Digits = extractAccountRef(lowerBody) ?: run { Log.d(TAG, "Excluded: no account ref"); return null }
        var bankName = inferSmsBankCode(senderId, cleanBody)
        if (bankName.isBlank() || bankName.length <= 1) bankName = "Bank"
        val accountRef = "$bankName-$last4Digits"

        // 10. Timestamp
        val parsedTime = extractTimestampFromSms(cleanBody, smsTimestamp)

        // 11. Special transaction types
        if (lowerBody.contains("was credited to your card") ||
            (lowerBody.contains("online payment") && lowerBody.contains("vide ref") && lowerBody.contains("card"))) {
            return SmsParsingResult(
                title = "CC Payment ACK", amount = amount, category = ExpenseCategory.DEBT,
                type = "DUPLICATE", accountRef = last4Digits,
                sender = accountRef, receiver = "CC Payment ACK", parsedTimestamp = parsedTime
            )
        }
        if (lowerBody.contains("credit card") && (lowerBody.contains("received towards") ||
                lowerBody.contains("towards your credit card") ||
                (lowerBody.contains("payment") && lowerBody.contains("received") && lowerBody.contains("card")))) {
            val availLimit = Pattern.compile(
                "(?:available|avl|avail)\\s*(?:credit\\s*)?(?:limit|bal|balance)\\s+(?:is\\s+)?(?:rs\\.?\\s*|inr\\s*)?([0-9,]+(?:\\.[0-9]{1,2})?)",
                Pattern.CASE_INSENSITIVE
            ).let { pat -> val m = pat.matcher(cleanBody); if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() else null }
            return SmsParsingResult(
                title = "Credit Card Payment", amount = amount, category = ExpenseCategory.INCOME_OTHERS,
                type = "INCOME", accountRef = last4Digits,
                sender = accountRef, receiver = "Credit Card Payment",
                parsedTimestamp = parsedTime, availableLimit = availLimit
            )
        }

        // 12. Payee / title
        val title = extractPayeeTitle(cleanBody, lowerBody, body, type, bankName)
        // Extract appended available balance for ALL transaction types (income, expense, transfer).
        // Banks often append "Avl Bal Rs.X" or "Available Balance Rs.X" after debit/credit SMS.
        val avlBalance = Pattern.compile(
            "(?:avl|avail\\.?|available)\\s+bal(?:ance)?\\s+(?:inr|rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE
        ).let { pat -> val m = pat.matcher(cleanBody); if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() else null }

        return SmsParsingResult(
            title = title,
            amount = amount,
            category = inferCategory(type, title, lowerBody, amount),
            type = type,
            accountRef = last4Digits,
            sender = if (type == "EXPENSE") accountRef else title,
            receiver = if (type == "EXPENSE") title else accountRef,
            parsedTimestamp = parsedTime,
            availableBalance = avlBalance
        )
    }

    // ─── Exclusion helpers ────────────────────────────────────────────────────────

    private fun isOtpSms(lower: String) =
        lower.contains("otp") || lower.contains("one time password") ||
        lower.contains("verification code") || lower.contains("code is") ||
        lower.contains("do not share") || lower.contains("code to verify") ||
        lower.contains("secure code") || lower.contains("use code")

    // Rejects bank-side NEFT/IMPS/RTGS credit confirmations sent to the sender.
    // The user's debit is already recorded; parsing this would create a phantom credit.
    private fun isBeneficiaryAck(lower: String) =
        (lower.contains("credited to beneficiary") || lower.contains("creditied to beneficiary") ||
         lower.contains("credit to beneficiary") || lower.contains("to beneficiary ac") ||
         lower.contains("to beneficiary a/c")) &&
        (lower.contains("neft") || lower.contains("imps") || lower.contains("rtgs"))

    private fun isBillDueSms(lower: String) =
        lower.contains("is due on") ||
        (lower.contains("due on") && !lower.contains("credited") && !lower.contains("debited") && !lower.contains("received")) ||
        (lower.contains("due by") && !lower.contains("credited") && !lower.contains("debited")) ||
        lower.contains("total amt due") ||         // "total amount due" covered by "amount due"
        lower.contains("minimum amt due") ||       // "minimum amount due" covered by "amount due"
        lower.contains("min amt due") || lower.contains("statement due") ||
        lower.contains("stmt due") || lower.contains("amount due") ||
        lower.contains("due amount") || lower.contains("due :") || lower.contains("due:")

    private fun isPaymentRequestSms(lower: String) =
        lower.contains("requesting money") ||
        lower.contains("requested money") || lower.contains("request to pay") ||
        lower.contains("pay using link") || lower.contains("collect request") ||
        lower.contains("has requested") || lower.contains("requested to pay")

    private fun isPromoOrReminderSms(lower: String): Boolean {
        val isPromo =
            lower.contains("is due") || lower.contains("payment due") ||  // "is due" covers "is due by"
            lower.contains("reminds you") || lower.contains("reminder:") || lower.contains("pay before") ||
            lower.contains("outstanding") || lower.contains("overdue") ||
            (lower.contains("will be debited") && !lower.contains("autopay") && !lower.contains("auto-pay")) ||
            lower.contains("scheduled transfer") || lower.contains("is scheduled") ||
            lower.contains("upcoming payment") || lower.contains("requires action") ||
            lower.contains("eligible for") || lower.contains("pre-approved") || lower.contains("apply now") ||
            lower.contains("win up to") || lower.contains("congratulations") || lower.contains("earn cashback") ||
            (lower.contains("flat") && lower.contains("off")) || lower.contains("exclusive discount")
        val isConfirmed =
            lower.contains("received towards") || lower.contains("payment of") ||
            lower.contains("received") || lower.contains("payment received")
        return isPromo && !isConfirmed
    }

    private fun hasTransactionKeywords(lower: String) =
        lower.contains("debited") || lower.contains("credited") || lower.contains("spent") ||
        lower.contains("paid") || lower.contains("received") || lower.contains("withdrawn") ||
        lower.contains("withdrew") || lower.contains("deposited") ||          // "transferred" covered by "transfer"
        lower.contains("transfer") || lower.contains("deducted") ||           // "charged" covered by "charge"
        lower.contains("charge") || lower.contains("recharge successful") || lower.contains("salary") ||
        lower.contains("added to your wallet") || lower.contains("txn") || lower.contains("payment") ||
        lower.contains("sent") || lower.contains("refund") || lower.contains("autopay") ||
        lower.contains("auto-pay")

    // ─── Extraction helpers ───────────────────────────────────────────────────────

    private fun extractAmount(lower: String): Double? {
        // Primary: currency prefix (Rs., INR, ₹, $, etc.)
        Pattern.compile(
            "(?:rs\\.?|inr|usd|eur|egp|sgd|₹|\\$|debited with|debited\\s+by|debited\\s+of|sent\\s+rs\\.?|paid\\s+rs\\.?|received\\s+rs\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"
        ).matcher(lower).let { m ->
            if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        // Fallback: verb-adjacent amount ("debited 500", "paid 1,200.00")
        Pattern.compile(
            "(?:debited|spent|paid|received|charged|credited|sent|withdrawn|transfer|transferred|deducted)\\s+(?:of|by|with|to|for)?\\s*(?:rs\\.?\\s*)?([0-9,]+(?:\\.[0-9]{1,2})?)"
        ).matcher(lower).let { m ->
            if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    // Returns the 3–4 digit account/card suffix, or null if not found.
    // Note: bare "-" is intentionally excluded — it falsely matched date separators (e.g. "22-May-2026").
    private fun extractAccountRef(lower: String): String? {
        val m = Pattern.compile(
            "(?i)(?:a/c|\\bac\\b|acct|acc|account|card|ending in|ending with|ending at|ending|ended with|ended|vpa|xx|\\*+|no\\.?)\\s*(?:no\\.?\\s*)?([xX*]*\\d{3,4})\\b"
        ).matcher(lower)
        if (!m.find()) return null
        val digits = (m.group(1) ?: "").replace("[^0-9]".toRegex(), "")
        return digits.takeIf { it.length in 3..4 }
    }

    // ─── Special wallet parsers ───────────────────────────────────────────────────

    private fun parseApayWallet(cleanBody: String, lower: String, smsTimestamp: Long?): SmsParsingResult? {
        if (!lower.contains("apay wallet") && !lower.contains("apay balance") && !lower.contains("using apay")) return null
        val amount = Pattern.compile("(?:inr|rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE)
            .let { p -> val m = p.matcher(lower); if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0 else 0.0 }
        if (amount <= 0.0) return null
        val type = if (lower.contains("credited") || lower.contains("added") || lower.contains("refund")) "INCOME" else "EXPENSE"
        val title = Pattern.compile("(?:at|for)\\s+([A-Za-z][A-Za-z0-9.\\-]+)", Pattern.CASE_INSENSITIVE)
            .let { p -> val m = p.matcher(cleanBody)
                if (m.find()) (m.group(1) ?: "Apay").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
                else "Apay Wallet"
            }
        return SmsParsingResult(
            title = title, amount = amount,
            category = if (type == "INCOME") ExpenseCategory.CASHBACK else ExpenseCategory.SHOPPING,
            type = type, accountRef = "APAY_WALLET",
            parsedTimestamp = extractTimestampFromSms(cleanBody, smsTimestamp)
        )
    }

    private fun parseNeuCoins(cleanBody: String, lower: String, smsTimestamp: Long?): SmsParsingResult? {
        val isNeu = lower.contains("neucoin") ||
            (lower.contains("neucard") && (lower.contains("credited") || lower.contains("refund")))
        if (!isNeu) return null
        val amount = Pattern.compile("([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:neucoin|neu\\s*coins?)", Pattern.CASE_INSENSITIVE)
            .let { p -> val m = p.matcher(cleanBody); if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0 else 0.0 }
        if (amount <= 0.0) return null
        val type = if (lower.contains("debited") || lower.contains("used") || lower.contains("spent")) "EXPENSE" else "INCOME"
        val category = when {
            lower.contains("refund") -> ExpenseCategory.REFUNDS
            type == "INCOME"         -> ExpenseCategory.COINS
            else                     -> ExpenseCategory.SHOPPING
        }
        val titleMatcher = Pattern.compile(
            "(?:at|for)\\s+([A-Za-z][A-Za-z0-9.\\-]+(?:\\s+[A-Za-z][A-Za-z0-9.\\-]+)*)(?:\\s+on\\b|\\s+check\\b|\\s*\\.\\s*check|\\s+team\\b|\\s*https)",
            Pattern.CASE_INSENSITIVE
        ).matcher(cleanBody)
        val title = when {
            lower.contains("refund") -> "Refund"
            type == "INCOME"         -> "NeuCoins Credited"
            titleMatcher.find()      -> (titleMatcher.group(1) ?: "NeuCoins").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
            else                     -> "NeuCoins"
        }
        return SmsParsingResult(
            title = title, amount = amount, category = category,
            type = type, accountRef = "NEUCOINS_WALLET",
            parsedTimestamp = extractTimestampFromSms(cleanBody, smsTimestamp)
        )
    }

    // EPFO: passbook balance is the running total, not the transaction amount.
    // We extract the contribution amount as PROVIDENT_FUND income instead.
    private fun parseEpfoContribution(cleanBody: String, lower: String, smsTimestamp: Long?): SmsParsingResult? {
        if (!lower.contains("passbook balance") || !lower.contains("contribution")) return null
        val contribution = Pattern.compile(
            "contribution\\s+of\\s+(?:rs\\.?\\s*|inr\\s*)([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE
        ).let { p -> val m = p.matcher(cleanBody); if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0 else 0.0 }
        if (contribution <= 0.0) return null
        val passbookBal = Pattern.compile(
            "passbook\\s+balance\\s+(?:against\\s+[^\\s]+\\s+)?is\\s+(?:rs\\.?\\s*)([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE
        ).let { p -> val m = p.matcher(cleanBody); if (m.find()) m.group(1)?.replace(",", "")?.toDoubleOrNull() else null }
        val epfoLast4 = Pattern.compile("[A-Z]+\\*+([0-9]{4})\\b")
            .let { p -> val m = p.matcher(cleanBody); if (m.find()) m.group(1) else null }
        return SmsParsingResult(
            title = "PF Contribution", amount = contribution,
            category = ExpenseCategory.PROVIDENT_FUND, type = "INCOME",
            accountRef = epfoLast4,
            parsedTimestamp = extractTimestampFromSms(cleanBody, smsTimestamp),
            availableBalance = passbookBal
        )
    }

    // ─── Payee / title extraction ─────────────────────────────────────────────────

    private fun extractPayeeTitle(cleanBody: String, lowerBody: String, rawBody: String, type: String, bankName: String): String {
        // Priority 1: UPI VPA handle (e.g. jeeva@okaxis) — 2-char handles allowed
        Pattern.compile("\\b([a-zA-Z0-9.\\-_]+@[a-zA-Z0-9]{2,})\\b").matcher(cleanBody).let { m ->
            if (m.find()) return m.group(1) ?: ""
        }

        // Scrub noise: card/account refs, verb+amount pairs, standalone amounts, date strings
        var payeeBody = lowerBody
        payeeBody = payeeBody.replace("(?:spent on|from)\\s+(?:your\\s+)?(?:[a-zA-Z0-9\\s]+)?(?:card|credit card|debit card|acc|account|a/c|bank|acct)\\s*(?:a/c|acc|card)?\\s*(?:ending with|ending in|ending at|ending|ended with|ended|at|with)?\\s*[xX*\\s-]*\\d{3,4}".toRegex(), "")
        payeeBody = payeeBody.replace("(?:a/c|\\bac\\b)\\.?\\s*(?:no\\.?)?\\s*[xX*\\s-]*\\d{3,4}\\s*(?:debited|spent|paid|received|charged|credited|sent|withdrawn|transfer|transferred|deducted|has been|is)?".toRegex(), "")
        payeeBody = payeeBody.replace("(?:debited|credited|spent|paid|received|sent|withdrawn)\\s+(?:by|for|of)?\\s*(?:rs\\.?\\s*)?\\d+(?:\\.\\d+)?".toRegex(), "")
        payeeBody = payeeBody.replace("(?:rs\\.?\\s*)?\\d+(?:\\.\\d+)?".toRegex(), "")
        payeeBody = payeeBody.replace("on\\s+date\\s+[a-zA-Z0-9/.-]+".toRegex(), "")
        payeeBody = payeeBody.replace("dated\\s+[a-zA-Z0-9/.-]+".toRegex(), "")

        // Extract vendor name via prepositions (most-specific to least-specific)
        var title = ""
        val vendorMatcher = Pattern.compile(
            "(?:transferred to|received from|spent on|paid to|transfer to|trf to|towards|merchant|info:|from|for|at|to)\\s+([a-zA-Z0-9\\s\\.\\-\\'&_]+)"
        ).matcher(payeeBody)
        if (vendorMatcher.find()) {
            val raw = vendorMatcher.group(1)?.trim() ?: ""
            val parts = raw.split("\\b(on|by|using|with|via|for|against|card|account|txn|ref|refno|ref\\.no|dated|at|transfer|if|call|dial|contact|help|link|click|visit|balance|bal|limit|avl|upi)\\b".toRegex())
            title = parts.firstOrNull()?.trim() ?: ""
        }
        title = title.replace("\\d+".toRegex(), "").trimEnd('.', ',', ':', ';').trim()

        // Fallback: payment channel + mobile number (IMPS/NEFT/RTGS/UPI)
        if (title.isEmpty() || title.length <= 2 || title.lowercase() in listOf("merchant store", "bank transfer")) {
            val mobile = Pattern.compile("\\b(?:linked\\s+)?mobile\\s*([0-9xX*]{5,15})\\b", Pattern.CASE_INSENSITIVE)
                .let { p -> val m = p.matcher(rawBody); if (m.find()) "Mobile " + m.group(1) else "" }
            val mode = when {
                rawBody.lowercase().contains("imps") -> "IMPS"
                rawBody.lowercase().contains("neft") -> "NEFT"
                rawBody.lowercase().contains("rtgs") -> "RTGS"
                rawBody.lowercase().contains("upi")  -> "UPI"
                else -> ""
            }
            title = when {
                mode.isNotEmpty() && mobile.isNotEmpty() -> "$mode - $mobile"
                mode.isNotEmpty()                        -> "$mode Transfer"
                mobile.isNotEmpty()                      -> mobile
                else                                     -> ""
            }
        }

        if (title.isEmpty() || title.length > 45 || title.length <= 2) {
            return if (type == "EXPENSE") "Merchant Store" else "$bankName Transfer"
        }
        // Title-case plain names; keep mode strings (IMPS, UPI, etc.) and mobile numbers as-is
        if ("Mobile" !in title && "IMPS" !in title && "NEFT" !in title && "RTGS" !in title && "UPI" !in title) {
            title = title.split(" ").filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
        }
        return title
    }

    // ─── Category inference ───────────────────────────────────────────────────────

    private fun inferCategory(type: String, titleText: String, lowerBody: String, amount: Double): ExpenseCategory {
        val t = titleText.lowercase()
        if (type == "INCOME") return when {
            lowerBody.contains("refund") || t.contains("refund")     -> ExpenseCategory.REFUNDS
            t.contains("cashback") || lowerBody.contains("cashback") -> ExpenseCategory.CASHBACK
            lowerBody.contains("upi") || lowerBody.contains("vpa") || t.contains("upi") || t.contains("@") -> ExpenseCategory.UPI
            t.contains("pocket money") || t.contains("allowance")   -> ExpenseCategory.INCOME_OTHERS
            lowerBody.contains("salary") || t.contains("salary")    -> ExpenseCategory.SALARY
            else                                                     -> ExpenseCategory.INCOME_OTHERS
        }
        return when {
            t.contains("tea") || t.contains("stall") ||
                (amount == 10.0 || amount == 12.0 || amount == 15.0 || amount == 20.0) -> ExpenseCategory.SOFT_HOT_DRINKS

            t.contains("starbucks") || t.contains("mcdonald") || t.contains("swiggy") ||
                t.contains("zomato") || t.contains("food") || t.contains("restaurant") ||
                t.contains("eats") || t.contains("cafe") || t.contains("pizza") || t.contains("hotel") ||
                lowerBody.contains("canteen") || lowerBody.contains("bakery") -> ExpenseCategory.FOOD

            t.contains("gas") || t.contains("petrol") || t.contains("fuel") || t.contains("agencies") ||
                lowerBody.contains("fuel station") || lowerBody.contains("petrol bunk") -> ExpenseCategory.FUEL

            t.contains("loan") || t.contains("debt") || t.contains("emi") ||
                t.contains("credila") || lowerBody.contains("repayment") -> ExpenseCategory.DEBT

            t.contains("nike") || t.contains("adidas") || t.contains("shoes") ||
                t.contains("footwear") || t.contains("bata") -> ExpenseCategory.SHOES

            t.contains("clothes") || t.contains("fashion") || t.contains("clothing") ||
                t.contains("zara") || t.contains("readymade") || t.contains("apparel") ||
                t.contains("trends") || t.contains("zudio") || t.contains("Levi's") ||
                t.contains("raymond") || t.contains("texttile") -> ExpenseCategory.CLOTHES

            t.contains("walmart") || t.contains("blinkit") || t.contains("zepto") ||
                t.contains("mart") || t.contains("dmart") || t.contains("bigbasket") ||
                t.contains("grocery") || t.contains("departmental") ||
                (t.contains("store") && !t.contains("playstore") && !t.contains("play store")) ||
                lowerBody.contains("supermarket") -> ExpenseCategory.GROCERIES

            t.contains("uber") || t.contains("lyft") || t.contains("ola") ||
                t.contains("rapido") || t.contains("taxi") || t.contains("cab") ||
                t.contains("metro") || lowerBody.contains("transit") -> ExpenseCategory.TRANSPORT

            t.contains("electric") || t.contains("water") || t.contains("utility") ||
                t.contains("bill") || t.contains("recharge") || t.contains("netflix") ||
                t.contains("broadband") || t.contains("club") || t.contains("payments") ||
                t.contains("airtel") || t.contains("jio") || lowerBody.contains("insurance") ||
                lowerBody.contains("mobile bill") -> ExpenseCategory.BILLS

            t.contains("movie") || t.contains("cinema") || t.contains("steam") ||
                t.contains("spotify") || t.contains("pub") || t.contains("bar") ||
                t.contains("concert") || t.contains("game") ||
                t.contains("playstore") || t.contains("play store") ||
                t.contains("mall") || t.contains("theatre") -> ExpenseCategory.ENTERTAINMENT

            t.contains("hospital") || t.contains("pharmacy") || t.contains("medical") ||
                t.contains("doctor") || t.contains("clinic") || lowerBody.contains("medicine") -> ExpenseCategory.HEALTHCARE

            t.contains("school") || t.contains("college") || t.contains("tuition") ||
                t.contains("education") || t.contains("book") || t.contains("course") -> ExpenseCategory.EDUCATION

            t.contains("zerodha") || t.contains("groww") || t.contains("INDMoney") -> ExpenseCategory.INVESTMENT

            t.contains("mutual fund") || t.contains("mutualfund") -> ExpenseCategory.MUTUAL_FUND

            t.contains("shopping") || t.contains("amazon") || t.contains("flipkart") ||
                t.contains("techno") || t.contains("croma") -> ExpenseCategory.ELECTRONICS

            t.contains("redbus") || t.contains("irctc") || t.contains("travel") -> ExpenseCategory.TRAVEL

            t.contains("protein") || t.contains("powder") || t.contains("supplement") ||
                t.contains("whey") || t.contains("nutrition") || t.contains("gym") ||
                t.contains("fitness") || t.contains("workout") -> ExpenseCategory.GYM

            t.contains("fruit") || t.contains("market") || t.contains("banana") -> ExpenseCategory.FRUITS

            t.contains("bike") || t.contains("motorcycle") || t.contains("two wheeler") ||
                t.contains("garage") || t.contains("automobile") || t.contains("mechanic") ||
                t.contains("royal enfield") || t.contains("RE bike") ||
                t.contains("water wash") -> ExpenseCategory.BIKE

            t.contains("gift") || t.contains("gifting") || t.contains("friend") ||
                t.contains("shagun") || t.contains("giftcard") -> ExpenseCategory.GIFTING_FRIENDS

            else -> ExpenseCategory.OTHERS
        }
    }

    private fun extractTimestampFromSms(body: String, referenceTimestamp: Long? = null): Long? {
        val lower = body.lowercase()
        // Match formats DD-MM-YYYY, DD/MM/YYYY, DD-MM-YY, DD/MM/YY
        val datePattern = Pattern.compile("\\b(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})\\b")
        val matcher = datePattern.matcher(body)
        
        var day = -1
        var month = -1
        var year = -1
        
        if (matcher.find()) {
            day = matcher.group(1)?.toIntOrNull() ?: -1
            month = matcher.group(2)?.toIntOrNull() ?: -1
            val rawYear = matcher.group(3)?.toIntOrNull() ?: -1
            year = if (rawYear in 0..99) rawYear + 2000 else rawYear
        } else {
            // Match formats DD-MMM-YYYY or DD-MMM-YY e.g. 29-May-2026 or 15-May-26
            val textMonthPattern = Pattern.compile("\\b(\\d{1,2})[-/\\s]?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[-/\\s]?(\\d{2,4})\\b", Pattern.CASE_INSENSITIVE)
            val textMatcher = textMonthPattern.matcher(body)
            if (textMatcher.find()) {
                day = textMatcher.group(1)?.toIntOrNull() ?: -1
                val mStr = textMatcher.group(2)?.lowercase() ?: ""
                month = when (mStr) {
                    "jan" -> 1 "feb" -> 2 "mar" -> 3 "apr" -> 4 "may" -> 5 "jun" -> 6
                    "jul" -> 7 "aug" -> 8 "sep" -> 9 "oct" -> 10 "nov" -> 11 "dec" -> 12
                    else -> -1
                }
                val rawYear = textMatcher.group(3)?.toIntOrNull() ?: -1
                year = if (rawYear in 0..99) rawYear + 2000 else rawYear
            }
        }
        
        if (day in 1..31 && month in 1..12 && year >= 2000) {
            val refCal = java.util.Calendar.getInstance()
            if (referenceTimestamp != null) {
                refCal.timeInMillis = referenceTimestamp
            }
            var hour = refCal.get(java.util.Calendar.HOUR_OF_DAY)
            var minute = refCal.get(java.util.Calendar.MINUTE)
            var second = refCal.get(java.util.Calendar.SECOND)
            
            // Match formats HH:MM:SS, HH:MM, HH.MM AM/PM
            val timePattern = Pattern.compile("\\b(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b", Pattern.CASE_INSENSITIVE)
            val timeMatcher = timePattern.matcher(body)
            if (timeMatcher.find()) {
                var h = timeMatcher.group(1)?.toIntOrNull() ?: 12
                val m = timeMatcher.group(2)?.toIntOrNull() ?: 0
                val s = timeMatcher.group(3)?.toIntOrNull() ?: 0
                val ampm = timeMatcher.group(4)?.lowercase()
                
                if (ampm != null) {
                    if (ampm == "pm" && h < 12) h += 12
                    if (ampm == "am" && h == 12) h = 0
                }
                if (h in 0..23 && m in 0..59 && s in 0..59) {
                    hour = h
                    minute = m
                    second = s
                }
            }
            
            try {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.YEAR, year)
                cal.set(java.util.Calendar.MONTH, month - 1)
                cal.set(java.util.Calendar.DAY_OF_MONTH, day)
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                cal.set(java.util.Calendar.MINUTE, minute)
                cal.set(java.util.Calendar.SECOND, second)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            } catch (e: Exception) {
                // fall through
            }
        }
        return null
    }

    fun getReferenceNumber(body: String?): String? {
        if (body == null) return null
        val patterns = listOf(
            "\\bupi\\s*ref(?:erence)?(?:\\s*no)?[:\\s-]+([a-z0-9]+)\\b",
            "\\bref(?:erence)?\\s*(?:no|num|number)?\\.?\\s*[:\\s-]+([a-z0-9]+)\\b",
            "\\btxn(?:\\s*id)?[:\\s-]+([a-z0-9]+)\\b",
            "\\btransaction\\s*id[:\\s-]+([a-z0-9]+)\\b",
            "\\bimps[:\\s-/]+([a-z0-9]+)\\b",
            "\\bref\\.([a-z0-9]+)\\b",
            // "UPI 1234567890" / "IMPS 1234567890" — payment-channel keyword directly followed by
            // a long numeric ref (≥8 digits) with no "Ref" keyword between them.
            "\\b(?:upi|imps|neft|rtgs)\\s+([0-9]{8,})\\b"
        )
        
        for (patStr in patterns) {
            val pattern = Pattern.compile(patStr, Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(body)
            if (matcher.find()) {
                val ref = matcher.group(1)
                if (ref != null && ref.trim().isNotEmpty() && ref.trim().length >= 3) {
                    return ref.trim()
                }
            }
        }
        return null
    }

    /**
     * Detects bank available balance / balance notification SMS messages.
     * Patterns:
     *   "Avail Bal in A/c xxx300: Rs.3393.08 CR -SBI"
     *   "Your Balance in account no. ending with 9553 is Rs. 0.12 -HDFC BANK"
     */
    private fun tryParseBalanceUpdate(cleanBody: String, lowerBody: String, senderId: String?, smsTimestamp: Long?): SmsParsingResult? {

        // ── Credit Card Summary SMS (checked FIRST, before generic isBalanceSms guard) ──────
        // Some CC Summary formats lack "ending with"/"a/c" so they fail the generic guard.
        // Always route them here when all three key phrases are present.
        // e.g. "HDFC BANK Credit Card Summary: Total Credit Limit: Rs. 2,00,000.
        //       Available Credit Limit: Rs. 1,50,000. Total Outstanding Balance: Rs. 50,000."
        val isCcSummaryBody = lowerBody.contains("credit card") && (
            lowerBody.contains("total outstanding balance") ||
            lowerBody.contains("outstanding balance") ||
            lowerBody.contains("outstanding bal")
        )
        if (isCcSummaryBody) {
            // Use [^0-9]* to skip any currency prefix (Rs., ₹, INR, invisible chars, etc.)
            val outstandingPat = Pattern.compile(
                "(?:total\\s+)?outstanding\\s+(?:balance|bal)[^0-9]*([0-9,]+(?:\\.[0-9]{1,2})?)",
                Pattern.CASE_INSENSITIVE
            )
            val outstandingMatcher = outstandingPat.matcher(cleanBody)
            val outstanding = if (outstandingMatcher.find())
                outstandingMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() else null

            val availLimitPat = Pattern.compile(
                "available\\s+credit\\s+limit[^0-9]*([0-9,]+(?:\\.[0-9]{1,2})?)",
                Pattern.CASE_INSENSITIVE
            )
            val availLimitMatcher = availLimitPat.matcher(cleanBody)
            val availCreditLimit = if (availLimitMatcher.find())
                availLimitMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() else null

            val totalLimitPat = Pattern.compile(
                "total\\s+credit\\s+limit[^0-9]*([0-9,]+(?:\\.[0-9]{1,2})?)",
                Pattern.CASE_INSENSITIVE
            )
            val totalLimitMatcher = totalLimitPat.matcher(cleanBody)
            val totalCreditLimit = if (totalLimitMatcher.find())
                totalLimitMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() else null

            val refPat = Pattern.compile(
                "(?:ending\\s+with|ending\\s+in|ending)\\s*[*xX\\s]*([0-9]{3,4})",
                Pattern.CASE_INSENSITIVE
            )
            val refMatcher = refPat.matcher(cleanBody)
            val ccRef = if (refMatcher.find()) refMatcher.group(1) else null

            // Return CC Summary result whenever we can identify the account or limits.
            // If outstandingPat couldn't match directly, derive outstanding from the two limits
            // (Total Limit - Available Limit) which are more reliably parsed.
            val effectiveOutstanding = outstanding
                ?: if (totalCreditLimit != null && availCreditLimit != null)
                    totalCreditLimit - availCreditLimit
                   else null
            if (ccRef != null || totalCreditLimit != null) {
                return SmsParsingResult(
                    title = "CC Summary",
                    amount = effectiveOutstanding ?: 0.0,
                    category = ExpenseCategory.INCOME_OTHERS,
                    type = "INCOME",
                    accountRef = ccRef,
                    parsedTimestamp = extractTimestampFromSms(cleanBody, smsTimestamp),
                    isBalanceUpdate = true,
                    availableBalance = if (effectiveOutstanding != null) -effectiveOutstanding else null,
                    availableLimit = availCreditLimit,
                    totalCreditLimit = totalCreditLimit
                )
            }
        }

        // Generic balance SMS guard — applies only to non-CC-Summary paths below
        val isBalanceSms =
            lowerBody.contains("avail bal") ||
            lowerBody.contains("avail. bal") ||
            lowerBody.contains("available bal") ||
            lowerBody.contains("available balance") ||
            lowerBody.contains("your balance") ||
            (lowerBody.contains("balance") && (
                lowerBody.contains("a/c") || lowerBody.contains("account no") ||
                lowerBody.contains("account number") || lowerBody.contains("ending with")
            )) ||
            (lowerBody.contains(" bal ") && lowerBody.contains("a/c"))
        if (!isBalanceSms) return null

        // ── Transaction SMS with appended balance info — NOT a pure balance update ──────
        // Any debit/credit/transfer keyword means this is a real transaction;
        // the "Avl bal" is just post-transaction info appended by the bank.
        // Let the main parser handle it — it will extract both the transaction AND the balance.
        val hasTransactionAction = lowerBody.contains("deposited") ||
            lowerBody.contains("credited") ||
            lowerBody.contains("debited") ||
            lowerBody.contains("transferred") ||
            lowerBody.contains("received")
        if (hasTransactionAction) return null

        val allPairs = mutableListOf<Pair<String, Double>>()

        // Multi-account format: "XXXXXX1234 INR 2204.092Cr, XXXXXXX5678 INR 100Cr"
        val multiAccPattern = Pattern.compile(
            "[xX*]{2,}(\\d{3,4})\\s+(?:inr|rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,3})?)",
            Pattern.CASE_INSENSITIVE
        )
        val multiMatcher = multiAccPattern.matcher(cleanBody)
        while (multiMatcher.find()) {
            val ref = multiMatcher.group(1) ?: continue
            val bal = multiMatcher.group(2)?.replace(",", "")?.toDoubleOrNull() ?: continue
            allPairs.add(ref to bal)
        }

        // Standard single-account format: "a/c xxx300: Rs.3393.08" / "ending with 1234 is Rs. 0.12"
        if (allPairs.isEmpty()) {
            val amtPattern = Pattern.compile(
                "(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,3})?)",
                Pattern.CASE_INSENSITIVE
            )
            val amtMatcher = amtPattern.matcher(cleanBody)
            var balance = if (amtMatcher.find()) amtMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() else null
            // Fallback: amount without currency symbol, e.g. "is 10,000.12" or ": 10,000.12"
            // Only trigger when amount has Indian comma-format (e.g. 1,000 or 10,000.12) to avoid false matches on years/dates.
            if (balance == null) {
                val noCurrPat = Pattern.compile(
                    "(?:is|:)\\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?)",
                    Pattern.CASE_INSENSITIVE
                )
                val noCurrMatcher = noCurrPat.matcher(cleanBody)
                if (noCurrMatcher.find()) balance = noCurrMatcher.group(1)?.replace(",", "")?.toDoubleOrNull()
            }
            if (balance == null) return null

            val refPattern = Pattern.compile(
                "(?:a/c|acc|account|ending\\s*with|no\\.?\\s*ending|ending)\\s*[xXx*\\s-]*([0-9]{3,4})\\b",
                Pattern.CASE_INSENSITIVE
            )
            val refMatcher = refPattern.matcher(cleanBody)
            val accountRef = if (refMatcher.find()) refMatcher.group(1)?.replace("[^0-9]".toRegex(), "") else null
            if (accountRef == null) return null
            allPairs.add(accountRef to balance)
        }

        if (allPairs.isEmpty()) return null

        val (firstRef, firstBal) = allPairs.first()
        val parsedTime = extractTimestampFromSms(cleanBody, smsTimestamp)

        // "As of yesterday" / "as on yesterday" balance SMS:
        // The reported balance is the closing balance of the PREVIOUS day, not today.
        // Pin the timestamp to 23:59:59 of that date so the baseline sits at the
        // very end of yesterday — today's transactions are unaffected and account
        // correctly from this balance onward.
        val isYesterdayBalance = lowerBody.contains("as on yesterday") ||
            lowerBody.contains("as of yesterday") ||
            lowerBody.contains("yesterday")

        val effectiveTime: Long? = if (isYesterdayBalance) {
            val cal = java.util.Calendar.getInstance()
            when {
                parsedTime != null -> {
                    // extractTimestampFromSms already found yesterday's date in the body — just fix the time.
                    cal.timeInMillis = parsedTime
                }
                smsTimestamp != null -> {
                    // No date in body; roll back one day from the SMS receive time.
                    cal.timeInMillis = smsTimestamp
                    cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                }
                else -> {
                    // Fallback: roll back one day from now.
                    cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                }
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            cal.set(java.util.Calendar.MINUTE, 59)
            cal.set(java.util.Calendar.SECOND, 59)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } else {
            parsedTime
        }

        return SmsParsingResult(
            title = "Balance Update",
            amount = firstBal,
            category = ExpenseCategory.INCOME_OTHERS,
            type = "INCOME",
            accountRef = firstRef,
            parsedTimestamp = effectiveTime,
            isBalanceUpdate = true,
            availableBalance = firstBal,
            allBalancePairs = allPairs
        )
    }
}
