package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recurring_transactions")
data class RecurringTransaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val category: String, // String category name
    val type: String = "EXPENSE", // EXPENSE or INCOME
    val frequency: String, // DAILY, WEEKLY, MONTHLY, YEARLY
    val startDate: Long,
    val endDate: Long,
    val lastExecutedDate: Long = 0L, // 0L means never executed yet
    val autoLog: Boolean = true,
    val note: String? = null
)
