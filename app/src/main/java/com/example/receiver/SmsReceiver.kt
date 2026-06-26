package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import android.widget.Toast
import com.example.data.Account
import com.example.data.FinanceDatabase
import com.example.data.TransactionEntry
import com.example.utils.SmsParser
import com.example.utils.inferSmsBankCode
import com.example.utils.isDuplicateImportedTransaction
import com.example.utils.smsBankMatchesAccount
import com.example.utils.smsDisplayBankName
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

            // Reassemble multi-part SMS: Android delivers all parts in one broadcast
            val smsMessages = pdus.mapNotNull { pdu ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }
            }
            if (smsMessages.isEmpty()) return
            val body = smsMessages.joinToString("") { it.messageBody ?: "" }
            val sender = smsMessages[0].originatingAddress ?: "SMS Alert"
            val timestampMillis = smsMessages[0].timestampMillis

                // Launch background thread to insert transaction securely
                CoroutineScope(Dispatchers.IO).launch {
                    val parsed = SmsParser.parseOffline(body, sender, timestampMillis)
                    if (parsed != null) {
                        val db = FinanceDatabase.getDatabase(context.applicationContext)
                        val dao = db.financeDao()

                        val targetTime = parsed.parsedTimestamp ?: timestampMillis

                        // --- Handle balance-snapshot SMS (available balance notification) ---
                        val balanceSyncEnabled = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
                            .getBoolean("enable_balance_sync", true)
                        if (parsed.isBalanceUpdate) {
                            val isCcSummary = parsed.totalCreditLimit != null

                            if (isCcSummary) {
                                // CC Summary: update creditLimit + availableLimit on the account.
                                // No Balance Sync transaction — outstanding is derived from limits fields.
                                if (parsed.accountRef != null) {
                                    val hyphen = parsed.accountRef.indexOf('-')
                                    val refDigits = if (hyphen != -1) parsed.accountRef.substring(hyphen + 1) else parsed.accountRef
                                    var limitAcc = dao.getAccountByLastFour(refDigits)
                                        ?: if (refDigits.length == 3) dao.getAccountByLastFourSuffix(refDigits) else null
                                    if (limitAcc == null) {
                                        ensureAccountExists(context, dao, parsed.accountRef, sender, body)
                                        limitAcc = dao.getAccountByLastFour(refDigits)
                                            ?: if (refDigits.length == 3) dao.getAccountByLastFourSuffix(refDigits) else null
                                    }
                                    if (limitAcc != null) {
                                        parsed.availableLimit?.let { dao.updateAccountAvailableLimit(limitAcc.id, it) }
                                        parsed.totalCreditLimit?.let { dao.updateAccountCreditLimit(limitAcc.id, it) }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "MyMoney: CC limits updated for ${limitAcc.name}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else if (balanceSyncEnabled && parsed.availableBalance != null) {
                                // Regular bank balance SMS: create Balance Sync when Bal Sync is ON
                                val pairs: List<Pair<String, Double>> = if (parsed.allBalancePairs.isNotEmpty()) {
                                    parsed.allBalancePairs
                                } else if (parsed.accountRef != null) {
                                    val hyphen = parsed.accountRef.indexOf('-')
                                    val digits = if (hyphen != -1) parsed.accountRef.substring(hyphen + 1) else parsed.accountRef
                                    listOf(digits to parsed.availableBalance)
                                } else emptyList()

                                val allTx = dao.getAllTransactions().first()
                                for ((refDigits, bal) in pairs) {
                                    val linkedAcc = dao.getAccountByLastFour(refDigits)
                                        ?: if (refDigits.length == 3) dao.getAccountByLastFourSuffix(refDigits) else null
                                    if (linkedAcc != null) {
                                        val isDup = allTx.any {
                                            it.type == "BALANCE_UPDATE" &&
                                            it.timestamp == targetTime &&
                                            it.note?.contains("[Acc: ${linkedAcc.name}]") == true
                                        }
                                        if (!isDup) {
                                            val snapTx = TransactionEntry(
                                                title = "Balance Sync",
                                                amount = bal,
                                                category = "ADJUST",
                                                type = "BALANCE_UPDATE",
                                                smsSender = sender,
                                                smsBody = body,
                                                timestamp = targetTime,
                                                note = "$body [Acc: ${linkedAcc.name}]"
                                            )
                                            dao.insertTransaction(snapTx)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "MyMoney: Balance updated to \u20b9${String.format("%.2f", bal)} for ${linkedAcc.name}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }

                            return@launch  // Don't also create a regular transaction
                        }

                        // --- Regular expense / income transaction ---
                        val walletName = ensureAccountExists(context, dao, parsed.accountRef, sender, body) ?: return@launch
                        val potentialDuplicates = dao.getPotentialDuplicates(parsed.amount, parsed.type, targetTime)
                        val incomingRef = com.example.utils.SmsParser.getReferenceNumber(body)

                        val allTransactions = dao.getAllTransactions().first()
                        val isDuplicate = allTransactions.any { existing ->
                            isDuplicateImportedTransaction(
                                existing = existing,
                                incomingSmsBody = body,
                                incomingTitle = parsed.title,
                                incomingAmount = parsed.amount,
                                incomingType = parsed.type,
                                incomingTimestamp = targetTime,
                                incomingReference = incomingRef,
                                incomingAccountName = walletName
                            )
                        } || potentialDuplicates.any { existing ->
                            isDuplicateImportedTransaction(
                                existing = existing,
                                incomingSmsBody = body,
                                incomingTitle = parsed.title,
                                incomingAmount = parsed.amount,
                                incomingType = parsed.type,
                                incomingTimestamp = targetTime,
                                incomingReference = incomingRef,
                                incomingAccountName = walletName
                            )
                        }
                        if (!isDuplicate) {
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
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error receiving incoming SMS: ${e.message}", e)
        }
    }

    private suspend fun ensureAccountExists(context: Context, dao: com.example.data.FinanceDao, accountRef: String?, senderHeader: String?, smsBody: String): String? {
        val last4Ref = accountRef ?: return "Cash Wallet"
        
        val hyphenIndex = last4Ref.indexOf('-')
        val actualLast4 = if (hyphenIndex != -1) last4Ref.substring(hyphenIndex + 1) else last4Ref
        var extractedBank = if (hyphenIndex != -1) last4Ref.substring(0, hyphenIndex) else ""
        
        if (extractedBank.isBlank()) {
            extractedBank = inferSmsBankCode(senderHeader, smsBody, accountRef)
        }
        if (extractedBank.isBlank() || extractedBank.length <= 1) {
            extractedBank = "Bank"
        }

        val list = dao.getAllAccounts().first()
        val candidates = list.filter {
            (it.lastFour == actualLast4) || (actualLast4.isNotEmpty() && it.name.contains(actualLast4))
        }
        val match = candidates.firstOrNull { smsBankMatchesAccount(extractedBank, it.name) }
            ?: candidates.singleOrNull()
        if (match != null) {
            val blockedIds = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
                .getStringSet("blocked_sms_account_ids", emptySet())
                ?.toSet()
                ?: emptySet()
            if (blockedIds.contains(match.id)) {
                return null
            }
            return match.name
        }

        val bodyLower = smsBody.lowercase()
        // Guard: never create a new account from a due-notice / reminder SMS
        val isDueOrReminder = bodyLower.contains("is due") || bodyLower.contains("due on") ||
            bodyLower.contains("due by") || bodyLower.contains("emi due") ||
            (bodyLower.contains("emi") && !bodyLower.contains("credited") && !bodyLower.contains("debited")) ||
            (bodyLower.contains("due") && (bodyLower.contains("bill") || bodyLower.contains("loan")))
        if (isDueOrReminder) return null
        val isCreditCard = bodyLower.contains("card") || 
                           bodyLower.contains("credit card") || 
                           bodyLower.contains("card limit") || 
                           bodyLower.contains("spent on") || 
                           (senderHeader ?: "").uppercase().contains("CARD")
        val acType = if (isCreditCard) "CREDIT_CARD" else "BANK"
        val displayName = smsDisplayBankName(extractedBank)
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

        val startBal = 0.0
        // Guard: do NOT create account if name or sender is in the SMS Import Blocklist
        val blocklistPatterns = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
            .getStringSet("sms_blocklist_patterns", emptySet()) ?: emptySet()
        val isNameBlocklisted = blocklistPatterns.any { pat ->
            val p = pat.lowercase().trim()
            listOf(nameLabel.lowercase(), (senderHeader ?: "").lowercase()).any { ref ->
                when {
                    p.startsWith("*") && p.endsWith("*") && p.length > 2 -> ref.contains(p.substring(1, p.length - 1))
                    p.startsWith("*") -> ref.endsWith(p.substring(1))
                    p.endsWith("*") -> ref.startsWith(p.dropLast(1))
                    else -> ref.contains(p)
                }
            }
        }
        if (isNameBlocklisted) return null
        val newAcObj = Account(name = nameLabel, balance = startBal, type = acType, lastFour = actualLast4)
        dao.insertAccount(newAcObj)
        return nameLabel
    }
}
