package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "budgets")
data class BudgetEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val category: String, // Maps to ExpenseCategory.name
    val amountLimit: Double,
    val monthYear: String // e.g., "2026-06"
)
