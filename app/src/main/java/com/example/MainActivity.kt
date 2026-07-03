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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.darkAppColors
import com.example.ui.theme.lightAppColors
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

// Enum representing the five core tabs mimicking MyMoney
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

fun TransactionEntry.getTransferDestName(): String? {
    val noteStr = this.note ?: return null
    if (noteStr.contains("[To: ")) {
        val start = noteStr.indexOf("[To: ") + 5
        val end = noteStr.indexOf("]", start)
        if (end > start) return noteStr.substring(start, end)
    }
    return null
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
    // Strip any pre-existing [Acc: ...] tag to avoid double-tagging on re-save
    val clean = (plainNote ?: "").replace("\\s*\\[Acc:[^]]*]".toRegex(), "").trim()
    return if (clean.isEmpty()) "[Acc: $accountName]" else "$clean [Acc: $accountName]"
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

        if (tx.type == "TRANSFER") {
            // Deduct from source, credit destination — but only when the transfer falls
            // AFTER that account's own snapshot (checked independently for each side).
            val srcActual = tx.getAccountName(consolidate = false)
            val srcKey    = tx.getAccountName(consolidate)
            val destRaw   = tx.getTransferDestName() ?: continue
            val destKey = if (consolidate) {
                val destAcc = accountsList.find { it.name == destRaw }
                if (destAcc != null) when (destAcc.type) {
                    "CASH" -> "Cash Wallet"
                    "BANK" -> "Bank Account"
                    "CREDIT_CARD" -> "Credit Card"
                    "WALLET" -> "Digital Wallet"
                    else -> "Bank Account"
                } else destRaw
            } else destRaw
            val srcSnap  = latestSnap[srcActual]
            val destSnap = latestSnap[if (consolidate) destKey else destRaw]
            if (srcSnap  == null || tx.timestamp > srcSnap.first)  balances[srcKey]  = (balances[srcKey]  ?: 0.0) - tx.amount
            if (destSnap == null || tx.timestamp > destSnap.first) balances[destKey] = (balances[destKey] ?: 0.0) + tx.amount
            continue
        }

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
            val vm: FinanceViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val isDarkPref by vm.isDarkTheme.collectAsStateWithLifecycle()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val isDark = when (themeMode) {
                "dark"  -> true
                "light" -> false
                else    -> systemDark // "system" — follow device setting
            }
            val appColors = if (isDark) darkAppColors() else lightAppColors()
            MyApplicationTheme(darkTheme = isDark) {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides appColors) {
                    MainAppScreen(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: FinanceViewModel = viewModel()) {
    val context = LocalContext.current
    val c = LocalAppColors.current
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    val dashboardListState = rememberLazyListState()
    val fabAlpha by animateFloatAsState(
        targetValue = if (currentTab == AppTab.DASHBOARD && dashboardListState.isScrollInProgress) 0f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "FabAlpha"
    )
    
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

    // Theme-aware palette constants (switch with dark/light mode)
    val darkBg = c.bg
    val cardSurface = c.surface

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
                            color = c.accentDim,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = "Wallet Logo",
                                    tint = c.accent,
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
                            color = c.text
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
                                tint = c.accent
                            )
                        }
                        DropdownMenu(
                            expanded = showAppMenu,
                            onDismissRequest = { showAppMenu = false },
                            modifier = Modifier
                                .background(c.surface, RoundedCornerShape(12.dp))
                                .widthIn(min = 180.dp, max = 220.dp)
                        ) {
                            // ── Theme toggle ─────────────────────────────
                            DropdownMenuItem(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            "Theme",
                                            fontSize = 11.sp,
                                            color = c.textSecondary,
                                            modifier = Modifier.padding(bottom = 6.dp)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(
                                                Triple("Device", Icons.Default.SettingsBrightness, "system"),
                                                Triple("Light",  Icons.Default.LightMode,          "light"),
                                                Triple("Dark",   Icons.Default.DarkMode,            "dark")
                                            ).forEach { (label, icon, mode) ->
                                                val selected = themeMode == mode
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (selected) c.accent else c.surface,
                                                    border = BorderStroke(1.dp, if (selected) c.accent else c.divider),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable {
                                                            viewModel.setThemeMode(mode)
                                                            if (mode == "dark") viewModel.setDarkTheme(true)
                                                            else if (mode == "light") viewModel.setDarkTheme(false)
                                                        }
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 6.dp)
                                                    ) {
                                                        Icon(
                                                            icon, null,
                                                            tint = if (selected) c.bg else c.textSecondary,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                        Spacer(Modifier.height(3.dp))
                                                        Text(
                                                            label,
                                                            fontSize = 9.sp,
                                                            color = if (selected) c.bg else c.textSecondary,
                                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                onClick = {}
                            )
                            HorizontalDivider(color = c.divider)
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Share, contentDescription = "Export", tint = c.accent)
                                        Text("Export", color = c.text)
                                    }
                                },
                                onClick = { showAppMenu = false; showCsvDialog = true }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Backup, contentDescription = "Backup", tint = c.income)
                                        Text("Backup", color = c.text)
                                    }
                                },
                                onClick = { showAppMenu = false; showBackupDialog = true }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Restore, contentDescription = "Restore", tint = Color(0xFFFFC107))
                                        Text("Restore", color = c.text)
                                    }
                                },
                                onClick = { showAppMenu = false; showRestoreDialog = true }
                            )
                            HorizontalDivider(color = c.divider)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Ver: 1.41",
                                        fontSize = 11.sp,
                                        color = c.textSecondary,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                },
                                onClick = {},
                                enabled = false,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
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
            Column {
                HorizontalDivider(color = c.border, thickness = 1.dp)
                NavigationBar(
                    containerColor = if (c.isDark) c.surface else Color(0xFFEDF3FA),
                    tonalElevation = 0.dp,
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
                                selectedIconColor = c.accent,
                                selectedTextColor = c.accent,
                                unselectedIconColor = c.textSecondary,
                                unselectedTextColor = c.textSecondary,
                                indicatorColor = c.accentDim
                            )
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentTab == AppTab.DASHBOARD) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = c.accent,
                    contentColor = c.bg,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .testTag("add_transaction_fab")
                        .padding(bottom = 8.dp)
                        .alpha(fabAlpha)
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
                    AppTab.DASHBOARD -> DashboardScreen(viewModel, dashboardListState)
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: FinanceViewModel, listState: LazyListState) {
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val c = LocalAppColors.current
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val activeMode by viewModel.displayMode.collectAsStateWithLifecycle()
    val anchorTime by viewModel.anchorDate.collectAsStateWithLifecycle()
    val carryOverPreviousAmount by viewModel.carryOverPreviousAmount.collectAsStateWithLifecycle()
    val showTotal by viewModel.showTotal.collectAsStateWithLifecycle()
    val consolidateAccounts by viewModel.consolidateAccounts.collectAsStateWithLifecycle()
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    val recentlyImportedFingerprints by viewModel.recentlyImportedFingerprints.collectAsStateWithLifecycle()
    val showRunningBalance by viewModel.showRunningBalance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val decFormat = DecimalFormat("₹#,##0.00")
    
    var selectedWallet by remember { mutableStateOf("All") }
    var selectedTxForEdit by remember { mutableStateOf<TransactionEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchFilter by remember { mutableStateOf("All") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var showDeleteOptionsSheet by remember { mutableStateOf(false) }

    // Close search bar on back press instead of exiting the app
    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        searchQuery = ""
        searchFilter = "All"
    }
    var deleteConfirmMode by remember { mutableStateOf("") }
    var selectedDeleteCategory by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showNetAssetInfo by remember { mutableStateOf(false) }
    
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

    // Running balance per transaction — walks the full transaction history in chronological order
    // and records the account balance (or total across all accounts) at each tx's point in time.
    // Only computed when the toggle is on to avoid unnecessary work.
    val runningBalances: Map<Int, Double> = remember(txs, accounts, selectedWallet, carryOverPreviousAmount, consolidateAccounts, showRunningBalance) {
        if (!showRunningBalance) {
            emptyMap()
        } else {
            val result = mutableMapOf<Int, Double>()
            val balMap = mutableMapOf<String, Double>()
            for (acc in accounts) {
                balMap[acc.name] = if (carryOverPreviousAmount) acc.balance else 0.0
            }
            for (tx in txs.sortedBy { it.timestamp }) {
                val accName = tx.getAccountName(false)
                when (tx.type) {
                    "BALANCE_UPDATE" -> balMap[accName] = tx.amount
                    "DUPLICATE"      -> { /* skip — no real money movement */ }
                    "TRANSFER" -> {
                        balMap[accName] = (balMap[accName] ?: 0.0) - tx.amount
                        val dest = tx.getTransferDestName()
                        if (dest != null) balMap[dest] = (balMap[dest] ?: 0.0) + tx.amount
                    }
                    else -> {
                        val delta = if (tx.type == "INCOME") tx.amount else -tx.amount
                        balMap[accName] = (balMap[accName] ?: 0.0) + delta
                    }
                }
                // Only record for txs matching the current wallet filter
                val txAccDisplay = tx.getAccountName(consolidateAccounts)
                if (selectedWallet != "All" && txAccDisplay != selectedWallet) continue
                val displayBal = if (selectedWallet == "All") {
                    accounts.sumOf { acc -> balMap[acc.name] ?: 0.0 }
                } else {
                    balMap[tx.getAccountName(false)] ?: 0.0
                }
                result[tx.id] = displayBal
            }
            result
        }
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
            val displayCategory = CategoryResolver.resolve(tx.category, customCats).displayName
            when (searchFilter) {
                "Title"    -> tx.title.contains(query, ignoreCase = true)
                "Category" -> tx.category.contains(query, ignoreCase = true) || displayCategory.contains(query, ignoreCase = true)
                "Account"  -> tx.getAccountName(consolidateAccounts).contains(query, ignoreCase = true)
                "Type"     -> tx.type.contains(query, ignoreCase = true)
                else       -> {
                    val noteText = tx.note?.substringBefore(" [Acc:")?.trim().orEmpty()
                    tx.title.contains(query, ignoreCase = true) ||
                        tx.category.contains(query, ignoreCase = true) ||
                        displayCategory.contains(query, ignoreCase = true) ||
                        noteText.contains(query, ignoreCase = true) ||
                        tx.getAccountName(consolidateAccounts).contains(query, ignoreCase = true)
                }
            }
        }
    }
    
    // Monthly aggregates (specific to selected wallet)
    val totalIncome = monthTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
    val totalExpense = monthTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val netBalance = totalIncome - totalExpense
    val totalWealth = walletsBalances.values.sum()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_scroll_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    color = c.surface,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, c.border),
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
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Period", tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = formatPeriodLabel(activeMode, anchorTime),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = c.text,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { shiftPeriod(viewModel, activeMode, anchorTime, 1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next Period", tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalIconButton(
                        onClick = {
                            isSearchExpanded = !isSearchExpanded
                            if (!isSearchExpanded) { searchQuery = ""; searchFilter = "All" }
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isSearchExpanded || searchQuery.isNotBlank()) c.accent.copy(alpha = 0.18f) else c.divider,
                            contentColor = if (isSearchExpanded || searchQuery.isNotBlank()) c.accent else c.text
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Toggle search", modifier = Modifier.size(20.dp))
                    }

                    Box {
                        FilledTonalIconButton(
                            onClick = { showFilterMenu = !showFilterMenu },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isSearchExpanded || searchQuery.isNotBlank()) c.accent.copy(alpha = 0.18f) else c.divider,
                                contentColor = if (isSearchExpanded || searchQuery.isNotBlank()) c.accent else c.text
                            ),
                            modifier = Modifier.size(40.dp).testTag("three_bar_filter_button")
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Options and Filtering", modifier = Modifier.size(22.dp))
                        }

                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false },
                            modifier = Modifier
                                .background(c.surfaceVariant)
                                .border(1.dp, c.border, RoundedCornerShape(8.dp))
                                .width(260.dp)
                        ) {
                        DropdownMenuItem(
                            text = {
                                Text("DISPLAY MODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
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
                                        Text(label, color = c.text, fontSize = 13.sp)
                                        if (activeMode == mode) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = c.accent,
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
                        
                        HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                        
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Carry over Balances", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.text)
                                        Text("Include previous initial totals", fontSize = 9.sp, color = c.textSecondary)
                                    }
                                    Switch(
                                        checked = carryOverPreviousAmount,
                                        onCheckedChange = { viewModel.setCarryOverPreviousAmount(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = c.bg,
                                            checkedTrackColor = c.accent
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
                                        Text("Running Balance", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.text)
                                        Text("Show balance after each transaction", fontSize = 9.sp, color = c.textSecondary)
                                    }
                                    Switch(
                                        checked = showRunningBalance,
                                        onCheckedChange = { viewModel.setShowRunningBalance(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = c.bg,
                                            checkedTrackColor = c.accent
                                        ),
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }
                            },
                            onClick = {
                                viewModel.setShowRunningBalance(!showRunningBalance)
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
                                        Text("Show Balance Figures", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.text)
                                        Text("Turn off to hide amounts for privacy", fontSize = 9.sp, color = c.textSecondary)
                                    }
                                    Switch(
                                        checked = showTotal,
                                        onCheckedChange = { viewModel.setShowTotal(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = c.bg,
                                            checkedTrackColor = c.accent
                                        ),
                                        modifier = Modifier.scale(0.8f).testTag("menu_show_total_switch")
                                    )
                                }
                            },
                            onClick = {
                                viewModel.setShowTotal(!showTotal)
                            }
                        )
                        HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "Delete", tint = c.expense, modifier = Modifier.size(18.dp))
                                    Text("Delete Records", fontSize = 13.sp, color = c.expense)
                                }
                            },
                            onClick = {
                                showDeleteOptionsSheet = true
                                showFilterMenu = false
                            }
                        )
                    }
                }
            }
        }
        }

        if (!isSearchExpanded) {
        // Net Balance Glow Card
        item {
            Surface(
                color = c.surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.2.dp, c.accent.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("net_wealth_glowing_card")
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedWallet == "All") "NET WALLET ASSETS" else "${selectedWallet.uppercase()}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = c.textSecondary
                        )
                        if (selectedWallet == "All") {
                            Spacer(modifier = Modifier.width(5.dp))
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "What is Net Wallet Assets?",
                                tint = c.textSecondary.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable { showNetAssetInfo = true }
                            )
                        }
                    }
                    if (showNetAssetInfo) {
                        AlertDialog(
                            onDismissRequest = { showNetAssetInfo = false },
                            title = { Text("Net Wallet Assets", fontWeight = FontWeight.Bold, color = c.text) },
                            text = {
                                Text(
                                    "This is the actual running balance across all your wallets — the real money present in each account right now.\n\n" +
                                    "It is computed from your recorded income and expense transactions, anchored by any manual balance adjustments you've set. " +
                                    "It reflects how much money you truly have, regardless of how much flowed in or out during this period.\n\n" +
                                    "If you select a specific wallet instead of 'All', this shows that wallet's individual balance.",
                                    color = c.text, fontSize = 13.sp
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showNetAssetInfo = false }) {
                                    Text("Got it", color = c.accent, fontWeight = FontWeight.Bold)
                                }
                            },
                            containerColor = c.surface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (showTotal) {
                            decFormat.format(if (selectedWallet == "All") totalWealth else (walletsBalances[selectedWallet] ?: 0.0))
                        } else {
                            "₹ ••••"
                        },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 32.sp,
                        color = c.accent
                    )
                    
                    Spacer(modifier = Modifier.height(18.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(c.divider)
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
                                color = c.income.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Income Flow",
                                        tint = c.income,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Inflow", fontSize = 11.sp, color = c.textSecondary)
                                Text(
                                    decFormat.format(totalIncome),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = c.income
                                )
                            }
                        }

                        // Monthly Expense Tracker
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = c.expense.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Expense Outflow",
                                        tint = c.expense,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Outflow", fontSize = 11.sp, color = c.textSecondary)
                                Text(
                                    decFormat.format(totalExpense),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = c.expense
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
                    color = c.textSecondary
                )
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val walletList = listOf("All") + accounts.map { it.name }
                    items(walletList) { name ->
                        val isSelected = selectedWallet == name
                        
                        val balance = if (name == "All") {
                            totalWealth
                        } else {
                            walletsBalances[name] ?: 0.0
                        }

                        val cardBorderColor = if (isSelected) c.accent else c.border
                        val cardBg = if (isSelected) c.accentDim else c.surface
                        val walletAccType = if (name == "All") "ALL" else accounts.find { it.name == name }?.type ?: ""
                        val walletTypeColor = when (walletAccType) {
                            "CASH"        -> c.income
                            "BANK"        -> Color(0xFF3B82F6)
                            "CREDIT_CARD" -> c.expense
                            "WALLET"      -> Color(0xFFFF9800)
                            else          -> c.accent
                        }

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
                                    tint = walletTypeColor,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = c.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = decFormat.format(balance),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (balance >= 0) c.income else c.expense,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        } // end if (!isSearchExpanded)

        if (isSearchExpanded) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = c.textSecondary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", tint = c.textSecondary)
                                }
                            }
                        },
                        label = { Text("Search records") },
                        placeholder = {
                            Text(when (searchFilter) {
                                "Title"    -> "Search by merchant / title…"
                                "Category" -> "Search by category…"
                                "Account"  -> "Search by account / wallet…"
                                "Type"     -> "INCOME or EXPENSE…"
                                else       -> "Search all fields…"
                            })
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            focusedBorderColor = c.accent,
                            unfocusedBorderColor = c.textTertiary,
                            focusedLabelColor = c.accent,
                            unfocusedLabelColor = c.textSecondary
                        )
                    )
                    // Filter chips — narrow the search to a specific field
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val filters = listOf("All", "Title", "Category", "Account", "Type")
                        items(filters.size) { i ->
                            val f = filters[i]
                            val sel = searchFilter == f
                            FilterChip(
                                selected = sel,
                                onClick = { searchFilter = f },
                                label = { Text(f, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = if (sel) {{ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }} else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = c.accent.copy(0.15f),
                                    selectedLabelColor = c.accent,
                                    containerColor = c.divider,
                                    labelColor = c.textSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = sel,
                                    selectedBorderColor = c.accent.copy(0.5f),
                                    borderColor = c.text.copy(0.1f)
                                )
                            )
                        }
                    }
                }
            }
        }

        // Chronological transaction lists grouped by Dates
        if (visibleTransactions.isEmpty()) {
            item {
                Surface(
                    color = c.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, c.border),
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
                            tint = c.textTertiary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (searchQuery.isBlank()) "No transactions recorded for this period." else "No transactions match your search.",
                            color = c.textSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap the '+' FAB below to log a cashflow.",
                            color = c.accent,
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
                            .background(c.bg)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateStr.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp,
                            color = c.textSecondary
                        )
                        
                        val dateNet = txList.filter { it.type != "DUPLICATE" && it.type != "BALANCE_UPDATE" && it.type != "TRANSFER" }.sumOf { if (it.type == "INCOME") it.amount else -it.amount }
                        Text(
                            text = (if (dateNet >= 0) "+" else "") + decFormat.format(dateNet),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (dateNet >= 0) c.income else c.expense
                        )
                    }
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        txList.forEachIndexed { txIdx, tx ->
                            val resolvedCat = CategoryResolver.resolve(tx.category, customCats)
                            val isAdjust = tx.category.equals("ADJUST", ignoreCase = true)
                            val isBalanceSync = tx.type == "BALANCE_UPDATE"
                            val isTransfer = tx.type == "TRANSFER"
                            val transferColor = Color(0xFF3B82F6)
                            val catColor = when {
                                isBalanceSync -> c.textTertiary
                                isTransfer -> transferColor
                                else -> resolvedCat.color
                            }
                            val catIcon  = when {
                                isBalanceSync -> Icons.Default.Autorenew
                                isTransfer -> Icons.Default.SwapHoriz
                                else -> resolvedCat.icon
                            }
                            val acctIcon = walletIconFor(tx.getAccountName(), null)
                            val acctType = accounts.find { it.name == tx.getAccountName() }?.type ?: ""
                            val acctColor = when (acctType) {
                                "CASH"        -> c.income
                                "BANK"        -> Color(0xFF3B82F6)
                                "CREDIT_CARD" -> c.expense
                                "WALLET"      -> Color(0xFFFF9800)
                                else          -> c.textSecondary
                            }
                            val txFingerprint = "${tx.title}|${tx.amount}|${tx.type}|${tx.timestamp}"
                            val isNewlyImported = recentlyImportedFingerprints.contains(txFingerprint)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = c.surface,
                                border = if (isNewlyImported) BorderStroke(2.dp, c.income) else BorderStroke(1.dp, c.divider),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable { selectedTxForEdit = tx }
                                    .testTag("transaction_item_${tx.id}")
                            ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = catColor.copy(alpha = 0.15f),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = catIcon,
                                            contentDescription = resolvedCat.displayName,
                                            tint = catColor,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tx.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = c.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    // For transfers: chip extends full width + time sits at end of chip row.
                                    // For other types: chip sits alone in the subtitle row.
                                    if (isTransfer) {
                                        Surface(
                                            color = acctColor.copy(alpha = 0.10f),
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = acctIcon,
                                                    contentDescription = null,
                                                    tint = acctColor,
                                                    modifier = Modifier.size(9.dp)
                                                )
                                                val dest = tx.getTransferDestName()
                                                Text(
                                                    text = if (dest != null) "${tx.getAccountName()} → $dest" else tx.getAccountName(),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = acctColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = acctColor.copy(alpha = 0.10f),
                                                shape = RoundedCornerShape(20.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = acctIcon,
                                                        contentDescription = null,
                                                        tint = acctColor,
                                                        modifier = Modifier.size(9.dp)
                                                    )
                                                    Text(
                                                        text = tx.getAccountName(),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = acctColor,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = when (tx.type) {
                                            "INCOME" -> "+" + decFormat.format(tx.amount)
                                            "DUPLICATE" -> "DUP"
                                            "TRANSFER" -> "⇄ " + decFormat.format(tx.amount)
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
                                            "INCOME" -> c.income
                                            "DUPLICATE" -> c.textTertiary
                                            "TRANSFER" -> Color(0xFF3B82F6)
                                            "BALANCE_UPDATE" -> c.textTertiary
                                            else -> c.expense
                                        }
                                    )
                                    // Running balance: plain gray figure above timestamp, hidden by default
                                    if (showRunningBalance) {
                                        val runBal = runningBalances[tx.id]
                                        if (runBal != null && tx.type != "DUPLICATE") {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = decFormat.format(runBal),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = c.textTertiary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = SystemDateFormat.getTimeFormat(context).format(Date(tx.timestamp)),
                                        fontSize = 10.sp,
                                        color = c.textTertiary
                                    )
                                    if (isNewlyImported) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Surface(
                                            color = c.income.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "NEW",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = c.income,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            } // end Surface card
                        }
                    }
                }
            }
        }
    }

    // ── Delete options bottom sheet ──────────────────────────────────────
    if (showDeleteOptionsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val periodCategories = remember(monthTransactions, customCats) {
            monthTransactions
                .filter { it.type != "BALANCE_UPDATE" }
                .map { CategoryResolver.resolve(it.category, customCats).displayName }
                .distinct()
                .sorted()
        }
        var showCategoryPicker by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showDeleteOptionsSheet = false },
            sheetState = sheetState,
            containerColor = c.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "Delete Records",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = c.text,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Option 1: Delete All Records
                Surface(
                    onClick = { deleteConfirmMode = "all"; showDeleteConfirmDialog = true },
                    color = c.expense.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, tint = c.expense)
                        Column {
                            Text("Delete All Records", color = c.text, fontWeight = FontWeight.SemiBold)
                            Text("Permanently removes every transaction", color = c.textSecondary, fontSize = 12.sp)
                        }
                    }
                }

                // Option 2: Delete This Period's Records
                Surface(
                    onClick = { deleteConfirmMode = "period"; showDeleteConfirmDialog = true },
                    color = Color(0xFFFFC107).copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFFFC107))
                        Column {
                            Text(
                                "Delete ${formatPeriodLabel(activeMode, anchorTime)}",
                                color = c.text,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Remove all transactions in the current period", color = c.textSecondary, fontSize = 12.sp)
                        }
                    }
                }

                // Option 3: Delete by Category
                Surface(
                    onClick = { showCategoryPicker = true },
                    color = c.accent.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = null, tint = c.accent)
                        Column {
                            Text("Delete by Category", color = c.text, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (periodCategories.isEmpty()) "No categories in this period"
                                else "Choose a category from the current period",
                                color = c.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Category picker dialog
            if (showCategoryPicker) {
                AlertDialog(
                    onDismissRequest = { showCategoryPicker = false },
                    title = { Text("Choose Category", color = c.text) },
                    text = {
                        if (periodCategories.isEmpty()) {
                            Text("No categories found in this period.", color = c.textSecondary)
                        } else {
                            LazyColumn {
                                items(periodCategories.size) { i ->
                                    val cat = periodCategories[i]
                                    TextButton(
                                        onClick = {
                                            selectedDeleteCategory = cat
                                            showCategoryPicker = false
                                            deleteConfirmMode = "category"
                                            showDeleteConfirmDialog = true
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            cat,
                                            color = c.accent,
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCategoryPicker = false }) {
                            Text("Cancel", color = c.text)
                        }
                    },
                    containerColor = c.border
                )
            }
        }
    }

    // ── Delete confirmation dialog ────────────────────────────────────────
    if (showDeleteConfirmDialog) {
        val confirmText = when (deleteConfirmMode) {
            "all"      -> "Delete ALL transactions permanently? This cannot be undone."
            "period"   -> "Delete every transaction in ${formatPeriodLabel(activeMode, anchorTime)}? This cannot be undone."
            "category" -> "Delete all '$selectedDeleteCategory' transactions in ${formatPeriodLabel(activeMode, anchorTime)}? This cannot be undone."
            else       -> ""
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Confirm Delete", color = c.text) },
            text = { Text(confirmText, color = c.text.copy(alpha = 0.75f)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (deleteConfirmMode) {
                            "all"      -> viewModel.clearAllData()
                            "period"   -> viewModel.deleteTransactionsInRange(periodStart, periodEnd, formatPeriodLabel(activeMode, anchorTime))
                            "category" -> {
                                val rawCat = monthTransactions
                                    .firstOrNull { CategoryResolver.resolve(it.category, customCats).displayName == selectedDeleteCategory }
                                    ?.category ?: selectedDeleteCategory
                                viewModel.deleteTransactionsByCategoryInRange(rawCat, periodStart, periodEnd)
                            }
                        }
                        showDeleteConfirmDialog = false
                        showDeleteOptionsSheet = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = c.expense)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = c.text)
                }
            },
            containerColor = c.surface
        )
    }

    // Modal dialog for editing/deleting any selected transaction
    selectedTxForEdit?.let { editTx ->
        var showDeleteConfirm by remember { mutableStateOf(false) }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Transaction", color = c.text) },
                text = { Text("Are you absolutely sure you want to delete this recorded transaction?", color = c.text.copy(alpha = 0.7f)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteTransaction(editTx.id)
                            selectedTxForEdit = null
                            showDeleteConfirm = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = c.expense)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = c.text)
                    }
                },
                containerColor = c.surface
            )
        } else {
            EditTransactionDialog(
                tx = editTx,
                viewModel = viewModel,
                onDismiss = { selectedTxForEdit = null },
                onDelete = { showDeleteConfirm = true },
                onConfirm = { updated, applyToAll ->
                    viewModel.updateTransaction(updated, applyToAll)
                    selectedTxForEdit = null
                }
            )
        }
    }
}

// 2. ANALYSIS / ANALYTICS SCREEN
@Composable
fun AnalyticsScreen(viewModel: FinanceViewModel) {
    val c = LocalAppColors.current
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val rawMonthYear by viewModel.selectedMonthYear.collectAsStateWithLifecycle()
    val anchorTime by viewModel.anchorDate.collectAsStateWithLifecycle()
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())

    var timeFilter by remember { mutableStateOf("MONTHLY") }
    var selectedMode by remember { mutableStateOf(AnalyticsMode.EXPENSE_OVERVIEW) }
    var showModeMenu by remember { mutableStateOf(false) }
    var showPeriodMenu by remember { mutableStateOf(false) }

    // Sync Analytics period with Records view so both tabs show the same window
    val activeMode by viewModel.displayMode.collectAsStateWithLifecycle()
    LaunchedEffect(activeMode) {
        timeFilter = when (activeMode) {
            DisplayMode.WEEKLY       -> "WEEKLY"
            DisplayMode.MONTHLY      -> "MONTHLY"
            DisplayMode.THREE_MONTHS -> "3M"
            DisplayMode.SIX_MONTHS   -> "6M"
            DisplayMode.ONE_YEAR     -> "1Y"
            else                     -> "MONTHLY"
        }
    }
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
        buildAccountAnalytics(filteredTransactions, c)
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
                        color = c.surface,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, c.border),
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
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Period", tint = c.accent, modifier = Modifier.size(20.dp))
                            }
                            Text(
                                text = formatAnalyticsPeriodLabel(rawMonthYear, timeFilter, anchorTime),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = c.text,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { shiftAnalyticsPeriod(viewModel, rawMonthYear, timeFilter, 1, anchorTime) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next Period", tint = c.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Box {
                        FilledTonalIconButton(
                            onClick = { showPeriodMenu = true },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = c.divider,
                                contentColor = c.text
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Select time period", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showPeriodMenu,
                            onDismissRequest = { showPeriodMenu = false },
                            modifier = Modifier
                                .background(c.surfaceVariant)
                                .border(1.dp, c.border, RoundedCornerShape(8.dp))
                                .width(180.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("PERIOD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary) },
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
                                            Text(label, color = if (timeFilter == key) c.accent else c.text, fontSize = 13.sp)
                                            if (timeFilter == key) Icon(Icons.Default.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                        border = BorderStroke(1.dp, c.divider),
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
                                    color = c.textSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = selectedMode.label,
                                    fontSize = 15.sp,
                                    color = c.text,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(Icons.Default.ExpandMore, contentDescription = "Select analysis mode", tint = c.accent)
                        }
                    }

                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                        modifier = Modifier.background(c.surface)
                    ) {
                        AnalyticsMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        mode.label,
                                        color = if (mode == selectedMode) c.accent else c.text,
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
                        accent = c.expense,
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
                        accent = c.income,
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
    val c = LocalAppColors.current
    var activeSectorIndex by remember(categoryTotals, totalLabel) { mutableStateOf(-1) }
    val decFormat = remember { DecimalFormat("₹#,##0.00") }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = c.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, c.border),
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
                        Text(emptyMessage, color = c.textSecondary, fontSize = 12.sp)
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
                                                color = c.text.toArgb()
                                                alpha = if (c.isDark) 220 else 200
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
                                            color = c.textSecondary.toArgb()
                                            textSize = 10.sp.toPx()
                                            isAntiAlias = true
                                            textAlign = android.graphics.Paint.Align.CENTER
                                        }
                                        val amtPaint = android.graphics.Paint().apply {
                                            color = c.text.toArgb()
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
                                            .background(if (activeSectorIndex == idx) c.divider else Color.Transparent)
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
                                            color = c.text.copy(alpha = 0.85f),
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = breakdownLabel,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = c.textSecondary
            )

            categoryTotals.forEachIndexed { idx, stats ->
                val active = activeSectorIndex == idx
                Surface(
                    color = if (active) stats.category.color.copy(alpha = 0.08f) else c.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (active) stats.category.color else c.border),
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
                                    color = c.text,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(decFormat.format(stats.total), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
                            }
                            LinearProgressIndicator(
                                progress = stats.percentage.toFloat(),
                                color = stats.category.color,
                                trackColor = c.divider,
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
            } // end breakdown Column
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
    val c = LocalAppColors.current
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
                        Text(title.uppercase(Locale.getDefault()), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = c.textSecondary)
                        Text(decFormat.format(total), fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = accent)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Average active day", fontSize = 11.sp, color = c.textSecondary)
                        Text(decFormat.format(average), fontWeight = FontWeight.Bold, color = c.text)
                    }
                }

                if (nonZeroPoints.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(emptyMessage, color = c.textSecondary, fontSize = 12.sp)
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
                                color = c.text
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
                                        color = c.textSecondary,
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
                                        color = c.divider,
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
                                    color = if (index == activePointIndex) accent else c.textSecondary,
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
            color = c.textSecondary
        )

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.divider)
                    .padding(vertical = 6.dp)
            ) {
                    listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                        Text(
                            text = day,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = c.textSecondary
                        )
                    }
                }
                HorizontalDivider(color = c.divider)
                calendarCells.chunked(7).forEachIndexed { weekIdx, week ->
                    if (weekIdx > 0) HorizontalDivider(color = c.divider)
                    Row(modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        week.forEachIndexed { dayIdx, point ->
                            if (dayIdx > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(1.dp)
                                        .background(c.divider)
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
                                            color = c.textSecondary,
                                            modifier = Modifier.align(Alignment.Start)
                                        )
                                        Text(
                                            text = if (value > 0.0) compactCurrency(value) else "\u00b7",
                                            fontSize = 7.sp,
                                            fontWeight = if (value > 0.0) FontWeight.Bold else FontWeight.Normal,
                                            color = if (value > 0.0) accent else c.text.copy(alpha = 0.18f),
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
    val c = LocalAppColors.current
    val decFormat = remember { DecimalFormat("₹#,##0.00") }
    val maxActivity = accountStats.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1.0) ?: 1.0
    val yAxisValues = remember(maxActivity) {
        listOf(maxActivity, maxActivity * 0.66, maxActivity * 0.33, 0.0)
    }
    var activeAccountIndex by remember(accountStats) { mutableStateOf(if (accountStats.isEmpty()) 0 else 0) }
    val activeAccount = accountStats.getOrNull(activeAccountIndex)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ACCOUNT ACTIVITY CHART", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = c.textSecondary)

                if (accountStats.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text("No account activity available for this period.", color = c.textSecondary, fontSize = 12.sp)
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
                                color = c.income.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text("INCOME", color = c.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    Text(compactCurrency(stats.income), color = c.income, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                            Surface(
                                color = c.expense.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text("EXPENSE", color = c.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    Text(compactCurrency(stats.expense), color = c.expense, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                            Surface(
                                color = (if (stats.net >= 0) c.accent else Color(0xFFFF7043)).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text("NET", color = c.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                    Text(compactCurrency(stats.net), color = if (stats.net >= 0) c.accent else Color(0xFFFF7043), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
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
                                    color = c.textSecondary,
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
                                    color = c.divider,
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
                                        color = c.divider,
                                        topLeft = Offset(slotWidth * index + 4.dp.toPx(), topPad),
                                        size = Size(slotWidth - 8.dp.toPx(), chartHeight),
                                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                                    )
                                }

                                if (stats.income > 0.0) {
                                    drawRoundRect(
                                        color = c.income,
                                        topLeft = Offset(incomeCenterX - barWidth / 2f, incomeY),
                                        size = Size(barWidth, (barBottom - incomeY).coerceAtLeast(4.dp.toPx())),
                                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                        alpha = barAlpha
                                    )
                                }
                                if (stats.expense > 0.0) {
                                    drawRoundRect(
                                        color = c.expense,
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
                                color = if (index == activeAccountIndex) stats.color else c.textSecondary,
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
                color = c.textSecondary
            )

            accountStats.forEach { stats ->
                Surface(
                    color = c.surface,
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
                            color = c.text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 10.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("IN", color = c.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                                Text(compactCurrency(stats.income), color = c.income, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("OUT", color = c.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                                Text(compactCurrency(stats.expense), color = c.expense, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("NET", color = c.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                                Text(compactCurrency(stats.net), color = if (stats.net >= 0) c.accent else Color(0xFFFF7043), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
    val c = LocalAppColors.current
    val decFormat = remember { DecimalFormat("₹#,##0.00") }
    Surface(
        color = tone.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = c.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
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

private fun buildAccountAnalytics(transactions: List<TransactionEntry>, appColors: com.example.ui.theme.AppColors): List<AccountAnalyticsSummary> {
    val palette = listOf(
        appColors.accent,
        appColors.income,
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
    "localgasstation" to Icons.Default.LocalGasStation,
    "checkroom" to Icons.Default.Checkroom,
    "payments" to Icons.Default.Payments,
    "eco" to Icons.Default.Eco,
    "twowheeler" to Icons.Default.TwoWheeler,
    "bolt" to Icons.Default.Bolt,
    "creditcard" to Icons.Default.CreditCard,
    "coffee" to Icons.Default.LocalCafe,
    "flight" to Icons.Default.Flight,
    "home" to Icons.Default.Home,
    "kitchen" to Icons.Default.Kitchen,
    "outside_food" to Icons.Default.Fastfood,
    "groceries" to Icons.Default.ShoppingCart,
    "cashback" to Icons.Default.Redeem,
    "investment" to Icons.Default.TrendingUp,
    "mutual_fund" to Icons.Default.AccountBalance,
    "etf" to Icons.Default.BarChart,
    "adjust" to Icons.Default.SwapVert,
    "hotel" to Icons.Default.Hotel,
    "movie" to Icons.Default.Movie,
    "music" to Icons.Default.MusicNote,
    "gift" to Icons.Default.CardGiftcard,
    "children" to Icons.Default.ChildCare,
    "pet" to Icons.Default.Pets,
    "pharmacy" to Icons.Default.LocalPharmacy,
    "work" to Icons.Default.Work,
    // New icons
    "online_shopping" to Icons.Default.Laptop,
    "maintenance" to Icons.Default.Build,
    "drinks" to Icons.Default.LocalBar,
    "fruits" to Icons.Default.WaterDrop,
    "campfire" to Icons.Default.Fireplace,
    "shoes" to Icons.Default.DirectionsRun,
    "party" to Icons.Default.Celebration,
    "birthday" to Icons.Default.Cake,
    "vacation" to Icons.Default.BeachAccess,
    "beauty" to Icons.Default.Spa,
    "cab" to Icons.Default.LocalTaxi,
    "others" to Icons.Default.Category
)

fun iconLabel(key: String): String = when (key) {
    "restaurant" -> "Dining"
    "shopping" -> "Shopping"
    "car" -> "Car"
    "bills" -> "Bills"
    "recharge" -> "Recharge"
    "gym" -> "Gym"
    "sport" -> "Sport"
    "electronics" -> "Tech"
    "insurance" -> "Insurance"
    "social" -> "Social"
    "tax" -> "Tax"
    "transportation" -> "Transit"
    "education" -> "Education"
    "healthcare" -> "Health"
    "entertainment" -> "Fun"
    "awards" -> "Awards"
    "coupons" -> "Coupons"
    "grants" -> "Grants"
    "refunds" -> "Refunds"
    "rental" -> "Rental"
    "salary" -> "Salary"
    "sale" -> "Sale"
    "rewards" -> "Rewards"
    "coins" -> "Savings"
    "upi" -> "UPI"
    "localgasstation" -> "Fuel"
    "checkroom" -> "Clothing"
    "payments" -> "Payment"
    "eco" -> "Eco"
    "twowheeler" -> "Bike"
    "bolt" -> "Electric"
    "creditcard" -> "Card"
    "coffee" -> "Café"
    "flight" -> "Flight"
    "home" -> "Home"
    "kitchen" -> "Kitchen"
    "outside_food" -> "Takeout"
    "groceries" -> "Grocery"
    "cashback" -> "Cashback"
    "investment" -> "Invest"
    "mutual_fund" -> "Funds"
    "etf" -> "ETF"
    "adjust" -> "Adjust"
    "hotel" -> "Hotel"
    "movie" -> "Movie"
    "music" -> "Music"
    "gift" -> "Gift"
    "children" -> "Kids"
    "pet" -> "Pet"
    "pharmacy" -> "Pharmacy"
    "work" -> "Work"
    "online_shopping" -> "Online"
    "maintenance" -> "Repair"
    "drinks" -> "Drinks"
    "fruits" -> "Fruits"
    "campfire" -> "Camp"
    "shoes" -> "Shoes"
    "party" -> "Party"
    "birthday" -> "Birthday"
    "vacation" -> "Vacation"
    "beauty" -> "Beauty"
    "cab" -> "Cab"
    "others" -> "Others"
    else -> key.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.take(9)
}

val categoryColorsList = listOf(
    "#FF9800" to Color(0xFFFF9800),  // Orange
    "#E91E63" to Color(0xFFE91E63),  // Pink
    "#0288D1" to Color(0xFF0288D1),  // Light Blue
    "#9C27B0" to Color(0xFF9C27B0),  // Purple
    "#FF5722" to Color(0xFFFF5722),  // Deep Orange
    "#4CAF50" to Color(0xFF4CAF50),  // Green
    "#009688" to Color(0xFF009688),  // Teal
    "#EC407A" to Color(0xFFEC407A),  // Pink Light
    "#00BCD4" to Color(0xFF00BCD4),  // Cyan
    "#795548" to Color(0xFF795548),  // Brown
    "#607D8B" to Color(0xFF607D8B),  // Blue Grey
    "#F44336" to Color(0xFFF44336),  // Red
    "#673AB7" to Color(0xFF673AB7),  // Deep Purple
    "#3F51B5" to Color(0xFF3F51B5),  // Indigo
    "#2196F3" to Color(0xFF2196F3),  // Blue
    "#00C853" to Color(0xFF00C853),  // Green Accent
    "#FFD600" to Color(0xFFFFD600),  // Yellow
    "#FF6D00" to Color(0xFFFF6D00),  // Deep Orange Accent
    "#00BFA5" to Color(0xFF00BFA5),  // Teal Accent
    "#AA00FF" to Color(0xFFAA00FF),  // Purple Accent
    "#2979FF" to Color(0xFF2979FF),  // Blue Accent
    "#D81B60" to Color(0xFFD81B60),  // Pink Dark
    "#43A047" to Color(0xFF43A047),  // Green Medium
    "#FB8C00" to Color(0xFFFB8C00),  // Orange Medium
    "#8D6E63" to Color(0xFF8D6E63),  // Brown Light
    "#26C6DA" to Color(0xFF26C6DA),  // Cyan Light
)

// 3. BUDGETS SCREEN
@Composable
fun BudgetsScreen(viewModel: FinanceViewModel) {
    val c = LocalAppColors.current
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
                                containerColor = c.divider,
                                contentColor = c.text
                            ),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                        }

                        Surface(
                            color = c.surface,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = formatDisplay.format(currentMonthDate),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = c.accent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = { viewModel.setMonthYear(shiftMonthYear(rawMonthYear, 1)) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = c.divider,
                                contentColor = c.text
                            ),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                        }
                    }

                    Button(
                        onClick = { showAddCategoryDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent.copy(alpha = 0.15f),
                            contentColor = c.accent
                        ),
                        border = BorderStroke(1.dp, c.accent.copy(alpha = 0.5f)),
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
                    .background(c.text.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                val isExp = activeCategoryTypeTab == "EXPENSE"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isExp) c.accent.copy(alpha = 0.15f) else Color.Transparent)
                        .border(1.dp, if (isExp) c.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { activeCategoryTypeTab = "EXPENSE" }
                        .padding(vertical = 10.dp)
                        .testTag("categories_tab_expense"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Expense Categories", color = if (isExp) c.accent else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isExp) c.accent.copy(alpha = 0.15f) else Color.Transparent)
                        .border(1.dp, if (!isExp) c.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { activeCategoryTypeTab = "INCOME" }
                        .padding(vertical = 10.dp)
                        .testTag("categories_tab_income"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Income Categories", color = if (!isExp) c.accent else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                val accentColor = if (overBudget) c.expense else budgetProgressColor(percent, c)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    accentColor.copy(alpha = 0.18f),
                                    c.surface
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
                                    color = c.textSecondary
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
                                    color = c.textSecondary
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
                                    .background(c.divider)
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
                                    color = c.expense,
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
                    val incColor = if (overTarget) c.income else c.accent

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(incColor.copy(alpha = 0.18f), c.surface)
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
                                        color = c.textSecondary
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
                                        color = c.textSecondary
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(c.divider)
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
                                    color = c.income,
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
                color = c.textSecondary
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
                        color = c.accent, modifier = Modifier.padding(vertical = 4.dp)
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
                    val progressColor = budgetProgressColor(percent, c)
                    val remaining = limit - catSpend
                    Surface(
                        color = if (isDragging) Color(0xFF1E3048) else c.surface,
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
                                        Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.text,
                                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("${compactCurrency(catSpend)} / ${compactCurrency(limit)}", fontSize = 13.sp, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
                                        Box {
                                            IconButton(onClick = { showCategoryMenuFor = cat.name }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = c.textSecondary, modifier = Modifier.size(16.dp))
                                            }
                                            DropdownMenu(
                                                expanded = showCategoryMenuFor == cat.name,
                                                onDismissRequest = { showCategoryMenuFor = null },
                                                containerColor = c.border
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(if (activeCategoryTypeTab == "INCOME") "Edit Expected Amount" else "Edit Budget", color = c.text, fontSize = 13.sp) },
                                                    onClick = { showBudgetAmountDialog = cat; showCategoryMenuFor = null }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Remove Budget", color = c.expense, fontSize = 13.sp) },
                                                    onClick = { viewModel.deleteBudget(budgetObj.id); showCategoryMenuFor = null }
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Remaining: ${compactCurrency(remaining)}",
                                        fontSize = 12.sp,
                                        color = if (remaining < 0) c.expense else c.income
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LinearProgressIndicator(
                                            progress = ratio.toFloat().coerceIn(0f, 1f),
                                            color = progressColor,
                                            trackColor = c.text.copy(alpha = 0.05f),
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
                        color = c.textSecondary, modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                unbudgetedCats.forEach { cat ->
                    val catIncome = txs.filter {
                        val txMonth = sdfMonth.format(Date(it.timestamp))
                        txMonth == rawMonthYear && it.type == "INCOME" && it.category.equals(cat.name, ignoreCase = true)
                    }.sumOf { it.amount }
                    Surface(
                        color = c.surface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, c.border),
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
                                    Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (catIncome > 0) {
                                        Text("${compactCurrency(catIncome)} received", fontSize = 12.sp, color = c.income, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.text,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { showBudgetAmountDialog = cat },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(if (activeCategoryTypeTab == "INCOME") "Set Expected" else "Set Budget", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // "Copy from last month" — shown below unbudgeted list, only for EXPENSE tab
                if (unbudgetedCats.isNotEmpty() && activeCategoryTypeTab == "EXPENSE") {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.copyBudgetsFromPreviousMonth() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary),
                        border = BorderStroke(1.dp, c.border),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy budgets from last month", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
            title = { Text("Edit Category", fontWeight = FontWeight.Bold, color = c.text, fontSize = 16.sp) },
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
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            focusedBorderColor = c.accent,
                            focusedLabelColor = c.accent,
                            unfocusedBorderColor = c.textTertiary,
                            unfocusedLabelColor = c.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("edit_category_name_field")
                    )

                    Text("Select Icon", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(58.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                    ) {
                        items(suitableIconsList) { (iconName, iconVec) ->
                            val isSelected = selectedIconName == iconName
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedIconName = iconName }
                                    .padding(vertical = 4.dp)
                                    .testTag("edit_icon_$iconName")
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) selectedColor.copy(alpha = 0.25f) else c.text.copy(alpha = 0.05f),
                                    border = BorderStroke(if (isSelected) 2.dp else 0.dp, if (isSelected) selectedColor else Color.Transparent),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = iconVec,
                                            contentDescription = iconName,
                                            tint = if (isSelected) selectedColor else c.text.copy(alpha = 0.7f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = iconLabel(iconName),
                                    fontSize = 7.sp,
                                    color = if (isSelected) selectedColor else c.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Text("Select Color", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categoryColorsList) { (hexStr, colorObj) ->
                            val isSelected = selectedColorHex == hexStr
                            Surface(
                                shape = CircleShape,
                                color = colorObj,
                                border = BorderStroke(2.dp, if (isSelected) c.text else Color.Transparent),
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
                                            tint = c.text,
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
                        colors = ButtonDefaults.buttonColors(containerColor = c.expense, contentColor = c.text),
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
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
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
                    Text("Cancel", color = c.text)
                }
            },
            containerColor = c.surface
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
                    color = c.text,
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
                        color = c.textSecondary
                    )
                    OutlinedTextField(
                        value = budgetValStr,
                        onValueChange = { budgetValStr = it },
                        label = { Text(if (cat.type == "INCOME") "Expected Amount (₹)" else "Budget Limit (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            focusedBorderColor = c.accent,
                            focusedLabelColor = c.accent,
                            unfocusedBorderColor = c.textTertiary,
                            unfocusedLabelColor = c.textSecondary
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
                            colors = ButtonDefaults.textButtonColors(contentColor = c.expense)
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
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                    ) {
                        Text(if (checkCurrent != null) "Update" else "Set Budget", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetAmountDialog = null }) { Text("Cancel", color = c.text) }
            },
            containerColor = c.surface
        )
    }

    if (showAddCategoryDialog) {
        var newCatName by remember { mutableStateOf("") }
        var selectedIconName by remember { mutableStateOf("restaurant") }
        var selectedColorHex by remember { mutableStateOf("#00E5FF") }
        var selectedColor by remember { mutableStateOf(c.accent) }
        var selectedType by remember { mutableStateOf("EXPENSE") }
        var initialBudgetAmt by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = {
                Text(
                    "Add New Category",
                    fontWeight = FontWeight.Bold,
                    color = c.text,
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
                            focusedTextColor = c.text,
                            unfocusedTextColor = c.text,
                            focusedBorderColor = c.accent,
                            focusedLabelColor = c.accent,
                            unfocusedBorderColor = c.textTertiary,
                            unfocusedLabelColor = c.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("add_category_name_field")
                    )

                    Text("Category Type", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.text.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .padding(4.dp)
                    ) {
                        val isExp = selectedType == "EXPENSE"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isExp) c.expense else Color.Transparent)
                                .clickable { selectedType = "EXPENSE" }
                                .padding(8.dp)
                                .testTag("type_selector_expense"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Expense", color = if (isExp) c.text else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isExp) c.income else Color.Transparent)
                                .clickable { selectedType = "INCOME" }
                                .padding(8.dp)
                                .testTag("type_selector_income"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Income", color = if (!isExp) c.text else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (selectedType == "EXPENSE") {
                        OutlinedTextField(
                            value = initialBudgetAmt,
                            onValueChange = { initialBudgetAmt = it },
                            label = { Text("Monthly Budget Limit (₹, Optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = c.text,
                                unfocusedTextColor = c.text,
                                focusedBorderColor = c.accent,
                                focusedLabelColor = c.accent,
                                unfocusedBorderColor = c.textTertiary,
                                unfocusedLabelColor = c.textSecondary
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("add_category_budget_field")
                        )
                    }

                    Text("Select Icon", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(58.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                    ) {
                        items(suitableIconsList) { (iconName, iconVec) ->
                            val isSelected = selectedIconName == iconName
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedIconName = iconName }
                                    .padding(vertical = 4.dp)
                                    .testTag("add_icon_$iconName")
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) selectedColor.copy(alpha = 0.25f) else c.text.copy(alpha = 0.05f),
                                    border = BorderStroke(if (isSelected) 2.dp else 0.dp, if (isSelected) selectedColor else Color.Transparent),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = iconVec,
                                            contentDescription = iconName,
                                            tint = if (isSelected) selectedColor else c.text.copy(alpha = 0.7f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = iconLabel(iconName),
                                    fontSize = 7.sp,
                                    color = if (isSelected) selectedColor else c.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Text("Select Color", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(categoryColorsList) { (hexStr, colorObj) ->
                            val isSelected = selectedColorHex == hexStr
                            Surface(
                                shape = CircleShape,
                                color = colorObj,
                                border = BorderStroke(2.dp, if (isSelected) c.text else Color.Transparent),
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
                                            tint = c.text,
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
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
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
                    Text("Cancel", color = c.text)
                }
            },
            containerColor = c.surface
        )
    }
}

// 4. ACCOUNTS / WALLETS MANAGEMENT SCREEN
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(viewModel: FinanceViewModel) {
    val c = LocalAppColors.current
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val carryOverPreviousAmount by viewModel.carryOverPreviousAmount.collectAsStateWithLifecycle()
    val showTotal by viewModel.showTotal.collectAsStateWithLifecycle()
    val blockedSmsAccountIds by viewModel.blockedSmsAccountIds.collectAsStateWithLifecycle()
    val blockedAccountNames by viewModel.blockedAccountNames.collectAsStateWithLifecycle()
    val showCreditCardDetails by viewModel.showCreditCardDetails.collectAsStateWithLifecycle()
    val hiddenAccountIds by viewModel.hiddenAccountIds.collectAsStateWithLifecycle()
    val smsBlocklistPatterns by viewModel.smsBlocklistPatterns.collectAsStateWithLifecycle()
    val walletsBalances = computeWalletBalances(txs, accounts, carryOverPreviousAmount)

    var showTransferDialog by remember { mutableStateOf(false) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var selectedAccountForEdit by remember { mutableStateOf<Account?>(null) }
    var showAccountCenterSettings by remember { mutableStateOf(false) }
    var showAllAccountsInfo by remember { mutableStateOf(false) }

    val decFormat = DecimalFormat("₹#,##0.00")

    val activeAccounts = accounts.filter { !hiddenAccountIds.contains(it.id) }
    val orderedAccounts = activeAccounts

    val totalAllAccounts = activeAccounts.sumOf { acc ->
        // CC with Limit-Based Balance ON: contribute outstanding debt (avail - limit, negative = owed).
        // All others: use the transaction-based wallet balance.
        if (acc.type == "CREDIT_CARD" && acc.showCreditLimitBalance && acc.creditLimit > 0)
            acc.availableLimit - acc.creditLimit
        else
            walletsBalances[acc.name] ?: 0.0
    }
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
                    color = c.text
                )
                IconButton(onClick = { showAccountCenterSettings = true }) {
                    Icon(Icons.Default.Menu, contentDescription = "Account Settings", tint = c.text)
                }
            }
            if (showAccountCenterSettings) {
                AccountCenterSettingsDialog(
                    viewModel = viewModel,
                    accounts = accounts,
                    hiddenAccountIds = hiddenAccountIds,
                    showCreditCardDetails = showCreditCardDetails,
                    smsBlocklistPatterns = smsBlocklistPatterns,
                    blockedSmsAccountIds = blockedSmsAccountIds,
                    blockedAccountNames = blockedAccountNames,
                    onDismiss = { showAccountCenterSettings = false }
                )
            }
        }

        // Net Wealth Overview Card
        item {
            Surface(
                color = c.surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.2.dp, c.accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ALL ACCOUNTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = c.textSecondary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "How is this calculated?",
                            tint = c.textSecondary.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(13.dp)
                                .clickable { showAllAccountsInfo = true }
                        )
                    }
                    if (showAllAccountsInfo) {
                        AlertDialog(
                            onDismissRequest = { showAllAccountsInfo = false },
                            title = { Text("All Accounts Balance", fontWeight = FontWeight.Bold, color = c.text) },
                            text = {
                                Text(
                                    "Shows the combined balance across all your visible accounts.\n\n" +
                                    "Each account's balance is computed from its full transaction history, anchored by any manual balance snapshots you've set.\n\n" +
                                    "For Credit Card accounts with 'Limit-Based Balance' toggled ON, the outstanding debt (Available Limit − Credit Limit, which is negative) is included — so your total net worth correctly decreases by the amount you owe.",
                                    color = c.text, fontSize = 13.sp
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showAllAccountsInfo = false }) {
                                    Text("Got it", color = c.accent, fontWeight = FontWeight.Bold)
                                }
                            },
                            containerColor = c.surface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (showTotal) decFormat.format(totalAllAccounts) else "₹ ••••",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 28.sp,
                        color = c.accent
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Income so far", fontSize = 11.sp, color = c.textSecondary)
                            Text(if (showTotal) decFormat.format(totalIncomeSoFar) else "₹ ••••", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.income)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Expense so far", fontSize = 11.sp, color = c.textSecondary)
                            Text(if (showTotal) decFormat.format(totalExpenseSoFar) else "₹ ••••", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.expense)
                        }
                    }
                }
            }
        }

        // Actions: inter-wallet transfer setup
        item {
            Button(
                onClick = { showTransferDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = c.accent.copy(alpha = 0.15f), contentColor = c.accent),
                border = BorderStroke(1.2.dp, c.accent),
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
                    text = "MANAGE ACCOUNTS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = c.textSecondary
                )
                TextButton(
                    onClick = { showAddAccountDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = c.accent)
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
                    color = c.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, c.border),
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
                            tint = c.textTertiary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No wallets added yet",
                            color = c.text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Add a wallet to track real balances. No default balances are created automatically.",
                            color = c.textSecondary,
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    orderedAccounts.forEach { acc ->
                        val bal = walletsBalances[acc.name] ?: 0.0
                        val smsTrackingBlocked = blockedSmsAccountIds.contains(acc.id)
                        val color = when(acc.type) {
                            "CASH" -> c.income
                            "BANK" -> c.accent
                            "CREDIT_CARD" -> c.expense
                            "WALLET" -> Color(0xFFFF9800)
                            else -> Color(0xFF94A3B8)
                        }

                        Surface(
                            color = c.surface,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, c.border),
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
                                        color = c.text
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
                                    // When Limit-Based Balance is ON, use (availableLimit - creditLimit):
                                    //   negative = outstanding debt (show red)
                                    //   zero/positive = fully paid / cashback (show green)
                                    // Otherwise use the transaction-based running balance.
                                    val useLimitBalance = acc.type == "CREDIT_CARD"
                                        && acc.showCreditLimitBalance
                                        && acc.creditLimit > 0
                                    val primaryBal = if (useLimitBalance) acc.availableLimit - acc.creditLimit else bal
                                    Text(
                                        decFormat.format(primaryBal),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 16.sp,
                                        color = if (primaryBal >= 0) c.income else c.expense
                                    )
                                    // acc.availableLimit is the single source of truth:
                                    // set directly from every CC Payment or CC Summary SMS,
                                    // delta-adjusted by adjustCcAvailableLimit on new transactions.
                                    // This is robust even when Balance Sync is missing/stale.
                                    if (showCreditCardDetails && acc.type == "CREDIT_CARD" && acc.availableLimit > 0) {
                                        Text(
                                            text = "Avail: ${decFormat.format(acc.availableLimit)}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.accent
                                        )
                                        if (!useLimitBalance && acc.creditLimit > 0) {
                                            val outstanding = (acc.creditLimit - acc.availableLimit).coerceAtLeast(0.0)
                                            Text(
                                                text = "Due: ${decFormat.format(outstanding)}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFE05A00)
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
        var trTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

        val supportWallets = activeAccounts.map { it.name }

        AlertDialog(
            onDismissRequest = { showTransferDialog = false },
            title = { Text("Transfer Funds", fontWeight = FontWeight.Bold, color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Move funds between your accounts. Creates a single Transfer entry.", fontSize = 11.sp, color = c.textSecondary)
                    
                    OutlinedTextField(
                        value = trAmount,
                        onValueChange = { trAmount = it },
                        label = { Text("Transfer Amount (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    TransactionDateTimePicker(
                        selectedTimestamp = trTimestamp,
                        onTimestampChange = { trTimestamp = it }
                    )

                    // Source Select Dropdown
                    Column {
                        Text("FROM SOURCE WALLET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(supportWallets.size) { i ->
                                val w = supportWallets[i]
                                val sel = sourceWall == w
                                Box(
                                    modifier = Modifier
                                        .background(if (sel) c.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) c.accent else c.text.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { sourceWall = w }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = w,
                                        fontSize = 11.sp,
                                        color = if (sel) c.accent else c.text,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }

                    // Dest Select Dropdown
                    Column {
                        Text("TO DESTINATION WALLET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(supportWallets.size) { i ->
                                val w = supportWallets[i]
                                val sel = destWall == w
                                Box(
                                    modifier = Modifier
                                        .background(if (sel) c.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) c.accent else c.text.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { destWall = w }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = w,
                                        fontSize = 11.sp,
                                        color = if (sel) c.accent else c.text,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2
                                    )
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
                            // Single TRANSFER entry with [Acc: SOURCE][To: DEST] note
                            viewModel.addTransaction(
                                title = "Transfer",
                                amount = dVal,
                                categoryName = "TRANSFER",
                                type = "TRANSFER",
                                note = "[Acc: $sourceWall][To: $destWall]",
                                timestamp = trTimestamp
                            )
                        }
                        showTransferDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                ) {
                    Text("Execute Transfer", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTransferDialog = false }) { Text("Cancel", color = c.text) }
            },
            containerColor = c.surface
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
            title = { Text("Create New Wallet", fontWeight = FontWeight.Bold, color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Account Name (e.g. SBI Bank)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = baseBalanceInput,
                        onValueChange = { baseBalanceInput = it },
                        label = { Text("Initial Ledger Balance (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
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
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column {
                        Text("ACCOUNT CATEGORY / TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            types.forEach { t ->
                                val sel = typeInput == t
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (sel) c.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) c.accent else c.text.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { typeInput = t }
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(t.replace("_", " "), fontSize = 9.sp, color = if (sel) c.accent else c.text, fontWeight = FontWeight.Bold)
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
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                ) {
                    Text("Provision Wallet", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAccountDialog = false }) { Text("Cancel", color = c.text) }
            },
            containerColor = c.surface
        )
    }

    // Dialog: Adjust / Edit / Delete Account Dialog
    selectedAccountForEdit?.let { acc ->
        val computedBal = walletsBalances[acc.name] ?: acc.balance
        var editName by remember(acc) { mutableStateOf(acc.name) }
        var editBalanceInput by remember(acc) { mutableStateOf(String.format("%.2f", computedBal)) }
        var editType by remember(acc) { mutableStateOf(acc.type) }
        var editCreditLimit by remember(acc) { mutableStateOf(if (acc.creditLimit > 0) acc.creditLimit.toString() else "") }
        var editShowCreditLimitBalance by remember(acc) { mutableStateOf(acc.showCreditLimitBalance) }

        val types = listOf("CASH", "BANK", "CREDIT_CARD", "WALLET")

        AlertDialog(
            onDismissRequest = { selectedAccountForEdit = null },
            title = { Text("Edit Account", fontWeight = FontWeight.Bold, color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(
                        color = c.accent.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, c.accent.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Current Balance", fontSize = 12.sp, color = c.textSecondary)
                            Text(
                                text = "₹${String.format("%.2f", computedBal)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.accent
                            )
                        }
                    }

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Account Name") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editBalanceInput,
                        onValueChange = { editBalanceInput = it },
                        label = { Text("Set New Balance (₹)") },
                        placeholder = { Text("e.g. 25000", color = c.text.copy(0.4f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (editType == "CREDIT_CARD") {
                        OutlinedTextField(
                            value = editCreditLimit,
                            onValueChange = { editCreditLimit = it },
                            label = { Text("Credit Limit (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.surface)
                                .border(1.dp, c.divider, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Limit-Based Balance", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                                Text("Show Avail. Limit − Credit Limit as main balance", fontSize = 10.sp, color = c.textSecondary)
                            }
                            Switch(
                                checked = editShowCreditLimitBalance,
                                onCheckedChange = { editShowCreditLimitBalance = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = c.accent,
                                    checkedTrackColor = c.accent.copy(alpha = 0.45f),
                                    uncheckedThumbColor = c.text,
                                    uncheckedTrackColor = c.textTertiary
                                )
                            )
                        }
                    }
                    Column {
                        Text("ACCOUNT CATEGORY / TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            types.forEach { t ->
                                val sel = editType == t
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (sel) c.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (sel) c.accent else c.text.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .clickable { editType = t }
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(t.replace("_", " "), fontSize = 9.sp, color = if (sel) c.accent else c.text, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Column {
                    // Cancel + Delete (destructive) — Cancel is compact so Delete Account doesn't wrap
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { selectedAccountForEdit = null },
                            border = BorderStroke(1.dp, c.divider),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                viewModel.deleteAccount(acc.id)
                                selectedAccountForEdit = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = c.expense, contentColor = Color.White)
                        ) {
                            Text("Delete Account", fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Row 2: Save Changes (primary action, full width)
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
                                    lastFour = editName.filter { it.isDigit() }.takeLast(4).ifBlank { null },
                                    creditLimit = editCreditLimit.toDoubleOrNull() ?: acc.creditLimit,
                                    showCreditLimitBalance = editShowCreditLimitBalance
                                )
                            )

                            if (oldName != editName) {
                                viewModel.renameAccountTransactions(oldName, editName)
                            }
                            selectedAccountForEdit = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                    ) {
                        Text("Save Changes", fontWeight = FontWeight.Bold)
                    }
                }   // end Column (confirmButton)
            },
            dismissButton = {},
            containerColor = c.surface
        )
    }
}

// 5. AUTO-SCAN SMS UTILITY HUB
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoScanHubScreen(viewModel: FinanceViewModel) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    var manualSmsSender by remember { mutableStateOf("") }
    var manualSmsBody by remember { mutableStateOf("") }
    var customPatternInput by remember { mutableStateOf("") }
    val forcePatternOptions = listOf("txn", "debited", "credited", "spent", "paid", "received", "upi", "card", "account", "salary")
    val suggestedForcePatterns = remember(manualSmsBody) {
        val lowerBody = manualSmsBody.lowercase()
        forcePatternOptions.filter { lowerBody.contains(it) }
    }
    // Default: only strict transactional verbs. "upi" and "card" are opt-in (too broad on their own).
    val defaultSelectedPatterns = setOf("txn", "debited", "credited", "spent", "paid", "received", "account", "salary")
    var selectedForcePatterns by remember { mutableStateOf(defaultSelectedPatterns) }
    var customPatterns by remember { mutableStateOf(emptyList<String>()) }
    // 1 = this month only, 2 = last + this month, 3 = last 3 months (calendar start-of-month boundaries)
    var smsScanMonthsBack by remember { mutableStateOf(1) }
    val isSmsParsing by viewModel.isSmsParsing.collectAsStateWithLifecycle()
    val isWalletPfScanning by viewModel.isWalletPfScanning.collectAsStateWithLifecycle()
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
            Toast.makeText(context, "SMS access enabled. Tap \"Scan Inbox\" to import messages.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "SMS read permission is required for inbox import.", Toast.LENGTH_SHORT).show()
        }
    }
    val merchantRules by viewModel.merchantCategoryRules.collectAsStateWithLifecycle()
    val customCatsForMerchant by viewModel.allCustomCategories.collectAsStateWithLifecycle()
    // Build via CategoryResolver.getAll so custom overrides of standard categories appear only
    // once (with the correct display name), not as both a standard entry AND a custom entry.
    val allMerchantCategoryOptions = remember(customCatsForMerchant) {
        CategoryResolver.getAll(customCatsForMerchant)
            .filter { it.type == "EXPENSE" }
            .map { it.name to it.displayName }
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
                color = c.text
            )
        }

        // Device Scan Action panel
        item {
            Surface(
                color = c.surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, c.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Security badge",
                        tint = c.accent,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Secure Offline Automated Scanner", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.text)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Reads your messages locally using regex pattern matching and/or local AI. No financial or personal data ever leaves your device.",
                        fontSize = 11.sp,
                        color = c.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Permission Status", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.text)
                        Surface(
                            color = if (hasSmsPermissions) c.income.copy(alpha = 0.15f) else c.expense.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (hasSmsPermissions) "GRANTED" else "REQUIRED",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasSmsPermissions) c.income else c.expense,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scan range selector
                    Text("Scan Range", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    val scanRangeOptions = listOf(1 to "This Month", 2 to "Last 2 Months", 3 to "Last 3 Months")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        scanRangeOptions.forEach { (months, label) ->
                            val selected = smsScanMonthsBack == months
                            Surface(
                                onClick = { smsScanMonthsBack = months },
                                color = if (selected) c.accent.copy(alpha = 0.15f) else c.border,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (selected) c.accent else Color(0xFF2D3748)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) c.accent else c.textSecondary,
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
                            enabled = !isSmsParsing,
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("scan_device_sms_button")
                        ) {
                            if (isSmsParsing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = c.bg,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Scanning…", fontWeight = FontWeight.Bold)
                            } else {
                                Text(
                                    if (hasReadSmsPermission) "Scan Inbox" else "Enable Auto-Import",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        // Bal Sync toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "Bal\nSync",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = c.textSecondary,
                                textAlign = TextAlign.End
                            )
                            Switch(
                                checked = enableBalanceSync,
                                onCheckedChange = { viewModel.setEnableBalanceSync(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = c.accent,
                                    checkedTrackColor = c.accent.copy(alpha = 0.35f),
                                    uncheckedThumbColor = c.textSecondary,
                                    uncheckedTrackColor = c.divider
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Wallets & PF scan button
                    OutlinedButton(
                        onClick = {
                            if (hasReadSmsPermission) {
                                viewModel.scanWalletsPfInbox(context, smsScanMonthsBack)
                            } else {
                                requestSmsLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                            }
                        },
                        enabled = !isWalletPfScanning,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                        border = BorderStroke(1.dp, c.accent.copy(alpha = if (isWalletPfScanning) 0.3f else 0.6f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        if (isWalletPfScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = c.accent,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Scanning…", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (hasReadSmsPermission) "Scan Wallets & PF" else "Enable Auto-Import",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
        // Manual pasted SMS analyzer
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = c.surface),
                border = BorderStroke(1.dp, c.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        "PARSER KEY RULES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = c.textSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Only SMS from senders ending in -S or -T (verified bank/fintech format) are processed — this rule is always enforced.\n" +
                        "Select core keys and/or add custom patterns to filter the SMS body:\n" +
                        "  balance  →  any SMS containing \"balance\"\n" +
                        "  (Avl bal)  →  exact phrase \"Avl bal\"\n" +
                        "  !(salary)  →  skip SMS containing \"salary\"\n" +
                        "  !(salary credited)  →  skip SMS containing \"salary credited\"\n" +
                        "Hit \"Scan Inbox with These Rules\" to apply globally.",
                        fontSize = 11.sp,
                        color = c.textSecondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Parser keys (must contain at least one)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textSecondary
                    )
                    if (suggestedForcePatterns.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Suggested from pasted SMS: ${suggestedForcePatterns.joinToString(", ")}",
                            fontSize = 10.sp,
                            color = c.accent
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
                                    selectedContainerColor = c.accent.copy(alpha = 0.18f),
                                    selectedLabelColor = c.accent,
                                    selectedLeadingIconColor = c.accent,
                                    containerColor = c.divider,
                                    labelColor = c.text
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selectedForcePatterns.contains(pattern),
                                    borderColor = c.divider,
                                    selectedBorderColor = c.accent.copy(alpha = 0.45f)
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
                            placeholder = { Text("balance | !(salary)") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
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
                            border = BorderStroke(1.dp, c.accent.copy(alpha = 0.45f))
                        ) {
                            Text("Add", color = c.accent, fontWeight = FontWeight.Bold)
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
                                        selectedContainerColor = c.income.copy(alpha = 0.15f),
                                        selectedLabelColor = c.income,
                                        selectedTrailingIconColor = c.income
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF), contentColor = c.text),
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
                    HorizontalDivider(color = c.divider)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        "PASTE MISSED SMS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = c.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = manualSmsSender,
                        onValueChange = { manualSmsSender = it },
                        label = { Text("Sender ID") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = manualSmsBody,
                        onValueChange = { manualSmsBody = it },
                        label = { Text("Real SMS Body") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.analyzePastedSms(manualSmsBody, manualSmsSender, selectedForcePatterns.toList(), customPatterns) },
                        enabled = manualSmsBody.isNotBlank() && !isSmsParsing,
                        colors = ButtonDefaults.buttonColors(containerColor = c.income, contentColor = c.bg),
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
                colors = CardDefaults.cardColors(containerColor = c.surface),
                border = BorderStroke(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Category, contentDescription = null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(20.dp))
                        Text("Merchant → Category Rules", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
                    }
                    Text(
                        "Map payee/merchant names to categories. Supports wildcards:\n" +
                        "  gmart*    →  starts with\n" +
                        "  *paytm*   →  contains\n" +
                        "  *nova     →  ends with\n" +
                        "  (no *)    →  contains (same as *text*)\n" +
                        "Rules here override built-in categorization.",
                        fontSize = 10.sp, color = c.textSecondary, lineHeight = 16.sp
                    )

                    // Input row
                    OutlinedTextField(
                        value = merchantPatternInput,
                        onValueChange = { merchantPatternInput = it },
                        label = { Text("Merchant Pattern (e.g. *paytm*)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text,
                            focusedBorderColor = Color(0xFF7C4DFF), unfocusedBorderColor = Color(0xFF2D3748),
                            focusedLabelColor = Color(0xFF7C4DFF), unfocusedLabelColor = c.textSecondary
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
                                focusedTextColor = c.text, unfocusedTextColor = c.text,
                                focusedBorderColor = Color(0xFF7C4DFF), unfocusedBorderColor = Color(0xFF2D3748),
                                focusedLabelColor = Color(0xFF7C4DFF), unfocusedLabelColor = c.textSecondary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = merchantCategoryDropdownExpanded,
                            onDismissRequest = { merchantCategoryDropdownExpanded = false },
                            modifier = Modifier.background(c.border).heightIn(max = 280.dp)
                        ) {
                            allMerchantCategoryOptions.forEach { (catName, catDisplay) ->
                                DropdownMenuItem(
                                    text = { Text(catDisplay, fontSize = 13.sp, color = c.text) },
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF), contentColor = c.text),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Rule", fontWeight = FontWeight.Bold)
                    }

                    // Existing rules list
                    if (merchantRules.isNotEmpty()) {
                        HorizontalDivider(color = c.divider)
                        Text("Active Rules (${merchantRules.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                        merchantRules.forEach { (pattern, category) ->
                            Surface(
                                color = c.bg,
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
                                        Text(pattern, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
                                        Text("→ $category", fontSize = 11.sp, color = c.textSecondary)
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeMerchantCategoryRule(pattern) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = c.expense, modifier = Modifier.size(16.dp))
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
                colors = CardDefaults.cardColors(containerColor = c.surface),
                border = BorderStroke(1.dp, c.expense.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "PARSER EXCLUSION & INCLUSION RULES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = c.expense.copy(alpha = 0.8f)
                    )
                    Text(
                        "These are the hard-coded rules the parser uses. Messages matching ANY exclusion word are rejected. Messages missing ALL inclusion words are also rejected.",
                        fontSize = 10.sp,
                        color = c.textSecondary
                    )

                    // EXCLUSION keywords (SmsFilterUtility hard-rejects)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AUTO-REJECT if body contains:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.expense)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("due", "emi", "loan", "otp", "mandate", "load", "eligibility", "apply", "approved").forEach { kw ->
                                Surface(color = c.expense.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
                                    Text(kw, fontSize = 10.sp, color = c.expense, fontWeight = FontWeight.SemiBold,
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
                        Text("REQUIRED — must contain at least one:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.income)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("debited", "credited", "spent", "received", "deducted", "sent", "paid", "withdrawn",
                                "transfer", "payment", "charge", "txn", "salary", "refund", "autopay").forEach { kw ->
                                Surface(color = c.income.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                                    Text(kw, fontSize = 10.sp, color = c.income, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    // Structural requirement
                    Surface(color = c.divider, shape = RoundedCornerShape(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CreditCard, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                            Text("Must contain a 3–4 digit account/card reference (e.g. a/c xx1234, card ending 4321, acc 567)",
                                fontSize = 10.sp, color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

// 6. POPUP DIALOGS & UTILS
/** Evaluate a left-to-right arithmetic expression using +, -, ×, ÷. Returns null on error. */
private fun evalCalcExpr(expr: String): Double? {
    if (expr.isBlank()) return null
    // Split keeping the operator attached to the following token
    val segments = Regex("(?<=[0-9.])[+\\-×÷]").split(expr)
    val ops = Regex("(?<=[0-9.])[+\\-×÷]").findAll(expr).map { it.value }.toList()
    if (segments.isEmpty()) return null
    var result = segments[0].toDoubleOrNull() ?: return null
    for (i in ops.indices) {
        val next = segments.getOrNull(i + 1)?.toDoubleOrNull() ?: return null
        result = when (ops[i]) {
            "+" -> result + next
            "-" -> result - next
            "×" -> result * next
            "÷" -> if (next != 0.0) result / next else return null
            else -> return null
        }
    }
    return result
}

/** Format a calculator result: no trailing .00 for whole numbers. */
private fun formatCalcNum(d: Double): String =
    if (d == kotlin.math.floor(d) && d < 1_000_000_000.0) d.toLong().toString()
    else "%.2f".format(d)

@Composable
fun AddTransactionDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, String, String, String?, Long) -> Unit
) {
    val c = LocalAppColors.current
    var title by remember { mutableStateOf("") }
    var calcExpr by remember { mutableStateOf("") }
    var transactionType by remember { mutableStateOf("EXPENSE") }
    var categorySelection by remember { mutableStateOf("FOOD") }
    var accountSelection by remember { mutableStateOf("") }
    var notesStr by remember { mutableStateOf("") }
    var selectedTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    var showWalletPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showQuickAddAccount by remember { mutableStateOf(false) }
    var showQuickAddCategory by remember { mutableStateOf(false) }

    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    val allCategories = CategoryResolver.getAll(customCats)
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val selectablesWallets = accounts.map { it.name }

    LaunchedEffect(accounts) {
        if (accounts.isNotEmpty() && !accounts.any { it.name == accountSelection }) {
            accountSelection = accounts.first().name
        }
    }

    val filteredCats = if (transactionType == "EXPENSE") {
        allCategories.filter { it.type == "EXPENSE" }
    } else {
        allCategories.filter { it.type == "INCOME" }
    }
    val selectedCategory = filteredCats.firstOrNull { it.name == categorySelection }
        ?: allCategories.firstOrNull { it.name == categorySelection }

    // ── Calculator logic ──────────────────────────────────────────────────────
    val hasOp = calcExpr.any { it in listOf('+', '-', '×', '÷') }
    val endsWithOp = calcExpr.isNotEmpty() && calcExpr.last() in listOf('+', '-', '×', '÷')
    val calcResult: Double? = if (hasOp && !endsWithOp) evalCalcExpr(calcExpr) else null
    val resolvedAmount: Double = calcResult ?: calcExpr.toDoubleOrNull() ?: 0.0

    fun onCalcKey(key: String) {
        when (key) {
            "C"  -> calcExpr = ""
            "⌫"  -> if (calcExpr.isNotEmpty()) calcExpr = calcExpr.dropLast(1)
            "="  -> {
                val res = evalCalcExpr(calcExpr)
                if (res != null) calcExpr = formatCalcNum(res)
            }
            "+", "-", "×", "÷" -> {
                if (calcExpr.isEmpty()) return
                calcExpr = calcExpr.trimEnd { it in listOf('+', '-', '×', '÷') } + key
            }
            "."  -> {
                val lastNum = calcExpr.split(Regex("[+\\-×÷]")).last()
                if (!lastNum.contains('.')) calcExpr += "."
            }
            "00" -> {
                if (calcExpr.isEmpty()) return
                val lastNum = calcExpr.split(Regex("[+\\-×÷]")).last()
                if (lastNum.length < 9) calcExpr += "00"
            }
            else -> {
                val lastNum = calcExpr.split(Regex("[+\\-×÷]")).last()
                if (lastNum.length < 10) calcExpr += key
            }
        }
    }

    val amountColor = if (transactionType == "INCOME") c.income else c.expense

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = c.bg) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Top bar ────────────────────────────────────────────────────
                Surface(shadowElevation = 6.dp, color = c.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = c.text)
                        }
                        Text(
                            "Log Cashflow",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = c.text,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        TextButton(
                            onClick = {
                                if (resolvedAmount > 0.0) {
                                    val cleanTitle = if (title.isBlank()) "Merchant Log" else title
                                    onConfirm(
                                        cleanTitle, resolvedAmount, categorySelection,
                                        transactionType,
                                        makeNoteWithAccount(notesStr, accountSelection),
                                        selectedTimestamp
                                    )
                                }
                            }
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold, color = c.accent, fontSize = 15.sp)
                        }
                    }
                }

                // ── Scrollable fields ──────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Type selector: EXPENSE / INCOME
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.text.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .padding(4.dp)
                    ) {
                        listOf("EXPENSE" to "Expense", "INCOME" to "Income").forEach { (type, label) ->
                            val sel = transactionType == type
                            val selColor = if (type == "EXPENSE") c.expense else c.income
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) selColor else Color.Transparent)
                                    .clickable {
                                        transactionType = type
                                        categorySelection = if (type == "EXPENSE") "FOOD" else "SALARY"
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (sel) c.text else c.textSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // Payee / Merchant
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Payee / Merchant") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Date & Time
                    TransactionDateTimePicker(
                        selectedTimestamp = selectedTimestamp,
                        onTimestampChange = { selectedTimestamp = it }
                    )

                    // Wallet
                    PickerButton(
                        label = "Wallet",
                        title = accountSelection.ifBlank { "Select Wallet" },
                        icon = walletIconFor(accountSelection, accounts.find { it.name == accountSelection }?.type),
                        tint = c.accent,
                        onClick = { showWalletPicker = true }
                    )

                    // Category
                    PickerButton(
                        label = "Category",
                        title = selectedCategory?.displayName ?: "Choose category",
                        icon = selectedCategory?.icon ?: Icons.Default.Category,
                        tint = selectedCategory?.color ?: c.accent,
                        onClick = { showCategoryPicker = true }
                    )

                    // Notes
                    OutlinedTextField(
                        value = notesStr,
                        onValueChange = { notesStr = it },
                        label = { Text("Notes (Optional)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3
                    )
                }

                // ── Amount display (calculator screen) ─────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        if (hasOp) {
                            Text(
                                text = calcExpr,
                                fontSize = 15.sp,
                                color = c.textSecondary,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = when {
                                calcResult != null -> "₹ ${formatCalcNum(calcResult)}"
                                calcExpr.isEmpty() -> "₹ 0"
                                else               -> "₹ $calcExpr"
                            },
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = amountColor,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // ⌫ backspace button anchored to display row
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.accent.copy(alpha = 0.12f))
                            .clickable { onCalcKey("⌫") },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⌫", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.accent)
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = c.text.copy(alpha = 0.10f))

                // ── Calculator keypad ──────────────────────────────────────────
                // Left column: operators (5 rows) | Right: 3 digit columns (4 rows) + = row
                // All cells use the same border to give a uniform grid appearance.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                ) {
                    // Left column: +  -  ×  ÷  C  (5 rows, same height as right)
                    Column(modifier = Modifier.weight(1.15f)) {
                        listOf("+", "-", "×", "÷", "C").forEach { op ->
                            val isC = op == "C"
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(57.dp)
                                    .clickable { onCalcKey(op) }
                                    .background(
                                        if (isC) MaterialTheme.colorScheme.error.copy(alpha = 0.13f)
                                        else c.accent.copy(alpha = 0.11f)
                                    )
                                    .border(0.5.dp, c.text.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = op,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isC) MaterialTheme.colorScheme.error else c.accent
                                )
                            }
                        }
                    }

                    // Right grid: 3 digit columns × 4 rows, then full-width =
                    val numRows = listOf(
                        listOf("7", "8", "9"),
                        listOf("4", "5", "6"),
                        listOf("1", "2", "3"),
                        listOf("00", "0", "."),
                    )
                    Column(modifier = Modifier.weight(3f)) {
                        numRows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(57.dp)
                            ) {
                                row.forEach { key ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable { onCalcKey(key) }
                                            .background(Color.Transparent)
                                            .border(0.5.dp, c.text.copy(alpha = 0.08f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = c.text
                                        )
                                    }
                                }
                            }
                        }
                        // = button — full width, accent fill
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(57.dp)
                                .clickable { onCalcKey("=") }
                                .background(c.accent)
                                .border(0.5.dp, c.text.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("=", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.bg)
                        }
                    }
                }
            }
        }
    }

    // ── Sub-dialogs ────────────────────────────────────────────────────────────
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
            clearCategoryName = if (transactionType == "INCOME") "INCOME_OTHERS" else "OTHERS",
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
    onConfirm: (TransactionEntry, Boolean) -> Unit
) {
    val c = LocalAppColors.current
    var title by remember { mutableStateOf(tx.title) }
    var amountStr by remember { mutableStateOf(if (tx.amount == kotlin.math.floor(tx.amount)) tx.amount.toLong().toString() else tx.amount.toString()) }
    var editType by remember { mutableStateOf(tx.type) }
    var categorySelection by remember {
        mutableStateOf(if (tx.category.equals("INCOME", ignoreCase = true)) "SALARY" else tx.category)
    }
    var accountSelection by remember { mutableStateOf(tx.getAccountName()) }
    var notesStr by remember {
        val rawNote = (tx.note ?: "").replace("\\s*\\[Acc:[^]]*]".toRegex(), "").trim()
        // For TRANSFER entries show [To: ...] on the first line, then the SMS body below
        val displayNote = if (tx.type == "TRANSFER") {
            val toMatch = Regex("\\[To:[^]]+]").find(rawNote)
            if (toMatch != null) {
                val toTag = toMatch.value.trim()
                val rest = rawNote.removeRange(toMatch.range).trim()
                if (rest.isEmpty()) toTag else "$toTag\n$rest"
            } else rawNote
        } else rawNote
        mutableStateOf(displayNote)
    }
    var selectedTimestamp by remember { mutableStateOf(tx.timestamp) }
    var showWalletPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showQuickAddAccount by remember { mutableStateOf(false) }
    var showQuickAddCategory by remember { mutableStateOf(false) }
    var applyToAllPayees by remember { mutableStateOf(false) }

    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())
    val allCategories = CategoryResolver.getAll(customCats)
    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val selectablesWallets = accounts.map { it.name }
    val isNoCategoryType = editType == "TRANSFER" || editType == "BALANCE_UPDATE" || editType == "DUPLICATE"
    val filteredCats = when (editType) {
        "INCOME" -> allCategories.filter { it.type == "INCOME" }
        else -> allCategories.filter { it.type == "EXPENSE" }
    }
    val selectedCategory = filteredCats.firstOrNull { it.name == categorySelection }
        ?: allCategories.firstOrNull { it.name == categorySelection }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Transaction", fontWeight = FontWeight.Bold, color = c.text) },
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
                        focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Text("TRANSACTION TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EXPENSE", "INCOME", "TRANSFER", "DUPLICATE", "BALANCE_UPDATE").forEach { t ->
                            val sel = editType == t
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (sel) c.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (sel) c.accent else c.divider, RoundedCornerShape(8.dp))
                                    .clickable { editType = t }
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when (t) {
                                        "BALANCE_UPDATE" -> "BAL SYNC"
                                        "DUPLICATE"      -> "DUPL"
                                        "TRANSFER"       -> "XFER"
                                        else             -> t
                                    },
                                    fontSize = 9.sp,
                                    color = if (sel) c.accent else c.textSecondary,
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
                        focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
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
                    tint = c.accent,
                    onClick = { showWalletPicker = true }
                )

                if (!isNoCategoryType) {
                    PickerButton(
                        label = "Category",
                        title = selectedCategory?.displayName ?: "Choose category",
                        icon = selectedCategory?.icon ?: Icons.Default.Category,
                        tint = selectedCategory?.color ?: c.accent,
                        onClick = { showCategoryPicker = true }
                    )
                }

                OutlinedTextField(
                    value = notesStr,
                    onValueChange = { notesStr = it },
                    label = { Text("Details note (Optional)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Apply-to-all-payees toggle (opt-in, only for EXPENSE/INCOME when category is not OTHERS)
                if (!isNoCategoryType && title.isNotBlank() && !categorySelection.equals("OTHERS", ignoreCase = true)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.textTertiary.copy(alpha = 0.06f))
                            .border(1.dp, c.divider, RoundedCornerShape(8.dp))
                            .clickable { applyToAllPayees = !applyToAllPayees }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Apply category to all '$title' transactions", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            Text("Re-categorizes every transaction with this merchant name", fontSize = 10.sp, color = c.textSecondary)
                        }
                        Switch(
                            checked = applyToAllPayees,
                            onCheckedChange = { applyToAllPayees = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = c.accent, checkedTrackColor = c.accent.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = c.expense)
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
                                ),
                                applyToAllPayees
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.text) }
        },
        containerColor = c.surface
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
            clearCategoryName = if (editType == "INCOME") "INCOME_OTHERS" else "OTHERS",
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
    val c = LocalAppColors.current
    val context = LocalContext.current
    // Use a themed context so the native pickers match the app's light/dark mode and
    // AM/PM buttons get proper selection highlight (Material Light gives white bg + coloured chip).
    val isDark = isSystemInDarkTheme()
    val dialogContext = remember(isDark, context) {
        android.view.ContextThemeWrapper(
            context,
            if (isDark) android.R.style.Theme_Material_Dialog
            else        android.R.style.Theme_Material_Light_Dialog
        )
    }
    val dateLabel = remember(selectedTimestamp) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedTimestamp))
    }
    val timeLabel = remember(selectedTimestamp) {
        SystemDateFormat.getTimeFormat(context).format(Date(selectedTimestamp))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
                    DatePickerDialog(
                        dialogContext,
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
                        dialogContext,
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
                        false  // AM/PM (12-hour) mode
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
    val c = LocalAppColors.current
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
        border = BorderStroke(1.dp, c.divider)
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
            Icon(Icons.Default.ExpandMore, contentDescription = label, tint = c.text.copy(alpha = 0.7f))
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
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Wallet", fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                walletOptions.forEach { (walletName, walletType) ->
                    val active = selectedWallet == walletName
                    Surface(
                        color = if (active) c.accent.copy(alpha = 0.15f) else c.divider,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (active) c.accent else c.divider),
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
                                tint = if (active) c.accent else c.text,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(walletName, color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                if (addActionLabel != null && onAddAction != null) {
                    OutlinedButton(
                        onClick = onAddAction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                        border = BorderStroke(1.dp, c.accent.copy(alpha = 0.5f))
                    ) {
                        Text(addActionLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = c.text) }
        },
        containerColor = c.surface
    )
}

@Composable
private fun CategorySelectionDialog(
    categories: List<DisplayCategory>,
    selectedCategoryName: String,
    clearCategoryName: String = "OTHERS",
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    addActionLabel: String? = null,
    onAddAction: (() -> Unit)? = null
) {
    val c = LocalAppColors.current
    val sortedCategories = remember(categories) { categories.sortedBy { it.displayName } }
    val clearDisplayName = categories.firstOrNull { it.name.equals(clearCategoryName, ignoreCase = true) }?.displayName ?: clearCategoryName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category", fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // "None / Remove" row — resets to the type-appropriate default category
                val clearActive = selectedCategoryName.isBlank() || selectedCategoryName.equals(clearCategoryName, ignoreCase = true)
                Surface(
                    color = if (clearActive) c.textTertiary.copy(alpha = 0.12f) else c.divider,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (clearActive) c.textTertiary else c.divider),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(clearCategoryName) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.RemoveCircle, contentDescription = "None", tint = c.textTertiary, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("None / $clearDisplayName", color = c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                sortedCategories.forEach { category ->
                    val active = selectedCategoryName == category.name
                    Surface(
                        color = if (active) category.color.copy(alpha = 0.15f) else c.divider,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (active) category.color else c.divider),
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
                            Text(category.displayName, color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                if (addActionLabel != null && onAddAction != null) {
                    OutlinedButton(
                        onClick = onAddAction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                        border = BorderStroke(1.dp, c.accent.copy(alpha = 0.5f))
                    ) {
                        Text(addActionLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = c.text) }
        },
        containerColor = c.surface
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
    val c = LocalAppColors.current
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("BANK") }
    var openingBalanceTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    val types = listOf("CASH", "BANK", "CREDIT_CARD", "WALLET")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Account", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name (e.g. SBI Bank)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text,
                        focusedBorderColor = c.accent,
                        focusedLabelColor = c.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Initial Ledger Balance (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text,
                        focusedBorderColor = c.accent,
                        focusedLabelColor = c.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                TransactionDateTimePicker(
                    selectedTimestamp = openingBalanceTimestamp,
                    onTimestampChange = { openingBalanceTimestamp = it },
                    label = "Opening Balance Date & Time"
                )
                Column {
                    Text("ACCOUNT CATEGORY / TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        types.forEach { option ->
                            val active = option == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (active) c.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .border(1.dp, if (active) c.accent else c.text.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                    .clickable { type = option }
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(option.replace("_", " "), color = if (active) c.accent else c.text, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name, amount.toDoubleOrNull() ?: 0.0, type, name.filter { it.isDigit() }.takeLast(4).ifBlank { null }, openingBalanceTimestamp)
                },
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
            ) {
                Text("Add Account", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text) } },
        containerColor = c.surface
    )
}

@Composable
private fun QuickAddCategoryDialog(
    defaultType: String,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, Double) -> Unit
) {
    val c = LocalAppColors.current
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(defaultType) }
    var selectedIconName by remember { mutableStateOf(if (defaultType == "INCOME") "salary" else "others") }
    var selectedColorHex by remember { mutableStateOf(if (defaultType == "INCOME") "#4CAF50" else "#607D8B") }
    var selectedColor by remember { mutableStateOf(if (defaultType == "INCOME") Color(0xFF4CAF50) else Color(0xFF607D8B)) }
    var initialBudgetAmt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Category", color = c.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text,
                        focusedBorderColor = c.accent,
                        focusedLabelColor = c.accent
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
                                .background(if (active) c.accent.copy(alpha = 0.15f) else Color.Transparent)
                                .border(1.dp, if (active) c.accent else c.divider, RoundedCornerShape(8.dp))
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
                            Text(option, color = if (active) c.accent else c.text, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
                            focusedTextColor = c.text,
                            focusedBorderColor = c.accent,
                            focusedLabelColor = c.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text("Select Icon", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                ) {
                    items(suitableIconsList) { (iconName, iconVec) ->
                        val isSelected = selectedIconName == iconName
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedIconName = iconName }
                                .padding(vertical = 4.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) selectedColor.copy(alpha = 0.25f) else c.text.copy(alpha = 0.05f),
                                border = BorderStroke(if (isSelected) 2.dp else 0.dp, if (isSelected) selectedColor else Color.Transparent),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = iconVec,
                                        contentDescription = iconName,
                                        tint = if (isSelected) selectedColor else c.text.copy(alpha = 0.7f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = iconLabel(iconName),
                                fontSize = 7.sp,
                                color = if (isSelected) selectedColor else c.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Text("Select Color", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    items(categoryColorsList) { (hexStr, colorObj) ->
                        val isSelected = selectedColorHex == hexStr
                        Surface(
                            shape = CircleShape,
                            color = colorObj,
                            border = BorderStroke(2.dp, if (isSelected) c.text else Color.Transparent),
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
                                        tint = c.text,
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
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
            ) {
                Text("Add Category", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text) } },
        containerColor = c.surface
    )
}

@Composable
fun ExportCsvDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
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
        title = { Text("Export Financial Records", fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Export styled month-wise financial reports as Excel or PDF for external analysis.",
                    fontSize = 12.sp,
                    color = c.text.copy(alpha = 0.7f)
                )
                Surface(
                    color = c.text.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Includes month-wise transactions, category color breakdown, carry over balance, monthly total, and grand total.",
                        fontSize = 10.sp,
                        color = c.accent,
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
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                ) {
                    Text("Export Excel", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val fileName = "mymoney-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.pdf"
                        pdfExporter.launch(fileName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = c.income, contentColor = c.text)
                ) {
                    Text("Export PDF", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss", color = c.text) }
        },
        containerColor = c.surface
    )
}

@Composable
fun BackupDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    var exported by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackupToUri(context, uri)
            exported = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Icon header ────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(c.income.copy(alpha = 0.15f), CircleShape)
                        .border(1.5.dp, c.income.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Backup,
                        contentDescription = null,
                        tint = c.income,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Export Backup ",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text
                )
                Text(
                    "Save all your data as a CSV file",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = c.text.copy(0.08f))
                Spacer(Modifier.height(16.dp))

                // ── What's included ───────────────────────────────────────
                Text(
                    "WHAT'S INCLUDED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text.copy(0.4f),
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(10.dp))
                listOf(
                    Triple(Icons.Default.AccountBalanceWallet, "All Accounts",       "Name, type, balance, last 4 digits"),
                    Triple(Icons.Default.ReceiptLong,          "All Transactions",   "Date, title, amount, category, type"),
                    Triple(Icons.Default.Category,             "Account Links",       "Each transaction linked to its account"),
                    Triple(Icons.Default.Calculate,            "All Budgets",         "Category limits and month-year targets")
                ).forEach { (icon, title, sub) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(c.income.copy(0.12f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = c.income, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            Text(sub,   fontSize = 11.sp, color = c.textSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Info note ─────────────────────────────────────────────
                Surface(
                    color = c.accent.copy(0.06f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, c.accent.copy(0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
                        Text(
                            "Opens in Excel & Google Sheets. Balance Sync entries are excluded.",
                            fontSize = 11.sp,
                            color = c.accent.copy(0.85f)
                        )
                    }
                }

                // ── Success banner ────────────────────────────────────────
                if (exported) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = c.income.copy(0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, c.income.copy(0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = c.income, modifier = Modifier.size(18.dp))
                            Text("Backup saved successfully!", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.income)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = c.text.copy(0.08f))
                Spacer(Modifier.height(14.dp))

                // ── Action buttons ────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text.copy(0.7f)),
                        border = BorderStroke(1.dp, c.text.copy(0.15f))
                    ) { Text("Close") }

                    Button(
                        onClick = {
                            val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
                            createDocLauncher.launch("mymoney_backup_$date.csv")
                        },
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = c.income, contentColor = c.text),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save CSV Backup", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = c.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun RestoreBackupDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    var confirmed by remember { mutableStateOf(false) }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.restoreFromBackupUri(context, uri)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Icon header ────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(c.expense.copy(alpha = 0.15f), CircleShape)
                        .border(1.5.dp, c.expense.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = null,
                        tint = c.expense,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Import Backup",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text
                )
                Text(
                    "Restore from a CSV backup file",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(Modifier.height(16.dp))

                // ── Warning banner ────────────────────────────────────────
                Surface(
                    color = c.expense.copy(0.08f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, c.expense.copy(0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = c.expense, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Destructive Action", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.expense)
                            Text(
                                "This will permanently replace ALL existing accounts and transactions with the backup contents.",
                                fontSize = 11.sp,
                                color = c.expense.copy(0.85f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = c.text.copy(0.08f))
                Spacer(Modifier.height(14.dp))

                // ── What gets restored ─────────────────────────────────────
                Text(
                    "WHAT GETS RESTORED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.text.copy(0.4f),
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(10.dp))
                listOf(
                    Triple(Icons.Default.AccountBalanceWallet, "Accounts",        "All wallets recreated with their types"),
                    Triple(Icons.Default.ReceiptLong,          "Transactions",    "All records with date, amount, category"),
                    Triple(Icons.Default.LinkOff,              "Existing Data",   "Cleared first — cannot be recovered")
                ).forEachIndexed { idx, (icon, title, sub) ->
                    val tint = if (idx == 2) c.expense else c.accent
                    val bg   = if (idx == 2) c.expense.copy(0.1f) else c.accent.copy(0.08f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(bg, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                            Text(sub,   fontSize = 11.sp, color = c.textSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = c.text.copy(0.08f))
                Spacer(Modifier.height(12.dp))

                // ── Confirmation toggle ───────────────────────────────────
                Surface(
                    color = if (confirmed) c.expense.copy(0.08f) else c.text.copy(0.04f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (confirmed) c.expense.copy(0.4f) else c.text.copy(0.1f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmed = !confirmed }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Checkbox(
                            checked = confirmed,
                            onCheckedChange = { confirmed = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = c.expense,
                                uncheckedColor = c.text.copy(0.3f)
                            )
                        )
                        Text(
                            "I understand this will overwrite all my existing data",
                            fontSize = 12.sp,
                            fontWeight = if (confirmed) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (confirmed) c.text else c.textSecondary
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Action buttons ────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text.copy(0.7f)),
                        border = BorderStroke(1.dp, c.text.copy(0.15f))
                    ) { Text("Cancel") }

                    Button(
                        onClick = { openDocLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                        modifier = Modifier.weight(2f),
                        enabled = confirmed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.expense,
                            contentColor = c.text,
                            disabledContainerColor = c.expense.copy(0.3f),
                            disabledContentColor = c.text.copy(0.4f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Browse Backup File", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = c.surface,
        shape = RoundedCornerShape(20.dp)
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
    blockedSmsAccountIds: Set<String>,
    blockedAccountNames: Set<String>,
    onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    var patternInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account Center Settings", fontWeight = FontWeight.Bold, color = c.text) },
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
                            Text("Show Credit Card Details", color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Display available limit & due amount on cards", color = c.textSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = showCreditCardDetails,
                            onCheckedChange = { viewModel.setShowCreditCardDetails(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = c.accent,
                                checkedTrackColor = c.accent.copy(alpha = 0.45f),
                                uncheckedThumbColor = c.text,
                                uncheckedTrackColor = c.textTertiary
                            )
                        )
                    }
                }
                // Account visibility section header
                item {
                    Text(
                        "ACCOUNT VISIBILITY",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textSecondary,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(accounts) { acc ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(acc.name, color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = !hiddenAccountIds.contains(acc.id),
                            onCheckedChange = { visible -> viewModel.setAccountHidden(acc.id, !visible) },
                            modifier = Modifier.scale(0.75f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = c.accent,
                                checkedTrackColor = c.accent.copy(alpha = 0.45f),
                                uncheckedThumbColor = c.text,
                                uncheckedTrackColor = c.textTertiary
                            )
                        )
                    }
                }
                // SMS Blocklist section
                item {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            "SMS IMPORT BLOCKLIST",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textSecondary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "Block SMS imports by wallet name or sender. Wildcards: HDFC*, *1234, *paytm*",
                            color = c.textSecondary,
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
                                placeholder = { Text("e.g. HDFC*, *paytm*", color = c.text.copy(0.4f), fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = c.accent,
                                    unfocusedBorderColor = c.text.copy(0.3f),
                                    focusedTextColor = c.text,
                                    unfocusedTextColor = c.text
                                ),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                            )
                            IconButton(onClick = {
                                if (patternInput.isNotBlank()) {
                                    viewModel.addSmsBlocklistPattern(patternInput.trim())
                                    patternInput = ""
                                }
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add pattern", tint = c.accent)
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
                        Text(pattern, color = c.expense, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeSmsBlocklistPattern(pattern) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = c.expense)
                        }
                    }
                }
                // Accounts blocked via the Delete Account dialog (persist even after account deleted)
                if (blockedAccountNames.isNotEmpty()) {
                    item {
                        Text("BLOCKED ACCOUNTS", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = c.textSecondary, letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                items(blockedAccountNames.toList()) { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, color = c.expense, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Blocked from SMS import", color = c.textSecondary, fontSize = 10.sp)
                        }
                        IconButton(onClick = { viewModel.unblockAccountByName(name) }) {
                            Icon(Icons.Default.Close, contentDescription = "Unblock", tint = c.expense)
                        }
                    }
                }
                if (smsBlocklistPatterns.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.deleteTransactionsMatchingBlocklist() },
                            border = BorderStroke(1.dp, c.expense),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.expense)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = c.expense)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Matching Transactions", color = c.expense, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.text
    )
}

fun getAnalyticsRange(monthYear: String, filter: String, anchorTimeMs: Long = -1L): Pair<Long, Long> {
    val anchor = if (anchorTimeMs > 0) anchorTimeMs else System.currentTimeMillis()
    // Use the same period logic as Records view so both tabs show identical date ranges
    return when (filter) {
        "WEEKLY"  -> getPeriodRange(DisplayMode.WEEKLY, anchor)
        "MONTHLY" -> getPeriodRange(DisplayMode.MONTHLY, anchor)
        "3M"      -> getPeriodRange(DisplayMode.THREE_MONTHS, anchor)
        "6M"      -> getPeriodRange(DisplayMode.SIX_MONTHS, anchor)
        "1Y"      -> getPeriodRange(DisplayMode.ONE_YEAR, anchor)
        else      -> getPeriodRange(DisplayMode.MONTHLY, anchor)
    }
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

fun budgetProgressColor(percent: Double, appColors: com.example.ui.theme.AppColors): Color {
    return when {
        percent > 100.0 -> appColors.expense
        percent > 90.0 -> Color(0xFFFB923C)
        percent >= 60.0 -> Color(0xFFFACC15)
        percent >= 30.0 -> Color(0xFF4ADE80)
        else -> appColors.income
    }
}

fun resolveBudgetCategoryName(category: DisplayCategory, editedName: String): String {
    // Always use category.name as the budget key.
    // For standard categories (even if icon/color customised), name is the enum value (e.g. "FOOD").
    // For user-renamed categories, name is the renamed value.
    // This ensures saveBudget and the activeBudgets lookup both use the same key.
    return category.name
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
