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

    // Custom Categories
    val allCustomCategories: Flow<List<CustomCategory>> = financeDao.getAllCustomCategories()

    suspend fun insertCustomCategory(category: CustomCategory) {
        financeDao.insertCustomCategory(category)
    }

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
        financeDao.deleteAccountById(id)
    }

    suspend fun getAccountById(id: String): Account? {
        return financeDao.getAccountById(id)
    }

    suspend fun getAccountByLastFour(lastFour: String): Account? {
        return financeDao.getAccountByLastFour(lastFour)
    }

    suspend fun getAccountByName(name: String): Account? {
        return financeDao.getAccountByName(name)
    }

    suspend fun clearAccounts() {
        financeDao.clearAllAccounts()
    }

    suspend fun updateAccountAvailableLimit(accountId: String, availableLimit: Double) {
        financeDao.updateAccountAvailableLimit(accountId, availableLimit)
    }
}

