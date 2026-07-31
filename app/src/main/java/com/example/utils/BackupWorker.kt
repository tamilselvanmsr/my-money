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
        const val WORK_NAME = "auto_backup"
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
                        // Include SMS fields so TRANSFER duplicate detection works after JSON restore
                        put("smsBody",   tx.smsBody   ?: JSONObject.NULL)
                        put("smsSender", tx.smsSender ?: JSONObject.NULL)
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
                // Merchant → category rules
                put("merchantRules", JSONArray(prefs.getString("merchant_category_rules", "[]") ?: "[]"))
            }
            val encrypted = SecurityUtils.encrypt(payload.toString(), BACKUP_KEY)
            val wrapper   = JSONObject().apply {
                put("v", 1); put("ts", System.currentTimeMillis()); put("encrypted", encrypted)
            }
            val content  = wrapper.toString()
            val fileName = "auto_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"

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
            pruneOldBackups(customPath)
            Log.i(TAG, "Auto-backup completed: $fileName")
            addAppNotification("Auto-Backup Completed", "Backup saved: $fileName")
            postSystemNotification(true, "Backup saved: $fileName")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Auto-backup failed: ${e.message}", e)
            addAppNotification("Auto-Backup Failed", "Backup failed: ${e.message ?: "Unknown error"}")
            postSystemNotification(false, "Backup failed: ${e.message ?: "Unknown error"}")
            Result.failure()
        }
    }

    /** Keeps only the newest 5 backup files, deleting older ones — mirrors
     *  FinanceViewModel.pruneOldBackups() (the manual "Back Up Now" path), which this
     *  scheduled auto-backup worker previously never called, so its backups just kept
     *  accumulating forever instead of being capped like manual ones. */
    private fun pruneOldBackups(customPath: String, maxKeep: Int = 5) {
        try {
            if (customPath.startsWith("content://")) {
                val treeUri = android.net.Uri.parse(customPath)
                val docFolder = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return
                docFolder.listFiles()
                    .filter { it.name?.endsWith(".json") == true || it.name?.endsWith(".csv") == true }
                    .sortedByDescending { it.lastModified() }
                    .drop(maxKeep)
                    .forEach { it.delete() }
            } else {
                val folder = if (customPath.isNotEmpty()) java.io.File(customPath)
                             else java.io.File(applicationContext.getExternalFilesDir(null), "Backups")
                if (!folder.exists()) return
                folder.listFiles { f -> f.extension == "json" || f.extension == "csv" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(maxKeep)
                    ?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pruneOldBackups failed: ${e.message}", e)
        }
    }

    /** Writes a notification entry directly into the shared SharedPreferences store used
     *  by FinanceViewModel, so the bell badge lights up when the user next opens the app. */
    private fun addAppNotification(title: String, message: String) {
        try {
            val p = applicationContext.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
            val existing = try {
                org.json.JSONArray(p.getString("app_notifications_json", "[]") ?: "[]")
            } catch (_: Exception) { org.json.JSONArray() }
            val notif = org.json.JSONObject().apply {
                val now = System.currentTimeMillis()
                put("id", now); put("title", title); put("message", message)
                put("timestamp", now); put("isRead", false)
            }
            val updated = org.json.JSONArray()
            updated.put(notif)
            for (i in 0 until minOf(existing.length(), 199)) updated.put(existing.getJSONObject(i))
            p.edit().putString("app_notifications_json", updated.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Posts a heads-up system notification for auto-backup completion/failure — this Worker
     * runs outside the ViewModel's lifecycle so it creates/reuses its own HIGH-importance
     * channel (same id as FinanceViewModel's, so there's only ever one "Backup Status" channel). */
    private fun postSystemNotification(success: Boolean, detail: String) {
        try {
            val channelId = "backup_status_v2"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
                if (manager.getNotificationChannel(channelId) == null) {
                    manager.createNotificationChannel(android.app.NotificationChannel(
                        channelId, "Backup Status", android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Automatic backup completion and failure alerts"
                        enableVibration(true)
                    })
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val title = if (success) "Backup Completed" else "Backup Failed"
            val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(detail)
                .setPriority(if (success) androidx.core.app.NotificationCompat.PRIORITY_DEFAULT else androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(title.hashCode(), notification)
        } catch (_: Exception) {}
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
