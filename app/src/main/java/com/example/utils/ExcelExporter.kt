package com.example.utils

import com.example.data.Account
import com.example.data.CustomCategory
import com.example.data.TransactionEntry
import com.example.data.CategoryResolver
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private data class MonthlyCategoryBreakdown(
    val name: String,
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
    val breakdown: List<MonthlyCategoryBreakdown>,
    val incomeBreakdown: List<MonthlyCategoryBreakdown>
)

private data class AccountMonthBalance(val monthKey: String, val opening: Double, val closing: Double)

/** One worksheet cell — either an inline string, a number, or blank (still stylable). */
private sealed class CellV {
    data class Str(val text: String, val style: Int) : CellV()
    data class Num(val value: Double, val style: Int) : CellV()
    data class Empty(val style: Int = 0) : CellV()
}

private class SheetPlan(val name: String) {
    val rows = mutableListOf<List<CellV>>()
    val merges = mutableListOf<String>()
    var colWidths: List<Double> = emptyList()
    var hidden: Boolean = false
    // Raw <dataValidation .../> XML fragments (e.g. list-type dropdowns for a column range).
    val dataValidations = mutableListOf<String>()

    fun addRow(vararg cells: CellV) {
        rows.add(cells.toList())
    }

    fun addSpacer() {
        rows.add(emptyList())
    }

    /** Adds a full-width merged banner row (e.g. a title band). */
    fun addMergeRow(text: String, style: Int, span: Int) {
        val rowNum = rows.size + 1
        val cells = (0 until span).map { i -> if (i == 0) CellV.Str(text, style) else CellV.Empty(style) }
        rows.add(cells)
        merges.add("A$rowNum:${colLetter(span)}$rowNum")
    }
}

/** Tracks fonts/fills/borders/number-formats/cellXfs and de-duplicates identical style
 * combinations, producing a single valid xl/styles.xml for the whole workbook. */
private class StyleSheetBuilder {
    private val fonts = mutableListOf<String>()
    private val fills = mutableListOf<String>()
    private val borders = mutableListOf<String>()
    private val numFmts = mutableListOf<Pair<Int, String>>()
    private val xfs = mutableListOf<IntArray>() // [numFmtId, fontId, fillId, borderId, center(0/1)]
    private val fontCache = mutableMapOf<String, Int>()
    private val fillCache = mutableMapOf<String, Int>()
    private val xfCache = mutableMapOf<String, Int>()

    init {
        fonts += fontXml(11, false, false, "FF0F172A")
        fills += "<patternFill patternType=\"none\"/>"
        fills += "<patternFill patternType=\"gray125\"/>" // required placeholder at index 1
        borders += "<left/><right/><top/><bottom/><diagonal/>"
        borders += thinBorderXml()
        xfs += intArrayOf(0, 0, 0, 0, 0)
    }

    private fun fontXml(size: Int, bold: Boolean, italic: Boolean, argb: String) = buildString {
        append("<font>")
        if (bold) append("<b/>")
        if (italic) append("<i/>")
        append("<sz val=\"$size\"/><color rgb=\"$argb\"/><name val=\"Calibri\"/>")
        append("</font>")
    }

    private fun thinBorderXml() = buildString {
        listOf("left", "right", "top", "bottom").forEach { side ->
            append("<$side style=\"thin\"><color rgb=\"FFCBD5E1\"/></$side>")
        }
        append("<diagonal/>")
    }

    fun font(size: Int, bold: Boolean, argb: String, italic: Boolean = false): Int {
        val xml = fontXml(size, bold, italic, argb)
        return fontCache.getOrPut(xml) { fonts += xml; fonts.size - 1 }
    }

    fun fill(argb: String): Int {
        val xml = "<patternFill patternType=\"solid\"><fgColor rgb=\"$argb\"/><bgColor indexed=\"64\"/></patternFill>"
        return fillCache.getOrPut(xml) { fills += xml; fills.size - 1 }
    }

    fun numFmt(code: String): Int {
        numFmts.firstOrNull { it.second == code }?.let { return it.first }
        val id = 164 + numFmts.size
        numFmts += id to code
        return id
    }

    fun xf(fontId: Int, fillId: Int, borderId: Int = 1, numFmtId: Int = 0, center: Boolean = false): Int {
        val key = "$fontId|$fillId|$borderId|$numFmtId|$center"
        return xfCache.getOrPut(key) {
            xfs += intArrayOf(numFmtId, fontId, fillId, borderId, if (center) 1 else 0)
            xfs.size - 1
        }
    }

    fun toXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (numFmts.isNotEmpty()) {
            append("<numFmts count=\"${numFmts.size}\">")
            numFmts.forEach { (id, code) -> append("<numFmt numFmtId=\"$id\" formatCode=\"${escapeXml(code)}\"/>") }
            append("</numFmts>")
        }
        append("<fonts count=\"${fonts.size}\">"); fonts.forEach { append(it) }; append("</fonts>")
        append("<fills count=\"${fills.size}\">"); fills.forEach { append("<fill>$it</fill>") }; append("</fills>")
        append("<borders count=\"${borders.size}\">"); borders.forEach { append("<border>$it</border>") }; append("</borders>")
        append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        append("<cellXfs count=\"${xfs.size}\">")
        xfs.forEach { (numFmtId, fontId, fillId, borderId, center) ->
            val applyNumFmt = if (numFmtId != 0) " applyNumberFormat=\"1\"" else ""
            val applyAlign = if (center == 1) " applyAlignment=\"1\"" else ""
            append("<xf numFmtId=\"$numFmtId\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"$borderId\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"$applyNumFmt$applyAlign>")
            if (center == 1) append("<alignment horizontal=\"center\" vertical=\"center\"/>")
            append("</xf>")
        }
        append("</cellXfs>")
        append("</styleSheet>")
    }
}

object ExcelExporter {

    fun exportToExcelBytes(
        transactions: List<TransactionEntry>,
        accounts: List<Account>,
        customCategories: List<CustomCategory>
    ): ByteArray {
        val sections = buildMonthlySections(transactions, customCategories)
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        // ── Compute actual account balances (mirrors computeWalletBalances logic) ──
        val latestSnap = mutableMapOf<String, Pair<Long, Double>>()
        for (tx in transactions) {
            if (tx.type != "BALANCE_UPDATE") continue
            val name = tx.getAccountName()
            val prev = latestSnap[name]
            if (prev == null || tx.timestamp > prev.first) latestSnap[name] = tx.timestamp to tx.amount
        }
        val computedBalances: Map<String, Double> = accounts.associate { acc ->
            val snap = latestSnap[acc.name]
            var bal = snap?.second ?: acc.balance
            for (tx in transactions) {
                if (tx.type == "DUPLICATE" || tx.type == "BALANCE_UPDATE") continue
                if (snap != null && tx.timestamp <= snap.first) continue
                when {
                    tx.type == "INCOME" && tx.getAccountName() == acc.name -> bal += tx.amount
                    tx.type == "EXPENSE" && tx.getAccountName() == acc.name -> bal -= tx.amount
                    tx.type == "TRANSFER" && tx.getAccountName() == acc.name -> bal -= tx.amount
                    tx.type == "TRANSFER" && run {
                        val n = tx.note ?: ""; val s = n.indexOf("[To: "); val e = if (s >= 0) n.indexOf("]", s + 5) else -1
                        if (s >= 0 && e > s) n.substring(s + 5, e) else null
                    } == acc.name -> {
                        val destSnap = latestSnap[acc.name]
                        if (destSnap == null || tx.timestamp > destSnap.first) bal += tx.amount
                    }
                }
            }
            acc.name to bal
        }
        val accountActivity: Map<String, Pair<Double, Double>> = transactions
            .filter { it.type == "INCOME" || it.type == "EXPENSE" }
            .groupBy { it.getAccountName() }
            .mapValues { (_, txList) ->
                txList.filter { it.type == "INCOME" }.sumOf { it.amount } to
                    txList.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            }

        // ── Chronological per-account replay: computes the delta each Balance Sync actually
        // represents (reported value − running balance right before it), so a Sync row shows a
        // signed correction (e.g. +1,000) instead of the raw absolute snapshot value (10,000).
        val syncDelta = mutableMapOf<Int, Double>()
        run {
            val running = mutableMapOf<String, Double>()
            for (acc in accounts) running[acc.name] = acc.balance
            val ordered = transactions.sortedWith(compareBy({ it.timestamp }, { if (it.type == "BALANCE_UPDATE") 1 else 0 }))
            for (tx in ordered) {
                val accName = tx.getAccountName()
                when (tx.type) {
                    "BALANCE_UPDATE" -> {
                        val before = running[accName] ?: 0.0
                        syncDelta[tx.id] = tx.amount - before
                        running[accName] = tx.amount
                    }
                    "DUPLICATE" -> { /* no real money movement */ }
                    "TRANSFER" -> {
                        running[accName] = (running[accName] ?: 0.0) - tx.amount
                        val n = tx.note ?: ""
                        val s = n.indexOf("[To: "); val e = if (s >= 0) n.indexOf("]", s + 5) else -1
                        val dest = if (s >= 0 && e > s) n.substring(s + 5, e) else null
                        if (dest != null) running[dest] = (running[dest] ?: 0.0) + tx.amount
                    }
                    else -> {
                        val delta = if (tx.type == "INCOME") tx.amount else -tx.amount
                        running[accName] = (running[accName] ?: 0.0) + delta
                    }
                }
            }
        }

        // ── Per-account month-by-month opening/closing balance, for the Account Summary sheet.
        val allMonthKeys = sections.map { it.monthKey }.sorted()
        val accountMonthlyBalances: Map<String, List<AccountMonthBalance>> = computeAccountMonthlyBalances(accounts, transactions, allMonthKeys)

        val styles = StyleSheetBuilder()
        // Raw quote characters here — escapeXml() in StyleSheetBuilder.toXml() escapes them to
        // &quot; when serializing. Passing already-escaped &quot; here double-escaped it to
        // &amp;quot;, corrupting the format code and making Excel show every amount as #VALUE!.
        val currencyFmt = styles.numFmt("\"\u20b9\"#,##0.00;[Red]\\-\"\u20b9\"#,##0.00")
        // Balance Sync deltas are corrections, not real expenses — negative values print in the
        // normal text color (no red) so they don't read as if money was spent.
        val syncFmt = styles.numFmt("+\"\u20b9\"#,##0.00;\\-\"\u20b9\"#,##0.00")
        val pctFmt = styles.numFmt("0.0%")

        val whiteBold = styles.font(11, bold = true, argb = "FFFFFFFF")
        val navyBold = styles.font(11, bold = true, argb = "FF0F172A")
        val greenBold = styles.font(11, bold = true, argb = "FF047857")
        val redBold = styles.font(11, bold = true, argb = "FFBE123C")
        val blueBold = styles.font(11, bold = true, argb = "FF1D4ED8")
        val grayPlain = styles.font(10, bold = false, argb = "FF475569")
        val plain = styles.font(11, bold = false, argb = "FF0F172A")

        fun headerStyle(bgArgb: String) = styles.xf(whiteBold, styles.fill(bgArgb), center = true)
        val hdrNavy = headerStyle("FF1E3A8A")
        val hdrBlue = headerStyle("FF0369A1")
        val hdrTeal = headerStyle("FF0F766E")
        val hdrPurp = headerStyle("FF6D28D9")
        val title = styles.xf(styles.font(16, bold = true, argb = "FFFFFFFF"), styles.fill("FF1E40AF"), borderId = 0)
        val subtitle = styles.xf(styles.font(10, bold = false, argb = "FFBFDBFE", italic = true), styles.fill("FF1E3A8A"), borderId = 0)

        val rowW = styles.xf(plain, styles.fill("FFFFFFFF"))
        val rowAlt = styles.xf(plain, styles.fill("FFF1F5F9"))
        val rowInc = styles.xf(plain, styles.fill("FFECFDF5"))
        val rowAltInc = styles.xf(plain, styles.fill("FFD1FAE5"))
        val rowExp = styles.xf(plain, styles.fill("FFFFF1F2"))
        val rowAltExp = styles.xf(plain, styles.fill("FFFFE4E6"))
        val rowXfer = styles.xf(plain, styles.fill("FFEFF6FF"))
        val rowAltXfer = styles.xf(plain, styles.fill("FFDBEAFE"))
        val rowSync = styles.xf(plain, styles.fill("FFF1F5F9"))
        val rowAltSync = styles.xf(plain, styles.fill("FFE2E8F0"))

        // Real Excel date values (not inline-string text) for the Date column, so Excel treats
        // them as genuine dates — sortable, filterable, and showing its native date picker —
        // one variant per row background so the Date cell still matches its row's tint.
        val dateFmt = styles.numFmt("yyyy-mm-dd")
        val dateInc = styles.xf(plain, styles.fill("FFECFDF5"), numFmtId = dateFmt)
        val dateAltInc = styles.xf(plain, styles.fill("FFD1FAE5"), numFmtId = dateFmt)
        val dateExp = styles.xf(plain, styles.fill("FFFFF1F2"), numFmtId = dateFmt)
        val dateAltExp = styles.xf(plain, styles.fill("FFFFE4E6"), numFmtId = dateFmt)
        val dateXfer = styles.xf(plain, styles.fill("FFEFF6FF"), numFmtId = dateFmt)
        val dateAltXfer = styles.xf(plain, styles.fill("FFDBEAFE"), numFmtId = dateFmt)
        val dateSync = styles.xf(plain, styles.fill("FFF1F5F9"), numFmtId = dateFmt)
        val dateAltSync = styles.xf(plain, styles.fill("FFE2E8F0"), numFmtId = dateFmt)

        val amtInc = styles.xf(greenBold, styles.fill("FFECFDF5"), numFmtId = currencyFmt)
        val amtAltInc = styles.xf(greenBold, styles.fill("FFD1FAE5"), numFmtId = currencyFmt)
        val amtExp = styles.xf(redBold, styles.fill("FFFFF1F2"), numFmtId = currencyFmt)
        val amtAltExp = styles.xf(redBold, styles.fill("FFFFE4E6"), numFmtId = currencyFmt)
        val amtBlue = styles.xf(blueBold, styles.fill("FFEFF6FF"), numFmtId = currencyFmt)
        val amtNeg = styles.xf(redBold, styles.fill("FFFFF1F2"), numFmtId = currencyFmt)
        val amtXfer = styles.xf(blueBold, styles.fill("FFEFF6FF"), numFmtId = currencyFmt)
        val amtAltXfer = styles.xf(blueBold, styles.fill("FFDBEAFE"), numFmtId = currencyFmt)
        // Neutral gray-blue (not green/red) — a Sync is a correction, not real income/expense.
        val syncBold = styles.font(11, bold = true, argb = "FF475569")
        val amtSync = styles.xf(syncBold, styles.fill("FFF1F5F9"), numFmtId = syncFmt)
        val amtAltSync = styles.xf(syncBold, styles.fill("FFE2E8F0"), numFmtId = syncFmt)

        val totLbl = styles.xf(navyBold, styles.fill("FFCBD5E1"))
        val totVal = styles.xf(navyBold, styles.fill("FFE2E8F0"), numFmtId = currencyFmt)
        val totValInc = styles.xf(styles.font(12, bold = true, argb = "FF047857"), styles.fill("FFD1FAE5"), numFmtId = currencyFmt)
        val totValExp = styles.xf(styles.font(12, bold = true, argb = "FFBE123C"), styles.fill("FFFFE4E6"), numFmtId = currencyFmt)
        val grayPlainFill = styles.fill("FFF8FAFC")

        // Category names stay plain black in the breakdown tables — only the amount is colored
        // (red for expense, green for income), so the two blocks read at a glance.
        val breakdownText = styles.xf(styles.font(10, bold = true, argb = "FF0F172A"), grayPlainFill)
        val breakdownExpAmt = styles.xf(styles.font(10, bold = true, argb = "FFBE123C"), grayPlainFill, numFmtId = currencyFmt)
        val breakdownIncAmt = styles.xf(styles.font(10, bold = true, argb = "FF047857"), grayPlainFill, numFmtId = currencyFmt)
        val pctStyle = styles.xf(grayPlain, grayPlainFill, numFmtId = pctFmt)

        val sheets = mutableListOf<SheetPlan>()

        // ─── SHEET 1: Monthly Summary ─────────────────────────────────────────
        val summarySheet = SheetPlan("Monthly Summary")
        summarySheet.colWidths = listOf(16.0, 17.0, 17.0, 17.0, 17.0, 18.0)
        summarySheet.addMergeRow("AutoLedger Financial Report", title, 6)
        summarySheet.addMergeRow("Generated: $generatedAt", subtitle, 6)
        summarySheet.addSpacer()
        summarySheet.addRow(
            CellV.Str("Month", hdrNavy), CellV.Str("Carry Over", hdrBlue),
            CellV.Str("Income", hdrTeal), CellV.Str("Expense", hdrPurp),
            CellV.Str("Monthly Net", hdrBlue), CellV.Str("Grand Total", hdrNavy)
        )
        sections.sortedByDescending { it.monthKey }.forEach { s ->
            val netStyle = if (s.net >= 0) amtInc else amtExp
            val closingStyle = if (s.closingBalance >= 0) amtBlue else amtNeg
            summarySheet.addRow(
                CellV.Str(s.monthKey, totLbl),
                CellV.Num(s.openingBalance, amtBlue),
                CellV.Num(s.income, amtInc),
                CellV.Num(s.expense, amtExp),
                CellV.Num(s.net, netStyle),
                CellV.Num(s.closingBalance, closingStyle)
            )
        }
        val grandInc = sections.sumOf { it.income }
        val grandExp = sections.sumOf { it.expense }
        summarySheet.addSpacer()
        summarySheet.addRow(
            CellV.Str("All-time Totals", totLbl), CellV.Empty(totLbl),
            CellV.Num(grandInc, totValInc),
            CellV.Num(grandExp, totValExp),
            CellV.Num(grandInc - grandExp, if (grandInc >= grandExp) totValInc else totValExp),
            CellV.Empty()
        )
        summarySheet.addSpacer()
        // Expense breakdown (cols A-C) and income breakdown (cols D-F) side by side, sharing
        // the same 6-column width as the monthly overview table above.
        sections.sortedByDescending { it.monthKey }.forEach { s ->
            val headerRowNum = summarySheet.rows.size + 1
            summarySheet.addRow(
                CellV.Str("${s.monthKey} \u2014 Expense Breakdown", hdrPurp), CellV.Empty(hdrPurp), CellV.Empty(hdrPurp),
                CellV.Str("${s.monthKey} \u2014 Income Breakdown", hdrTeal), CellV.Empty(hdrTeal), CellV.Empty(hdrTeal)
            )
            summarySheet.merges.add("A$headerRowNum:C$headerRowNum")
            summarySheet.merges.add("D$headerRowNum:F$headerRowNum")
            summarySheet.addRow(
                CellV.Str("Category", hdrBlue), CellV.Str("Share %", hdrBlue), CellV.Str("Amount", hdrBlue),
                CellV.Str("Category", hdrBlue), CellV.Str("Share %", hdrBlue), CellV.Str("Amount", hdrBlue)
            )
            val maxRows = maxOf(s.breakdown.size, s.incomeBreakdown.size)
            for (i in 0 until maxRows) {
                val exp = s.breakdown.getOrNull(i)
                val inc = s.incomeBreakdown.getOrNull(i)
                summarySheet.addRow(
                    if (exp != null) CellV.Str(exp.name, breakdownText) else CellV.Empty(),
                    if (exp != null) CellV.Num(exp.percentage, pctStyle) else CellV.Empty(),
                    if (exp != null) CellV.Num(exp.total, breakdownExpAmt) else CellV.Empty(),
                    if (inc != null) CellV.Str(inc.name, breakdownText) else CellV.Empty(),
                    if (inc != null) CellV.Num(inc.percentage, pctStyle) else CellV.Empty(),
                    if (inc != null) CellV.Num(inc.total, breakdownIncAmt) else CellV.Empty()
                )
            }
            summarySheet.addSpacer()
        }
        sheets += summarySheet

        // ─── SHEET 2: Account Summary ─────────────────────────────────────────
        val acctSheet = SheetPlan("Account Summary")
        acctSheet.colWidths = listOf(28.0, 18.0, 17.0, 16.0, 17.0, 17.0, 10.0)
        acctSheet.addMergeRow("Account Balances & Activity", title, 7)
        acctSheet.addMergeRow("Generated: $generatedAt", subtitle, 7)
        acctSheet.addSpacer()
        acctSheet.addRow(
            CellV.Str("Account / Wallet", hdrNavy), CellV.Str("Type", hdrBlue),
            CellV.Str("Current Balance", hdrBlue), CellV.Str("Credit Limit", hdrPurp),
            CellV.Str("Income (all-time)", hdrTeal), CellV.Str("Expense (all-time)", hdrPurp),
            CellV.Str("Tx Count", hdrBlue)
        )
        accounts.forEachIndexed { idx, acc ->
            val alt = idx % 2 != 0
            val rowS = if (alt) rowAlt else rowW
            val bal = computedBalances[acc.name] ?: acc.balance
            val balS = when {
                bal >= 0 && alt -> amtAltInc
                bal >= 0 -> amtInc
                alt -> amtAltExp
                else -> amtExp
            }
            val (inc, exp) = accountActivity[acc.name] ?: (0.0 to 0.0)
            val incS = if (alt) amtAltInc else amtInc
            val expS = if (alt) amtAltExp else amtExp
            val txCount = transactions.count { it.getAccountName() == acc.name }
            val typeLabel = when (acc.type) {
                "BANK" -> "Bank"; "CREDIT_CARD" -> "Credit Card"; "DEBIT_CARD" -> "Debit Card"
                "CASH" -> "Cash"; "WALLET" -> "Wallet"; else -> acc.type
            }
            acctSheet.addRow(
                CellV.Str(acc.name, rowS),
                CellV.Str(typeLabel, rowS),
                CellV.Num(bal, balS),
                if (acc.type == "CREDIT_CARD" && acc.creditLimit > 0) CellV.Num(acc.creditLimit, rowS) else CellV.Empty(rowS),
                CellV.Num(inc, incS),
                CellV.Num(exp, expS),
                CellV.Str("$txCount", rowS)
            )
        }
        val grandBal = computedBalances.values.sum()
        val grandInc2 = accountActivity.values.sumOf { it.first }
        val grandExp2 = accountActivity.values.sumOf { it.second }
        acctSheet.addSpacer()
        acctSheet.addRow(
            CellV.Str("Grand Total", totLbl), CellV.Empty(totLbl),
            CellV.Num(grandBal, if (grandBal >= 0) totValInc else totValExp),
            CellV.Empty(),
            CellV.Num(grandInc2, totValInc),
            CellV.Num(grandExp2, totValExp),
            CellV.Empty()
        )
        acctSheet.addSpacer()
        acctSheet.addMergeRow("Monthly Opening & Closing Balances", title, 4)
        // Month-wise, like the Monthly Summary breakdown blocks: one mini table per month
        // listing every account's opening/closing balance for that month.
        sections.sortedByDescending { it.monthKey }.forEach { s ->
            acctSheet.addMergeRow(s.monthKey, hdrBlue, 4)
            acctSheet.addRow(
                CellV.Str("Account", hdrNavy), CellV.Str("Opening Balance", hdrBlue),
                CellV.Str("Closing Balance", hdrBlue), CellV.Str("Net Change", hdrPurp)
            )
            accounts.forEachIndexed { idx, acc ->
                val mb = accountMonthlyBalances[acc.name].orEmpty().find { it.monthKey == s.monthKey }
                    ?: AccountMonthBalance(s.monthKey, acc.balance, acc.balance)
                val alt = idx % 2 != 0
                val rowS = if (alt) rowAlt else rowW
                val netChange = mb.closing - mb.opening
                val netStyle = when {
                    netChange > 0 -> if (alt) amtAltInc else amtInc
                    netChange < 0 -> if (alt) amtAltExp else amtExp
                    else -> if (alt) amtAltXfer else amtXfer
                }
                acctSheet.addRow(
                    CellV.Str(acc.name, rowS),
                    CellV.Num(mb.opening, if (alt) amtAltXfer else amtXfer),
                    CellV.Num(mb.closing, if (alt) amtAltXfer else amtXfer),
                    CellV.Num(netChange, netStyle)
                )
            }
            acctSheet.addSpacer()
        }
        sheets += acctSheet

        // ─── PER-MONTH SHEETS ─────────────────────────────────────────────────
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Source lists for the Account/Category/Date dropdowns (data validation) on every
        // per-month sheet — kept on a hidden "Lists" sheet since Excel list-validation must
        // point at cells rather than an inline literal list (which also has a 255-char limit).
        val distinctAccountNames = accounts.map { it.name }.distinct().sorted()
        val distinctCategoryNames = (transactions.map { CategoryResolver.resolve(it.category, customCategories).displayName } +
            listOf("Transfer", "Balance Sync")).distinct().sorted()
        val distinctDateSerials = transactions.map { excelSerialDate(it.timestamp) }.distinct().sorted()
        val listsSheet = SheetPlan("Lists")
        listsSheet.hidden = true
        val listRows = maxOf(distinctAccountNames.size, distinctCategoryNames.size, distinctDateSerials.size)
        for (i in 0 until listRows) {
            listsSheet.addRow(
                if (i < distinctAccountNames.size) CellV.Str(distinctAccountNames[i], rowW) else CellV.Empty(),
                if (i < distinctCategoryNames.size) CellV.Str(distinctCategoryNames[i], rowW) else CellV.Empty(),
                if (i < distinctDateSerials.size) CellV.Num(distinctDateSerials[i], dateXfer) else CellV.Empty()
            )
        }

        sections.sortedByDescending { it.monthKey }.forEach { s ->
            val wsName = sanitizeWorksheetName(s.monthKey)
            val sheet = SheetPlan(wsName)
            sheet.colWidths = listOf(14.0, 14.0, 24.0, 24.0, 24.0, 15.0)

            sheet.addMergeRow("${s.monthKey} Transactions", title, 6)
            sheet.addRow(
                CellV.Str("Income", hdrTeal), CellV.Num(s.income, amtInc),
                CellV.Str("Expense", hdrPurp), CellV.Num(s.expense, amtExp),
                CellV.Str("Net", hdrBlue), CellV.Num(s.net, if (s.net >= 0) amtInc else amtExp)
            )
            sheet.addSpacer()

            val headerRowIdx = sheet.rows.size + 1
            sheet.addRow(
                CellV.Str("Date", hdrNavy), CellV.Str("Time", hdrNavy),
                CellV.Str("Payee", hdrBlue), CellV.Str("Account", hdrBlue),
                CellV.Str("Category", hdrBlue), CellV.Str("Amount", hdrBlue)
            )

            // Duplicates represent no real money movement — excluded from the export entirely.
            val sortedTx = s.transactions.filter { it.type != "DUPLICATE" }.sortedByDescending { it.timestamp }
            sortedTx.forEachIndexed { idx, tx ->
                val alt = idx % 2 != 0
                val isInc = tx.type == "INCOME"
                val isExp = tx.type == "EXPENSE"
                val isTransfer = tx.type == "TRANSFER"
                val isSync = tx.type == "BALANCE_UPDATE"

                val categoryLabel = when {
                    isTransfer -> "Transfer"
                    isSync -> "Balance Sync"
                    else -> CategoryResolver.resolve(tx.category, customCategories).displayName
                }
                val walletLabel = if (isTransfer) {
                    val n = tx.note ?: ""
                    val s2 = n.indexOf("[To: "); val e2 = if (s2 >= 0) n.indexOf("]", s2 + 5) else -1
                    val dest = if (s2 >= 0 && e2 > s2) n.substring(s2 + 5, e2) else null
                    if (dest != null) "${tx.getAccountName()} \u2192 $dest" else tx.getAccountName()
                } else tx.getAccountName()

                val rowS = when {
                    isTransfer -> if (alt) rowAltXfer else rowXfer
                    isSync -> if (alt) rowAltSync else rowSync
                    isInc -> if (alt) rowAltInc else rowInc
                    isExp -> if (alt) rowAltExp else rowExp
                    else -> if (alt) rowAlt else rowW
                }
                val dateS = when {
                    isTransfer -> if (alt) dateAltXfer else dateXfer
                    isSync -> if (alt) dateAltSync else dateSync
                    isInc -> if (alt) dateAltInc else dateInc
                    isExp -> if (alt) dateAltExp else dateExp
                    else -> dateXfer
                }
                val amtS = when {
                    isTransfer -> if (alt) amtAltXfer else amtXfer
                    isSync -> if (alt) amtAltSync else amtSync
                    isInc -> if (alt) amtAltInc else amtInc
                    isExp -> if (alt) amtAltExp else amtExp
                    else -> amtBlue
                }
                val amtValue = when {
                    isTransfer -> tx.amount
                    isSync -> syncDelta[tx.id] ?: 0.0
                    isInc -> tx.amount
                    isExp -> -tx.amount
                    else -> tx.amount
                }

                sheet.addRow(
                    CellV.Num(excelSerialDate(tx.timestamp), dateS),
                    CellV.Str(timeFormat.format(Date(tx.timestamp)), rowS),
                    CellV.Str(tx.title, rowS),
                    CellV.Str(walletLabel, rowS),
                    CellV.Str(categoryLabel, rowS),
                    CellV.Num(amtValue, amtS)
                )
            }

            if (sortedTx.isNotEmpty()) {
                val firstDataRow = headerRowIdx + 1
                val lastDataRow = headerRowIdx + sortedTx.size
                sheet.dataValidations += "<dataValidation type=\"list\" allowBlank=\"1\" showInputMessage=\"1\" showErrorMessage=\"0\" sqref=\"A$firstDataRow:A$lastDataRow\"><formula1>Lists!\$C\$1:\$C\$${distinctDateSerials.size}</formula1></dataValidation>"
                sheet.dataValidations += "<dataValidation type=\"list\" allowBlank=\"1\" showInputMessage=\"1\" showErrorMessage=\"0\" sqref=\"D$firstDataRow:D$lastDataRow\"><formula1>Lists!\$A\$1:\$A\$${distinctAccountNames.size}</formula1></dataValidation>"
                sheet.dataValidations += "<dataValidation type=\"list\" allowBlank=\"1\" showInputMessage=\"1\" showErrorMessage=\"0\" sqref=\"E$firstDataRow:E$lastDataRow\"><formula1>Lists!\$B\$1:\$B\$${distinctCategoryNames.size}</formula1></dataValidation>"
            }

            sheet.addSpacer()
            sheet.addRow(CellV.Str("Carry Over Balance", totLbl), CellV.Num(s.openingBalance, totVal))
            sheet.addRow(CellV.Str("Monthly Net", totLbl), CellV.Num(s.net, if (s.net >= 0) totValInc else totValExp))
            sheet.addRow(CellV.Str("Grand Total", totLbl), CellV.Num(s.closingBalance, totVal))
            sheets += sheet
        }

        sheets += listsSheet

        return writeXlsx(sheets, styles)
    }

    // ─── XLSX (OOXML zip) assembly ────────────────────────────────────────────

    private fun writeXlsx(sheets: List<SheetPlan>, styles: StyleSheetBuilder): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun put(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            put("[Content_Types].xml", contentTypesXml(sheets))
            put("_rels/.rels", rootRelsXml())
            put("xl/workbook.xml", workbookXml(sheets))
            put("xl/_rels/workbook.xml.rels", workbookRelsXml(sheets))
            put("xl/styles.xml", styles.toXml())
            sheets.forEachIndexed { i, sheet ->
                put("xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet))
            }
        }
        return out.toByteArray()
    }

    private fun contentTypesXml(sheets: List<SheetPlan>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        sheets.forEachIndexed { i, sheet ->
            append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun rootRelsXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private fun workbookXml(sheets: List<SheetPlan>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<sheets>")
        sheets.forEachIndexed { i, sheet ->
            val hiddenAttr = if (sheet.hidden) " state=\"hidden\"" else ""
            append("<sheet name=\"${escapeXml(sheet.name)}\" sheetId=\"${i + 1}\"$hiddenAttr r:id=\"rId${i + 1}\"/>")
        }
        append("</sheets>")
        append("</workbook>")
    }

    private fun workbookRelsXml(sheets: List<SheetPlan>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sheets.forEachIndexed { i, _ ->
            append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
        }
        append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun sheetXml(sheet: SheetPlan): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        if (sheet.colWidths.isNotEmpty()) {
            append("<cols>")
            sheet.colWidths.forEachIndexed { i, w -> append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${"%.2f".format(Locale.US, w)}\" customWidth=\"1\"/>") }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { rIdx, cells ->
            val rowNum = rIdx + 1
            append("<row r=\"$rowNum\">")
            cells.forEachIndexed { cIdx, cell ->
                val ref = "${colLetter(cIdx + 1)}$rowNum"
                when (cell) {
                    is CellV.Str -> append("<c r=\"$ref\" s=\"${cell.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(cell.text)}</t></is></c>")
                    is CellV.Num -> append("<c r=\"$ref\" s=\"${cell.style}\"><v>${"%.4f".format(Locale.US, cell.value)}</v></c>")
                    is CellV.Empty -> if (cell.style != 0) append("<c r=\"$ref\" s=\"${cell.style}\"/>")
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        if (sheet.merges.isNotEmpty()) {
            append("<mergeCells count=\"${sheet.merges.size}\">")
            sheet.merges.forEach { append("<mergeCell ref=\"$it\"/>") }
            append("</mergeCells>")
        }
        if (sheet.dataValidations.isNotEmpty()) {
            append("<dataValidations count=\"${sheet.dataValidations.size}\">")
            sheet.dataValidations.forEach { append(it) }
            append("</dataValidations>")
        }
        append("</worksheet>")
    }

    // ─── Data helpers ────────────────────────────────────────────────────────

    private fun buildMonthlySections(
        transactions: List<TransactionEntry>,
        customCategories: List<CustomCategory>
    ): List<MonthlyExportSection> {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val grouped = transactions.groupBy { monthFormat.format(Date(it.timestamp)) }.toSortedMap()
        val sections = mutableListOf<MonthlyExportSection>()
        var carryOver = 0.0
        grouped.forEach { (monthKey, monthTx) ->
            val income = monthTx.filter { it.type == "INCOME" }.sumOf { it.amount }
            val expense = monthTx.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            val net = income - expense
            val closing = carryOver + net
            val expTx = monthTx.filter { it.type == "EXPENSE" }
            val totExp = expTx.sumOf { it.amount }
            val breakdown = expTx.groupBy { it.category }.map { (cat, entries) ->
                val tot = entries.sumOf { it.amount }
                val resolved = CategoryResolver.resolve(cat, customCategories)
                MonthlyCategoryBreakdown(
                    name = resolved.displayName,
                    total = tot,
                    percentage = if (totExp > 0.0) tot / totExp else 0.0
                )
            }.sortedByDescending { it.total }
            val incTx = monthTx.filter { it.type == "INCOME" }
            val totInc = incTx.sumOf { it.amount }
            val incomeBreakdown = incTx.groupBy { it.category }.map { (cat, entries) ->
                val tot = entries.sumOf { it.amount }
                val resolved = CategoryResolver.resolve(cat, customCategories)
                MonthlyCategoryBreakdown(
                    name = resolved.displayName,
                    total = tot,
                    percentage = if (totInc > 0.0) tot / totInc else 0.0
                )
            }.sortedByDescending { it.total }
            sections += MonthlyExportSection(monthKey, carryOver, income, expense, net, closing, monthTx, breakdown, incomeBreakdown)
            carryOver = closing
        }
        return sections
    }

    private fun sanitizeWorksheetName(name: String): String =
        name.replace(Regex("[\\\\/?*\\[\\]:]"), "-").take(31)

    /** Month-by-month opening/closing balance per account, carrying the running balance
     * forward across months and resetting it whenever a Balance Sync snapshot occurs. */
    private fun computeAccountMonthlyBalances(
        accounts: List<Account>,
        transactions: List<TransactionEntry>,
        allMonthKeys: List<String>
    ): Map<String, List<AccountMonthBalance>> {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val result = mutableMapOf<String, List<AccountMonthBalance>>()
        for (acc in accounts) {
            val relevant = transactions.filter { tx ->
                when (tx.type) {
                    "INCOME", "EXPENSE", "BALANCE_UPDATE" -> tx.getAccountName() == acc.name
                    "TRANSFER" -> tx.getAccountName() == acc.name || run {
                        val n = tx.note ?: ""; val s = n.indexOf("[To: "); val e = if (s >= 0) n.indexOf("]", s + 5) else -1
                        (if (s >= 0 && e > s) n.substring(s + 5, e) else null) == acc.name
                    }
                    else -> false
                }
            }.sortedWith(compareBy({ it.timestamp }, { if (it.type == "BALANCE_UPDATE") 1 else 0 }))
            val grouped = relevant.groupBy { monthFormat.format(Date(it.timestamp)) }
            var running = acc.balance
            val monthly = mutableListOf<AccountMonthBalance>()
            // Forward-fill every month in the workbook (not just months this account was active
            // in) so every account has a row in each month's block on the Account Summary sheet.
            for (monthKey in allMonthKeys) {
                val opening = running
                for (tx in grouped[monthKey].orEmpty()) {
                    when {
                        tx.type == "BALANCE_UPDATE" -> running = tx.amount
                        tx.type == "TRANSFER" && tx.getAccountName() == acc.name -> running -= tx.amount
                        tx.type == "TRANSFER" -> running += tx.amount // inbound (this acc is the destination)
                        tx.type == "INCOME" -> running += tx.amount
                        tx.type == "EXPENSE" -> running -= tx.amount
                    }
                }
                monthly += AccountMonthBalance(monthKey, opening, running)
            }
            result[acc.name] = monthly
        }
        return result
    }
}

// Top-level so SheetPlan/StyleSheetBuilder (declared outside the object) can also use them.
private fun colLetter(n: Int): String {
    var num = n
    val sb = StringBuilder()
    while (num > 0) {
        val rem = (num - 1) % 26
        sb.insert(0, ('A' + rem))
        num = (num - 1) / 26
    }
    return sb.toString()
}

private fun escapeXml(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;")

// Excel's date system counts days since 1899-12-30; 25569 is the serial number for the Unix
// epoch (1970-01-01). Whole-number local calendar day (not raw millis/86400000, which keeps a
// fractional time-of-day component and made the cell display a time alongside the date).
private fun excelSerialDate(millis: Long): Double {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val localMidnight = cal.timeInMillis

    val epochCal = java.util.Calendar.getInstance()
    epochCal.clear()
    epochCal.set(1970, java.util.Calendar.JANUARY, 1, 0, 0, 0)
    val epochMidnight = epochCal.timeInMillis

    val days = Math.round((localMidnight - epochMidnight) / 86400000.0)
    return days + 25569.0
}
