package com.example.viewmodel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.*
import com.example.utils.ExcelExporter
import com.example.utils.PdfExporter
import com.example.utils.SecurityUtils
import com.example.utils.inferSmsBankCode
import com.example.utils.isSmsTrackingBlocked
import com.example.utils.smsBankMatchesAccount
import com.example.utils.smsDisplayBankName
import com.example.utils.SmsParser
import com.example.utils.isDuplicateImportedTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Holds a parsed transaction before it is committed, so we can match transfer pairs first. */
private data class PendingTxItem(
    val tx: com.example.data.TransactionEntry,
    val refNo: String?,
    val walletName: String,
    val parsedAvailableLimit: Double?,
    val parsedAccountRef: String?,
    val parsedAvailableBalance: Double? = null
)

class FinanceViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "FinanceViewModel"
    private val budgetAlertChannelId = "budget_alerts"
    private val db = FinanceDatabase.getDatabase(application)
    private val repository = FinanceRepository(db.financeDao())
    private val createdAccountsCache = mutableMapOf<String, String>()

    // State for filtering
    private val _selectedMonthYear = MutableStateFlow("")
    val selectedMonthYear: StateFlow<String> = _selectedMonthYear.asStateFlow()

    private val _displayMode = MutableStateFlow(DisplayMode.MONTHLY)
    val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

    private val _anchorDate = MutableStateFlow(System.currentTimeMillis())
    val anchorDate: StateFlow<Long> = _anchorDate.asStateFlow()

    fun setDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
    }

    fun setAnchorDate(time: Long) {
        _anchorDate.value = time
        _selectedMonthYear.value = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(time))
    }

    // Configurable options backed by persistent SharedPreferences
    private val prefs = application.getSharedPreferences("finance_settings", android.content.Context.MODE_PRIVATE)

    private val _carryOverPreviousAmount = MutableStateFlow(prefs.getBoolean("carry_over_previous_amount", false))
    val carryOverPreviousAmount: StateFlow<Boolean> = _carryOverPreviousAmount.asStateFlow()

    private val _showTotal = MutableStateFlow(prefs.getBoolean("show_total", true))
    val showTotal: StateFlow<Boolean> = _showTotal.asStateFlow()

    private val _consolidateAccounts = MutableStateFlow(prefs.getBoolean("consolidate_accounts", false))
    val consolidateAccounts: StateFlow<Boolean> = _consolidateAccounts.asStateFlow()
    private val _blockedSmsAccountIds = MutableStateFlow(prefs.getStringSet("blocked_sms_account_ids", emptySet())?.toSet() ?: emptySet())
    val blockedSmsAccountIds: StateFlow<Set<String>> = _blockedSmsAccountIds.asStateFlow()
    // Account NAMES stored separately so they survive after the account is deleted
    private val _blockedAccountNames = MutableStateFlow(prefs.getStringSet("blocked_account_names", emptySet())?.toSet() ?: emptySet())
    val blockedAccountNames: StateFlow<Set<String>> = _blockedAccountNames.asStateFlow()

    fun setAccountSmsTrackingBlocked(account: Account, blocked: Boolean) {
        val updatedIds   = _blockedSmsAccountIds.value.toMutableSet()
        val updatedNames = _blockedAccountNames.value.toMutableSet()
        if (blocked) { updatedIds.add(account.id); updatedNames.add(account.name) }
        else         { updatedIds.remove(account.id); updatedNames.remove(account.name) }
        _blockedSmsAccountIds.value = updatedIds
        _blockedAccountNames.value  = updatedNames
        prefs.edit()
            .putStringSet("blocked_sms_account_ids", updatedIds)
            .putStringSet("blocked_account_names", updatedNames)
            .apply()
    }

    fun unblockAccountByName(name: String) {
        val updated = _blockedAccountNames.value.toMutableSet().also { it.remove(name) }
        _blockedAccountNames.value = updated
        prefs.edit().putStringSet("blocked_account_names", updated).apply()
    }

    fun setCarryOverPreviousAmount(value: Boolean) {
        _carryOverPreviousAmount.value = value
        prefs.edit().putBoolean("carry_over_previous_amount", value).apply()
    }

    fun setShowTotal(value: Boolean) {
        _showTotal.value = value
        prefs.edit().putBoolean("show_total", value).apply()
    }

    fun setConsolidateAccounts(value: Boolean) {
        _consolidateAccounts.value = value
        prefs.edit().putBoolean("consolidate_accounts", value).apply()
    }

    // ── Credit card detail display ─────────────────────────────────────────────
    private val _showCreditCardDetails = MutableStateFlow(prefs.getBoolean("show_credit_card_details", false))
    val showCreditCardDetails: StateFlow<Boolean> = _showCreditCardDetails.asStateFlow()
    fun setShowCreditCardDetails(v: Boolean) {
        _showCreditCardDetails.value = v
        prefs.edit().putBoolean("show_credit_card_details", v).apply()
    }

    // ── Balance sync scan toggle ───────────────────────────────────────────────
    private val _enableBalanceSync = MutableStateFlow(prefs.getBoolean("enable_balance_sync", true))
    val enableBalanceSync: StateFlow<Boolean> = _enableBalanceSync.asStateFlow()
    fun setEnableBalanceSync(v: Boolean) {
        _enableBalanceSync.value = v
        prefs.edit().putBoolean("enable_balance_sync", v).apply()
    }

    // ── Running balance overlay on transaction records ─────────────────────────
    private val _showRunningBalance = MutableStateFlow(prefs.getBoolean("show_running_balance", false))
    val showRunningBalance: StateFlow<Boolean> = _showRunningBalance.asStateFlow()
    fun setShowRunningBalance(v: Boolean) {
        _showRunningBalance.value = v
        prefs.edit().putBoolean("show_running_balance", v).apply()
    }

    // ── Recently imported fingerprints (from custom-pattern scan) — session-only highlight ─────
    // Fingerprint format: "title|amount|type|timestamp". Cleared on new scan. Never persisted.
    private val _recentlyImportedFingerprints = MutableStateFlow<Set<String>>(emptySet())
    val recentlyImportedFingerprints: StateFlow<Set<String>> = _recentlyImportedFingerprints.asStateFlow()

    // ── Light / Dark theme preference ─────────────────────────────────────────
    // themeMode: "system" (follow device) | "light" | "dark"
    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "system") ?: "system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()
    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
    }
    // Legacy isDarkTheme kept for backward compat (used when themeMode == "dark")
    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean("is_dark_theme", false))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()
    fun setDarkTheme(dark: Boolean) {
        _isDarkTheme.value = dark
        prefs.edit().putBoolean("is_dark_theme", dark).apply()
    }

    // ── Hidden wallets (display only) ─────────────────────────────────────────
    private val _hiddenAccountIds = MutableStateFlow(prefs.getStringSet("hidden_account_ids", emptySet())?.toSet() ?: emptySet())
    val hiddenAccountIds: StateFlow<Set<String>> = _hiddenAccountIds.asStateFlow()
    fun setAccountHidden(accountId: String, hidden: Boolean) {
        val updated = _hiddenAccountIds.value.toMutableSet()
        if (hidden) updated.add(accountId) else updated.remove(accountId)
        _hiddenAccountIds.value = updated
        prefs.edit().putStringSet("hidden_account_ids", updated).apply()
    }

    // ── SMS import blocklist patterns (bank name / last-4 wildcards) ──────────
    private val _smsBlocklistPatterns = MutableStateFlow(prefs.getStringSet("sms_blocklist_patterns", emptySet())?.toSet() ?: emptySet())
    val smsBlocklistPatterns: StateFlow<Set<String>> = _smsBlocklistPatterns.asStateFlow()
    fun addSmsBlocklistPattern(pattern: String) {
        val updated = _smsBlocklistPatterns.value.toMutableSet().also { it.add(pattern.trim()) }
        _smsBlocklistPatterns.value = updated
        prefs.edit().putStringSet("sms_blocklist_patterns", updated).apply()
    }
    fun removeSmsBlocklistPattern(pattern: String) {
        val updated = _smsBlocklistPatterns.value.toMutableSet().also { it.remove(pattern) }
        _smsBlocklistPatterns.value = updated
        prefs.edit().putStringSet("sms_blocklist_patterns", updated).apply()
    }
    fun matchesSmsBlocklistPattern(ref: String): Boolean {
        val lower = ref.lowercase().trim()
        return _smsBlocklistPatterns.value.any { pat ->
            val p = pat.lowercase().trim()
            when {
                p.startsWith("*") && p.endsWith("*") && p.length > 2 -> lower.contains(p.substring(1, p.length - 1))
                p.startsWith("*") -> lower.endsWith(p.substring(1))
                p.endsWith("*") -> lower.startsWith(p.dropLast(1))
                else -> lower.contains(p)
            }
        }
    }
    fun deleteTransactionsMatchingBlocklist() {
        viewModelScope.launch {
            var n = 0
            allTransactions.value.forEach { tx ->
                val ref = tx.note?.substringAfter("[Acc: ")?.substringBefore("]") ?: tx.smsSender ?: ""
                if (matchesSmsBlocklistPattern(ref) || matchesSmsBlocklistPattern(tx.title)) {
                    repository.deleteTransaction(tx.id); n++
                }
            }
            _toastMessage.emit(if (n > 0) "Deleted $n transaction(s) matching blocklist." else "No matching transactions found.")
        }
    }

    // ── Merchant → Category Rules ──────────────────────────────────────────────
    private fun loadMerchantCategoryRules(): List<Pair<String, String>> {
        val json = prefs.getString("merchant_category_rules", "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.getString("pattern") to obj.getString("category")
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveMerchantCategoryRules(rules: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        rules.forEach { (p, c) -> arr.put(org.json.JSONObject().put("pattern", p).put("category", c)) }
        prefs.edit().putString("merchant_category_rules", arr.toString()).apply()
    }

    private val _merchantCategoryRules = MutableStateFlow(loadMerchantCategoryRules())
    val merchantCategoryRules: StateFlow<List<Pair<String, String>>> = _merchantCategoryRules.asStateFlow()

    fun addMerchantCategoryRule(pattern: String, category: String) {
        val updated = _merchantCategoryRules.value.toMutableList()
        updated.removeAll { it.first.equals(pattern.trim(), ignoreCase = true) }
        updated.add(0, pattern.trim() to category.trim())
        _merchantCategoryRules.value = updated
        saveMerchantCategoryRules(updated)
    }

    fun removeMerchantCategoryRule(pattern: String) {
        val updated = _merchantCategoryRules.value.filter { !it.first.equals(pattern.trim(), ignoreCase = true) }
        _merchantCategoryRules.value = updated
        saveMerchantCategoryRules(updated)
    }

    /** Wildcard matching: `zerodha*` = startsWith, `*paytm*` = contains, `*nova` = endsWith, else = contains */
    private fun matchesMerchantPattern(title: String, pattern: String): Boolean {
        val lower = title.lowercase().trim()
        val p = pattern.lowercase().trim()
        return when {
            p.startsWith("*") && p.endsWith("*") && p.length > 2 -> lower.contains(p.substring(1, p.length - 1))
            p.startsWith("*") -> lower.endsWith(p.substring(1))
            p.endsWith("*") -> lower.startsWith(p.dropLast(1))
            else -> lower.contains(p)
        }
    }

    /** Returns overriding category name if any rule matches, else null. */
    fun applyMerchantRulesToCategory(title: String): String? {
        for ((pattern, category) in _merchantCategoryRules.value) {
            if (matchesMerchantPattern(title, pattern)) return category
        }
        return null
    }

    fun reapplyMerchantRulesToExisting() {
        viewModelScope.launch {
            var updatedCount = 0
            allTransactions.value.forEach { tx ->
                val override = applyMerchantRulesToCategory(tx.title)
                if (override != null && !override.equals(tx.category, ignoreCase = true)) {
                    repository.updateTransaction(tx.copy(category = override))
                    updatedCount++
                }
            }
            _toastMessage.emit(
                if (updatedCount > 0) "Re-categorized $updatedCount existing record(s) using merchant rules."
                else "No existing records needed re-categorization."
            )
        }
    }

    // Custom Categories
    val allCustomCategories: StateFlow<List<CustomCategory>> = repository.allCustomCategories
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Recurring Transactions
    val allRecurringTransactions: StateFlow<List<RecurringTransaction>> = repository.allRecurringTransactions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Accounts
    val allAccounts: StateFlow<List<Account>> = repository.allAccounts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Initialize with current month e.g., "2026-06"
        val current = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        _selectedMonthYear.value = current
        createBudgetAlertChannel()
        checkAndLogRecurringTransactions()
        seedDefaultAccountsIfNeeded()
    }

    private fun seedDefaultAccountsIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.allAccounts.first().isEmpty()) {
                listOf(
                    Account(name = "Cash Wallet",    type = "CASH",        balance = 0.0),
                    Account(name = "Savings Account", type = "BANK",        balance = 0.0),
                    Account(name = "Credit Card",     type = "CREDIT_CARD", balance = 0.0),
                    Account(name = "Digital Wallet",  type = "WALLET",      balance = 0.0)
                ).forEach { repository.insertAccount(it) }
            }
        }
    }

    private fun createBudgetAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getApplication<Application>().getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                budgetAlertChannelId,
                "Budget Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when monthly category budgets near or exceed limits"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private suspend fun maybeNotifyBudgetAlert(transaction: TransactionEntry, projectedTransactions: List<TransactionEntry>) {
        if (transaction.type != "EXPENSE") return

        val monthYear = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(transaction.timestamp))
        val monthBudgets = repository.getBudgetsForMonth(monthYear).first()
        val budget = monthBudgets.firstOrNull { it.category.equals(transaction.category, ignoreCase = true) } ?: return
        if (budget.amountLimit <= 0.0) return

        val spent = projectedTransactions.filter {
            it.type == "EXPENSE" &&
                it.category.equals(transaction.category, ignoreCase = true) &&
                SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(it.timestamp)) == monthYear
        }.sumOf { it.amount }

        val ratio = spent / budget.amountLimit
        val displayCategoryName = CategoryResolver.resolve(transaction.category, allCustomCategories.value).displayName
        val thresholds = listOf(0.75 to "75%", 0.90 to "90%", 1.0 to "100%")

        thresholds.forEach { (threshold, label) ->
            val prefKey = "budget_alert_${monthYear}_${budget.category.uppercase(Locale.getDefault())}_$label"
            if (ratio >= threshold && !prefs.getBoolean(prefKey, false)) {
                prefs.edit().putBoolean(prefKey, true).apply()
                postBudgetAlert(displayCategoryName, spent, budget.amountLimit, label)
            }
        }
    }

    private fun postBudgetAlert(categoryName: String, spent: Double, limit: Double, thresholdLabel: String) {
        val application = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(application, budgetAlertChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Budget alert: $categoryName")
            .setContentText("$categoryName reached $thresholdLabel of budget. Spent ₹${"%.2f".format(Locale.getDefault(), spent)} of ₹${"%.2f".format(Locale.getDefault(), limit)}.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(application).notify(categoryName.hashCode() + thresholdLabel.hashCode(), notification)
    }

    private fun getNextOccurenceDate(time: Long, frequency: String): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = time }
        when (frequency.uppercase()) {
            "DAILY" -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            "WEEKLY" -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            "MONTHLY" -> calendar.add(Calendar.MONTH, 1)
            "YEARLY" -> calendar.add(Calendar.YEAR, 1)
            else -> calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    fun checkAndLogRecurringTransactions() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentTime = System.currentTimeMillis()
            val activeRecurring = repository.getAllRecurringTransactionsSync()
            for (rec in activeRecurring) {
                if (!rec.autoLog) continue

                // Determine starting point for check
                var nextCheckTime = if (rec.lastExecutedDate > 0L) {
                    getNextOccurenceDate(rec.lastExecutedDate, rec.frequency)
                } else {
                    rec.startDate
                }

                var lastExecuted = rec.lastExecutedDate
                var logsCount = 0

                while (nextCheckTime <= currentTime && nextCheckTime <= rec.endDate) {
                    val tx = TransactionEntry(
                        title = rec.title + " (Recurring)",
                        amount = rec.amount,
                        category = rec.category,
                        type = rec.type,
                        timestamp = nextCheckTime,
                        note = rec.note ?: "Automatically logged recurring transaction."
                    )
                    repository.insertTransaction(tx)

                    lastExecuted = nextCheckTime
                    logsCount++

                    nextCheckTime = getNextOccurenceDate(nextCheckTime, rec.frequency)
                }

                if (logsCount > 0) {
                    val updatedRec = rec.copy(lastExecutedDate = lastExecuted)
                    repository.updateRecurringTransaction(updatedRec)
                    Log.d(TAG, "Automatically logged $logsCount occurrences for recurring transaction: ${rec.title}")
                }
            }
        }
    }

    fun addCustomCategory(name: String, iconName: String, colorHex: String, type: String = "EXPENSE") {
        viewModelScope.launch {
            if (name.isBlank()) return@launch
            val trimmed = name.trim()
            val iconNameWithType = "$iconName:$type"
            // Reuse existing ID to avoid duplicate rows
            val existing = repository.getCustomCategoryByName(trimmed)
            val cat = CustomCategory(id = existing?.id ?: 0, name = trimmed, iconName = iconNameWithType, colorHex = colorHex)
            repository.insertCustomCategory(cat)
            _toastMessage.emit("Category '$trimmed' added successfully!")
        }
    }

    fun updateCustomCategory(id: Int, oldName: String, newName: String, iconName: String, colorHex: String, type: String = "EXPENSE") {
        viewModelScope.launch {
            if (newName.isBlank()) return@launch
            val trimmedNew = newName.trim()
            // When the user hasn't changed the name (editCatName is pre-filled with displayName
            // which may differ in case from the stored key, e.g. "Groceries" vs "GROCERIES"),
            // keep the original stored key to prevent silent key drift in the DB.
            val resolvedName = if (trimmedNew.equals(oldName, ignoreCase = true)) oldName else trimmedNew
            val iconNameWithType = "$iconName:$type"
            val cat = CustomCategory(id = id, name = resolvedName, iconName = iconNameWithType, colorHex = colorHex)
            repository.insertCustomCategory(cat)
            if (!resolvedName.equals(oldName, ignoreCase = true)) {
                repository.updateCategoryReferences(oldName, resolvedName)
            }
            _toastMessage.emit("Category '$resolvedName' updated successfully!")
        }
    }

    fun deleteCustomCategory(id: Int) {
        viewModelScope.launch {
            repository.deleteCustomCategory(id)
            _toastMessage.emit("Custom category deleted.")
        }
    }

    fun customizeStandardCategory(oldName: String, newName: String, iconName: String, colorHex: String, type: String) {
        viewModelScope.launch {
            if (newName.isBlank()) return@launch
            val trimmedNew = newName.trim()
            val iconNameWithType = "$iconName:$type"
            val standardDisplayName = ExpenseCategory.fromString(oldName).displayName
            val isRename = !oldName.equals(trimmedNew, ignoreCase = true) && !standardDisplayName.equals(trimmedNew, ignoreCase = true)
            
            if (isRename) {
                // 1. Hide the old standard category — reuse existing ID to avoid duplicates
                val existingOld = repository.getCustomCategoryByName(oldName)
                val hideCat = CustomCategory(id = existingOld?.id ?: 0, name = oldName, iconName = "hidden:$type", colorHex = "#000000")
                repository.insertCustomCategory(hideCat)
                
                // 2. Add the new custom category — reuse existing ID if name already exists
                val existingNew = repository.getCustomCategoryByName(trimmedNew)
                val newCat = CustomCategory(id = existingNew?.id ?: 0, name = trimmedNew, iconName = iconNameWithType, colorHex = colorHex)
                repository.insertCustomCategory(newCat)
                
                // 3. Update all existing transaction category references
                repository.updateCategoryReferences(oldName, trimmedNew)
                _toastMessage.emit("Category renamed to '$trimmedNew' and updated.")
            } else {
                // Just overriding the icon/color — reuse existing ID to avoid duplicate rows
                val existingCustom = repository.getCustomCategoryByName(oldName)
                val overrideCat = CustomCategory(id = existingCustom?.id ?: 0, name = oldName, iconName = iconNameWithType, colorHex = colorHex)
                repository.insertCustomCategory(overrideCat)
                _toastMessage.emit("Category '$standardDisplayName' icon/color updated.")
            }
        }
    }

    fun hideStandardCategory(name: String, type: String) {
        viewModelScope.launch {
            // Reuse existing ID to avoid duplicate hidden entries
            val existing = repository.getCustomCategoryByName(name)
            val hideCat = CustomCategory(id = existing?.id ?: 0, name = name, iconName = "hidden:$type", colorHex = "#000000")
            repository.insertCustomCategory(hideCat)
            _toastMessage.emit("Category '$name' removed.")
        }
    }

    fun addAccount(
        name: String,
        balance: Double,
        type: String,
        lastFour: String? = null,
        openingBalanceTimestamp: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isBlank()) return@launch
            val cleanName = name.trim()
            val existing = repository.allAccounts.first().any { it.name.lowercase() == cleanName.lowercase() }
            if (existing) {
                _toastMessage.emit("Wallet named '$cleanName' already exists!")
                return@launch
            }
            val acc = Account(name = cleanName, balance = 0.0, type = type.uppercase(), lastFour = lastFour?.trim())
            repository.insertAccount(acc)
            if (balance != 0.0) {
                repository.insertTransaction(
                    TransactionEntry(
                        title = "Opening Balance",
                        amount = kotlin.math.abs(balance),
                        category = if (balance >= 0.0) "SALARY" else "OTHERS",
                        type = if (balance >= 0.0) "INCOME" else "EXPENSE",
                        timestamp = openingBalanceTimestamp,
                        note = "Opening balance [Acc: $cleanName]"
                    )
                )
            }
            _toastMessage.emit("Successfully configured account: $cleanName ($type)")
        }
    }

    fun updateAccount(account: Account) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateAccount(account)
            _toastMessage.emit("Budget wallet configurations updated.")
        }
    }

    fun renameAccountTransactions(oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = repository.allTransactions.first()
                for (tx in list) {
                    val currentAcc = tx.getAccountName()
                    if (currentAcc == oldName) {
                        val noteStr = tx.note ?: ""
                        val updatedNote = if (noteStr.contains("[Acc: $oldName]")) {
                            noteStr.replace("[Acc: $oldName]", "[Acc: $newName]")
                        } else {
                            if (noteStr.contains("[Acc: ")) {
                                noteStr.replace("\\[Acc: [^\\]]+\\]".toRegex(), "[Acc: $newName]")
                            } else {
                                if (noteStr.isBlank()) "[Acc: $newName]" else "$noteStr [Acc: $newName]"
                            }
                        }
                        repository.updateTransaction(tx.copy(note = updatedNote))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed renaming account transaction notes: ${e.message}", e)
            }
        }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            createdAccountsCache.clear() // prevent stale cache from blocking account recreation
            repository.deleteAccount(accountId)
            _toastMessage.emit("Account and its records deleted.")
        }
    }

    fun adjustAccountBalance(accountId: String, targetTotalBalance: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = repository.getAccountById(accountId) ?: return@launch
            val updated = account.copy(balance = targetTotalBalance)
            repository.updateAccount(updated)
            _toastMessage.emit("Balance adjusted successfully!")
        }
    }

    suspend fun ensureAccountExists(accountRef: String?, senderHeader: String?, smsBody: String): String? {
        val last4Ref = accountRef ?: return "Cash Wallet"
        
        // Check in-memory cache first to avoid race conditions during batch scan
        createdAccountsCache[last4Ref]?.let { return it }

        // Special wallet markers — no last-4 digits required
        if (last4Ref == "APAY_WALLET") {
            val walletDisplayName = "Apay Wallet"
            if (matchesSmsBlocklistPattern(walletDisplayName) || matchesSmsBlocklistPattern(senderHeader ?: "")) {
                Log.d(TAG, "ensureAccountExists: blocked Apay Wallet by blocklist")
                return null
            }
            val existing = repository.allAccounts.first().find { it.name.equals(walletDisplayName, ignoreCase = true) }
            if (existing != null && (isSmsTrackingBlocked(existing, blockedSmsAccountIds.value) || matchesSmsBlocklistPattern(existing.name))) {
                Log.d(TAG, "ensureAccountExists: import blocked for existing Apay Wallet")
                createdAccountsCache[last4Ref] = walletDisplayName; return null
            }
            val name = existing?.name ?: run {
                repository.insertAccount(Account(name = walletDisplayName, balance = 0.0, type = "WALLET", lastFour = null))
                walletDisplayName
            }
            createdAccountsCache[last4Ref] = name; return name
        }
        if (last4Ref == "NEUCOINS_WALLET") {
            val walletDisplayName = "NeuCoins"
            if (matchesSmsBlocklistPattern(walletDisplayName) || matchesSmsBlocklistPattern(senderHeader ?: "")) {
                Log.d(TAG, "ensureAccountExists: blocked NeuCoins by blocklist")
                return null
            }
            val existing = repository.allAccounts.first().find { it.name.equals(walletDisplayName, ignoreCase = true) }
            if (existing != null && (isSmsTrackingBlocked(existing, blockedSmsAccountIds.value) || matchesSmsBlocklistPattern(existing.name))) {
                Log.d(TAG, "ensureAccountExists: import blocked for existing NeuCoins")
                createdAccountsCache[last4Ref] = walletDisplayName; return null
            }
            val name = existing?.name ?: run {
                repository.insertAccount(Account(name = walletDisplayName, balance = 0.0, type = "WALLET", lastFour = null))
                walletDisplayName
            }
            createdAccountsCache[last4Ref] = name; return name
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

        val list = repository.allAccounts.first()
        val candidates = list.filter {
            (it.lastFour == actualLast4) || (actualLast4.isNotEmpty() && it.name.contains(actualLast4))
        }
        val match = candidates.firstOrNull { smsBankMatchesAccount(extractedBank, it.name) }
            ?: candidates.singleOrNull()
        if (match != null) {
            if (isSmsTrackingBlocked(match, blockedSmsAccountIds.value) ||
                matchesSmsBlocklistPattern(match.name) ||
                matchesSmsBlocklistPattern(senderHeader ?: "")) {
                Log.d(TAG, "ensureAccountExists: import blocked for existing account — ${match.name}")
                createdAccountsCache[last4Ref] = match.name
                return null
            }
            createdAccountsCache[last4Ref] = match.name
            return match.name
        }

        val bodyLower = smsBody.lowercase()
        // Guard: never create a new account from a due-notice / reminder SMS
        val isDueOrReminder = bodyLower.contains("is due") || bodyLower.contains("due on") ||
            bodyLower.contains("due by") || bodyLower.contains("emi due") ||
            (bodyLower.contains("emi") && !bodyLower.contains("credited") && !bodyLower.contains("debited")) ||
            (bodyLower.contains("due") && (bodyLower.contains("bill") || bodyLower.contains("loan")))
        if (isDueOrReminder) {
            Log.d(TAG, "ensureAccountExists: skipping account creation for due/reminder SMS.")
            return null
        }
        val isCreditCard = bodyLower.contains("card") || 
                           bodyLower.contains("credit card") || 
                           bodyLower.contains("card limit") || 
                           bodyLower.contains("spent on") || 
                           (senderHeader ?: "").uppercase().contains("CARD")
         val acType = if (isCreditCard) "CREDIT_CARD" else "BANK"
         val displayName = smsDisplayBankName(extractedBank)
         val nameLabel = if (isCreditCard) {
             "$displayName CC ·$actualLast4"
         } else {
             if (displayName.endsWith("Bank", ignoreCase = true)) {
                 "$displayName ·$actualLast4"
             } else {
                 "$displayName Bank ·$actualLast4"
             }
         }
 
         val startBal = 0.0

         // Safety rule: account name must NEVER start with a digit.
         // Try to extract a meaningful word prefix from the body near the masked account ref.
         val validatedNameLabel = if (nameLabel.firstOrNull()?.isDigit() == true) {
             // Look for pattern: letters immediately before asterisks/X's and the digits (e.g. "BGBNG*****4090" → "BGBNG")
             val bodyPrefixPat = java.util.regex.Pattern.compile(
                 "([A-Za-z]{2,})[xX*\\s]+${java.util.regex.Pattern.quote(actualLast4)}\\b",
                 java.util.regex.Pattern.CASE_INSENSITIVE)
             val prefixMatcher = bodyPrefixPat.matcher(smsBody)
             if (prefixMatcher.find()) {
                 val prefix = (prefixMatcher.group(1) ?: "").uppercase().filter { it.isLetter() }
                 if (prefix.isNotBlank()) {
                     val prefixDisplay = smsDisplayBankName(prefix)
                     if (isCreditCard) "$prefixDisplay CC ·$actualLast4"
                     else if (prefixDisplay.endsWith("Bank", ignoreCase = true)) "$prefixDisplay ·$actualLast4"
                     else "$prefixDisplay Bank ·$actualLast4"
                 } else nameLabel
             } else nameLabel
         } else nameLabel

         // Guard: do NOT create account if name or sender is in the SMS Import Blocklist
         if (matchesSmsBlocklistPattern(validatedNameLabel) || matchesSmsBlocklistPattern(senderHeader ?: "")) {
             Log.d(TAG, "ensureAccountExists: blocked account creation by blocklist — $validatedNameLabel")
             return null
         }
         val newAcObj = Account(name = validatedNameLabel, balance = startBal, type = acType, lastFour = actualLast4)
         repository.insertAccount(newAcObj)
         Log.d(TAG, "Created account dynamically from parsed SMS: $validatedNameLabel")
         createdAccountsCache[last4Ref] = validatedNameLabel
         return validatedNameLabel
     }

    fun addRecurringTransaction(
        title: String,
        amount: Double,
        category: String,
        type: String,
        frequency: String,
        startDate: Long,
        endDate: Long,
        autoLog: Boolean,
        note: String? = null
    ) {
        viewModelScope.launch {
            if (title.isBlank() || amount <= 0.0) return@launch
            val rec = RecurringTransaction(
                title = title.trim(),
                amount = amount,
                category = category,
                type = type,
                frequency = frequency,
                startDate = startDate,
                endDate = endDate,
                lastExecutedDate = 0L,
                autoLog = autoLog,
                note = note
            )
            repository.insertRecurringTransaction(rec)
            _toastMessage.emit("Recurring transaction '$title' scheduled ($frequency)!")
            checkAndLogRecurringTransactions()
        }
    }

    fun deleteRecurringTransaction(id: Int) {
        viewModelScope.launch {
            repository.deleteRecurringTransaction(id)
            _toastMessage.emit("Recurring schedule removed.")
        }
    }


    // Expose all transactions
    val allTransactions: StateFlow<List<TransactionEntry>> = repository.allTransactions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Filtered transactions for selected month
    val monthlyTransactions: StateFlow<List<TransactionEntry>> = combine(
        allTransactions,
        selectedMonthYear
    ) { txs, monthYear ->
        val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        txs.filter { tx ->
            val txMonthYear = sdf.format(Date(tx.timestamp))
            txMonthYear == monthYear
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Budgets for selected month
    val monthlyBudgets: StateFlow<List<BudgetEntry>> = _selectedMonthYear
        .flatMapLatest { monthYear ->
            repository.getBudgetsForMonth(monthYear)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // UI Feedback States
    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage = _toastMessage.asSharedFlow()

    private val _backupString = MutableStateFlow<String?>(null)
    val backupString: StateFlow<String?> = _backupString.asStateFlow()

    // Gemini API connectivity status (check if API key is not a placeholder)
    val isGeminiAvailable: Boolean
        get() {
            val key = BuildConfig.GEMINI_API_KEY
            return key.isNotEmpty() && key != "MY_GEMINI_API_KEY"
        }

    fun setMonthYear(monthYear: String) {
        _selectedMonthYear.value = monthYear
    }

    // Transaction CRUD
    fun addTransaction(title: String, amount: Double, categoryName: String, type: String, note: String? = null, timestamp: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            val tx = TransactionEntry(
                title = title,
                amount = amount,
                category = categoryName,
                type = type,
                note = note,
                timestamp = timestamp
            )
            repository.insertTransaction(tx)
            // Keep CC availableLimit in sync with manual transactions
            adjustCcAvailableLimit(note, amount, type, reverse = false, txTimestamp = timestamp)
            maybeNotifyBudgetAlert(tx, allTransactions.value + tx)
            _toastMessage.emit("Added: $title ($type)")
        }
    }

    /** Adjusts availableLimit on a CREDIT_CARD account when a transaction is added or removed.
     *  EXPENSE reduces available credit; INCOME (payment) restores it.
     *  Set [reverse] = true when undoing (e.g., on delete or before update).
     *  Reads the account fresh from the DB to avoid stale StateFlow values in batch imports. */
    private suspend fun adjustCcAvailableLimit(note: String?, amount: Double, type: String, reverse: Boolean, txTimestamp: Long? = null) {
        if (note == null) return
        // Extract account name from note "[Acc: NAME]"
        val accStart = note.indexOf("[Acc: ")
        if (accStart < 0) return
        val nameStart = accStart + 6
        val nameEnd = note.indexOf("]", nameStart)
        if (nameEnd <= nameStart) return
        val accName = note.substring(nameStart, nameEnd)
        // Read fresh from DB — avoids stale StateFlow values during batch scan imports
        val acc = repository.getAccountByName(accName) ?: return
        if (acc.type != "CREDIT_CARD" || acc.creditLimit <= 0) return
        if (type != "EXPENSE" && type != "INCOME") return  // skip BALANCE_UPDATE, DUPLICATE, etc.
        // Pre-CC-Summary transactions are already baked into the CC Summary's availableLimit.
        // Skip the delta for any transaction whose timestamp ≤ the latest Balance Sync anchor.
        if (txTimestamp != null) {
            val latestSyncTs = repository.getLatestBalanceSyncTimestamp(accName)
            if (latestSyncTs != null && txTimestamp <= latestSyncTs) return
        }
        // EXPENSE → less available; INCOME → more available (reversed when undoing)
        val baseChange = if (type == "EXPENSE") -amount else amount
        val delta = if (reverse) -baseChange else baseChange
        repository.updateAccountAvailableLimit(acc.id, acc.availableLimit + delta)
    }

    /** Sets a balance snapshot for [accountName] at [targetBalance]. */
    fun adjustAccountBalance(accountName: String, currentBalance: Double, targetBalance: Double) {
        if (Math.abs(targetBalance - currentBalance) < 0.01) return
        viewModelScope.launch {
            // Store the absolute target as a BALANCE_UPDATE snapshot.
            // computeWalletBalances will use this as the starting point going forward.
            val tx = TransactionEntry(
                title = "Balance Adjustment",
                amount = targetBalance,
                category = "",
                type = "BALANCE_UPDATE",
                note = "Snapshot \u20b9${String.format("%.2f", targetBalance)} [Acc: $accountName]",
                timestamp = System.currentTimeMillis()
            )
            repository.insertTransaction(tx)
            _toastMessage.emit("Balance set to \u20b9${String.format("%.2f", targetBalance)} for $accountName")
        }
    }

    fun updateTransaction(tx: TransactionEntry, applyToSameMerchant: Boolean = false) {
        viewModelScope.launch {
            // Reverse old CC limit effect, then apply new one after update
            val oldTx = allTransactions.value.find { it.id == tx.id }
            if (oldTx != null) adjustCcAvailableLimit(oldTx.note, oldTx.amount, oldTx.type, reverse = true, txTimestamp = oldTx.timestamp)
            repository.updateTransaction(tx)
            adjustCcAvailableLimit(tx.note, tx.amount, tx.type, reverse = false, txTimestamp = tx.timestamp)
            // Auto-categorize all transactions with the same payee title (only when user opts in)
            if (applyToSameMerchant && tx.category.isNotBlank() && tx.title.isNotBlank()) {
                repository.recategorizeByTitle(tx.title, tx.category, tx.id)
            }
            maybeNotifyBudgetAlert(tx, allTransactions.value.map { if (it.id == tx.id) tx else it })
            _toastMessage.emit("Updated transaction details")
        }
    }

    fun deleteTransaction(txId: Int) {
        viewModelScope.launch {
            // Reverse CC limit effect before deleting
            val tx = allTransactions.value.find { it.id == txId }
            if (tx != null) adjustCcAvailableLimit(tx.note, tx.amount, tx.type, reverse = true, txTimestamp = tx.timestamp)
            repository.deleteTransaction(txId)
            _toastMessage.emit("Deleted transaction")
        }
    }

    fun deleteTransactionsInRange(startTime: Long, endTime: Long, label: String) {
        viewModelScope.launch {
            repository.deleteTransactionsInRange(startTime, endTime)
            _toastMessage.emit("Deleted all transactions for $label")
        }
    }

    fun deleteTransactionsByCategoryInRange(category: String, startTime: Long, endTime: Long) {
        viewModelScope.launch {
            repository.deleteTransactionsByCategoryInRange(category, startTime, endTime)
            _toastMessage.emit("Deleted '$category' transactions for this period")
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearTransactions()
            repository.clearBudgets()
            repository.clearAccounts()
            seedDefaultAccountsIfNeeded()
            _toastMessage.emit("All data cleared successfully")
        }
    }

    // Budget Operations
    fun saveBudget(categoryName: String, categoryDisplayName: String, amount: Double, previousCategoryName: String? = null) {
        viewModelScope.launch {
            val existingBudget = monthlyBudgets.value.firstOrNull {
                it.category.equals(categoryName, ignoreCase = true) ||
                    (!previousCategoryName.isNullOrBlank() && it.category.equals(previousCategoryName, ignoreCase = true))
            }
            val budget = BudgetEntry(
                id = existingBudget?.id ?: 0,
                category = categoryName,
                amountLimit = amount,
                monthYear = _selectedMonthYear.value
            )
            repository.insertBudget(budget)
            _toastMessage.emit("Budget set for $categoryDisplayName: ₹$amount")
        }
    }

    fun deleteBudget(budgetId: Int) {
        viewModelScope.launch {
            repository.deleteBudget(budgetId)
            _toastMessage.emit("Budget removed")
        }
    }

    /** Copies all budgets from the previous month into the current month.
     *  Skips categories that already have a budget for the current month. */
    fun copyBudgetsFromPreviousMonth() {
        viewModelScope.launch {
            val currentMonth = _selectedMonthYear.value
            val sdf = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
            val cal = java.util.Calendar.getInstance().apply {
                try { time = sdf.parse(currentMonth) ?: java.util.Date() } catch (_: Exception) { time = java.util.Date() }
                add(java.util.Calendar.MONTH, -1)
            }
            val prevMonth = sdf.format(cal.time)
            val prevBudgets = repository.getAllBudgetsOnce().filter { it.monthYear == prevMonth }
            val currentBudgets = monthlyBudgets.value
            val currentCategories = currentBudgets.map { it.category.lowercase() }.toSet()
            var copied = 0
            for (prev in prevBudgets) {
                if (!currentCategories.contains(prev.category.lowercase())) {
                    repository.insertBudget(
                        BudgetEntry(id = 0, category = prev.category, amountLimit = prev.amountLimit, monthYear = currentMonth)
                    )
                    copied++
                }
            }
            if (copied > 0) _toastMessage.emit("Copied $copied budget(s) from $prevMonth")
            else _toastMessage.emit("No new budgets to copy from $prevMonth")
        }
    }

    // Export to CSV
    fun getExcelData(): ByteArray {
        return ExcelExporter.exportToExcelBytes(allTransactions.value, allCustomCategories.value)
    }

    fun getPdfData(): ByteArray {
        return PdfExporter.exportToPdfBytes(allTransactions.value, allCustomCategories.value)
    }

    // Cloud Synchronization Simulation (Simulates encrypted backup to cloud storage)
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncedStr = MutableStateFlow("Never")
    val lastSyncedStr: StateFlow<String> = _lastSyncedStr.asStateFlow()

    fun triggerCloudSync(emailStr: String) {
        if (emailStr.isEmpty() || !emailStr.contains("@")) {
            viewModelScope.launch {
                _toastMessage.emit("Please enter a valid account email")
            }
            return
        }
        viewModelScope.launch {
            _isSyncing.value = true
            // Simulate cryptographic verification, sync connection, payload creation
            kotlinx.coroutines.delay(2000)
            _isSyncing.value = false
            _lastSyncedStr.value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            _toastMessage.emit("Cloud Sync successful for $emailStr!")
        }
    }

    /** Returns true for Apay Wallet, NeuCoins, and EPFO passbook results — excluded from the regular scan. */
    private fun isWalletOrPfResult(parsed: com.example.utils.SmsParsingResult): Boolean =
        parsed.accountRef == "APAY_WALLET" ||
        parsed.accountRef == "NEUCOINS_WALLET" ||
        parsed.title == "PF Contribution"

    fun scanDeviceSmsInbox(context: android.content.Context, monthsBack: Int = 3) {
        viewModelScope.launch {
            _isSmsParsing.value = true
            try {
                val contentResolver = context.contentResolver
                val cursor = contentResolver.query(
                    android.net.Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"),
                    null,
                    null,
                    "date DESC"
                )

                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.MONTH, -(monthsBack - 1))
                }
                val cutoffTime = cal.timeInMillis
                var matchedCount = 0
                val newImportedFingerprints = mutableSetOf<String>()
                val projectedTransactions = allTransactions.value.toMutableList()
                // Bug 2: clear stale cache so deleted accounts can be recreated
                createdAccountsCache.clear()
                if (cursor != null) {
                    val bodyIndex = cursor.getColumnIndex("body")
                    val addressIndex = cursor.getColumnIndex("address")
                    val dateIndex = cursor.getColumnIndex("date")

                    // Collect all SMS into memory first (enables two-pass processing)
                    // Bug 3: SMS are ordered DATE DESC, so balance-update SMS arrive before the
                    // account-creating regular-transaction SMS. Collect all, then:
                    //   Pass 1 → regular txns (creates accounts)
                    //   Pass 2 → balance updates (accounts now exist in DB)
                    val allRawSms = mutableListOf<Triple<String, String, Long>>() // body, sender, smsDate
                    while (cursor.moveToNext()) {
                        val smsDate = if (dateIndex != -1) cursor.getLong(dateIndex) else System.currentTimeMillis()
                        if (smsDate < cutoffTime) break
                        val body = cursor.getString(bodyIndex) ?: ""
                        val sender = cursor.getString(addressIndex) ?: "Unknown"
                        allRawSms.add(Triple(body, sender, smsDate))
                    }
                    cursor.close()

                    // ── PASS 1: regular income/expense (creates accounts) ─────────────────
                    val deferredBalanceSms = mutableListOf<Triple<String, String, Long>>()
                    val pendingPass1 = mutableListOf<PendingTxItem>()

                    for ((body, sender, smsDate) in allRawSms) {
                        val parsed = SmsParser.parseOffline(body, sender, smsDate)
                        if (parsed != null) {
                            val targetTime = parsed.parsedTimestamp ?: smsDate

                            if (parsed.isBalanceUpdate) {
                                // Defer ALL balance/CC-summary SMS to Pass 2
                                // (CC Summary has availableBalance=null — only updates credit limits)
                                deferredBalanceSms.add(Triple(body, sender, smsDate))
                                continue
                            }

                            // Wallet / PF entries are handled exclusively by scanWalletsPfInbox
                            if (isWalletOrPfResult(parsed)) continue

                            // Guard: skip account creation + transaction if sender is blocklisted
                            if (matchesSmsBlocklistPattern(sender)) continue

                            val walletName = ensureAccountExists(parsed.accountRef, sender, body) ?: continue
                            val potentialDuplicates = repository.getPotentialDuplicates(parsed.amount, parsed.type, targetTime)
                            val incomingRef = com.example.utils.SmsParser.getReferenceNumber(body)
                            val duplicate = allTransactions.value.any { existing ->
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
                            if (!duplicate && !matchesSmsBlocklistPattern(walletName) && !matchesSmsBlocklistPattern(sender)) {
                                val finalCategory = applyMerchantRulesToCategory(parsed.title) ?: parsed.category.name
                                // EPFO passbook → store as BALANCE_UPDATE so it doesn't inflate income totals
                                val txType = if (parsed.title == "PF Contribution") "BALANCE_UPDATE" else parsed.type
                                val tx = TransactionEntry(
                                    title = parsed.title,
                                    amount = parsed.amount,
                                    category = finalCategory,
                                    type = txType,
                                    smsSender = sender,
                                    smsBody = body,
                                    timestamp = targetTime,
                                    note = "$body [Acc: $walletName]"
                                )
                                pendingPass1.add(PendingTxItem(tx, incomingRef, walletName, parsed.availableLimit, parsed.accountRef, parsed.availableBalance))
                            }
                        }
                    }

                    // ── PASS 1 post: pair EXPENSE↔INCOME as TRANSFER ────────────────────────
                    // Priority 1 — same reference number (UPI, IMPS): most reliable.
                    // Priority 2 — cross-bank NEFT/RTGS: refs differ between banks, so match
                    //   by same amount + timestamps within 4 hours + different accounts +
                    //   the expense SMS explicitly mentions the income account's last 4 digits
                    //   (e.g. "to the credit of A/c XXXXX9553") AND a bank-channel keyword.
                    val usedInPass1 = mutableSetOf<Int>()
                    for (i in pendingPass1.indices) {
                        if (i in usedInPass1) continue
                        val item = pendingPass1[i]

                        val matchIdx: Int? = if (item.tx.type !in listOf("EXPENSE", "INCOME")) {
                            null
                        } else {
                            // Priority 1: same ref number
                            val refMatch = if (item.refNo != null) {
                                pendingPass1.indices.firstOrNull { j ->
                                    j != i && j !in usedInPass1 &&
                                    pendingPass1[j].refNo == item.refNo &&
                                    pendingPass1[j].tx.amount == item.tx.amount &&
                                    pendingPass1[j].tx.type != item.tx.type &&
                                    pendingPass1[j].tx.type in listOf("EXPENSE", "INCOME")
                                }
                            } else null

                            // Priority 2: cross-bank NEFT/RTGS — no shared ref number
                            val crossBankMatch = if (refMatch == null) {
                                pendingPass1.indices.firstOrNull { j ->
                                    j != i && j !in usedInPass1 &&
                                    pendingPass1[j].tx.amount == item.tx.amount &&
                                    pendingPass1[j].tx.type != item.tx.type &&
                                    pendingPass1[j].tx.type in listOf("EXPENSE", "INCOME") &&
                                    pendingPass1[j].walletName != item.walletName &&
                                    kotlin.math.abs(pendingPass1[j].tx.timestamp - item.tx.timestamp) < 4 * 60 * 60 * 1000L &&
                                    run {
                                        // Safety: expense SMS must explicitly name the destination
                                        // account digits AND contain a bank-transfer channel keyword.
                                        // This prevents accidental pairing of coincidental equal-amount
                                        // income+expense transactions.
                                        val expItem = if (item.tx.type == "EXPENSE") item else pendingPass1[j]
                                        val incItem = if (item.tx.type == "INCOME") item else pendingPass1[j]
                                        val expBody = expItem.tx.smsBody?.lowercase() ?: ""
                                        val incRef  = incItem.parsedAccountRef ?: ""
                                        val hasChannel = expBody.contains("neft") || expBody.contains("imps") ||
                                            expBody.contains("rtgs") || expBody.contains("mobile bank") ||
                                            expBody.contains("net bank")
                                        incRef.length >= 3 && expBody.contains(incRef) && hasChannel
                                    }
                                }
                            } else null

                            refMatch ?: crossBankMatch
                        }

                        if (matchIdx != null) {
                            usedInPass1 += i
                            usedInPass1 += matchIdx
                            val expItem = if (item.tx.type == "EXPENSE") item else pendingPass1[matchIdx]
                            val incItem = if (item.tx.type == "INCOME") item else pendingPass1[matchIdx]
                            val transferTx = expItem.tx.copy(
                                category = "TRANSFER",
                                type = "TRANSFER",
                                note = "${expItem.tx.smsBody ?: ""} [Acc: ${expItem.walletName}][To: ${incItem.walletName}]"
                            )
                            repository.insertTransaction(transferTx)
                            projectedTransactions.add(transferTx)
                            newImportedFingerprints.add("${transferTx.title}|${transferTx.amount}|TRANSFER|${transferTx.timestamp}")
                            matchedCount++
                        } else {
                            usedInPass1 += i
                            repository.insertTransaction(item.tx)
                            adjustCcAvailableLimit(item.tx.note, item.tx.amount, item.tx.type, reverse = false, txTimestamp = item.tx.timestamp)
                            projectedTransactions.add(item.tx)
                            newImportedFingerprints.add("${item.tx.title}|${item.tx.amount}|${item.tx.type}|${item.tx.timestamp}")
                            maybeNotifyBudgetAlert(item.tx, projectedTransactions)
                            if (item.parsedAvailableLimit != null && item.parsedAccountRef != null) {
                                val linkedAccount = repository.getAccountByLastFour(item.parsedAccountRef)
                                if (linkedAccount != null) {
                                    repository.updateAccountAvailableLimit(linkedAccount.id, item.parsedAvailableLimit)
                                }
                            }
                            // If the income SMS also reported an available balance (e.g. "Avl bal INR 30,210.12"),
                            // create a Balance Sync snapshot so the balance is not lost.
                            if (item.tx.type == "INCOME" && item.parsedAvailableBalance != null && item.parsedAccountRef != null) {
                                val incomeAcct = repository.getAccountByLastFour(item.parsedAccountRef)
                                if (incomeAcct != null && incomeAcct.type != "CREDIT_CARD") {
                                    createBalanceAdjustIfNeeded(
                                        incomeAcct, item.parsedAvailableBalance,
                                        item.tx.timestamp + 1L,
                                        item.tx.smsBody ?: "", item.tx.smsSender ?: "",
                                        projectedTransactions
                                    )
                                }
                            }
                            matchedCount++
                        }
                    }

                    // ── PASS 2: balance updates (all accounts now in DB) ───────────────────
                    for ((body, sender, smsDate) in deferredBalanceSms) {
                        val parsed = SmsParser.parseOffline(body, sender, smsDate) ?: continue
                        val targetTime = parsed.parsedTimestamp ?: smsDate
                        val isCcSummary = parsed.totalCreditLimit != null

                        if (isCcSummary) {
                            // CC Summary: update creditLimit + availableLimit, and create a
                            // BALANCE_UPDATE snapshot so computeWalletBalances is properly anchored.
                            if (parsed.accountRef != null) {
                                var linkedAcc = repository.getAccountByRef(parsed.accountRef)
                                if (linkedAcc == null) {
                                    ensureAccountExists(parsed.accountRef, sender, body)
                                    linkedAcc = repository.getAccountByRef(parsed.accountRef)
                                }
                                if (linkedAcc != null && !matchesSmsBlocklistPattern(linkedAcc.name) && !matchesSmsBlocklistPattern(sender)) {
                                    parsed.availableLimit?.let { repository.updateAccountAvailableLimit(linkedAcc.id, it) }
                                    parsed.totalCreditLimit?.let { repository.updateAccountCreditLimit(linkedAcc.id, it) }
                                    // Write a negative-outstanding snapshot so computeWalletBalances
                                    // seeds the CC wallet balance correctly (same pattern as bank Balance Sync)
                                    val availLimit = parsed.availableLimit ?: linkedAcc.availableLimit
                                    val creditLimit = parsed.totalCreditLimit ?: linkedAcc.creditLimit
                                    if (creditLimit > 0) {
                                        val snapshot = availLimit - creditLimit  // negative = outstanding debt
                                        // Only delete+recreate when the snapshot amount changed.
                                        val existingAmount = repository.getLatestBalanceSyncAmount(linkedAcc.name)
                                        if (existingAmount == null || Math.abs(existingAmount - snapshot) >= 0.01) {
                                            repository.deleteAllBalanceSyncForAccount(linkedAcc.name)
                                            // Use actual SMS received date, NOT the parsed statement date from the body
                                            if (createBalanceAdjustIfNeeded(linkedAcc, snapshot, smsDate, body, sender, projectedTransactions)) {
                                                matchedCount++
                                            }
                                        }
                                        // Re-apply post-CC-Summary deltas from Pass 1 transactions.
                                        // Pass 2 overwrote acc.availableLimit with the CC Summary value, which
                                        // loses adjustments for expenses/income that arrived AFTER the CC Summary.
                                        if (parsed.availableLimit != null) {
                                            var adjustedAvail = parsed.availableLimit
                                            for (tx in projectedTransactions) {
                                                if ((tx.type == "EXPENSE" || tx.type == "INCOME")
                                                    && tx.timestamp > smsDate
                                                    && tx.getAccountName() == linkedAcc.name) {
                                                    adjustedAvail += if (tx.type == "EXPENSE") -tx.amount else tx.amount
                                                }
                                            }
                                            if (Math.abs(adjustedAvail - parsed.availableLimit) > 0.01) {
                                                repository.updateAccountAvailableLimit(linkedAcc.id, adjustedAvail)
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (enableBalanceSync.value && parsed.availableBalance != null) {
                            // Regular bank balance SMS: create Balance Sync when Bal Sync is ON
                            val pairs = if (parsed.allBalancePairs.isNotEmpty()) parsed.allBalancePairs
                                        else if (parsed.accountRef != null) listOf(parsed.accountRef to parsed.availableBalance)
                                        else emptyList()
                            for ((ref, bal) in pairs) {
                                var linkedAcc = repository.getAccountByRef(ref)
                                if (linkedAcc != null && !matchesSmsBlocklistPattern(linkedAcc.name) && !matchesSmsBlocklistPattern(sender)) {
                                    // CC accounts report available credit; convert to snapshot format (avail - limit)
                                    // so that the display formula (creditLimit + bal) gives the correct available credit.
                                    val snapshotBal = if (linkedAcc.type == "CREDIT_CARD" && linkedAcc.creditLimit > 0)
                                        bal - linkedAcc.creditLimit else bal
                                    if (createBalanceAdjustIfNeeded(linkedAcc, snapshotBal, targetTime, body, sender, projectedTransactions)) {
                                        matchedCount++
                                    }
                                }
                                parsed.availableLimit?.let {
                                    val acc = linkedAcc ?: repository.getAccountByRef(ref)
                                    acc?.let { a -> repository.updateAccountAvailableLimit(a.id, it) }
                                }
                            }
                        }
                    }
                }

                if (newImportedFingerprints.isNotEmpty()) {
                    _recentlyImportedFingerprints.value = newImportedFingerprints
                }
                if (matchedCount > 0) {
                    _toastMessage.emit("Successfully imported $matchedCount transactions from your Inbox!")
                } else {
                    _toastMessage.emit("Scan complete. No new transaction messages found.")
                }
                cleanupEmptyDefaultAccounts()
            } catch (e: Exception) {
                Log.e(TAG, "SMS scan failed: ${e.message}", e)
                _toastMessage.emit("SMS Scan failed. Ensure SMS read permission is granted.")
            } finally {
                _isSmsParsing.value = false
            }
        }
    }

    // SMS Simulation Engine
    private val _isSmsParsing = MutableStateFlow(false)
    val isSmsParsing: StateFlow<Boolean> = _isSmsParsing.asStateFlow()

    private val _isWalletPfScanning = MutableStateFlow(false)
    val isWalletPfScanning: StateFlow<Boolean> = _isWalletPfScanning.asStateFlow()

    /** Scans inbox for Wallet (Apay, NeuCoins) and EPFO PF Contribution SMS only. */
    fun scanWalletsPfInbox(context: android.content.Context, monthsBack: Int = 3) {
        if (_isWalletPfScanning.value) return  // prevent concurrent scans / race conditions
        viewModelScope.launch {
            _isWalletPfScanning.value = true
            try {
                val contentResolver = context.contentResolver
                val cursor = contentResolver.query(
                    android.net.Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"),
                    null, null, "date DESC"
                )
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.MONTH, -(monthsBack - 1))
                }
                val cutoffTime = cal.timeInMillis
                var matchedCount = 0
                val newImportedFingerprints = mutableSetOf<String>()
                val projectedTransactions = allTransactions.value.toMutableList()
                createdAccountsCache.clear()

                if (cursor != null) {
                    val bodyIndex = cursor.getColumnIndex("body")
                    val addressIndex = cursor.getColumnIndex("address")
                    val dateIndex = cursor.getColumnIndex("date")
                    while (cursor.moveToNext()) {
                        val smsDate = if (dateIndex != -1) cursor.getLong(dateIndex) else System.currentTimeMillis()
                        if (smsDate < cutoffTime) break
                        val body = cursor.getString(bodyIndex) ?: ""
                        val sender = cursor.getString(addressIndex) ?: "Unknown"

                        val parsed = SmsParser.parseOffline(body, sender, smsDate) ?: continue
                        if (!isWalletOrPfResult(parsed)) continue   // only wallet/PF
                        if (matchesSmsBlocklistPattern(sender)) continue

                        val targetTime = parsed.parsedTimestamp ?: smsDate
                        val walletName = ensureAccountExists(parsed.accountRef, sender, body) ?: continue
                        val potentialDuplicates = repository.getPotentialDuplicates(parsed.amount, parsed.type, targetTime)
                        val incomingRef = com.example.utils.SmsParser.getReferenceNumber(body)
                        val duplicate = allTransactions.value.any { existing ->
                            isDuplicateImportedTransaction(existing, body, parsed.title, parsed.amount, parsed.type, targetTime, incomingRef, walletName)
                        } || potentialDuplicates.any { existing ->
                            isDuplicateImportedTransaction(existing, body, parsed.title, parsed.amount, parsed.type, targetTime, incomingRef, walletName)
                        }
                        // Skip PF Contribution INCOME when Bal Sync is ON — balance tracked by the Balance Sync snapshot
                        val skipPfIncome = parsed.title == "PF Contribution" && enableBalanceSync.value
                        if (!duplicate && !skipPfIncome && !matchesSmsBlocklistPattern(walletName)) {
                            val finalCategory = applyMerchantRulesToCategory(parsed.title) ?: parsed.category.name
                            val txType = parsed.type
                            val tx = TransactionEntry(
                                title = parsed.title,
                                amount = parsed.amount,
                                category = finalCategory,
                                type = txType,
                                smsSender = sender,
                                smsBody = body,
                                timestamp = targetTime,
                                note = "$body [Acc: $walletName]"
                            )
                            repository.insertTransaction(tx)
                            adjustCcAvailableLimit(tx.note, tx.amount, tx.type, reverse = false, txTimestamp = tx.timestamp)
                            projectedTransactions.add(tx)
                            newImportedFingerprints.add("${tx.title}|${tx.amount}|${tx.type}|${tx.timestamp}")
                            matchedCount++
                        }
                        // Sync passbook total to PF account balance (only when Bal Sync is enabled)
                        if (parsed.title == "PF Contribution" && parsed.availableBalance != null &&
                            enableBalanceSync.value && !matchesSmsBlocklistPattern(walletName)) {
                            val pfAcc = repository.getAccountByRef(parsed.accountRef ?: "")
                                ?: allAccounts.value.find { it.name == walletName }
                            if (pfAcc != null) {
                                val inserted = createBalanceAdjustIfNeeded(pfAcc, parsed.availableBalance!!, targetTime + 1, body, sender, projectedTransactions)
                                if (inserted) {
                                    newImportedFingerprints.add("Balance Sync|${parsed.availableBalance!!}|BALANCE_UPDATE|${targetTime + 1}")
                                }
                            }
                        }
                    }
                    cursor.close()
                }

                if (newImportedFingerprints.isNotEmpty()) {
                    _recentlyImportedFingerprints.value = newImportedFingerprints
                }
                if (matchedCount > 0) {
                    _toastMessage.emit("Imported $matchedCount wallet/PF transaction(s)!")
                } else {
                    _toastMessage.emit("Wallets & PF scan complete. No new entries found.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wallets/PF scan failed: ${e.message}", e)
                _toastMessage.emit("Wallets & PF scan failed. Check SMS permission.")
            } finally {
                _isWalletPfScanning.value = false
            }
        }
    }

    /**
     * Creates an ADJUST income/expense transaction if the reported balance differs from current computed balance.
     * Returns true if a transaction was created.
     */
    private suspend fun createBalanceAdjustIfNeeded(
        account: Account,
        reportedBalance: Double,
        timestamp: Long,
        smsBody: String,
        sender: String,
        projectedTransactions: MutableList<TransactionEntry>
    ): Boolean {
        // Dedup: if there's already a Balance Sync near this timestamp, check its amount.
        // Same amount → genuine duplicate, skip.  Different amount → stale/wrong entry: replace it.
        val existing = repository.getExactBalanceUpdate(account.name, timestamp)
        if (existing != null) {
            if (Math.abs(existing.amount - reportedBalance) < 0.01) return false  // exact same value
            repository.deleteTransaction(existing.id)  // stale entry with wrong amount — replace below
        }

        // Store the ABSOLUTE reported balance as a point-in-time snapshot.
        // computeWalletBalances will use this as the starting balance and only apply
        // transactions that arrived AFTER this timestamp — no diff calculation needed.
        val tx = TransactionEntry(
            title = "Balance Sync",
            amount = reportedBalance,
            category = "",
            type = "BALANCE_UPDATE",
            smsSender = sender,
            smsBody = smsBody,
            timestamp = timestamp,
            note = "$smsBody [Acc: ${account.name}]"
        )
        repository.insertTransaction(tx)
        projectedTransactions.add(tx)
        return true
    }

    fun analyzePastedSms(
        smsBody: String,
        sender: String,
        forcedKeys: List<String>,
        customPatterns: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            val cleanBody = smsBody.trim()
            val cleanSender = sender.trim().ifBlank { "Manual-Paste" }
            val selectedKeys = forcedKeys.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
            val customPatternList = customPatterns.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val extractedSenderKey = cleanSender
                .split(Regex("[^A-Za-z]+"))
                .firstOrNull { it.length >= 4 }
                ?.take(4)
                ?.lowercase()

            if (cleanBody.isBlank()) {
                _toastMessage.emit("Paste an SMS body before analyzing.")
                return@launch
            }

            val assistedBody = buildString {
                append(cleanBody)
                val helperTerms = buildList {
                    addAll(selectedKeys)
                    if (!extractedSenderKey.isNullOrBlank()) add(extractedSenderKey)
                    customPatternList.forEach { pattern ->
                        val regexMatched = runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(cleanBody) }
                            .getOrDefault(false)
                        if (!regexMatched) {
                            addAll(
                                pattern.split(Regex("[^A-Za-z0-9@._-]+"))
                                    .map { it.trim() }
                                    .filter { it.length > 1 }
                            )
                        } else {
                            add(pattern)
                        }
                    }
                }.distinct()

                if (helperTerms.isNotEmpty()) {
                    append(' ')
                    append(helperTerms.joinToString(" "))
                }
            }

            processManualSmsImport(
                smsBody = cleanBody,
                parserBody = assistedBody,
                sender = cleanSender,
                bypassExclusionFilter = customPatternList.isNotEmpty(),
                progressMessage = if (customPatternList.isEmpty()) {
                    "Analyzing pasted SMS with selected parser keys..."
                } else {
                    "Analyzing pasted SMS with selected keys and custom pattern..."
                }
            )
        }
    }

    fun simulateSmsReceived(smsBody: String, sender: String) {
        analyzePastedSms(smsBody, sender, forcedKeys = listOf("txn"), customPatterns = emptyList())
    }

    /**
     * Scans the device SMS inbox applying custom force-patterns and custom regex patterns in addition
     * to the default SmsParser rules.  For each message that fails the default parse, this function
     * retries with bypassExclusionFilter=true if the body contains at least one of the user-supplied
     * patterns — making the rules "global" for the full inbox run.
     */
    fun scanInboxWithCustomRules(
        context: android.content.Context,
        forcePatterns: List<String>,
        customPatterns: List<String>,
        monthsBack: Int = 3
    ) {
        viewModelScope.launch {
            try {
                val contentResolver = context.contentResolver
                val cursor = contentResolver.query(
                    android.net.Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"),
                    null, null, "date DESC"
                )

                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.DAY_OF_MONTH, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.MONTH, -(monthsBack - 1))
                }
                val cutoffTime = cal.timeInMillis
                var matchedCount = 0
                val newImportedFingerprints = mutableSetOf<String>()
                val projectedTransactions = allTransactions.value.toMutableList()
                // Bug 2: clear stale cache so deleted accounts can be recreated on rescan
                createdAccountsCache.clear()
                // Separate inclusion patterns from exclusion patterns (prefix '!')
                val positiveCustom = customPatterns.filter { !it.trimStart().startsWith("!") }
                val negativePatterns = customPatterns
                    .filter { it.trimStart().startsWith("!") }
                    .map { it.trimStart().removePrefix("!").trim().removeSurrounding("(", ")").trim().lowercase() }
                    .filter { it.isNotBlank() }
                val allPatterns = (forcePatterns + positiveCustom).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

                if (cursor != null) {
                    val bodyIndex = cursor.getColumnIndex("body")
                    val addressIndex = cursor.getColumnIndex("address")
                    val dateIndex = cursor.getColumnIndex("date")

                    while (cursor.moveToNext()) {
                        val smsDate = if (dateIndex != -1) cursor.getLong(dateIndex) else System.currentTimeMillis()
                        if (smsDate < cutoffTime) break

                        val body = cursor.getString(bodyIndex) ?: ""
                        val sender = cursor.getString(addressIndex) ?: "Unknown"

                        // Skip if body matches any exclusion pattern (custom pattern starting with !)
                        if (negativePatterns.isNotEmpty()) {
                            val lb = body.lowercase()
                            val isExcluded = negativePatterns.any { pattern ->
                                runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(body) }
                                    .getOrDefault(lb.contains(pattern))
                            }
                            if (isExcluded) continue
                        }

                        // 1. Standard parse
                        var parsed = com.example.utils.SmsParser.parseOffline(body, sender, smsDate, false)

                        // Check if body explicitly matches a custom pattern (used to decide wallet/PF inclusion)
                        val lowerBodyForMatch = body.lowercase()
                        val customPatternMatchesBody = allPatterns.isNotEmpty() && allPatterns.any { pattern ->
                            runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(body) }
                                .getOrDefault(lowerBodyForMatch.contains(pattern))
                        }

                        // Wallet/PF entries: only import when a custom pattern explicitly matches the body
                        if (parsed != null && isWalletOrPfResult(parsed) && !customPatternMatchesBody) continue

                        // 2. Retry with custom rules if default parse failed and body matches any pattern
                        if (parsed == null && allPatterns.isNotEmpty()) {
                            if (customPatternMatchesBody) {
                                val assistedBody = buildString {
                                    append(body)
                                    val helpers = allPatterns.flatMap { p ->
                                        p.split(Regex("[^A-Za-z0-9@._-]+"))
                                            .map { it.trim() }
                                            .filter { it.length > 1 }
                                    }.distinct()
                                    if (helpers.isNotEmpty()) { append(' '); append(helpers.joinToString(" ")) }
                                }
                                parsed = com.example.utils.SmsParser.parseOffline(assistedBody, sender, smsDate, true)
                            }
                        }

                        if (parsed != null) {
                            val targetTime = parsed.parsedTimestamp ?: smsDate

                            // Handle bank available balance notification SMS (before ensureAccountExists to avoid creating phantom accounts)
                            if (parsed.isBalanceUpdate) {
                                // Balance snapshot: only when Bal Sync ON and actual balance was parsed
                                if (enableBalanceSync.value && parsed.availableBalance != null) {
                                    val pairs = if (parsed.allBalancePairs.isNotEmpty()) parsed.allBalancePairs
                                                else if (parsed.accountRef != null) listOf(parsed.accountRef to parsed.availableBalance)
                                                else emptyList()
                                    for ((ref, bal) in pairs) {
                                        var linkedAcc = repository.getAccountByRef(ref)
                                        if (linkedAcc == null && parsed.totalCreditLimit != null) {
                                            ensureAccountExists(ref, sender, body)
                                            linkedAcc = repository.getAccountByRef(ref)
                                        }
                                        if (linkedAcc != null && !matchesSmsBlocklistPattern(linkedAcc.name) && !matchesSmsBlocklistPattern(sender)) {
                                            if (createBalanceAdjustIfNeeded(linkedAcc, bal, targetTime, body, sender, projectedTransactions)) {
                                                matchedCount++
                                            }
                                        }
                                    }
                                }
                                // Always update CC credit limits (regardless of Bal Sync setting)
                                if (parsed.accountRef != null) {
                                    var limitAcc = repository.getAccountByRef(parsed.accountRef)
                                    if (limitAcc == null && parsed.totalCreditLimit != null) {
                                        ensureAccountExists(parsed.accountRef, sender, body)
                                        limitAcc = repository.getAccountByRef(parsed.accountRef)
                                    }
                                    if (limitAcc != null) {
                                        parsed.availableLimit?.let { repository.updateAccountAvailableLimit(limitAcc.id, it) }
                                        parsed.totalCreditLimit?.let { repository.updateAccountCreditLimit(limitAcc.id, it) }
                                    }
                                }
                                continue
                            }

                            // Guard: skip account creation + transaction if sender is blocklisted
                            if (matchesSmsBlocklistPattern(sender)) continue

                            val walletName = ensureAccountExists(parsed.accountRef, sender, body) ?: continue
                            val potentialDuplicates = repository.getPotentialDuplicates(parsed.amount, parsed.type, targetTime)
                            val incomingRef = com.example.utils.SmsParser.getReferenceNumber(body)
                            val duplicate = allTransactions.value.any { existing ->
                                isDuplicateImportedTransaction(existing, body, parsed.title, parsed.amount, parsed.type, targetTime, incomingRef, walletName)
                            } || potentialDuplicates.any { existing ->
                                isDuplicateImportedTransaction(existing, body, parsed.title, parsed.amount, parsed.type, targetTime, incomingRef, walletName)
                            }
                            // Skip PF Contribution INCOME when Bal Sync is ON
                            val skipPfIncome = parsed.title == "PF Contribution" && enableBalanceSync.value
                            if (!duplicate && !skipPfIncome && !matchesSmsBlocklistPattern(walletName) && !matchesSmsBlocklistPattern(sender)) {
                                val finalCategory = applyMerchantRulesToCategory(parsed.title) ?: parsed.category.name
                                val txType = parsed.type
                                val tx = TransactionEntry(
                                    title = parsed.title,
                                    amount = parsed.amount,
                                    category = finalCategory,
                                    type = txType,
                                    smsSender = sender,
                                    smsBody = body,
                                    timestamp = targetTime,
                                    note = "$body [Acc: $walletName]"
                                )
                                repository.insertTransaction(tx)
                                adjustCcAvailableLimit(tx.note, tx.amount, tx.type, reverse = false, txTimestamp = tx.timestamp)
                                newImportedFingerprints.add("${tx.title}|${tx.amount}|${tx.type}|${tx.timestamp}")
                                projectedTransactions.add(tx)
                                matchedCount++
                            }
                            // Sync passbook total to PF account balance (only when Bal Sync is enabled)
                            if (parsed.title == "PF Contribution" && parsed.availableBalance != null &&
                                enableBalanceSync.value && !matchesSmsBlocklistPattern(walletName) && !matchesSmsBlocklistPattern(sender)) {
                                val pfAcc = repository.getAccountByRef(parsed.accountRef ?: "")
                                    ?: allAccounts.value.find { it.name == walletName }
                                if (pfAcc != null) {
                                    createBalanceAdjustIfNeeded(pfAcc, parsed.availableBalance!!, targetTime + 1, body, sender, projectedTransactions)
                                }
                            }
                        }
                    }
                    cursor.close()
                }

                if (newImportedFingerprints.isNotEmpty()) {
                    _recentlyImportedFingerprints.value = newImportedFingerprints
                }

                val rulesDesc = if (allPatterns.isNotEmpty()) " (with ${allPatterns.size} custom rule${if (allPatterns.size > 1) "s" else ""})" else ""
                if (matchedCount > 0) {
                    _toastMessage.emit("Imported $matchedCount transaction(s) from inbox$rulesDesc!")
                } else {
                    _toastMessage.emit("Scan complete$rulesDesc. No new transactions matched.")
                }
                cleanupEmptyDefaultAccounts()
            } catch (e: Exception) {
                Log.e(TAG, "Custom rule inbox scan failed: ${e.message}", e)
                _toastMessage.emit("SMS scan failed. Ensure SMS read permission is granted.")
            }
        }
    }

    private suspend fun cleanupEmptyDefaultAccounts() {
        val defaultNames = setOf("Cash Wallet", "Savings Account", "Credit Card", "Digital Wallet")
        val accounts = repository.allAccounts.first().filter { it.name in defaultNames }
        for (acc in accounts) {
            if (repository.countTransactionsForAccount(acc.name) == 0) {
                repository.deleteAccount(acc.id)
            }
        }
    }

    private suspend fun processManualSmsImport(
        smsBody: String,
        parserBody: String,
        sender: String,
        progressMessage: String,
        bypassExclusionFilter: Boolean = false
    ) {
        _isSmsParsing.value = true
        val apiKey = BuildConfig.GEMINI_API_KEY

        // Always try offline parser first — it handles CC Summary, balance updates, and most
        // bank transactions reliably without a network round-trip.  Only fall back to Gemini
        // when offline returns null AND Gemini is available.
        _toastMessage.emit(progressMessage)
        val parsed = withContext(Dispatchers.Default) {
            SmsParser.parseOffline(parserBody, sender, System.currentTimeMillis(), bypassExclusionFilter)
        } ?: if (isGeminiAvailable) {
            _toastMessage.emit("Offline parser found nothing — retrying with Gemini AI...")
            SmsParser.parseWithGemini(parserBody, sender, apiKey, System.currentTimeMillis())
        } else null

        _isSmsParsing.value = false

        if (parsed == null) {
            _toastMessage.emit("Could not parse this SMS. Check the Sender ID and SMS body format.")
            return
        }

        val targetTime = parsed.parsedTimestamp ?: System.currentTimeMillis()

        // CC Summary / balance-notification SMS: update limits only, no Balance Sync entry
        if (parsed.isBalanceUpdate) {
            if (parsed.accountRef != null) {
                // Create CC account if not yet in DB
                var limitAcc = repository.getAccountByRef(parsed.accountRef)
                if (limitAcc == null && parsed.totalCreditLimit != null) {
                    ensureAccountExists(parsed.accountRef, sender, smsBody)
                    limitAcc = repository.getAccountByRef(parsed.accountRef)
                }
                if (limitAcc != null) {
                    val isCcSummary = parsed.totalCreditLimit != null
                    if (isCcSummary) {
                        // CC Summary: update stored limits AND create a negative-outstanding
                        // BALANCE_UPDATE snapshot so computeWalletBalances stays anchored.
                        parsed.availableLimit?.let { repository.updateAccountAvailableLimit(limitAcc.id, it) }
                        parsed.totalCreditLimit?.let { repository.updateAccountCreditLimit(limitAcc.id, it) }
                        val availLimit = parsed.availableLimit ?: limitAcc.availableLimit
                        val creditLimit = parsed.totalCreditLimit ?: limitAcc.creditLimit
                        if (creditLimit > 0) {
                            val snapshot = availLimit - creditLimit  // negative = outstanding debt
                            // Delete any stale CC snapshot first — new statement supersedes all previous
                            repository.deleteAllBalanceSyncForAccount(limitAcc.name)
                            // Use current time for manual imports — NOT the parsed statement date from the body
                            val projected = mutableListOf<TransactionEntry>()
                            createBalanceAdjustIfNeeded(limitAcc, snapshot, System.currentTimeMillis(), smsBody, sender, projected)
                        }
                        _toastMessage.emit("CC limits + balance snapshot updated for ${limitAcc.name}: Due ₹${String.format("%.2f", (limitAcc.creditLimit - (parsed.availableLimit ?: limitAcc.availableLimit)).coerceAtLeast(0.0))}")
                    } else if (enableBalanceSync.value && parsed.availableBalance != null) {
                        // Regular bank balance SMS: create Balance Sync when Bal Sync is ON
                        val projected = mutableListOf<TransactionEntry>()
                        // CC accounts report available credit; convert to snapshot format (avail - limit)
                        val snapshotBal = if (limitAcc.type == "CREDIT_CARD" && limitAcc.creditLimit > 0)
                            parsed.availableBalance - limitAcc.creditLimit else parsed.availableBalance
                        createBalanceAdjustIfNeeded(limitAcc, snapshotBal, targetTime, smsBody, sender, projected)
                        parsed.availableLimit?.let { repository.updateAccountAvailableLimit(limitAcc.id, it) }
                        _toastMessage.emit("Balance updated for ${limitAcc.name}.")
                    } else {
                        _toastMessage.emit("Balance Sync is off — limits noted for ${limitAcc.name}.")
                    }
                } else {
                    _toastMessage.emit("Account not found for ref ${parsed.accountRef}.")
                }
            }
            return
        }

            val walletName = ensureAccountExists(parsed.accountRef, sender, smsBody)
            if (walletName == null) {
                _toastMessage.emit("SMS import skipped because this wallet is blocked from tracking.")
                return
            }
            val potentialDuplicates = repository.getPotentialDuplicates(parsed.amount, parsed.type, targetTime)
            val incomingRef = com.example.utils.SmsParser.getReferenceNumber(smsBody)
            val duplicate = allTransactions.value.any { existing ->
                isDuplicateImportedTransaction(
                    existing = existing,
                    incomingSmsBody = smsBody,
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
                    incomingSmsBody = smsBody,
                    incomingTitle = parsed.title,
                    incomingAmount = parsed.amount,
                    incomingType = parsed.type,
                    incomingTimestamp = targetTime,
                    incomingReference = incomingRef,
                    incomingAccountName = walletName
                )
            }
            if (duplicate) {
                // For EPFO with Bal Sync ON: still sync passbook balance even if contribution record is a duplicate
                if (parsed.title == "PF Contribution" && parsed.availableBalance != null && enableBalanceSync.value) {
                    val pfAcc = repository.getAccountByRef(parsed.accountRef ?: "")
                        ?: allAccounts.value.find { it.name == walletName }
                    if (pfAcc != null) {
                        val projected = mutableListOf<TransactionEntry>()
                        createBalanceAdjustIfNeeded(pfAcc, parsed.availableBalance!!, targetTime + 1, smsBody, sender, projected)
                    }
                }
                _toastMessage.emit("Detected potential duplicate transaction of ₹${parsed.amount} at ${parsed.title}. Skipped.")
                return
            }

            val learnedCategory = if (parsed.title.isNotBlank()) repository.getLearnedCategoryForTitle(parsed.title) else null
            // PF Contribution is always INCOME
            val txType = parsed.type
            // Skip PF Contribution INCOME when Bal Sync is ON — balance tracked by Balance Sync snapshot
            val skipPfIncome = parsed.title == "PF Contribution" && enableBalanceSync.value
            if (!skipPfIncome) {
                val tx = TransactionEntry(
                    title = parsed.title,
                    amount = parsed.amount,
                    category = learnedCategory ?: parsed.category.name,
                    type = txType,
                    smsSender = sender,
                    smsBody = smsBody,
                    timestamp = targetTime,
                    note = "$smsBody [Acc: $walletName]"
                )
                repository.insertTransaction(tx)
                adjustCcAvailableLimit(tx.note, tx.amount, tx.type, reverse = false, txTimestamp = tx.timestamp)
                _recentlyImportedFingerprints.value = setOf("${tx.title}|${tx.amount}|${tx.type}|${tx.timestamp}")
                maybeNotifyBudgetAlert(tx, allTransactions.value + tx)
            }
            // Sync passbook total to PF account balance (only when Bal Sync is enabled)
            if (parsed.title == "PF Contribution" && parsed.availableBalance != null && enableBalanceSync.value) {
                val pfAcc = repository.getAccountByRef(parsed.accountRef ?: "")
                    ?: allAccounts.value.find { it.name == walletName }
                if (pfAcc != null) {
                    val projected = mutableListOf<TransactionEntry>()
                    createBalanceAdjustIfNeeded(pfAcc, parsed.availableBalance!!, targetTime + 1, smsBody, sender, projected)
                }
            }

            // Update available + total credit limits for CC accounts
            if (parsed.accountRef != null) {
                val limitAcc = repository.getAccountByRef(parsed.accountRef)
                if (limitAcc != null) {
                    parsed.availableLimit?.let { repository.updateAccountAvailableLimit(limitAcc.id, it) }
                    parsed.totalCreditLimit?.let { repository.updateAccountCreditLimit(limitAcc.id, it) }
                }
            }

            _toastMessage.emit("Auto-Tracked Expense: ₹${parsed.amount} at ${parsed.title}")
    }

    // ── CSV Backup / Restore ──────────────────────────────────────────────────

    /** Writes accounts + transactions as a plain CSV to the URI chosen by the user (SAF CreateDocument). */
    fun exportBackupToUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val sb = StringBuilder()

                // ── ACCOUNTS section ─────────────────────────────
                sb.appendLine("## ACCOUNTS")
                sb.appendLine("Name,Type,LastFour,CreditLimit,Balance")
                for (acc in allAccounts.value) {
                    val name = acc.name.replace(",", ";")
                    sb.appendLine("${name},${acc.type},${acc.lastFour ?: ""},${acc.creditLimit},${acc.balance}")
                }
                sb.appendLine()

                // ── TRANSACTIONS section ──────────────────────────
                sb.appendLine("## TRANSACTIONS")
                sb.appendLine("Date,Title,Amount,Category,Type,Account")
                for (tx in allTransactions.value) {
                    val date = dateFormat.format(Date(tx.timestamp))
                    val title = tx.title.replace(",", ";").replace("\"", "")
                    val accountName = tx.getAccountName().replace(",", ";")
                    sb.appendLine("${date},${title},${tx.amount},${tx.category},${tx.type},${accountName}")
                }
                sb.appendLine()

                // ── BUDGETS section ───────────────────────────────
                sb.appendLine("## BUDGETS")
                sb.appendLine("Category,AmountLimit,MonthYear")
                val budgets = repository.getAllBudgetsOnce()
                for (b in budgets) {
                    val cat = b.category.replace(",", ";")
                    sb.appendLine("${cat},${b.amountLimit},${b.monthYear}")
                }

                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                val txCount = allTransactions.value.size
                val budgetCount = budgets.size
                _toastMessage.emit("Backup saved — ${allAccounts.value.size} accounts, $txCount transactions, $budgetCount budgets.")
            } catch (e: Exception) {
                Log.e(TAG, "Export backup failed: ${e.message}", e)
                _toastMessage.emit("Export failed: ${e.message}")
            }
        }
    }

    /** Reads a CSV backup (created by exportBackupToUri) and fully restores accounts + transactions. */
    fun restoreFromBackupUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lines = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readLines()
                } ?: run {
                    _toastMessage.emit("Could not open backup file.")
                    return@launch
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                var section = ""
                var accountsRestored = 0
                var txRestored = 0
                var budgetsRestored = 0

                // Wipe existing data
                repository.clearTransactions()
                repository.clearBudgets()
                repository.clearAccounts()
                createdAccountsCache.clear()

                for (line in lines) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("## ") -> section = trimmed.removePrefix("## ")
                        trimmed.isBlank() ||
                        trimmed.startsWith("Name,") ||
                        trimmed.startsWith("Date,") ||
                        trimmed.startsWith("Category,") -> { /* header / blank – skip */ }
                        section == "ACCOUNTS" -> {
                            val cols = trimmed.split(",")
                            if (cols.size >= 5) {
                                repository.insertAccount(
                                    Account(
                                        name = cols[0].trim(),
                                        type = cols[1].trim(),
                                        lastFour = cols[2].trim().ifBlank { null },
                                        creditLimit = cols[3].trim().toDoubleOrNull() ?: 0.0,
                                        balance = cols[4].trim().toDoubleOrNull() ?: 0.0
                                    )
                                )
                                accountsRestored++
                            }
                        }
                        section == "TRANSACTIONS" -> {
                            // Date,Title,Amount,Category,Type,Account — split on first 5 commas only
                            val parts = trimmed.split(",", limit = 6)
                            if (parts.size == 6) {
                                val timestamp = try {
                                    dateFormat.parse(parts[0].trim())?.time ?: System.currentTimeMillis()
                                } catch (e: Exception) { System.currentTimeMillis() }
                                repository.insertTransaction(
                                    TransactionEntry(
                                        title = parts[1].trim(),
                                        amount = parts[2].trim().toDoubleOrNull() ?: 0.0,
                                        category = parts[3].trim(),
                                        type = parts[4].trim(),
                                        timestamp = timestamp,
                                        note = "[Acc: ${parts[5].trim()}]"
                                    )
                                )
                                txRestored++
                            }
                        }
                        section == "BUDGETS" -> {
                            val parts = trimmed.split(",", limit = 3)
                            if (parts.size == 3) {
                                repository.insertBudget(
                                    BudgetEntry(
                                        id = 0,
                                        category = parts[0].trim(),
                                        amountLimit = parts[1].trim().toDoubleOrNull() ?: 0.0,
                                        monthYear = parts[2].trim()
                                    )
                                )
                                budgetsRestored++
                            }
                        }
                    }
                }
                _toastMessage.emit("Restored $accountsRestored accounts, $txRestored transactions and $budgetsRestored budgets.")
            } catch (e: Exception) {
                Log.e(TAG, "Restore backup failed: ${e.message}", e)
                _toastMessage.emit("Restore failed: ${e.message}")
            }
        }
    }

    // Cryptographic Secure Local Backup (legacy — retained for reference)
    fun createSecureBackup(password: String): String? {
        if (password.length < 4) {
            viewModelScope.launch { _toastMessage.emit("Password must be at least 4 characters") }
            return null
        }
        try {
            val backupJson = JSONObject()
            
            // Array of transactions
            val txArray = JSONArray()
            for (tx in allTransactions.value) {
                val txObj = JSONObject().apply {
                    put("title", tx.title)
                    put("amount", tx.amount)
                    put("category", tx.category)
                    put("timestamp", tx.timestamp)
                    put("type", tx.type)
                    put("smsSender", tx.smsSender ?: JSONObject.NULL)
                    put("smsBody", tx.smsBody ?: JSONObject.NULL)
                    put("note", tx.note ?: JSONObject.NULL)
                }
                txArray.put(txObj)
            }
            backupJson.put("transactions", txArray)

            // Array of budgets (fetch all from db synchronously or using a combined state)
            // For simple offline restore, we can backup active monthly budgets
            val budgetArray = JSONArray()
            for (b in monthlyBudgets.value) {
                val bObj = JSONObject().apply {
                    put("category", b.category)
                    put("amountLimit", b.amountLimit)
                    put("monthYear", b.monthYear)
                }
                budgetArray.put(bObj)
            }
            backupJson.put("budgets", budgetArray)

            val plainJsonStr = backupJson.toString()
            val encrypted = SecurityUtils.encrypt(plainJsonStr, password)
            _backupString.value = encrypted
            viewModelScope.launch { _toastMessage.emit("Secure encrypted backup metadata generated!") }
            return encrypted
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed: ${e.message}", e)
            viewModelScope.launch { _toastMessage.emit("Backup failed: ${e.message}") }
        }
        return null
    }

    fun restoreFromSecureBackup(encryptedB64: String, password: String): Boolean {
        if (encryptedB64.isEmpty() || password.isEmpty()) {
            viewModelScope.launch { _toastMessage.emit("Please enter both backup string and passphrase") }
            return false
        }
        return try {
            val decryptedPlainStr = SecurityUtils.decrypt(encryptedB64, password)
            val backupJson = JSONObject(decryptedPlainStr)
            
            val txArray = backupJson.optJSONArray("transactions") ?: JSONArray()
            val budgetArray = backupJson.optJSONArray("budgets") ?: JSONArray()

            viewModelScope.launch {
                // Clear existing
                repository.clearTransactions()
                repository.clearBudgets()

                // Restore Transactions
                for (i in 0 until txArray.length()) {
                    val item = txArray.getJSONObject(i)
                    val tx = TransactionEntry(
                        title = item.getString("title"),
                        amount = item.getDouble("amount"),
                        category = item.getString("category"),
                        timestamp = item.getLong("timestamp"),
                        type = item.optString("type", "EXPENSE"),
                        smsSender = if (item.isNull("smsSender")) null else item.getString("smsSender"),
                        smsBody = if (item.isNull("smsBody")) null else item.getString("smsBody"),
                        note = if (item.isNull("note")) null else item.getString("note")
                    )
                    repository.insertTransaction(tx)
                }

                // Restore Budgets
                for (i in 0 until budgetArray.length()) {
                    val item = budgetArray.getJSONObject(i)
                    val b = BudgetEntry(
                        category = item.getString("category"),
                        amountLimit = item.getDouble("amountLimit"),
                        monthYear = item.getString("monthYear")
                    )
                    repository.insertBudget(b)
                }

                _toastMessage.emit("Cryptography restored: ${txArray.length()} transactions & ${budgetArray.length()} budgets successfully!")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Decryption/Restore failed due to incorrect password or corrupted checksums", e)
            viewModelScope.launch { _toastMessage.emit("Error: Decryption failed. Please check passphrase.") }
            false
        }
    }
}

enum class DisplayMode {
    DAILY, WEEKLY, MONTHLY, THREE_MONTHS, SIX_MONTHS, ONE_YEAR
}
