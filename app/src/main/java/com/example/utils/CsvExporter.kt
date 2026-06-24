package com.example.utils

import com.example.data.TransactionEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {
    fun exportToCsvString(transactions: List<TransactionEntry>): String {
        val builder = java.lang.StringBuilder()
        builder.append("Transaction History\n")
        builder.append("ID,Date,Title,Amount,Category,Type,Account,SMS Sender,SMS Body,Notes\n")
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        for (tx in transactions) {
            val dateStr = dateFormat.format(Date(tx.timestamp))
            val cleanTitle = tx.title.replace("\"", "\"\"")
            val cleanSmsSender = (tx.smsSender ?: "").replace("\"", "\"\"")
            val cleanSmsBody = (tx.smsBody ?: "").replace("\n", " ").replace("\"", "\"\"")
            val cleanNote = (tx.note ?: "").replace("\n", " ").replace("\"", "\"\"")
            val cleanAccount = tx.getAccountName().replace("\"", "\"\"")
            
            builder.append("${tx.id},")
            builder.append("\"$dateStr\",")
            builder.append("\"$cleanTitle\",")
            builder.append("${tx.amount},")
            builder.append("\"${tx.category}\",")
            builder.append("\"${tx.type}\",")
            builder.append("\"$cleanAccount\",")
            builder.append("\"$cleanSmsSender\",")
            builder.append("\"$cleanSmsBody\",")
            builder.append("\"$cleanNote\"\n")
        }

        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val monthlyGroups = transactions.groupBy { monthFormat.format(Date(it.timestamp)) }
            .toSortedMap(compareByDescending { it })

        builder.append("\nMonthly Summary\n")
        builder.append("Month,Income,Expense,Net\n")
        monthlyGroups.forEach { (month, monthTransactions) ->
            val income = monthTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
            val expense = monthTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            builder.append("$month,$income,$expense,${income - expense}\n")
        }

        builder.append("\nMonthly Category Summary\n")
        builder.append("Month,Category,Type,Total\n")
        monthlyGroups.forEach { (month, monthTransactions) ->
            monthTransactions.groupBy { it.category to it.type }.forEach { (key, entries) ->
                builder.append("$month,\"${key.first}\",${key.second},${entries.sumOf { it.amount }}\n")
            }
        }
        
        return builder.toString()
    }
}
