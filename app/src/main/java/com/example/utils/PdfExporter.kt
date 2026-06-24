package com.example.utils

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.data.CategoryResolver
import com.example.data.CustomCategory
import com.example.data.TransactionEntry
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {
    fun exportToPdfBytes(transactions: List<TransactionEntry>, customCategories: List<CustomCategory>): ByteArray {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 30f
        val lineHeight = 18f
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 10f
        }
        val headingPaint = Paint().apply {
            color = Color.WHITE
            textSize = 12f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#0F172A")
            textSize = 10f
        }
        val labelPaint = Paint().apply {
            color = Color.parseColor("#475569")
            textSize = 9f
            isFakeBoldText = true
        }
        val lightCardPaint = Paint().apply { color = Color.parseColor("#F8FAFC") }
        val darkHeaderPaint = Paint().apply { color = Color.parseColor("#0F172A") }
        val accentPaint = Paint().apply { color = Color.parseColor("#0EA5E9") }
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val monthlyGroups = transactions.groupBy { monthFormat.format(Date(it.timestamp)) }.toSortedMap()
        val monthlySummaries = mutableListOf<MonthlyPdfSection>()
        var carryOver = 0.0
        monthlyGroups.forEach { (month, monthTransactions) ->
            val income = monthTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
            val expense = monthTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            val net = income - expense
            val closing = carryOver + net
            val expenseTransactions = monthTransactions.filter { it.type == "EXPENSE" }
            val totalExpense = expenseTransactions.sumOf { it.amount }
            val breakdown = expenseTransactions.groupBy { it.category }.map { (categoryName, entries) ->
                val resolved = CategoryResolver.resolve(categoryName, customCategories)
                MonthlyPdfBreakdown(
                    name = resolved.displayName,
                    color = Color.parseColor(String.format("#%06X", 0xFFFFFF and resolved.color.value.toInt())),
                    total = entries.sumOf { it.amount },
                    percentage = if (totalExpense > 0.0) entries.sumOf { it.amount } / totalExpense else 0.0
                )
            }.sortedByDescending { it.total }
            monthlySummaries += MonthlyPdfSection(month, carryOver, income, expense, net, closing, monthTransactions.sortedByDescending { it.timestamp }, breakdown)
            carryOver = closing
        }

        var pageNumber = 1
        var page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = margin

        fun nextPage() {
            pdfDocument.finishPage(page)
            pageNumber += 1
            page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = margin
        }

        fun ensureSpace(lines: Int = 1) {
            if (y + (lines * lineHeight) > pageHeight - margin) {
                nextPage()
            }
        }

        fun drawLine(text: String, paint: Paint = bodyPaint, extraGap: Float = 0f, x: Float = margin) {
            ensureSpace()
            canvas.drawText(text, x, y, paint)
            y += lineHeight + extraGap
        }

        fun drawSummaryCard(label: String, value: Double, x: Float, width: Float, color: Int) {
            val paint = Paint().apply { this.color = color }
            canvas.drawRoundRect(x, y, x + width, y + 46f, 12f, 12f, paint)
            canvas.drawText(label, x + 10f, y + 16f, subtitlePaint)
            canvas.drawText("₹${"%,.2f".format(Locale.getDefault(), value)}", x + 10f, y + 34f, headingPaint)
        }

        fun drawMonthSection(section: MonthlyPdfSection) {
            ensureSpace(18)
            canvas.drawRoundRect(margin, y, pageWidth - margin, y + 34f, 14f, 14f, darkHeaderPaint)
            canvas.drawText(section.month, margin + 14f, y + 22f, titlePaint)
            y += 46f

            val cardWidth = (pageWidth - margin * 2 - 20f) / 3f
            drawSummaryCard("Carry Over", section.openingBalance, margin, cardWidth, Color.parseColor("#1D4ED8"))
            drawSummaryCard("Monthly Total", section.net, margin + cardWidth + 10f, cardWidth, Color.parseColor("#0F766E"))
            drawSummaryCard("Grand Total", section.closingBalance, margin + (cardWidth + 10f) * 2, cardWidth, Color.parseColor("#7C3AED"))
            y += 60f

            canvas.drawRoundRect(margin, y, pageWidth - margin, y + 24f, 10f, 10f, accentPaint)
            canvas.drawText("Category Breakdown", margin + 12f, y + 16f, headingPaint)
            y += 34f
            section.breakdown.forEach { entry ->
                ensureSpace(1)
                val colorPaint = Paint().apply { color = entry.color }
                canvas.drawCircle(margin + 6f, y - 4f, 5f, colorPaint)
                canvas.drawText(entry.name, margin + 18f, y, bodyPaint)
                canvas.drawText(String.format(Locale.getDefault(), "%.1f%%", entry.percentage * 100), pageWidth - margin - 120f, y, labelPaint)
                canvas.drawText("₹${"%,.2f".format(Locale.getDefault(), entry.total)}", pageWidth - margin - 70f, y, bodyPaint)
                y += 16f
            }

            y += 8f
            canvas.drawRoundRect(margin, y, pageWidth - margin, y + 24f, 10f, 10f, darkHeaderPaint)
            canvas.drawText("Transactions", margin + 12f, y + 16f, headingPaint)
            y += 32f

            val headers = listOf("Date", "Time", "Payee", "Wallet", "Category", "Amount")
            val columns = listOf(margin, 102f, 160f, 320f, 430f, 500f)
            headers.forEachIndexed { index, header ->
                canvas.drawText(header, columns[index], y, labelPaint)
            }
            y += 12f
            canvas.drawLine(margin, y, pageWidth - margin, y, labelPaint)
            y += 10f

            section.transactions.forEach { tx ->
                ensureSpace(2)
                val resolved = CategoryResolver.resolve(tx.category, customCategories)
                canvas.drawText(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(tx.timestamp)), columns[0], y, bodyPaint)
                canvas.drawText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tx.timestamp)), columns[1], y, bodyPaint)
                canvas.drawText(trimText(tx.title, 24), columns[2], y, bodyPaint)
                canvas.drawText(trimText(tx.getAccountName(), 16), columns[3], y, bodyPaint)
                canvas.drawText(trimText(resolved.displayName, 14), columns[4], y, bodyPaint)
                val signedAmount = if (tx.type == "EXPENSE") -tx.amount else tx.amount
                canvas.drawText("₹${"%,.2f".format(Locale.getDefault(), signedAmount)}", columns[5], y, bodyPaint)
                y += 16f
            }
            y += 16f
        }

        canvas.drawRect(0f, 0f, pageWidth.toFloat(), 76f, darkHeaderPaint)
        drawLine("MyMoney Financial Report", titlePaint, x = margin, extraGap = 2f)
        drawLine("Generated on ${dateFormat.format(Date())}", subtitlePaint, x = margin)
        y += 10f

        monthlySummaries.sortedByDescending { it.month }.forEach { section ->
            drawMonthSection(section)
        }

        pdfDocument.finishPage(page)
        val outputStream = ByteArrayOutputStream()
        pdfDocument.writeTo(outputStream)
        pdfDocument.close()
        return outputStream.toByteArray()
    }

    private fun trimText(text: String, maxChars: Int): String {
        return if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
    }

    private data class MonthlyPdfSection(
        val month: String,
        val openingBalance: Double,
        val income: Double,
        val expense: Double,
        val net: Double,
        val closingBalance: Double,
        val transactions: List<TransactionEntry>,
        val breakdown: List<MonthlyPdfBreakdown>
    )

    private data class MonthlyPdfBreakdown(
        val name: String,
        val color: Int,
        val total: Double,
        val percentage: Double
    )
}