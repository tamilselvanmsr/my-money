package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast
import com.example.data.FinanceDatabase
import com.example.data.TransactionEntry
import com.example.data.Account
import com.example.utils.SmsParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val bundle = intent.extras ?: return
        try {
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")

            for (pdu in pdus) {
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                } ?: continue

                val body = sms.messageBody ?: ""
                val sender = sms.originatingAddress ?: "SMS Alert"

                // Launch background thread to insert transaction securely
                CoroutineScope(Dispatchers.IO).launch {
                    val parsed = SmsParser.parseOffline(body, sender, sms.timestampMillis)
                    if (parsed != null) {
                        val db = FinanceDatabase.getDatabase(context.applicationContext)
                        val dao = db.financeDao()

                        val targetTime = parsed.parsedTimestamp ?: sms.timestampMillis

                        // Double check to prevent duplicates (both identical body and identical temporal properties)
                        val existsByBody = dao.countTransactionsWithSmsBody(body)
                        val potentialDuplicates = dao.getPotentialDuplicates(parsed.amount, parsed.type, targetTime)
                        val incomingRef = com.example.utils.SmsParser.getReferenceNumber(body)
                        
                        val isDuplicate = existsByBody > 0 || potentialDuplicates.any { existing ->
                            if (existing.smsBody == body) {
                                true
                            } else {
                                val existingRef = com.example.utils.SmsParser.getReferenceNumber(existing.smsBody)
                                if (incomingRef != null && existingRef != null) {
                                    incomingRef == existingRef
                                } else {
                                    // No reference numbers to differentiate, so treat as duplicate
                                    true
                                }
                            }
                        }
                        if (!isDuplicate) {
                            val walletName = ensureAccountExists(dao, parsed.accountRef, sender, body)
                            val transaction = TransactionEntry(
                                title = parsed.title,
                                amount = parsed.amount,
                                category = parsed.category.name,
                                type = parsed.type,
                                smsSender = sender,
                                smsBody = body,
                                timestamp = targetTime,
                                note = "$body [Acc: $walletName]"
                            )
                            dao.insertTransaction(transaction)

                            withContext(Dispatchers.Main) {
                                val direction = if (parsed.type == "INCOME") "Added Income" else "Debited Expense"
                                Toast.makeText(
                                    context,
                                    "MyMoney Auto-Tracked: $direction of ₹${parsed.amount} at ${parsed.title}!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Log.d("SmsReceiver", "Incoming transaction from this SMS body already exists.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error receiving incoming SMS: ${e.message}", e)
        }
    }

    private suspend fun ensureAccountExists(dao: com.example.data.FinanceDao, accountRef: String?, senderHeader: String?, smsBody: String): String {
        val last4Ref = accountRef ?: return "Cash Wallet"
        
        val hyphenIndex = last4Ref.indexOf('-')
        val actualLast4 = if (hyphenIndex != -1) last4Ref.substring(hyphenIndex + 1) else last4Ref
        var extractedBank = if (hyphenIndex != -1) last4Ref.substring(0, hyphenIndex) else ""
        
        if (extractedBank.isBlank()) {
            val headerUpper = (senderHeader ?: "Bank").uppercase()
            val bodyLower = smsBody.lowercase()
            extractedBank = when {
                headerUpper.contains("SBI") || bodyLower.contains("sbi") || bodyLower.contains("state bank") -> "SBI"
                headerUpper.contains("HDFC") || bodyLower.contains("hdfc") -> "HDFC"
                headerUpper.contains("ICICI") || bodyLower.contains("icici") -> "ICICI"
                headerUpper.contains("AXIS") || bodyLower.contains("axis") -> "AXIS"
                headerUpper.contains("IND") || bodyLower.contains("indusind") || bodyLower.contains("indian bank") -> "IND"
                headerUpper.contains("PNB") || bodyLower.contains("pnb") || bodyLower.contains("punjab national") -> "PNB"
                else -> {
                    val cleanHeader = headerUpper.replace("[^A-Z]".toRegex(), "")
                    if (cleanHeader.isNotEmpty()) cleanHeader.take(4) else "Bank"
                }
            }
        }
        if (extractedBank.isBlank() || extractedBank.length <= 1) {
            extractedBank = "Bank"
        }

        val list = dao.getAllAccounts().first()
        val match = list.find { 
            val lastFourMatches = (it.lastFour == actualLast4) || (actualLast4.isNotEmpty() && it.name.contains(actualLast4))
            if (!lastFourMatches) return@find false
            
            val accNameUpper = it.name.uppercase()
            val extBankUpper = extractedBank.uppercase()
            when (extBankUpper) {
                "SBI" -> accNameUpper.contains("SBI") || accNameUpper.contains("STATE BANK")
                "IND" -> accNameUpper.split("\\s+".toRegex()).any { it.startsWith("IND") }
                "HDFC" -> accNameUpper.contains("HDFC")
                "ICICI" -> accNameUpper.contains("ICICI")
                "AXIS" -> accNameUpper.contains("AXIS")
                "PNB" -> accNameUpper.contains("PNB") || accNameUpper.contains("PUNJAB")
                else -> accNameUpper.contains(extBankUpper)
            }
        }
        if (match != null) {
            return match.name
        }

        // Direct database lookup fallback
        val directMatch = dao.getAccountByLastFour(actualLast4)
        if (directMatch != null) {
            val accNameUpper = directMatch.name.uppercase()
            val extBankUpper = extractedBank.uppercase()
            val bankMatches = when (extBankUpper) {
                "SBI" -> accNameUpper.contains("SBI") || accNameUpper.contains("STATE BANK")
                "IND" -> accNameUpper.split("\\s+".toRegex()).any { it.startsWith("IND") }
                "HDFC" -> accNameUpper.contains("HDFC")
                "ICICI" -> accNameUpper.contains("ICICI")
                "AXIS" -> accNameUpper.contains("AXIS")
                "PNB" -> accNameUpper.contains("PNB") || accNameUpper.contains("PUNJAB")
                else -> accNameUpper.contains(extBankUpper)
            }
            if (bankMatches) {
                return directMatch.name
            }
        }

        val bodyLower = smsBody.lowercase()
        val isCreditCard = bodyLower.contains("card") || 
                           bodyLower.contains("credit card") || 
                           bodyLower.contains("card limit") || 
                           bodyLower.contains("spent on") || 
                           (senderHeader ?: "").uppercase().contains("CARD")
        val acType = if (isCreditCard) "CREDIT_CARD" else "BANK"
        val displayName = when (extractedBank.uppercase()) {
            "IND" -> "Indian Bank"
            "SBI" -> "SBI"
            "HDFC" -> "HDFC"
            "ICICI" -> "ICICI"
            "AXIS" -> "AXIS"
            "PNB" -> "PNB"
            else -> extractedBank
        }
        val nameLabel = if (isCreditCard) {
            if (displayName == "SBI" || displayName == "HDFC" || displayName == "ICICI" || displayName == "AXIS" || displayName == "PNB") {
                "$displayName Credit Card Ending $actualLast4"
            } else {
                "$displayName Card Ending $actualLast4"
            }
        } else {
            if (displayName.endsWith("Bank", ignoreCase = true)) {
                "$displayName Ending $actualLast4"
            } else {
                "$displayName Bank Ending $actualLast4"
            }
        }

        val startBal = if (isCreditCard) 0.0 else 10000.0
        val newAcObj = Account(name = nameLabel, balance = startBal, type = acType, lastFour = actualLast4)
        dao.insertAccount(newAcObj)
        return nameLabel
    }
}
