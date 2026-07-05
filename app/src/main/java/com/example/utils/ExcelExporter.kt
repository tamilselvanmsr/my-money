package com.example.utils

import com.example.data.Account
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

    fun exportToExcelBytes(
        transactions: List<TransactionEntry>,
        accounts: List<Account>,
        customCategories: List<CustomCategory>
    ): ByteArray {
        val sections = buildMonthlySections(transactions, customCategories)
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        // ── Compute actual account balances (mirrors computeWalletBalances logic) ──
        // Step 1: find the latest BALANCE_UPDATE snapshot per account name
        val latestSnap = mutableMapOf<String, Pair<Long, Double>>()
        for (tx in transactions) {
            if (tx.type != "BALANCE_UPDATE") continue
            val name = tx.getAccountName()
            val prev = latestSnap[name]
            if (prev == null || tx.timestamp > prev.first) latestSnap[name] = tx.timestamp to tx.amount
        }
        // Step 2: starting balance = snapshot if present, else account.balance
        // Step 3: apply all regular transactions AFTER the snapshot
        val computedBalances: Map<String, Double> = accounts.associate { acc ->
            val snap = latestSnap[acc.name]
            var bal = snap?.second ?: acc.balance
            for (tx in transactions) {
                if (tx.type == "DUPLICATE" || tx.type == "BALANCE_UPDATE") continue
                if (snap != null && tx.timestamp <= snap.first) continue
                when {
                    tx.type == "INCOME"   && tx.getAccountName() == acc.name -> bal += tx.amount
                    tx.type == "EXPENSE"  && tx.getAccountName() == acc.name -> bal -= tx.amount
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
        // Per-account activity totals (all-time income + expense)
        val accountActivity: Map<String, Pair<Double, Double>> = transactions
            .filter { it.type == "INCOME" || it.type == "EXPENSE" }
            .groupBy { it.getAccountName() }
            .mapValues { (_, txList) ->
                txList.filter { it.type == "INCOME" }.sumOf { it.amount } to
                    txList.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            }

        val workbook = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"")
            append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"")
            append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"")
            append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">")

            // ─── STYLES ───────────────────────────────────────────────────────────
            append("<Styles>")
            append(style("Default",  font("Calibri", 10, color = "#1E293B"), align = "Center"))
            // Title block — use rich blue, not near-black
            append(style("title",    font("Calibri", 16, bold = true, color = "#FFFFFF"), bg = "#1E40AF"))
            append(style("subtitle", font("Calibri", 10, italic = true, color = "#BFDBFE"), bg = "#1E3A8A"))
            // Column headers
            append(style("hdrBlue",  font("Calibri", 10, bold = true, color = "#FFFFFF"), bg = "#0369A1", borders = true))
            append(style("hdrNavy",  font("Calibri", 10, bold = true, color = "#FFFFFF"), bg = "#1E3A8A", borders = true))
            append(style("hdrTeal",  font("Calibri", 10, bold = true, color = "#FFFFFF"), bg = "#0F766E", borders = true))
            append(style("hdrPurp",  font("Calibri", 10, bold = true, color = "#FFFFFF"), bg = "#6D28D9", borders = true))
            // Data rows – plain
            append(style("rowW",     font("Calibri", 10, color = "#0F172A"), bg = "#FFFFFF", borders = true))
            append(style("rowAlt",   font("Calibri", 10, color = "#0F172A"), bg = "#F1F5F9", borders = true))
            // Data rows – income tinted
            append(style("rowInc",    font("Calibri", 10, color = "#065F46"), bg = "#ECFDF5", borders = true))
            append(style("rowAltInc", font("Calibri", 10, color = "#065F46"), bg = "#D1FAE5", borders = true))
            // Data rows – expense tinted
            append(style("rowExp",    font("Calibri", 10, color = "#881337"), bg = "#FFF1F2", borders = true))
            append(style("rowAltExp", font("Calibri", 10, color = "#881337"), bg = "#FFE4E6", borders = true))
            // Amount cells
            append(style("amtInc",    font("Calibri", 10, bold = true, color = "#047857"), bg = "#ECFDF5", borders = true, numFmt = "₹#,##0.00"))
            append(style("amtAltInc", font("Calibri", 10, bold = true, color = "#047857"), bg = "#D1FAE5", borders = true, numFmt = "₹#,##0.00"))
            append(style("amtExp",    font("Calibri", 10, bold = true, color = "#BE123C"), bg = "#FFF1F2", borders = true, numFmt = "₹#,##0.00"))
            append(style("amtAltExp", font("Calibri", 10, bold = true, color = "#BE123C"), bg = "#FFE4E6", borders = true, numFmt = "₹#,##0.00"))
            append(style("amtBlue",   font("Calibri", 10, bold = true, color = "#1D4ED8"), bg = "#EFF6FF", borders = true, numFmt = "₹#,##0.00"))
            append(style("amtNeg",    font("Calibri", 10, bold = true, color = "#BE123C"), bg = "#FFF1F2", borders = true, numFmt = "₹#,##0.00"))
            // Total / summary
            append(style("totLbl",    font("Calibri", 10, bold = true, color = "#0F172A"), bg = "#CBD5E1", borders = true))
            append(style("totVal",    font("Calibri", 10, bold = true, color = "#0F172A"), bg = "#E2E8F0", borders = true, numFmt = "₹#,##0.00"))
            append(style("totValInc", font("Calibri", 11, bold = true, color = "#047857"), bg = "#D1FAE5", borders = true, numFmt = "₹#,##0.00"))
            append(style("totValExp", font("Calibri", 11, bold = true, color = "#BE123C"), bg = "#FFE4E6", borders = true, numFmt = "₹#,##0.00"))
            // Percentage
            append(style("pct", font("Calibri", 10, color = "#475569"), bg = "#F8FAFC", borders = true))
            // Dynamic category colors — colored TEXT on light background (avoids dark/black cells)
            sections.flatMap { it.breakdown }.map { it.colorHex }.distinct().forEach { hex ->
                val id = "c${hex.removePrefix("#").uppercase()}"
                append("<Style ss:ID=\"$id\">")
                append("<Font ss:FontName=\"Calibri\" ss:Size=\"10\" ss:Bold=\"1\" ss:Color=\"$hex\"/>")
                append("<Interior ss:Color=\"#F8FAFC\" ss:Pattern=\"Solid\"/>")
                append("<Borders>")
                listOf("Bottom", "Left", "Right", "Top").forEach { pos ->
                    append("<Border ss:Position=\"$pos\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"#CBD5E1\"/>")
                }
                append("</Borders>")
                append("</Style>")
            }
            append("</Styles>")

            // ─── SHEET 1: Monthly Summary ─────────────────────────────────────────
            append("<Worksheet ss:Name=\"Monthly Summary\">")
            append("<Table>")
            append("<Column ss:Width=\"110\"/>") // Month
            append("<Column ss:Width=\"115\"/>") // Carry Over
            append("<Column ss:Width=\"115\"/>") // Income
            append("<Column ss:Width=\"115\"/>") // Expense
            append("<Column ss:Width=\"115\"/>") // Net
            append("<Column ss:Width=\"120\"/>") // Grand Total

            append(mergeRow("MyMoney Financial Report", "title", 5))
            append(mergeRow("Generated: $generatedAt", "subtitle", 5))
            append(row(ec()))
            append(row(
                tc("Month", "hdrNavy"), tc("Carry Over", "hdrBlue"),
                tc("Income", "hdrTeal"), tc("Expense", "hdrPurp"),
                tc("Monthly Net", "hdrBlue"), tc("Grand Total", "hdrNavy")
            ))

            sections.sortedByDescending { it.monthKey }.forEach { s ->
                val netStyle = if (s.net >= 0) "amtInc" else "amtExp"
                val closingStyle = if (s.closingBalance >= 0) "amtBlue" else "amtNeg"
                append(row(
                    tc(s.monthKey, "totLbl"),
                    nc(s.openingBalance, "amtBlue"),
                    nc(s.income, "amtInc"),
                    nc(s.expense, "amtExp"),
                    nc(s.net, netStyle),
                    nc(s.closingBalance, closingStyle)
                ))
            }

            // All-time totals
            val grandInc = sections.sumOf { it.income }
            val grandExp = sections.sumOf { it.expense }
            append(row(ec()))
            append(row(
                tc("All-time Totals", "totLbl"), ec(),
                nc(grandInc, "totValInc"),
                nc(grandExp, "totValExp"),
                nc(grandInc - grandExp, if (grandInc >= grandExp) "totValInc" else "totValExp"),
                ec()
            ))
            append(row(ec()))

            // Category breakdown embedded
            sections.sortedByDescending { it.monthKey }.forEach { s ->
                append(mergeRow("${s.monthKey} — Category Breakdown", "hdrBlue", 2))
                append(row(tc("Category", "hdrBlue"), tc("Share %", "hdrBlue"), tc("Amount", "hdrBlue")))
                s.breakdown.forEach { entry ->
                    val id = "c${entry.colorHex.removePrefix("#").uppercase()}"
                    append(row(
                        tc(entry.name, id),
                        tc(String.format(Locale.getDefault(), "%.1f%%", entry.percentage * 100), "pct"),
                        nc(entry.total, id)
                    ))
                }
                append(row(ec()))
            }

            append("</Table>")
            append(freezeRows(4))
            append("</Worksheet>")

            // ─── SHEET 2: Account Summary ─────────────────────────────────────────
            append("<Worksheet ss:Name=\"Account Summary\">")
            append("<Table>")
            append("<Column ss:Width=\"190\"/>") // Account Name
            append("<Column ss:Width=\"90\"/>")  // Type
            append("<Column ss:Width=\"120\"/>") // Current Balance
            append("<Column ss:Width=\"110\"/>") // Credit Limit
            append("<Column ss:Width=\"115\"/>") // Income Activity
            append("<Column ss:Width=\"115\"/>") // Expense Activity
            append("<Column ss:Width=\"70\"/>")  // Tx Count

            append(mergeRow("Account Balances & Activity", "title", 6))
            append(mergeRow("Generated: $generatedAt", "subtitle", 6))
            append(row(ec()))
            append(row(
                tc("Account / Wallet", "hdrNavy"),
                tc("Type",            "hdrBlue"),
                tc("Current Balance", "hdrBlue"),
                tc("Credit Limit",    "hdrPurp"),
                tc("Income (all-time)",  "hdrTeal"),
                tc("Expense (all-time)", "hdrPurp"),
                tc("Tx Count",        "hdrBlue")
            ))

            accounts.forEachIndexed { idx, acc ->
                val alt  = idx % 2 != 0
                val rowS = if (alt) "rowAlt" else "rowW"
                val bal  = computedBalances[acc.name] ?: acc.balance
                val balS = when {
                    bal >= 0 && alt  -> "amtAltInc"
                    bal >= 0         -> "amtInc"
                    alt              -> "amtAltExp"
                    else             -> "amtExp"
                }
                val (inc, exp) = accountActivity[acc.name] ?: (0.0 to 0.0)
                val incS = if (alt) "amtAltInc" else "amtInc"
                val expS = if (alt) "amtAltExp" else "amtExp"
                val txCount = transactions.count { it.getAccountName() == acc.name }
                val typeLabel = when (acc.type) {
                    "BANK"        -> "Bank"
                    "CREDIT_CARD" -> "Credit Card"
                    "CASH"        -> "Cash"
                    "WALLET"      -> "Wallet"
                    else          -> acc.type
                }
                append(row(
                    tc(acc.name,    rowS),
                    tc(typeLabel,   rowS),
                    nc(bal,         balS),
                    if (acc.type == "CREDIT_CARD" && acc.creditLimit > 0) nc(acc.creditLimit, rowS) else ec(),
                    nc(inc,         incS),
                    nc(exp,         expS),
                    tc("$txCount",  rowS)
                ))
            }

            // Grand totals row
            val grandBal = computedBalances.values.sum()
            val grandInc2 = accountActivity.values.sumOf { it.first }
            val grandExp2 = accountActivity.values.sumOf { it.second }
            append(row(ec()))
            append(row(
                tc("Grand Total", "totLbl"), ec(),
                nc(grandBal,  if (grandBal >= 0) "totValInc" else "totValExp"),
                ec(),
                nc(grandInc2, "totValInc"),
                nc(grandExp2, "totValExp"),
                ec()
            ))

            append("</Table>")
            append(freezeRows(4))
            append("</Worksheet>")

            // ─── PER-MONTH SHEETS ─────────────────────────────────────────────────
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            sections.sortedByDescending { it.monthKey }.forEach { s ->
                val wsName = sanitizeWorksheetName(s.monthKey)
                append("<Worksheet ss:Name=\"${escapeXml(wsName)}\">")
                append("<Table>")
                append("<Column ss:Width=\"90\"/>")  // Date
                append("<Column ss:Width=\"52\"/>")  // Time
                append("<Column ss:Width=\"175\"/>") // Payee
                append("<Column ss:Width=\"140\"/>") // Wallet
                append("<Column ss:Width=\"115\"/>") // Category
                append("<Column ss:Width=\"105\"/>") // Amount
                append("<Column ss:Width=\"70\"/>")  // Type

                append(mergeRow("${s.monthKey} Transactions", "title", 6))
                append(row(
                    tc("Income", "hdrTeal"), nc(s.income, "amtInc"),
                    tc("Expense", "hdrPurp"), nc(s.expense, "amtExp"),
                    tc("Net", "hdrBlue"),
                    nc(s.net, if (s.net >= 0) "amtInc" else "amtExp"),
                    ec()
                ))
                append(row(ec()))
                append(row(
                    tc("Date", "hdrNavy"), tc("Time", "hdrNavy"),
                    tc("Payee", "hdrBlue"), tc("Wallet / Bank", "hdrBlue"),
                    tc("Category", "hdrBlue"), tc("Amount", "hdrBlue"),
                    tc("Type", "hdrBlue")
                ))

                s.transactions.sortedByDescending { it.timestamp }.forEachIndexed { idx, tx ->
                    val resolved = CategoryResolver.resolve(tx.category, customCategories)
                    val isInc = tx.type == "INCOME"
                    val alt = idx % 2 != 0
                    val rowS = when {
                        isInc && alt  -> "rowAltInc"
                        isInc         -> "rowInc"
                        !isInc && alt -> "rowAltExp"
                        else          -> "rowExp"
                    }
                    val amtS = when {
                        isInc && alt  -> "amtAltInc"
                        isInc         -> "amtInc"
                        !isInc && alt -> "amtAltExp"
                        else          -> "amtExp"
                    }
                    append(row(
                        tc(dateFormat.format(Date(tx.timestamp)), rowS),
                        tc(timeFormat.format(Date(tx.timestamp)), rowS),
                        tc(tx.title, rowS),
                        tc(tx.getAccountName(), rowS),
                        tc(resolved.displayName, rowS),
                        nc(if (isInc) tx.amount else -tx.amount, amtS),
                        tc(tx.type, rowS)
                    ))
                }

                append(row(ec()))
                append(row(tc("Carry Over Balance", "totLbl"), nc(s.openingBalance, "totVal")))
                append(row(tc("Monthly Net", "totLbl"), nc(s.net, if (s.net >= 0) "totValInc" else "totValExp")))
                append(row(tc("Grand Total", "totLbl"), nc(s.closingBalance, "totVal")))
                append("</Table>")
                append(freezeRows(4))
                append("</Worksheet>")
            }

            append("</Workbook>")
        }

        return workbook.toByteArray(Charsets.UTF_8)
    }

    // ─── Style builder ───────────────────────────────────────────────────────

    private fun font(
        name: String,
        size: Int,
        bold: Boolean = false,
        italic: Boolean = false,
        color: String = "#0F172A"
    ) = buildString {
        append("<Font ss:FontName=\"$name\" ss:Size=\"$size\" ss:Color=\"$color\"")
        if (bold) append(" ss:Bold=\"1\"")
        if (italic) append(" ss:Italic=\"1\"")
        append("/>")
    }

    private fun style(
        id: String,
        fontXml: String,
        bg: String? = null,
        borders: Boolean = false,
        numFmt: String? = null,
        align: String? = null
    ) = buildString {
        append("<Style ss:ID=\"$id\">")
        append(fontXml)
        if (bg != null) append("<Interior ss:Color=\"$bg\" ss:Pattern=\"Solid\"/>")
        if (borders) {
            append("<Borders>")
            listOf("Bottom", "Left", "Right", "Top").forEach { pos ->
                append("<Border ss:Position=\"$pos\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"#CBD5E1\"/>")
            }
            append("</Borders>")
        }
        if (numFmt != null) append("<NumberFormat ss:Format=\"$numFmt\"/>")
        if (align != null) append("<Alignment ss:Vertical=\"Center\" ss:Horizontal=\"$align\"/>")
        append("</Style>")
    }

    private fun freezeRows(count: Int) = buildString {
        append("<WorksheetOptions xmlns=\"urn:schemas-microsoft-com:office:excel\">")
        append("<FreezePanes/><FrozenNoSplit/>")
        append("<SplitHorizontal>$count</SplitHorizontal>")
        append("<TopRowBottomPane>$count</TopRowBottomPane>")
        append("<ActivePane>2</ActivePane>")
        append("</WorksheetOptions>")
    }

    // ─── Cell / row helpers ──────────────────────────────────────────────────

    private fun row(vararg cells: String): String = "<Row>${cells.joinToString("")}</Row>"
    /** Outputs a full-width merged header row spanning (mergeAcross+1) columns. */
    private fun mergeRow(text: String, styleId: String, mergeAcross: Int): String =
        "<Row><Cell ss:StyleID=\"$styleId\" ss:MergeAcross=\"$mergeAcross\"><Data ss:Type=\"String\">${escapeXml(text)}</Data></Cell></Row>"
    private fun tc(text: String, styleId: String): String =
        "<Cell ss:StyleID=\"$styleId\"><Data ss:Type=\"String\">${escapeXml(text)}</Data></Cell>"
    private fun nc(amount: Double, styleId: String): String =
        "<Cell ss:StyleID=\"$styleId\"><Data ss:Type=\"Number\">$amount</Data></Cell>"
    private fun ec(): String = "<Cell><Data ss:Type=\"String\"></Data></Cell>"

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
            val income  = monthTx.filter { it.type == "INCOME"  }.sumOf { it.amount }
            val expense = monthTx.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            val net     = income - expense
            val closing = carryOver + net
            val expTx   = monthTx.filter { it.type == "EXPENSE" }
            val totExp  = expTx.sumOf { it.amount }
            val breakdown = expTx.groupBy { it.category }.map { (cat, entries) ->
                val tot = entries.sumOf { it.amount }
                val resolved = CategoryResolver.resolve(cat, customCategories)
                MonthlyCategoryBreakdown(
                    name       = resolved.displayName,
                    colorHex   = colorToHex(resolved.color.value.toInt()),
                    total      = tot,
                    percentage = if (totExp > 0.0) tot / totExp else 0.0
                )
            }.sortedByDescending { it.total }
            sections += MonthlyExportSection(monthKey, carryOver, income, expense, net, closing, monthTx, breakdown)
            carryOver = closing
        }
        return sections
    }

    private fun colorToHex(colorValue: Int): String =
        String.format("#%06X", 0xFFFFFF and colorValue)

    private fun sanitizeWorksheetName(name: String): String =
        name.replace(Regex("[\\\\/?*\\[\\]:]"), "-").take(31)

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}