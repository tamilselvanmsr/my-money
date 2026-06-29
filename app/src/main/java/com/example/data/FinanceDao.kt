package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {
    // Transactions
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntry)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntry)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Int)

    @Query("DELETE FROM transactions")
    suspend fun clearAllTransactions()

    @Query("DELETE FROM transactions WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun deleteTransactionsInRange(startTime: Long, endTime: Long)

    @Query("DELETE FROM transactions WHERE category = :category AND timestamp BETWEEN :startTime AND :endTime")
    suspend fun deleteTransactionsByCategoryInRange(category: String, startTime: Long, endTime: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE smsBody = :smsBody")
    suspend fun countTransactionsWithSmsBody(smsBody: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE amount = :amount AND type = :type AND ABS(timestamp - :timestamp) < 300000")
    suspend fun countPotentialDuplicates(amount: Double, type: String, timestamp: Long): Int

    @Query("SELECT * FROM transactions WHERE amount = :amount AND type = :type AND ABS(timestamp - :timestamp) < 300000")
    suspend fun getPotentialDuplicates(amount: Double, type: String, timestamp: Long): List<TransactionEntry>

    @Query("SELECT COUNT(*) FROM transactions WHERE type = 'BALANCE_UPDATE' AND title = 'Balance Sync' AND note LIKE '%[Acc: ' || :accountName || ']%' AND ABS(timestamp - :timestamp) < 1000")
    suspend fun countExactBalanceUpdates(accountName: String, timestamp: Long): Int

    @Query("SELECT * FROM transactions WHERE type = 'BALANCE_UPDATE' AND title = 'Balance Sync' AND note LIKE '%[Acc: ' || :accountName || ']%' AND ABS(timestamp - :timestamp) < 1000 LIMIT 1")
    suspend fun getExactBalanceUpdate(accountName: String, timestamp: Long): TransactionEntry?

    // Budgets
    @Query("SELECT * FROM budgets WHERE monthYear = :monthYear")
    fun getBudgetsForMonth(monthYear: String): Flow<List<BudgetEntry>>

    @Query("SELECT * FROM budgets")
    suspend fun getAllBudgetsOnce(): List<BudgetEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: BudgetEntry)

    @Query("UPDATE transactions SET category = :newCategory WHERE TRIM(LOWER(title)) = TRIM(LOWER(:title)) AND id != :excludeId")
    suspend fun recategorizeByTitle(title: String, newCategory: String, excludeId: Int)

    @Query("SELECT category FROM transactions WHERE TRIM(LOWER(title)) = TRIM(LOWER(:title)) ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLearnedCategoryForTitle(title: String): String?

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteBudgetById(id: Int)

    @Query("DELETE FROM budgets")
    suspend fun clearAllBudgets()

    // Custom Categories
    @Query("SELECT * FROM custom_categories ORDER BY name ASC")
    fun getAllCustomCategories(): Flow<List<CustomCategory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomCategory(category: CustomCategory)

    @Query("SELECT * FROM custom_categories WHERE name = :name LIMIT 1")
    suspend fun getCustomCategoryByName(name: String): CustomCategory?

    @Query("UPDATE transactions SET category = :newCategory WHERE category = :oldCategory")
    suspend fun updateTransactionCategory(oldCategory: String, newCategory: String)

    @Query("UPDATE budgets SET category = :newCategory WHERE category = :oldCategory")
    suspend fun updateBudgetCategory(oldCategory: String, newCategory: String)

    @Query("DELETE FROM custom_categories WHERE id = :id")
    suspend fun deleteCustomCategoryById(id: Int)

    // Recurring Transactions
    @Query("SELECT * FROM recurring_transactions")
    fun getAllRecurringTransactions(): Flow<List<RecurringTransaction>>

    @Query("SELECT * FROM recurring_transactions")
    suspend fun getAllRecurringTransactionsSync(): List<RecurringTransaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurringTransaction(recurring: RecurringTransaction)

    @Update
    suspend fun updateRecurringTransaction(recurring: RecurringTransaction)

    @Query("DELETE FROM recurring_transactions WHERE id = :id")
    suspend fun deleteRecurringTransactionById(id: Int)

    // Accounts
    @Query("SELECT * FROM accounts ORDER BY name ASC")
    fun getAllAccounts(): Flow<List<Account>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: Account)

    @Update
    suspend fun updateAccount(account: Account)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccountById(id: String)

    @Query("DELETE FROM transactions WHERE note LIKE :pattern")
    suspend fun deleteTransactionsByNotePattern(pattern: String)

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getAccountById(id: String): Account?

    @Query("SELECT * FROM accounts WHERE lastFour = :lastFour LIMIT 1")
    suspend fun getAccountByLastFour(lastFour: String): Account?

    @Query("SELECT * FROM accounts WHERE lastFour LIKE '%' || :suffix LIMIT 1")
    suspend fun getAccountByLastFourSuffix(suffix: String): Account?

    @Query("SELECT * FROM accounts WHERE name = :name LIMIT 1")
    suspend fun getAccountByName(name: String): Account?

    @Query("DELETE FROM accounts")
    suspend fun clearAllAccounts()

    @Query("UPDATE accounts SET availableLimit = :availableLimit WHERE id = :accountId")
    suspend fun updateAccountAvailableLimit(accountId: String, availableLimit: Double)

    @Query("UPDATE accounts SET creditLimit = :creditLimit WHERE id = :accountId")
    suspend fun updateAccountCreditLimit(accountId: String, creditLimit: Double)

    /** Removes ALL 'Balance Sync' BALANCE_UPDATE snapshots for one account.
     *  Used for CC Summary: each new statement supersedes all previous snapshots. */
    @Query("DELETE FROM transactions WHERE type = 'BALANCE_UPDATE' AND title = 'Balance Sync' AND note LIKE '%[Acc: ' || :accountName || ']%'")
    suspend fun deleteAllBalanceSyncForAccount(accountName: String)

    /** Returns the most-recent Balance Sync amount for an account (used to skip re-import when unchanged). */
    @Query("SELECT amount FROM transactions WHERE type = 'BALANCE_UPDATE' AND title = 'Balance Sync' AND note LIKE '%[Acc: ' || :accountName || ']%' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBalanceSyncAmount(accountName: String): Double?

    /** Returns the timestamp of the most-recent Balance Sync for an account.
     *  Used by adjustCcAvailableLimit to skip pre-CC-Summary transactions. */
    @Query("SELECT timestamp FROM transactions WHERE type = 'BALANCE_UPDATE' AND title = 'Balance Sync' AND note LIKE '%[Acc: ' || :accountName || ']%' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestBalanceSyncTimestamp(accountName: String): Long?
}

