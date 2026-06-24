package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class TransactionEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val category: String, // Maps to ExpenseCategory.name
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = "EXPENSE", // EXPENSE or INCOME
    val smsSender: String? = null,
    val smsBody: String? = null,
    val note: String? = null
) {
    fun getAccountName(): String {
        val noteStr = this.note ?: ""
        if (noteStr.contains("[Acc: ")) {
            val start = noteStr.indexOf("[Acc: ") + 6
            val end = noteStr.indexOf("]", start)
            if (end > start) {
                return noteStr.substring(start, end)
            }
        }
        // Try to infer from details
        val body = (this.smsBody ?: "").lowercase()
        val tLower = this.title.lowercase()
        if (body.contains("credit card") || tLower.contains("credit card") || tLower.contains("visa") || tLower.contains("sbi card") || tLower.contains("hdfc card")) {
            return "Credit Card"
        } else if (body.contains("hdfc") || body.contains("sbi") || body.contains("icici") || body.contains("bank") || body.contains("salary") || tLower.contains("salary")) {
            return "Bank Account"
        }
        return "Cash Wallet"
    }
}
