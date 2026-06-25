package com.example.utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.data.CategoryResolver
import com.example.data.CustomCategory
import com.example.data.TransactionEntry
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfExporter {

    // ─── Page constants ───────────────────────────────────────────────────────
    private const val PW = 595f   // A4 width  (pts)
    private const val PH = 842f   // A4 height (pts)
    private const val M  = 28f    // margin

    // ─── Palette ──────────────────────────────────────────────────────────────
    private val C_BG_DARK  = Color.parseColor("#0F172A")
    private val C_BG_MID   = Color.parseColor("#1E293B")
    private val C_ACCENT   = Color.parseColor("#0EA5E9")
    private val C_TEAL     = Color.parseColor("#0F766E")
    private val C_PURPLE   = Color.parseColor("#6D28D9")
    private val C_INC      = Color.parseColor("#047857")
    private val C_INC_BG   = Color.parseColor("#ECFDF5")
    private val C_INC_ALT  = Color.parseColor("#D1FAE5")
    private val C_EXP      = Color.parseColor("#BE123C")
    private val C_EXP_BG   = Color.parseColor("#FFF1F2")
    private val C_EXP_ALT  = Color.parseColor("#FFE4E6")
    private val C_TRACK    = Color.parseColor("#E2E8F0")
    private val C_ROW_ALT  = Color.parseColor("#F1F5F9")
    private val C_TEXT     = Color.parseColor("#0F172A")
    private val C_MUTED    = Color.parseColor("#64748B")
    private val C_WHITE    = Color.WHITE
    private val C_DIVIDER  = Color.parseColor("#CBD5E1")

    fun exportToPdfBytes(
        transactions: List<TransactionEntry>,
        customCategories: List<CustomCategory>
    ): ByteArray {
        // ─── Build monthly data ──────────────────────────────────────────────
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val dateFormat  = SimpleDateFormat("dd MMM yy", Locale.getDefault())
        val timeFormat  = SimpleDateFormat("HH:mm", Locale.getDefault())
        val tsFormat    = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        val monthlyGroups = transactions
            .filter { it.type == "INCOME" || it.type == "EXPENSE" }
            .groupBy { monthFormat.format(Date(it.timestamp)) }
            .toSortedMap()

        val allTx = transactions.filter { it.type == "INCOME" || it.type == "EXPENSE" }
        val grandInc = allTx.filter { it.type == "INCOME" }.sumOf { it.amount }
        val grandExp = allTx.filter { it.type == "EXPENSE" }.sumOf { it.amount }

        val sections = mutableListOf<MonthlyPdfSection>()
        var carryOver = 0.0
        monthlyGroups.forEach { (month, monthTx) ->
            val inc = monthTx.filter { it.type == "INCOME" }.sumOf { it.amount }
            val exp = monthTx.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            val net = inc - exp
            val closing = carryOver + net
            val expTx = monthTx.filter { it.type == "EXPENSE" }
            val totExp = expTx.sumOf { it.amount }
            val breakdown = expTx.groupBy { it.category }.map { (cat, entries) ->
                val resolved = CategoryResolver.resolve(cat, customCategories)
                MonthlyPdfBreakdown(
                    name  = resolved.displayName,
                    color = Color.parseColor(String.format("#%06X", 0xFFFFFF and resolved.color.value.toInt())),
                    total = entries.sumOf { it.amount },
                    pct   = if (totExp > 0.0) entries.sumOf { it.amount } / totExp else 0.0
                )
            }.sortedByDescending { it.total }
            sections += MonthlyPdfSection(month, carryOver, inc, exp, net, closing,
                monthTx.sortedByDescending { it.timestamp }, breakdown)
            carryOver = closing
        }

        // ─── PDF document state ───────────────────────────────────────────────
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
        var cv: Canvas = page.canvas
        var y = M

        fun finishAndNextPage() {
            drawPageFooter(cv, pageNum)
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
            cv = page.canvas
            y = M
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > PH - M - 16f) finishAndNextPage()
        }

        // ─── COVER PAGE ───────────────────────────────────────────────────────
        // Dark header band
        cv.drawRect(0f, 0f, PW, 100f, p(C_BG_DARK))
        cv.drawText("MyMoney", M, 44f, paint(22f, C_WHITE, bold = true))
        cv.drawText("Financial Report", M, 66f, paint(13f, C_ACCENT))
        cv.drawText("Generated: ${tsFormat.format(Date())}", M, 86f, paint(9f, C_MUTED))

        y = 116f

        // Overall summary cards
        val cardW = (PW - M * 2 - 16f) / 3f
        drawCard(cv, "Total Income",   grandInc, M,                  y, cardW, C_TEAL)
        drawCard(cv, "Total Expense",  grandExp, M + cardW + 8f,     y, cardW, C_EXP)
        drawCard(cv, "Net Balance",    grandInc - grandExp,
            M + (cardW + 8f) * 2, y, cardW,
            if (grandInc >= grandExp) C_INC else C_EXP)
        y += 62f

        // Overall income vs expense bar
        y += 8f
        cv.drawText("Income vs Expense Overview", M, y, paint(10f, C_BG_DARK, bold = true))
        y += 14f
        val maxVal = maxOf(grandInc, grandExp, 1.0)
        val barZoneW = PW - M * 2
        drawLabeledBar(cv, "Income",  grandInc, (grandInc / maxVal).toFloat(), M, y, barZoneW, C_TEAL)
        y += 22f
        drawLabeledBar(cv, "Expense", grandExp, (grandExp / maxVal).toFloat(), M, y, barZoneW, C_EXP)
        y += 26f

        // Monthly overview mini-table
        cv.drawText("Monthly Overview", M, y, paint(10f, C_BG_DARK, bold = true))
        y += 12f
        val colM = floatArrayOf(M, M + 90f, M + 200f, M + 310f, M + 420f)
        drawTableHeader(cv, y, colM, arrayOf("Month", "Income", "Expense", "Net", "Grand Total"))
        y += 18f

        sections.sortedByDescending { it.month }.forEach { s ->
            ensureSpace(16f)
            val netColor = if (s.net >= 0) C_INC else C_EXP
            val gcColor  = if (s.closingBalance >= 0) C_ACCENT else C_EXP
            cv.drawText(s.month,                             colM[0], y, paint(9f, C_TEXT))
            cv.drawText(fmtAmt(s.income),                   colM[1], y, paint(9f, C_INC, bold = true))
            cv.drawText(fmtAmt(s.expense),                  colM[2], y, paint(9f, C_EXP, bold = true))
            cv.drawText(fmtAmt(s.net),                      colM[3], y, paint(9f, netColor, bold = true))
            cv.drawText(fmtAmt(s.closingBalance),           colM[4], y, paint(9f, gcColor, bold = true))
            y += 16f
            cv.drawLine(M, y - 4f, PW - M, y - 4f, p(C_DIVIDER, strokeWidth = 0.5f))
        }

        // ─── PER-MONTH SECTIONS ───────────────────────────────────────────────
        sections.sortedByDescending { it.month }.forEach { s ->
            finishAndNextPage()

            // Month title band
            cv.drawRoundRect(RectF(M, y, PW - M, y + 36f), 10f, 10f, p(C_BG_DARK))
            cv.drawText(s.month, M + 14f, y + 24f, paint(14f, C_WHITE, bold = true))
            y += 48f

            // Summary cards
            val cw = (PW - M * 2 - 16f) / 3f
            drawCard(cv, "Carry Over",   s.openingBalance, M,              y, cw, Color.parseColor("#1D4ED8"))
            drawCard(cv, "Monthly Net",  s.net,            M + cw + 8f,    y, cw, if (s.net >= 0) C_TEAL else C_EXP)
            drawCard(cv, "Grand Total",  s.closingBalance, M + (cw+8f)*2, y, cw, C_PURPLE)
            y += 62f

            // Income vs expense for this month
            val mMax = maxOf(s.income, s.expense, 1.0)
            drawLabeledBar(cv, "Income",  s.income, (s.income / mMax).toFloat(),  M, y, PW - M * 2, C_TEAL)
            y += 22f
            drawLabeledBar(cv, "Expense", s.expense, (s.expense / mMax).toFloat(), M, y, PW - M * 2, C_EXP)
            y += 26f

            // Category breakdown bar chart
            if (s.breakdown.isNotEmpty()) {
                ensureSpace(20f + s.breakdown.take(8).size * 20f)
                cv.drawRoundRect(RectF(M, y, PW - M, y + 22f), 8f, 8f, p(C_ACCENT))
                cv.drawText("Category Breakdown", M + 10f, y + 15f, paint(10f, C_WHITE, bold = true))
                y += 30f
                drawCategoryBars(cv, s.breakdown.take(8), M, y, PW - M * 2)
                y += s.breakdown.take(8).size * 20f + 10f
            }

            // Transaction table
            ensureSpace(38f)
            cv.drawRoundRect(RectF(M, y, PW - M, y + 22f), 8f, 8f, p(C_BG_MID))
            cv.drawText("Transactions (${s.transactions.size})", M + 10f, y + 15f, paint(10f, C_WHITE, bold = true))
            y += 30f

            val txCols = floatArrayOf(M, M + 62f, M + 107f, M + 267f, M + 377f, M + 462f)
            drawTableHeader(cv, y, txCols, arrayOf("Date", "Time", "Payee", "Wallet", "Category", "Amount"))
            y += 18f

            s.transactions.forEachIndexed { idx, tx ->
                ensureSpace(16f)
                val resolved = CategoryResolver.resolve(tx.category, customCategories)
                val isInc = tx.type == "INCOME"
                val alt   = idx % 2 != 0

                // Row background
                val rowBg = when {
                    isInc && alt  -> C_INC_ALT
                    isInc         -> C_INC_BG
                    !isInc && alt -> C_EXP_ALT
                    else          -> C_EXP_BG
                }
                cv.drawRect(M, y - 11f, PW - M, y + 5f, p(rowBg))

                val amtColor = if (isInc) C_INC else C_EXP
                cv.drawText(dateFormat.format(Date(tx.timestamp)),              txCols[0], y, paint(8.5f, C_TEXT))
                cv.drawText(timeFormat.format(Date(tx.timestamp)),              txCols[1], y, paint(8.5f, C_MUTED))
                cv.drawText(trim(tx.title, 22),                                 txCols[2], y, paint(8.5f, C_TEXT))
                cv.drawText(trim(tx.getAccountName(), 15),                      txCols[3], y, paint(8.5f, C_TEXT))
                cv.drawText(trim(resolved.displayName, 13),                     txCols[4], y, paint(8.5f, C_MUTED))
                cv.drawText(
                    fmtAmt(if (isInc) tx.amount else -tx.amount),
                    txCols[5], y, paint(8.5f, amtColor, bold = true)
                )
                y += 16f
            }

            // Monthly totals
            y += 6f
            ensureSpace(52f)
            drawSummaryRow(cv, "Carry Over Balance", s.openingBalance, y, C_ACCENT);  y += 18f
            drawSummaryRow(cv, "Monthly Net",        s.net,            y, if (s.net >= 0) C_TEAL else C_EXP); y += 18f
            drawSummaryRow(cv, "Grand Total",        s.closingBalance, y, C_PURPLE);  y += 18f
        }

        drawPageFooter(cv, pageNum)
        doc.finishPage(page)

        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    // ─── Drawing helpers ──────────────────────────────────────────────────────

    private fun drawCard(canvas: Canvas, label: String, value: Double, x: Float, y: Float, w: Float, color: Int) {
        canvas.drawRoundRect(RectF(x, y, x + w, y + 52f), 10f, 10f, p(color))
        canvas.drawText(label, x + 10f, y + 17f, paint(8f, C_WHITE))
        canvas.drawText(fmtAmt(value), x + 10f, y + 36f, paint(11f, C_WHITE, bold = true))
    }

    private fun drawLabeledBar(
        canvas: Canvas, label: String, value: Double,
        fill: Float, x: Float, y: Float, totalW: Float, color: Int
    ) {
        val labelW = 62f
        val amtW   = 90f
        val barW   = totalW - labelW - amtW
        canvas.drawText(label, x, y + 11f, paint(9f, C_TEXT))
        // track
        canvas.drawRoundRect(RectF(x + labelW, y, x + labelW + barW, y + 14f), 5f, 5f, p(C_TRACK))
        // fill
        val fillPx = (barW * fill.coerceIn(0f, 1f)).coerceAtLeast(4f)
        canvas.drawRoundRect(RectF(x + labelW, y, x + labelW + fillPx, y + 14f), 5f, 5f, p(color))
        canvas.drawText(fmtAmt(value), x + labelW + barW + 6f, y + 11f, paint(9f, C_TEXT, bold = true))
    }

    private fun drawCategoryBars(
        canvas: Canvas, items: List<MonthlyPdfBreakdown>,
        x: Float, startY: Float, totalW: Float
    ) {
        val labelW = 100f
        val pctW   = 38f
        val amtW   = 78f
        val barW   = totalW - labelW - pctW - amtW
        items.forEach { entry ->
            val y = startY + items.indexOf(entry) * 20f
            // label
            canvas.drawText(trim(entry.name, 16), x, y + 11f, paint(9f, C_TEXT))
            // track
            canvas.drawRoundRect(RectF(x + labelW, y, x + labelW + barW, y + 12f), 4f, 4f, p(C_TRACK))
            // fill
            val fillPx = (barW * entry.pct.toFloat().coerceIn(0f, 1f)).coerceAtLeast(4f)
            canvas.drawRoundRect(RectF(x + labelW, y, x + labelW + fillPx, y + 12f), 4f, 4f, p(entry.color))
            // pct
            canvas.drawText(String.format(Locale.getDefault(), "%.0f%%", entry.pct * 100),
                x + labelW + barW + 5f, y + 10f, paint(8f, C_MUTED))
            // amount
            canvas.drawText(fmtAmt(entry.total),
                x + labelW + barW + pctW + 5f, y + 10f, paint(8f, C_TEXT, bold = true))
        }
    }

    private fun drawTableHeader(canvas: Canvas, y: Float, cols: FloatArray, labels: Array<String>) {
        canvas.drawRect(M, y - 12f, PW - M, y + 4f, p(C_BG_MID))
        labels.forEachIndexed { i, label ->
            canvas.drawText(label, cols[i] + 2f, y, paint(8.5f, C_WHITE, bold = true))
        }
    }

    private fun drawSummaryRow(canvas: Canvas, label: String, value: Double, y: Float, valueColor: Int) {
        canvas.drawRect(M, y - 12f, PW - M, y + 4f, p(C_TRACK))
        canvas.drawText(label, M + 8f, y, paint(9f, C_TEXT, bold = true))
        canvas.drawText(fmtAmt(value), PW - M - 100f, y, paint(9f, valueColor, bold = true))
    }

    private fun drawPageFooter(canvas: Canvas, pageNum: Int) {
        canvas.drawLine(M, PH - 18f, PW - M, PH - 18f, p(C_DIVIDER, strokeWidth = 0.5f))
        canvas.drawText("MyMoney Financial Report", M, PH - 7f, paint(7f, C_MUTED))
        canvas.drawText("Page $pageNum", PW - M - 30f, PH - 7f, paint(7f, C_MUTED))
    }

    // ─── Paint factory ────────────────────────────────────────────────────────

    private fun p(color: Int, strokeWidth: Float = 0f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (strokeWidth > 0f) { style = Paint.Style.STROKE; this.strokeWidth = strokeWidth }
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 1.3f   // pts → px for A4 at 72dpi (reduced from 2.0 to stop cell overflow)
            this.color = color
            isFakeBoldText = bold
        }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private fun fmtAmt(value: Double): String =
        (if (value < 0) "-₹" else "₹") + "%,.2f".format(Locale.getDefault(), Math.abs(value))

    private fun trim(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    // ─── Data classes ─────────────────────────────────────────────────────────

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
        val pct: Double
    )
}