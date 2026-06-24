package com.example.utils

import com.example.data.CustomCategory
import com.example.data.TransactionEntry
import com.example.data.CategoryResolver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class MonthlyCategoryBreakdown(
    val name: String,
    val colorHex: String,
    val total: Double,
    val percentage: Double
)

private data class MonthlyExportSection(
    val monthKey: String,
    val openingBalance: Double,
    val income: Double,
    val expense: Double,
    val net: Double,
    val closingBalance: Double,
    val transactions: List<TransactionEntry>,
    val breakdown: List<MonthlyCategoryBreakdown>
)

object ExcelExporter {
    fun exportToExcelBytes(transactions: List<TransactionEntry>, customCategories: List<CustomCategory>): ByteArray {
        val sections = buildMonthlySections(transactions, customCategories)
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        val workbook = buildString {
            append("<?xml version=\"1.0\"?>")
            append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"")
            append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"")
            append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"")
            append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">")
            append("<Styles>")
            append("<Style ss:ID=\"Default\"><Alignment ss:Vertical=\"Center\"/><Font ss:FontName=\"Calibri\" ss:Size=\"11\"/><Borders/></Style>")
            append("<Style ss:ID=\"title\"><Font ss:Bold=\"1\" ss:Size=\"16\" ss:Color=\"#FFFFFF\"/><Interior ss:Color=\"#0F172A\" ss:Pattern=\"Solid\"/></Style>")
            append("<Style ss:ID=\"subtitle\"><Font ss:Italic=\"1\" ss:Color=\"#475569\"/></Style>")
            append("<Style ss:ID=\"header\"><Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\"/><Interior ss:Color=\"#0EA5E9\" ss:Pattern=\"Solid\"/></Style>")
            append("<Style ss:ID=\"monthHeader\"><Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\"/><Interior ss:Color=\"#1E293B\" ss:Pattern=\"Solid\"/></Style>")
            append("<Style ss:ID=\"currency\"><NumberFormat ss:Format=\"₹#,##0.00\"/></Style>")
            append("<Style ss:ID=\"summaryLabel\"><Font ss:Bold=\"1\" ss:Color=\"#1E293B\"/><Interior ss:Color=\"#E2E8F0\" ss:Pattern=\"Solid\"/></Style>")
            append("<Style ss:ID=\"summaryValue\"><Font ss:Bold=\"1\" ss:Color=\"#0F172A\"/><Interior ss:Color=\"#F8FAFC\" ss:Pattern=\"Solid\"/><NumberFormat ss:Format=\"₹#,##0.00\"/></Style>")
            sections.flatMap { it.breakdown }.map { it.colorHex }.distinct().forEach { colorHex ->
                val styleId = "c${colorHex.removePrefix("#").uppercase(Locale.getDefault())}"
                append("<Style ss:ID=\"")
                append(styleId)
                append("\"><Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\"/><Interior ss:Color=\"")
                append(colorHex)
                append("\" ss:Pattern=\"Solid\"/></Style>")
            }
            append("</Styles>")

            append("<Worksheet ss:Name=\"Monthly Summary\"><Table>")
            append(row(listOf(cell("MyMoney Monthly Report", "title"), emptyCell(), emptyCell(), emptyCell(), emptyCell(), emptyCell())))
            append(row(listOf(cell("Generated at $generatedAt", "subtitle"))))
            append(row(listOf()))
            append(row(listOf(cell("Month", "header"), cell("Carry Over", "header"), cell("Income", "header"), cell("Expense", "header"), cell("Monthly Total", "header"), cell("Grand Total", "header"))))
            sections.sortedByDescending { it.monthKey }.forEach { section ->
                append(row(listOf(
                    textCell(section.monthKey),
                    currencyCell(section.openingBalance),
                    currencyCell(section.income),
                    currencyCell(section.expense),
                    currencyCell(section.net),
                    currencyCell(section.closingBalance)
                )))
                append(row(listOf(cell("Category Breakdown", "monthHeader"), emptyCell(), emptyCell(), emptyCell())))
                append(row(listOf(cell("Category", "header"), cell("Share", "header"), cell("Amount", "header"))))
                section.breakdown.forEach { entry ->
                    val styleId = "c${entry.colorHex.removePrefix("#").uppercase(Locale.getDefault())}"
                    append(row(listOf(
                        cell(entry.name, styleId),
                        textCell(String.format(Locale.getDefault(), "%.1f%%", entry.percentage * 100)),
                        currencyCell(entry.total)
                    )))
                }
                append(row(listOf()))
            }
            append("</Table></Worksheet>")

            sections.sortedByDescending { it.monthKey }.forEach { section ->
                val worksheetName = sanitizeWorksheetName(section.monthKey)
                append("<Worksheet ss:Name=\"")
                append(escapeXml(worksheetName))
                append("\"><Table>")
                append(row(listOf(cell("${section.monthKey} Transactions", "monthHeader"), emptyCell(), emptyCell(), emptyCell(), emptyCell(), emptyCell())))
                append(row(listOf(cell("Payee", "header"), cell("Amount", "header"), cell("Category", "header"), cell("Wallet/Bank", "header"), cell("Date", "header"), cell("Time", "header"))))
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                section.transactions.sortedByDescending { it.timestamp }.forEach { tx ->
                    val resolved = CategoryResolver.resolve(tx.category, customCategories)
                    append(row(listOf(
                        textCell(tx.title),
                        currencyCell(if (tx.type == "EXPENSE") -tx.amount else tx.amount),
                        textCell(resolved.displayName),
                        textCell(tx.getAccountName()),
                        textCell(dateFormat.format(Date(tx.timestamp))),
                        textCell(timeFormat.format(Date(tx.timestamp)))
                    )))
                }
                append(row(listOf()))
                append(row(listOf(cell("Carry Over Balance", "summaryLabel"), currencySummaryCell(section.openingBalance))))
                append(row(listOf(cell("Monthly Total", "summaryLabel"), currencySummaryCell(section.net))))
                append(row(listOf(cell("Grand Total", "summaryLabel"), currencySummaryCell(section.closingBalance))))
                append("</Table></Worksheet>")
            }

            append("</Workbook>")
        }

        return workbook.toByteArray(Charsets.UTF_8)
    }

    private fun buildMonthlySections(transactions: List<TransactionEntry>, customCategories: List<CustomCategory>): List<MonthlyExportSection> {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val grouped = transactions.groupBy { monthFormat.format(Date(it.timestamp)) }.toSortedMap()
        val sections = mutableListOf<MonthlyExportSection>()
        var carryOver = 0.0

        grouped.forEach { (monthKey, monthTransactions) ->
            val income = monthTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
            val expense = monthTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            val net = income - expense
            val openingBalance = carryOver
            val closingBalance = openingBalance + net
            val expenseTransactions = monthTransactions.filter { it.type == "EXPENSE" }
            val totalExpense = expenseTransactions.sumOf { it.amount }
            val breakdown = expenseTransactions.groupBy { it.category }
                .map { (categoryName, entries) ->
                    val total = entries.sumOf { it.amount }
                    val resolved = CategoryResolver.resolve(categoryName, customCategories)
                    MonthlyCategoryBreakdown(
                        name = resolved.displayName,
                        colorHex = colorToHex(resolved.color.value.toInt()),
                        total = total,
                        percentage = if (totalExpense > 0.0) total / totalExpense else 0.0
                    )
                }
                .sortedByDescending { it.total }

            sections += MonthlyExportSection(monthKey, openingBalance, income, expense, net, closingBalance, monthTransactions, breakdown)
            carryOver = closingBalance
        }

        return sections
    }

    private fun colorToHex(colorValue: Int): String {
        return String.format("#%06X", 0xFFFFFF and colorValue)
    }

    private fun sanitizeWorksheetName(name: String): String {
        return name.replace(Regex("[\\\\/?*\\[\\]:]"), "-").take(31)
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun row(cells: List<String>): String = "<Row>${cells.joinToString("")}</Row>"
    private fun cell(text: String, styleId: String): String = "<Cell ss:StyleID=\"$styleId\"><Data ss:Type=\"String\">${escapeXml(text)}</Data></Cell>"
    private fun textCell(text: String): String = "<Cell><Data ss:Type=\"String\">${escapeXml(text)}</Data></Cell>"
    private fun currencyCell(amount: Double): String = "<Cell ss:StyleID=\"currency\"><Data ss:Type=\"Number\">$amount</Data></Cell>"
    private fun currencySummaryCell(amount: Double): String = "<Cell ss:StyleID=\"summaryValue\"><Data ss:Type=\"Number\">$amount</Data></Cell>"
    private fun emptyCell(): String = "<Cell><Data ss:Type=\"String\"></Data></Cell>"
}