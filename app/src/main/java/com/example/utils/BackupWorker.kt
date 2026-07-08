package com.example.utils

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.example.data.FinanceDatabase
import com.example.data.FinanceRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * CoroutineWorker that runs a full encrypted JSON backup without needing the ViewModel.
 * Scheduled via [BackupScheduler] to fire at midnight based on the user's chosen frequency.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "BackupWorker"
        private const val BACKUP_KEY = "AutoLedger_Local_Backup_AES256"
        const val WORK_NAME = "autoledger_auto_backup"
    }

    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
            val customPath = prefs.getString("custom_backup_path", "") ?: ""

            val db   = FinanceDatabase.getDatabase(applicationContext)
            val repo = FinanceRepository(db.financeDao())

            val accounts    = repo.getAllAccountsOnce()
            val transactions = repo.getAllTransactionsOnce()
            val budgets     = repo.getAllBudgetsOnce()
            val customCats  = repo.getAllCustomCategoriesOnce()

            // Skip if DB is empty — likely first launch before any data is loaded
            if (transactions.isEmpty() && accounts.isEmpty()) {
                Log.w(TAG, "Auto-backup skipped: no data in DB yet")
                return Result.retry()
            }

            val accArray = JSONArray().apply {
                accounts.forEach { acc ->
                    put(JSONObject().apply {
                        put("name", acc.name); put("type", acc.type)
                        put("lastFour", acc.lastFour ?: JSONObject.NULL)
                        put("creditLimit", acc.creditLimit); put("balance", acc.balance)
                    })
                }
            }
            val txArray = JSONArray().apply {
                transactions.forEach { tx ->
                    put(JSONObject().apply {
                        put("title", tx.title); put("amount", tx.amount)
                        put("category", tx.category); put("type", tx.type)
                        put("timestamp", tx.timestamp); put("note", tx.note ?: JSONObject.NULL)
                    })
                }
            }
            val budgetArray = JSONArray().apply {
                budgets.forEach { b ->
                    put(JSONObject().apply {
                        put("category", b.category); put("amountLimit", b.amountLimit); put("monthYear", b.monthYear)
                    })
                }
            }
            val ccArray = JSONArray().apply {
                customCats.forEach { cc ->
                    put(JSONObject().apply {
                        put("name", cc.name); put("iconName", cc.iconName); put("colorHex", cc.colorHex)
                    })
                }
            }

            val payload = JSONObject().apply {
                put("accounts", accArray); put("transactions", txArray)
                put("budgets", budgetArray); put("customCategories", ccArray)
            }
            val encrypted = SecurityUtils.encrypt(payload.toString(), BACKUP_KEY)
            val wrapper   = JSONObject().apply {
                put("v", 1); put("ts", System.currentTimeMillis()); put("encrypted", encrypted)
            }
            val content  = wrapper.toString()
            val fileName = "autoledger_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"

            if (customPath.startsWith("content://")) {
                val treeUri   = android.net.Uri.parse(customPath)
                val docFolder = DocumentFile.fromTreeUri(applicationContext, treeUri)
                val docFile   = docFolder?.createFile("application/json", fileName)
                docFile?.let {
                    applicationContext.contentResolver.openOutputStream(it.uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                } ?: throw Exception("Could not create backup file in chosen folder.")
            } else {
                val folder = if (customPath.isNotEmpty()) java.io.File(customPath)
                             else java.io.File(applicationContext.getExternalFilesDir(null), "Backups")
                folder.mkdirs()
                java.io.File(folder, fileName).writeText(content, Charsets.UTF_8)
            }

            prefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply()
            Log.i(TAG, "Auto-backup completed: $fileName")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Auto-backup failed: ${e.message}", e)
            Result.failure()
        }
    }
}

/**
 * Schedules / cancels the [BackupWorker] periodic job so that it first fires at the next
 * midnight and then repeats according to [freq] (DAILY / WEEKLY / MONTHLY).
 *
 * Call whenever the user changes the frequency in settings.
 */
object BackupScheduler {

    fun schedule(context: Context, freq: String) {
        val wm = WorkManager.getInstance(context)
        if (freq == "MANUAL") { wm.cancelUniqueWork(BackupWorker.WORK_NAME); return }

        val repeatHours: Long = when (freq.uppercase()) {
            "DAILY"   -> 24L
            "WEEKLY"  -> 24L * 7
            "MONTHLY" -> 24L * 30
            else      -> return
        }

        // Initial delay to next midnight
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val initialDelayMs = midnight.timeInMillis - System.currentTimeMillis()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(repeatHours, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()

        wm.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(BackupWorker.WORK_NAME)
}
