package com.example

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.*
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.FinanceViewModel
import com.example.viewmodel.DisplayMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import android.text.format.DateFormat as SystemDateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.ui.graphics.toArgb

// Enum representing the five core tabs mimicking MyMoney by Ananta Raha
enum class AppTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DASHBOARD("Records", Icons.Default.ReceiptLong),
    ANALYTICS("Analysis", Icons.Default.DonutLarge),
    BUDGETS("Budgets", Icons.Default.Calculate),
    ACCOUNT("Accounts", Icons.Default.AccountBalanceWallet),
    AUTO_SCAN("SMS Scan", Icons.Default.Security)
}

// Pseudo accounts parsing extension helpers
fun TransactionEntry.getRawAccountName(): String {
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

fun TransactionEntry.getAccountName(consolidate: Boolean = false): String {
    val rawName = getRawAccountName()
    if (!consolidate) return rawName
    val lower = rawName.lowercase()
    return when {
        lower.contains("card") || lower.contains("credit") -> "Credit Card"
        lower.contains("bank") || lower.contains("sbi") || lower.contains("hdfc") || lower.contains("icici") || lower.contains("axis") || lower.contains("pnb") -> "Bank Account"
        lower.contains("cash") -> "Cash Wallet"
        lower.contains("wallet") -> "Digital Wallet"
        else -> "Bank Account"
    }
}

fun makeNoteWithAccount(plainNote: String?, accountName: String): String {
    val clean = plainNote ?: ""
    return "$clean [Acc: $accountName]".trim()
}

// Base balances helper
fun computeWalletBalances(
    transactions: List<TransactionEntry>,
    accountsList: List<Account> = emptyList(),
    carryOverPreviousAmount: Boolean = true,
    consolidate: Boolean = false
): Map<String, Double> {
    val balances = mutableMapOf<String, Double>()

    // Step 1 — find the latest BALANCE_UPDATE snapshot per actual account name.
    // Always use consolidate=false so we key by real account name, not generic type.
    val latestSnap = mutableMapOf<String, Pair<Long, Double>>()  // actualName -> (timestamp, absoluteBalance)
    for (tx in transactions) {
        if (tx.type != "BALANCE_UPDATE") continue
        val actualName = tx.getAccountName(consolidate = false)
        val prev = latestSnap[actualName]
        if (prev == null || tx.timestamp > prev.first) {
            latestSnap[actualName] = Pair(tx.timestamp, tx.amount)
        }
    }

    // Step 2 — initialise each balance bucket from snapshot (if present) else account.balance.
    if (accountsList.isEmpty()) {
        balances["Cash Wallet"] = 0.0
        balances["Bank Account"] = 0.0
        balances["Credit Card"] = 0.0
        balances["Digital Wallet"] = 0.0
    } else {
        if (consolidate) {
            balances["Cash Wallet"] = 0.0
            balances["Bank Account"] = 0.0
            balances["Credit Card"] = 0.0
            balances["Digital Wallet"] = 0.0
            for (acc in accountsList) {
                val genericName = when (acc.type) {
                    "CASH" -> "Cash Wallet"
                    "BANK" -> "Bank Account"
                    "CREDIT_CARD" -> "Credit Card"
                    "WALLET" -> "Digital Wallet"
                    else -> "Bank Account"
                }
                val snap = latestSnap[acc.name]
                val startBal = snap?.second ?: (if (carryOverPreviousAmount) acc.balance else 0.0)
                balances[genericName] = (balances[genericName] ?: 0.0) + startBal
            }
        } else {
            for (acc in accountsList) {
                val snap = latestSnap[acc.name]
                balances[acc.name] = snap?.second ?: (if (carryOverPreviousAmount) acc.balance else 0.0)
            }
        }
    }

    // Step 3 — apply regular transactions that occurred AFTER the snapshot.
    // Pre-snapshot transactions are already baked into the snapshot amount.
    for (tx in transactions) {
        if (tx.type == "DUPLICATE" || tx.type == "BALANCE_UPDATE") continue
        val actualName = tx.getAccountName(consolidate = false)
        val snap = latestSnap[actualName]
        if (snap != null && tx.timestamp <= snap.first) continue  // pre-snapshot — already accounted for
        val accountKey = tx.getAccountName(consolidate)
        val change = if (tx.type == "INCOME") tx.amount else -tx.amount
        balances[accountKey] = (balances[accountKey] ?: 0.0) + change
    }
    return balances
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: FinanceViewModel = viewModel()) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    
    // Bottom Sheet & Dialog control states
    var showAddDialog by remember { mutableStateOf(false) }
    var showCsvDialog by remember { mutableStateOf(false) }
    var showAppMenu by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    
    val toastMessage = viewModel.toastMessage
    val scope = rememberCoroutineScope()
    val requestNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Collect Toast notifications
    LaunchedEffect(key1 = true) {
        toastMessage.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // Material 3 Custom dark palette constants
    val darkBg = Color(0xFF0B0F19)
    val cardSurface = Color(0xFF131A26)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Surface(
                            color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = "Wallet Logo",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "MyMoney",
                            fontWeight = FontWeight.Bold,
                            fontSize = 21.sp,
                            letterSpacing = 0.5.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showAppMenu = true },
                            modifier = Modifier.testTag("app_overflow_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "App menu",
                                tint = Color(0xFF00E5FF)
                            )
                        }

                        DropdownMenu(
                            expanded = showAppMenu,
                            onDismissRequest = { showAppMenu = false },
                            modifier = Modifier
                                .background(Color(0xFF131A26))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("MyMoney v${BuildConfig.VERSION_NAME}", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("App version", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                                    }
                                },
                                onClick = {},
                                enabled = false
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Share, contentDescription = "Export", tint = Color(0xFF00E5FF))
                                        Text("Export", color = Color.White)
                                    }
                                },
                                onClick = {
                                    showAppMenu = false
                                    showCsvDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Backup, contentDescription = "Backup", tint = Color(0xFF10B981))
                                        Text("Backup", color = Color.White)
                                    }
                                },
                                onClick = {
                                    showAppMenu = false
                                    showBackupDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Restore, contentDescription = "Restore", tint = Color(0xFFFFC107))
                                        Text("Restore", color = Color.White)
                                    }
                                },
                                onClick = {
                                    showAppMenu = false
                                    showRestoreDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = darkBg
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = cardSurface,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        label = { Text(tab.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E5FF),
                            selectedTextColor = Color(0xFF00E5FF),
                            unselectedIconColor = Color.White.copy(alpha = 0.5f),
                            unselectedTextColor = Color.White.copy(alpha = 0.5f),
                            indicatorColor = Color(0xFF00E5FF).copy(alpha = 0.15f)
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab == AppTab.DASHBOARD) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color(0xFF0B0F19),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .testTag("add_transaction_fab")
                        .padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Transaction",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(darkBg)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "TabTransition"
            ) { targetTab ->
                when (targetTab) {
                    AppTab.DASHBOARD -> DashboardScreen(viewModel)
                    AppTab.BUDGETS -> BudgetsScreen(viewModel)
                    AppTab.ANALYTICS -> AnalyticsScreen(viewModel)
                    AppTab.ACCOUNT -> AccountScreen(viewModel)
                    AppTab.AUTO_SCAN -> AutoScanHubScreen(viewModel)
                }
            }
        }
    }

    // Modal Dialog: Manual Add Transaction
    if (showAddDialog) {
        AddTransactionDialog(
            viewModel = viewModel,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, amount, categoryName, type, note, timestamp ->
                viewModel.addTransaction(title, amount, categoryName, type, note, timestamp)
                showAddDialog = false
            }
        )
    }

    // Modal Dialog: Export CSV Data
    if (showCsvDialog) {
        ExportCsvDialog(
            viewModel = viewModel,
            onDismiss = { showCsvDialog = false }
        )
    }

    if (showBackupDialog) {
        BackupDialog(
            viewModel = viewModel,
            onDismiss = { showBackupDialog = false }
        )
    }

    if (showRestoreDialog) {
        RestoreBackupDialog(
            viewModel = viewModel,
            onDismiss = { showRestoreDialog = false }
        )
    }
}

// 1. RECORDS / DASHBOARD SCREEN
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(viewModel: FinanceViewModel) {
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val activeMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val anchorTime by viewModel.anchorDate.collectAsStateWithLifecycle()
    val carryOverPreviousAmount by viewModel.carryOverPreviousAmount.collectAsStateWithLifecycle()
    val showTotal by viewModel.showTotal.collectAsStateWithLifecycle()
    val consolidateAccounts by viewModel.consolidateAccounts.collectAsStateWithLifecycle()
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val decFormat = DecimalFormat("₹#,##0.00")
    
    var selectedWallet by remember { mutableStateOf("All") }
    var selectedTxForEdit by remember { mutableStateOf<TransactionEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var showDeletePeriodDialog by remember { mutableStateOf(false) }
    
    val (periodStart, periodEnd) = getPeriodRange(activeMode, anchorTime)
    
    val periodTransactions = txs.filter { tx ->
        tx.timestamp in periodStart..periodEnd
    }
    
    val walletsBalances = if (carryOverPreviousAmount) {
        val historicalTxs = txs.filter { it.timestamp <= periodEnd }
        computeWalletBalances(historicalTxs, accounts, carryOverPreviousAmount = true, consolidate = consolidateAccounts)
    } else {
        computeWalletBalances(periodTransactions, accounts, carryOverPreviousAmount = false, consolidate = consolidateAccounts)
    }
    
    // Filter Transactions by selected period AND selected wallet
    val monthTransactions = periodTransactions.filter { tx ->
        selectedWallet == "All" || tx.getAccountName(consolidateAccounts) == selectedWallet
    }
    val searchableTransactions = if (searchQuery.isBlank()) {
        monthTransactions
    } else {
        txs.filter { tx ->
            selectedWallet == "All" || tx.getAccountName(consolidateAccounts) == selectedWallet
        }
    }
    val visibleTransactions = searchableTransactions.filter { tx ->
        if (searchQuery.isBlank()) {
            true
        } else {
            val query = searchQuery.trim()
            val noteText = tx.note?.substringBefore(" [Acc:")?.trim().orEmpty()
            val displayCategory = CategoryResolver.resolve(tx.category, customCats).displayName
            tx.title.contains(query, ignoreCase = true) ||
                tx.category.contains(query, ignoreCase = true) ||
                displayCategory.contains(query, ignoreCase = true) ||
                noteText.contains(query, ignoreCase = true)
        }
    }
    
    // Monthly aggregates (specific to selected wallet)
    val totalIncome = monthTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
    val totalExpense = monthTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val netBalance = totalIncome - totalExpense
    val totalWealth = walletsBalances.values.sum()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_scroll_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Month/Period Navigation Selector Header
        item {
            var showFilterMenu by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFF131A26),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF1D293B)),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { shiftPeriod(viewModel, activeMode, anchorTime, -1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Period", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = formatPeriodLabel(activeMode, anchorTime),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { shiftPeriod(viewModel, activeMode, anchorTime, 1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next Period", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(
                        onClick = {
                            isSearchExpanded = !isSearchExpanded
                            if (!isSearchExpanded) searchQuery = ""
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isSearchExpanded || searchQuery.isNotBlank()) Color(0xFF00E5FF).copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f),
                            contentColor = if (isSearchExpanded || searchQuery.isNotBlank()) Color(0xFF00E5FF) else Color.White
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Toggle search")
                    }

                    if (activeMode == DisplayMode.MONTHLY) {
                        FilledTonalIconButton(
                            onClick = { showDeletePeriodDialog = true },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFFF43F5E).copy(alpha = 0.18f),
                                contentColor = Color(0xFFF43F5E)
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Delete month")
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { showFilterMenu = !showFilterMenu },
                            modifier = Modifier.size(40.dp).testTag("three_bar_filter_button")
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Options and Filtering", tint = Color(0xFF00E5FF), modifier = Modifier.size(24.dp))
                        }

                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false },
                            modifier = Modifier
                                .background(Color(0xFF0F172A))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .width(260.dp)
                        ) {
                        DropdownMenuItem(
                            text = {
                                Text("DISPLAY MODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                            },
                            onClick = {},
                            enabled = false
                        )
                        
                        val modes = listOf(
                            DisplayMode.DAILY to "Daily View",
                            DisplayMode.WEEKLY to "Weekly View",
                            DisplayMode.MONTHLY to "Monthly View",
                            DisplayMode.THREE_MONTHS to "3 Months View",
                            DisplayMode.SIX_MONTHS to "6 Months View",
                            DisplayMode.ONE_YEAR to "1 Year View"
                        )
                        
                        modes.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(label, color = Color.White, fontSize = 13.sp)
                                        if (activeMode == mode) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color(0xFF00E5FF),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    viewModel.setDisplayMode(mode)
                                    showFilterMenu = false
                                },
                                modifier = Modifier.testTag("filter_mode_${mode.name.lowercase()}")
                            )
                        }
                        
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                        
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Carry over Balances", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                        Text("Include previous initial totals", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
                                    }
                                    Switch(
                                        checked = carryOverPreviousAmount,
                                        onCheckedChange = { viewModel.setCarryOverPreviousAmount(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF0B0F19),
                                            checkedTrackColor = Color(0xFF00E5FF)
                                        ),
                                        modifier = Modifier.scale(0.8f).testTag("menu_carry_over_switch")
                                    )
                                }
                            },
                            onClick = {
                                viewModel.setCarryOverPreviousAmount(!carryOverPreviousAmount)
                            }
                        )
                        
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Show Grand Totals", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                        Text("Privacy mask total value", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
                                    }
                                    Switch(
                                        checked = showTotal,
                                        onCheckedChange = { viewModel.setShowTotal(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF0B0F19),
                                            checkedTrackColor = Color(0xFF00E5FF)
                                        ),
                                        modifier = Modifier.scale(0.8f).testTag("menu_show_total_switch")
                                    )
                                }
                            },
                            onClick = {
                                viewModel.setShowTotal(!showTotal)
                            }
                        )
                    }
                }
            }
        }
        }

        // Net Balance Glow Card
        item {
            Surface(
                color = Color(0xFF131A26),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.2.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("net_wealth_glowing_card")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (selectedWallet == "All") "NET WALLET ASSETS" else "${selectedWallet.uppercase()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (showTotal) {
                            decFormat.format(if (selectedWallet == "All") totalWealth else (walletsBalances[selectedWallet] ?: 0.0))
                        } else {
                            "₹ ••••"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp,
                        color = Color(0xFF00E5FF)
                    )
                    
                    Spacer(modifier = Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Monthly Income Tracker
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF10B981).copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Income Flow",
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Inflow", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                                Text(
                                    decFormat.format(totalIncome),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }

                        // Monthly Expense Tracker
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFF43F5E).copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Expense Outflow",
                                        tint = Color(0xFFF43F5E),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Outflow", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                                Text(
                                    decFormat.format(totalExpense),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color(0xFFF43F5E)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Horizontal Wallet microcards mimicking MyMoney custom wallets selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MY WALLETS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val walletList = if (accounts.isEmpty()) {
                        listOf("All", "Cash Wallet", "Bank Account", "Credit Card", "Digital Wallet")
                    } else {
                        listOf("All") + accounts.map { it.name }
                    }
                    items(walletList) { name ->
                        val isSelected = selectedWallet == name
                        
                        val balance = if (name == "All") {
                            totalWealth
                        } else {
                            walletsBalances[name] ?: 0.0
                        }

                        val cardBorderColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B)
                        val cardBg = if (isSelected) Color(0xFF1E293B) else Color(0xFF131A26)

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(1.2.dp, cardBorderColor),
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { selectedWallet = name }
                                .testTag("wallet_selector_$name")
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Icon(
                                    imageVector = when {
                                        name == "Cash Wallet" -> Icons.Default.Money
                                        name == "Bank Account" -> Icons.Default.AccountBalance
                                        name == "Credit Card" -> Icons.Default.CreditCard
                                        name == "Digital Wallet" -> Icons.Default.AccountBalanceWallet
                                        else -> {
                                            val acType = accounts.find { it.name == name }?.type ?: ""
                                            when(acType) {
                                                "CASH" -> Icons.Default.Money
                                                "BANK" -> Icons.Default.AccountBalance
                                                "CREDIT_CARD" -> Icons.Default.CreditCard
                                                "WALLET" -> Icons.Default.AccountBalanceWallet
                                                else -> Icons.Default.AllInclusive
                                            }
                                        }
                                    },
                                    contentDescription = name,
                                    tint = if (isSelected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = decFormat.format(balance),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (balance >= 0) Color(0xFF10B981) else Color(0xFFF43F5E),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isSearchExpanded) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White.copy(alpha = 0.6f))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    },
                    label = { Text("Search records") },
                    placeholder = { Text("Merchant, category, or notes") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = Color(0xFF00E5FF),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                    )
                )
            }
        }

        // Chronological transaction lists grouped by Dates
        if (visibleTransactions.isEmpty()) {
            item {
                Surface(
                    color = Color(0xFF131A26),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Receipt,
                            contentDescription = "No trans",
                            tint = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (searchQuery.isBlank()) "No transactions recorded for this period." else "No transactions match your search.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap the '+' FAB below to log a cashflow.",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        } else {
            // Group transactions by date
            val grouped = visibleTransactions.sortedByDescending { it.timestamp }.groupBy {
                val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                val today = Calendar.getInstance()
                val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                
                if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                    "Today"
                } else if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)) {
                    "Yesterday"
                } else {
                    SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(cal.time)
                }
            }

            grouped.forEach { (dateStr, txList) ->
                stickyHeader {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0F19))
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateStr.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp,
                            color = Color(0xFF00E5FF)
                        )
                        
                        val dateNet = txList.filter { it.type != "DUPLICATE" && it.type != "BALANCE_UPDATE" }.sumOf { if (it.type == "INCOME") it.amount else -it.amount }
                        Text(
                            text = (if (dateNet >= 0) "+" else "") + decFormat.format(dateNet),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (dateNet >= 0) Color(0xFF10B981) else Color(0xFFF43F5E)
                        )
                    }
                }

                items(txList) { tx ->
                    val resolvedCat = CategoryResolver.resolve(tx.category, customCats)
                    val isAdjust = tx.category.equals("ADJUST", ignoreCase = true)
                    
                    Surface(
                        color = if (isAdjust) Color(0xFF00E5FF).copy(alpha = 0.06f) else Color(0xFF131A26),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (isAdjust) Color(0xFF00E5FF).copy(alpha = 0.45f) else Color(0xFF1E293B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedTxForEdit = tx }
                            .testTag("transaction_item_${tx.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Round category leading circle matching design
                            Surface(
                                shape = CircleShape,
                                color = resolvedCat.color.copy(alpha = 0.15f),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = resolvedCat.icon,
                                        contentDescription = resolvedCat.displayName,
                                        tint = resolvedCat.color,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tx.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color.White.copy(alpha = 0.06f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = tx.getAccountName(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF00E5FF),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = when (tx.type) {
                                        "INCOME" -> "+" + decFormat.format(tx.amount)
                                        "DUPLICATE" -> "DUP"
                                        "BALANCE_UPDATE" -> {
                                            val a = tx.amount
                                            when {
                                                a >= 1_000_000 -> "\u20b9${String.format("%.1f", a / 1_000_000)}M"
                                                a >= 1_000 -> "\u20b9${String.format("%.1f", a / 1_000)}K"
                                                else -> "\u20b9${a.toInt()}"
                                            }
                                        }
                                        else -> "-" + decFormat.format(tx.amount)
                                    },
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = when (tx.type) {
                                        "INCOME" -> Color(0xFF10B981)
                                        "DUPLICATE" -> Color.White.copy(alpha = 0.35f)
                                        "BALANCE_UPDATE" -> Color.White.copy(alpha = 0.35f)
                                        else -> Color(0xFFF43F5E)
                                    }
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = SystemDateFormat.getTimeFormat(context).format(Date(tx.timestamp)),
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeletePeriodDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePeriodDialog = false },
            title = { Text("Delete Month Transactions", color = Color.White) },
            text = {
                Text(
                    "Delete every transaction in ${formatPeriodLabel(activeMode, anchorTime)}? This cannot be undone.",
                    color = Color.White.copy(alpha = 0.75f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTransactionsInRange(periodStart, periodEnd, formatPeriodLabel(activeMode, anchorTime))
                        showDeletePeriodDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF43F5E))
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePeriodDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF131A26)
        )
    }

    // Modal dialog for editing/deleting any selected transaction
    selectedTxForEdit?.let { editTx ->
        var showDeleteConfirm by remember { mutableStateOf(false) }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Transaction", color = Color.White) },
                text = { Text("Are you absolutely sure you want to delete this recorded transaction?", color = Color.White.copy(alpha = 0.7f)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteTransaction(editTx.id)
                            selectedTxForEdit = null
                            showDeleteConfirm = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF43F5E))
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF131A26)
            )
        } else {
            EditTransactionDialog(
                tx = editTx,
                viewModel = viewModel,
                onDismiss = { selectedTxForEdit = null },
                onDelete = { showDeleteConfirm = true },
                onConfirm = { updated ->
                    viewModel.updateTransaction(updated)
                    selectedTxForEdit = null
                }
            )
        }
    }
}

// 2. ANALYSIS / ANALYTICS SCREEN
@Composable
fun AnalyticsScreen(viewModel: FinanceViewModel) {
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val rawMonthYear by viewModel.selectedMonthYear.collectAsStateWithLifecycle()
    val anchorTime by viewModel.anchorDate.collectAsStateWithLifecycle()
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())

    var timeFilter by remember { mutableStateOf("MONTHLY") }
    var selectedMode by remember { mutableStateOf(AnalyticsMode.EXPENSE_OVERVIEW) }
    var showModeMenu by remember { mutableStateOf(false) }
    var showPeriodMenu by remember { mutableStateOf(false) }
    val (analysisStart, analysisEnd) = getAnalyticsRange(rawMonthYear, timeFilter, anchorTime)

    val filteredTransactions = txs.filter { tx ->
        tx.timestamp in analysisStart..analysisEnd
    }

    val expenses = filteredTransactions.filter { it.type == "EXPENSE" }
    val incomes = filteredTransactions.filter { it.type == "INCOME" }
    val overviewTransactions = if (selectedMode == AnalyticsMode.INCOME_OVERVIEW) incomes else expenses
    val totalOverviewSum = overviewTransactions.sumOf { it.amount }
    val categoryTotals = overviewTransactions.groupBy { it.category }.map { (catName, list) ->
        val sumObj = list.sumOf { it.amount }
        val resolved = CategoryResolver.resolve(catName, customCats)
        DisplayCategorySpend(
            category = resolved,
            total = sumObj,
            percentage = if (totalOverviewSum > 0.0) sumObj / totalOverviewSum else 0.0
        )
    }.sortedByDescending { it.total }
    val flowPoints = remember(filteredTransactions, analysisStart, analysisEnd) {
        buildDailyFlowPoints(filteredTransactions, analysisStart, analysisEnd)
    }
    val accountStats = remember(filteredTransactions) {
        buildAccountAnalytics(filteredTransactions)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("analytics_scroll_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Period navigation row (mirrors Records section)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFF131A26),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFF1D293B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { shiftAnalyticsPeriod(viewModel, rawMonthYear, timeFilter, -1, anchorTime) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Period", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                            }
                            Text(
                                text = formatAnalyticsPeriodLabel(rawMonthYear, timeFilter, anchorTime),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { shiftAnalyticsPeriod(viewModel, rawMonthYear, timeFilter, 1, anchorTime) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next Period", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Box {
                        FilledTonalIconButton(
                            onClick = { showPeriodMenu = true },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Select time period", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showPeriodMenu,
                            onDismissRequest = { showPeriodMenu = false },
                            modifier = Modifier
                                .background(Color(0xFF0F172A))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .width(180.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("PERIOD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.45f)) },
                                onClick = {},
                                enabled = false
                            )
                            listOf("WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "3M" to "3 Months", "6M" to "6 Months", "1Y" to "1 Year").forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(label, color = if (timeFilter == key) Color(0xFF00E5FF) else Color.White, fontSize = 13.sp)
                                            if (timeFilter == key) Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                        }
                                    },
                                    onClick = { timeFilter = key; showPeriodMenu = false }
                                )
                            }
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { showModeMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = "Analysis Mode",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = selectedMode.label,
                                    fontSize = 15.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(Icons.Default.ExpandMore, contentDescription = "Select analysis mode", tint = Color(0xFF00E5FF))
                        }
                    }

                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                        modifier = Modifier.background(Color(0xFF131A26))
                    ) {
                        AnalyticsMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        mode.label,
                                        color = if (mode == selectedMode) Color(0xFF00E5FF) else Color.White,
                                        fontWeight = if (mode == selectedMode) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                onClick = {
                                    selectedMode = mode
                                    showModeMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }

        when (selectedMode) {
            AnalyticsMode.EXPENSE_OVERVIEW -> {
                item {
                    AnalyticsOverviewSection(
                        categoryTotals = categoryTotals,
                        totalAmount = totalOverviewSum,
                        totalLabel = "Total Spent",
                        breakdownLabel = "CATEGORY WISE BREAKDOWN",
                        percentSuffix = "of expenses",
                        emptyMessage = "No expense data available for this period."
                    )
                }
            }

            AnalyticsMode.INCOME_OVERVIEW -> {
                item {
                    AnalyticsOverviewSection(
                        categoryTotals = categoryTotals,
                        totalAmount = totalOverviewSum,
                        totalLabel = "Total Received",
                        breakdownLabel = "INCOME BREAKDOWN",
                        percentSuffix = "of income",
                        emptyMessage = "No income data available for this period."
                    )
                }
            }

            AnalyticsMode.EXPENSE_FLOW -> {
                item {
                    AnalyticsFlowSection(
                        title = "Expense Flow",
                        points = flowPoints,
                        showIncome = false,
                        accent = Color(0xFFF43F5E),
                        emptyMessage = "No expense flow available for this period."
                    )
                }
            }

            AnalyticsMode.INCOME_FLOW -> {
                item {
                    AnalyticsFlowSection(
                        title = "Income Flow",
                        points = flowPoints,
                        showIncome = true,
                        accent = Color(0xFF10B981),
                        emptyMessage = "No income flow available for this period."
                    )
                }
            }

            AnalyticsMode.ACCOUNT_ANALYSIS -> {
                item {
                    AnalyticsAccountSection(accountStats = accountStats)
                }
            }
        }
    }
}

data class DisplayCategorySpend(
    val category: DisplayCategory,
    val total: Double,
    val percentage: Double
)

private enum class AnalyticsMode(val label: String) {
    EXPENSE_OVERVIEW("Expense overview"),
    INCOME_OVERVIEW("Income overview"),
    EXPENSE_FLOW("Expense flow"),
    INCOME_FLOW("Income flow"),
    ACCOUNT_ANALYSIS("Account analysis")
}

private data class AnalyticsFlowPoint(
    val dateMillis: Long,
    val shortLabel: String,
    val fullLabel: String,
    val dayOfWeekLabel: String,
    val dayOfMonthLabel: String,
    val income: Double,
    val expense: Double
)

private data class AccountAnalyticsSummary(
    val accountName: String,
    val income: Double,
    val expense: Double,
    val net: Double,
    val color: Color
)

@Composable
private fun AnalyticsOverviewSection(
    categoryTotals: List<DisplayCategorySpend>,
    totalAmount: Double,
    totalLabel: String,
    breakdownLabel: String,
    percentSuffix: String,
    emptyMessage: String
) {
    var activeSectorIndex by remember(categoryTotals, totalLabel) { mutableStateOf(-1) }
    val decFormat = remember { DecimalFormat("₹#,##0.00") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = Color(0xFF131A26),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (categoryTotals.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emptyMessage, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val isCompact = maxWidth < 420.dp
                        val chartSize = if (isCompact) 170.dp else 180.dp
                        val chartContent: @Composable () -> Unit = {
                            Box(
                                modifier = Modifier
                                    .size(chartSize)
                                    .padding(8.dp)
                            ) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(categoryTotals, activeSectorIndex) {
                                            detectTapGestures { offset ->
                                                val centerX = size.width / 2f
                                                val centerY = size.height / 2f
                                                val dx = offset.x - centerX
                                                val dy = offset.y - centerY
                                                val dist = sqrt(dx * dx + dy * dy)
                                                val sizeMin = minOf(size.width, size.height)
                                                val strokeWidthValue = sizeMin * 0.16f
                                                val radius = (sizeMin - strokeWidthValue) / 2f

                                                if (dist >= radius - strokeWidthValue * 1.5f && dist <= radius + strokeWidthValue * 1.5f) {
                                                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                                    if (angle < 0f) angle += 360f
                                                    var chartAngle = angle + 90f
                                                    if (chartAngle >= 360f) chartAngle -= 360f

                                                    var currentAngle = 0f
                                                    var foundIdx = -1
                                                    categoryTotals.forEachIndexed { idx, item ->
                                                        val sweep = (item.percentage * 360f).toFloat()
                                                        if (chartAngle >= currentAngle && chartAngle < currentAngle + sweep) {
                                                            foundIdx = idx
                                                            return@forEachIndexed
                                                        }
                                                        currentAngle += sweep
                                                    }
                                                    if (foundIdx != -1) {
                                                        activeSectorIndex = if (activeSectorIndex == foundIdx) -1 else foundIdx
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    val sizeMin = size.minDimension
                                    val strokeWidthValue = sizeMin * 0.16f
                                    val arcSize = Size(sizeMin - strokeWidthValue, sizeMin - strokeWidthValue)
                                    val topLeftOffset = Offset(strokeWidthValue / 2f, strokeWidthValue / 2f)

                                    var currentAngle = -90f
                                    categoryTotals.forEachIndexed { idx, item ->
                                        val sweep = (item.percentage * 360f).toFloat()
                                        val isHighlighted = activeSectorIndex == idx
                                        drawArc(
                                            color = item.category.color,
                                            startAngle = currentAngle,
                                            sweepAngle = sweep,
                                            useCenter = false,
                                            style = Stroke(
                                                width = if (isHighlighted) strokeWidthValue * 1.35f else strokeWidthValue,
                                                cap = StrokeCap.Round
                                            ),
                                            size = arcSize,
                                            topLeft = topLeftOffset,
                                            alpha = if (activeSectorIndex == -1 || isHighlighted) 1f else 0.4f
                                        )
                                        currentAngle += sweep
                                    }

                                    if (activeSectorIndex != -1) {
                                        val item = categoryTotals.getOrNull(activeSectorIndex)
                                        if (item != null) {
                                            val cx2 = size.width / 2f
                                            val cy2 = size.height / 2f
                                            val namePaint = android.graphics.Paint().apply {
                                                color = item.category.color.toArgb()
                                                textSize = 13.sp.toPx()
                                                isAntiAlias = true
                                                textAlign = android.graphics.Paint.Align.CENTER
                                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            }
                                            val pctPaint = android.graphics.Paint().apply {
                                                color = android.graphics.Color.WHITE
                                                alpha = 220
                                                textSize = 14.sp.toPx()
                                                isAntiAlias = true
                                                textAlign = android.graphics.Paint.Align.CENTER
                                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            }
                                            val innerRadius = (sizeMin - strokeWidthValue) / 2f - strokeWidthValue * 0.65f
                                            val maxNameWidth = innerRadius * 1.6f
                                            var displayName = item.category.displayName
                                            while (displayName.isNotEmpty() && namePaint.measureText(displayName) > maxNameWidth) {
                                                displayName = displayName.dropLast(1)
                                            }
                                            if (displayName.length < item.category.displayName.length) displayName = "$displayName…"
                                            drawIntoCanvas { nativeCanvasWrapper ->
                                                nativeCanvasWrapper.nativeCanvas.drawText(
                                                    displayName, cx2, cy2 - 7.dp.toPx(), namePaint
                                                )
                                                nativeCanvasWrapper.nativeCanvas.drawText(
                                                    String.format(java.util.Locale.getDefault(), "%.1f%%", item.percentage * 100),
                                                    cx2, cy2 + 11.dp.toPx(), pctPaint
                                                )
                                            }
                                        }
                                    } else {
                                        // Nothing selected — show total label + amount
                                        val cx2 = size.width / 2f
                                        val cy2 = size.height / 2f
                                        val innerRadius = (sizeMin - strokeWidthValue) / 2f - strokeWidthValue * 0.65f
                                        val labelPaint = android.graphics.Paint().apply {
                                            color = android.graphics.Color.WHITE
                                            alpha = 160
                                            textSize = 10.sp.toPx()
                                            isAntiAlias = true
                                            textAlign = android.graphics.Paint.Align.CENTER
                                        }
                                        val amtPaint = android.graphics.Paint().apply {
                                            color = android.graphics.Color.WHITE
                                            alpha = 230
                                            textSize = 12.sp.toPx()
                                            isAntiAlias = true
                                            textAlign = android.graphics.Paint.Align.CENTER
                                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                                        }
                                        val rawAmtText = decFormat.format(totalAmount)
                                        val amtText = if (amtPaint.measureText(rawAmtText) > innerRadius * 1.7f) {
                                            when {
                                                totalAmount >= 1_00_00_000.0 -> "₹${DecimalFormat("#.##").format(totalAmount / 1_00_00_000.0)}Cr"
                                                totalAmount >= 1_00_000.0 -> "₹${DecimalFormat("#.##").format(totalAmount / 1_00_000.0)}L"
                                                else -> "₹${DecimalFormat("#.##").format(totalAmount / 1_000.0)}K"
                                            }
                                        } else rawAmtText
                                        drawIntoCanvas { canvas ->
                                            canvas.nativeCanvas.drawText(totalLabel, cx2, cy2 - 6.dp.toPx(), labelPaint)
                                            canvas.nativeCanvas.drawText(amtText, cx2, cy2 + 10.dp.toPx(), amtPaint)
                                        }
                                    }
                                }

                                // pointer dot+line for active sector drawn in Canvas above
                            }
                        }
                        val legendContent: @Composable () -> Unit = {
                            Column(
                                modifier = Modifier.widthIn(max = 110.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                categoryTotals.forEachIndexed { idx, stats ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                activeSectorIndex = if (activeSectorIndex == idx) -1 else idx
                                            }
                                            .background(if (activeSectorIndex == idx) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(stats.category.color)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stats.category.displayName,
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                chartContent()
                            }
                            legendContent()
                        }

                    }
                }
            }
        }

        if (categoryTotals.isNotEmpty()) {
            Text(
                text = breakdownLabel,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            categoryTotals.forEachIndexed { idx, stats ->
                val active = activeSectorIndex == idx
                Surface(
                    color = if (active) Color(0xFF1E293B) else Color(0xFF131A26),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (active) Color(0xFF00E5FF) else Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            activeSectorIndex = if (activeSectorIndex == idx) -1 else idx
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = stats.category.color.copy(alpha = 0.15f),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = stats.category.icon,
                                    contentDescription = stats.category.displayName,
                                    tint = stats.category.color,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stats.category.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(decFormat.format(stats.total), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                            }
                            LinearProgressIndicator(
                                progress = stats.percentage.toFloat(),
                                color = stats.category.color,
                                trackColor = Color.White.copy(alpha = 0.06f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", stats.percentage * 100)}%",
                            fontSize = 16.sp,
                            color = stats.category.color,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.widthIn(min = 44.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsFlowSection(
    title: String,
    points: List<AnalyticsFlowPoint>,
    showIncome: Boolean,
    accent: Color,
    emptyMessage: String
) {
    val decFormat = remember { DecimalFormat("₹#,##0.00") }
    val pointValues = remember(points, showIncome) {
        points.map { if (showIncome) it.income else it.expense }
    }
    val nonZeroPoints = remember(pointValues) { pointValues.filter { it > 0.0 } }
    val total = pointValues.sum()
    val average = if (nonZeroPoints.isNotEmpty()) total / nonZeroPoints.size else 0.0
    val maxValue = (pointValues.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val yAxisValues = remember(maxValue) {
        listOf(maxValue, maxValue * 0.66, maxValue * 0.33, 0.0)
    }
    val xAxisIndices = remember(points) {
        when {
            points.isEmpty() -> emptyList()
            points.size <= 5 -> points.indices.toList()
            else -> listOf(0, points.lastIndex / 4, points.lastIndex / 2, (points.lastIndex * 3) / 4, points.lastIndex).distinct()
        }
    }
    var activePointIndex by remember(points, showIncome) {
        mutableStateOf(pointValues.indexOfLast { it > 0.0 }.takeIf { it >= 0 } ?: 0)
    }
    val activePoint = points.getOrNull(activePointIndex)
    val activeValue = pointValues.getOrElse(activePointIndex) { 0.0 }
    val calendarCells = remember(points) { buildAnalyticsCalendarCells(points) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(title.uppercase(Locale.getDefault()), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.5f))
                        Text(decFormat.format(total), fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = accent)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Average active day", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
                        Text(decFormat.format(average), fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                if (nonZeroPoints.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(emptyMessage, color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activePoint?.fullLabel ?: title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Surface(
                                color = accent.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = decFormat.format(activeValue),
                                    color = accent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxHeight(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.End
                            ) {
                                yAxisValues.forEach { value ->
                                    Text(
                                        text = compactCurrency(value),
                                        color = Color.White.copy(alpha = 0.45f),
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.End
                                    )
                                }
                            }

                            Canvas(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .pointerInput(points, pointValues) {
                                        detectTapGestures { offset ->
                                            if (points.isNotEmpty()) {
                                                val index = if (points.size == 1) {
                                                    0
                                                } else {
                                                    ((offset.x / size.width) * (points.size - 1)).toInt().coerceIn(0, points.lastIndex)
                                                }
                                                activePointIndex = index
                                            }
                                        }
                                    }
                            ) {
                                val topPad = 12.dp.toPx()
                                val bottomPad = 16.dp.toPx()
                                val chartHeight = size.height - topPad - bottomPad
                                val chartWidth = size.width

                                yAxisValues.forEachIndexed { index, _ ->
                                    val y = topPad + chartHeight * index / (yAxisValues.lastIndex.coerceAtLeast(1)).toFloat()
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.08f),
                                        start = Offset(0f, y),
                                        end = Offset(chartWidth, y),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }

                                var previousOffset: Offset? = null
                                pointValues.forEachIndexed { index, value ->
                                    val x = if (pointValues.size == 1) chartWidth / 2f else chartWidth * index / (pointValues.size - 1).toFloat()
                                    val y = topPad + chartHeight - ((value / maxValue) * chartHeight).toFloat()
                                    val currentOffset = Offset(x, y)

                                    previousOffset?.let {
                                        drawLine(
                                            color = accent,
                                            start = it,
                                            end = currentOffset,
                                            strokeWidth = 3.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                    }

                                    if (index == activePointIndex) {
                                        drawLine(
                                            color = accent.copy(alpha = 0.22f),
                                            start = Offset(x, topPad),
                                            end = Offset(x, topPad + chartHeight),
                                            strokeWidth = 2.dp.toPx()
                                        )
                                    }

                                    drawCircle(
                                        color = accent,
                                        radius = if (index == activePointIndex) 6.dp.toPx() else 4.dp.toPx(),
                                        center = currentOffset
                                    )
                                    previousOffset = currentOffset
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 56.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            xAxisIndices.forEach { index ->
                                Text(
                                    text = points[index].dayOfMonthLabel,
                                    color = if (index == activePointIndex) accent else Color.White.copy(alpha = 0.45f),
                                    fontSize = 10.sp,
                                    fontWeight = if (index == activePointIndex) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
        }

        Text(
            text = "CALENDAR DAY SNAPSHOT",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = Color.White.copy(alpha = 0.6f)
        )

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f))
                    .padding(vertical = 6.dp)
            ) {
                    listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                        Text(
                            text = day,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                calendarCells.chunked(7).forEachIndexed { weekIdx, week ->
                    if (weekIdx > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.04f))
                    Row(modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        week.forEachIndexed { dayIdx, point ->
                            if (dayIdx > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(1.dp)
                                        .background(Color.White.copy(alpha = 0.04f))
                                )
                            }
                            val value = point?.let { if (showIncome) it.income else it.expense } ?: 0.0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(
                                        if (point != null && value > 0.0) accent.copy(alpha = 0.09f) else Color.Transparent
                                    )
                            ) {
                                if (point != null) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 2.dp, vertical = 2.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = point.dayOfMonthLabel,
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.align(Alignment.Start)
                                        )
                                        Text(
                                            text = if (value > 0.0) compactCurrency(value) else "\u00b7",
                                            fontSize = 7.sp,
                                            fontWeight = if (value > 0.0) FontWeight.Bold else FontWeight.Normal,
                                            color = if (value > 0.0) accent else Color.White.copy(alpha = 0.18f),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

@Composable
private fun AnalyticsAccountSection(accountStats: List<AccountAnalyticsSummary>) {
    val decFormat = remember { DecimalFormat("₹#,##0.00") }
    val maxActivity = accountStats.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1.0) ?: 1.0
    val yAxisValues = remember(maxActivity) {
        listOf(maxActivity, maxActivity * 0.66, maxActivity * 0.33, 0.0)
    }
    var activeAccountIndex by remember(accountStats) { mutableStateOf(if (accountStats.isEmpty()) 0 else 0) }
    val activeAccount = accountStats.getOrNull(activeAccountIndex)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ACCOUNT ACTIVITY CHART", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.5f))

                if (accountStats.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text("No account activity available for this period.", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                } else {
                    activeAccount?.let { stats ->
                        Text(
                            text = stats.accountName,
                            color = stats.color,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = Color(0xFF10B981).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text("INCOME", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    Text(compactCurrency(stats.income), color = Color(0xFF10B981), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                            Surface(
                                color = Color(0xFFF43F5E).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text("EXPENSE", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    Text(compactCurrency(stats.expense), color = Color(0xFFF43F5E), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                            Surface(
                                color = (if (stats.net >= 0) Color(0xFF00E5FF) else Color(0xFFFF7043)).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text("NET", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    Text(compactCurrency(stats.net), color = if (stats.net >= 0) Color(0xFF00E5FF) else Color(0xFFFF7043), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.End
                        ) {
                            yAxisValues.forEach { value ->
                                Text(
                                    text = compactCurrency(value),
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.End
                                )
                            }
                        }

                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .pointerInput(accountStats) {
                                    detectTapGestures { offset ->
                                        if (accountStats.isNotEmpty()) {
                                            val slotWidth = size.width / accountStats.size
                                            activeAccountIndex = (offset.x / slotWidth).toInt().coerceIn(0, accountStats.lastIndex)
                                        }
                                    }
                                }
                        ) {
                            val topPad = 12.dp.toPx()
                            val bottomPad = 18.dp.toPx()
                            val chartHeight = size.height - topPad - bottomPad
                            val slotWidth = size.width / accountStats.size

                            yAxisValues.forEachIndexed { index, _ ->
                                val y = topPad + chartHeight * index / (yAxisValues.lastIndex.coerceAtLeast(1)).toFloat()
                                drawLine(
                                    color = Color.White.copy(alpha = 0.08f),
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }

                            accountStats.forEachIndexed { index, stats ->
                                val centerX = slotWidth * index + slotWidth / 2f
                                val barWidth = slotWidth * 0.28f
                                val gap = slotWidth * 0.05f
                                val incomeCenterX = centerX - barWidth / 2f - gap / 2f
                                val expenseCenterX = centerX + barWidth / 2f + gap / 2f
                                val incomeY = topPad + chartHeight - ((stats.income / maxActivity) * chartHeight).toFloat()
                                val expenseY = topPad + chartHeight - ((stats.expense / maxActivity) * chartHeight).toFloat()
                                val barBottom = topPad + chartHeight
                                val barAlpha = if (index == activeAccountIndex) 1f else 0.65f

                                if (index == activeAccountIndex) {
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.04f),
                                        topLeft = Offset(slotWidth * index + 4.dp.toPx(), topPad),
                                        size = Size(slotWidth - 8.dp.toPx(), chartHeight),
                                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                                    )
                                }

                                if (stats.income > 0.0) {
                                    drawRoundRect(
                                        color = Color(0xFF10B981),
                                        topLeft = Offset(incomeCenterX - barWidth / 2f, incomeY),
                                        size = Size(barWidth, (barBottom - incomeY).coerceAtLeast(4.dp.toPx())),
                                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                        alpha = barAlpha
                                    )
                                }
                                if (stats.expense > 0.0) {
                                    drawRoundRect(
                                        color = Color(0xFFF43F5E),
                                        topLeft = Offset(expenseCenterX - barWidth / 2f, expenseY),
                                        size = Size(barWidth, (barBottom - expenseY).coerceAtLeast(4.dp.toPx())),
                                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                        alpha = barAlpha
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        accountStats.forEachIndexed { index, stats ->
                            Text(
                                text = run {
                                    val n = stats.accountName
                                    val upper = n.uppercase(java.util.Locale.getDefault())
                                    val tag = if (upper.contains("CARD") || upper.contains("CREDIT") || upper.contains(" CC ")) {
                                        val digits = n.filter { it.isDigit() }.takeLast(2)
                                        "CC${if (digits.isNotEmpty()) " $digits" else ""}"
                                    } else "BNK"
                                    "${n.split(" ").first().take(4).uppercase(java.util.Locale.getDefault())} $tag"
                                },
                                color = if (index == activeAccountIndex) stats.color else Color.White.copy(alpha = 0.45f),
                                fontSize = 10.sp,
                                fontWeight = if (index == activeAccountIndex) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
        }

        if (accountStats.isNotEmpty()) {
            Text(
                text = "ACCOUNT BREAKDOWN",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            accountStats.forEach { stats ->
                Surface(
                    color = Color(0xFF131A26),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, stats.color.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stats.accountName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 10.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("IN", color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                                Text(compactCurrency(stats.income), color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("OUT", color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                                Text(compactCurrency(stats.expense), color = Color(0xFFF43F5E), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("NET", color = Color.White.copy(alpha = 0.45f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                                Text(compactCurrency(stats.net), color = if (stats.net >= 0) Color(0xFF00E5FF) else Color(0xFFFF7043), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountStatChip(
    label: String,
    amount: Double,
    tone: Color,
    modifier: Modifier = Modifier
) {
    val decFormat = remember { DecimalFormat("₹#,##0.00") }
    Surface(
        color = tone.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(decFormat.format(amount), color = tone, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

private fun buildDailyFlowPoints(
    transactions: List<TransactionEntry>,
    start: Long,
    end: Long
): List<AnalyticsFlowPoint> {
    val totalsByDay = transactions.groupBy {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp))
    }

    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val shortFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
    val fullFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
    val weekdayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    val dayNumberFormat = SimpleDateFormat("d", Locale.getDefault())
    val calendar = Calendar.getInstance().apply {
        timeInMillis = start
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val lastDay = Calendar.getInstance().apply {
        timeInMillis = end
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val points = mutableListOf<AnalyticsFlowPoint>()
    while (!calendar.after(lastDay)) {
        val time = calendar.timeInMillis
        val key = keyFormat.format(Date(time))
        val dayTransactions = totalsByDay[key].orEmpty()
        points += AnalyticsFlowPoint(
            dateMillis = time,
            shortLabel = shortFormat.format(Date(time)),
            fullLabel = fullFormat.format(Date(time)),
            dayOfWeekLabel = weekdayFormat.format(Date(time)),
            dayOfMonthLabel = dayNumberFormat.format(Date(time)),
            income = dayTransactions.filter { it.type == "INCOME" }.sumOf { it.amount },
            expense = dayTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
        )
        calendar.add(Calendar.DAY_OF_MONTH, 1)
    }
    return points
}

private fun compactCurrency(amount: Double): String {
    val absAmount = if (amount < 0) -amount else amount
    return when {
        absAmount >= 10000000 -> "₹${String.format(Locale.getDefault(), "%.1f", amount / 10000000)}Cr"
        absAmount >= 100000 -> "₹${String.format(Locale.getDefault(), "%.1f", amount / 100000)}L"
        absAmount >= 1000 -> "₹${String.format(Locale.getDefault(), "%.1f", amount / 1000)}K"
        else -> "₹${String.format(Locale.getDefault(), "%.0f", amount)}"
    }
}

private fun buildAnalyticsCalendarCells(points: List<AnalyticsFlowPoint>): List<AnalyticsFlowPoint?> {
    if (points.isEmpty()) return emptyList()

    val firstDay = Calendar.getInstance().apply { timeInMillis = points.first().dateMillis }
    val leadingBlanks = (firstDay.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceAtLeast(0)
    val cells = MutableList<AnalyticsFlowPoint?>(leadingBlanks) { null }
    cells.addAll(points)
    while (cells.size % 7 != 0) {
        cells.add(null)
    }
    return cells
}

private fun buildAccountAnalytics(transactions: List<TransactionEntry>): List<AccountAnalyticsSummary> {
    val palette = listOf(
        Color(0xFF00E5FF),
        Color(0xFF10B981),
        Color(0xFFFF7043),
        Color(0xFFFFC107),
        Color(0xFF7C4DFF),
        Color(0xFF26C6DA)
    )

    return transactions.groupBy { it.getAccountName() }
        .entries
        .toList()
        .mapIndexed { index, entry ->
            val accountName = entry.key
            val accountTransactions = entry.value
            val income = accountTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
            val expense = accountTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
            AccountAnalyticsSummary(
                accountName = accountName,
                income = income,
                expense = expense,
                net = income - expense,
                color = palette[index % palette.size]
            )
        }
        .sortedByDescending { it.income + it.expense }
}

val suitableIconsList = listOf(
    "restaurant" to Icons.Default.Restaurant,
    "shopping" to Icons.Default.ShoppingBag,
    "car" to Icons.Default.DirectionsCar,
    "bills" to Icons.Default.ReceiptLong,
    "recharge" to Icons.Default.Smartphone,
    "gym" to Icons.Default.FitnessCenter,
    "sport" to Icons.Default.SportsSoccer,
    "electronics" to Icons.Default.Devices,
    "insurance" to Icons.Default.Security,
    "social" to Icons.Default.Group,
    "tax" to Icons.Default.Percent,
    "transportation" to Icons.Default.AirportShuttle,
    "education" to Icons.Default.School,
    "healthcare" to Icons.Default.MedicalServices,
    "entertainment" to Icons.Default.LocalPlay,
    "awards" to Icons.Default.EmojiEvents,
    "coupons" to Icons.Default.CardGiftcard,
    "grants" to Icons.Default.Handshake,
    "refunds" to Icons.Default.Cached,
    "rental" to Icons.Default.Domain,
    "salary" to Icons.Default.AttachMoney,
    "sale" to Icons.Default.Storefront,
    "rewards" to Icons.Default.MilitaryTech,
    "coins" to Icons.Default.Savings,
    "upi" to Icons.Default.QrCode,
    "handshake" to Icons.Default.Handshake,
    "localgasstation" to Icons.Default.LocalGasStation,
    "checkroom" to Icons.Default.Checkroom,
    "payments" to Icons.Default.Payments,
    "eco" to Icons.Default.Eco,
    "twowheeler" to Icons.Default.TwoWheeler,
    "bolt" to Icons.Default.Bolt,
    "creditcard" to Icons.Default.CreditCard,
    "others" to Icons.Default.Category
)

val categoryColorsList = listOf(
    "#FF9800" to Color(0xFFFF9800),
    "#E91E63" to Color(0xFFE91E63),
    "#0288D1" to Color(0xFF0288D1),
    "#9C27B0" to Color(0xFF9C27B0),
    "#FF5722" to Color(0xFFFF5722),
    "#4CAF50" to Color(0xFF4CAF50),
    "#009688" to Color(0xFF009688),
    "#EC407A" to Color(0xFFEC407A),
    "#00BCD4" to Color(0xFF00BCD4),
    "#795548" to Color(0xFF795548),
    "#607D8B" to Color(0xFF607D8B)
)

// 3. BUDGETS SCREEN
@Composable
fun BudgetsScreen(viewModel: FinanceViewModel) {
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val rawMonthYear by viewModel.selectedMonthYear.collectAsStateWithLifecycle()
    val activeBudgets by viewModel.monthlyBudgets.collectAsStateWithLifecycle()
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    
    // Dialog setups
    var showEditCategoryDialog by remember { mutableStateOf<DisplayCategory?>(null) }
    var showBudgetAmountDialog by remember { mutableStateOf<DisplayCategory?>(null) }
    var showCategoryMenuFor by remember { mutableStateOf<String?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var activeCategoryTypeTab by remember { mutableStateOf("EXPENSE") }
    var categoryOrderKeys by remember(activeCategoryTypeTab) { mutableStateOf<List<String>>(emptyList()) }
    var draggingItemKey by remember { mutableStateOf<String?>(null) }
    var draggingItemOffsetY by remember { mutableStateOf(0f) }
    
    val sdfMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    val formatDisplay = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val currentMonthDate = try { sdfMonth.parse(rawMonthYear) ?: Date() } catch (e: Exception) { Date() }

    // Filter transaction for the active month
    val monthExpenses = txs.filter {
        val txMonth = sdfMonth.format(Date(it.timestamp))
        txMonth == rawMonthYear && it.type == "EXPENSE"
    }

    val baseCategories = CategoryResolver.getAll(customCats).filter {
        it.type == activeCategoryTypeTab
    }
    val budgetCategoryNames = activeBudgets.map { it.category.lowercase() }.toSet()

    // Initialize / reset order: budget-set categories first (alpha), then others (alpha)
    if (categoryOrderKeys.isEmpty() || categoryOrderKeys.any { key -> baseCategories.none { it.name == key } }) {
        val withBudget = baseCategories.filter { budgetCategoryNames.contains(it.name.lowercase()) }.sortedBy { it.displayName }
        val withoutBudget = baseCategories.filter { !budgetCategoryNames.contains(it.name.lowercase()) }.sortedBy { it.displayName }
        categoryOrderKeys = (withBudget + withoutBudget).map { it.name }
    }
    val lookup = baseCategories.associateBy { it.name }
    val standardCategoriesList = categoryOrderKeys.mapNotNull { lookup[it] }
    val decFormat = DecimalFormat("₹#,##0.00")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("budgets_scroll_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Upper Title HUD
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = { viewModel.setMonthYear(shiftMonthYear(rawMonthYear, -1)) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.06f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                        }

                        Surface(
                            color = Color(0xFF131A26),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = formatDisplay.format(currentMonthDate),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color(0xFF00E5FF),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = { viewModel.setMonthYear(shiftMonthYear(rawMonthYear, 1)) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.06f),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                        }
                    }

                    Button(
                        onClick = { showAddCategoryDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            contentColor = Color(0xFF00E5FF)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("add_custom_category_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Category", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Category", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // Tab Selector for Expense vs Income Categories
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                val isExp = activeCategoryTypeTab == "EXPENSE"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isExp) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                        .border(1.dp, if (isExp) Color(0xFF00E5FF) else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { activeCategoryTypeTab = "EXPENSE" }
                        .padding(vertical = 10.dp)
                        .testTag("categories_tab_expense"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Expense Categories", color = if (isExp) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isExp) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                        .border(1.dp, if (!isExp) Color(0xFF00E5FF) else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { activeCategoryTypeTab = "INCOME" }
                        .padding(vertical = 10.dp)
                        .testTag("categories_tab_income"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Income Categories", color = if (!isExp) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // Aggregate Overarching Budget Status card
        val allCats = CategoryResolver.getAll(customCats)
        val expenseCatNames = allCats.filter { it.type == "EXPENSE" }.map { it.name.lowercase() }.toSet()
        val incomeCatNames = allCats.filter { it.type == "INCOME" }.map { it.name.lowercase() }.toSet()
        val globalBudgetLimit = activeBudgets
            .filter { expenseCatNames.contains(it.category.lowercase()) }
            .sumOf { it.amountLimit }
        val globalBudgetSpend = monthExpenses.sumOf { it.amount }
        
        if (activeCategoryTypeTab == "EXPENSE") {
            item {
                val percent = if (globalBudgetLimit > 0) (globalBudgetSpend / globalBudgetLimit * 100) else 0.0
                val progressFraction = if (globalBudgetLimit > 0) (globalBudgetSpend / globalBudgetLimit).toFloat().coerceIn(0f, 1f) else 0f
                val overBudget = globalBudgetLimit > 0 && globalBudgetSpend > globalBudgetLimit
                val accentColor = if (overBudget) Color(0xFFF43F5E) else budgetProgressColor(percent)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    accentColor.copy(alpha = 0.18f),
                                    Color(0xFF131A26)
                                )
                            )
                        )
                        .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "MONTHLY BUDGET",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    color = Color.White.copy(alpha = 0.45f)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = decFormat.format(globalBudgetSpend),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 26.sp,
                                    color = accentColor
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (globalBudgetLimit > 0) {
                                    Text(
                                        text = "${String.format(Locale.getDefault(), "%.0f", percent)}%",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 22.sp,
                                        color = accentColor
                                    )
                                }
                                Text(
                                    text = "of ${decFormat.format(globalBudgetLimit)}",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }
                        if (globalBudgetLimit > 0) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progressFraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(accentColor.copy(0.7f), accentColor)
                                            )
                                        )
                                )
                            }
                            if (overBudget) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Over by ${decFormat.format(globalBudgetSpend - globalBudgetLimit)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFFF43F5E),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (activeCategoryTypeTab == "INCOME") {
            val incomeExpectedTotal = activeBudgets
                .filter { incomeCatNames.contains(it.category.lowercase()) }
                .sumOf { it.amountLimit }
            val incomeReceivedTotal = txs.filter {
                val txMonth = sdfMonth.format(Date(it.timestamp))
                txMonth == rawMonthYear && it.type == "INCOME"
            }.sumOf { it.amount }
            if (incomeExpectedTotal > 0) {
                item {
                    val incPct = if (incomeExpectedTotal > 0) (incomeReceivedTotal / incomeExpectedTotal * 100) else 0.0
                    val incFraction = if (incomeExpectedTotal > 0) (incomeReceivedTotal / incomeExpectedTotal).toFloat().coerceIn(0f, 1f) else 0f
                    val overTarget = incomeReceivedTotal >= incomeExpectedTotal
                    val incColor = if (overTarget) Color(0xFF10B981) else Color(0xFF00E5FF)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(incColor.copy(alpha = 0.18f), Color(0xFF131A26))
                                )
                            )
                            .border(1.dp, incColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "MONTHLY INCOME",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp,
                                        color = Color.White.copy(alpha = 0.45f)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = decFormat.format(incomeReceivedTotal),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 26.sp,
                                        color = incColor
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${String.format(Locale.getDefault(), "%.0f", incPct)}%",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 22.sp,
                                        color = incColor
                                    )
                                    Text(
                                        text = "of ${decFormat.format(incomeExpectedTotal)}",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(incFraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(incColor.copy(0.7f), incColor)
                                            )
                                        )
                                )
                            }
                            if (overTarget) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Target reached! +${decFormat.format(incomeReceivedTotal - incomeExpectedTotal)} extra",
                                    fontSize = 11.sp,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // List of configured or unconfigured limits
        item {
            Text(
                text = "MANAGE LIMITS",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }

        val budgetedCats = standardCategoriesList.filter { cat ->
            activeBudgets.any { it.category.equals(cat.name, ignoreCase = true) }
        }
        val unbudgetedCats = standardCategoriesList.filter { cat ->
            activeBudgets.none { it.category.equals(cat.name, ignoreCase = true) }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (budgetedCats.isNotEmpty()) {
                    Text(
                        text = if (activeCategoryTypeTab == "EXPENSE") "BUDGETED" else "EXPECTED INCOME",
                        fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                        color = Color(0xFF00E5FF), modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                budgetedCats.forEach { cat ->
                    val isDragging = draggingItemKey == cat.name
                    val budgetObj = activeBudgets.first { it.category.equals(cat.name, ignoreCase = true) }
                    val catSpend = if (activeCategoryTypeTab == "EXPENSE") {
                        monthExpenses.filter { it.category.equals(cat.name, ignoreCase = true) }.sumOf { it.amount }
                    } else {
                        txs.filter {
                            val txMonth = sdfMonth.format(Date(it.timestamp))
                            txMonth == rawMonthYear && it.type == "INCOME" && it.category.equals(cat.name, ignoreCase = true)
                        }.sumOf { it.amount }
                    }
                    val limit = budgetObj.amountLimit
                    val ratio = if (limit > 0) (catSpend / limit) else 0.0
                    val percent = ratio * 100
                    val progressColor = budgetProgressColor(percent)
                    val remaining = limit - catSpend
                    Surface(
                        color = if (isDragging) Color(0xFF1E3048) else Color(0xFF131A26),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (isDragging) cat.color else cat.color.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth().pointerInput(cat.name) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { _ -> draggingItemKey = cat.name; draggingItemOffsetY = 0f },
                                onDragEnd = { draggingItemKey = null; draggingItemOffsetY = 0f },
                                onDragCancel = { draggingItemKey = null; draggingItemOffsetY = 0f },
                                onDrag = { _, dragAmount ->
                                    val currentKey = draggingItemKey ?: return@detectDragGesturesAfterLongPress
                                    draggingItemOffsetY += dragAmount.y
                                    val currentIndex = categoryOrderKeys.indexOf(currentKey)
                                    if (currentIndex == -1) return@detectDragGesturesAfterLongPress
                                    val itemHeight = size.height.toFloat().coerceAtLeast(1f)
                                    val steps = (draggingItemOffsetY / itemHeight).roundToInt()
                                    val targetIndex = (currentIndex + steps).coerceIn(0, categoryOrderKeys.lastIndex)
                                    if (targetIndex != currentIndex) {
                                        val newOrder = categoryOrderKeys.toMutableList()
                                        val moved = newOrder.removeAt(currentIndex)
                                        newOrder.add(targetIndex, moved)
                                        categoryOrderKeys = newOrder
                                        draggingItemOffsetY -= (targetIndex - currentIndex) * itemHeight
                                    }
                                }
                            )
                        }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Surface(shape = CircleShape, color = cat.color.copy(alpha = 0.15f), modifier = Modifier.size(52.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(imageVector = cat.icon, contentDescription = cat.name, tint = cat.color, modifier = Modifier.size(26.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White,
                                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("${compactCurrency(catSpend)} / ${compactCurrency(limit)}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f), fontWeight = FontWeight.SemiBold)
                                        Box {
                                            IconButton(onClick = { showCategoryMenuFor = cat.name }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                            }
                                            DropdownMenu(
                                                expanded = showCategoryMenuFor == cat.name,
                                                onDismissRequest = { showCategoryMenuFor = null },
                                                containerColor = Color(0xFF1E293B)
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(if (activeCategoryTypeTab == "INCOME") "Edit Expected Amount" else "Edit Budget", color = Color.White, fontSize = 13.sp) },
                                                    onClick = { showBudgetAmountDialog = cat; showCategoryMenuFor = null }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Remove Budget", color = Color(0xFFF43F5E), fontSize = 13.sp) },
                                                    onClick = { viewModel.deleteBudget(budgetObj.id); showCategoryMenuFor = null }
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Remaining: ${compactCurrency(remaining)}",
                                        fontSize = 12.sp,
                                        color = if (remaining < 0) Color(0xFFF43F5E) else Color(0xFF10B981)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LinearProgressIndicator(
                                            progress = ratio.toFloat().coerceIn(0f, 1f),
                                            color = progressColor,
                                            trackColor = Color.White.copy(alpha = 0.05f),
                                            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${String.format(Locale.getDefault(), "%.1f", percent)}%",
                                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = progressColor,
                                            modifier = Modifier.widthIn(min = 44.dp), textAlign = TextAlign.End
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (unbudgetedCats.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "NOT BUDGETED",
                        fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                        color = Color.White.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                unbudgetedCats.forEach { cat ->
                    val catIncome = txs.filter {
                        val txMonth = sdfMonth.format(Date(it.timestamp))
                        txMonth == rawMonthYear && it.type == "INCOME" && it.category.equals(cat.name, ignoreCase = true)
                    }.sumOf { it.amount }
                    Surface(
                        color = Color(0xFF131A26),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showEditCategoryDialog = cat }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = cat.color.copy(alpha = 0.15f), modifier = Modifier.size(52.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(imageVector = cat.icon, contentDescription = cat.name, tint = cat.color, modifier = Modifier.size(26.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            if (activeCategoryTypeTab == "INCOME") {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (catIncome > 0) {
                                        Text("${compactCurrency(catIncome)} received", fontSize = 12.sp, color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { showBudgetAmountDialog = cat },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(if (activeCategoryTypeTab == "INCOME") "Set Expected" else "Set Budget", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet or Alert Dialog for writing categories budgets
    showEditCategoryDialog?.let { cat ->
        var editCatName by remember(cat) { mutableStateOf(cat.displayName) }
        var selectedIconName by remember(cat) { mutableStateOf("others") }
        var selectedColorHex by remember(cat) { mutableStateOf("#607D8B") }
        var selectedColor by remember(cat) { mutableStateOf(cat.color) }
        val checkCurrent = activeBudgets.firstOrNull { it.category.equals(cat.name, ignoreCase = true) }

        LaunchedEffect(cat) {
            editCatName = cat.displayName
            val matchedIcon = suitableIconsList.firstOrNull { it.second == cat.icon }?.first
            if (matchedIcon != null) selectedIconName = matchedIcon
            val matchedColorHex = categoryColorsList.firstOrNull { it.second.value == cat.color.value }?.first
            if (matchedColorHex != null) {
                selectedColorHex = matchedColorHex
            } else {
                selectedColorHex = String.format("#%06X", (0xFFFFFF and cat.color.value.toInt()))
            }
            selectedColor = cat.color
        }

        AlertDialog(
            onDismissRequest = { showEditCategoryDialog = null },
            title = { Text("Edit Category", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = editCatName,
                        onValueChange = { editCatName = it },
                        label = { Text("Category Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("edit_category_name_field")
                    )

                    Text("Select Icon", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(suitableIconsList) { (iconName, iconVec) ->
                            val isSelected = selectedIconName == iconName
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) selectedColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(2.dp, if (isSelected) selectedColor else Color.Transparent),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable { selectedIconName = iconName }
                                    .testTag("edit_icon_$iconName")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = iconVec,
                                        contentDescription = iconName,
                                        tint = if (isSelected) selectedColor else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text("Select Color", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categoryColorsList) { (hexStr, colorObj) ->
                            val isSelected = selectedColorHex == hexStr
                            Surface(
                                shape = CircleShape,
                                color = colorObj,
                                border = BorderStroke(2.dp, if (isSelected) Color.White else Color.Transparent),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable { selectedColorHex = hexStr; selectedColor = colorObj }
                                    .testTag("edit_color_$hexStr")
                            ) {
                                if (isSelected) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Button(
                        onClick = {
                            if (cat.isCustom) viewModel.deleteCustomCategory(cat.customId)
                            else viewModel.hideStandardCategory(cat.name, cat.type)
                            if (checkCurrent != null) viewModel.deleteBudget(checkCurrent.id)
                            showEditCategoryDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF43F5E), contentColor = Color.White),
                        modifier = Modifier.testTag("delete_custom_category_button")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val cleanName = editCatName.trim()
                            if (cat.isCustom) {
                                viewModel.updateCustomCategory(
                                    id = cat.customId,
                                    oldName = cat.name,
                                    newName = cleanName,
                                    iconName = selectedIconName,
                                    colorHex = selectedColorHex,
                                    type = cat.type
                                )
                            } else {
                                viewModel.customizeStandardCategory(
                                    oldName = cat.name,
                                    newName = cleanName,
                                    iconName = selectedIconName,
                                    colorHex = selectedColorHex,
                                    type = cat.type
                                )
                            }
                            showEditCategoryDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19)),
                        modifier = Modifier.testTag("save_category_changes_button")
                    ) {
                        Text("Save Changes", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditCategoryDialog = null },
                    modifier = Modifier.testTag("cancel_category_changes_button")
                ) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF131A26)
        )
    }

    showBudgetAmountDialog?.let { cat ->
        var budgetValStr by remember(cat) { mutableStateOf("") }
        val checkCurrent = activeBudgets.firstOrNull { it.category.equals(cat.name, ignoreCase = true) }

        LaunchedEffect(cat) {
            checkCurrent?.let { budgetValStr = it.amountLimit.toInt().toString() }
        }

        AlertDialog(
            onDismissRequest = { showBudgetAmountDialog = null },
            title = {
                Text(
                    if (cat.type == "INCOME") "Set Expected Income" else "Set Budget Limit",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (cat.type == "INCOME")
                            "Set the expected monthly income for ${cat.displayName}"
                        else
                            "Set a monthly budget limit for ${cat.displayName}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = budgetValStr,
                        onValueChange = { budgetValStr = it },
                        label = { Text(if (cat.type == "INCOME") "Expected Amount (₹)" else "Budget Limit (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("edit_category_budget_field")
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (checkCurrent != null) {
                        TextButton(
                            onClick = { viewModel.deleteBudget(checkCurrent.id); showBudgetAmountDialog = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF43F5E))
                        ) { Text("Remove", fontWeight = FontWeight.Bold) }
                    }
                    Button(
                        onClick = {
                            val limitAmt = budgetValStr.toDoubleOrNull() ?: 0.0
                            val effectiveBudgetCategory = resolveBudgetCategoryName(cat, cat.displayName)
                            if (limitAmt > 0) {
                                viewModel.saveBudget(effectiveBudgetCategory, cat.displayName, limitAmt, cat.name)
                            }
                            showBudgetAmountDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
                    ) {
                        Text(if (checkCurrent != null) "Update" else "Set Budget", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetAmountDialog = null }) { Text("Cancel", color = Color.White) }
            },
            containerColor = Color(0xFF131A26)
        )
    }

    if (showAddCategoryDialog) {
        var newCatName by remember { mutableStateOf("") }
        var selectedIconName by remember { mutableStateOf("restaurant") }
        var selectedColorHex by remember { mutableStateOf("#00E5FF") }
        var selectedColor by remember { mutableStateOf(Color(0xFF00E5FF)) }
        var selectedType by remember { mutableStateOf("EXPENSE") }
        var initialBudgetAmt by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = {
                Text(
                    "Add New Category",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it },
                        label = { Text("Category Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("add_category_name_field")
                    )

                    Text("Category Type", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .padding(4.dp)
                    ) {
                        val isExp = selectedType == "EXPENSE"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isExp) Color(0xFFF43F5E) else Color.Transparent)
                                .clickable { selectedType = "EXPENSE" }
                                .padding(8.dp)
                                .testTag("type_selector_expense"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Expense", color = if (isExp) Color.White else Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isExp) Color(0xFF10B981) else Color.Transparent)
                                .clickable { selectedType = "INCOME" }
                                .padding(8.dp)
                                .testTag("type_selector_income"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Income", color = if (!isExp) Color.White else Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (selectedType == "EXPENSE") {
                        OutlinedTextField(
                            value = initialBudgetAmt,
                            onValueChange = { initialBudgetAmt = it },
                            label = { Text("Monthly Budget Limit (₹, Optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                focusedLabelColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("add_category_budget_field")
                        )
                    }

                    Text("Select Icon", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(suitableIconsList) { (iconName, iconVec) ->
                            val isSelected = selectedIconName == iconName
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) selectedColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f),
                                border = BorderStroke(2.dp, if (isSelected) selectedColor else Color.Transparent),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable { selectedIconName = iconName }
                                    .testTag("add_icon_$iconName")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = iconVec,
                                        contentDescription = iconName,
                                        tint = if (isSelected) selectedColor else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text("Select Color", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categoryColorsList) { (hexStr, colorObj) ->
                            val isSelected = selectedColorHex == hexStr
                            Surface(
                                shape = CircleShape,
                                color = colorObj,
                                border = BorderStroke(2.dp, if (isSelected) Color.White else Color.Transparent),
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable {
                                        selectedColorHex = hexStr
                                        selectedColor = colorObj
                                    }
                                    .testTag("add_color_$hexStr")
                            ) {
                                if (isSelected) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanName = newCatName.trim()
                        if (cleanName.isNotEmpty()) {
                            viewModel.addCustomCategory(cleanName, selectedIconName, selectedColorHex, selectedType)
                            val budgetLimit = initialBudgetAmt.toDoubleOrNull() ?: 0.0
                            if (selectedType == "EXPENSE" && budgetLimit > 0) {
                                viewModel.saveBudget(cleanName, cleanName, budgetLimit)
                            }
                        }
                        showAddCategoryDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19)),
                    modifier = Modifier.testTag("add_category_confirm_button")
                ) {
                    Text("Add Category", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddCategoryDialog = false },
                    modifier = Modifier.testTag("add_category_cancel_button")
                ) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF131A26)
        )
    }
}

// 4. ACCOUNTS / WALLETS MANAGEMENT SCREEN
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(viewModel: FinanceViewModel) {
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val carryOverPreviousAmount by viewModel.carryOverPreviousAmount.collectAsStateWithLifecycle()
    val showTotal by viewModel.showTotal.collectAsStateWithLifecycle()
    val blockedSmsAccountIds by viewModel.blockedSmsAccountIds.collectAsStateWithLifecycle()
    val showCreditCardDetails by viewModel.showCreditCardDetails.collectAsStateWithLifecycle()
    val hiddenAccountIds by viewModel.hiddenAccountIds.collectAsStateWithLifecycle()
    val smsBlocklistPatterns by viewModel.smsBlocklistPatterns.collectAsStateWithLifecycle()
    val walletsBalances = computeWalletBalances(txs, accounts, carryOverPreviousAmount)

    var showTransferDialog by remember { mutableStateOf(false) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var selectedAccountForEdit by remember { mutableStateOf<Account?>(null) }
    var showAccountCenterSettings by remember { mutableStateOf(false) }

    val decFormat = DecimalFormat("₹#,##0.00")

    val activeAccounts = accounts.filter { !hiddenAccountIds.contains(it.id) }
    val orderedAccounts = activeAccounts

    val totalAllAccounts = activeAccounts.sumOf { walletsBalances[it.name] ?: 0.0 }
    val totalIncomeSoFar = txs.filter { it.type == "INCOME" }.sumOf { it.amount }
    val totalExpenseSoFar = txs.filter { it.type == "EXPENSE" }.sumOf { it.amount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("accounts_scroll_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Upper balance HUD
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Accounts Center",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
                IconButton(onClick = { showAccountCenterSettings = true }) {
                    Icon(Icons.Default.Menu, contentDescription = "Account Settings", tint = Color.White)
                }
            }
            if (showAccountCenterSettings) {
                AccountCenterSettingsDialog(
                    viewModel = viewModel,
                    accounts = accounts,
                    hiddenAccountIds = hiddenAccountIds,
                    showCreditCardDetails = showCreditCardDetails,
                    smsBlocklistPatterns = smsBlocklistPatterns,
                    onDismiss = { showAccountCenterSettings = false }
                )
            }
        }

        // Net Wealth Overview Card
        item {
            Surface(
                color = Color(0xFF131A26),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.2.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "ALL ACCOUNTS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (showTotal) decFormat.format(totalAllAccounts) else "₹ ••••",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        color = Color(0xFF00E5FF)
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Income so far", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                            Text(if (showTotal) decFormat.format(totalIncomeSoFar) else "₹ ••••", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF10B981))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Expense so far", fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f))
                            Text(if (showTotal) decFormat.format(totalExpenseSoFar) else "₹ ••••", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFF43F5E))
                        }
                    }
                }
            }
        }

        // Actions: inter-wallet transfer setup
        item {
            Button(
                onClick = { showTransferDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.15f), contentColor = Color(0xFF00E5FF)),
                border = BorderStroke(1.2.dp, Color(0xFF00E5FF)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = activeAccounts.size >= 2
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Transfer icon")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (activeAccounts.size >= 2) "Transfer Funds Between Wallets" else "Add at least 2 wallets to transfer funds",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        // List of configured mock cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MANAGE WALLETS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
                TextButton(
                    onClick = { showAddAccountDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00E5FF))
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Icon", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Account", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (activeAccounts.isEmpty()) {
            item {
                Surface(
                    color = Color(0xFF131A26),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = "No wallets",
                            tint = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No wallets added yet",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Add a wallet to track real balances. No default balances are created automatically.",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    orderedAccounts.forEach { acc ->
                        val bal = walletsBalances[acc.name] ?: 0.0
                        val smsTrackingBlocked = blockedSmsAccountIds.contains(acc.id)
                        val color = when(acc.type) {
                            "CASH" -> Color(0xFF10B981)
                            "BANK" -> Color(0xFF00E5FF)
                            "CREDIT_CARD" -> Color(0xFFF43F5E)
                            "WALLET" -> Color(0xFFFF9800)
                            else -> Color(0xFF94A3B8)
                        }

                        Surface(
                            color = Color(0xFF131A26),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Color(0xFF1E293B)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccountForEdit = acc }
                        ) {
                            Row(
                                modifier = Modifier.padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when(acc.type) {
                                        "CASH" -> Icons.Default.Money
                                        "BANK" -> Icons.Default.AccountBalance
                                        "CREDIT_CARD" -> Icons.Default.CreditCard
                                        "WALLET" -> Icons.Default.AccountBalanceWallet
                                        else -> Icons.Default.AllInclusive
                                    },
                                    contentDescription = acc.type,
                                    tint = color,
                                    modifier = Modifier.size(24.dp)
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        acc.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                    if (smsTrackingBlocked) {
                                        Text(
                                            text = "SMS auto-tracking blocked",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFB923C)
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        decFormat.format(bal),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 16.sp,
                                        color = if (bal >= 0) Color(0xFF10B981) else Color(0xFFF43F5E)
                                    )
                                    if (acc.type == "CREDIT_CARD" && showCreditCardDetails && acc.availableLimit > 0) {
                                        Text(
                                            text = "Avail: ${decFormat.format(acc.availableLimit)}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF00E5FF)
                                        )
                                        if (acc.creditLimit > 0) {
                                            val outstanding = acc.creditLimit - acc.availableLimit
                                            Text(
                                                text = "Due: ${decFormat.format(outstanding.coerceAtLeast(0.0))}",
                                                fontSize = 11.sp,
                                                color = Color(0xFFFB923C)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal sheet: transfer funds popup
    if (showTransferDialog) {
        var trAmount by remember { mutableStateOf("") }
        var sourceWall by remember { mutableStateOf(activeAccounts.first().name) }
        var destWall by remember { mutableStateOf(activeAccounts.getOrNull(1)?.name ?: activeAccounts.first().name) }

        val supportWallets = activeAccounts.map { it.name }

        AlertDialog(
            onDismissRequest = { showTransferDialog = false },
            title = { Text("Transfer Funds", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Record a virtual transfer of liquidity. Note: This creates a synchronized offsetting expense & income transaction.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                    
                    OutlinedTextField(
                        value = trAmount,
                        onValueChange = { trAmount = it },
                        label = { Text("Transfer Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Source Select Dropdown
                    Column {
                        Text("FROM SOURCE WALLET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(supportWallets.size) { i ->
                                val w = supportWallets[i]
                                val sel = sourceWall == w
                                Box(
                                    modifier = Modifier
                                        .background(if (sel) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { sourceWall = w }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(w.substringBefore(" Ending"), fontSize = 10.sp, color = if (sel) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    // Dest Select Dropdown
                    Column {
                        Text("TO DESTINATION WALLET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(supportWallets.size) { i ->
                                val w = supportWallets[i]
                                val sel = destWall == w
                                Box(
                                    modifier = Modifier
                                        .background(if (sel) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { destWall = w }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(w.substringBefore(" Ending"), fontSize = 10.sp, color = if (sel) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dVal = trAmount.toDoubleOrNull() ?: 0.0
                        if (dVal > 0.0 && sourceWall != destWall) {
                            // Deduct from Source
                            viewModel.addTransaction(
                                title = "Transfer to $destWall",
                                amount = dVal,
                                categoryName = "OTHERS",
                                type = "EXPENSE",
                                note = "[Acc: $sourceWall]"
                            )
                            // Credit to Dest
                            viewModel.addTransaction(
                                title = "Transfer from $sourceWall",
                                amount = dVal,
                                categoryName = "OTHERS",
                                type = "INCOME",
                                note = "[Acc: $destWall]"
                            )
                        }
                        showTransferDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
                ) {
                    Text("Execute Transfer", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTransferDialog = false }) { Text("Cancel", color = Color.White) }
            },
            containerColor = Color(0xFF131A26)
        )
    }

    // Dialog: Add Custom Account Dialog
    if (showAddAccountDialog) {
        var nameInput by remember { mutableStateOf("") }
        var baseBalanceInput by remember { mutableStateOf("") }
        var typeInput by remember { mutableStateOf("BANK") }
        var last4Input by remember { mutableStateOf("") }
        var openingBalanceTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

        val types = listOf("CASH", "BANK", "CREDIT_CARD", "WALLET")

        AlertDialog(
            onDismissRequest = { showAddAccountDialog = false },
            title = { Text("Create New Wallet", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Account Name (e.g. SBI Bank)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF)),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = baseBalanceInput,
                        onValueChange = { baseBalanceInput = it },
                        label = { Text("Initial Ledger Balance (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF)),
                        modifier = Modifier.fillMaxWidth()
                    )

                    TransactionDateTimePicker(
                        selectedTimestamp = openingBalanceTimestamp,
                        onTimestampChange = { openingBalanceTimestamp = it },
                        label = "Opening Balance Date & Time"
                    )

                    OutlinedTextField(
                        value = last4Input,
                        onValueChange = { last4Input = it },
                        label = { Text("Last 4 digits (optional ID)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF)),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column {
                        Text("ACCOUNT CATEGORY / TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            types.forEach { t ->
                                val sel = typeInput == t
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (sel) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { typeInput = t }
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(t.replace("_", " "), fontSize = 9.sp, color = if (sel) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val initBal = baseBalanceInput.toDoubleOrNull() ?: 0.0
                        viewModel.addAccount(
                            name = nameInput,
                            balance = initBal,
                            type = typeInput,
                            lastFour = if (last4Input.isBlank()) null else last4Input,
                            openingBalanceTimestamp = openingBalanceTimestamp
                        )
                        showAddAccountDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
                ) {
                    Text("Provision Wallet", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAccountDialog = false }) { Text("Cancel", color = Color.White) }
            },
            containerColor = Color(0xFF131A26)
        )
    }

    // Dialog: Adjust / Edit / Delete Account Dialog
    selectedAccountForEdit?.let { acc ->
        val computedBal = walletsBalances[acc.name] ?: acc.balance
        var editName by remember(acc) { mutableStateOf(acc.name) }
        var editBalanceInput by remember(acc) { mutableStateOf(String.format("%.2f", computedBal)) }
        var editType by remember(acc) { mutableStateOf(acc.type) }
        var editLast4 by remember(acc) { mutableStateOf(acc.lastFour ?: "") }
        var editCreditLimit by remember(acc) { mutableStateOf(if (acc.creditLimit > 0) acc.creditLimit.toString() else "") }

        val types = listOf("CASH", "BANK", "CREDIT_CARD", "WALLET")

        AlertDialog(
            onDismissRequest = { selectedAccountForEdit = null },
            title = { Text("Edit Wallet", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(
                        color = Color(0xFF00E5FF).copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Current Balance", fontSize = 12.sp, color = Color.White.copy(0.6f))
                            Text(
                                text = "₹${String.format("%.2f", computedBal)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Account Name") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF)),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editBalanceInput,
                        onValueChange = { editBalanceInput = it },
                        label = { Text("Set New Balance (₹)") },
                        placeholder = { Text("e.g. 25000", color = Color.White.copy(0.4f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF)),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editLast4,
                        onValueChange = { editLast4 = it },
                        label = { Text("Last 4 digits") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF)),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (editType == "CREDIT_CARD") {
                        OutlinedTextField(
                            value = editCreditLimit,
                            onValueChange = { editCreditLimit = it },
                            label = { Text("Credit Limit (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF)),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column {
                        Text("ACCOUNT CATEGORY / TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            types.forEach { t ->
                                val sel = editType == t
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (sel) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { editType = t }
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(t.replace("_", " "), fontSize = 9.sp, color = if (sel) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.deleteAccount(acc.id)
                            selectedAccountForEdit = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF43F5E))
                    ) {
                        Text("Delete Wallet", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val targetBal = editBalanceInput.toDoubleOrNull() ?: computedBal
                            val oldName = acc.name

                            // Create Adjust transaction if balance changed
                            if (Math.abs(targetBal - computedBal) >= 0.01) {
                                viewModel.adjustAccountBalance(oldName, computedBal, targetBal)
                            }

                            // Update account metadata only (not balance — the Adjust tx handles it)
                            viewModel.updateAccount(
                                acc.copy(
                                    name = editName,
                                    type = editType,
                                    lastFour = if (editLast4.isBlank()) null else editLast4,
                                    creditLimit = editCreditLimit.toDoubleOrNull() ?: acc.creditLimit
                                )
                            )

                            if (oldName != editName) {
                                viewModel.renameAccountTransactions(oldName, editName)
                            }
                            selectedAccountForEdit = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
                    ) {
                        Text("Save Changes", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAccountForEdit = null }) { Text("Cancel", color = Color.White) }
            },
            containerColor = Color(0xFF131A26)
        )
    }
}

// 5. AUTO-SCAN SMS UTILITY HUB
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoScanHubScreen(viewModel: FinanceViewModel) {
    val context = LocalContext.current
    var manualSmsSender by remember { mutableStateOf("HDFC-BANK") }
    var manualSmsBody by remember { mutableStateOf("") }
    var customPatternInput by remember { mutableStateOf("") }
    val forcePatternOptions = listOf("txn", "debited", "credited", "spent", "paid", "received", "upi", "card", "account", "salary")
    val suggestedForcePatterns = remember(manualSmsBody) {
        val lowerBody = manualSmsBody.lowercase()
        forcePatternOptions.filter { lowerBody.contains(it) }
    }
    var selectedForcePatterns by remember { mutableStateOf(forcePatternOptions.toSet()) }
    var customPatterns by remember { mutableStateOf(emptyList<String>()) }
    // 1 = this month only, 2 = last + this month, 3 = last 3 months (calendar start-of-month boundaries)
    var smsScanMonthsBack by remember { mutableStateOf(1) }
    val isSmsParsing by viewModel.isSmsParsing.collectAsStateWithLifecycle()
    val enableBalanceSync by viewModel.enableBalanceSync.collectAsStateWithLifecycle()
    var hasReadSmsPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    var hasReceiveSmsPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    val hasSmsPermissions = hasReadSmsPermission && hasReceiveSmsPermission
    val requestSmsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasReadSmsPermission = result[Manifest.permission.READ_SMS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        hasReceiveSmsPermission = result[Manifest.permission.RECEIVE_SMS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (hasReadSmsPermission) {
            Toast.makeText(context, "SMS access enabled. Importing inbox now.", Toast.LENGTH_SHORT).show()
            viewModel.scanDeviceSmsInbox(context, smsScanMonthsBack)
        } else {
            Toast.makeText(context, "SMS read permission is required for inbox import.", Toast.LENGTH_SHORT).show()
        }
    }
    val merchantRules by viewModel.merchantCategoryRules.collectAsStateWithLifecycle()
    val customCatsForMerchant by viewModel.allCustomCategories.collectAsStateWithLifecycle()
    val allMerchantCategoryOptions = remember(customCatsForMerchant) {
        (com.example.data.ExpenseCategory.entries
            .filter { it.type == "EXPENSE" && it.name != "OUTSIDE_FOOD" && it.name != "POCKET_MONEY" }
            .map { it.name to it.displayName } +
        customCatsForMerchant.filter { !it.iconName.startsWith("hidden") && !it.iconName.equals("hidden") && it.iconName.split(":").getOrNull(1) != "INCOME" }
            .map { it.name to it.name })
        .sortedBy { it.second }
    }
    var merchantPatternInput by remember { mutableStateOf("") }
    var merchantCategoryInput by remember { mutableStateOf("") }
    var merchantCategoryDropdownExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("auto_scan_hub_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "SMS Automatic Tracking",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        }

        // Device Scan Action panel
        item {
            Surface(
                color = Color(0xFF131A26),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Security badge",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Secure Offline Automated Scanner", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Reads your messages locally using regex pattern matching and/or local AI. No financial or personal data ever leaves your device.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Permission Status", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Surface(
                            color = if (hasSmsPermissions) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF43F5E).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (hasSmsPermissions) "GRANTED" else "REQUIRED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasSmsPermissions) Color(0xFF10B981) else Color(0xFFF43F5E),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scan range selector
                    Text("Scan Range", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    val scanRangeOptions = listOf(1 to "This Month", 2 to "Last 2 Months", 3 to "Last 3 Months")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        scanRangeOptions.forEach { (months, label) ->
                            val selected = smsScanMonthsBack == months
                            Surface(
                                onClick = { smsScanMonthsBack = months },
                                color = if (selected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF00E5FF) else Color(0xFF2D3748)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                if (hasReadSmsPermission) {
                                    viewModel.scanDeviceSmsInbox(context, smsScanMonthsBack)
                                } else {
                                    requestSmsLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("scan_device_sms_button")
                        ) {
                            Text(if (hasReadSmsPermission) "Scan Inbox" else "Enable Auto-Import", fontWeight = FontWeight.Bold)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Bal\nSync",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.End
                            )
                            Switch(
                                checked = enableBalanceSync,
                                onCheckedChange = { viewModel.setEnableBalanceSync(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF00E5FF),
                                    checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.35f),
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.5f),
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                )
                            )
                        }
                    }
                }
            }
        }
        // Manual pasted SMS analyzer
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131A26)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        "PARSER KEY RULES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Select core keys and/or add custom patterns. Prefix with ! to exclude — e.g. !(emi Balance) skips any SMS containing that text. Hit \"Scan Inbox\" to apply globally.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Parser keys (must contain at least one)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    if (suggestedForcePatterns.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Suggested from pasted SMS: ${suggestedForcePatterns.joinToString(", ")}",
                            fontSize = 10.sp,
                            color = Color(0xFF00E5FF)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        forcePatternOptions.forEach { pattern ->
                            FilterChip(
                                selected = selectedForcePatterns.contains(pattern),
                                onClick = {
                                    selectedForcePatterns = if (selectedForcePatterns.contains(pattern)) {
                                        selectedForcePatterns - pattern
                                    } else {
                                        selectedForcePatterns + pattern
                                    }
                                },
                                label = { Text(pattern) },
                                leadingIcon = if (selectedForcePatterns.contains(pattern)) {
                                    { Icon(Icons.Default.Check, contentDescription = pattern, modifier = Modifier.size(16.dp)) }
                                } else {
                                    null
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.18f),
                                    selectedLabelColor = Color(0xFF00E5FF),
                                    selectedLeadingIconColor = Color(0xFF00E5FF),
                                    containerColor = Color.White.copy(alpha = 0.04f),
                                    labelColor = Color.White
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selectedForcePatterns.contains(pattern),
                                    borderColor = Color.White.copy(alpha = 0.08f),
                                    selectedBorderColor = Color(0xFF00E5FF).copy(alpha = 0.45f)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customPatternInput,
                            onValueChange = { customPatternInput = it },
                            label = { Text("Add Custom Pattern") },
                            placeholder = { Text("salary  |  !(emi Balance)") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                val pattern = customPatternInput.trim()
                                if (pattern.isNotBlank() && pattern !in customPatterns) {
                                    customPatterns = customPatterns + pattern
                                }
                                customPatternInput = ""
                            },
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f))
                        ) {
                            Text("Add", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                        }
                    }

                    if (customPatterns.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            customPatterns.forEach { pattern ->
                                InputChip(
                                    selected = true,
                                    onClick = { customPatterns = customPatterns.filterNot { it == pattern } },
                                    label = { Text(pattern) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, contentDescription = "Remove pattern", modifier = Modifier.size(16.dp))
                                    },
                                    colors = InputChipDefaults.inputChipColors(
                                        selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.15f),
                                        selectedLabelColor = Color(0xFF10B981),
                                        selectedTrailingIconColor = Color(0xFF10B981)
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Scan Inbox with custom rules (global)
                    Button(
                        onClick = {
                            if (hasReadSmsPermission) {
                                viewModel.scanInboxWithCustomRules(
                                    context,
                                    selectedForcePatterns.toList(),
                                    customPatterns,
                                    smsScanMonthsBack
                                )
                            } else {
                                requestSmsLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                            }
                        },
                        enabled = !isSmsParsing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF), contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (isSmsParsing) "Scanning…"
                            else if (!hasReadSmsPermission) "Enable SMS Permission to Scan"
                            else "Scan Inbox with These Rules",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        "PASTE MISSED SMS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = manualSmsSender,
                        onValueChange = { manualSmsSender = it },
                        label = { Text("Sender ID") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = manualSmsBody,
                        onValueChange = { manualSmsBody = it },
                        label = { Text("Real SMS Body") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.analyzePastedSms(manualSmsBody, manualSmsSender, selectedForcePatterns.toList(), customPatterns) },
                        enabled = manualSmsBody.isNotBlank() && !isSmsParsing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF0B0F19)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("analyze_pasted_sms_button")
                    ) {
                        Text(if (isSmsParsing) "Analyzing…" else "Analyze & Import Pasted SMS", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Merchant → Category Mapping ───────────────────────────────────────
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131A26)),
                border = BorderStroke(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Category, contentDescription = null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(20.dp))
                        Text("Merchant → Category Rules", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                    }
                    Text(
                        "Map payee/merchant names to categories. Supports wildcards:\n" +
                        "  zerodha*  →  starts with\n" +
                        "  *paytm*   →  contains\n" +
                        "  *nova     →  ends with\n" +
                        "  (no *)    →  contains (same as *text*)\n" +
                        "Rules here override built-in categorization.",
                        fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f), lineHeight = 16.sp
                    )

                    // Input row
                    OutlinedTextField(
                        value = merchantPatternInput,
                        onValueChange = { merchantPatternInput = it },
                        label = { Text("Merchant Pattern (e.g. zerodha*, *paytm*)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF7C4DFF), unfocusedBorderColor = Color(0xFF2D3748),
                            focusedLabelColor = Color(0xFF7C4DFF), unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Category picker
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = merchantCategoryInput,
                            onValueChange = { merchantCategoryInput = it },
                            label = { Text("Target Category") },
                            trailingIcon = {
                                IconButton(onClick = { merchantCategoryDropdownExpanded = !merchantCategoryDropdownExpanded }) {
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF7C4DFF))
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF7C4DFF), unfocusedBorderColor = Color(0xFF2D3748),
                                focusedLabelColor = Color(0xFF7C4DFF), unfocusedLabelColor = Color.White.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = merchantCategoryDropdownExpanded,
                            onDismissRequest = { merchantCategoryDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E293B)).heightIn(max = 280.dp)
                        ) {
                            allMerchantCategoryOptions.forEach { (catName, catDisplay) ->
                                DropdownMenuItem(
                                    text = { Text(catDisplay, fontSize = 13.sp, color = Color.White) },
                                    onClick = {
                                        merchantCategoryInput = catName
                                        merchantCategoryDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Add button
                    Button(
                        onClick = {
                            if (merchantPatternInput.isNotBlank() && merchantCategoryInput.isNotBlank()) {
                                viewModel.addMerchantCategoryRule(merchantPatternInput.trim(), merchantCategoryInput.trim())
                                merchantPatternInput = ""
                                merchantCategoryInput = ""
                            }
                        },
                        enabled = merchantPatternInput.isNotBlank() && merchantCategoryInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF), contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Rule", fontWeight = FontWeight.Bold)
                    }

                    // Existing rules list
                    if (merchantRules.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.07f))
                        Text("Active Rules (${merchantRules.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f))
                        merchantRules.forEach { (pattern, category) ->
                            Surface(
                                color = Color(0xFF0B0F19),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF2D3748)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pattern, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF00E5FF))
                                        Text("→ $category", fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeMerchantCategoryRule(pattern) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color(0xFFF43F5E), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Re-apply to existing records
                        OutlinedButton(
                            onClick = { viewModel.reapplyMerchantRulesToExisting() },
                            border = BorderStroke(1.dp, Color(0xFFFF9800)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Re-apply Rules to All Existing Records", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                        }
                    }
                }
            }
        }

        // Parser exclusion rules reference card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1320)),
                border = BorderStroke(1.dp, Color(0xFFF43F5E).copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "PARSER EXCLUSION & INCLUSION RULES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = Color(0xFFF43F5E).copy(alpha = 0.8f)
                    )
                    Text(
                        "These are the hard-coded rules the parser uses. Messages matching ANY exclusion word are rejected. Messages missing ALL inclusion words are also rejected.",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )

                    // EXCLUSION keywords (SmsFilterUtility hard-rejects)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AUTO-REJECT if body contains:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF43F5E))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("due", "emi", "loan", "otp", "mandate", "load", "eligibility", "apply", "approved").forEach { kw ->
                                Surface(color = Color(0xFFF43F5E).copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
                                    Text(kw, fontSize = 10.sp, color = Color(0xFFF43F5E), fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    // OTP / promo exclusions from SmsParser
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ALSO REJECTED (OTP / Promo / Reminder):", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("one time password", "verification code", "do not share", "is due", "payment due", "reminder:",
                                "pay before", "outstanding", "will be debited", "scheduled transfer", "pre-approved",
                                "apply now", "earn cashback", "requesting money", "total amt due", "minimum amt due",
                                "amount due", "stmt due").forEach { kw ->
                                Surface(color = Color(0xFFFF9800).copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                                    Text(kw, fontSize = 9.sp, color = Color(0xFFFF9800), fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    // REQUIRED inclusion keywords
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("REQUIRED — must contain at least one:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("debited", "credited", "spent", "received", "deducted", "sent", "paid", "withdrawn",
                                "transfer", "payment", "charge", "txn", "salary", "refund", "autopay").forEach { kw ->
                                Surface(color = Color(0xFF10B981).copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                                    Text(kw, fontSize = 10.sp, color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    // Structural requirement
                    Surface(color = Color.White.copy(alpha = 0.04f), shape = RoundedCornerShape(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CreditCard, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                            Text("Must contain a 3–4 digit account/card reference (e.g. a/c xx1234, card ending 4321, acc 567)",
                                fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

// 6. POPUP DIALOGS & UTILS
@Composable
fun AddTransactionDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, String, String, String?, Long) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var transactionType by remember { mutableStateOf("EXPENSE") } // EXPENSE, INCOME
    var categorySelection by remember { mutableStateOf("FOOD") }
    var accountSelection by remember { mutableStateOf("Cash Wallet") }
    var notesStr by remember { mutableStateOf("") }
    var selectedTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    var showWalletPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showQuickAddAccount by remember { mutableStateOf(false) }
    var showQuickAddCategory by remember { mutableStateOf(false) }

    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    val allCategories = CategoryResolver.getAll(customCats)
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val selectablesWallets = if (accounts.isEmpty()) {
        listOf("Cash Wallet", "Bank Account", "Credit Card", "Digital Wallet")
    } else {
        accounts.map { it.name }
    }
    val filteredCats = if (transactionType == "EXPENSE") {
        allCategories.filter { it.type == "EXPENSE" }
    } else {
        allCategories.filter { it.type == "INCOME" }
    }
    val selectedCategory = filteredCats.firstOrNull { it.name == categorySelection }
        ?: allCategories.firstOrNull { it.name == categorySelection }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Cashflow", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Type selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    val isExp = transactionType == "EXPENSE"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isExp) Color(0xFFF43F5E) else Color.Transparent)
                            .clickable {
                                transactionType = "EXPENSE"
                                categorySelection = "FOOD"
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Expense", color = if (isExp) Color.White else Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isExp) Color(0xFF10B981) else Color.Transparent)
                            .clickable {
                                transactionType = "INCOME"
                                categorySelection = "SALARY"
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Income", color = if (!isExp) Color.White else Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Payee / Merchant") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                TransactionDateTimePicker(
                    selectedTimestamp = selectedTimestamp,
                    onTimestampChange = { selectedTimestamp = it }
                )

                PickerButton(
                    label = "Select Wallet",
                    title = accountSelection,
                    icon = walletIconFor(accountSelection, accounts.find { it.name == accountSelection }?.type),
                    tint = Color(0xFF00E5FF),
                    onClick = { showWalletPicker = true }
                )

                PickerButton(
                    label = "Select Category",
                    title = selectedCategory?.displayName ?: "Choose category",
                    icon = selectedCategory?.icon ?: Icons.Default.Category,
                    tint = selectedCategory?.color ?: Color(0xFF00E5FF),
                    onClick = { showCategoryPicker = true }
                )

                OutlinedTextField(
                    value = notesStr,
                    onValueChange = { notesStr = it },
                    label = { Text("Details note (Optional)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val dAmt = amountStr.toDoubleOrNull() ?: 0.0
                    val cleanTitle = if (title.isBlank()) "Merchant Log" else title
                    if (dAmt > 0.0) {
                        onConfirm(
                            cleanTitle,
                            dAmt,
                            categorySelection,
                            transactionType,
                            makeNoteWithAccount(notesStr, accountSelection),
                            selectedTimestamp
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
        },
        containerColor = Color(0xFF131A26)
    )

    if (showWalletPicker) {
        WalletSelectionDialog(
            walletOptions = selectablesWallets.map { walletName ->
                walletName to (accounts.find { it.name == walletName }?.type)
            },
            selectedWallet = accountSelection,
            onSelect = {
                accountSelection = it
                showWalletPicker = false
            },
            onDismiss = { showWalletPicker = false },
            addActionLabel = "+ Add New Account",
            onAddAction = {
                showWalletPicker = false
                showQuickAddAccount = true
            }
        )
    }

    if (showCategoryPicker) {
        CategorySelectionDialog(
            categories = filteredCats,
            selectedCategoryName = categorySelection,
            onSelect = {
                categorySelection = it
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false },
            addActionLabel = "+ Add New Category",
            onAddAction = {
                showCategoryPicker = false
                showQuickAddCategory = true
            }
        )
    }

    if (showQuickAddAccount) {
        QuickAddAccountDialog(
            onDismiss = { showQuickAddAccount = false },
            onAdd = { name, balance, type, lastFour, openingBalanceTimestamp ->
                val cleanName = name.trim()
                viewModel.addAccount(cleanName, balance, type, lastFour, openingBalanceTimestamp)
                accountSelection = cleanName
                showQuickAddAccount = false
            }
        )
    }

    if (showQuickAddCategory) {
        QuickAddCategoryDialog(
            defaultType = transactionType,
            onDismiss = { showQuickAddCategory = false },
            onAdd = { name, type, iconName, colorHex, initialBudget ->
                val cleanName = name.trim()
                viewModel.addCustomCategory(cleanName, iconName, colorHex, type)
                if (type == "EXPENSE" && initialBudget > 0.0) {
                    viewModel.saveBudget(cleanName, cleanName, initialBudget)
                }
                categorySelection = cleanName
                showQuickAddCategory = false
            }
        )
    }
}

@Composable
fun EditTransactionDialog(
    tx: TransactionEntry,
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: (TransactionEntry) -> Unit
) {
    var title by remember { mutableStateOf(tx.title) }
    var amountStr by remember { mutableStateOf(tx.amount.toInt().toString()) }
    var editType by remember { mutableStateOf(tx.type) }
    var categorySelection by remember {
        mutableStateOf(if (tx.category.equals("INCOME", ignoreCase = true)) "SALARY" else tx.category)
    }
    var accountSelection by remember { mutableStateOf(tx.getAccountName()) }
    var notesStr by remember { mutableStateOf(tx.note?.substringBefore(" [Acc:")?.trim() ?: "") }
    var selectedTimestamp by remember { mutableStateOf(tx.timestamp) }
    var showWalletPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showQuickAddAccount by remember { mutableStateOf(false) }
    var showQuickAddCategory by remember { mutableStateOf(false) }

    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    val allCategories = CategoryResolver.getAll(customCats)
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val selectablesWallets = if (accounts.isEmpty()) {
        listOf("Cash Wallet", "Bank Account", "Credit Card", "Digital Wallet")
    } else {
        accounts.map { it.name }
    }
    val filteredCats = when (editType) {
        "INCOME" -> allCategories.filter { it.type == "INCOME" }
        else -> allCategories.filter { it.type == "EXPENSE" }
    }
    val selectedCategory = filteredCats.firstOrNull { it.name == categorySelection }
        ?: allCategories.firstOrNull { it.name == categorySelection }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Transaction", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Text("TRANSACTION TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EXPENSE", "INCOME", "DUPLICATE", "BALANCE_UPDATE").forEach { t ->
                            val sel = editType == t
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (sel) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .clickable { editType = t }
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (t == "BALANCE_UPDATE") "BAL_UPDATE" else t,
                                    fontSize = 9.sp,
                                    color = if (sel) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Payee / Merchant") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                TransactionDateTimePicker(
                    selectedTimestamp = selectedTimestamp,
                    onTimestampChange = { selectedTimestamp = it }
                )

                PickerButton(
                    label = "Account",
                    title = accountSelection,
                    icon = walletIconFor(accountSelection, accounts.find { it.name == accountSelection }?.type),
                    tint = Color(0xFF00E5FF),
                    onClick = { showWalletPicker = true }
                )

                PickerButton(
                    label = "Category",
                    title = selectedCategory?.displayName ?: "Choose category",
                    icon = selectedCategory?.icon ?: Icons.Default.Category,
                    tint = selectedCategory?.color ?: Color(0xFF00E5FF),
                    onClick = { showCategoryPicker = true }
                )

                OutlinedTextField(
                    value = notesStr,
                    onValueChange = { notesStr = it },
                    label = { Text("Details note (Optional)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFF43F5E))
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = {
                        val dAmt = amountStr.toDoubleOrNull() ?: 0.0
                        if (dAmt > 0.0 && title.isNotBlank()) {
                            onConfirm(
                                tx.copy(
                                    title = title,
                                    amount = dAmt,
                                    category = categorySelection,
                                    type = editType,
                                    timestamp = selectedTimestamp,
                                    note = makeNoteWithAccount(notesStr, accountSelection)
                                )
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
        },
        containerColor = Color(0xFF131A26)
    )

    if (showWalletPicker) {
        WalletSelectionDialog(
            walletOptions = selectablesWallets.map { walletName -> walletName to (accounts.find { it.name == walletName }?.type) },
            selectedWallet = accountSelection,
            onSelect = {
                accountSelection = it
                showWalletPicker = false
            },
            onDismiss = { showWalletPicker = false },
            addActionLabel = "+ Add New Account",
            onAddAction = {
                showWalletPicker = false
                showQuickAddAccount = true
            }
        )
    }

    if (showCategoryPicker) {
        CategorySelectionDialog(
            categories = filteredCats,
            selectedCategoryName = categorySelection,
            onSelect = {
                categorySelection = it
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false },
            addActionLabel = "+ Add New Category",
            onAddAction = {
                showCategoryPicker = false
                showQuickAddCategory = true
            }
        )
    }

    if (showQuickAddAccount) {
        QuickAddAccountDialog(
            onDismiss = { showQuickAddAccount = false },
            onAdd = { name, balance, type, lastFour, openingBalanceTimestamp ->
                val cleanName = name.trim()
                viewModel.addAccount(cleanName, balance, type, lastFour, openingBalanceTimestamp)
                accountSelection = cleanName
                showQuickAddAccount = false
            }
        )
    }

    if (showQuickAddCategory) {
        QuickAddCategoryDialog(
            defaultType = tx.type,
            onDismiss = { showQuickAddCategory = false },
            onAdd = { name, type, iconName, colorHex, initialBudget ->
                val cleanName = name.trim()
                viewModel.addCustomCategory(cleanName, iconName, colorHex, type)
                if (type == "EXPENSE" && initialBudget > 0.0) {
                    viewModel.saveBudget(cleanName, cleanName, initialBudget)
                }
                categorySelection = cleanName
                showQuickAddCategory = false
            }
        )
    }
}

@Composable
fun TransactionDateTimePicker(
    selectedTimestamp: Long,
    onTimestampChange: (Long) -> Unit,
    label: String = "Transaction Date & Time"
) {
    val context = LocalContext.current
    val dateLabel = remember(selectedTimestamp) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedTimestamp))
    }
    val timeLabel = remember(selectedTimestamp) {
        SystemDateFormat.getTimeFormat(context).format(Date(selectedTimestamp))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                            val updated = Calendar.getInstance().apply {
                                timeInMillis = selectedTimestamp
                                set(Calendar.YEAR, year)
                                set(Calendar.MONTH, month)
                                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            }
                            onTimestampChange(updated.timeInMillis)
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(dateLabel)
            }

            OutlinedButton(
                onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
                    TimePickerDialog(
                        context,
                        { _, hourOfDay, minute ->
                            val updated = Calendar.getInstance().apply {
                                timeInMillis = selectedTimestamp
                                set(Calendar.HOUR_OF_DAY, hourOfDay)
                                set(Calendar.MINUTE, minute)
                            }
                            onTimestampChange(updated.timeInMillis)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    ).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(timeLabel)
            }
        }
    }
}

@Composable
private fun PickerButton(
    label: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.ExpandMore, contentDescription = label, tint = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun WalletSelectionDialog(
    walletOptions: List<Pair<String, String?>>,
    selectedWallet: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    addActionLabel: String? = null,
    onAddAction: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Wallet", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                walletOptions.forEach { (walletName, walletType) ->
                    val active = selectedWallet == walletName
                    Surface(
                        color = if (active) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (active) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(walletName) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = walletIconFor(walletName, walletType),
                                contentDescription = walletName,
                                tint = if (active) Color(0xFF00E5FF) else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(walletName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                if (addActionLabel != null && onAddAction != null) {
                    OutlinedButton(
                        onClick = onAddAction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                    ) {
                        Text(addActionLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        },
        containerColor = Color(0xFF131A26)
    )
}

@Composable
private fun CategorySelectionDialog(
    categories: List<DisplayCategory>,
    selectedCategoryName: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    addActionLabel: String? = null,
    onAddAction: (() -> Unit)? = null
) {
    val sortedCategories = remember(categories) { categories.sortedBy { it.displayName } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sortedCategories.forEach { category ->
                    val active = selectedCategoryName == category.name
                    Surface(
                        color = if (active) category.color.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (active) category.color else Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(category.name) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = category.displayName,
                                tint = category.color,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(category.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                if (addActionLabel != null && onAddAction != null) {
                    OutlinedButton(
                        onClick = onAddAction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                    ) {
                        Text(addActionLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        },
        containerColor = Color(0xFF131A26)
    )
}

private fun walletIconFor(name: String, type: String?): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        type == "CASH" || name.contains("cash", ignoreCase = true) -> Icons.Default.Money
        type == "BANK" || name.contains("bank", ignoreCase = true) -> Icons.Default.AccountBalance
        type == "CREDIT_CARD" || name.contains("card", ignoreCase = true) -> Icons.Default.CreditCard
        type == "WALLET" || name.contains("wallet", ignoreCase = true) -> Icons.Default.AccountBalanceWallet
        else -> Icons.Default.AccountBalanceWallet
    }
}

@Composable
private fun QuickAddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Double, String, String?, Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("BANK") }
    var lastFour by remember { mutableStateOf("") }
    var openingBalanceTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    val types = listOf("CASH", "BANK", "CREDIT_CARD", "WALLET")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Account", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name (e.g. SBI Bank)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Initial Ledger Balance (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                TransactionDateTimePicker(
                    selectedTimestamp = openingBalanceTimestamp,
                    onTimestampChange = { openingBalanceTimestamp = it },
                    label = "Opening Balance Date & Time"
                )
                OutlinedTextField(
                    value = lastFour,
                    onValueChange = { lastFour = it },
                    label = { Text("Last 4 digits (optional ID)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text("ACCOUNT CATEGORY / TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        types.forEach { option ->
                            val active = option == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (active) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (active) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                    .clickable { type = option }
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(option.replace("_", " "), color = if (active) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name, amount.toDoubleOrNull() ?: 0.0, type, lastFour.ifBlank { null }, openingBalanceTimestamp)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
            ) {
                Text("Add Account", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF131A26)
    )
}

@Composable
private fun QuickAddCategoryDialog(
    defaultType: String,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(defaultType) }
    var selectedIconName by remember { mutableStateOf(if (defaultType == "INCOME") "salary" else "others") }
    var selectedColorHex by remember { mutableStateOf(if (defaultType == "INCOME") "#4CAF50" else "#607D8B") }
    var selectedColor by remember { mutableStateOf(if (defaultType == "INCOME") Color(0xFF4CAF50) else Color(0xFF607D8B)) }
    var initialBudgetAmt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Category", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("EXPENSE", "INCOME").forEach { option ->
                        val active = option == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                                .border(1.dp, if (active) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .clickable {
                                    type = option
                                    if (option == "INCOME") {
                                        selectedIconName = "salary"
                                        selectedColorHex = "#4CAF50"
                                        selectedColor = Color(0xFF4CAF50)
                                    }
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(option, color = if (active) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }

                if (type == "EXPENSE") {
                    OutlinedTextField(
                        value = initialBudgetAmt,
                        onValueChange = { initialBudgetAmt = it },
                        label = { Text("Monthly Budget Limit (₹, Optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            focusedLabelColor = Color(0xFF00E5FF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Select Icon", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    items(suitableIconsList) { (iconName, iconVec) ->
                        val isSelected = selectedIconName == iconName
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) selectedColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f),
                            border = BorderStroke(2.dp, if (isSelected) selectedColor else Color.Transparent),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { selectedIconName = iconName }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = iconVec,
                                    contentDescription = iconName,
                                    tint = if (isSelected) selectedColor else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Text("Select Color", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    items(categoryColorsList) { (hexStr, colorObj) ->
                        val isSelected = selectedColorHex == hexStr
                        Surface(
                            shape = CircleShape,
                            color = colorObj,
                            border = BorderStroke(2.dp, if (isSelected) Color.White else Color.Transparent),
                            modifier = Modifier
                                .size(36.dp)
                                .clickable {
                                    selectedColorHex = hexStr
                                    selectedColor = colorObj
                                }
                        ) {
                            if (isSelected) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name, type, selectedIconName, selectedColorHex, initialBudgetAmt.toDoubleOrNull() ?: 0.0)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
            ) {
                Text("Add Category", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF131A26)
    )
}

@Composable
fun ExportCsvDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val excelExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.ms-excel")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(viewModel.getExcelData())
            }
            Toast.makeText(context, "Excel exported successfully!", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    val pdfExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(viewModel.getPdfData())
            }
            Toast.makeText(context, "PDF exported successfully!", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Financial Records", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Export styled month-wise financial reports as Excel or PDF for external analysis.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Includes month-wise transactions, category color breakdown, carry over balance, monthly total, and grand total.",
                        fontSize = 10.sp,
                        color = Color(0xFF00E5FF),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val fileName = "mymoney-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.xlsx"
                        excelExporter.launch(fileName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))
                ) {
                    Text("Export Excel", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val fileName = "mymoney-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.pdf"
                        pdfExporter.launch(fileName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White)
                ) {
                    Text("Export PDF", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.White) }
        },
        containerColor = Color(0xFF131A26)
    )
}

@Composable
fun BackupDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    val backupString by viewModel.backupString.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secure Backup", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Creates an AES-encrypted backup of your transactions and budgets. Copy and store the backup string safely.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup Passphrase (min 4 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        focusedLabelColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (backupString != null) {
                    Surface(
                        color = Color(0xFF10B981).copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Backup generated! Copy and store securely:",
                                fontSize = 11.sp,
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = backupString!!.take(80) + if (backupString!!.length > 80) "\u2026" else "",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.55f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        android.content.ClipData.newPlainText("mymoney_backup", backupString)
                                    )
                                    Toast.makeText(context, "Backup string copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF10B981),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Full Backup String", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.createSecureBackup(password) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color(0xFF0B0F19)
                ),
                enabled = password.length >= 4
            ) {
                Text("Generate Backup", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
        },
        containerColor = Color(0xFF131A26)
    )
}

@Composable
fun RestoreBackupDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    var backupStr by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore Backup", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = Color(0xFFF43F5E).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFFF43F5E).copy(alpha = 0.3f))
                ) {
                    Text(
                        "\u26a0 Restoring will replace ALL existing transactions and budgets. This cannot be undone.",
                        fontSize = 11.sp,
                        color = Color(0xFFF43F5E),
                        modifier = Modifier.padding(10.dp)
                    )
                }
                OutlinedTextField(
                    value = backupStr,
                    onValueChange = { backupStr = it },
                    label = { Text("Paste Backup String") },
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFF43F5E),
                        focusedLabelColor = Color(0xFFF43F5E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFF43F5E),
                        focusedLabelColor = Color(0xFFF43F5E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.restoreFromSecureBackup(backupStr.trim(), password)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF43F5E),
                    contentColor = Color.White
                ),
                enabled = backupStr.isNotBlank() && password.isNotEmpty()
            ) {
                Text("Restore Data", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
        },
        containerColor = Color(0xFF131A26)
    )
}

fun getPeriodRange(mode: DisplayMode, anchorTime: Long): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply { timeInMillis = anchorTime }
    return when (mode) {
        DisplayMode.DAILY -> {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            Pair(start, end)
        }
        DisplayMode.WEEKLY -> {
            cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_WEEK, 6)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            Pair(start, end)
        }
        DisplayMode.MONTHLY -> {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            Pair(start, end)
        }
        DisplayMode.THREE_MONTHS -> {
            cal.add(Calendar.MONTH, -2)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            
            val calEnd = Calendar.getInstance().apply { timeInMillis = anchorTime }
            calEnd.set(Calendar.DAY_OF_MONTH, calEnd.getActualMaximum(Calendar.DAY_OF_MONTH))
            calEnd.set(Calendar.HOUR_OF_DAY, 23)
            calEnd.set(Calendar.MINUTE, 59)
            calEnd.set(Calendar.SECOND, 59)
            calEnd.set(Calendar.MILLISECOND, 999)
            val end = calEnd.timeInMillis
            Pair(start, end)
        }
        DisplayMode.SIX_MONTHS -> {
            cal.add(Calendar.MONTH, -5)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            
            val calEnd = Calendar.getInstance().apply { timeInMillis = anchorTime }
            calEnd.set(Calendar.DAY_OF_MONTH, calEnd.getActualMaximum(Calendar.DAY_OF_MONTH))
            calEnd.set(Calendar.HOUR_OF_DAY, 23)
            calEnd.set(Calendar.MINUTE, 59)
            calEnd.set(Calendar.SECOND, 59)
            calEnd.set(Calendar.MILLISECOND, 999)
            val end = calEnd.timeInMillis
            Pair(start, end)
        }
        DisplayMode.ONE_YEAR -> {
            cal.set(Calendar.MONTH, Calendar.JANUARY)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            
            cal.set(Calendar.MONTH, Calendar.DECEMBER)
            cal.set(Calendar.DAY_OF_MONTH, 31)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            Pair(start, end)
        }
    }
}

@Composable
fun AccountCenterSettingsDialog(
    viewModel: FinanceViewModel,
    accounts: List<Account>,
    hiddenAccountIds: Set<String>,
    showCreditCardDetails: Boolean,
    smsBlocklistPatterns: Set<String>,
    onDismiss: () -> Unit
) {
    var patternInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account Center Settings", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // CC Details toggle
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Credit Card Details", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Display available limit & due amount on cards", color = Color.White.copy(0.5f), fontSize = 11.sp)
                        }
                        Switch(
                            checked = showCreditCardDetails,
                            onCheckedChange = { viewModel.setShowCreditCardDetails(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.45f),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
                // Wallet visibility section header
                item {
                    Text(
                        "WALLET VISIBILITY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(0.5f),
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(accounts) { acc ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(acc.name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = !hiddenAccountIds.contains(acc.id),
                            onCheckedChange = { visible -> viewModel.setAccountHidden(acc.id, !visible) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.45f),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
                // SMS Blocklist section
                item {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            "SMS IMPORT BLOCKLIST",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(0.5f),
                            letterSpacing = 1.sp
                        )
                        Text(
                            "Block SMS imports by wallet name or sender. Wildcards: HDFC*, *1234, *paytm*",
                            color = Color.White.copy(0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = patternInput,
                                onValueChange = { patternInput = it },
                                placeholder = { Text("e.g. HDFC*, *paytm*", color = Color.White.copy(0.4f), fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = Color.White.copy(0.3f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                            )
                            IconButton(onClick = {
                                if (patternInput.isNotBlank()) {
                                    viewModel.addSmsBlocklistPattern(patternInput.trim())
                                    patternInput = ""
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add pattern", tint = Color(0xFF00E5FF))
                            }
                        }
                    }
                }
                items(smsBlocklistPatterns.toList()) { pattern ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(pattern, color = Color(0xFFF43F5E), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeSmsBlocklistPattern(pattern) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color(0xFFF43F5E))
                        }
                    }
                }
                if (smsBlocklistPatterns.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.deleteTransactionsMatchingBlocklist() },
                            border = BorderStroke(1.dp, Color(0xFFF43F5E)),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF43F5E))
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFF43F5E))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Matching Transactions", color = Color(0xFFF43F5E), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Color(0xFF00E5FF)) } },
        containerColor = Color(0xFF131A26),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

fun getAnalyticsRange(monthYear: String, filter: String, anchorTimeMs: Long = -1L): Pair<Long, Long> {
    val baseCalendar = Calendar.getInstance().apply {
        try {
            time = SimpleDateFormat("yyyy-MM", Locale.getDefault()).parse(monthYear) ?: Date()
        } catch (_: Exception) {
            time = Date()
        }
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val periodEnd = (baseCalendar.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }

    if (filter == "WEEKLY") {
        val endCal = if (anchorTimeMs > 0) {
            Calendar.getInstance().apply {
                timeInMillis = anchorTimeMs
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }
        } else periodEnd
        val start = (endCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, -6)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return start to endCal.timeInMillis
    }

    val start = (baseCalendar.clone() as Calendar).apply {
        when (filter) {
            "3M" -> add(Calendar.MONTH, -2)
            "6M" -> add(Calendar.MONTH, -5)
            "1Y" -> add(Calendar.MONTH, -11)
        }
        if (filter == "3M" || filter == "6M" || filter == "1Y") {
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }.timeInMillis

    val end = periodEnd.timeInMillis

    return start to end
}

fun shiftMonthYear(monthYear: String, amount: Int): String {
    val calendar = Calendar.getInstance().apply {
        try {
            time = SimpleDateFormat("yyyy-MM", Locale.getDefault()).parse(monthYear) ?: Date()
        } catch (_: Exception) {
            time = Date()
        }
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, amount)
    }
    return SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.time)
}

fun budgetProgressColor(percent: Double): Color {
    return when {
        percent > 100.0 -> Color(0xFFF43F5E)
        percent > 90.0 -> Color(0xFFFB923C)
        percent >= 60.0 -> Color(0xFFFACC15)
        percent >= 30.0 -> Color(0xFF4ADE80)
        else -> Color(0xFF10B981)
    }
}

fun resolveBudgetCategoryName(category: DisplayCategory, editedName: String): String {
    val trimmedName = editedName.trim()
    if (category.isCustom) {
        return trimmedName
    }
    return if (trimmedName.equals(category.displayName, ignoreCase = true)) category.name else trimmedName
}

fun shiftAnalyticsPeriod(viewModel: FinanceViewModel, monthYear: String, timeFilter: String, amount: Int, anchorTimeMs: Long = -1L) {
    val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    val cal = if (timeFilter == "WEEKLY" && anchorTimeMs > 0) {
        Calendar.getInstance().apply { timeInMillis = anchorTimeMs }
    } else {
        Calendar.getInstance().apply {
            try { time = sdf.parse(monthYear) ?: Date() } catch (_: Exception) { time = Date() }
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    when (timeFilter) {
        "WEEKLY" -> cal.add(Calendar.DAY_OF_YEAR, amount * 7)
        "MONTHLY" -> cal.add(Calendar.MONTH, amount)
        "3M" -> cal.add(Calendar.MONTH, amount * 3)
        "6M" -> cal.add(Calendar.MONTH, amount * 6)
        "1Y" -> cal.add(Calendar.YEAR, amount)
        else -> cal.add(Calendar.MONTH, amount)
    }
    viewModel.setAnchorDate(cal.timeInMillis)
}

fun formatAnalyticsPeriodLabel(monthYear: String, timeFilter: String, anchorTimeMs: Long = -1L): String {
    val (startMs, endMs) = getAnalyticsRange(monthYear, timeFilter, anchorTimeMs)
    val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
    val endCal = Calendar.getInstance().apply { timeInMillis = endMs }
    return when (timeFilter) {
        "WEEKLY" -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            val sdfY = SimpleDateFormat("yyyy", Locale.getDefault())
            "${sdf.format(startCal.time)} – ${sdf.format(endCal.time)}, ${sdfY.format(endCal.time)}"
        }
        "MONTHLY" -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(endCal.time)
        "3M", "6M" -> {
            val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
            "${sdf.format(startCal.time)} – ${sdf.format(endCal.time)}"
        }
        "1Y" -> SimpleDateFormat("yyyy", Locale.getDefault()).format(endCal.time)
        else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(endCal.time)
    }
}

fun shiftPeriod(viewModel: FinanceViewModel, mode: DisplayMode, anchorTime: Long, amount: Int) {
    val cal = Calendar.getInstance().apply { timeInMillis = anchorTime }
    when (mode) {
        DisplayMode.DAILY -> cal.add(Calendar.DAY_OF_YEAR, amount)
        DisplayMode.WEEKLY -> cal.add(Calendar.WEEK_OF_YEAR, amount)
        DisplayMode.MONTHLY -> cal.add(Calendar.MONTH, amount)
        DisplayMode.THREE_MONTHS -> cal.add(Calendar.MONTH, amount * 3)
        DisplayMode.SIX_MONTHS -> cal.add(Calendar.MONTH, amount * 6)
        DisplayMode.ONE_YEAR -> cal.add(Calendar.YEAR, amount)
    }
    viewModel.setAnchorDate(cal.timeInMillis)
}

fun formatPeriodLabel(mode: DisplayMode, anchorTime: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = anchorTime }
    return when (mode) {
        DisplayMode.DAILY -> {
            val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            sdf.format(cal.time)
        }
        DisplayMode.WEEKLY -> {
            cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            val start = cal.time
            cal.add(Calendar.DAY_OF_WEEK, 6)
            val end = cal.time
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            val sdfYear = SimpleDateFormat("yyyy", Locale.getDefault())
            "${sdf.format(start)} - ${sdf.format(end)}, ${sdfYear.format(start)}"
        }
        DisplayMode.MONTHLY -> {
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            sdf.format(cal.time)
        }
        DisplayMode.THREE_MONTHS -> {
            val endMonth = cal.time
            cal.add(Calendar.MONTH, -2)
            val startMonth = cal.time
            val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
            "${sdf.format(startMonth)} - ${sdf.format(endMonth)}"
        }
        DisplayMode.SIX_MONTHS -> {
            val endMonth = cal.time
            cal.add(Calendar.MONTH, -5)
            val startMonth = cal.time
            val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
            "${sdf.format(startMonth)} - ${sdf.format(endMonth)}"
        }
        DisplayMode.ONE_YEAR -> {
            val sdf = SimpleDateFormat("yyyy", Locale.getDefault())
            sdf.format(cal.time)
        }
    }
}
