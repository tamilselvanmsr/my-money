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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

// Enum representing the five core tabs mimicking MyMoney by Ananta Raha
enum class AppTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DASHBOARD("Records", Icons.Default.ReceiptLong),
    ANALYTICS("Analysis", Icons.Default.DonutLarge),
    BUDGETS("Budgets", Icons.Default.Calculate),
    ACCOUNT("Accounts", Icons.Default.AccountBalanceWallet),
    AUTO_SCAN("Auto-Scan Hub", Icons.Default.Security)
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
        lower.contains("savings") -> "Savings Goal"
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
    if (accountsList.isEmpty()) {
        balances["Cash Wallet"] = 0.0
        balances["Bank Account"] = 0.0
        balances["Credit Card"] = 0.0
        balances["Savings Goal"] = 0.0
    } else {
        if (consolidate) {
            balances["Cash Wallet"] = 0.0
            balances["Bank Account"] = 0.0
            balances["Credit Card"] = 0.0
            balances["Savings Goal"] = 0.0
            for (acc in accountsList) {
                val genericName = when (acc.type) {
                    "CASH" -> "Cash Wallet"
                    "BANK" -> "Bank Account"
                    "CREDIT_CARD" -> "Credit Card"
                    "SAVINGS" -> "Savings Goal"
                    else -> "Bank Account"
                }
                balances[genericName] = (balances[genericName] ?: 0.0) + (if (carryOverPreviousAmount) acc.balance else 0.0)
            }
        } else {
            for (acc in accountsList) {
                balances[acc.name] = if (carryOverPreviousAmount) acc.balance else 0.0
            }
        }
    }
    for (tx in transactions) {
        val account = tx.getAccountName(consolidate)
        val change = if (tx.type == "INCOME") tx.amount else -tx.amount
        balances[account] = (balances[account] ?: 0.0) + change
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
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
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
                    IconButton(
                        onClick = { showCsvDialog = true },
                        modifier = Modifier.testTag("export_csv_top_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share CSV",
                            tint = Color(0xFF00E5FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
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
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Centered period selection navigation
                Surface(
                    color = Color(0xFF131A26),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF1D293B)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                shiftPeriod(viewModel, activeMode, anchorTime, -1)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "Previous Period",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatPeriodLabel(activeMode, anchorTime),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            modifier = Modifier.widthIn(min = 120.dp),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                shiftPeriod(viewModel, activeMode, anchorTime, 1)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Next Period",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                
                // Right-aligned quick actions and filter menu
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            onClick = {
                                isSearchExpanded = !isSearchExpanded
                                if (!isSearchExpanded) {
                                    searchQuery = ""
                                }
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
                            Spacer(modifier = Modifier.width(8.dp))
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

                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { showFilterMenu = !showFilterMenu },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("three_bar_filter_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Options and Filtering",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(24.dp)
                            )
                        }
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
                        text = if (selectedWallet == "All") "NET WALLET ASSETS" else "${selectedWallet.uppercase()} VALUE",
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
                        listOf("All", "Cash Wallet", "Bank Account", "Credit Card", "Savings Goal")
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
                                        name == "Savings Goal" -> Icons.Default.Savings
                                        else -> {
                                            val acType = accounts.find { it.name == name }?.type ?: ""
                                            when(acType) {
                                                "CASH" -> Icons.Default.Money
                                                "BANK" -> Icons.Default.AccountBalance
                                                "CREDIT_CARD" -> Icons.Default.CreditCard
                                                "SAVINGS" -> Icons.Default.Savings
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
                        
                        val dateNet = txList.sumOf { if (it.type == "INCOME") it.amount else -it.amount }
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
                    
                    Surface(
                        color = Color(0xFF131A26),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
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
                                    // Custom active payment wallet badge
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
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = resolvedCat.color.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = resolvedCat.displayName,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = resolvedCat.color,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = (if (tx.type == "INCOME") "+" else "-") + decFormat.format(tx.amount),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = if (tx.type == "INCOME") Color(0xFF10B981) else Color(0xFFF43F5E)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tx.timestamp)),
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
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())

    var timeFilter by remember { mutableStateOf("MONTHLY") }
    var selectedMode by remember { mutableStateOf(AnalyticsMode.EXPENSE_OVERVIEW) }
    var showModeMenu by remember { mutableStateOf(false) }
    val (analysisStart, analysisEnd) = getAnalyticsRange(rawMonthYear, timeFilter)

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
    val flowTransactions = if (selectedMode == AnalyticsMode.INCOME_FLOW) incomes else expenses
    val flowPoints = remember(flowTransactions, analysisStart, analysisEnd) {
        buildDailyFlowPoints(flowTransactions, analysisStart, analysisEnd)
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
                Text(
                    text = "ANALYSIS HUB",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )

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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF131A26), RoundedCornerShape(20.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val filters = listOf(
                        "WEEKLY" to "Weekly",
                        "MONTHLY" to "Monthly",
                        "3M" to "3 Months",
                        "6M" to "6 Months",
                        "1Y" to "1 Year"
                    )
                    filters.forEach { (key, display) ->
                        val active = timeFilter == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (active) Color(0xFF00E5FF) else Color.Transparent)
                                .clickable { timeFilter = key }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = display,
                                color = if (active) Color(0xFF0B0F19) else Color.White.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                maxLines = 1
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
    val shortLabel: String,
    val fullLabel: String,
    val total: Double
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
                                }

                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    val highlightItem = categoryTotals.getOrNull(activeSectorIndex)
                                    Text(
                                        text = highlightItem?.category?.displayName ?: totalLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White.copy(alpha = 0.6f),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(130.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = decFormat.format(highlightItem?.total ?: totalAmount),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = highlightItem?.category?.color ?: Color(0xFF00E5FF)
                                    )
                                    if (highlightItem != null) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${String.format(Locale.getDefault(), "%.1f", highlightItem.percentage * 100)}%",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                        val legendContent: @Composable () -> Unit = {
                            Column(
                                modifier = if (isCompact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 150.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                categoryTotals.forEachIndexed { idx, stats ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                activeSectorIndex = if (activeSectorIndex == idx) -1 else idx
                                            }
                                            .background(if (activeSectorIndex == idx) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(stats.category.color, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            stats.category.displayName,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        if (isCompact) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                chartContent()
                                legendContent()
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = stats.category.color.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = stats.category.icon,
                                        contentDescription = stats.category.displayName,
                                        tint = stats.category.color,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stats.category.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text(
                                    text = "${String.format(Locale.getDefault(), "%.1f", stats.percentage * 100)}% $percentSuffix",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            }
                            Text(decFormat.format(stats.total), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        }

                        Spacer(modifier = Modifier.height(10.dp))
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
                }
            }
        }
    }
}

@Composable
private fun AnalyticsFlowSection(
    title: String,
    points: List<AnalyticsFlowPoint>,
    accent: Color,
    emptyMessage: String
) {
    val nonZeroPoints = remember(points) { points.filter { it.total > 0.0 } }
    val decFormat = remember { DecimalFormat("₹#,##0.00") }
    val total = points.sumOf { it.total }
    val average = if (nonZeroPoints.isNotEmpty()) total / nonZeroPoints.size else 0.0

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = Color(0xFF131A26),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        val leftPad = 28.dp.toPx()
                        val rightPad = 12.dp.toPx()
                        val topPad = 16.dp.toPx()
                        val bottomPad = 24.dp.toPx()
                        val chartWidth = size.width - leftPad - rightPad
                        val chartHeight = size.height - topPad - bottomPad
                        val maxValue = (nonZeroPoints.maxOfOrNull { it.total } ?: 0.0).coerceAtLeast(1.0)

                        repeat(4) { index ->
                            val y = topPad + chartHeight * index / 3f
                            drawLine(
                                color = Color.White.copy(alpha = 0.08f),
                                start = Offset(leftPad, y),
                                end = Offset(size.width - rightPad, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        var previousOffset: Offset? = null
                        nonZeroPoints.forEachIndexed { index, point ->
                            val x = if (nonZeroPoints.size == 1) {
                                leftPad + chartWidth / 2f
                            } else {
                                leftPad + (chartWidth * index / (nonZeroPoints.size - 1).toFloat())
                            }
                            val y = topPad + chartHeight - ((point.total / maxValue) * chartHeight).toFloat()
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

                            drawCircle(color = accent, radius = 4.dp.toPx(), center = currentOffset)
                            previousOffset = currentOffset
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

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items(points) { point ->
                Surface(
                    color = if (point.total > 0.0) accent.copy(alpha = 0.12f) else Color(0xFF131A26),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (point.total > 0.0) accent.copy(alpha = 0.45f) else Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier.width(112.dp).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(point.shortLabel, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Text(point.fullLabel, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
                        Text(
                            decFormat.format(point.total),
                            fontWeight = FontWeight.Bold,
                            color = if (point.total > 0.0) accent else Color.White.copy(alpha = 0.45f),
                            fontSize = 12.sp
                        )
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

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = Color(0xFF131A26),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFF1E293B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("ACCOUNT ACTIVITY CHART", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.5f))

                if (accountStats.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text("No account activity available for this period.", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                } else {
                    accountStats.forEach { stats ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stats.accountName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(decFormat.format(stats.net), color = if (stats.net >= 0) Color(0xFF10B981) else Color(0xFFF43F5E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("In", color = Color(0xFF10B981), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(
                                    progress = (stats.income / maxActivity).toFloat(),
                                    color = Color(0xFF10B981),
                                    trackColor = Color.White.copy(alpha = 0.06f),
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Text(decFormat.format(stats.income), color = Color.White, fontSize = 10.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Out", color = Color(0xFFF43F5E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(
                                    progress = (stats.expense / maxActivity).toFloat(),
                                    color = Color(0xFFF43F5E),
                                    trackColor = Color.White.copy(alpha = 0.06f),
                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                )
                                Text(decFormat.format(stats.expense), color = Color.White, fontSize = 10.sp)
                            }
                        }
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
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stats.accountName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            AccountStatChip(label = "Income", amount = stats.income, tone = Color(0xFF10B981), modifier = Modifier.weight(1f))
                            AccountStatChip(label = "Expense", amount = stats.expense, tone = Color(0xFFF43F5E), modifier = Modifier.weight(1f))
                            AccountStatChip(label = "Net", amount = stats.net, tone = if (stats.net >= 0) Color(0xFF00E5FF) else Color(0xFFFF7043), modifier = Modifier.weight(1f))
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
    }.mapValues { (_, dayTransactions) ->
        dayTransactions.sumOf { it.amount }
    }

    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val shortFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
    val fullFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
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
        points += AnalyticsFlowPoint(
            shortLabel = shortFormat.format(Date(time)),
            fullLabel = fullFormat.format(Date(time)),
            total = totalsByDay[key] ?: 0.0
        )
        calendar.add(Calendar.DAY_OF_MONTH, 1)
    }
    return points
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
    var showSetBudgetDialog by remember { mutableStateOf<DisplayCategory?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var activeCategoryTypeTab by remember { mutableStateOf("EXPENSE") }
    
    val sdfMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    val formatDisplay = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val currentMonthDate = try { sdfMonth.parse(rawMonthYear) ?: Date() } catch (e: Exception) { Date() }

    // Filter transaction for the active month
    val monthExpenses = txs.filter {
        val txMonth = sdfMonth.format(Date(it.timestamp))
        txMonth == rawMonthYear && it.type == "EXPENSE"
    }

    val standardCategoriesList = CategoryResolver.getAll(customCats).filter {
        it.type == activeCategoryTypeTab
    }
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
                Text(
                    text = if (activeCategoryTypeTab == "EXPENSE") "Expense Budgets" else "Income Categories",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )

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
        val globalBudgetLimit = activeBudgets.sumOf { it.amountLimit }
        val globalBudgetSpend = monthExpenses.sumOf { it.amount }
        
        if (activeCategoryTypeTab == "EXPENSE") {
            item {
                Surface(
                    color = Color(0xFF131A26),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "TOTAL BUDGET PROGRESS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Column {
                                Text(
                                    text = decFormat.format(globalBudgetSpend),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 24.sp,
                                    color = if (globalBudgetSpend > globalBudgetLimit && globalBudgetLimit > 0) Color(0xFFF43F5E) else Color.White
                                )
                                Text(
                                    "Spent of ${decFormat.format(globalBudgetLimit)} set limit",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            }
                            
                            if (globalBudgetLimit > 0) {
                                val percent = globalBudgetSpend / globalBudgetLimit * 100
                                val progressColor = budgetProgressColor(percent)
                                Text(
                                    text = "${String.format(Locale.getDefault(), "%.1f", percent)}%",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = progressColor
                                )
                            }
                        }
                        
                        if (globalBudgetLimit > 0) {
                            Spacer(modifier = Modifier.height(14.dp))
                            val actualPercent = globalBudgetSpend / globalBudgetLimit * 100
                            val progressFraction = (globalBudgetSpend / globalBudgetLimit).toFloat().coerceIn(0f, 1f)
                            val barColor = budgetProgressColor(actualPercent)
                            
                            LinearProgressIndicator(
                                progress = progressFraction,
                                color = barColor,
                                trackColor = Color.White.copy(alpha = 0.05f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
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

        items(standardCategoriesList) { cat ->
            val budgetObj = activeBudgets.firstOrNull { it.category.equals(cat.name, ignoreCase = true) }
            val catExpense = monthExpenses.filter { it.category.equals(cat.name, ignoreCase = true) }.sumOf { it.amount }
            val catIncome = txs.filter {
                val txMonth = sdfMonth.format(Date(it.timestamp))
                txMonth == rawMonthYear && it.type == "INCOME" && it.category.equals(cat.name, ignoreCase = true)
            }.sumOf { it.amount }

            Surface(
                color = Color(0xFF131A26),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSetBudgetDialog = cat }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = cat.color.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = cat.icon,
                                contentDescription = cat.name,
                                tint = cat.color,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cat.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            OutlinedButton(
                                onClick = { showSetBudgetDialog = cat },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(if (budgetObj == null) "Set Budget" else "Edit Budget", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))

                        if (activeCategoryTypeTab == "EXPENSE") {
                            if (budgetObj != null) {
                                val limit = budgetObj.amountLimit
                                val ratio = if (limit > 0) (catExpense / limit) else 0.0
                                val percent = ratio * 100
                                val progressColor = budgetProgressColor(percent)
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Spent: ${decFormat.format(catExpense)} of ${decFormat.format(limit)}",
                                        fontSize = 11.sp,
                                        color = if (percent > 100) Color(0xFFF43F5E) else Color.White.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        text = "${String.format(Locale.getDefault(), "%.0f", percent)}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = progressColor
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = ratio.toFloat().coerceIn(0f, 1f),
                                    color = progressColor,
                                    trackColor = Color.White.copy(alpha = 0.05f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        } else {
                            Text(
                                text = "Received: ${decFormat.format(catIncome)} this month",
                                fontSize = 11.sp,
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet or Alert Dialog for writing categories budgets
    showSetBudgetDialog?.let { cat ->
        var budgetValStr by remember { mutableStateOf("") }
        var editCatName by remember { mutableStateOf(cat.displayName) }
        var selectedIconName by remember { mutableStateOf("others") }
        var selectedColorHex by remember { mutableStateOf("#607D8B") }
        var selectedColor by remember { mutableStateOf(cat.color) }
        
        val checkCurrent = activeBudgets.firstOrNull { it.category.equals(cat.name, ignoreCase = true) }
        
        LaunchedEffect(cat) {
            checkCurrent?.let { budgetValStr = it.amountLimit.toInt().toString() }
            editCatName = cat.displayName
            
            val matchedIcon = suitableIconsList.firstOrNull { it.second == cat.icon }?.first
            if (matchedIcon != null) {
                selectedIconName = matchedIcon
            }
            
            val matchedColorHex = categoryColorsList.firstOrNull {
                it.second.value == cat.color.value
            }?.first
            if (matchedColorHex != null) {
                selectedColorHex = matchedColorHex
            } else {
                selectedColorHex = String.format("#%06X", (0xFFFFFF and cat.color.value.toInt()))
            }
            selectedColor = cat.color
        }

        AlertDialog(
            onDismissRequest = { showSetBudgetDialog = null },
            title = {
                Text(
                    "Edit Category",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
            },
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

                    if (cat.type == "EXPENSE") {
                        OutlinedTextField(
                            value = budgetValStr,
                            onValueChange = { budgetValStr = it },
                            label = { Text("Budget Limit Amount (₹)") },
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
                                    .clickable {
                                        selectedColorHex = hexStr
                                        selectedColor = colorObj
                                    }
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
                            if (cat.isCustom) {
                                viewModel.deleteCustomCategory(cat.customId)
                            } else {
                                viewModel.hideStandardCategory(cat.name, cat.type)
                            }
                            if (checkCurrent != null) {
                                viewModel.deleteBudget(checkCurrent.id)
                            }
                            showSetBudgetDialog = null
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
                            val limitAmt = budgetValStr.toDoubleOrNull() ?: 0.0
                            val cleanName = editCatName.trim()
                            val effectiveBudgetCategory = resolveBudgetCategoryName(cat, cleanName)
                            
                            if (cat.isCustom) {
                                viewModel.updateCustomCategory(
                                    id = cat.customId,
                                    oldName = cat.name,
                                    newName = cleanName,
                                    iconName = selectedIconName,
                                    colorHex = selectedColorHex,
                                    type = cat.type
                                )
                                if (cat.type == "EXPENSE") {
                                    if (limitAmt > 0) {
                                        viewModel.saveBudget(effectiveBudgetCategory, cleanName, limitAmt, cat.name)
                                    } else if (checkCurrent != null) {
                                        viewModel.deleteBudget(checkCurrent.id)
                                    }
                                }
                            } else {
                                viewModel.customizeStandardCategory(
                                    oldName = cat.name,
                                    newName = cleanName,
                                    iconName = selectedIconName,
                                    colorHex = selectedColorHex,
                                    type = cat.type
                                )
                                if (cat.type == "EXPENSE") {
                                    if (limitAmt > 0) {
                                        viewModel.saveBudget(effectiveBudgetCategory, cleanName, limitAmt, cat.name)
                                    } else if (checkCurrent != null) {
                                        viewModel.deleteBudget(checkCurrent.id)
                                    }
                                }
                            }
                            showSetBudgetDialog = null
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
                    onClick = { showSetBudgetDialog = null },
                    modifier = Modifier.testTag("cancel_category_changes_button")
                ) {
                    Text("Cancel", color = Color.White)
                }
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
    val walletsBalances = computeWalletBalances(txs, accounts, carryOverPreviousAmount)
    
    var showTransferDialog by remember { mutableStateOf(false) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var selectedAccountForEdit by remember { mutableStateOf<Account?>(null) }
    
    val decFormat = DecimalFormat("₹#,##0.00")

    val activeAccounts = accounts

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
            Text(
                text = "Accounts Center",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
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
            items(activeAccounts.size) { index ->
                val acc = activeAccounts[index]
                val bal = walletsBalances[acc.name] ?: 0.0
                val smsTrackingBlocked = blockedSmsAccountIds.contains(acc.id)
                val color = when(acc.type) {
                    "CASH" -> Color(0xFF10B981)
                    "BANK" -> Color(0xFF00E5FF)
                    "CREDIT_CARD" -> Color(0xFFF43F5E)
                    "SAVINGS" -> Color(0xFFE91E63)
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
                                "SAVINGS" -> Icons.Default.Savings
                                else -> Icons.Default.AllInclusive
                            },
                            contentDescription = acc.type,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                acc.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            Text(
                                text = when(acc.type) {
                                    "CASH" -> "On-Hand Liquidity"
                                    "BANK" -> "Savings & Online Deposit"
                                    "CREDIT_CARD" -> "Line of Credit Liability"
                                    "SAVINGS" -> "Target Reserves Accumulator"
                                    else -> "Custom Register"
                                },
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.4f)
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

                        Text(
                            decFormat.format(bal),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = if (bal >= 0) Color(0xFF10B981) else Color(0xFFF43F5E)
                        )
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

        val types = listOf("CASH", "BANK", "CREDIT_CARD", "SAVINGS")

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
        var editName by remember(acc) { mutableStateOf(acc.name) }
        var editBalanceInput by remember(acc) { mutableStateOf(acc.balance.toString()) }
        var editType by remember(acc) { mutableStateOf(acc.type) }
        var editLast4 by remember(acc) { mutableStateOf(acc.lastFour ?: "") }
        var smsTrackingEnabled by remember(acc, blockedSmsAccountIds) { mutableStateOf(!blockedSmsAccountIds.contains(acc.id)) }

        val types = listOf("CASH", "BANK", "CREDIT_CARD", "SAVINGS")

        AlertDialog(
            onDismissRequest = { selectedAccountForEdit = null },
            title = { Text("Fine-Tune Wallet Ledger", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Directly correct or override this active wallet balance. You may also update metadata properties safely.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))

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
                        label = { Text("Target Base Balance (₹)") },
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Track this wallet from SMS",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Turn this off to block future inbox and real-time auto imports for this account.",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = smsTrackingEnabled,
                            onCheckedChange = { smsTrackingEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.45f),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                            )
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
                            val targetBal = editBalanceInput.toDoubleOrNull() ?: 0.0
                            val oldName = acc.name
                            val txOffset = txs.filter { it.getAccountName() == oldName }
                                .sumOf { if (it.type == "INCOME") it.amount else -it.amount }
                            val adjustedBase = targetBal - txOffset

                            viewModel.updateAccount(
                                acc.copy(
                                    name = editName,
                                    balance = adjustedBase,
                                    type = editType,
                                    lastFour = if (editLast4.isBlank()) null else editLast4
                                )
                            )
                            viewModel.setAccountSmsTrackingBlocked(acc, blocked = !smsTrackingEnabled)

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
    var manualRegexHint by remember { mutableStateOf("") }
    val forcePatternOptions = listOf("txn", "debited", "credited", "spent", "paid", "received", "upi", "card", "account", "salary")
    val suggestedForcePatterns = remember(manualSmsBody) {
        val lowerBody = manualSmsBody.lowercase()
        forcePatternOptions.filter { lowerBody.contains(it) }
    }
    var selectedForcePatterns by remember { mutableStateOf(emptySet<String>()) }
    val isSmsParsing by viewModel.isSmsParsing.collectAsStateWithLifecycle()
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
            viewModel.scanDeviceSmsInbox(context)
        } else {
            Toast.makeText(context, "SMS read permission is required for inbox import.", Toast.LENGTH_SHORT).show()
        }
    }
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
                    Button(
                        onClick = {
                            if (hasReadSmsPermission) {
                                viewModel.scanDeviceSmsInbox(context)
                            } else {
                                requestSmsLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("scan_device_sms_button")
                    ) {
                        Text(if (hasReadSmsPermission) "Scan Device SMS Inbox Now" else "Enable SMS Auto-Import", fontWeight = FontWeight.Bold)
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
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "PASTE MISSED SMS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Paste a real bank SMS here, then explicitly choose one or more keys to force the parser path for this single message.",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

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
                            .height(120.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        "Pick required parser keys",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                    if (suggestedForcePatterns.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Suggested from this SMS: ${suggestedForcePatterns.joinToString(", ")}",
                            fontSize = 10.sp,
                            color = Color(0xFF00E5FF)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = manualRegexHint,
                        onValueChange = { manualRegexHint = it },
                        label = { Text("Optional Custom Pattern") },
                        placeholder = { Text("Example: txn|debited|upi") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (selectedForcePatterns.isEmpty()) {
                            "Choose at least one key before analyzing this pasted SMS."
                        } else {
                            "Selected keys and custom pattern are only used for this pasted message. They do not change automatic inbox scanning rules globally."
                        },
                        fontSize = 10.sp,
                        color = if (selectedForcePatterns.isEmpty()) Color(0xFFF43F5E) else Color.White.copy(alpha = 0.45f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.analyzePastedSms(manualSmsBody, manualSmsSender, selectedForcePatterns.toList(), manualRegexHint) },
                        enabled = manualSmsBody.isNotBlank() && selectedForcePatterns.isNotEmpty() && !isSmsParsing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF0B0F19)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("analyze_pasted_sms_button")
                    ) {
                        Text(if (isSmsParsing) "Analyzing Message..." else "Analyze & Import Pasted SMS", fontWeight = FontWeight.Bold)
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

    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    val allCategories = CategoryResolver.getAll(customCats)
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val selectablesWallets = if (accounts.isEmpty()) {
        listOf("Cash Wallet", "Bank Account", "Credit Card", "Savings Goal")
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
            onDismiss = { showWalletPicker = false }
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
            onDismiss = { showCategoryPicker = false }
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
        listOf("Cash Wallet", "Bank Account", "Credit Card", "Savings Goal")
    } else {
        accounts.map { it.name }
    }
    val filteredCats = if (tx.type == "EXPENSE") {
        allCategories.filter { it.type == "EXPENSE" }
    } else {
        allCategories.filter { it.type == "INCOME" }
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

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        PickerButton(
                            label = "Account",
                            title = accountSelection,
                            icon = walletIconFor(accountSelection, accounts.find { it.name == accountSelection }?.type),
                            tint = Color(0xFF00E5FF),
                            onClick = { showWalletPicker = true }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PickerButton(
                            label = "Category",
                            title = selectedCategory?.displayName ?: "Choose category",
                            icon = selectedCategory?.icon ?: Icons.Default.Category,
                            tint = selectedCategory?.color ?: Color(0xFF00E5FF),
                            onClick = { showCategoryPicker = true }
                        )
                    }
                }

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
            onAdd = { name, balance, type, lastFour ->
                viewModel.addAccount(name, balance, type, lastFour)
                accountSelection = name.trim()
                showQuickAddAccount = false
            }
        )
    }

    if (showQuickAddCategory) {
        QuickAddCategoryDialog(
            defaultType = tx.type,
            onDismiss = { showQuickAddCategory = false },
            onAdd = { name, type ->
                val iconName = if (type == "INCOME") "salary" else "others"
                val colorHex = if (type == "INCOME") "#2E7D32" else "#607D8B"
                viewModel.addCustomCategory(name, iconName, colorHex, type)
                categorySelection = name.trim()
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
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(selectedTimestamp))
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                categories.forEach { category ->
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
        type == "SAVINGS" || name.contains("saving", ignoreCase = true) -> Icons.Default.Savings
        else -> Icons.Default.AccountBalanceWallet
    }
}

@Composable
private fun QuickAddAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Double, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("BANK") }
    var lastFour by remember { mutableStateOf("") }
    val types = listOf("CASH", "BANK", "CREDIT_CARD", "SAVINGS")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Account", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Account Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Opening Balance (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = lastFour, onValueChange = { lastFour = it }, label = { Text("Last 4 digits") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(types) { option ->
                        val active = option == type
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                                .border(1.dp, if (active) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .clickable { type = option }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(option.replace("_", " "), color = if (active) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, amount.toDoubleOrNull() ?: 0.0, type, lastFour.ifBlank { null }) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))) {
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
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(defaultType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Category", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Category Name") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("EXPENSE", "INCOME").forEach { option ->
                        val active = option == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                                .border(1.dp, if (active) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .clickable { type = option }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(option, color = if (active) Color(0xFF00E5FF) else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, type) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF0B0F19))) {
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
                        val fileName = "mymoney-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.xls"
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

fun getAnalyticsRange(monthYear: String, filter: String): Pair<Long, Long> {
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
        val start = (periodEnd.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, -6)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return start to periodEnd.timeInMillis
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
