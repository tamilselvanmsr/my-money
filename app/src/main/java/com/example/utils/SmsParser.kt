package com.example.utils

import android.util.Log
import com.example.data.ExpenseCategory
import com.example.data.TransactionEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SmsParsingResult(
    val title: String,
    val amount: Double,
    val category: ExpenseCategory,
    val type: String, // EXPENSE or INCOME
    val isGeminiParsed: Boolean,
    val accountRef: String? = null, // Linked account ending/identifier e.g. "4321"
    val sender: String? = null,     // Source of transfer
    val receiver: String? = null,    // Destination of transfer
    val parsedTimestamp: Long? = null,
    val availableLimit: Double? = null, // Parsed available credit limit from CC SMS
    val isBalanceUpdate: Boolean = false, // True when SMS is a bank balance notification
    val availableBalance: Double? = null,  // Parsed actual account balance from bank balance SMS
    val allBalancePairs: List<Pair<String, Double>> = emptyList() // All (accountRef-digits, balance) pairs for multi-account balance SMS
)

object SmsParser {
    private const val TAG = "SmsParser"

    // Strict offline parser — processes SMS in a well-defined pipeline:
    //   STEP 1  Header validation      (non-bypassable)
    //   STEP 2  Hard exclusions        (non-bypassable: OTP, bill due, requests, balance expiry)
    //   STEP 3  Balance update         (early-return for balance-notification SMS)
    //   STEP 4  Special wallet parsers (early-return for Apay Wallet, NeuCoins)
    //   STEP 5  Soft validation        (bypassable via bypassExclusionFilter)
    //   STEP 6  Transaction keywords   (must contain at least one transactional trigger)
    //   STEP 7  Amount extraction
    //   STEP 8  Transaction type       (INCOME / EXPENSE)
    //   STEP 9  Account reference      (last 3–4 digits)
    //   STEP 10 Timestamp extraction
    //   STEP 11 Special transaction types (CC ACK, CC bill payment)
    //   STEP 12 Payee / title extraction
    fun parseOffline(body: String, senderId: String?, smsTimestamp: Long? = null, bypassExclusionFilter: Boolean = false): SmsParsingResult? {
        val cleanBody = body.replace("\\s+".toRegex(), " ").trim()
        val lowerBody = cleanBody.lowercase()

        Log.d(TAG, "Parsing text: '$cleanBody' from sender: '$senderId'")

        // ── STEP 1: HEADER VALIDATION ─────────────────────────────────────────
        // Non-bypassable. Only verified bank/fintech senders ending in -S or -T are
        // processed (e.g. JD-JUSPAY-S, AX-QCAMZN-S, AD-TNUCRD-S, VK-HDFCBK-T).
        if (!senderId.isNullOrBlank()) {
            val upperSender = senderId.trim().uppercase()
            if (!upperSender.endsWith("-S") && !upperSender.endsWith("-T")) {
                Log.d(TAG, "Excluded: Sender '$senderId' does not end with -S or -T.")
                return null
            }
        }

        // ── STEP 2: HARD EXCLUSIONS ───────────────────────────────────────────
        // Non-bypassable — these must never create transactions or accounts.
        // Intentionally placed BEFORE balance-update detection (Step 3) so that
        // edge cases like balance-expiry notifications are rejected early.

        // 2a. OTP / verification codes — never transactional
        val isOtp = lowerBody.contains("otp") ||
            lowerBody.contains("one time password") ||
            lowerBody.contains("verification code") ||
            lowerBody.contains("code is") ||
            lowerBody.contains("do not share") ||
            lowerBody.contains("code to verify") ||
            lowerBody.contains("secure code") ||
            lowerBody.contains("use code") ||
            lowerBody.contains("verification otp")
        if (isOtp) {
            Log.d(TAG, "Excluded: OTP / verification code SMS.")
            return null
        }

        // 2b. Bill-due notices and CC statement alerts
        val isBillDue = lowerBody.contains("is due on") ||
            (lowerBody.contains("due on") && !lowerBody.contains("credited") && !lowerBody.contains("debited") && !lowerBody.contains("received")) ||
            (lowerBody.contains("due by") && !lowerBody.contains("credited") && !lowerBody.contains("debited")) ||
            lowerBody.contains("total amt due") ||
            lowerBody.contains("total amount due") ||
            lowerBody.contains("minimum amt due") ||
            lowerBody.contains("minimum amount due") ||
            lowerBody.contains("min amt due") ||
            lowerBody.contains("statement due") ||
            lowerBody.contains("stmt due") ||
            lowerBody.contains("amount due") ||
            lowerBody.contains("due amount") ||
            lowerBody.contains("due :") ||
            lowerBody.contains("due:")
        if (isBillDue) {
            Log.d(TAG, "Excluded: Bill-due / CC statement SMS.")
            return null
        }

        // 2c. Payment request CTAs (UPI collect, money requests)
        val isPaymentRequest = lowerBody.contains("has requested you") ||
            lowerBody.contains("requesting money") ||
            lowerBody.contains("requested money") ||
            lowerBody.contains("request to pay") ||
            lowerBody.contains("pay using link") ||
            lowerBody.contains("collect request") ||
            lowerBody.contains("has requested") ||
            lowerBody.contains("requested to pay")
        if (isPaymentRequest) {
            Log.d(TAG, "Excluded: Payment request / UPI collect SMS.")
            return null
        }

        // 2d. Wallet / reward balance-expiry notifications
        //     e.g. "Rs. 5.20 added to Zomato Money … This balance expires on 15 Jul 2026."
        //     Must be rejected here, before the balance-update detector in Step 3,
        //     because such SMS can match the "balance … ending with" structure.
        if (lowerBody.contains("balance expire")) {
            Log.d(TAG, "Excluded: Wallet / reward balance-expiry notification.")
            return null
        }

        // ── STEP 3: BALANCE UPDATE DETECTION ─────────────────────────────────
        // Detects pure balance-notification SMS ("Avail Bal in A/c xxx300: Rs.3393.08 CR").
        // Returns early with isBalanceUpdate = true; balance-sync is handled by the ViewModel.
        val balanceUpdateResult = tryParseBalanceUpdate(cleanBody, lowerBody, senderId, smsTimestamp)
        if (balanceUpdateResult != null) return balanceUpdateResult

        // ── STEP 4: SPECIAL WALLET PARSERS ───────────────────────────────────
        // These SMS types carry no standard last-4-digit account reference.
        // They map to named wallet accounts and return early before the main pipeline.

        // 4a. Apay Wallet transactions (senders: JD-JUSPAY-S, AX-QCAMZN-S)
        //     Account = "Apay Wallet" (WALLET type). Category = CASHBACK. No last-4 required.
        if (lowerBody.contains("apay wallet") || lowerBody.contains("apay balance") || lowerBody.contains("using apay")) {
            val apayAmtPat = Pattern.compile("(?:inr|rs\\.?|₹)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE)
            val apayAmtMatcher = apayAmtPat.matcher(lowerBody)
            val apayAmount = if (apayAmtMatcher.find()) apayAmtMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0 else 0.0
            if (apayAmount > 0.0) {
                val apayType = if (lowerBody.contains("credited") || lowerBody.contains("added") || lowerBody.contains("refund")) "INCOME" else "EXPENSE"
                val apayTitlePat = Pattern.compile("(?:at|for)\\s+([A-Za-z][A-Za-z0-9.\\-]+)", Pattern.CASE_INSENSITIVE)
                val apayTitleMatcher = apayTitlePat.matcher(cleanBody)
                val apayTitle = if (apayTitleMatcher.find())
                    (apayTitleMatcher.group(1) ?: "Apay").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
                else "Apay Wallet"
                return SmsParsingResult(
                    title = apayTitle, amount = apayAmount, category = ExpenseCategory.CASHBACK,
                    type = apayType, isGeminiParsed = false, accountRef = "APAY_WALLET",
                    parsedTimestamp = extractTimestampFromSms(cleanBody, smsTimestamp)
                )
            }
        }

        // 4b. NeuCoins transactions (Tata Neu — senders: AD-TNUCRD-S, Cp-BIGBKT-S, etc.)
        //     Account = "NeuCoins" (WALLET type). No last-4 required.
        //     Categories: COINS (credited), SHOPPING (spent/used), REFUNDS (refund).
        if (lowerBody.contains("neucoin") ||
            (lowerBody.contains("neucard") && (lowerBody.contains("credited") || lowerBody.contains("refund")))) {
            val neuAmtPat = Pattern.compile("([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:neucoin|neu\\s*coins?)", Pattern.CASE_INSENSITIVE)
            val neuAmtMatcher = neuAmtPat.matcher(cleanBody)
            val neuAmount = if (neuAmtMatcher.find()) neuAmtMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0 else 0.0
            if (neuAmount > 0.0) {
                val neuType = if (lowerBody.contains("debited") || lowerBody.contains("used") || lowerBody.contains("spent")) "EXPENSE" else "INCOME"
                val neuCategory = when {
                    lowerBody.contains("refund") -> ExpenseCategory.REFUNDS
                    neuType == "INCOME"          -> ExpenseCategory.COINS
                    else                         -> ExpenseCategory.SHOPPING
                }
                val neuTitlePat = Pattern.compile("(?:at|for)\\s+([A-Za-z][A-Za-z0-9.\\-]+)(?:\\s+on|\\s+check|\\s*\\.\\s*check|\\s+team|\\s*https)", Pattern.CASE_INSENSITIVE)
                val neuTitleMatcher = neuTitlePat.matcher(cleanBody)
                val neuTitle = when {
                    lowerBody.contains("refund") -> "Refund"
                    neuType == "INCOME"          -> "NeuCoins Credited"
                    neuTitleMatcher.find()       -> (neuTitleMatcher.group(1) ?: "NeuCoins").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
                    else                         -> "NeuCoins"
                }
                return SmsParsingResult(
                    title = neuTitle, amount = neuAmount, category = neuCategory,
                    type = neuType, isGeminiParsed = false, accountRef = "NEUCOINS_WALLET",
                    parsedTimestamp = extractTimestampFromSms(cleanBody, smsTimestamp)
                )
            }
        }

        // ── STEP 5: SOFT VALIDATION (bypassable via bypassExclusionFilter) ────
        // The checks below are skipped when scanning with custom user-defined patterns,
        // allowing manual inclusion of SMS that the automatic filters would reject.

        // 5a. Strict regex utility filter — rejects common non-transactional structures
        if (!bypassExclusionFilter && !SmsFilterUtility.isValidTransactionSms(body)) {
            Log.d(TAG, "Excluded: Failed SmsFilterUtility validation.")
            return null
        }

        // 5b. Promotional / marketing / reminder / future-event exclusions
        val isPromoOrReminder =
            // Reminder and soft due notices (stricter variants already rejected in Step 2b)
            lowerBody.contains("is due") ||
            lowerBody.contains("payment due") ||
            lowerBody.contains("is due by") ||
            lowerBody.contains("reminds you") ||
            lowerBody.contains("reminder:") ||
            lowerBody.contains("pay before") ||
            lowerBody.contains("outstanding") ||
            lowerBody.contains("overdue") ||
            // Scheduled / future-tense events (not yet executed)
            (lowerBody.contains("will be debited") && !lowerBody.contains("autopay") && !lowerBody.contains("auto-pay")) ||
            lowerBody.contains("scheduled transfer") ||
            lowerBody.contains("is scheduled") ||
            lowerBody.contains("upcoming payment") ||
            lowerBody.contains("requires action") ||
            // Promotional / marketing content
            lowerBody.contains("eligible for") ||
            lowerBody.contains("pre-approved") ||
            lowerBody.contains("apply now") ||
            lowerBody.contains("win up to") ||
            lowerBody.contains("congratulations") ||
            lowerBody.contains("earn cashback") ||
            (lowerBody.contains("flat") && lowerBody.contains("off")) ||
            lowerBody.contains("exclusive discount")
        // Exception: allow through if the SMS also confirms a completed payment
        val isConfirmedPayment = lowerBody.contains("received towards") ||
            lowerBody.contains("payment of") ||
            lowerBody.contains("received") ||
            lowerBody.contains("payment received")
        if (isPromoOrReminder && !isConfirmedPayment && !bypassExclusionFilter) {
            Log.d(TAG, "Excluded: Promotional, reminder, or future-event SMS.")
            return null
        }

        // ── STEP 6: TRANSACTION KEYWORD CHECK ────────────────────────────────
        // SMS must contain at least one past-tense / active transactional word.
        val hasTransactionKeywords =
            lowerBody.contains("debited") ||
            lowerBody.contains("credited") ||
            lowerBody.contains("spent") ||
            lowerBody.contains("paid") ||
            lowerBody.contains("received") ||
            lowerBody.contains("withdrawn") ||
            lowerBody.contains("withdrew") ||
            lowerBody.contains("deposited") ||
            lowerBody.contains("transferred") ||
            lowerBody.contains("transfer") ||
            lowerBody.contains("deducted") ||
            lowerBody.contains("charged") ||
            lowerBody.contains("charge") ||
            lowerBody.contains("recharge successful") ||
            lowerBody.contains("salary") ||
            lowerBody.contains("added to your wallet") ||
            lowerBody.contains("txn") ||
            lowerBody.contains("payment") ||
            lowerBody.contains("sent") ||
            lowerBody.contains("refund") ||
            lowerBody.contains("autopay") ||
            lowerBody.contains("auto-pay")
        if (!hasTransactionKeywords) {
            Log.d(TAG, "Excluded: No transactional trigger keywords found.")
            return null
        }

        // ── STEP 7: AMOUNT EXTRACTION ─────────────────────────────────────────
        // Primary: currency-symbol / currency-code prefix (e.g. "Rs.500", "INR 1,200.00")
        val amountPattern = Pattern.compile(
            "(?:rs\\.?|inr|usd|eur|egp|sgd|₹|\\$|debited with|debited\\s+by|debited\\s+of|sent\\s+rs\\.?|paid\\s+rs\\.?|received\\s+rs\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"
        )
        val matcher = amountPattern.matcher(lowerBody)
        var amount = 0.0
        if (matcher.find()) {
            amount = matcher.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
        }
        // Fallback: verb-adjacent amount (e.g. "debited 500", "paid 1,200.00")
        if (amount == 0.0) {
            val nakedAmountPattern = Pattern.compile(
                "(?:debited|spent|paid|received|charged|credited|sent|withdrawn|transfer|transferred|deducted)\\s+(?:of|by|with|to|for)?\\s*(?:rs\\.?\\s*)?([0-9,]+(?:\\.[0-9]{1,2})?)"
            )
            val nakedMatcher = nakedAmountPattern.matcher(lowerBody)
            if (nakedMatcher.find()) {
                amount = nakedMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
            }
        }
        if (amount <= 0.0) {
            Log.d(TAG, "Excluded: No valid monetary amount found.")
            return null
        }

        // ── STEP 8: TRANSACTION TYPE ──────────────────────────────────────────
        var type = "EXPENSE"
        if (lowerBody.contains("credited") ||
            lowerBody.contains("received") ||
            lowerBody.contains("deposited") ||
            lowerBody.contains("salary") ||
            lowerBody.contains("added to your wallet") ||
            lowerBody.contains("refund")) {
            type = "INCOME"
        }

        // ── STEP 9: ACCOUNT REFERENCE (last 3–4 digits) ───────────────────────
        // NOTE: bare "-" is excluded from the prefix list — it falsely matched date
        //       separators (e.g. "22-May-2026" captured "2026" as the account ref).
        val last4Pattern = Pattern.compile(
            "(?i)(?:a/c|acct|acc|account|card|ending in|ending with|ending|ended with|ended|vpa|xx|\\*+|no\\.?)\\s*(?:no\\.?\\s*)?([xX*]*\\d{3,4})\\b"
        )
        val last4Matcher = last4Pattern.matcher(lowerBody)
        var last4Digits: String? = null
        if (last4Matcher.find()) {
            val digitsOnly = (last4Matcher.group(1) ?: "").replace("[^0-9]".toRegex(), "")
            if (digitsOnly.length in 3..4) last4Digits = digitsOnly
        }
        if (last4Digits == null) {
            Log.d(TAG, "Excluded: No 3–4 digit account/card reference found.")
            return null
        }

        var bankName = inferSmsBankCode(senderId, cleanBody)
        if (bankName.isBlank() || bankName.length <= 1) bankName = "Bank"
        val accountRef = "${bankName}-${last4Digits}"

        // ── STEP 10: TIMESTAMP EXTRACTION ────────────────────────────────────
        val parsedTime = extractTimestampFromSms(cleanBody, smsTimestamp)

        // ── STEP 11: SPECIAL TRANSACTION TYPES ───────────────────────────────
        // 11a. Credit card payment acknowledgment — a receipt confirmation from the bank,
        //      not a real new debit. Marked DUPLICATE so the ViewModel skips it.
        val isCcPaymentAck = lowerBody.contains("was credited to your card") ||
            (lowerBody.contains("online payment") && lowerBody.contains("vide ref") && lowerBody.contains("card"))
        if (isCcPaymentAck) {
            return SmsParsingResult(
                title = "CC Payment ACK", amount = amount,
                category = ExpenseCategory.DEBT, type = "DUPLICATE",
                isGeminiParsed = false, accountRef = last4Digits,
                sender = accountRef, receiver = "CC Payment ACK",
                parsedTimestamp = parsedTime
            )
        }

        // 11b. Credit card bill payment received
        //      e.g. "payment of Rs. X received towards your credit card"
        val isCreditCardPayment = lowerBody.contains("credit card") &&
            (lowerBody.contains("received towards") || lowerBody.contains("towards your credit card") ||
             (lowerBody.contains("payment") && lowerBody.contains("received") && lowerBody.contains("card")))
        if (isCreditCardPayment) {
            val availLimitPattern = Pattern.compile(
                "(?:available|avl|avail)\\s*(?:credit\\s*)?(?:limit|bal|balance)\\s+(?:is\\s+)?(?:rs\\.?\\s*|inr\\s*)?([0-9,]+(?:\\.[0-9]{1,2})?)",
                Pattern.CASE_INSENSITIVE
            )
            val availLimitMatcher = availLimitPattern.matcher(cleanBody)
            val parsedAvailableLimit = if (availLimitMatcher.find())
                availLimitMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() else null
            return SmsParsingResult(
                title = "Credit Card Payment", amount = amount,
                category = ExpenseCategory.CC_SETTLEMENT, type = "INCOME",
                isGeminiParsed = false, accountRef = last4Digits,
                sender = accountRef, receiver = "Credit Card Payment",
                parsedTimestamp = parsedTime, availableLimit = parsedAvailableLimit
            )
        }

        // ── STEP 12: PAYEE / TITLE EXTRACTION ────────────────────────────────
        var titleText = ""
        
        // Exact high-priority VPA / UPI ID lookup — allow 2-char handles (e.g. @pz, @ok)
        val upiVpaPattern = Pattern.compile("\\b([a-zA-Z0-9.\\-_]+@[a-zA-Z0-9]{2,})\\b")
        val upiVpaMatcher = upiVpaPattern.matcher(cleanBody)
        if (upiVpaMatcher.find()) {
            titleText = upiVpaMatcher.group(1) ?: ""
        } else {
            // Pre-clean payeeBody to remove common transaction, bank, and card details
            var payeeBody = lowerBody
            
            // 1. Remove "spent on your <bank/card> card ending at <digits>" or variants and "from <bank/card> a/c <digits>" or variants
            payeeBody = payeeBody.replace("(?:spent on|from)\\s+(?:your\\s+)?(?:[a-zA-Z0-9\\s]+)?(?:card|credit card|debit card|acc|account|a/c|bank|acct)\\s*(?:a/c|acc|card)?\\s*(?:ending with|ending in|ending|ended with|ended|at|with)?\\s*[xX*\\s-]*\\d{3,4}".toRegex(), "")
            
            // 3. Remove standalone "a/c <digits> debited/credited"
            payeeBody = payeeBody.replace("a/c\\.?\\s*(?:no\\.?)?\\s*[xX*\\s-]*\\d{3,4}\\s*(?:debited|spent|paid|received|charged|credited|sent|withdrawn|transfer|transferred|deducted|has been|is)?".toRegex(), "")
            
            // 4. Remove common "debited by <amount>" or "credited by <amount>" or "debited for <amount>" or standalone amounts
            payeeBody = payeeBody.replace("(?:debited|credited|spent|paid|received|sent|withdrawn)\\s+(?:by|for|of)?\\s*(?:rs\\.?\\s*)?\\d+(?:\\.\\d+)?".toRegex(), "")
            payeeBody = payeeBody.replace("(?:rs\\.?\\s*)?\\d+(?:\\.\\d+)?".toRegex(), "")
            
            // 5. Remove date references like "on date 19apr26" or "on date 14jun26" or "dated 14/06/26" (using precise character set without spaces to prevent greedily consuming payee name)
            payeeBody = payeeBody.replace("on\\s+date\\s+[a-zA-Z0-9/.-]+".toRegex(), "")
            payeeBody = payeeBody.replace("dated\\s+[a-zA-Z0-9/.-]+".toRegex(), "")

            // Re-ordered keywords from longest/most-specific to shortest/least-specific
            val vendorPattern = Pattern.compile(
                "(?:transferred to|received from|spent on|paid to|transfer to|trf to|towards|merchant|info:|from|for|at|to)\\s+([a-zA-Z0-9\\s\\.\\-\\'&_]+)"
            )
            val vendorMatcher = vendorPattern.matcher(payeeBody)
            if (vendorMatcher.find()) {
                val rawVendor = vendorMatcher.group(1)?.trim() ?: ""
                // Clean up delimiters using word boundaries instead of plain string split
                val cleanParts = rawVendor.split("\\b(on|by|using|with|via|for|against|card|account|txn|ref|refno|ref\\.no|dated|at|transfer|if|call|dial|contact|help|link|click|visit|balance|bal|limit|avl)\\b".toRegex())
                val intermediateTitle = cleanParts.firstOrNull()?.trim() ?: ""
                titleText = intermediateTitle
            }

            // Clean numbers, txn IDs, dates, from titles
            titleText = titleText.replace("\\d+".toRegex(), "").trim()

            // UPI/IMPS/NEFT and mobile fallback parsing if payee is missing or too generic
            if (titleText.isEmpty() || titleText.length <= 2 || titleText.lowercase() == "merchant store" || titleText.lowercase() == "bank transfer") {
                val mobilePattern = Pattern.compile("\\b(?:linked\\s+)?mobile\\s*([0-9xX*]{5,15})\\b", Pattern.CASE_INSENSITIVE)
                val mobileMatcher = mobilePattern.matcher(body)
                val hasMobile = mobileMatcher.find()
                val mobileStr = if (hasMobile) "Mobile " + mobileMatcher.group(1) else ""
                
                val lower = body.lowercase()
                val mode = when {
                    lower.contains("imps") -> "IMPS"
                    lower.contains("neft") -> "NEFT"
                    lower.contains("rtgs") -> "RTGS"
                    lower.contains("upi") -> "UPI"
                    else -> ""
                }
                
                if (mode.isNotEmpty() && mobileStr.isNotEmpty()) {
                    titleText = "$mode - $mobileStr"
                } else if (mode.isNotEmpty()) {
                    titleText = "$mode Transfer"
                } else if (mobileStr.isNotEmpty()) {
                    titleText = mobileStr
                }
            }

            if (titleText.isEmpty() || titleText.length > 45 || titleText.length <= 2) {
                titleText = if (type == "EXPENSE") "Merchant Store" else "${bankName} Transfer"
            } else if (!titleText.contains("Mobile") && !titleText.contains("IMPS") && !titleText.contains("NEFT") && !titleText.contains("RTGS") && !titleText.contains("UPI")) {
                // Apply title case only for non-mode specific formats to keep exact masked number / mode casing
                titleText = titleText.split(" ").filter { it.isNotEmpty() }.joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            }
        }

        val extractedSender = if (type == "EXPENSE") accountRef else titleText
        val extractedReceiver = if (type == "EXPENSE") titleText else accountRef

        return SmsParsingResult(
            title = titleText,
            amount = amount,
            category = when {
                type == "INCOME" -> {
                    val lowerTitleText = titleText.lowercase()
                    when {
                        lowerTitleText.contains("cashback") || lowerBody.contains("cashback") -> ExpenseCategory.CASHBACK
                        lowerBody.contains("upi") || lowerBody.contains("by upi") || lowerBody.contains("via upi") || lowerTitleText.contains("upi") -> ExpenseCategory.UPI
                        lowerTitleText.contains("pocket money") || lowerTitleText.contains("allowance") -> ExpenseCategory.POCKET_MONEY_INC
                        else -> ExpenseCategory.SALARY
                    }
                }
                else -> {
                    val lowerTitleText = titleText.lowercase()
                    when {
                        // Tea & Soft Drinks — merchant name starts with tea/stall OR exact small amounts (Rs.12/15/20)
                        lowerTitleText.contains("tea") || lowerTitleText.contains("stall") ||
                        (amount == 12.0 || amount == 15.0 || amount == 20.0) -> ExpenseCategory.SOFT_HOT_DRINKS

                        lowerTitleText.contains("starbucks") || lowerTitleText.contains("mcdonald") || lowerTitleText.contains("swiggy") ||
                        lowerTitleText.contains("zomato") || lowerTitleText.contains("food") || lowerTitleText.contains("restaurant") ||
                        lowerTitleText.contains("eats") || lowerTitleText.contains("cafe") || lowerTitleText.contains("pizza") ||
                        lowerBody.contains("canteen") || lowerBody.contains("bakery") -> ExpenseCategory.FOOD

                        lowerTitleText.contains("gas") || lowerTitleText.contains("petrol") || lowerTitleText.contains("fuel") ||
                        lowerTitleText.contains("agencies") ||
                        lowerBody.contains("fuel station") || lowerBody.contains("petrol bunk") -> ExpenseCategory.FUEL

                        lowerTitleText.contains("loan") || lowerTitleText.contains("debt") || lowerTitleText.contains("emi") ||
                        lowerTitleText.contains("credila") || lowerBody.contains("repayment") -> ExpenseCategory.DEBT

                        lowerTitleText.contains("nike") || lowerTitleText.contains("adidas") || lowerTitleText.contains("shoes") ||
                        lowerTitleText.contains("footwear") || lowerTitleText.contains("bata") -> ExpenseCategory.SHOES

                        lowerTitleText.contains("clothes") || lowerTitleText.contains("fashion") || lowerTitleText.contains("clothing") ||
                        lowerTitleText.contains("zara") || lowerTitleText.contains("hm") || lowerTitleText.contains("apparel") -> ExpenseCategory.CLOTHES

                        lowerTitleText.contains("amazon") || lowerTitleText.contains("flipkart") || lowerTitleText.contains("walmart") ||
                        lowerTitleText.contains("gmart") ||
                        lowerTitleText.contains("shopping") || lowerTitleText.contains("grocery") || lowerTitleText.contains("target") ||
                        lowerTitleText.contains("store") || lowerBody.contains("supermarket") -> ExpenseCategory.SHOPPING

                        lowerTitleText.contains("uber") || lowerTitleText.contains("lyft") || lowerTitleText.contains("ola") ||
                        lowerTitleText.contains("rapido") ||
                        lowerTitleText.contains("taxi") || lowerTitleText.contains("metro") || lowerBody.contains("transit") -> ExpenseCategory.TRANSPORT

                        lowerTitleText.contains("electric") || lowerTitleText.contains("water") || lowerTitleText.contains("utility") ||
                        lowerTitleText.contains("bill") || lowerTitleText.contains("recharge") || lowerTitleText.contains("netflix") ||
                        lowerTitleText.contains("broadband") || lowerTitleText.contains("club") || lowerTitleText.contains("payments") ||
                        lowerTitleText.contains("airtel") || lowerTitleText.contains("jio") ||
                        lowerBody.contains("insurance") || lowerBody.contains("mobile bill") -> ExpenseCategory.BILLS

                        lowerTitleText.contains("movie") || lowerTitleText.contains("cinema") || lowerTitleText.contains("steam") ||
                        lowerTitleText.contains("spotify") || lowerTitleText.contains("pub") || lowerTitleText.contains("bar") ||
                        lowerTitleText.contains("concert") || lowerTitleText.contains("game") ||
                        lowerTitleText.contains("playstore") || lowerTitleText.contains("play store") || lowerTitleText.contains("mall") ||
                        lowerTitleText.contains("theatre") -> ExpenseCategory.ENTERTAINMENT

                        lowerTitleText.contains("hospital") || lowerTitleText.contains("pharmacy") || lowerTitleText.contains("medical") ||
                        lowerTitleText.contains("doctor") || lowerTitleText.contains("clinic") || lowerBody.contains("medicine") -> ExpenseCategory.HEALTHCARE

                        lowerTitleText.contains("school") || lowerTitleText.contains("college") || lowerTitleText.contains("tuition") ||
                        lowerTitleText.contains("education") || lowerTitleText.contains("book") || lowerTitleText.contains("course") -> ExpenseCategory.EDUCATION

                        lowerTitleText.contains("zerodha") || lowerTitleText.contains("groww") -> ExpenseCategory.INVESTMENT

                        lowerTitleText.contains("iccl") -> ExpenseCategory.MUTUAL_FUND

                        lowerTitleText.contains("techno") -> ExpenseCategory.ELECTRONICS

                        lowerTitleText.contains("redbus") -> ExpenseCategory.TRAVEL

                        lowerTitleText.contains("protein") || lowerTitleText.contains("powder") || lowerTitleText.contains("supplement") ||
                        lowerTitleText.contains("whey") || lowerTitleText.contains("nutrition") -> ExpenseCategory.GYM

                        lowerTitleText.contains("gym") || lowerTitleText.contains("fitness") || lowerTitleText.contains("workout") -> ExpenseCategory.GYM

                        lowerTitleText.contains("fruit") || lowerTitleText.contains("fruit") || lowerTitleText.contains("market") ||
                        lowerTitleText.contains("apple") || lowerTitleText.contains("banana") -> ExpenseCategory.FRUITS

                        lowerTitleText.contains("bike") || lowerTitleText.contains("motorcycle") || lowerTitleText.contains("two wheeler") ||
                        lowerTitleText.contains("royal enfield") || lowerTitleText.contains("RE bike") -> ExpenseCategory.BIKE

                        lowerTitleText.contains("gift") || lowerTitleText.contains("gifting") || lowerTitleText.contains("friend") ||
                        lowerTitleText.contains("shagun") || lowerTitleText.contains("giftcard") -> ExpenseCategory.GIFTING_FRIENDS

                        else -> ExpenseCategory.OTHERS
                    }
                }
            },
            type = type,
            isGeminiParsed = false,
            accountRef = last4Digits, // We return just the 4 digits to associate with Account database
            sender = extractedSender,
            receiver = extractedReceiver,
            parsedTimestamp = parsedTime
        )
    }

    // Advanced Gemini hybrid parser
    suspend fun parseWithGemini(body: String, senderId: String?, apiKey: String, smsTimestamp: Long? = null): SmsParsingResult? = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.d(TAG, "Gemini API skipped: falls back to high-fidelity offline matcher.")
            return@withContext parseOffline(body, senderId, smsTimestamp)
        }

        // Double check local criteria first: do not waste API call if clearly promotional / OTP / bills due
        val localVerify = parseOffline(body, senderId, smsTimestamp)
        if (localVerify == null) {
            Log.d(TAG, "Gemini parsing skipped: message classified offline as promotional, OTP, or non-transactional.")
            return@withContext null
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val prompt = """
            Extract precise transaction parameters of dynamic actual expenses or incomes from this notification body.
            SMS Text: "$body"
            SenderId: "$senderId"
            
            Respond STRICTLY with a valid flat JSON containing ONLY these schema properties:
            {
               "title": "Clean concise Payee / Merchant / Payer Name (Capitalized, max 30 chars, e.g., 'Starbucks', 'Uber', 'Amazon', 'Salary Checking'). For UPI ids, extract name like 'Jeeva (UPI)' instead of 'jeeva@paytm'.",
               "amount": 12.34, // Positive Double of transaction amount only
               "category": "One of: FOOD, SHOPPING, TRANSPORT, BILLS, ENTERTAINMENT, HEALTHCARE, EDUCATION, SALARY, CASHBACK, UPI, REFUNDS, RENTAL, SALE, REWARDS, COUPONS, GRANTS, COINS, POCKET_MONEY_INC, OTHERS",
               "type": "EXPENSE" or "INCOME",
               "accountRef": "Last 4 digits only of card/wallet identifier e.g. '1234' or '8765'. Set null if not found.",
               "sender": "Source account code, card label, or merchant sending funds",
               "receiver": "Destination payload"
            }
            Do not include any speech markdown, headers, or explanations. Just return raw JSON.
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini API failure: code ${response.code}, message ${response.message}")
                    return@withContext localVerify
                }

                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Gemini Response payload: $responseBody")

                val responseObj = JSONObject(responseBody)
                val candidates = responseObj.getJSONArray("candidates")
                val parts = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                val textResponse = parts.getJSONObject(0).getString("text").trim()

                val parsedJson = JSONObject(textResponse)
                val title = parsedJson.optString("title", localVerify.title)
                val amountVal = parsedJson.optDouble("amount", localVerify.amount)
                val categoryStr = parsedJson.optString("category", "OTHERS")
                val typeVal = parsedJson.optString("type", localVerify.type).uppercase()
                val accountRefVal = if (parsedJson.isNull("accountRef")) localVerify.accountRef else parsedJson.getString("accountRef")
                val senderVal = if (parsedJson.isNull("sender")) localVerify.sender else parsedJson.getString("sender")
                val receiverVal = if (parsedJson.isNull("receiver")) localVerify.receiver else parsedJson.getString("receiver")

                val resolvedCategory = ExpenseCategory.entries.firstOrNull { it.name == categoryStr.uppercase() } ?: ExpenseCategory.OTHERS

                if (amountVal > 0.0) {
                    return@withContext SmsParsingResult(
                        title = title,
                        amount = amountVal,
                        category = resolvedCategory,
                        type = typeVal,
                        isGeminiParsed = true,
                        accountRef = accountRefVal,
                        sender = senderVal,
                        receiver = receiverVal,
                        parsedTimestamp = localVerify?.parsedTimestamp
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching via Gemini flash: ${e.message}", e)
        }

        return@withContext localVerify
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
            "\\bref\\.([a-z0-9]+)\\b"
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
        val isBalanceSms = (lowerBody.contains("avail bal") || lowerBody.contains("avail. bal") ||
            lowerBody.contains("available balance") ||
            (lowerBody.contains("balance") && (lowerBody.contains("a/c") || lowerBody.contains("account no") ||
            lowerBody.contains("account number") || lowerBody.contains("ending with"))))
        if (!isBalanceSms) return null

        val allPairs = mutableListOf<Pair<String, Double>>()

        // Multi-account format: "XXXXXX2045 INR 2204.092Cr, XXXXXXX8660 INR 100Cr"
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

        // Standard single-account format: "a/c xxx300: Rs.3393.08" / "ending with 9553 is Rs. 0.12"
        if (allPairs.isEmpty()) {
            val amtPattern = Pattern.compile(
                "(?:rs\\.?|inr|₹)\\s*([0-9,]+(?:\\.[0-9]{1,3})?)",
                Pattern.CASE_INSENSITIVE
            )
            val amtMatcher = amtPattern.matcher(cleanBody)
            val balance = if (amtMatcher.find()) amtMatcher.group(1)?.replace(",", "")?.toDoubleOrNull() else null
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

        return SmsParsingResult(
            title = "Balance Update",
            amount = firstBal,
            category = ExpenseCategory.ADJUST,
            type = "INCOME",
            isGeminiParsed = false,
            accountRef = firstRef,
            parsedTimestamp = parsedTime,
            isBalanceUpdate = true,
            availableBalance = firstBal,
            allBalancePairs = allPairs
        )
    }
}
