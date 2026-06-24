package com.example.utils

import com.example.data.TransactionEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {
    fun exportToCsvString(transactions: List<TransactionEntry>): String {
        val builder = java.lang.StringBuilder()
        // Headers
        builder.append("ID,Date,Title,Amount,Category,Type,SMS Sender,SMS Body,Notes\n")
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        for (tx in transactions) {
            val dateStr = dateFormat.format(Date(tx.timestamp))
            val cleanTitle = tx.title.replace("\"", "\"\"")
            val cleanSmsSender = (tx.smsSender ?: "").replace("\"", "\"\"")
            val cleanSmsBody = (tx.smsBody ?: "").replace("\n", " ").replace("\"", "\"\"")
            val cleanNote = (tx.note ?: "").replace("\n", " ").replace("\"", "\"\"")
            
            builder.append("${tx.id},")
            builder.append("\"$dateStr\",")
            builder.append("\"$cleanTitle\",")
            builder.append("${tx.amount},")
            builder.append("\"${tx.category}\",")
            builder.append("\"${tx.type}\",")
            builder.append("\"$cleanSmsSender\",")
            builder.append("\"$cleanSmsBody\",")
            builder.append("\"$cleanNote\"\n")
        }
        
        return builder.toString()
    }
}
