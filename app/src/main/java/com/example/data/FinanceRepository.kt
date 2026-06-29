package com.example.data

import kotlinx.coroutines.flow.Flow

class FinanceRepository(private val financeDao: FinanceDao) {
    // Transactions
    val allTransactions: Flow<List<TransactionEntry>> = financeDao.getAllTransactions()

    fun getBudgetsForMonth(monthYear: String): Flow<List<BudgetEntry>> =
        financeDao.getBudgetsForMonth(monthYear)

    suspend fun insertTransaction(transaction: TransactionEntry) {
        financeDao.insertTransaction(transaction)
    }

    suspend fun countPotentialDuplicates(amount: Double, type: String, timestamp: Long): Int {
        return financeDao.countPotentialDuplicates(amount, type, timestamp)
    }

    suspend fun getPotentialDuplicates(amount: Double, type: String, timestamp: Long): List<TransactionEntry> {
        return financeDao.getPotentialDuplicates(amount, type, timestamp)
    }

    suspend fun countExactBalanceUpdates(accountName: String, timestamp: Long): Int {
        return financeDao.countExactBalanceUpdates(accountName, timestamp)
    }

    suspend fun getExactBalanceUpdate(accountName: String, timestamp: Long): TransactionEntry? {
        return financeDao.getExactBalanceUpdate(accountName, timestamp)
    }

    suspend fun updateTransaction(transaction: TransactionEntry) {
        financeDao.updateTransaction(transaction)
    }

    suspend fun deleteTransaction(id: Int) {
        financeDao.deleteTransactionById(id)
    }

    suspend fun clearTransactions() {
        financeDao.clearAllTransactions()
    }

    suspend fun deleteTransactionsInRange(startTime: Long, endTime: Long) {
        financeDao.deleteTransactionsInRange(startTime, endTime)
    }

    suspend fun deleteTransactionsByCategoryInRange(category: String, startTime: Long, endTime: Long) {
        financeDao.deleteTransactionsByCategoryInRange(category, startTime, endTime)
    }

    // Budgets
    suspend fun insertBudget(budget: BudgetEntry) {
        financeDao.insertBudget(budget)
    }

    suspend fun deleteBudget(id: Int) {
        financeDao.deleteBudgetById(id)
    }

    suspend fun clearBudgets() {
        financeDao.clearAllBudgets()
    }

    suspend fun getAllBudgetsOnce(): List<BudgetEntry> = financeDao.getAllBudgetsOnce()

    // Custom Categories
    val allCustomCategories: Flow<List<CustomCategory>> = financeDao.getAllCustomCategories()

    suspend fun insertCustomCategory(category: CustomCategory) {
        financeDao.insertCustomCategory(category)
    }

    suspend fun getCustomCategoryByName(name: String): CustomCategory? =
        financeDao.getCustomCategoryByName(name)

    suspend fun updateCategoryReferences(oldCategory: String, newCategory: String) {
        financeDao.updateTransactionCategory(oldCategory, newCategory)
        financeDao.updateBudgetCategory(oldCategory, newCategory)
    }

    suspend fun recategorizeByTitle(title: String, newCategory: String, excludeId: Int) {
        financeDao.recategorizeByTitle(title, newCategory, excludeId)
    }

    suspend fun getLearnedCategoryForTitle(title: String): String? {
        return financeDao.getLearnedCategoryForTitle(title)
    }

    suspend fun deleteCustomCategory(id: Int) {
        financeDao.deleteCustomCategoryById(id)
    }

    // Recurring Transactions
    val allRecurringTransactions: Flow<List<RecurringTransaction>> = financeDao.getAllRecurringTransactions()

    suspend fun getAllRecurringTransactionsSync(): List<RecurringTransaction> {
        return financeDao.getAllRecurringTransactionsSync()
    }

    suspend fun insertRecurringTransaction(rec: RecurringTransaction) {
        financeDao.insertRecurringTransaction(rec)
    }

    suspend fun updateRecurringTransaction(rec: RecurringTransaction) {
        financeDao.updateRecurringTransaction(rec)
    }

    suspend fun deleteRecurringTransaction(id: Int) {
        financeDao.deleteRecurringTransactionById(id)
    }

    // Accounts
    val allAccounts: Flow<List<Account>> = financeDao.getAllAccounts()

    suspend fun insertAccount(account: Account) {
        financeDao.insertAccount(account)
    }

    suspend fun updateAccount(account: Account) {
        financeDao.updateAccount(account)
    }

    suspend fun deleteAccount(id: String) {
        // Cascade-delete all transactions linked to this account via note "[Acc: name]"
        val account = financeDao.getAccountById(id)
        if (account != null) {
            financeDao.deleteTransactionsByNotePattern("%[Acc: ${account.name}]%")
        }
        financeDao.deleteAccountById(id)
    }

    suspend fun getAccountById(id: String): Account? {
        return financeDao.getAccountById(id)
    }

    suspend fun getAccountByLastFour(lastFour: String): Account? {
        return financeDao.getAccountByLastFour(lastFour)
    }

    suspend fun getAccountByRef(ref: String): Account? {
        return financeDao.getAccountByLastFour(ref)
            ?: if (ref.length == 3) financeDao.getAccountByLastFourSuffix(ref) else null
    }

    suspend fun getAccountByName(name: String): Account? {
        return financeDao.getAccountByName(name)
    }

    suspend fun deleteAllBalanceSyncForAccount(accountName: String) {
        financeDao.deleteAllBalanceSyncForAccount(accountName)
    }

    suspend fun getLatestBalanceSyncAmount(accountName: String): Double? {
        return financeDao.getLatestBalanceSyncAmount(accountName)
    }

    suspend fun getLatestBalanceSyncTimestamp(accountName: String): Long? {
        return financeDao.getLatestBalanceSyncTimestamp(accountName)
    }

    suspend fun clearAccounts() {
        financeDao.clearAllAccounts()
    }

    suspend fun updateAccountAvailableLimit(accountId: String, availableLimit: Double) {
        financeDao.updateAccountAvailableLimit(accountId, availableLimit)
    }

    suspend fun updateAccountCreditLimit(accountId: String, creditLimit: Double) {
        financeDao.updateAccountCreditLimit(accountId, creditLimit)
    }
}

