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
    val parsedTimestamp: Long? = null
)

object SmsParser {
    private const val TAG = "SmsParser"

    // Strict offline parser based on high-precision regex signatures
    fun parseOffline(body: String, senderId: String?, smsTimestamp: Long? = null, bypassExclusionFilter: Boolean = false): SmsParsingResult? {
        val cleanBody = body.replace("\\s+".toRegex(), " ").trim()
        val lowerBody = cleanBody.lowercase()

        Log.d(TAG, "Parsing text: '$cleanBody' from sender: '$senderId'")

        // 0. Use the dedicated strict regex filtering utility (bypassed for manual paste with custom patterns)
        if (!bypassExclusionFilter && !SmsFilterUtility.isValidTransactionSms(body)) {
            Log.d(TAG, "Excluded: Failed validation check in SmsFilterUtility.")
            return null
        }

        // 1. STRICT NON-TRANSACTIONAL EXCLUSIONS (OTP, Reminders, Promotionals, Bills Due, Requests)
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
            Log.d(TAG, "Excluded: Message identified as OTP / Verification code.")
            return null
        }

        val isNonTransactionalDoc = (
            (lowerBody.contains("is due") || 
             lowerBody.contains("payment due") || 
             lowerBody.contains("due on") || 
             lowerBody.contains("is due by") || 
             lowerBody.contains("reminds you") || 
             lowerBody.contains("reminder:") || 
             lowerBody.contains("pay before") || 
             lowerBody.contains("outstanding") || 
             lowerBody.contains("overdue") || 
             (lowerBody.contains("will be debited") && !lowerBody.contains("autopay") && !lowerBody.contains("auto-pay")) || 
             lowerBody.contains("scheduled transfer") || 
             lowerBody.contains("eligible for") || 
             lowerBody.contains("pre-approved") || 
             lowerBody.contains("apply now") || 
             lowerBody.contains("win up to") || 
             lowerBody.contains("congratulations") || 
             lowerBody.contains("earn cashback") || 
             (lowerBody.contains("flat") && lowerBody.contains("off")) || 
             lowerBody.contains("exclusive discount") || 
             lowerBody.contains("requesting money") || 
             lowerBody.contains("requested money") || 
             lowerBody.contains("request to pay") || 
             lowerBody.contains("pay using link") ||
             lowerBody.contains("collect request") ||
             lowerBody.contains("requires action") ||
             lowerBody.contains("is scheduled") ||
             lowerBody.contains("upcoming payment") ||
             lowerBody.contains("has requested") ||
             lowerBody.contains("requested to pay") ||
             lowerBody.contains("total amt due") ||
             lowerBody.contains("total amount due") ||
             lowerBody.contains("minimum amt due") ||
             lowerBody.contains("minimum amount due") ||
             lowerBody.contains("min amt due") ||
             lowerBody.contains("statement due") ||
             lowerBody.contains("stmt due") ||
             lowerBody.contains("due amount") ||
             lowerBody.contains("amount due") ||
             lowerBody.contains("due :") ||
             lowerBody.contains("due:"))
            // Make exceptions for explicit payment confirmations or received updates
            && !lowerBody.contains("received towards")
            && !lowerBody.contains("payment of")
            && !lowerBody.contains("received")
            && !lowerBody.contains("payment received")
        )

        if (isNonTransactionalDoc && !bypassExclusionFilter) {
            Log.d(TAG, "Excluded: Message identified as promo, reminder, due notice or pending payment request.")
            return null
        }

        // 2. TRANSACTION SIGNIFICANCE CHECK (MUST contain dynamic transactional active words)
        val hasTransactionKeywords = lowerBody.contains("debited") || 
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
            Log.d(TAG, "Excluded: Lack of past-tense transactional triggers.")
            return null
        }

        // 3. EXTRACT TRANSACTION AMOUNT
        val amountPattern = Pattern.compile(
            "(?:rs\\.?|inr|usd|eur|egp|sgd|₹|\\$|debited with|debited\\s+by|debited\\s+of|sent\\s+rs\\.?|paid\\s+rs\\.?|received\\s+rs\\.?)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)"
        )
        val matcher = amountPattern.matcher(lowerBody)
        var amount = 0.0
        if (matcher.find()) {
            val amountStr = matcher.group(1)?.replace(",", "")
            amount = amountStr?.toDoubleOrNull() ?: 0.0
        }

        // Fallback for clean decimal parsing after transaction action trigger
        if (amount == 0.0) {
            val nakedAmountPattern = Pattern.compile(
                "(?:debited|spent|paid|received|charged|credited|sent|withdrawn|transfer|transferred|deducted)\\s+(?:of|by|with|to|for)?\\s*(?:rs\\.?\\s*)?([0-9,]+(?:\\.[0-9]{1,2})?)"
            )
            val nakedMatcher = nakedAmountPattern.matcher(lowerBody)
            if (nakedMatcher.find()) {
                val amountStr = nakedMatcher.group(1)?.replace(",", "")
                amount = amountStr?.toDoubleOrNull() ?: 0.0
            }
        }

        if (amount <= 0.0) {
            Log.d(TAG, "Excluded: No dynamic valid monetary value parsed.")
            return null
        }

        // 4. IDENTIFY TRANSACTION TYPE (INCOME vs EXPENSE)
        var type = "EXPENSE"
        if (lowerBody.contains("credited") || 
            lowerBody.contains("received") || 
            lowerBody.contains("deposited") || 
            lowerBody.contains("salary") || 
            lowerBody.contains("added to your wallet") ||
            lowerBody.contains("refund")) {
            type = "INCOME"
        }

        // 5. EXTRACT ACCOUNT DETAILS (Card/Account references - ending in 3 or 4 numbers)
        val last4Pattern = Pattern.compile(
            "(?i)(?:a/c|acct|acc|account|card|ending in|ending with|ending|ended with|ended|vpa|xx|\\*+|-|no\\.?)\\s*(?:no\\.?\\s*)?([xX*]*\\d{3,4})\\b"
        )
        val last4Matcher = last4Pattern.matcher(lowerBody)
        var last4Digits: String? = null
        if (last4Matcher.find()) {
            val rawEnding = last4Matcher.group(1) ?: ""
            val digitsOnly = rawEnding.replace("[^0-9]".toRegex(), "")
            if (digitsOnly.length in 3..4) {
                last4Digits = digitsOnly
            }
        }

        // We STRICTLY require an identified card/account number. Otherwise, do not parse/add this transaction.
        if (last4Digits == null) {
            Log.d(TAG, "Excluded: No valid 3 or 4 digit account/card reference identified in SMS body.")
            return null
        }

        // Also identify bank source header properties (e.g. SBI, IND, HDFC)
        var bankName = inferSmsBankCode(senderId, cleanBody)
        if (bankName.isBlank() || bankName.length <= 1) {
            bankName = "Bank"
        }

        val accountRef = "${bankName}-${last4Digits}"

        // 5b. Extract exact Date and Time parameters from body
        val parsedTime = extractTimestampFromSms(cleanBody, smsTimestamp)

        // 6. EXTRACT PAYEE / MERCHANT / SENDER / RECEIVER DETAILS
        var titleText = ""
        
        // Exact high-priority VPA / UPI ID lookup
        val upiVpaPattern = Pattern.compile("\\b([a-zA-Z0-9.\\-_]+@[a-zA-Z0-9]{3,})\\b")
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
                        lowerTitleText.contains("upi") || lowerBody.contains("upi") -> ExpenseCategory.UPI
                        lowerTitleText.contains("pocket money") || lowerTitleText.contains("allowance") -> ExpenseCategory.POCKET_MONEY_INC
                        else -> ExpenseCategory.SALARY
                    }
                }
                else -> {
                    val lowerTitleText = titleText.lowercase()
                    when {
                        lowerTitleText.contains("starbucks") || lowerTitleText.contains("mcdonald") || lowerTitleText.contains("swiggy") ||
                        lowerTitleText.contains("zomato") || lowerTitleText.contains("food") || lowerTitleText.contains("restaurant") ||
                        lowerTitleText.contains("eats") || lowerTitleText.contains("cafe") || lowerTitleText.contains("pizza") ||
                        lowerBody.contains("canteen") || lowerBody.contains("bakery") -> ExpenseCategory.FOOD

                        lowerTitleText.contains("gas") || lowerTitleText.contains("petrol") || lowerTitleText.contains("fuel") ||
                        lowerBody.contains("fuel station") || lowerBody.contains("petrol bunk") -> ExpenseCategory.FUEL

                        lowerTitleText.contains("loan") || lowerTitleText.contains("debt") || lowerTitleText.contains("emi") ||
                        lowerTitleText.contains("credila") || lowerBody.contains("repayment") -> ExpenseCategory.DEBT

                        lowerTitleText.contains("nike") || lowerTitleText.contains("adidas") || lowerTitleText.contains("shoes") ||
                        lowerTitleText.contains("footwear") || lowerTitleText.contains("bata") -> ExpenseCategory.SHOES

                        lowerTitleText.contains("clothes") || lowerTitleText.contains("fashion") || lowerTitleText.contains("clothing") ||
                        lowerTitleText.contains("zara") || lowerTitleText.contains("hm") || lowerTitleText.contains("apparel") -> ExpenseCategory.CLOTHES

                        lowerTitleText.contains("amazon") || lowerTitleText.contains("flipkart") || lowerTitleText.contains("walmart") ||
                        lowerTitleText.contains("shopping") || lowerTitleText.contains("grocery") || lowerTitleText.contains("target") ||
                        lowerTitleText.contains("store") || lowerBody.contains("supermarket") -> ExpenseCategory.SHOPPING

                        lowerTitleText.contains("uber") || lowerTitleText.contains("lyft") || lowerTitleText.contains("ola") ||
                        lowerTitleText.contains("taxi") || lowerTitleText.contains("metro") || lowerBody.contains("transit") -> ExpenseCategory.TRANSPORT

                        lowerTitleText.contains("electric") || lowerTitleText.contains("water") || lowerTitleText.contains("utility") ||
                        lowerTitleText.contains("bill") || lowerTitleText.contains("recharge") || lowerTitleText.contains("netflix") ||
                        lowerTitleText.contains("broadband") || lowerBody.contains("insurance") || lowerBody.contains("mobile bill") -> ExpenseCategory.BILLS

                        lowerTitleText.contains("movie") || lowerTitleText.contains("cinema") || lowerTitleText.contains("steam") ||
                        lowerTitleText.contains("spotify") || lowerTitleText.contains("pub") || lowerTitleText.contains("bar") ||
                        lowerTitleText.contains("concert") || lowerTitleText.contains("game") -> ExpenseCategory.ENTERTAINMENT

                        lowerTitleText.contains("hospital") || lowerTitleText.contains("pharmacy") || lowerTitleText.contains("medical") ||
                        lowerTitleText.contains("doctor") || lowerTitleText.contains("clinic") || lowerBody.contains("medicine") -> ExpenseCategory.HEALTHCARE

                        lowerTitleText.contains("school") || lowerTitleText.contains("college") || lowerTitleText.contains("tuition") ||
                        lowerTitleText.contains("education") || lowerTitleText.contains("book") || lowerTitleText.contains("course") -> ExpenseCategory.EDUCATION

                        lowerTitleText.contains("protein") || lowerTitleText.contains("powder") || lowerTitleText.contains("supplement") ||
                        lowerTitleText.contains("whey") || lowerTitleText.contains("nutrition") -> ExpenseCategory.GYM

                        lowerTitleText.contains("gym") || lowerTitleText.contains("fitness") || lowerTitleText.contains("workout") -> ExpenseCategory.GYM

                        lowerTitleText.contains("fruits") || lowerTitleText.contains("fruit") || lowerTitleText.contains("mango") ||
                        lowerTitleText.contains("apple") || lowerTitleText.contains("banana") -> ExpenseCategory.FRUITS

                        lowerTitleText.contains("bike") || lowerTitleText.contains("motorcycle") || lowerTitleText.contains("two wheeler") ||
                        lowerTitleText.contains("royal enfield") || lowerTitleText.contains("honda bike") -> ExpenseCategory.BIKE

                        lowerTitleText.contains("gift") || lowerTitleText.contains("gifting") || lowerTitleText.contains("friend") ||
                        lowerTitleText.contains("shagun") || lowerTitleText.contains("giftcard") -> ExpenseCategory.GIFTING_FRIENDS

                        lowerTitleText.contains("pocket money") || lowerTitleText.contains("allowance") -> ExpenseCategory.POCKET_MONEY

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
}
