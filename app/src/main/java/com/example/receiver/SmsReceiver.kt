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
import com.example.utils.inferSmsCardKind
import com.example.utils.accountTypeAndLabelFor
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
            @Suppress("DEPRECATION")
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
                                        // Create a negative-outstanding BALANCE_UPDATE snapshot (same as bank Balance Sync)
                                        val availLimit = parsed.availableLimit ?: limitAcc.availableLimit
                                        val creditLimit = parsed.totalCreditLimit ?: limitAcc.creditLimit
                                        if (creditLimit > 0) {
                                            val snapshot = availLimit - creditLimit  // negative = outstanding debt
                                            // Delete any stale CC snapshot first — new statement supersedes all previous
                                            dao.deleteAllBalanceSyncForAccount(limitAcc.name)
                                            // Use actual SMS received time — NOT the parsed statement date from the body
                                            val snapTx = TransactionEntry(
                                                title = "Balance Sync",
                                                amount = snapshot,
                                                category = "ADJUST",
                                                type = "BALANCE_UPDATE",
                                                smsSender = sender,
                                                smsBody = body,
                                                timestamp = timestampMillis,
                                                note = "[Acc: ${limitAcc.name}]"
                                            )
                                            dao.insertTransaction(snapTx)
                                        }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "AutoLedger: CC limits updated for ${limitAcc.name}", Toast.LENGTH_SHORT).show()
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
                                        // CC accounts report available credit; convert to snapshot format (avail - limit)
                                        // so that the display formula (creditLimit + bal) gives the correct available credit.
                                        val snapshotBal = if (linkedAcc.type == "CREDIT_CARD" && linkedAcc.creditLimit > 0)
                                            bal - linkedAcc.creditLimit else bal
                                        val isDup = allTx.any {
                                            it.type == "BALANCE_UPDATE" &&
                                            it.timestamp == targetTime &&
                                            it.note?.contains("[Acc: ${linkedAcc.name}]") == true
                                        }
                                        if (!isDup) {
                                            val snapTx = TransactionEntry(
                                                title = "Balance Sync",
                                                amount = snapshotBal,
                                                category = "ADJUST",
                                                type = "BALANCE_UPDATE",
                                                smsSender = sender,
                                                smsBody = body,
                                                timestamp = targetTime,
                                                note = "[Acc: ${linkedAcc.name}]"
                                            )
                                            dao.insertTransaction(snapTx)
                                            withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "AutoLedger: Balance ₹${String.format("%.2f", bal)} → ${linkedAcc.name}", Toast.LENGTH_LONG).show()
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
                                note = "[Acc: $walletName]"
                            )

                            // Transfer pair detection: if a recent SMS with the same UPI ref no
                            // already exists with the opposite type, merge both into one TRANSFER.
                            val transferCounterpart = if (incomingRef != null && parsed.type in listOf("EXPENSE", "INCOME")) {
                                allTransactions.firstOrNull { existing ->
                                    val existRef = com.example.utils.SmsParser.getReferenceNumber(existing.smsBody)
                                    existRef != null && existRef == incomingRef &&
                                    existing.amount == parsed.amount &&
                                    existing.type != parsed.type &&
                                    existing.type in listOf("EXPENSE", "INCOME")
                                }
                            } else null

                            if (transferCounterpart != null) {
                                val fromAcc = if (parsed.type == "EXPENSE") walletName else transferCounterpart.getAccountName()
                                val toAcc   = if (parsed.type == "INCOME") walletName else transferCounterpart.getAccountName()
                                dao.deleteTransactionById(transferCounterpart.id)
                                val transferTx = TransactionEntry(
                                    title = parsed.title,
                                    amount = parsed.amount,
                                    category = "TRANSFER",
                                    type = "TRANSFER",
                                    smsSender = sender,
                                    smsBody = body,
                                    timestamp = targetTime,
                                    note = run {
                                        val incRefTag = if (incomingRef != null) "[IncRef: $incomingRef]" else ""
                                        "[Acc: $fromAcc][To: $toAcc][T:A]$incRefTag"
                                    }
                                )
                                dao.insertTransaction(transferTx)
                                saveReceiverFingerprint(context, "${transferTx.title}|${transferTx.amount}|TRANSFER|${transferTx.timestamp}")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "AutoLedger: Transfer ₹${parsed.amount} ($fromAcc → $toAcc)", Toast.LENGTH_LONG).show()
                                }
                                return@launch
                            }

                            dao.insertTransaction(transaction)
                            saveReceiverFingerprint(context, "${transaction.title}|${transaction.amount}|${transaction.type}|${transaction.timestamp}")

                            // Keep CC availableLimit in sync with live incoming transactions.
                            // Live SMS is always post-any-existing-CC-Summary, so delta is always valid.
                            val linkedAcc = dao.getAccountByName(walletName)
                            if (linkedAcc != null && linkedAcc.type == "CREDIT_CARD") {
                                when {
                                    // CC Payment SMS reports the exact available limit — set it directly
                                    parsed.availableLimit != null ->
                                        dao.updateAccountAvailableLimit(linkedAcc.id, parsed.availableLimit)
                                    // Regular expense/income with a known credit limit — apply delta
                                    // (live SMS always post-CC-Summary, no pre-summary guard needed)
                                    linkedAcc.creditLimit > 0 && (parsed.type == "EXPENSE" || parsed.type == "INCOME") -> {
                                        val delta = if (parsed.type == "EXPENSE") -parsed.amount else parsed.amount
                                        dao.updateAccountAvailableLimit(linkedAcc.id, linkedAcc.availableLimit + delta)
                                    }
                                }
                            }

                            // If this SMS ALSO reported an appended available balance (e.g.
                            // "Avl Bal Rs.X", "Bal: INR X") alongside the regular transaction,
                            // create/refresh a Balance Sync snapshot too — mirrors the bulk
                            // SMS-scan import path (FinanceViewModel), which already did this;
                            // this live single-SMS receiver previously only created a Balance
                            // Sync for PURE balance-notification SMS (isBalanceUpdate == true),
                            // silently skipping this for every live-received regular
                            // transaction that happened to also report its resulting balance.
                            if (balanceSyncEnabled && parsed.availableBalance != null && linkedAcc != null && linkedAcc.type != "CREDIT_CARD") {
                                val bsTs = targetTime + 1L
                                val existingSync = dao.getExactBalanceUpdate(linkedAcc.name, bsTs)
                                val shouldInsert = if (existingSync != null) {
                                    val sameValue = Math.abs(existingSync.amount - parsed.availableBalance) < 0.01
                                    if (!sameValue) dao.deleteTransactionById(existingSync.id)
                                    !sameValue
                                } else true
                                if (shouldInsert) {
                                    val snapTx = TransactionEntry(
                                        title = "Balance Sync",
                                        amount = parsed.availableBalance,
                                        category = "",
                                        type = "BALANCE_UPDATE",
                                        smsSender = sender,
                                        smsBody = body,
                                        timestamp = bsTs,
                                        note = "[Acc: ${linkedAcc.name}]"
                                    )
                                    dao.insertTransaction(snapTx)
                                    saveReceiverFingerprint(context, "Balance Sync|${parsed.availableBalance}|BALANCE_UPDATE|$bsTs")
                                }
                            }

                            withContext(Dispatchers.Main) {
                                val direction = if (parsed.type == "INCOME") "Income" else "Expense"
                                Toast.makeText(
                                    context,
                                    "AutoLedger: $direction ₹${parsed.amount} at ${parsed.title}",
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

    /** Appends a fingerprint to the SharedPrefs set that ViewModel reads on next resume. */
    private fun saveReceiverFingerprint(context: Context, fingerprint: String) {
        try {
            val prefs = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("receiver_new_fingerprints", null)?.toMutableSet() ?: mutableSetOf()
            existing.add(fingerprint)
            // Keep at most 100 pending fingerprints to avoid unbounded growth
            val trimmed = if (existing.size > 100) existing.toList().takeLast(100).toSet() else existing
            prefs.edit().putStringSet("receiver_new_fingerprints", trimmed).apply()
        } catch (_: Exception) {}
    }

    private suspend fun ensureAccountExists(context: Context, dao: com.example.data.FinanceDao, accountRef: String?, senderHeader: String?, smsBody: String): String? {
        val last4Ref = accountRef ?: return "Cash Wallet"

        // Special wallet markers — no last-4 digits required. Mirrors
        // FinanceViewModel.ensureAccountExists's wallet handling (kept in sync — see that
        // function's comment for why this generic lookup replaced per-wallet special cases).
        val walletDisplayName = com.example.utils.SmsParser.walletDisplayNameForRef(last4Ref)
        if (walletDisplayName != null) {
            val blocklistPatterns = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
                .getStringSet("sms_blocklist_patterns", emptySet()) ?: emptySet()
            fun matchesBlocklist(ref: String): Boolean {
                val refLower = ref.lowercase()
                return blocklistPatterns.any { pat ->
                    val p = pat.lowercase().trim()
                    when {
                        p.startsWith("*") && p.endsWith("*") && p.length > 2 -> refLower.contains(p.substring(1, p.length - 1))
                        p.startsWith("*") -> refLower.endsWith(p.substring(1))
                        p.endsWith("*") -> refLower.startsWith(p.dropLast(1))
                        else -> refLower.contains(p)
                    }
                }
            }
            if (matchesBlocklist(walletDisplayName) || matchesBlocklist(senderHeader ?: "")) return null
            val existing = dao.getAllAccounts().first().find { it.name.equals(walletDisplayName, ignoreCase = true) }
            if (existing != null) {
                val blockedIds = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
                    .getStringSet("blocked_sms_account_ids", emptySet())?.toSet() ?: emptySet()
                if (blockedIds.contains(existing.id)) return null
                return existing.name
            }
            dao.insertAccount(Account(name = walletDisplayName, balance = 0.0, type = "WALLET", lastFour = null))
            return walletDisplayName
        }

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
        // Card-kind classification (and the resulting type/name) is shared with
        // FinanceViewModel.ensureAccountExists via accountTypeAndLabelFor() so this
        // background-scan entry point and the foreground one never diverge on naming —
        // both are matched afterwards purely by the account's last-4 digits either way.
        val cardKind = inferSmsCardKind(smsBody, senderHeader)
        val displayName = smsDisplayBankName(extractedBank)
        val (acType, nameLabel) = accountTypeAndLabelFor(cardKind, displayName, actualLast4)

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
