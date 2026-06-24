package com.example.utils

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.data.TransactionEntry
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {
    fun exportToPdfBytes(transactions: List<TransactionEntry>): ByteArray {
        val pdfDocument = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 32f
        val lineHeight = 18f
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            isFakeBoldText = true
        }
        val headingPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 12f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
        }
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val monthlySummaries = transactions.groupBy { monthFormat.format(Date(it.timestamp)) }
            .toSortedMap(compareByDescending { it })
            .map { (month, monthTransactions) ->
                val income = monthTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
                val expense = monthTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
                Triple(month, income, expense)
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

        fun drawLine(text: String, paint: Paint = bodyPaint, extraGap: Float = 0f) {
            ensureSpace()
            canvas.drawText(text, margin, y, paint)
            y += lineHeight + extraGap
        }

        drawLine("MyMoney Financial Report", titlePaint, 4f)
        drawLine("Generated on ${dateFormat.format(Date())}")
        y += 8f

        drawLine("Monthly Summary", headingPaint, 2f)
        monthlySummaries.forEach { (month, income, expense) ->
            drawLine("$month  Income: ${"%.2f".format(Locale.getDefault(), income)}  Expense: ${"%.2f".format(Locale.getDefault(), expense)}  Net: ${"%.2f".format(Locale.getDefault(), income - expense)}")
        }

        y += 8f
        drawLine("Transactions", headingPaint, 2f)
        transactions.sortedByDescending { it.timestamp }.forEach { tx ->
            val notes = tx.note?.substringBefore(" [Acc:")?.trim().orEmpty()
            ensureSpace(2)
            drawLine("${dateFormat.format(Date(tx.timestamp))}  ${tx.type}  ${tx.category}  ${"%.2f".format(Locale.getDefault(), tx.amount)}")
            drawLine("${tx.title} | ${tx.getAccountName()}${if (notes.isNotBlank()) " | $notes" else ""}")
        }

        pdfDocument.finishPage(page)
        val outputStream = ByteArrayOutputStream()
        pdfDocument.writeTo(outputStream)
        pdfDocument.close()
        return outputStream.toByteArray()
    }
}