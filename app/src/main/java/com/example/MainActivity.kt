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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
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
import com.example.ui.theme.forestAppColors
import com.example.ui.theme.goldAppColors
import com.example.ui.theme.jadeAppColors
import com.example.ui.theme.sandAppColors
import com.example.ui.theme.midnightAppColors
import com.example.ui.theme.oceanAppColors
import com.example.viewmodel.FinanceViewModel
import com.example.viewmodel.DisplayMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.text.DecimalFormat
import android.text.format.DateFormat as SystemDateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb

// Enum representing the five core tabs mimicking MyMoney
enum class AppTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DASHBOARD("Records", Icons.AutoMirrored.Filled.ReceiptLong),
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

/** Appends [Acc: accountName] tag to a note, removing any pre-existing account tag. */
fun makeNoteWithAccount(plainNote: String?, accountName: String): String {
    // Strip any pre-existing [Acc: ...] tag to avoid double-tagging on re-save
    val clean = (plainNote ?: "").replace("\\s*\\[Acc:[^]]*]".toRegex(), "").trim()
    return if (clean.isEmpty()) "[Acc: $accountName]" else "$clean [Acc: $accountName]"
}

/** Strips ALL internal metadata tags, returning only the user-written portion of a note. */
/** Strips all internal metadata tags ([Acc:], [To:], [T:A], [IncRef:]) from a note,
 * returning only the user-visible portion. */
fun userNoteFrom(note: String?): String = (note ?: "")
    .replace(Regex("\\s*\\[Acc:[^]]*]"), "")
    .replace(Regex("\\s*\\[To:[^]]*]"), "")
    .replace(Regex("\\s*\\[T:A]"), "")
    .replace(Regex("\\s*\\[IncRef:[^]]*]"), "")
    .trim()

/** Rebuilds the full note for saving: account tag + preserved transfer metadata + user text. */
/** Reconstructs the full note string: user text + account tag + preserved transfer metadata. */
fun rebuildNote(userNote: String?, accountName: String, originalNote: String?): String {
    val withAccount = makeNoteWithAccount(userNote, accountName)
    val transferTags = buildString {
        Regex("\\[To:[^]]*]").find(originalNote ?: "")?.value?.let { append(it) }
        if ((originalNote ?: "").contains("[T:A]")) append("[T:A]")
        Regex("\\[IncRef:[^]]*]").find(originalNote ?: "")?.value?.let { append(it) }
    }
    return if (transferTags.isNotEmpty()) "$withAccount$transferTags" else withAccount
}

/** Applies the same consolidation rules as getAccountName(consolidate=true) to a raw name string. */
fun consolidateAccountName(raw: String): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("card") || lower.contains("credit") -> "Credit Card"
        lower.contains("bank") || lower.contains("sbi") || lower.contains("hdfc") || lower.contains("icici") || lower.contains("axis") || lower.contains("pnb") -> "Bank Account"
        lower.contains("cash") -> "Cash Wallet"
        lower.contains("wallet") -> "Digital Wallet"
        else -> "Bank Account"
    }
}

// Base balances helper
/**
 * Computes the current balance for every wallet by replaying all transactions
 * from the account's base balance (or the most recent BALANCE_UPDATE snapshot).
 * Handles TRANSFER entries, optional account consolidation, and carry-over toggle.
 */
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
        // For TRANSFER we skip the outer source-account guard: the TRANSFER branch below checks
        // each side (source AND destination) independently against its own snapshot.
        // Without this exception, a BALANCE_UPDATE on the source account (e.g. HDFC Balance Sync)
        // would silently discard the entire transfer — including the destination credit — even when
        // the destination account (e.g. Indian Bank) has no snapshot that covers this transfer.
        if (tx.type != "TRANSFER" && snap != null && tx.timestamp <= snap.first) continue  // pre-snapshot

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


// ═══════════════════════════════════════════════════════════════════════════
// AutoLedger — Personal Finance Tracker  (single-file Compose UI)
//
// Architecture Flow:
//   SMS inbox / Manual entry
//       ↓
//   SmsReceiver / AddTransactionDialog
//       ↓
//   FinanceViewModel  ←→  Room Database
//   (transactions, budgets, accounts, categories, SMS rules)
//       ↓  StateFlow / collectAsStateWithLifecycle
//   MainAppScreen  →  HorizontalPager (5 tabs)
//       ├─ DashboardScreen   (Records)   – wallet cards, grouped tx list
//       ├─ AnalyticsScreen   (Analysis)  – donut/flow/account charts
//       ├─ BudgetsScreen     (Budgets)   – limits, category drag-sort
//       ├─ AccountScreen     (Accounts)  – wallet management, transfers
//       └─ AutoScanHubScreen (SMS Scan)  – on-device SMS parser
//
// Theming:
//   AppColors data class  →  LocalAppColors  →  `val c = LocalAppColors.current`
//   `c.isBorderless` drives the flat/bordered style toggle.
//
// Conventions:
//   `c`          – current AppColors (theme palette)
//   `isPaid`     – Pro features unlocked
//   `rawMonthYear` – period key "yyyy-MM"
//   `txs`        – all transactions from DB (unfiltered)
// ═══════════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("app_lock_enabled", false)
            .apply()
        setContent {
            val vm: FinanceViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val isDarkPref by vm.isDarkTheme.collectAsStateWithLifecycle()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val isDark = when (themeMode) {
                "dark", "midnight" -> true
                else   -> systemDark.takeIf { themeMode == "system" } ?: false
            }
            val isFlatStyle by vm.isFlatStyle.collectAsStateWithLifecycle()
            val appColors = when (themeMode) {
                "dark"     -> darkAppColors()
                "forest"   -> forestAppColors()
                "gold"     -> goldAppColors()
                "jade"     -> jadeAppColors()
                "sand"     -> sandAppColors()
                "midnight" -> midnightAppColors()
                "ocean"    -> oceanAppColors()
                "light"    -> lightAppColors()
                else       -> if (systemDark) darkAppColors() else lightAppColors()
            }.let { if (isFlatStyle) it.copy(isBorderless = true) else it }
            MyApplicationTheme(darkTheme = isDark) {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides appColors) {
                    MainAppScreen(vm)
                }
            }
        }
    }
}

/**
 * Root app shell — owns the Scaffold (top bar, bottom navigation bar, FAB)
 * and the HorizontalPager that drives tab navigation between the 5 main screens.
 * Also owns app-level overlays: Add Transaction, CSV Export, Backup, Restore, Pro Upgrade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: FinanceViewModel = viewModel()) {
    val context = LocalContext.current
    val c = LocalAppColors.current
    val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val smsScanMonthsBack by viewModel.smsScanMonthsBack.collectAsStateWithLifecycle()
    val isPaidMain by viewModel.isPaidFeaturesEnabled.collectAsStateWithLifecycle()
    val isFlatStyleMain by viewModel.isFlatStyle.collectAsStateWithLifecycle()
    val proExpiresAt by viewModel.proExpiresAt.collectAsStateWithLifecycle()
    var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    // Show SMS Scan only on the very first install, not on every launch
    LaunchedEffect(Unit) {
        if (viewModel.isFirstLaunch()) {
            viewModel.markAppLaunched()
            currentTab = AppTab.AUTO_SCAN
        }
    }
    // Gesture drawing: disabled by default; double-tap title to toggle.
    // Gesture drawing is disabled; title taps control Pro features
    val gestureEnabled = false
    var titleTapCount by remember { mutableStateOf(0) }
    val titleTapScope = rememberCoroutineScope()
    var titleTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val dashboardListState = rememberLazyListState()
    // Persistent list states for all tabs — created here so they survive tab switches
    val analyticsListState  = rememberLazyListState()
    val budgetsListState    = rememberLazyListState()
    val accountsListState   = rememberLazyListState()
    val smsScanListState    = rememberLazyListState()
    val fabAlpha by animateFloatAsState(
        targetValue = if (currentTab == AppTab.DASHBOARD &&
            dashboardListState.canScrollBackward && dashboardListState.canScrollForward) 0f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "FabAlpha"
    )

    // Bottom Sheet & Dialog control states
    var showAddDialog by remember { mutableStateOf(false) }
    var showCsvDialog by remember { mutableStateOf(false) }
    var showAppMenu by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showProUpgradeDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showNotificationsPanel by remember { mutableStateOf(false) }
    var expandedNotificationIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val toastMessage = viewModel.toastMessage
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
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

    // Periodically revoke Pro if a trial key has elapsed
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.checkAndRevokeExpiredPro()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    // Merge fingerprints written by SmsReceiver while app was in background
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.mergeReceiverFingerprints()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Theme-aware palette constants (switch with dark/light mode)
    val darkBg = c.bg
    val cardSurface = c.surface

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        topBar = {
            Surface(
                shadowElevation = 0.dp,
                color = c.effectiveBg
            ) {
                Column {
                    // Status bar inset — pushes content below the system status bar
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    // Actual bar content: 10dp top padding (title visually lower) + 2dp bottom
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 9.dp, bottom = 0.dp, start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ── Logo + Title ──────────────────────────────────────
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                color = c.accentDim,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(28.dp).offset(x = 6.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBalanceWallet,
                                        contentDescription = "Wallet Logo",
                                        tint = c.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "AutoLedger",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                letterSpacing = 0.5.sp,
                                color = c.text
                            )
                        }
                        // ── Notification bell ─────────────────────────────────
                        val unreadCount = notifications.count { !it.isRead }
                        BadgedBox(
                            badge = {
                                if (unreadCount > 0) Badge(
                                    containerColor = Color(0xFFE53935),
                                    contentColor = Color.White
                                ) {
                                    Text(
                                        if (unreadCount > 99) "99+" else unreadCount.toString(),
                                        fontSize = 9.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            IconButton(onClick = {
                                showNotificationsPanel = true
                                viewModel.markAllNotificationsRead()
                            }) {
                                Icon(
                                    imageVector = if (unreadCount > 0) Icons.Default.Notifications else Icons.Default.NotificationsNone,
                                    contentDescription = "Notifications",
                                    tint = if (unreadCount > 0) c.accent else c.textSecondary
                                )
                            }
                        }
                        // ── Hamburger / overflow menu ─────────────────────────
                        Box {
                            IconButton(
                                onClick = { showAppMenu = !showAppMenu },
                                modifier = Modifier.testTag("app_overflow_button")
                            ) {
                                Icon(
                                    imageVector = if (showAppMenu) Icons.Default.Close else Icons.Default.Menu,
                                    contentDescription = "App menu",
                                    tint = c.accent
                                )
                            }
                            DropdownMenu(
                                expanded = showAppMenu,
                                onDismissRequest = { showAppMenu = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = c.surface,
                                shadowElevation = 10.dp,
                                modifier = Modifier.widthIn(max = 240.dp)
                            ) {
                                // ── Pro Status entry ──────────────────────────
                                val proLabel = when {
                                    isPaidMain && proExpiresAt > 0L -> {
                                        val days = ((proExpiresAt - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
                                        "✦ Pro Trial — ${days}d left"
                                    }
                                    isPaidMain -> "✦ AutoLedger Pro"
                                    else -> "Upgrade to Pro"
                                }
                                val proColor = if (isPaidMain) Color(0xFFFFA000) else c.accent
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                if (isPaidMain) Icons.Default.AutoAwesome else Icons.Default.Lock,
                                                contentDescription = null,
                                                tint = proColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(proLabel, color = proColor, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = { showAppMenu = false; showProUpgradeDialog = true }
                                )
                                HorizontalDivider(color = c.divider)
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
                                            // Row 1: Device / Dark
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(
                                                    Triple("Device", Icons.Default.SettingsBrightness, "system"),
                                                    Triple("Dark",   Icons.Default.DarkMode,            "dark")
                                                ).forEach { (label, icon, mode) ->
                                                    val selected = themeMode == mode
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = if (selected) c.accent else c.surface,
                                                        border = BorderStroke(1.dp, if (selected) c.accent else c.divider),
                                                        modifier = Modifier.weight(1f).clickable { viewModel.setThemeMode(mode); viewModel.setDarkTheme(mode == "dark") }
                                                    ) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                                            Icon(icon, null, tint = if (selected) c.bg else c.textSecondary, modifier = Modifier.size(15.dp))
                                                            Spacer(Modifier.height(3.dp))
                                                            Text(label, fontSize = 9.sp, color = if (selected) c.bg else c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text("Light themes", fontSize = 9.sp, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                                            // Row 2: Light / Gold / Jade / Sand
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                listOf(Triple("Light", Icons.Default.LightMode, "light"), Triple("Gold", Icons.Default.Star, "gold"), Triple("Jade", Icons.Default.Spa, "jade"), Triple("Sand", Icons.Default.WbCloudy, "sand")).forEach { (label, icon, mode) ->
                                                    val selected = themeMode == mode
                                                    Surface(shape = RoundedCornerShape(8.dp), color = if (selected) c.accent else c.surface, border = BorderStroke(1.dp, if (selected) c.accent else c.divider), modifier = Modifier.weight(1f).clickable { viewModel.setThemeMode(mode); viewModel.setDarkTheme(false) }) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Icon(icon, null, tint = if (selected) c.bg else c.textSecondary, modifier = Modifier.size(12.dp)); Spacer(Modifier.height(2.dp)); Text(label, fontSize = 7.5.sp, color = if (selected) c.bg else c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onClick = {}
                                )
                                // Flat style toggle
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.ViewStream, contentDescription = null, tint = if (isFlatStyleMain) c.accent else c.textSecondary, modifier = Modifier.size(16.dp))
                                                Text("Flat Style", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Switch(
                                                checked = isFlatStyleMain,
                                                onCheckedChange = { viewModel.setFlatStyle(it) },
                                                colors = SwitchDefaults.colors(checkedThumbColor = c.accent, checkedTrackColor = c.accent.copy(0.4f)),
                                                modifier = Modifier.scale(0.8f)
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    onClick = { viewModel.setFlatStyle(!isFlatStyleMain) }
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
                                            "Ver: ${BuildConfig.VERSION_NAME}",
                                            fontSize = 11.sp,
                                            color = c.textSecondary,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    onClick = {},
                                    enabled = false,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                    }
                    } // end Row
                } // end Column
            } // end Surface
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = c.border, thickness = 1.dp)
                NavigationBar(
                    containerColor = c.effectiveBg,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars,
                    modifier = Modifier.height(68.dp + with(androidx.compose.ui.platform.LocalDensity.current) {
                        WindowInsets.navigationBars.getBottom(this).toDp()
                    })
                ) {
                    AppTab.values().forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            label = { Text(tab.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                BoxWithConstraints {
                    val fabSize = if (maxWidth < 360.dp) 48.dp else 56.dp
                    val iconSize = if (maxWidth < 360.dp) 22.dp else 28.dp
                    FloatingActionButton(
                        onClick = { showAddDialog = true },
                        containerColor = c.accent,
                        contentColor = c.bg,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .size(fabSize)
                            .testTag("add_transaction_fab")
                            .padding(bottom = 0.dp)
                            .alpha(fabAlpha)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Transaction",
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val appTabValues = AppTab.values()
        val pagerState = rememberPagerState(pageCount = { appTabValues.size })
        // Gesture state — lives here so the pointerInput is on the PARENT Box,
        // never on a sibling composable placed on top (which would intercept all touches).
        var gestureStroke by remember { mutableStateOf<List<androidx.compose.ui.geometry.Offset>>(emptyList()) }
        var gestureLabel by remember { mutableStateOf<String?>(null) }
        var showGestureRestoreConfirm by remember { mutableStateOf(false) }
        val gestureScope = rememberCoroutineScope()
        val availableBackupsForGesture by viewModel.availableBackups.collectAsStateWithLifecycle()

        LaunchedEffect(currentTab) {
            val idx = appTabValues.indexOf(currentTab)
            if (pagerState.currentPage != idx) pagerState.scrollToPage(idx)
        }
        LaunchedEffect(pagerState.settledPage) {
            val tab = appTabValues[pagerState.settledPage]
            if (tab != currentTab) currentTab = tab
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(c.effectiveBg)
                // ── Passive gesture observer on the PARENT Box ──────────────
                // PointerEventPass.Final = we see events AFTER the pager processes them.
                // We never call consume() so scrolling/tapping/swipe navigation are unaffected.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pts = mutableListOf(down.position)
                        gestureStroke = listOf(down.position)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            event.changes.firstOrNull { it.id == down.id }?.let {
                                pts.add(it.position)
                                if (pts.size % 4 == 0) gestureStroke = pts.toList()
                            }
                        } while (event.changes.any { it.pressed })
                        gestureStroke = pts.toList()

                        val gesture = if (gestureEnabled) detectDrawnGesture(pts) else null
                        if (gesture != null) {
                            gestureLabel = when (gesture) {
                                "SCAN"    -> "✦ Scanning Inbox…"
                                "BACKUP"  -> "✦ Backing Up…"
                                "RESTORE" -> "✦ Restore Latest Backup?"
                                else      -> null
                            }
                            gestureScope.launch {
                                kotlinx.coroutines.delay(150)
                                when (gesture) {
                                    "SCAN"    -> {
                                        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                                        if (hasPerm) viewModel.scanDeviceSmsInbox(context, smsScanMonthsBack)
                                    }
                                    "BACKUP"  -> viewModel.executeBackupNow("Gesture") { success, _ ->
                                        if (success) Toast.makeText(context, "Backup saved!", Toast.LENGTH_SHORT).show()
                                    }
                                    "RESTORE" -> {
                                        viewModel.refreshAvailableBackups()
                                        showGestureRestoreConfirm = true
                                    }
                                }
                                kotlinx.coroutines.delay(1500)
                                gestureLabel = null
                                gestureStroke = emptyList()
                            }
                        } else {
                            gestureScope.launch {
                                kotlinx.coroutines.delay(250)
                                gestureStroke = emptyList()
                            }
                        }
                    }
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { appTabValues[it].name }
            ) { page ->
                when (appTabValues[page]) {
                    AppTab.DASHBOARD -> DashboardScreen(viewModel, dashboardListState)
                    AppTab.BUDGETS   -> BudgetsScreen(viewModel, budgetsListState)
                    AppTab.ANALYTICS -> AnalyticsScreen(viewModel, analyticsListState)
                    AppTab.ACCOUNT   -> AccountScreen(viewModel, accountsListState)
                    AppTab.AUTO_SCAN -> AutoScanHubScreen(viewModel, smsScanListState)
                }
            }

            // Stroke trail — only visible when gesture mode is enabled
            val strokeSnap = if (gestureEnabled) gestureStroke else emptyList()
            if (strokeSnap.size > 1) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val path = Path()
                    path.moveTo(strokeSnap.first().x, strokeSnap.first().y)
                    strokeSnap.drop(1).forEach { path.lineTo(it.x, it.y) }
                    drawPath(path, color = c.accent.copy(alpha = 0.6f),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
                }
            }

            // Gesture action badge
            gestureLabel?.let { lbl ->
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    color = c.accent.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 12.dp
                ) {
                    Text(lbl, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp))
                }
            }
        }

        // Gesture-triggered restore: show the latest backup with a confirm prompt
        if (showGestureRestoreConfirm) {
            val latestBackup = availableBackupsForGesture.firstOrNull()
            AlertDialog(
                onDismissRequest = { showGestureRestoreConfirm = false },
                containerColor = c.surface,
                title = { Text("Restore Latest Backup", fontWeight = FontWeight.Bold, color = c.text) },
                text = {
                    if (latestBackup == null) {
                        Text("No backups found.", color = c.textSecondary)
                    } else {
                        val sizeStr = when {
                            latestBackup.sizeBytes >= 1_000_000 -> "${String.format("%.1f", latestBackup.sizeBytes / 1_000_000.0)} MB"
                            latestBackup.sizeBytes >= 1_000     -> "${String.format("%.1f", latestBackup.sizeBytes / 1_000.0)} KB"
                            else                                -> "${latestBackup.sizeBytes} B"
                        }
                        Text("Restore \"${latestBackup.name}\" ($sizeStr)?\n\nThis will merge backup data into your current records.",
                            color = c.text.copy(alpha = 0.8f), fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    if (latestBackup != null) {
                        Button(
                            onClick = {
                                viewModel.executeRestore(latestBackup, false) { _, _ -> }
                                showGestureRestoreConfirm = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                        ) { Text("Restore", fontWeight = FontWeight.Bold) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGestureRestoreConfirm = false }) { Text("Cancel", color = c.text) }
                }
            )
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

    if (showProUpgradeDialog) {
        val allTxs by viewModel.allTransactions.collectAsStateWithLifecycle()
        val sdf = remember { SimpleDateFormat("yyyy-MM", Locale.getDefault()) }
        val thisMonth = sdf.format(java.util.Date())
        val autoTracked = remember(allTxs) {
            allTxs.count { tx -> !tx.smsSender.isNullOrBlank() && sdf.format(java.util.Date(tx.timestamp)) == thisMonth }
        }
        ProUpgradeDialog(
            autoTrackedThisMonth = autoTracked,
            viewModel = viewModel,
            onDismiss = { showProUpgradeDialog = false }
        )
    }

    // ── Notification panel ──────────────────────────────────────────────────
    if (showNotificationsPanel) {
        Dialog(
            onDismissRequest = { showNotificationsPanel = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Dimmed scrim — tap outside panel dismisses
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                            showNotificationsPanel = false
                        }
                )
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.75f)
                    .align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                color = c.surface,
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header — uses the SAME surface color as the body (previously the header
                    // explicitly used c.bg while the body used c.surface; on the default
                    // light/dark themes c.bg has a distinct blue-gray tint vs c.surface's
                    // neutral white/near-black, which made the header look like a separate
                    // "light blue" bar sitting on top of the body. One consistent color reads
                    // correctly across every theme, including Gold/Jade/Sand.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Notifications", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.text, modifier = Modifier.weight(1f))
                        if (notifications.isNotEmpty()) {
                            IconButton(onClick = { viewModel.markAllNotificationsRead() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.DoneAll, contentDescription = "Mark all read", tint = c.income, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { viewModel.deleteAllNotifications() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Delete all", tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = { showNotificationsPanel = false }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(color = c.divider)
                    if (notifications.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No notifications", color = c.textSecondary, fontSize = 14.sp)
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(notifications, key = { it.id }) { notif ->
                                val isExpanded = notif.id in expandedNotificationIds
                                val (notifIcon, notifIconColor) = notificationIconFor(notif.title)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // Expand the message IN PLACE instead of opening a
                                        // second popup on top of this panel — the panel stays
                                        // open the whole time.
                                        .clickable {
                                            expandedNotificationIds = if (isExpanded) expandedNotificationIds - notif.id else expandedNotificationIds + notif.id
                                        }
                                        .background(if (!notif.isRead) c.accent.copy(alpha = 0.07f) else Color.Transparent)
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Surface(shape = CircleShape, color = notifIconColor.copy(alpha = 0.15f), modifier = Modifier.size(38.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(notifIcon, contentDescription = null, tint = notifIconColor, modifier = Modifier.size(19.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                notif.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = c.text,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (!notif.isRead) {
                                                Spacer(Modifier.width(5.dp))
                                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text(relativeTimeText(notif.timestamp), fontSize = 10.sp, color = c.textSecondary)
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(notif.message, fontSize = 12.sp, color = c.textSecondary, maxLines = if (isExpanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(notif.timestamp)),
                                            fontSize = 10.sp, color = c.textTertiary
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteNotification(notif.id) }, modifier = Modifier.padding(start = 4.dp).size(28.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete", tint = c.textTertiary, modifier = Modifier.size(13.dp))
                                    }
                                }
                                HorizontalDivider(color = c.divider, thickness = 0.5.dp, modifier = Modifier.padding(start = 62.dp))
                            }
                        }
                    }
                }
            }
        } // close outer Box
        }
    }

}

// ═══════════════════════════════════════════════════════════════════════════
// SCREEN 1 — RECORDS / DASHBOARD
// ═══════════════════════════════════════════════════════════════════════════

/** Records tab — period-aware transaction list grouped by date.
 * Features:
 *  - Multi-wallet balance cards + "All" aggregate
 *  - Daily / Weekly / Monthly / 3M / 6M / 1Y period selector
 *  - Full-text + type / category / account filters
 *  - Running balance column (optional)
 *  - Delete by all / period / category
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: FinanceViewModel, listState: LazyListState) {
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val c = LocalAppColors.current
    val isPaid by viewModel.isPaidFeaturesEnabled.collectAsStateWithLifecycle()
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
    // Advanced filter state — multi-select sets
    var filterTypes     by remember { mutableStateOf(setOf<String>()) }
    var filterCategories by remember { mutableStateOf(setOf<String>()) }
    var filterAccounts  by remember { mutableStateOf(setOf<String>()) }
    var showAdvancedFilter by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showAccountPicker  by remember { mutableStateOf(false) }
    val hasActiveFilters = filterTypes.isNotEmpty() || filterCategories.isNotEmpty() || filterAccounts.isNotEmpty()
    val isFilterPanelOpen = isSearchExpanded || showAdvancedFilter
    var showDeleteOptionsSheet by remember { mutableStateOf(false) }

    fun clearAllFilters() {
        searchQuery = ""; searchFilter = "All"
        filterTypes = emptySet(); filterCategories = emptySet(); filterAccounts = emptySet()
        isSearchExpanded = false; showAdvancedFilter = false
        showCategoryPicker = false; showAccountPicker = false
    }

    // Close search/filter on back press
    BackHandler(enabled = isFilterPanelOpen || hasActiveFilters) {
        clearAllFilters()
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
            // Secondary sort: BALANCE_UPDATE must come after INCOME/EXPENSE at the same timestamp,
            // because the balance update reflects the final state after the income/expense is applied.
            // Without this, a same-timestamp pair can double-count the amount.
            for (tx in txs.sortedWith(compareBy({ it.timestamp }, { if (it.type == "BALANCE_UPDATE") 1 else 0 }))) {
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
                val destRawForBal = tx.getTransferDestName()
                val isInboundTransfer = tx.type == "TRANSFER" && destRawForBal != null && run {
                    val destDisplay = if (consolidateAccounts) consolidateAccountName(destRawForBal) else destRawForBal
                    destDisplay == selectedWallet
                }
                if (selectedWallet != "All" && txAccDisplay != selectedWallet && !isInboundTransfer) continue
                val displayBal = when {
                    selectedWallet == "All" -> accounts.sumOf { acc -> balMap[acc.name] ?: 0.0 }
                    isInboundTransfer      -> balMap[destRawForBal!!] ?: 0.0
                    else                   -> balMap[tx.getAccountName(false)] ?: 0.0
                }
                result[tx.id] = displayBal
            }
            result
        }
    }

    // Filter Transactions by selected period AND selected wallet
    val monthTransactions = periodTransactions.filter { tx ->
        selectedWallet == "All" ||
        tx.getAccountName(consolidateAccounts) == selectedWallet ||
        (tx.type == "TRANSFER" && run {
            val destRaw = tx.getTransferDestName() ?: return@run false
            val destDisplay = if (consolidateAccounts) consolidateAccountName(destRaw) else destRaw
            destDisplay == selectedWallet
        })
    }
    // Always scope to the current period — search/filters do NOT expand across all time.
    val searchableTransactions = monthTransactions
    val visibleTransactions = searchableTransactions.filter { tx ->
        val textMatch = if (searchQuery.isBlank()) true else {
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
        val typeMatch = filterTypes.isEmpty() || filterTypes.any { it.equals(tx.type, ignoreCase = true) }
        val catMatch  = filterCategories.isEmpty() || run {
            val displayCat = CategoryResolver.resolve(tx.category, customCats).displayName
            filterCategories.any { tx.category.equals(it, ignoreCase = true) || displayCat.equals(it, ignoreCase = true) }
        }
        val accMatch  = filterAccounts.isEmpty() || filterAccounts.any { it.equals(tx.getAccountName(consolidateAccounts), ignoreCase = true) }
        textMatch && typeMatch && catMatch && accMatch
    }

    // Monthly aggregates (specific to selected wallet)
    val totalIncome = monthTransactions.filter { it.type == "INCOME" }.sumOf { it.amount }
    val totalExpense = monthTransactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val netBalance = totalIncome - totalExpense
    val totalWealth = walletsBalances.values.sum()

    // Pro savings banner: remember must be outside LazyColumn (LazyListScope is not @Composable)
    val sdfBanner = remember { SimpleDateFormat("yyyy-MM", Locale.getDefault()) }
    val thisMonthBanner = remember(Unit) { sdfBanner.format(java.util.Date()) }
    val autoTrackedCount = remember(txs) {
        txs.count { tx -> !tx.smsSender.isNullOrBlank() && sdfBanner.format(java.util.Date(tx.timestamp)) == thisMonthBanner
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_scroll_column"),
        contentPadding = if (c.isBorderless) PaddingValues(horizontal = 6.dp, vertical = 4.dp) else PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Active Month/Period Navigation Selector Header
        item {
            var showFilterMenu by remember { mutableStateOf(false) }
            var showDashboardProUpgrade by remember { mutableStateOf(false) }
            if (showDashboardProUpgrade) {
                ProUpgradeDialog(
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
                    onDismiss = { showDashboardProUpgrade = false }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = c.cardBg,
                    shape = RoundedCornerShape(24.dp),
                    border = if (c.isBorderless && !c.isDark) BorderStroke(1.dp, c.flatDivider) else if (!c.isBorderless) BorderStroke(1.dp, c.border) else null,
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
                    // Search + Filter toggle — one button opens the combined panel
                    Box {
                        FilledTonalIconButton(
                            onClick = {
                                if (isFilterPanelOpen) clearAllFilters()
                                else isSearchExpanded = true
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isFilterPanelOpen || hasActiveFilters) c.accent.copy(alpha = 0.18f) else if (c.isBorderless) Color.Transparent else c.divider,
                                contentColor   = if (isFilterPanelOpen || hasActiveFilters) c.accent else c.text
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (isFilterPanelOpen) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Toggle search/filter",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Dot badge when filters are active but panel is closed
                        if (hasActiveFilters && !isFilterPanelOpen) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .clip(CircleShape)
                                    .background(c.accent)
                            )
                        }
                    }

                    Box {
                        FilledTonalIconButton(
                            onClick = { showFilterMenu = !showFilterMenu },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isSearchExpanded || searchQuery.isNotBlank()) c.accent.copy(alpha = 0.18f) else if (c.isBorderless) Color.Transparent else c.divider,
                                contentColor = if (isSearchExpanded || searchQuery.isNotBlank()) c.accent else c.text
                            ),
                            modifier = Modifier.size(40.dp).testTag("three_bar_filter_button")
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Options and Filtering", modifier = Modifier.size(22.dp))
                        }

                        DropdownMenu(
                            expanded = showFilterMenu,
                            onDismissRequest = { showFilterMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = c.surfaceVariant,
                            shadowElevation = 10.dp,
                            modifier = Modifier.width(220.dp)
                        ) {
                        // Header (non-interactive label — no min-height needed)
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("PERIOD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                        }

                        val modes = listOf(
                            DisplayMode.DAILY to "Daily",
                            DisplayMode.WEEKLY to "Weekly",
                            DisplayMode.MONTHLY to "Monthly",
                            DisplayMode.THREE_MONTHS to "3 Months",
                            DisplayMode.SIX_MONTHS to "6 Months",
                            DisplayMode.ONE_YEAR to "1 Year"
                        )

                        modes.forEach { (mode, label) ->
                            val isProPeriod = mode == DisplayMode.THREE_MONTHS || mode == DisplayMode.SIX_MONTHS || mode == DisplayMode.ONE_YEAR
                            val context = LocalContext.current
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isProPeriod && !isPaid) 0.5f else 1f)
                                    .clickable {
                                        if (isProPeriod && !isPaid) {
                                            showFilterMenu = false
                                            Toast.makeText(context, "Pro feature — unlock in AutoLedger Pro", Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.setDisplayMode(mode)
                                            showFilterMenu = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                                    .testTag("filter_mode_${mode.name.lowercase()}")
                            ) {
                                Text(label, color = if (activeMode == mode) c.accent else c.text, fontSize = 13.sp,
                                    fontWeight = if (activeMode == mode) FontWeight.Bold else FontWeight.Normal)
                                if (isProPeriod && !isPaid) {
                                    Surface(color = Color(0xFFFFA000), shape = RoundedCornerShape(4.dp)) {
                                        Text("PRO", fontSize = 7.sp, color = Color.White, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                    }
                                } else if (activeMode == mode) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
                                }
                            }
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
                                        Text("Carry Over Balance", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.text)
                                        Text("Include prior balance in totals", fontSize = 9.sp, color = c.textSecondary, maxLines = 1)
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
                                        Text("Pet-tx cumulative total", fontSize = 9.sp, color = c.textSecondary, maxLines = 1)
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

        if (!isFilterPanelOpen) {
            // Net Balance Glow Card
            item {
                Surface(
                    color = c.cardBg,
                    shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
                    border = if (c.isBorderless) null else BorderStroke(1.2.dp, c.accent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("net_wealth_glowing_card")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
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
                        Spacer(modifier = Modifier.height(4.dp))
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

                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(c.divider)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Monthly Income Tracker
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.weight(1f)) {
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

            // Horizontal Wallet microcards mimicking AutoLedger custom wallets selector
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
                            val cardBg = if (isSelected) c.accentDim else if (c.isBorderless) c.surfaceVariant else c.surface
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
            } // end if (!isFilterPanelOpen)

            // ── Pro savings banner (free users, when SMS transactions exist this month) ─
            if (!isPaid && autoTrackedCount > 0) {
                item {
                    Surface(
                        color = c.accent.copy(0.07f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, c.accent.copy(0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("$autoTrackedCount transactions auto-tracked this month",
                                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.text)
                                Text("Upgrade Pro to keep automation running →",
                                    fontSize = 11.sp, color = c.accent)
                            }
                        }
                    }
                }
        }

        // ── Combined Search + Filter panel ──────────────────────────────────
        if (isFilterPanelOpen) {
            item {
                val accountsInUse = remember(monthTransactions, accounts, consolidateAccounts) {
                    val usedNames = monthTransactions.map { tx -> tx.getAccountName(consolidateAccounts) }.toSet()
                    accounts.map { if (consolidateAccounts) consolidateAccountName(it.name) else it.name }
                        .distinct().filter { it in usedNames }
                }
                val catsInUse = remember(monthTransactions, customCats) {
                    monthTransactions.map { CategoryResolver.resolve(it.category, customCats) }
                        .distinctBy { it.name }
                        .filter { it.displayName.isNotBlank() }
                        .sortedBy { it.displayName }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Text search
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = c.textSecondary) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = c.textSecondary)
                            }
                        },
                        label = { Text("Search", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text,
                            focusedBorderColor = c.accent, unfocusedBorderColor = c.textTertiary,
                            focusedLabelColor = c.accent, unfocusedLabelColor = c.textSecondary
                        )
                    )
                    // Type chips — multi-select
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val types = listOf(
                            Triple("EXPENSE",  Icons.Default.ArrowUpward,   c.expense),
                            Triple("INCOME",   Icons.Default.ArrowDownward, c.income),
                            Triple("TRANSFER", Icons.Default.SyncAlt,       c.accent)
                        )
                        items(types.size) { i ->
                            val (t, icon, color) = types[i]
                            val sel = t in filterTypes
                            FilterChip(
                                selected = sel,
                                onClick = { filterTypes = if (sel) filterTypes - t else filterTypes + t },
                                label = { Text(t.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = color.copy(alpha = 0.18f), selectedLabelColor = color,
                                    containerColor = c.divider, labelColor = c.textSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = sel,
                                    selectedBorderColor = color.copy(0.4f), borderColor = c.text.copy(0.1f)
                                )
                            )
                        }
                    }
                    // Category picker
                    ProGate(isPaid = isPaid, modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth().height(20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Category", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
                            if (filterCategories.isNotEmpty()) CompositionLocalProvider(
                                LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified
                            ) {
                                TextButton(
                                    onClick = { filterCategories = emptySet() },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) { Text("Clear", fontSize = 11.sp, color = c.expense) }
                            }
                        }
                        if (filterCategories.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(filterCategories.toList()) { catKey ->
                                    val resolved = CategoryResolver.resolve(catKey, customCats)
                                    InputChip(
                                        selected = true,
                                        onClick = { filterCategories = filterCategories - catKey },
                                        label = { Text(resolved.displayName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(resolved.icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = resolved.color) },
                                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                                        colors = InputChipDefaults.inputChipColors(selectedContainerColor = resolved.color.copy(0.15f), selectedLabelColor = resolved.color)
                                    )
                                }
                            }
                        }
                        Box {
                            OutlinedButton(
                                onClick = { showCategoryPicker = !showCategoryPicker; showAccountPicker = false },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                border = if (c.isBorderless) null else BorderStroke(1.dp, if (showCategoryPicker) c.accent else c.divider),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(15.dp), tint = c.textSecondary)
                                Spacer(Modifier.width(6.dp))
                                Text(if (filterCategories.isEmpty()) "Add category filter…" else "+ Add more categories",
                                    fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.weight(1f))
                                Icon(if (showCategoryPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null, modifier = Modifier.size(16.dp), tint = c.textSecondary)
                            }
                            DropdownMenu(
                                expanded = showCategoryPicker,
                                onDismissRequest = { showCategoryPicker = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = c.surfaceVariant,
                                shadowElevation = 10.dp,
                                modifier = Modifier.widthIn(min = 220.dp, max = 300.dp).heightIn(max = 320.dp)
                            ) {
                                catsInUse.forEach { resolved ->
                                    val selected = resolved.name in filterCategories
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Icon(resolved.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = resolved.color)
                                                Text(resolved.displayName, fontSize = 13.sp,
                                                    color = if (selected) resolved.color else c.text,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                                Spacer(Modifier.weight(1f))
                                                if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = resolved.color)
                                            }
                                        },
                                        onClick = { filterCategories = if (selected) filterCategories - resolved.name else filterCategories + resolved.name }
                                    )
                                }
                                if (catsInUse.isEmpty()) DropdownMenuItem(
                                    text = { Text("No categories in this period", fontSize = 12.sp, color = c.textSecondary) }, onClick = {}
                                )
                            }
                        }
                    }
                    } // end ProGate (category)
                    // Account picker
                    ProGate(isPaid = isPaid, modifier = Modifier.fillMaxWidth()) {
                    if (accountsInUse.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth().height(20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Text("Account", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
                                if (filterAccounts.isNotEmpty()) CompositionLocalProvider(
                                    LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified
                                ) {
                                    TextButton(
                                        onClick = { filterAccounts = emptySet() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                    ) { Text("Clear", fontSize = 11.sp, color = c.expense) }
                                }
                            }
                            if (filterAccounts.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(filterAccounts.toList()) { acc ->
                                        InputChip(
                                            selected = true,
                                            onClick = { filterAccounts = filterAccounts - acc },
                                            label = { Text(acc, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                                            modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth(0.92f),
                                            colors = InputChipDefaults.inputChipColors(selectedContainerColor = c.accent.copy(0.15f), selectedLabelColor = c.accent)
                                        )
                                    }
                                }
                            }
                            Box {
                                OutlinedButton(
                                    onClick = { showAccountPicker = !showAccountPicker; showCategoryPicker = false },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    border = if (c.isBorderless) null else BorderStroke(1.dp, if (showAccountPicker) c.accent else c.divider),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(15.dp), tint = c.textSecondary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (filterAccounts.isEmpty()) "Add account filter…" else "+ Add more accounts",
                                        fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.weight(1f))
                                    Icon(if (showAccountPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null, modifier = Modifier.size(16.dp), tint = c.textSecondary)
                                }
                                DropdownMenu(
                                    expanded = showAccountPicker,
                                    onDismissRequest = { showAccountPicker = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = c.surfaceVariant,
                                    shadowElevation = 10.dp,
                                    modifier = Modifier.widthIn(min = 220.dp, max = 300.dp).fillMaxWidth(0.92f).heightIn(max = 260.dp)
                                ) {
                                    accountsInUse.forEach { name ->
                                        val selected = name in filterAccounts
                                        val acctObj = accounts.find { a ->
                                            if (consolidateAccounts) consolidateAccountName(a.name) == name else a.name == name
                                        }
                                        val acctType = acctObj?.type ?: ""
                                        val acctColor = when (acctType) {
                                            "CASH"        -> c.income
                                            "BANK"        -> Color(0xFF3B82F6)
                                            "CREDIT_CARD" -> c.expense
                                            "WALLET"      -> Color(0xFFFF9800)
                                            else          -> c.textSecondary
                                        }
                                        val acctIcon = walletIconFor(name, acctType.takeIf { it.isNotEmpty() })
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Icon(acctIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = acctColor)
                                                    Text(name, fontSize = 13.sp,
                                                        color = if (selected) acctColor else c.text,
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                    if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = acctColor)
                                                }
                                            },
                                            onClick = { filterAccounts = if (selected) filterAccounts - name else filterAccounts + name }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    } // end ProGate (account)
                    // Filter summary
                    if (hasActiveFilters) {
                        val count = filterTypes.size + filterCategories.size + filterAccounts.size
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("$count filter${if (count > 1) "s" else ""} active · ${visibleTransactions.size} result${if (visibleTransactions.size != 1) "s" else ""}",
                                fontSize = 11.sp, color = c.accent, fontWeight = FontWeight.Medium)
                            TextButton(
                                onClick = { filterTypes = emptySet(); filterCategories = emptySet(); filterAccounts = emptySet() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) { Text("Clear all", fontSize = 11.sp, color = c.expense) }
                        }
                    }
                }
            }
        }

        // Active filter badge row (shown when panel is closed)
        if (hasActiveFilters && !isFilterPanelOpen) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items((filterTypes + filterCategories + filterAccounts).toList()) { key ->
                        val label = when {
                            key in filterTypes -> key.lowercase().replaceFirstChar { it.uppercase() }
                            else -> ExpenseCategory.entries.firstOrNull { it.name == key }?.displayName ?: key
                        }
                        InputChip(
                            selected = true,
                            onClick = { filterTypes = filterTypes - key; filterCategories = filterCategories - key; filterAccounts = filterAccounts - key },
                            label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            modifier = Modifier.widthIn(max = 180.dp),
                            colors = InputChipDefaults.inputChipColors(selectedContainerColor = c.accent.copy(0.15f), selectedLabelColor = c.accent)
                        )
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
                    Column(modifier = Modifier.fillMaxWidth().background(c.effectiveBg)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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

                        val dateNet = txList.sumOf { tx ->
                            when {
                                tx.type == "DUPLICATE" || tx.type == "BALANCE_UPDATE" -> 0.0
                                tx.type == "TRANSFER" -> {
                                    if (selectedWallet != "All") {
                                        val destRaw = tx.getTransferDestName()
                                        val destDisplay = if (consolidateAccounts) consolidateAccountName(destRaw ?: "") else (destRaw ?: "")
                                        if (destDisplay == selectedWallet) tx.amount else -tx.amount
                                    } else 0.0
                                }
                                tx.type == "INCOME" -> tx.amount
                                else -> -tx.amount
                            }
                        }
                        Text(
                            text = (if (dateNet >= 0) "+" else "") + decFormat.format(dateNet),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (dateNet >= 0) c.income else c.expense
                        )
                    }
                    if (c.isBorderless) HorizontalDivider(color = c.flatDividerBold, thickness = if (c.isDark) 1.dp else 1.5.dp)
                    } // end Column
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
                                "DEBIT_CARD"  -> Color(0xFF0EA5E9)
                                "WALLET"      -> Color(0xFFFF9800)
                                else          -> c.textSecondary
                            }
                            val txFingerprint = "${tx.title}|${tx.amount}|${tx.type}|${tx.timestamp}"
                            val isNewlyImported = recentlyImportedFingerprints.contains(txFingerprint)
                            Surface(
                                shape = if (c.isBorderless) RoundedCornerShape(4.dp) else RoundedCornerShape(14.dp),
                                color = c.cardBg,
                                border = if (c.isBorderless) null else if (isNewlyImported) BorderStroke(2.dp, c.income) else BorderStroke(1.dp, c.divider),
                                modifier = Modifier
                                    .fillMaxWidth()
                        .padding(vertical = if (c.isBorderless) 0.dp else 2.dp)
                                    .clickable { selectedTxForEdit = tx }
                                    .testTag("transaction_item_${tx.id}")
                            ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = if (c.isBorderless) 4.dp else 10.dp),
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
                                if (isTransfer) {
                                    // Transfer: right side is a Column so title+amount sits above the full-width chip
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = tx.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = c.text,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "⇄ " + decFormat.format(tx.amount),
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 15.sp,
                                                color = Color(0xFF3B82F6)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        val dest = tx.getTransferDestName()
                                        val chipText = if (dest != null) "${tx.getAccountName()} → $dest" else tx.getAccountName()
                                        val chipFontSizeState = remember(chipText) { mutableStateOf(9.sp) }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Chip fills remaining space (fill=true), pushing time to the right
                                            Surface(
                                                color = acctColor.copy(alpha = 0.10f),
                                                shape = RoundedCornerShape(20.dp),
                                                modifier = Modifier.weight(1f, fill = true)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(imageVector = acctIcon, contentDescription = null, tint = acctColor, modifier = Modifier.size(9.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = chipText,
                                                        fontSize = chipFontSizeState.value,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = acctColor,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Clip,
                                                        softWrap = false,
                                                        onTextLayout = { result ->
                                                            if (result.hasVisualOverflow && chipFontSizeState.value.value > 5f) {
                                                                chipFontSizeState.value = (chipFontSizeState.value.value * 0.85f).sp
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            // Time group: NO weight — non-weighted items are measured first,
                                            // guaranteeing balance + time are always fully visible.
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (showRunningBalance) {
                                                    val runBal = runningBalances[tx.id]
                                                    if (runBal != null) {
                                                        Text(text = decFormat.format(runBal), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                }
                                                Text(
                                                    text = SystemDateFormat.getTimeFormat(context).format(Date(tx.timestamp)),
                                                    fontSize = 10.sp,
                                                    color = c.textTertiary,
                                                    maxLines = 1
                                                )
                                                if (isNewlyImported) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Surface(color = c.income.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                                        Text("NEW", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = c.income, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
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
                                                "INCOME" -> c.income
                                                "DUPLICATE" -> c.textTertiary
                                                "BALANCE_UPDATE" -> c.textTertiary
                                                else -> c.expense
                                            }
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            if (showRunningBalance) {
                                                val runBal = runningBalances[tx.id]
                                                if (runBal != null && tx.type != "DUPLICATE") {
                                                    Text(
                                                        text = decFormat.format(runBal),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = c.textSecondary
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                            }
                                            Text(
                                                text = SystemDateFormat.getTimeFormat(context).format(Date(tx.timestamp)),
                                                fontSize = 10.sp,
                                                color = c.textTertiary
                                            )
                                        }
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
                            }
                            } // end Surface card
                            if (c.isBorderless && txIdx < txList.size - 1) {
                                HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 0.5.dp else 1.dp)
                            }
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

// ═══════════════════════════════════════════════════════════════════════════
// SCREEN 2 — ANALYSIS
// ═══════════════════════════════════════════════════════════════════════════

/** Analysis tab — visual spending/income analytics over the selected period.
 * Modes: Expense Overview | Income Overview | Expense Flow | Income Flow | Account Analysis
 * Category filter persists via ViewModel; "Budgeted Only" toggle filters all modes.
 * Tapping a category opens a full-screen detail popup with pie chart + transaction list.
 */
@Composable
fun AnalyticsScreen(viewModel: FinanceViewModel, listState: LazyListState = rememberLazyListState()) {
    val c = LocalAppColors.current
    val isPaid by viewModel.isPaidFeaturesEnabled.collectAsStateWithLifecycle()
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val rawMonthYear by viewModel.selectedMonthYear.collectAsStateWithLifecycle()
    val anchorTime by viewModel.anchorDate.collectAsStateWithLifecycle()
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())

    var timeFilter by remember { mutableStateOf("MONTHLY") }
    val selectedModeIdx by viewModel.selectedAnalyticsModeIdx.collectAsStateWithLifecycle()
    val selectedMode = AnalyticsMode.entries.getOrElse(selectedModeIdx) { AnalyticsMode.EXPENSE_OVERVIEW }
    var showModeMenu by remember { mutableStateOf(false) }
    var showPeriodMenu by remember { mutableStateOf(false) }
    var showAnalyticsProUpgrade by remember { mutableStateOf(false) }
    if (showAnalyticsProUpgrade) {
        ProUpgradeDialog(
            viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
            onDismiss = { showAnalyticsProUpgrade = false }
        )
    }
    var categoryDetailItem by remember { mutableStateOf<Pair<DisplayCategorySpend, List<TransactionEntry>>?>(null) }
    var expandedNotesTxId by remember { mutableStateOf<Int?>(null) }  // null = none, -1 = all
    var accountDetailItem by remember { mutableStateOf<Pair<AccountAnalyticsSummary, List<TransactionEntry>>?>(null) }
    var flowDayItem by remember { mutableStateOf<Pair<AnalyticsFlowPoint, List<TransactionEntry>>?>(null) }
    val allAccountsForAnalytics by viewModel.allAccounts.collectAsStateWithLifecycle()
    // Per-mode category filters — each overview keeps its own independent selection
    val analyticsExpenseCatFilter by viewModel.analyticsExpenseCatFilter.collectAsStateWithLifecycle()
    val analyticsIncomeCatFilter  by viewModel.analyticsIncomeCatFilter.collectAsStateWithLifecycle()
    val activeBudgetsForAnalytics by viewModel.monthlyBudgets.collectAsStateWithLifecycle()
    val analyticsShowBudgetedOnly by viewModel.analyticsShowBudgetedOnly.collectAsStateWithLifecycle()
    val analyticsCategoryFilter = when (selectedMode) {
        AnalyticsMode.EXPENSE_OVERVIEW, AnalyticsMode.EXPENSE_FLOW -> analyticsExpenseCatFilter
        AnalyticsMode.INCOME_OVERVIEW,  AnalyticsMode.INCOME_FLOW  -> analyticsIncomeCatFilter
        else -> emptySet()
    }
    var showAnalyticsCatFilterMenu by remember { mutableStateOf(false) }

    // Records↔Analytics bidirectional period sync
    val activeMode by viewModel.displayMode.collectAsStateWithLifecycle()
    LaunchedEffect(activeMode) {
        timeFilter = when (activeMode) {
            DisplayMode.DAILY        -> "DAILY"
            DisplayMode.WEEKLY       -> "WEEKLY"
            DisplayMode.MONTHLY      -> "MONTHLY"
            DisplayMode.THREE_MONTHS -> "3M"
            DisplayMode.SIX_MONTHS   -> "6M"
            DisplayMode.ONE_YEAR     -> "1Y"
            else                     -> "MONTHLY"
        }
    }
    LaunchedEffect(timeFilter) {
        val mode = when (timeFilter) {
            "DAILY"   -> DisplayMode.DAILY
            "WEEKLY"  -> DisplayMode.WEEKLY
            "MONTHLY" -> DisplayMode.MONTHLY
            "3M"      -> DisplayMode.THREE_MONTHS
            "6M"      -> DisplayMode.SIX_MONTHS
            "1Y"      -> DisplayMode.ONE_YEAR
            else      -> DisplayMode.MONTHLY
        }
        if (activeMode != mode) viewModel.setDisplayMode(mode)
    }
    val (analysisStart, analysisEnd) = getAnalyticsRange(rawMonthYear, timeFilter, anchorTime)

    val filteredTransactions = txs.filter { tx ->
        tx.timestamp in analysisStart..analysisEnd
    }

    val expenses = filteredTransactions.filter { it.type == "EXPENSE" }
    val incomes = filteredTransactions.filter { it.type == "INCOME" }
    val overviewTransactions = when (selectedMode) {
        AnalyticsMode.INCOME_OVERVIEW, AnalyticsMode.INCOME_FLOW -> incomes
        else -> expenses
    }
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
    // Apply analytics category filter
    val filteredCategoryTotals = if (analyticsCategoryFilter.isEmpty()) categoryTotals
    else categoryTotals.filter { it.category.name in analyticsCategoryFilter }
    // Recalculate percentages relative to the filtered subset so the donut fills 100%
    val filteredOverviewTotal = filteredCategoryTotals.sumOf { it.total }
    val recalcCategoryTotals = if (analyticsCategoryFilter.isEmpty()) filteredCategoryTotals
    else filteredCategoryTotals.map { it.copy(percentage = if (filteredOverviewTotal > 0) it.total / filteredOverviewTotal else 0.0) }
    // Budgeted-only filter: recalculate percentages relative to budgeted subset.
    // IMPORTANT: `monthlyBudgets` is backed by a Room Flow keyed on the selected month
    // (flatMapLatest), so right after navigating to a different period it can briefly
    // still hold the PREVIOUS month's budget rows until the new query emits. Without
    // the monthYear guard below, that stale list would leak through and make category
    // totals (income in particular, since income rarely has a matching budget every
    // month) flash correct-then-empty — the "shown and immediately hidden" glitch.
    // Filtering to rows whose monthYear actually matches the period being displayed
    // makes the stale frame resolve to "no match" (safe/empty) instead of a wrong match.
    val budgetedCatNamesForFilter = activeBudgetsForAnalytics
        .filter { it.monthYear == rawMonthYear }
        .map { it.category.lowercase() }
        .toSet()
    val displayCategoryTotals = if (analyticsShowBudgetedOnly) {
        val subset = recalcCategoryTotals.filter { it.category.name.lowercase() in budgetedCatNamesForFilter }
        val subsetTotal = subset.sumOf { it.total }
        if (subsetTotal > 0) subset.map { it.copy(percentage = it.total / subsetTotal) } else subset
    } else recalcCategoryTotals
    val displayOverviewTotal = if (analyticsShowBudgetedOnly) displayCategoryTotals.sumOf { it.total }
        else if (analyticsCategoryFilter.isEmpty()) totalOverviewSum else filteredOverviewTotal
    // Category-filtered transactions for flow & account analysis.
    // "Budgeted Only" has no UI toggle in Account Analysis (the category-filter icon is
    // only shown for the Overview/Flow modes), so it must never silently apply there —
    // otherwise Account Analysis inherits whatever budgeted-only state was last left on
    // in Expense/Income Overview, wiping out income (and sometimes expense) entries for
    // periods that don't happen to share the same budgeted categories.
    val categoryFilteredTxns = when {
        analyticsShowBudgetedOnly && selectedMode != AnalyticsMode.ACCOUNT_ANALYSIS ->
            filteredTransactions.filter { it.category.lowercase() in budgetedCatNamesForFilter }
        analyticsCategoryFilter.isEmpty() -> filteredTransactions
        else -> filteredTransactions.filter { it.category in analyticsCategoryFilter }
    }
    val flowPoints = remember(categoryFilteredTxns, analysisStart, analysisEnd) {
        buildDailyFlowPoints(categoryFilteredTxns, analysisStart, analysisEnd)
    }
    val accountStats = remember(categoryFilteredTxns) {
        buildAccountAnalytics(categoryFilteredTxns, c)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("analytics_scroll_column"),
        contentPadding = if (c.isBorderless) PaddingValues(horizontal = 6.dp, vertical = 4.dp) else PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        color = c.cardBg,
                        shape = RoundedCornerShape(24.dp),
                        border = if (c.isBorderless && !c.isDark) BorderStroke(1.dp, c.flatDivider) else if (!c.isBorderless) BorderStroke(1.dp, c.border) else null,
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
                                containerColor = if (c.isBorderless) Color.Transparent else c.divider,
                                contentColor = c.text
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.FilterList, contentDescription = "Select time period", modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showPeriodMenu,
                            onDismissRequest = { showPeriodMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = c.surfaceVariant,
                            shadowElevation = 10.dp,
                            modifier = Modifier.widthIn(min = 170.dp, max = 240.dp)
                        ) {
                            Text(
                                "PERIOD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = c.textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                            // Plain clickable Row instead of DropdownMenuItem: Material3's
                            // DropdownMenuItem enforces a 48dp minimum row height regardless
                            // of contentPadding, which was the cause of the large gaps between
                            // period entries — a custom Row lets us use a compact, real height.
                            listOf("DAILY" to "Daily", "WEEKLY" to "Weekly", "MONTHLY" to "Monthly", "3M" to "3 Months", "6M" to "6 Months", "1Y" to "1 Year").forEach { (key, label) ->
                                val isProPeriod = key == "3M" || key == "6M" || key == "1Y"
                                val ctx = LocalContext.current
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isProPeriod && !isPaid) { showPeriodMenu = false; Toast.makeText(ctx, "Pro feature — unlock in AutoLedger Pro", Toast.LENGTH_SHORT).show() }
                                            else { timeFilter = key; showPeriodMenu = false }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(label, color = if (timeFilter == key) c.accent else if (isProPeriod && !isPaid) c.textTertiary else c.text, fontSize = 13.sp)
                                    if (isProPeriod && !isPaid) Surface(color = Color(0xFFFFA000), shape = RoundedCornerShape(4.dp)) {
                                        Text("PRO", fontSize = 7.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                                    } else if (timeFilter == key) Icon(Icons.Default.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // Category filter icon (overview + flow modes)
                    if (selectedMode == AnalyticsMode.EXPENSE_OVERVIEW || selectedMode == AnalyticsMode.INCOME_OVERVIEW ||
                        selectedMode == AnalyticsMode.EXPENSE_FLOW || selectedMode == AnalyticsMode.INCOME_FLOW) {
                        val analyticsCatCtx = LocalContext.current
                        var showAnalyticsProUpgrade by remember { mutableStateOf(false) }
                        if (showAnalyticsProUpgrade) {
                            ProUpgradeDialog(
                                viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
                                onDismiss = { showAnalyticsProUpgrade = false }
                            )
                        }
                        Box {
                            FilledTonalIconButton(
                                onClick = {
                                    showAnalyticsCatFilterMenu = true  // always open; free users get first 3
                                },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (analyticsCategoryFilter.isNotEmpty()) c.accent.copy(alpha = 0.18f) else if (c.isBorderless) Color.Transparent else c.divider,
                                    contentColor = if (analyticsCategoryFilter.isNotEmpty()) c.accent else c.text
                                ),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Category, contentDescription = "Filter categories", modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(
                                expanded = showAnalyticsCatFilterMenu,
                                onDismissRequest = { showAnalyticsCatFilterMenu = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = c.surfaceVariant,
                                shadowElevation = 10.dp,
                                modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 350.dp)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                            Text("All Categories", fontSize = 13.sp, color = if (analyticsCategoryFilter.isEmpty() && !analyticsShowBudgetedOnly) c.accent else c.text,
                                                fontWeight = if (analyticsCategoryFilter.isEmpty() && !analyticsShowBudgetedOnly) FontWeight.Bold else FontWeight.Normal)
                                            if (analyticsCategoryFilter.isEmpty() && !analyticsShowBudgetedOnly) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(14.dp))
                                        }
                                    },
                                    onClick = {
                                        viewModel.setAnalyticsShowBudgetedOnly(false)
                                        when (selectedMode) {
                                            AnalyticsMode.EXPENSE_OVERVIEW, AnalyticsMode.EXPENSE_FLOW -> viewModel.setAnalyticsExpenseCatFilter(emptySet())
                                            AnalyticsMode.INCOME_OVERVIEW,  AnalyticsMode.INCOME_FLOW  -> viewModel.setAnalyticsIncomeCatFilter(emptySet())
                                            else -> {}
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(Icons.Default.Calculate, null, tint = if (analyticsShowBudgetedOnly) c.accent else c.textSecondary, modifier = Modifier.size(15.dp))
                                                Text("Budgeted Only", fontSize = 13.sp, color = if (analyticsShowBudgetedOnly) c.accent else c.text, fontWeight = if (analyticsShowBudgetedOnly) FontWeight.Bold else FontWeight.Normal)
                                            }
                                            if (analyticsShowBudgetedOnly) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(14.dp))
                                        }
                                    },
                                    onClick = {
                                        viewModel.setAnalyticsShowBudgetedOnly(!analyticsShowBudgetedOnly)
                                        // Clear individual cat filter when switching to budgeted-only
                                        if (!analyticsShowBudgetedOnly) when (selectedMode) {
                                            AnalyticsMode.EXPENSE_OVERVIEW, AnalyticsMode.EXPENSE_FLOW -> viewModel.setAnalyticsExpenseCatFilter(emptySet())
                                            AnalyticsMode.INCOME_OVERVIEW,  AnalyticsMode.INCOME_FLOW  -> viewModel.setAnalyticsIncomeCatFilter(emptySet())
                                            else -> {}
                                        }
                                    }
                                )
                                HorizontalDivider(color = c.divider)
                                categoryTotals.forEachIndexed { catIdx, cat ->
                                    val selected = cat.category.name in analyticsCategoryFilter
                                    val isFreeTier = catIdx < 3 || isPaid
                                    DropdownMenuItem(
                                        text = {
                                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                                                Icon(cat.category.icon, null, tint = if (isFreeTier) cat.category.color else cat.category.color.copy(alpha = 0.4f), modifier = Modifier.size(15.dp))
                                                Text(cat.category.displayName, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                                    color = when { selected -> cat.category.color; !isFreeTier -> c.textTertiary; else -> c.text },
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                                if (!isFreeTier) Surface(color = Color(0xFFFFA000), shape = RoundedCornerShape(4.dp)) {
                                                    Text("PRO", fontSize = 7.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                                                } else if (selected) Icon(Icons.Default.Check, null, tint = cat.category.color, modifier = Modifier.size(13.dp))
                                            }
                                        },
                                        onClick = {
                                            if (!isFreeTier) { showAnalyticsCatFilterMenu = false; showAnalyticsProUpgrade = true }
                                            else {
                                                val newFilter = if (selected) analyticsCategoryFilter - cat.category.name
                                                                else analyticsCategoryFilter + cat.category.name
                                                when (selectedMode) {
                                                    AnalyticsMode.EXPENSE_OVERVIEW, AnalyticsMode.EXPENSE_FLOW -> viewModel.setAnalyticsExpenseCatFilter(newFilter)
                                                    AnalyticsMode.INCOME_OVERVIEW,  AnalyticsMode.INCOME_FLOW  -> viewModel.setAnalyticsIncomeCatFilter(newFilter)
                                                    else -> {}
                                                }
                                            }
                                        }
                                    )
                                }
                            } // closes DropdownMenu
                        } // closes Box (analytics category filter)
                    }
                } // closes period-nav Row

                // Analysis mode — compact dropdown button
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showModeMenu = !showModeMenu },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                        border = BorderStroke(1.dp, if (showModeMenu) c.accent else c.divider),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(selectedMode.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.text)
                            Icon(
                                imageVector = if (showModeMenu) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = c.surface,
                        shadowElevation = 10.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AnalyticsMode.entries.forEachIndexed { i, mode ->
                            val isSelected = mode == selectedMode
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(mode.label,
                                            color = if (isSelected) c.accent else c.text,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp)
                                        if (isSelected) Icon(Icons.Default.Check, contentDescription = null,
                                            tint = c.accent, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = { viewModel.setSelectedAnalyticsModeIdx(i); showModeMenu = false }
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
                        categoryTotals = displayCategoryTotals,
                        totalAmount = displayOverviewTotal,
                        totalLabel = "Total Spent",
                        breakdownLabel = "CATEGORY WISE BREAKDOWN",
                        percentSuffix = "of expenses",
                        emptyMessage = "No expense data available for this period.",
                        onCategoryTap = { cat ->
                            val txs = overviewTransactions.filter { it.category.equals(cat.category.name, ignoreCase = true) }
                            categoryDetailItem = cat to txs
                        }
                    )
                }
            }

            AnalyticsMode.INCOME_OVERVIEW -> {
                item {
                    AnalyticsOverviewSection(
                        categoryTotals = displayCategoryTotals,
                        totalAmount = displayOverviewTotal,
                        totalLabel = "Total Received",
                        breakdownLabel = "INCOME BREAKDOWN",
                        percentSuffix = "of income",
                        emptyMessage = "No income data available for this period.",
                        onCategoryTap = { cat ->
                            val txs = overviewTransactions.filter { it.category.equals(cat.category.name, ignoreCase = true) }
                            categoryDetailItem = cat to txs
                        }
                    )
                }
            }

            AnalyticsMode.EXPENSE_FLOW -> {
                item {
                    val flowCtxExpense = LocalContext.current
                    AnalyticsFlowSection(
                        title = "Expense Flow",
                        points = flowPoints,
                        showIncome = false,
                        accent = c.expense,
                        emptyMessage = "No expense flow available for this period.",
                        onDayClick = { point ->
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = point.dateMillis }
                            val dayTxns = categoryFilteredTxns.filter { tx ->
                                val tc = java.util.Calendar.getInstance().apply { timeInMillis = tx.timestamp }
                                tc.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) &&
                                tc.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR) &&
                                tx.type == "EXPENSE"
                            }
                            if (dayTxns.isEmpty()) Toast.makeText(flowCtxExpense, "No expenses on ${point.fullLabel}", Toast.LENGTH_SHORT).show()
                            else flowDayItem = point to dayTxns
                        }
                    )
                }
            }

            AnalyticsMode.INCOME_FLOW -> {
                item {
                    val flowCtxIncome = LocalContext.current
                    AnalyticsFlowSection(
                        title = "Income Flow",
                        points = flowPoints,
                        showIncome = true,
                        accent = c.income,
                        emptyMessage = "No income flow available for this period.",
                        onDayClick = { point ->
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = point.dateMillis }
                            val dayTxns = categoryFilteredTxns.filter { tx ->
                                val tc = java.util.Calendar.getInstance().apply { timeInMillis = tx.timestamp }
                                tc.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) &&
                                tc.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR) &&
                                tx.type == "INCOME"
                            }
                            if (dayTxns.isEmpty()) Toast.makeText(flowCtxIncome, "No income on ${point.fullLabel}", Toast.LENGTH_SHORT).show()
                            else flowDayItem = point to dayTxns
                        }
                    )
                }
            }

            AnalyticsMode.ACCOUNT_ANALYSIS -> {
                item {
                    AnalyticsAccountSection(
                        accountStats = accountStats,
                        onAccountTap = { stats ->
                            val txs = categoryFilteredTxns.filter { tx ->
                                tx.getAccountName() == stats.accountName
                            }
                            accountDetailItem = stats to txs
                        }
                    )
                }
            }
        }
    }

    // Account breakdown detail dialog
    accountDetailItem?.let { (stats, txList) ->
        val decFormat = remember { DecimalFormat("₹#,##0.00") }
        val sdf = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
        // Track which individual rows have notes expanded; -1 sentinel not needed (managed per-id)
        var expandedAccountNoteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var allAccountNotesExpanded by remember { mutableStateOf(false) }
        val txsWithNotes = txList.filter { userNoteFrom(it.note).isNotBlank() }
        AlertDialog(
            onDismissRequest = { accountDetailItem = null },
            containerColor = c.surface,
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stats.accountName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.text)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("↑ ${compactCurrency(stats.income)}", fontSize = 12.sp, color = c.income, fontWeight = FontWeight.SemiBold)
                                Text("↓ ${compactCurrency(stats.expense)}", fontSize = 12.sp, color = c.expense, fontWeight = FontWeight.SemiBold)
                                Text("= ${compactCurrency(stats.net)}", fontSize = 12.sp, color = if (stats.net >= 0) c.accent else Color(0xFFFF7043), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (txsWithNotes.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    allAccountNotesExpanded = !allAccountNotesExpanded
                                    if (!allAccountNotesExpanded) expandedAccountNoteIds = emptySet()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    if (allAccountNotesExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                    contentDescription = if (allAccountNotesExpanded) "Hide all notes" else "Show all notes",
                                    tint = if (allAccountNotesExpanded) c.accent else c.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            },
            text = {
                if (txList.isEmpty()) {
                    Text("No transactions in this period.", color = c.textSecondary, fontSize = 13.sp)
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        txList.sortedByDescending { it.timestamp }.forEach { tx ->
                            val resolvedCat = CategoryResolver.resolve(tx.category, customCats)
                            val hasNote = userNoteFrom(tx.note).isNotBlank()
                            val isNoteExpanded = allAccountNotesExpanded || tx.id in expandedAccountNoteIds
                            Surface(
                                color = if (isNoteExpanded && hasNote) c.accent.copy(alpha = 0.06f) else c.divider,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (hasNote) Modifier.clickable {
                                        expandedAccountNoteIds = if (tx.id in expandedAccountNoteIds)
                                            expandedAccountNoteIds - tx.id
                                        else
                                            expandedAccountNoteIds + tx.id
                                    } else Modifier)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Surface(shape = CircleShape, color = resolvedCat.color.copy(0.15f), modifier = Modifier.size(32.dp)) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(resolvedCat.icon, null, tint = resolvedCat.color, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(tx.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                if (hasNote) Icon(Icons.Default.Notes, contentDescription = "Has note", tint = if (isNoteExpanded) c.accent else c.textTertiary, modifier = Modifier.size(10.dp))
                                            }
                                            Text(sdf.format(java.util.Date(tx.timestamp)), fontSize = 10.sp, color = c.textSecondary)
                                        }
                                    }
                                    Text(
                                        if (tx.type == "INCOME") "+${decFormat.format(tx.amount)}" else "-${decFormat.format(tx.amount)}",
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        color = if (tx.type == "INCOME") c.income else c.expense
                                    )
                                    }
                                    // Expanded notes — shown below the row, separated by a divider
                                    if (isNoteExpanded && hasNote) {
                                        val userNote = userNoteFrom(tx.note)
                                        if (userNote.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            HorizontalDivider(color = c.accent.copy(alpha = 0.2f))
                                            Spacer(Modifier.height(4.dp))
                                            Text(userNote, fontSize = 11.sp, color = c.text.copy(alpha = 0.85f),
                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { accountDetailItem = null }, colors = ButtonDefaults.buttonColors(containerColor = c.accent.copy(0.12f), contentColor = c.accent), elevation = ButtonDefaults.buttonElevation(0.dp)) { Text("Close", fontWeight = FontWeight.SemiBold) } }
        )
    }

    // Flow day breakdown dialog — shown when a calendar day is tapped in flow modes
    flowDayItem?.let { (point, txList) ->
        val decFormat = remember { DecimalFormat("₹#,##0.00") }
        val sdf = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
        val accent = if (txList.firstOrNull()?.type == "INCOME") c.income else c.expense
        var expandedFlowNoteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var allFlowNotesExpanded by remember { mutableStateOf(false) }
        val txsWithNotes = txList.filter { userNoteFrom(it.note).isNotBlank() }
        AlertDialog(
            onDismissRequest = { flowDayItem = null },
            containerColor = c.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(point.fullLabel, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.text)
                        Text(
                            "${txList.size} transaction${if (txList.size != 1) "s" else ""} · ${decFormat.format(txList.sumOf { it.amount })}",
                            fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (txsWithNotes.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                allFlowNotesExpanded = !allFlowNotesExpanded
                                if (!allFlowNotesExpanded) expandedFlowNoteIds = emptySet()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (allFlowNotesExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                contentDescription = if (allFlowNotesExpanded) "Hide all notes" else "Show all notes",
                                tint = if (allFlowNotesExpanded) accent else c.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            text = {
                if (txList.isEmpty()) {
                    Text("No transactions on this day.", color = c.textSecondary, fontSize = 13.sp)
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        txList.sortedByDescending { it.timestamp }.forEach { tx ->
                            val resolvedCat = CategoryResolver.resolve(tx.category, customCats)
                            val accountName = tx.getAccountName()
                            val acctType = allAccountsForAnalytics.find { it.name == accountName }?.type ?: ""
                            val acctColor = when (acctType) { "CASH" -> c.income; "BANK" -> Color(0xFF3B82F6); "CREDIT_CARD" -> c.expense; "DEBIT_CARD" -> Color(0xFF0EA5E9); "WALLET" -> Color(0xFFFF9800); else -> c.textSecondary }
                            val userNote = userNoteFrom(tx.note)
                            val hasNote = userNote.isNotBlank()
                            val isNoteExpanded = allFlowNotesExpanded || tx.id in expandedFlowNoteIds
                            Surface(
                                color = if (isNoteExpanded && hasNote) accent.copy(alpha = 0.06f) else c.divider,
                                shape = RoundedCornerShape(10.dp),
                                border = if (isNoteExpanded && hasNote) BorderStroke(1.dp, accent.copy(alpha = 0.25f)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (hasNote) Modifier.clickable {
                                        expandedFlowNoteIds = if (tx.id in expandedFlowNoteIds)
                                            expandedFlowNoteIds - tx.id
                                        else
                                            expandedFlowNoteIds + tx.id
                                    } else Modifier)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Surface(shape = CircleShape, color = resolvedCat.color.copy(0.15f), modifier = Modifier.size(32.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(resolvedCat.icon, null, tint = resolvedCat.color, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(tx.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                    if (hasNote) Icon(Icons.Default.Notes, contentDescription = "Has note", tint = if (isNoteExpanded) accent else c.textTertiary, modifier = Modifier.size(10.dp))
                                                }
                                                Spacer(Modifier.height(3.dp))
                                                // Account chip — same style as the Overview category-detail popup.
                                                Surface(color = acctColor.copy(0.12f), shape = RoundedCornerShape(4.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                        Icon(walletIconFor(accountName, acctType.ifEmpty { null }), null, tint = acctColor, modifier = Modifier.size(10.dp))
                                                        Text(accountName, fontSize = 9.sp, color = acctColor, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                            }
                                        }
                                        // Time now sits under the amount, right-aligned — matching the
                                        // Overview category-detail popup's transaction rows.
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                if (tx.type == "INCOME") "+${decFormat.format(tx.amount)}" else "-${decFormat.format(tx.amount)}",
                                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                                color = if (tx.type == "INCOME") c.income else c.expense
                                            )
                                            Text(sdf.format(java.util.Date(tx.timestamp)), fontSize = 10.sp, color = c.textSecondary)
                                        }
                                    }
                                    // Expanded notes — shown below the row, separated by a divider
                                    if (isNoteExpanded && hasNote) {
                                        Spacer(Modifier.height(6.dp))
                                        HorizontalDivider(color = accent.copy(alpha = 0.2f))
                                        Spacer(Modifier.height(4.dp))
                                        Text(userNote, fontSize = 11.sp, color = c.text.copy(alpha = 0.85f),
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { flowDayItem = null }, colors = ButtonDefaults.buttonColors(containerColor = c.accent.copy(0.12f), contentColor = c.accent), elevation = ButtonDefaults.buttonElevation(0.dp)) { Text("Close", fontWeight = FontWeight.SemiBold) } }
        )
    }

    // Category detail dialog
    categoryDetailItem?.let { (cat, txList) ->
        val amtFormatter = remember { java.text.DecimalFormat("#,##0.00") }
        val dateFormatter = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
        val txsWithNotesCat = txList.filter { userNoteFrom(it.note).isNotBlank() }
        val periodLabelA = formatAnalyticsPeriodLabel(rawMonthYear, timeFilter, anchorTime)

        Dialog(
            onDismissRequest = { categoryDetailItem = null; expandedNotesTxId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            EdgeToEdgeDialogWindowEffect(isDarkBackground = c.isDark)
            Surface(modifier = Modifier.fillMaxSize(), color = c.bg) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    Surface(shadowElevation = 6.dp, color = c.bg) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("Category Details", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.text)
                                Spacer(Modifier.height(1.dp))
                                Text("Period: $periodLabelA", fontSize = 11.sp, color = c.textSecondary)
                            }
                            IconButton(onClick = { categoryDetailItem = null; expandedNotesTxId = null }, modifier = Modifier.padding(end = 5.dp).size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.text.copy(alpha = 0.07f))) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = c.text, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = c.text.copy(alpha = 0.15f), thickness = 1.dp)
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // ── Header ────────────────────────────────────────────
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Surface(shape = CircleShape, color = cat.category.color.copy(alpha = 0.15f), modifier = Modifier.size(56.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(cat.category.icon, null, tint = cat.category.color, modifier = Modifier.size(30.dp)) }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cat.category.displayName, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                val catTypeLabel = if (cat.category.type == "INCOME") "Income Category" else "Expense Category"
                                Text(catTypeLabel, fontSize = 13.sp, color = c.textSecondary, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (c.isBorderless) HorizontalDivider(color = c.flatDivider)
                        // ── Fill chart: share of total ────────────────────────
                        Surface(color = if (c.isBorderless) Color.Transparent else c.cardBg, shape = RoundedCornerShape(14.dp), border = if (!c.isBorderless) BorderStroke(1.dp, c.border) else null, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("SHARE OF TOTAL", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = c.textSecondary)
                                // Pie (left half) + percentage info (right half), equal halves
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    // Left half: pie centered
                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        Box(modifier = Modifier.size(120.dp)) {
                                            Canvas(modifier = Modifier.fillMaxSize()) {
                                                val d = size.minDimension
                                                val rect = androidx.compose.ui.geometry.Size(d, d)
                                                val tl = androidx.compose.ui.geometry.Offset(0f, 0f)
                                                val catSweep = (cat.percentage * 360.0).toFloat().coerceIn(1f, 359f)
                                                val restSweep = 360f - catSweep
                                                if (restSweep > 0.5f) drawArc(color = cat.category.color.copy(alpha = 0.14f), startAngle = -90f + catSweep, sweepAngle = restSweep, useCenter = true, size = rect, topLeft = tl)
                                                drawArc(color = cat.category.color, startAngle = -90f, sweepAngle = catSweep, useCenter = true, size = rect, topLeft = tl)
                                                drawCircle(color = Color.White.copy(alpha = 0.25f), radius = d / 2f, center = center, style = Stroke(width = 1.5.dp.toPx()))
                                            }
                                        }
                                    }
                                    // Right half: text left-aligned, indented for visual centering
                                    Column(modifier = Modifier.weight(1f).padding(start = 20.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(String.format(Locale.getDefault(), "%.1f%%", cat.percentage * 100), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = cat.category.color)
                                        Text("of total spending", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(4.dp))
                                        Text("₹${amtFormatter.format(cat.total)}", fontSize = 14.sp, color = cat.category.color, fontWeight = FontWeight.Bold)
                                        Text("out of ₹${amtFormatter.format(displayOverviewTotal)}", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        if (c.isBorderless) HorizontalDivider(color = c.flatDivider)
                        // ── Transactions ──────────────────────────────────────
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${txList.size} TRANSACTION${if (txList.size != 1) "S" else ""}", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = c.textSecondary)
                            if (txsWithNotesCat.isNotEmpty()) {
                                val allExpanded = expandedNotesTxId == -1
                                IconButton(onClick = { expandedNotesTxId = if (allExpanded) null else -1 }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (allExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore, null, tint = if (allExpanded) c.accent else c.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        if (c.isBorderless) HorizontalDivider(color = c.flatDividerBold, thickness = if (c.isDark) 1.dp else 1.5.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(if (c.isBorderless) 0.dp else 2.dp)) {
                            txList.sortedByDescending { it.timestamp }.forEachIndexed { idx, tx ->
                                val accountName = tx.getAccountName()
                                val acctType = allAccountsForAnalytics.find { it.name == accountName }?.type ?: ""
                                val acctColor = when (acctType) { "CASH" -> c.income; "BANK" -> Color(0xFF3B82F6); "CREDIT_CARD" -> c.expense; "DEBIT_CARD" -> Color(0xFF0EA5E9); "WALLET" -> Color(0xFFFF9800); else -> c.textSecondary }
                                val userNote = userNoteFrom(tx.note); val hasNote = userNote.isNotBlank()
                                val isExpanded = expandedNotesTxId == -1 || expandedNotesTxId == tx.id
                                if (c.isBorderless && idx > 0) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 0.5.dp else 1.dp)
                                Surface(color = if (c.isBorderless) Color.Transparent else if (isExpanded && hasNote) acctColor.copy(0.06f) else c.divider, shape = RoundedCornerShape(if (c.isBorderless) 0.dp else 10.dp), border = if (!c.isBorderless && isExpanded && hasNote) BorderStroke(1.dp, acctColor.copy(0.3f)) else null, modifier = Modifier.fillMaxWidth().then(if (hasNote) Modifier.clickable { expandedNotesTxId = if (expandedNotesTxId == -1) null else if (isExpanded) null else tx.id } else Modifier)) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(tx.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                    if (hasNote) Icon(Icons.Default.Notes, null, tint = if (isExpanded) c.accent else c.textTertiary, modifier = Modifier.size(10.dp))
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Surface(color = acctColor.copy(0.12f), shape = RoundedCornerShape(4.dp)) {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                            Icon(walletIconFor(accountName, acctType.ifEmpty { null }), null, tint = acctColor, modifier = Modifier.size(10.dp))
                                                            Text(accountName, fontSize = 9.sp, color = acctColor, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        }
                                                    }
                                                }
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("₹${amtFormatter.format(tx.amount)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cat.category.color)
                                                Text(dateFormatter.format(java.util.Date(tx.timestamp)), fontSize = 10.sp, color = c.textSecondary)
                                            }
                                        }
                                        if (isExpanded && hasNote) {
                                            Spacer(Modifier.height(6.dp)); HorizontalDivider(color = acctColor.copy(0.2f)); Spacer(Modifier.height(4.dp))
                                            Text(userNote, fontSize = 11.sp, color = c.text.copy(0.85f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
} // closes AnalyticsScreen

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
    emptyMessage: String,
    onCategoryTap: ((DisplayCategorySpend) -> Unit)? = null
) {
    val c = LocalAppColors.current
    var activeSectorIndex by remember(categoryTotals, totalLabel) { mutableStateOf(-1) }
    val decFormat = remember { DecimalFormat("₹#,##0.00") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = c.cardBg,
            shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
            border = if (c.isBorderless) null else BorderStroke(1.dp, c.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                        // Chart uses ~55% of the available row width — big enough without crowding the legend
                        val chartSize = if (isCompact) (maxWidth * 0.55f).coerceIn(140.dp, 180.dp)
                                        else (maxWidth * 0.57f).coerceIn(155.dp, 220.dp)
                        val legendFontSize = if (isCompact) 11.sp else 12.sp
                        val chartContent: @Composable () -> Unit = {
                            Box(
                                modifier = Modifier
                                    .size(chartSize)
                                    .padding(2.dp)  // minimal padding — chart fills its allotted space
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
                                                width = if (isHighlighted) strokeWidthValue * 1.12f else strokeWidthValue,
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
                                            textSize = (sizeMin * 0.072f).coerceIn(8.sp.toPx(), 11.sp.toPx())
                                            isAntiAlias = true
                                            textAlign = android.graphics.Paint.Align.CENTER
                                        }
                                        val amtPaint = android.graphics.Paint().apply {
                                            color = c.text.toArgb()
                                            textSize = (sizeMin * 0.085f).coerceIn(9.sp.toPx(), 13.sp.toPx())
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
                                modifier = Modifier.fillMaxWidth(),
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
                                                .size(7.dp)
                                                .background(stats.category.color)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stats.category.displayName,
                                            color = c.text.copy(alpha = 0.85f),
                                            fontSize = legendFontSize,
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
                            Box(modifier = Modifier.weight(0.62f), contentAlignment = Alignment.Center) {
                                chartContent()
                            }
                            Box(modifier = Modifier.weight(0.38f)) { legendContent() }
                        }

                    }
                }
            }
        }

        if (categoryTotals.isNotEmpty()) {
            Column(verticalArrangement = if (c.isBorderless) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(4.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = breakdownLabel,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = c.textSecondary
            )
            if (c.isBorderless) HorizontalDivider(color = c.flatDividerBold, thickness = if (c.isDark) 1.dp else 1.5.dp)
            } // end title+underline Column

            categoryTotals.forEachIndexed { idx, stats ->
                val active = activeSectorIndex == idx
                if (c.isBorderless && idx > 0) HorizontalDivider(color = c.flatDivider)
                Surface(
                    color = if (c.isBorderless) Color.Transparent else if (active) stats.category.color.copy(alpha = 0.08f) else c.surface,
                    shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                    border = if (c.isBorderless) null else BorderStroke(1.dp, if (active) stats.category.color else c.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            activeSectorIndex = if (activeSectorIndex == idx) -1 else idx
                            onCategoryTap?.invoke(stats)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = if (c.isBorderless) 6.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = stats.category.color.copy(alpha = 0.15f),
                            modifier = Modifier.size(46.dp)
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
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stats.category.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = c.text,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(decFormat.format(stats.total), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.textSecondary)
                            }
                            Spacer(modifier = Modifier.height(13.dp))
                            LinearProgressIndicator(
                                progress = { stats.percentage.toFloat() },
                                color = stats.category.color,
                                trackColor = c.divider,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            // Spacer matching the "Remaining" text row height in budget boxes — keeps card height identical
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", stats.percentage * 100)}%",
                            fontSize = 14.sp,
                            color = stats.category.color,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(min = 40.dp),
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
    emptyMessage: String,
    onDayClick: ((AnalyticsFlowPoint) -> Unit)? = null
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
    var showAverageActiveDay by remember { mutableStateOf(false) }
    val averageAllDay = if (points.isNotEmpty()) total / points.size else 0.0
    val displayAverage = if (showAverageActiveDay) average else averageAllDay

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(decFormat.format(total), fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = accent)
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.clickable { showAverageActiveDay = !showAverageActiveDay }
                    ) {
                        Text(
                            if (showAverageActiveDay) "Average active day" else "Average day",
                            fontSize = 11.sp, color = c.textSecondary
                        )
                        Text(decFormat.format(displayAverage), fontWeight = FontWeight.Bold, color = c.text)
                    }
                }

                if (nonZeroPoints.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(emptyMessage, color = c.textSecondary, fontSize = 12.sp)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Chart with overlay tooltip (active point info shown on tap, not fixed above)
                        Box(
                            modifier = Modifier.fillMaxWidth().height(220.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
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
                                        // Respond immediately on press AND during drag
                                        // so every part of the canvas (start/end/middle) is responsive
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            fun selectAt(x: Float) {
                                                if (points.isNotEmpty()) {
                                                    activePointIndex = if (points.size == 1) 0
                                                    else ((x / size.width) * (points.size - 1))
                                                        .toInt().coerceIn(0, points.lastIndex)
                                                }
                                            }
                                            selectAt(down.position.x)
                                            do {
                                                val event = awaitPointerEvent()
                                                event.changes.firstOrNull { it.id == down.id }?.let {
                                                    it.consume()
                                                    selectAt(it.position.x)
                                                }
                                            } while (event.changes.any { it.pressed })
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
                        // Tooltip overlay: appears on tap, aligned to top of chart
                        activePoint?.let { point ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = accent.copy(alpha = 0.92f),
                                shadowElevation = 4.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(point.fullLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text("\u00b7", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                    Text(decFormat.format(activeValue), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        } // end Box

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
                                    .then(if (point != null && onDayClick != null) Modifier.clickable { onDayClick(point) } else Modifier)
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
private fun AnalyticsAccountSection(
    accountStats: List<AccountAnalyticsSummary>,
    onAccountTap: ((AccountAnalyticsSummary) -> Unit)? = null
) {
    val c = LocalAppColors.current
    val decFormat = remember { DecimalFormat("₹#,##0.00") }
    val maxActivity = accountStats.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1.0) ?: 1.0
    val yAxisValues = remember(maxActivity) {
        listOf(maxActivity, maxActivity * 0.66, maxActivity * 0.33, 0.0)
    }
    var activeAccountIndex by remember(accountStats) { mutableStateOf(-1) }
    val activeAccount = if (activeAccountIndex >= 0) accountStats.getOrNull(activeAccountIndex) else null

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ACCOUNT ACTIVITY CHART", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp, color = c.textSecondary)

                if (accountStats.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text("No account activity available for this period.", color = c.textSecondary, fontSize = 12.sp)
                    }
                } else {
                    // Chart with compact tooltip overlay (replaces the large header block)
                    Box(modifier = Modifier.fillMaxWidth()) {
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
                                    .pointerInput(accountStats) {
                                        detectTapGestures { offset ->
                                            if (accountStats.isNotEmpty()) {
                                                val slotWidth = size.width / accountStats.size
                                                val tapped = (offset.x / slotWidth).toInt().coerceIn(0, accountStats.lastIndex)
                                                // Tap same bar again to dismiss tooltip
                                                activeAccountIndex = if (activeAccountIndex == tapped) -1 else tapped
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
                                    val barWidth = slotWidth * 0.40f
                                    val gap = slotWidth * 0.05f
                                    val incomeCenterX = centerX - barWidth / 2f - gap / 2f
                                    val expenseCenterX = centerX + barWidth / 2f + gap / 2f
                                    val incomeY = topPad + chartHeight - ((stats.income / maxActivity) * chartHeight).toFloat()
                                    val expenseY = topPad + chartHeight - ((stats.expense / maxActivity) * chartHeight).toFloat()
                                    val barBottom = topPad + chartHeight
                                    val barAlpha = if (activeAccountIndex < 0 || index == activeAccountIndex) 1f else 0.65f

                                    if (index == activeAccountIndex) {
                                        drawRoundRect(
                                            color = c.divider,
                                            topLeft = Offset(slotWidth * index + 4.dp.toPx(), topPad),
                                            size = Size(slotWidth - 8.dp.toPx(), chartHeight),
                                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                                        )
                                    }

                                    if (stats.income > 0.0) {
                                        val barHeight = (barBottom - incomeY).coerceAtLeast(4.dp.toPx())
                                        val startY = barBottom - barHeight
                                        drawRoundRect(
                                            color = c.income,
                                            topLeft = Offset(incomeCenterX - barWidth / 2f, startY),
                                            size = Size(barWidth, barHeight),
                                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                            alpha = barAlpha
                                        )
                                    }
                                    if (stats.expense > 0.0) {
                                        val barHeight = (barBottom - expenseY).coerceAtLeast(4.dp.toPx())
                                        val startY = barBottom - barHeight
                                        drawRoundRect(
                                            color = c.expense,
                                            topLeft = Offset(expenseCenterX - barWidth / 2f, startY),
                                            size = Size(barWidth, barHeight),
                                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                            alpha = barAlpha
                                        )
                                    }
                                }
                            }
                        }

                        // Compact info tooltip at top-end (appears when a bar is tapped)
                        activeAccount?.let { stats ->
                            Surface(
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = c.surface,
                                border = BorderStroke(1.dp, stats.color.copy(alpha = 0.5f)),
                                shadowElevation = 6.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(stats.accountName, color = stats.color, fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("In  ${compactCurrency(stats.income)}", color = c.income, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Out ${compactCurrency(stats.expense)}", color = c.expense, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Net ${compactCurrency(stats.net)}",
                                            color = if (stats.net >= 0) c.accent else Color(0xFFFF7043),
                                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    // Labels row aligned with bars (invisible y-axis placeholder ensures correct offset)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Match y-axis column width using single transparent line (no height bloat)
                        Text(
                            text = compactCurrency(yAxisValues.firstOrNull() ?: 0.0),
                            fontSize = 10.sp,
                            color = Color.Transparent
                        )
                        Row(modifier = Modifier.weight(1f)) {
                            accountStats.forEachIndexed { index, stats ->
                                Text(
                                    text = run {
                                        val n = stats.accountName
                                        val upper = n.uppercase(java.util.Locale.getDefault())
                                        val isCreditCard = upper.contains("CARD") || upper.contains("CREDIT")
                                        val digits = n.filter { it.isDigit() }.takeLast(2)
                                        val prefix = n.split(" ").first().take(4)
                                            .uppercase(java.util.Locale.getDefault())
                                        when {
                                            isCreditCard && digits.isNotEmpty() -> "$prefix $digits"
                                            isCreditCard -> "$prefix CC"
                                            else -> prefix
                                        }
                                    },
                                    color = if (index == activeAccountIndex) stats.color else c.textSecondary,
                                    fontSize = 9.sp,
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
        }

        if (accountStats.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, stats.color.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth().then(
                        if (onAccountTap != null) Modifier.clickable { onAccountTap(stats) } else Modifier
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
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
            } // end Column(spacedBy(8dp))
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

/** Formats an amount with ₹ prefix, abbreviating large values: 1.2L, 3.5K, etc. */
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
    "bills" to Icons.AutoMirrored.Filled.ReceiptLong,
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
    "investment" to Icons.AutoMirrored.Filled.TrendingUp,
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
    "shoes" to Icons.AutoMirrored.Filled.DirectionsRun,
    "party" to Icons.Default.Celebration,
    "birthday" to Icons.Default.Cake,
    "vacation" to Icons.Default.BeachAccess,
    "beauty" to Icons.Default.Spa,
    "cab" to Icons.Default.LocalTaxi,
    "loan_emi" to Icons.Default.CreditScore,
    "lend" to Icons.Default.CallMade,
    "borrowed_money" to Icons.Default.CallReceived,
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
    "loan_emi" -> "Loan/EMI"
    "lend" -> "Lend"
    "borrowed_money" -> "Borrowed"
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

// ═══════════════════════════════════════════════════════════════════════════
// SCREEN 3 — BUDGETS
// ═══════════════════════════════════════════════════════════════════════════

/** Budgets tab — manage per-category monthly limits and expected income targets.
 * Features:
 *  - Global spend vs limit progress bar
 *  - Drag-to-reorder category cards (order persisted per type/tab)
 *  - Tap budgeted category → full-screen detail popup
 *  - "Copy from last month" for unbudgeted categories
 *  - Expense / Income category tabs
 */
@Composable
fun BudgetsScreen(viewModel: FinanceViewModel, listState: LazyListState = rememberLazyListState()) {
    val c = LocalAppColors.current
    val isPaid by viewModel.isPaidFeaturesEnabled.collectAsStateWithLifecycle()
    val txs by viewModel.allTransactions.collectAsStateWithLifecycle()
    val rawMonthYear by viewModel.selectedMonthYear.collectAsStateWithLifecycle()
    val activeBudgets by viewModel.monthlyBudgets.collectAsStateWithLifecycle()
    val customCats by viewModel.allCustomCategories.collectAsStateWithLifecycle(emptyList())

    // Dialog setups
    var showEditCategoryDialog by remember { mutableStateOf<DisplayCategory?>(null) }
    var showBudgetAmountDialog by remember { mutableStateOf<DisplayCategory?>(null) }
    var showCategoryMenuFor by remember { mutableStateOf<String?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showBudgetCategoryDetailFor by remember { mutableStateOf<DisplayCategory?>(null) }
    var showBudgetKebabFor by remember { mutableStateOf<String?>(null) }
    val budgetScreenCtx = LocalContext.current
    val showBudgetedOnly by viewModel.budgetShowBudgetedOnly.collectAsStateWithLifecycle()
    val activeCategoryTypeTab by viewModel.budgetCategoryTab.collectAsStateWithLifecycle()
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

    // Initialize / reset order: budget-set categories first (alpha), then others (alpha).
    // Runs synchronously during composition (not a LaunchedEffect) so the very first frame
    // already has the correct order — deferring this to an effect would flash an empty/
    // stale list for one frame every time a category is added/renamed. This is safe now
    // that the underlying spurious-rename bug (which used to make a category's name drift
    // every time its color was edited, repeatedly re-triggering this block) is fixed.
    if (categoryOrderKeys.isEmpty() ||
        categoryOrderKeys.any { key -> baseCategories.none { it.name == key } } ||
        baseCategories.any { cat -> cat.name !in categoryOrderKeys }) {
        // Restore persisted order; merge new categories in alphabetical position among unbudgeted items
        val saved = viewModel.getCategoryOrder(activeCategoryTypeTab)
        val validSaved = saved.filter { key -> baseCategories.any { it.name == key } }
        val missing = baseCategories.map { it.name }.filter { it !in validSaved }
            .sortedBy { name -> baseCategories.find { it.name == name }?.displayName ?: name }
        categoryOrderKeys = if (validSaved.isNotEmpty()) {
            // Keep budgeted categories in their saved order; preserve dragged unbudgeted order too
            val budgetedKeys   = validSaved.filter { k -> budgetCategoryNames.contains(k.lowercase()) }
            val unbudgetedFromSaved = validSaved.filter { k -> !budgetCategoryNames.contains(k.lowercase()) }
            val newMissing = missing.filter { it !in unbudgetedFromSaved && !budgetCategoryNames.contains(it.lowercase()) }
            val unbudgetedKeys = (unbudgetedFromSaved + newMissing).distinctBy { it.lowercase() }
            budgetedKeys + unbudgetedKeys
        } else {
            val withBudget = baseCategories.filter { budgetCategoryNames.contains(it.name.lowercase()) }.sortedBy { it.displayName }
            val withoutBudget = baseCategories.filter { !budgetCategoryNames.contains(it.name.lowercase()) }.sortedBy { it.displayName }
            (withBudget + withoutBudget).map { it.name }
        }
    }

    // Keep the invariant "all budgeted keys form a contiguous block before all unbudgeted
    // keys" in sync with the CURRENT budget assignments. Without this, a category that just
    // got a budget assigned (or lost one) stays wherever it was left in the flat order list
    // — e.g. it could still be sitting deep in the unbudgeted portion from when it was first
    // merged in alphabetically — and only jumps to the Budgeted section visually (since that
    // split is recomputed fresh every time from activeBudgets), landing in a seemingly random
    // position relative to the OTHER already-budgeted categories. This preserves each
    // category's relative order within its own group — only the group boundary moves — and
    // is a no-op once already partitioned correctly, so it's safe to run every recomposition.
    if (categoryOrderKeys.isNotEmpty()) {
        val repartitioned = categoryOrderKeys.filter { budgetCategoryNames.contains(it.lowercase()) } +
            categoryOrderKeys.filter { !budgetCategoryNames.contains(it.lowercase()) }
        if (repartitioned != categoryOrderKeys) {
            categoryOrderKeys = repartitioned
        }
    }

    // Auto-save order whenever it changes
    LaunchedEffect(categoryOrderKeys, activeCategoryTypeTab) {
        if (categoryOrderKeys.isNotEmpty()) viewModel.saveCategoryOrder(activeCategoryTypeTab, categoryOrderKeys)
    }
    val lookup = baseCategories.associateBy { it.name }
    val standardCategoriesList = categoryOrderKeys.mapNotNull { lookup[it] }
    val decFormat = DecimalFormat("₹#,##0.00")

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("budgets_scroll_column"),
        contentPadding = if (c.isBorderless) PaddingValues(horizontal = 6.dp, vertical = 4.dp) else PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Upper Title HUD
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.Transparent,
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
                                onClick = { viewModel.setMonthYear(shiftMonthYear(rawMonthYear, -1)) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month", tint = c.accent, modifier = Modifier.size(20.dp))
                            }
                            Text(
                                text = formatDisplay.format(currentMonthDate),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = c.text,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { viewModel.setMonthYear(shiftMonthYear(rawMonthYear, 1)) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Next month", tint = c.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    } // end period Surface

                    val budgetCtx2 = LocalContext.current
                    Box {
                        FilledTonalIconButton(
                            onClick = {
                                if (isPaid) viewModel.setBudgetShowBudgetedOnly(!showBudgetedOnly)
                                else Toast.makeText(budgetCtx2, "Budgeted categories only — Pro feature", Toast.LENGTH_SHORT).show()
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (showBudgetedOnly && isPaid) c.accent.copy(alpha = 0.18f) else if (c.isBorderless) Color.Transparent else c.divider,
                                contentColor = if (showBudgetedOnly && isPaid) c.accent else c.textSecondary
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (showBudgetedOnly && isPaid) Icons.Default.FilterList else Icons.Default.GridView,
                                contentDescription = if (showBudgetedOnly && isPaid) "Showing budgeted only" else "Showing all spending",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } // end Box

                    // Shrink to icon-only when EITHER the current month label itself is
                    // long enough to need the room (e.g. "September 2026", "November 2026")
                    // OR the available width is reduced (small screens) — short labels on
                    // a wide screen keep the full text button as before.
                    val monthLabelIsLong = formatDisplay.format(currentMonthDate).length > 11
                    BoxWithConstraints {
                        if (monthLabelIsLong || maxWidth < 260.dp) {
                            FilledTonalIconButton(
                                onClick = { showAddCategoryDialog = true },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = c.accent.copy(alpha = 0.12f),
                                    contentColor = c.accent
                                ),
                                modifier = Modifier.size(36.dp).testTag("add_custom_category_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Category", modifier = Modifier.size(18.dp))
                            }
                        } else {
                            Button(
                                onClick = { showAddCategoryDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = c.accent
                                ),
                                border = BorderStroke(0.5.dp, c.accent.copy(alpha = 0.55f)),
                                elevation = ButtonDefaults.buttonElevation(0.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp).testTag("add_custom_category_button")
                            ) {
                                Surface(shape = CircleShape, color = c.accent.copy(alpha = 0.18f), modifier = Modifier.size(18.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Add, contentDescription = "Add Category", tint = c.accent, modifier = Modifier.size(12.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Add Category", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Tab Selector for Expense vs Income Categories
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (c.isBorderless) Color.Transparent else c.text.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                val isExp = activeCategoryTypeTab == "EXPENSE"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isExp) c.accent.copy(alpha = 0.15f) else Color.Transparent)
                        .border(1.dp, if (isExp) c.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { viewModel.setBudgetCategoryTab("EXPENSE") }
                        .padding(vertical = 10.dp)
                        .testTag("categories_tab_expense"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Expense Categories", color = if (isExp) c.accent else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!isExp) c.accent.copy(alpha = 0.15f) else Color.Transparent)
                        .border(1.dp, if (!isExp) c.accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { viewModel.setBudgetCategoryTab("INCOME") }
                        .padding(vertical = 10.dp)
                        .testTag("categories_tab_income"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Income Categories", color = if (!isExp) c.accent else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
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
        val globalBudgetSpend = if (showBudgetedOnly) {
            monthExpenses.filter { tx -> budgetCategoryNames.contains(tx.category.lowercase()) }.sumOf { it.amount }
        } else {
            monthExpenses.sumOf { it.amount }
        }

        if (activeCategoryTypeTab == "EXPENSE" && globalBudgetLimit > 0) {
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
            val incomeBudgetCatNames = activeBudgets
                .filter { incomeCatNames.contains(it.category.lowercase()) }
                .map { it.category.lowercase() }.toSet()
            val incomeExpectedTotal = incomeBudgetCatNames.sumOf { catLower ->
                activeBudgets.firstOrNull { it.category.lowercase() == catLower }?.amountLimit ?: 0.0
            }
            val incomeReceivedTotal = txs.filter {
                val txMonth = sdfMonth.format(Date(it.timestamp))
                txMonth == rawMonthYear && it.type == "INCOME" &&
                    (!showBudgetedOnly || incomeBudgetCatNames.contains(it.category.lowercase()))
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
                verticalArrangement = if (c.isBorderless) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(8.dp)
            ) {
                if (budgetedCats.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (activeCategoryTypeTab == "EXPENSE") "BUDGETED" else "EXPECTED INCOME",
                            fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                            color = c.accent, modifier = Modifier.padding(vertical = 4.dp)
                        )
                        if (c.isBorderless) HorizontalDivider(color = c.flatDividerBold, thickness = if (c.isDark) 1.dp else 1.5.dp)
                    }
                }
                budgetedCats.forEachIndexed { budgetCatIdx, cat ->
                    // key() tells Compose to track this composable by cat.name (not list position).
                    // Without this, reordering resets the pointerInput coroutine, breaking multi-step drag.
                    key(cat.name) {
                    val isDragging = draggingItemKey == cat.name
                    val thresholdDp = 48.dp   // swap threshold
                    // Clamp visual offset so item never flies off screen during fast drag
                    val dragOffsetDp = with(LocalDensity.current) {
                        draggingItemOffsetY.coerceIn(-thresholdDp.toPx(), thresholdDp.toPx()).toDp()
                    }
                    // Debounce tracker to prevent overbuffering with many categories
                    val lastSwapMs = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
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
                        color = if (c.isBorderless) Color.Transparent else if (isDragging) Color(0xFF1E3048) else c.surface,
                        shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                        border = if (c.isBorderless) null else BorderStroke(1.dp, if (isDragging) cat.color else cat.color.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = if (isDragging) dragOffsetDp else 0.dp)
                            .scale(if (isDragging) 1.02f else 1f)
                            .clickable {
                                val catTxs = if (activeCategoryTypeTab == "EXPENSE") { monthExpenses.filter { it.category.equals(cat.name, ignoreCase = true) } } else { txs.filter { tx -> sdfMonth.format(Date(tx.timestamp)) == rawMonthYear && tx.type == "INCOME" && tx.category.equals(cat.name, ignoreCase = true) } }
                                if (catTxs.isEmpty()) { Toast.makeText(budgetScreenCtx, "No transactions for ${cat.displayName} this period.", Toast.LENGTH_SHORT).show() } else { showBudgetCategoryDetailFor = cat }
                            }
                            .pointerInput(cat.name) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { _ -> draggingItemKey = cat.name; draggingItemOffsetY = 0f },
                                onDragEnd = {
                                    draggingItemKey = null
                                    draggingItemOffsetY = 0f
                                    viewModel.saveCategoryOrder(activeCategoryTypeTab, categoryOrderKeys)
                                },
                                onDragCancel = { draggingItemKey = null; draggingItemOffsetY = 0f },
                                onDrag = { _, dragAmount ->
                                    val currentKey = draggingItemKey ?: return@detectDragGesturesAfterLongPress
                                    draggingItemOffsetY += dragAmount.y
                                    val currentIndex = categoryOrderKeys.indexOf(currentKey)
                                    if (currentIndex == -1) return@detectDragGesturesAfterLongPress
                                    val thresholdPx = thresholdDp.toPx()
                                    // Clamp + 200ms debounce to prevent multi-skip on fast drag or with many categories
                                    draggingItemOffsetY = draggingItemOffsetY.coerceIn(-thresholdPx * 1.4f, thresholdPx * 1.4f)
                                    val now = System.currentTimeMillis()
                                    if (now - lastSwapMs.longValue < 200L) return@detectDragGesturesAfterLongPress
                                    // Swap with next when dragged down
                                    if (draggingItemOffsetY > thresholdPx && currentIndex < categoryOrderKeys.lastIndex) {
                                        val newOrder = categoryOrderKeys.toMutableList()
                                        newOrder.add(currentIndex + 1, newOrder.removeAt(currentIndex))
                                        categoryOrderKeys = newOrder
                                        draggingItemOffsetY -= thresholdPx * 2f
                                        lastSwapMs.longValue = now
                                    }
                                    // Swap with previous when dragged up
                                    else if (draggingItemOffsetY < -thresholdPx && currentIndex > 0) {
                                        val newOrder = categoryOrderKeys.toMutableList()
                                        newOrder.add(currentIndex - 1, newOrder.removeAt(currentIndex))
                                        categoryOrderKeys = newOrder
                                        draggingItemOffsetY += thresholdPx * 2f
                                        lastSwapMs.longValue = now
                                    }
                                }
                            )
                        }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = cat.color.copy(alpha = 0.15f), modifier = Modifier.size(46.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(imageVector = cat.icon, contentDescription = cat.name, tint = cat.color, modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.text,
                                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("${compactCurrency(catSpend)} / ${compactCurrency(limit)}", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Box {
                                            IconButton(onClick = { showBudgetKebabFor = cat.name }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = c.textSecondary, modifier = Modifier.size(16.dp))
                                            }
                                            DropdownMenu(expanded = showBudgetKebabFor == cat.name, onDismissRequest = { showBudgetKebabFor = null }, containerColor = c.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                                                DropdownMenuItem(text = { Text("Edit Budget", color = c.text, fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.Edit, null, tint = c.accent, modifier = Modifier.size(16.dp)) }, onClick = { showBudgetAmountDialog = cat; showBudgetKebabFor = null })
                                                DropdownMenuItem(text = { Text("Edit Category", color = c.text, fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.Category, null, tint = c.accent, modifier = Modifier.size(16.dp)) }, onClick = { showEditCategoryDialog = cat; showBudgetKebabFor = null })
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Remaining: ${compactCurrency(remaining)}",
                                            fontSize = 11.sp,
                                            color = if (remaining < 0) c.expense else c.income,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${String.format(Locale.getDefault(), "%.1f", percent)}%",
                                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = progressColor
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { ratio.toFloat().coerceIn(0f, 1f) },
                                        color = progressColor,
                                        trackColor = c.text.copy(alpha = 0.05f),
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        }
                    } // end Surface
                    } // end key(cat.name)
                    if (c.isBorderless && budgetCatIdx < budgetedCats.size - 1) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 0.5.dp else 1.dp)
                } // end budgetedCats.forEach
                if (unbudgetedCats.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "NOT BUDGETED",
                            fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                            color = c.textSecondary, modifier = Modifier.padding(vertical = 4.dp)
                        )
                        if (c.isBorderless) HorizontalDivider(color = c.flatDividerBold, thickness = if (c.isDark) 1.dp else 1.5.dp)
                    }
                }
                unbudgetedCats.forEachIndexed { budgetCatIdx, cat ->
                    val catIncome = txs.filter {
                        val txMonth = sdfMonth.format(Date(it.timestamp))
                        txMonth == rawMonthYear && it.type == "INCOME" && it.category.equals(cat.name, ignoreCase = true)
                    }.sumOf { it.amount }
                    val catExpense = monthExpenses.filter { it.category.equals(cat.name, ignoreCase = true) }.sumOf { it.amount }
                    Surface(
                        color = c.cardBg,
                        shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                        border = if (c.isBorderless) null else BorderStroke(1.dp, c.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showEditCategoryDialog = cat }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = CircleShape, color = cat.color.copy(alpha = 0.15f), modifier = Modifier.size(46.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(imageVector = cat.icon, contentDescription = cat.name, tint = cat.color, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            if (activeCategoryTypeTab == "INCOME") {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (catIncome > 0) {
                                        Text("${compactCurrency(catIncome)} received", fontSize = 11.sp, color = c.income, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cat.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (catExpense > 0) {
                                        Text("${compactCurrency(catExpense)} spent", fontSize = 11.sp, color = c.expense, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedButton(
                                onClick = { showBudgetAmountDialog = cat },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text(if (activeCategoryTypeTab == "INCOME") "Set Expected" else "Set Budget", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (c.isBorderless && budgetCatIdx < unbudgetedCats.size - 1) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 0.5.dp else 1.dp)
                }  // end unbudgetedCats.forEachIndexed

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
                        ShrinkingSingleLineText(
                            text = "Copy last month's budgets",
                            color = c.textSecondary,
                            initialFontSize = 13f,
                            minFontSize = 9f,
                            modifier = Modifier,
                            fontWeight = FontWeight.SemiBold
                        )
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

        val editIconVec = suitableIconsList.firstOrNull { it.first == selectedIconName }?.second ?: Icons.Default.Category

        ModernDialogFrame(
            title = "Edit Category",
            icon = editIconVec,
            iconColor = selectedColor,
            onDismiss = { showEditCategoryDialog = null },
            actions = {
                OutlinedButton(
                    onClick = {
                        if (cat.isCustom) viewModel.deleteCustomCategory(cat.customId)
                        else viewModel.hideStandardCategory(cat.name, cat.type)
                        if (checkCurrent != null) viewModel.deleteBudget(checkCurrent.id)
                        showEditCategoryDialog = null
                    },
                    shape = RoundedCornerShape(percent = 50),
                    border = BorderStroke(1.dp, c.expense.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.expense),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).testTag("delete_custom_category_button")
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { showEditCategoryDialog = null },
                    shape = RoundedCornerShape(percent = 50),
                    border = BorderStroke(1.dp, c.text.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).testTag("cancel_category_changes_button")
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = {
                        val cleanName = editCatName.trim()
                        val oldKey = cat.name
                        if (cat.isCustom) {
                            viewModel.updateCustomCategory(
                                id = cat.customId,
                                oldName = cat.name,
                                newName = cleanName,
                                iconName = selectedIconName,
                                colorHex = selectedColorHex,
                                type = cat.type,
                                previousDisplayName = cat.displayName
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
                        // Keep renamed category at its current position in the order list
                        if (cleanName != oldKey && categoryOrderKeys.contains(oldKey)) {
                            categoryOrderKeys = categoryOrderKeys.map { if (it == oldKey) cleanName else it }
                            viewModel.saveCategoryOrder(activeCategoryTypeTab, categoryOrderKeys)
                        }
                        showEditCategoryDialog = null
                    },
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).testTag("save_category_changes_button")
                ) {
                    Text("Save", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        ) {
            OutlinedTextField(
                value = editCatName,
                onValueChange = { editCatName = it },
                label = { Text("Category Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.text,
                    unfocusedTextColor = c.text,
                    focusedBorderColor = c.accent,
                    focusedLabelColor = c.accent,
                    unfocusedBorderColor = c.textTertiary,
                    unfocusedLabelColor = c.textSecondary
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("edit_category_name_field")
            )

            Text("Select Icon", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(58.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
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
    }

    showBudgetAmountDialog?.let { cat ->
        var budgetValStr by remember(cat) { mutableStateOf("") }
        val checkCurrent = activeBudgets.firstOrNull { it.category.equals(cat.name, ignoreCase = true) }

        LaunchedEffect(cat) {
            checkCurrent?.let { budgetValStr = it.amountLimit.toInt().toString() }
        }

        AlertDialog(
            onDismissRequest = { showBudgetAmountDialog = null },
            containerColor = c.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = CircleShape, color = cat.color.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(cat.icon, contentDescription = null, tint = cat.color, modifier = Modifier.size(22.dp))
                        }
                    }
                    Column {
                        Text(
                            cat.displayName,
                            fontWeight = FontWeight.Bold, color = c.text, fontSize = 16.sp
                        )
                        Text(
                            if (cat.type == "INCOME") "Expected monthly income" else "Monthly budget limit",
                            fontSize = 11.sp, color = c.textSecondary
                        )
                    }
                }
            },
            text = {
                OutlinedTextField(
                    value = budgetValStr,
                    onValueChange = { budgetValStr = it },
                    label = { Text("₹ Amount", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text,
                        unfocusedTextColor = c.text,
                        focusedBorderColor = cat.color,
                        focusedLabelColor = cat.color,
                        unfocusedBorderColor = c.textTertiary,
                        unfocusedLabelColor = c.textSecondary
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("edit_category_budget_field")
                )
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
                        colors = ButtonDefaults.buttonColors(containerColor = cat.color, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (checkCurrent != null) "Update" else "Set", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetAmountDialog = null }) { Text("Cancel", color = c.textSecondary) }
            }
        )
    }

    // ── Budget category detail popup ─────────────────────────────────────────
    showBudgetCategoryDetailFor?.let { cat ->
        val amtFmt = remember { java.text.DecimalFormat("#,##0.00") }
        val sdf = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
        val categoryTxs = remember(cat.name, rawMonthYear, activeCategoryTypeTab) {
            if (activeCategoryTypeTab == "EXPENSE") {
                monthExpenses.filter { it.category.equals(cat.name, ignoreCase = true) }.sortedByDescending { it.timestamp }
            } else {
                txs.filter { tx -> sdfMonth.format(Date(tx.timestamp)) == rawMonthYear && tx.type == "INCOME" && tx.category.equals(cat.name, ignoreCase = true) }.sortedByDescending { it.timestamp }
            }
        }
        val budgetObj = activeBudgets.firstOrNull { it.category.equals(cat.name, ignoreCase = true) }
        val catSpendDetail = categoryTxs.sumOf { it.amount }
        val limitDetail = budgetObj?.amountLimit ?: 0.0
        val remaining = limitDetail - catSpendDetail
        // percentUsed uncapped — shows >100% when over budget
        val percentUsed = if (limitDetail > 0) (catSpendDetail / limitDetail * 100) else 0.0
        // fillFraction capped at 1f for visual bar
        val fillFraction = if (limitDetail > 0) (catSpendDetail / limitDetail).toFloat().coerceIn(0f, 1f) else 0f
        val progressColor = budgetProgressColor(percentUsed, c)
        val totalBudgetedSpend = monthExpenses.filter { tx -> activeBudgets.any { it.category.equals(tx.category, ignoreCase = true) } }.sumOf { it.amount }
        val ofTotalPct = if (totalBudgetedSpend > 0) (catSpendDetail / totalBudgetedSpend * 100) else 0.0
        val calDetail = remember { Calendar.getInstance() }
        val daysInMonth = calDetail.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dayOfMonth = calDetail.get(Calendar.DAY_OF_MONTH)
        val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(1)
        val avgDailySpend = if (dayOfMonth > 0) catSpendDetail / dayOfMonth else 0.0
        val expectedDailyBudget = if (daysInMonth > 0) limitDetail / daysInMonth else 0.0
        val dailyAllowanceLeft = if (limitDetail > 0) (limitDetail - catSpendDetail) / daysRemaining else 0.0
        // avg/day color: red if over daily target, else orange if close, else green
        val avgDayColor = when {
            avgDailySpend > expectedDailyBudget * 1.05 -> c.expense
            avgDailySpend > expectedDailyBudget * 0.9  -> Color(0xFFFB923C)
            else -> c.income
        }
        val monthLabel = remember(rawMonthYear) {
            try { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM", Locale.getDefault()).parse(rawMonthYear) ?: Date()) }
            catch (_: Exception) { rawMonthYear }
        }
        var expandedBudgetNoteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var allBudgetNotesExpanded by remember { mutableStateOf(false) }
        val txsWithNotes = categoryTxs.filter { userNoteFrom(it.note).isNotBlank() }

        Dialog(
            onDismissRequest = { showBudgetCategoryDetailFor = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            EdgeToEdgeDialogWindowEffect(isDarkBackground = c.isDark)
            Surface(modifier = Modifier.fillMaxSize(), color = c.bg) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    // ── Top bar ──────────────────────────────────────────────
                    Surface(shadowElevation = 6.dp, color = c.bg) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("Category Details", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.text)
                                Spacer(Modifier.height(1.dp))
                                Text("Period: $monthLabel", fontSize = 11.sp, color = c.textSecondary)
                            }
                            IconButton(onClick = { showBudgetCategoryDetailFor = null }, modifier = Modifier.padding(end = 5.dp).size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.text.copy(alpha = 0.07f))) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = c.text, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = c.text.copy(alpha = 0.15f), thickness = 1.dp)

                    // ── Scrollable body ──────────────────────────────────────
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                        // ── Header: icon + name + amount ─────────────────────
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Surface(shape = CircleShape, color = cat.color.copy(alpha = 0.15f), modifier = Modifier.size(56.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(cat.icon, null, tint = cat.color, modifier = Modifier.size(30.dp)) }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                Text(cat.displayName, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                if (limitDetail > 0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        ShrinkingSingleLineText(
                                            text = "₹${amtFmt.format(catSpendDetail)} of ₹${amtFmt.format(limitDetail)}",
                                            color = progressColor,
                                            initialFontSize = 12f,
                                            minFontSize = 8f,
                                            modifier = Modifier.weight(1f, fill = false),
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        val overBudget = remaining < 0
                                        val badgeColor = if (overBudget) c.expense else c.income
                                        Surface(color = badgeColor.copy(alpha = 0.12f), shape = RoundedCornerShape(5.dp)) {
                                            ShrinkingSingleLineText(
                                                text = if (overBudget) "↑ ₹${amtFmt.format(-remaining)} over" else "↓ ₹${amtFmt.format(remaining)} left",
                                                color = badgeColor,
                                                initialFontSize = 10f,
                                                minFontSize = 7f,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    Text("₹${amtFmt.format(catSpendDetail)} spent", fontSize = 14.sp, color = cat.color, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        if (c.isBorderless) HorizontalDivider(color = c.flatDivider)

                        // ── Progress ─────────────────────────────────────────
                        if (limitDetail > 0) {
                            Surface(color = if (c.isBorderless) Color.Transparent else c.cardBg, shape = RoundedCornerShape(14.dp), border = if (!c.isBorderless) BorderStroke(1.dp, c.border) else null, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(if (c.isBorderless) PaddingValues(vertical = 8.dp) else PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // Fill bar with text inside
                                    Box(modifier = Modifier.fillMaxWidth().height(44.dp).border(1.5.dp, progressColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
                                        Box(modifier = Modifier.fillMaxSize().background(progressColor.copy(alpha = 0.07f)))
                                        Box(modifier = Modifier.fillMaxWidth(fillFraction).fillMaxHeight().background(progressColor.copy(alpha = 0.65f)))
                                        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(String.format(Locale.getDefault(), "%.1f%%", percentUsed) + " of budget", fontSize = 13.sp, color = c.text, fontWeight = FontWeight.Bold)
                                            if (totalBudgetedSpend > 0) Text(String.format(Locale.getDefault(), "%.1f%%", ofTotalPct) + " of total", fontSize = 11.sp, color = c.text.copy(alpha = 0.75f))
                                        }
                                    }
                                }
                            }
                        }
                        if (c.isBorderless && limitDetail > 0) HorizontalDivider(color = c.flatDivider)

                        // ── Daily breakdown ──────────────────────────────────
                        if (limitDetail > 0) {
                            Surface(color = if (c.isBorderless) Color.Transparent else c.cardBg, shape = RoundedCornerShape(14.dp), border = if (!c.isBorderless) BorderStroke(1.dp, c.border) else null, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(if (c.isBorderless) PaddingValues(vertical = 8.dp) else PaddingValues(16.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // Day-of-month progress — how far through the month we
                                    // are (time elapsed), distinct from the spend-vs-budget
                                    // bar above so it can't be confused with it.
                                    val monthProgressFraction = (dayOfMonth.toFloat() / daysInMonth.toFloat()).coerceIn(0f, 1f)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.CalendarToday, null, tint = c.accent, modifier = Modifier.size(14.dp))
                                            Text("Daily Breakdown (Day $dayOfMonth of $daysInMonth)", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Medium)
                                        }
                                        Text(
                                            "${String.format(Locale.getDefault(), "%.0f", monthProgressFraction * 100)}%",
                                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3B82F6)
                                        )
                                    }
                                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.divider)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(monthProgressFraction)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color(0xFF3B82F6))
                                        )
                                    }
                                    HorizontalDivider(color = c.divider)
                                    // 3-column: avg spent / daily target / left per day — equal, centered
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Text("₹${amtFmt.format(avgDailySpend)}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = avgDayColor, textAlign = TextAlign.Center)
                                            Text("avg/day so far", fontSize = 10.sp, color = c.textSecondary, textAlign = TextAlign.Center)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Text("₹${amtFmt.format(expectedDailyBudget)}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF3B82F6), textAlign = TextAlign.Center)
                                            Text("daily target", fontSize = 10.sp, color = c.textSecondary, textAlign = TextAlign.Center)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            // 3-tier color: red once nothing is left, dark yellow while
                                            // what's left is still below the daily target (cutting it
                                            // close), green when comfortably at/above target.
                                            val leftPerDayColor = when {
                                                dailyAllowanceLeft <= 0 -> c.expense
                                                dailyAllowanceLeft < expectedDailyBudget -> Color(0xFFB8860B)
                                                else -> c.income
                                            }
                                            Text("₹${amtFmt.format(dailyAllowanceLeft.coerceAtLeast(0.0))}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = leftPerDayColor, textAlign = TextAlign.Center)
                                            Text(if (dailyAllowanceLeft < 0) "over budget" else "left/day", fontSize = 10.sp, color = c.textSecondary, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                        if (c.isBorderless && limitDetail > 0) HorizontalDivider(color = c.flatDivider)

                        // ── Transactions ─────────────────────────────────────
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${categoryTxs.size} TRANSACTION${if (categoryTxs.size != 1) "S" else ""}", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = c.textSecondary)
                            if (txsWithNotes.isNotEmpty()) {
                                val allExpanded = allBudgetNotesExpanded
                                IconButton(onClick = { allBudgetNotesExpanded = !allBudgetNotesExpanded; if (!allBudgetNotesExpanded) expandedBudgetNoteIds = emptySet() }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (allExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore, contentDescription = if (allExpanded) "Hide notes" else "Show notes", tint = if (allExpanded) c.accent else c.textSecondary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        if (c.isBorderless) HorizontalDivider(color = c.flatDividerBold, thickness = if (c.isDark) 1.dp else 1.5.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(if (c.isBorderless) 0.dp else 2.dp)) {
                        categoryTxs.forEachIndexed { idx, tx ->
                            val hasNote = userNoteFrom(tx.note).isNotBlank()
                            val isNoteExpanded = allBudgetNotesExpanded || tx.id in expandedBudgetNoteIds
                            if (c.isBorderless && idx > 0) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 0.5.dp else 1.dp)
                            Surface(
                                color = if (c.isBorderless) Color.Transparent else if (isNoteExpanded && hasNote) c.accent.copy(alpha = 0.06f) else c.divider,
                                shape = RoundedCornerShape(if (c.isBorderless) 0.dp else 10.dp),
                                border = if (!c.isBorderless && isNoteExpanded && hasNote) BorderStroke(1.dp, c.accent.copy(alpha = 0.3f)) else null,
                                modifier = Modifier.fillMaxWidth().then(if (hasNote) Modifier.clickable { expandedBudgetNoteIds = if (tx.id in expandedBudgetNoteIds) expandedBudgetNoteIds - tx.id else expandedBudgetNoteIds + tx.id } else Modifier)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(tx.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                if (hasNote) Icon(Icons.Default.Notes, contentDescription = "Has note", tint = if (isNoteExpanded) c.accent else c.textTertiary, modifier = Modifier.size(10.dp))
                                            }
                                            val bAccName = tx.getAccountName()
                                            val bAccType = when {
                                                bAccName.contains("card", ignoreCase = true) || bAccName.contains("credit", ignoreCase = true) -> "CREDIT_CARD"
                                                bAccName.contains("cash", ignoreCase = true) -> "CASH"
                                                bAccName.contains("wallet", ignoreCase = true) -> "WALLET"
                                                else -> "BANK"
                                            }
                                            val bAccColor = when (bAccType) { "CASH" -> c.income; "CREDIT_CARD" -> c.expense; "WALLET" -> Color(0xFFFF9800); else -> Color(0xFF3B82F6) }
                                            Surface(color = bAccColor.copy(0.12f), shape = RoundedCornerShape(4.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                    Icon(walletIconFor(bAccName, bAccType), null, tint = bAccColor, modifier = Modifier.size(10.dp))
                                                    Text(bAccName, fontSize = 9.sp, color = bAccColor, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("₹${amtFmt.format(tx.amount)}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cat.color)
                                            Text(sdf.format(Date(tx.timestamp)), fontSize = 10.sp, color = c.textSecondary)
                                        }
                                    }
                                    if (isNoteExpanded && hasNote) {
                                        val userNote = userNoteFrom(tx.note)
                                        Spacer(Modifier.height(6.dp))
                                        HorizontalDivider(color = c.accent.copy(alpha = 0.2f))
                                        Spacer(Modifier.height(4.dp))
                                        Text(userNote, fontSize = 11.sp, color = c.text.copy(alpha = 0.85f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                    }
                                }
                            }
                        }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }

    if (showAddCategoryDialog) {
        var newCatName by remember { mutableStateOf("") }
        var selectedIconName by remember { mutableStateOf("restaurant") }
        var selectedColorHex by remember { mutableStateOf("#00E5FF") }
        var selectedColor by remember { mutableStateOf(c.accent) }
        var selectedType by remember { mutableStateOf("EXPENSE") }
        var initialBudgetAmt by remember { mutableStateOf("") }
        val addCatIconVec = suitableIconsList.firstOrNull { it.first == selectedIconName }?.second ?: Icons.Default.Category

        ModernDialogFrame(
            title = "Add New Category",
            icon = addCatIconVec,
            iconColor = selectedColor,
            onDismiss = { showAddCategoryDialog = false },
            actions = {
                OutlinedButton(
                    onClick = { showAddCategoryDialog = false },
                    shape = RoundedCornerShape(percent = 50),
                    border = BorderStroke(1.dp, c.text.copy(alpha = 0.2f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).testTag("add_category_cancel_button")
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).testTag("add_category_confirm_button")
                ) {
                    Text("Add Category", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        ) {
            OutlinedTextField(
                value = newCatName,
                onValueChange = { newCatName = it },
                label = { Text("Category Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
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
                    Text("Expense", color = if (isExp) c.text else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Text("Income", color = if (!isExp) c.text else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (selectedType == "EXPENSE") {
                OutlinedTextField(
                    value = initialBudgetAmt,
                    onValueChange = { initialBudgetAmt = it },
                    label = { Text("Monthly Budget Limit (₹, Optional)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
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
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
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
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SCREEN 4 — ACCOUNTS
// ═══════════════════════════════════════════════════════════════════════════

/** Accounts tab — view and manage named wallets (Bank, Cash, Credit Card, Digital Wallet).
 * Shows combined net-worth balance, per-wallet balances, and optional running balance.
 * Supports inter-wallet fund transfers. Credit card accounts show available limit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(viewModel: FinanceViewModel, listState: LazyListState = rememberLazyListState()) {
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
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("accounts_scroll_column"),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Icon(Icons.Default.Settings, contentDescription = "Account Settings", tint = c.text)
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
                color = c.cardBg,
                shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
                border = if (c.isBorderless) null else BorderStroke(1.2.dp, c.accent.copy(alpha = 0.4f)),
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
                    if (c.isBorderless) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 0.5.dp else 1.dp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = CircleShape, color = c.income.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Income", tint = c.income, modifier = Modifier.size(16.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Income so far", fontSize = 11.sp, color = c.textSecondary, maxLines = 1)
                                Text(if (showTotal) decFormat.format(totalIncomeSoFar) else "₹ ••••", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.income, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // Expense so far
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = CircleShape, color = c.expense.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Expense", tint = c.expense, modifier = Modifier.size(16.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Expense so far", fontSize = 11.sp, color = c.textSecondary, maxLines = 1)
                                Text(if (showTotal) decFormat.format(totalExpenseSoFar) else "₹ ••••", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.expense, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Actions: inter-wallet transfer setup
        item {
            if (c.isBorderless) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 1.dp else 1.5.dp)
            Button(
                onClick = { showTransferDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.cardBg,
                    contentColor = c.text
                ),
                border = if (c.isBorderless) null else BorderStroke(1.dp, c.border),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = activeAccounts.size >= 2
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Transfer icon")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (activeAccounts.size >= 2) "Transfer Funds Between Accounts" else "Add at least 2 wallets to transfer funds",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        // List of configured mock cards
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
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
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                OutlinedButton(
                    onClick = { showAddAccountDialog = true },
                    shape = RoundedCornerShape(percent = 50),
                    border = if (c.isBorderless) null else BorderStroke(0.5.dp, c.accent.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Surface(shape = CircleShape, color = c.accent.copy(alpha = 0.18f), modifier = Modifier.size(18.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "Add Icon", tint = c.accent, modifier = Modifier.size(12.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Add Account", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                }
            }
            if (c.isBorderless) HorizontalDivider(color = c.flatDividerBold, thickness = if (c.isDark) 1.dp else 1.5.dp)
            } // end Column
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
                    verticalArrangement = if (c.isBorderless) Arrangement.spacedBy(0.dp) else Arrangement.spacedBy(8.dp)
                ) {
                    orderedAccounts.forEachIndexed { accIdx, acc ->
                        val bal = walletsBalances[acc.name] ?: 0.0
                        val smsTrackingBlocked = blockedSmsAccountIds.contains(acc.id)
                        val color = when(acc.type) {
                            "CASH" -> c.income
                            "BANK" -> c.accent
                            "CREDIT_CARD" -> c.expense
                            "DEBIT_CARD" -> Color(0xFF0EA5E9)
                            "WALLET" -> Color(0xFFFF9800)
                            else -> Color(0xFF94A3B8)
                        }

                        Surface(
                            color = c.cardBg,
                            shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(20.dp),
                            border = if (c.isBorderless) null else BorderStroke(1.dp, c.border),
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
                                        "DEBIT_CARD" -> Icons.Default.CreditCard
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
                        if (c.isBorderless && accIdx < orderedAccounts.size - 1) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 0.5.dp else 1.dp)
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

        AdaptiveEditorDialog(
            title = "Transfer Funds",
            onDismiss = { showTransferDialog = false },
            content = {
                    Text("Move funds between your accounts. Creates a single Transfer entry.", fontSize = 11.sp, color = c.textSecondary)

                    OutlinedTextField(
                        value = trAmount,
                        onValueChange = { trAmount = it },
                        label = { Text("Transfer Amount (₹)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            },
            actions = {
                Column {
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
                        enabled = trAmount.toDoubleOrNull()?.let { it > 0.0 } == true && sourceWall != destWall,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                    ) {
                        Text("Execute Transfer", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showTransferDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, c.divider),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        )
    }

    // Dialog: Add Custom Account Dialog — same AdaptiveEditorDialog frame as Edit Account.
    if (showAddAccountDialog) {
        var nameInput by remember { mutableStateOf("") }
        var baseBalanceInput by remember { mutableStateOf("") }
        var typeInput by remember { mutableStateOf("BANK") }
        var last4Input by remember { mutableStateOf("") }
        var openingBalanceTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

        val types = listOf("CASH", "BANK", "CREDIT_CARD", "DEBIT_CARD", "WALLET")

        AdaptiveEditorDialog(
            title = "Add Account",
            onDismiss = { showAddAccountDialog = false },
            content = {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Account Name (e.g. SBI Bank)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = baseBalanceInput,
                        onValueChange = { baseBalanceInput = it },
                        label = { Text("Initial Ledger Balance (₹)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        placeholder = { Text("e.g. 25000", color = c.text.copy(0.4f)) },
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
                        label = { Text("Last 4 digits (optional ID)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                    Text(t.replace("_", " "), fontSize = 9.sp, color = if (sel) c.accent else c.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
            },
            actions = {
                Column {
                    // Add Account — primary, full width, matching Edit Account's
                    // "Save Changes" button style/position.
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
                        enabled = nameInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                    ) {
                        Text("Add Account", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showAddAccountDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, c.divider),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary)
                    ) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
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

        val types = listOf("CASH", "BANK", "CREDIT_CARD", "DEBIT_CARD", "WALLET")

        AdaptiveEditorDialog(
            title = "Edit Account",
            onDismiss = { selectedAccountForEdit = null },
            content = {
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
                        label = { Text("Account Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editBalanceInput,
                        onValueChange = { editBalanceInput = it },
                        label = { Text("Set New Balance (₹)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        placeholder = { Text("e.g. 25000", color = c.text.copy(0.4f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.text, focusedBorderColor = c.accent),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (editType == "CREDIT_CARD") {
                        OutlinedTextField(
                            value = editCreditLimit,
                            onValueChange = { editCreditLimit = it },
                            label = { Text("Credit Limit (₹)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                    Text(t.replace("_", " "), fontSize = 9.sp, color = if (sel) c.accent else c.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
            },
            actions = {
                Column {
                    // Save Changes — primary, full width
                        Button(
                            onClick = {
                                val targetBal = editBalanceInput.toDoubleOrNull() ?: computedBal
                                val oldName = acc.name
                                if (Math.abs(targetBal - computedBal) >= 0.01) {
                                    viewModel.adjustAccountBalance(oldName, computedBal, targetBal)
                                }
                                val updatedAcc = acc.copy(
                                    name = editName,
                                    type = editType,
                                    lastFour = editName.filter { it.isDigit() }.takeLast(4).ifBlank { null },
                                    creditLimit = editCreditLimit.toDoubleOrNull() ?: acc.creditLimit,
                                    showCreditLimitBalance = editShowCreditLimitBalance
                                )
                                val metadataChanged = updatedAcc.name != acc.name ||
                                    updatedAcc.type != acc.type ||
                                    updatedAcc.lastFour != acc.lastFour ||
                                    updatedAcc.creditLimit != acc.creditLimit ||
                                    updatedAcc.showCreditLimitBalance != acc.showCreditLimitBalance
                                if (metadataChanged) viewModel.updateAccount(updatedAcc)
                                if (oldName != editName) viewModel.renameAccountTransactions(oldName, editName)
                                selectedAccountForEdit = null
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
                        ) {
                            Spacer(Modifier.width(8.dp))
                            Text("Save Changes", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        // Delete + Cancel in one row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.deleteAccount(acc.id)
                                    selectedAccountForEdit = null
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, c.expense.copy(0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.expense)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Delete", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { selectedAccountForEdit = null },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, c.divider),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary)
                            ) {
                                Text("Cancel", fontWeight = FontWeight.SemiBold)
                            }
                        }
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SCREEN 5 — SMS SCAN HUB
// ═══════════════════════════════════════════════════════════════════════════

/** SMS Scan tab — 100% on-device SMS parser for auto-tracking transactions.
 * Never uploads data; reads only verified bank/fintech SMS formats (-S/-T suffix).
 * Features:
 *  - Configurable scan range (1–3 months)
 *  - Custom inclusion/exclusion keyword rules
 *  - Manual SMS paste & analyze
 *  - Merchant → Category mapping rules (Pro)
 *  - Balance sync toggle
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoScanHubScreen(viewModel: FinanceViewModel, listState: LazyListState = rememberLazyListState()) {
    val c = LocalAppColors.current
    val isPaid by viewModel.isPaidFeaturesEnabled.collectAsStateWithLifecycle()
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
    val smsScanMonthsBack by viewModel.smsScanMonthsBack.collectAsStateWithLifecycle()
    val isSmsParsing by viewModel.isSmsParsing.collectAsStateWithLifecycle()
    val isWalletPfScanning by viewModel.isWalletPfScanning.collectAsStateWithLifecycle()
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
            .sortedBy { it.displayName }
    }
    var merchantPatternInput by remember { mutableStateOf("") }
    var merchantCategoryInput by remember { mutableStateOf("") }
    var merchantCategoryDropdownExpanded by remember { mutableStateOf(false) }
    // Secret tap counter for the security badge icon
    val parserKeyRulesVisible by viewModel.parserKeyRulesVisible.collectAsStateWithLifecycle()
    val parserExclusionVisible by viewModel.parserExclusionVisible.collectAsStateWithLifecycle()
    var secIconTapCount by remember { mutableStateOf(0) }
    val secIconTapScope = rememberCoroutineScope()
    var secIconTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("auto_scan_hub_screen"),
        contentPadding = if (c.isBorderless) PaddingValues(horizontal = 6.dp, vertical = 4.dp) else PaddingValues(horizontal = 10.dp, vertical = 4.dp),
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
                color = c.cardBg,
                shape = if (c.isBorderless) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
                border = if (c.isBorderless) null else BorderStroke(1.dp, c.accent.copy(0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Security badge",
                        tint = c.accent,
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                                secIconTapCount++
                                secIconTapJob?.cancel()
                                secIconTapJob = secIconTapScope.launch {
                                    kotlinx.coroutines.delay(1200)
                                    when {
                                        secIconTapCount >= 3 -> {
                                            if (!parserExclusionVisible) {
                                                viewModel.setParserExclusionVisible(true)
                                                Toast.makeText(context, "\uD83D\uDD13 Parser exclusion rules unlocked", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Exclusion rules already visible", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        secIconTapCount == 2 -> {
                                            if (parserExclusionVisible) {
                                                viewModel.setParserExclusionVisible(false)
                                                Toast.makeText(context, "\uD83D\uDD12 Parser exclusion rules hidden", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    secIconTapCount = 0
                                }
                            }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Secure Offline Automated Scanner", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.text)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Messages are parsed 100% on-device using offline pattern matching — no internet connection is used, no data is uploaded, and your financial information never leaves your phone.",
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
                            ShrinkingSingleLineText(
                                text = if (hasSmsPermissions) "GRANTED" else "REQUIRED",
                                color = if (hasSmsPermissions) c.income else c.expense,
                                initialFontSize = 10f,
                                minFontSize = 7f,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Bold
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
                                onClick = { viewModel.setSmsScanMonthsBack(months) },
                                color = if (selected) c.accent.copy(alpha = 0.15f) else c.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (selected) c.accent else c.border),
                                modifier = Modifier.weight(1f)
                            ) {
                                ShrinkingSingleLineText(
                                    text = label,
                                    color = if (selected) c.accent else c.text,
                                    initialFontSize = 11f,
                                    minFontSize = 7f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 8.dp),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
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
                            .fillMaxWidth()
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
                            ShrinkingSingleLineText("Scanning…", c.bg, 14f, 9f, Modifier, FontWeight.Bold, TextAlign.Center)
                        } else {
                            Icon(Icons.Default.Sms, contentDescription = null, tint = c.bg, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            ShrinkingSingleLineText(
                                text = if (hasReadSmsPermission) "Scan Inbox" else "Enable Auto-Import",
                                color = c.bg,
                                initialFontSize = 14f,
                                minFontSize = 9f,
                                modifier = Modifier,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Scan Wallets button (PF tracking removed)
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
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        if (isWalletPfScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            ShrinkingSingleLineText("Scanning…", c.accent, 14f, 9f, Modifier, FontWeight.SemiBold, TextAlign.Center)
                        } else {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            ShrinkingSingleLineText(
                                text = if (hasReadSmsPermission) "Scan Wallets" else "Enable Auto-Import",
                                color = c.accent,
                                initialFontSize = 14f,
                                minFontSize = 9f,
                                modifier = Modifier,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
        // Manual pasted SMS analyzer — shown only when Parser Key Rules are unlocked
        // Parser Key Rules — always visible (no unlock needed)
        item {
            if (c.isBorderless) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 1.dp else 1.5.dp)
            Card(
                colors = CardDefaults.cardColors(containerColor = c.cardBg),
                border = if (c.isBorderless) null else BorderStroke(1.dp, c.income.copy(0.5f)),
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
                        "  !(salary)  →  skip SMS containing \"salary\"\n" +
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
                    val chunkSize = 3
                    forcePatternOptions.chunked(chunkSize).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowItems.forEach { pattern ->
                                val selected = selectedForcePatterns.contains(pattern)
                                Surface(
                                    onClick = {
                                        selectedForcePatterns = if (selected)
                                            selectedForcePatterns - pattern
                                        else
                                            selectedForcePatterns + pattern
                                    },
                                    color = if (selected) c.income.copy(alpha = 0.18f) else c.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, if (selected) c.income.copy(alpha = 0.5f) else c.border),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = c.income, modifier = Modifier.size(10.dp))
                                        Spacer(Modifier.width(if (selected) 3.dp else 0.dp))
                                        Text(pattern, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                            color = if (selected) c.income else c.text,
                                            softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            // Pad incomplete last row so chips don't stretch
                            repeat(chunkSize - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
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
                            label = { Text("Add Custom Pattern", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                    label = { Text(pattern, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E), contentColor = Color.White),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        ShrinkingSingleLineText(
                            text = if (isSmsParsing) "Scanning…"
                            else if (!hasReadSmsPermission) "Enable SMS Permission to Scan"
                            else "Scan Inbox with These Rules",
                            color = Color.White,
                            initialFontSize = 14f,
                            minFontSize = 9f,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
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
                        label = { Text("Sender ID", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = manualSmsBody,
                        onValueChange = { manualSmsBody = it },
                        label = { Text("Real SMS Body", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("analyze_pasted_sms_button")
                    ) {
                        ShrinkingSingleLineText(
                            text = if (isSmsParsing) "Analyzing…" else "Analyze & Import Pasted SMS",
                            color = c.bg,
                            initialFontSize = 15f,
                            minFontSize = 9f,
                            modifier = Modifier,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // ── Merchant → Category Mapping ───────────────────────────────────────
        item {
            if (c.isBorderless) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 1.dp else 1.5.dp)
            ProGate(isPaid = isPaid, modifier = Modifier.fillMaxWidth()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = c.cardBg),
                border = if (c.isBorderless) null else BorderStroke(1.dp, Color(0xFF7C4DFF).copy(alpha = 0.4f)),
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
                        label = { Text("Merchant Pattern (e.g. *paytm*)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text,
                            focusedBorderColor = Color(0xFF7C4DFF), unfocusedBorderColor = Color(0xFF2D3748),
                            focusedLabelColor = Color(0xFF7C4DFF), unfocusedLabelColor = c.textSecondary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Category picker — OutlinedTextField style with icon + dropdown
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val selectedResolved = remember(merchantCategoryInput, allMerchantCategoryOptions) {
                            allMerchantCategoryOptions.firstOrNull { it.name == merchantCategoryInput }
                        }
                        OutlinedTextField(
                            value = selectedResolved?.displayName ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Target Category", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = selectedResolved?.let { cat ->
                                { Icon(cat.icon, contentDescription = null, tint = cat.color, modifier = Modifier.size(18.dp)) }
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = if (merchantCategoryDropdownExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null, tint = Color(0xFF7C4DFF), modifier = Modifier.size(22.dp)
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = c.text, unfocusedTextColor = c.text,
                                focusedBorderColor = Color(0xFF7C4DFF), unfocusedBorderColor = Color(0xFF2D3748),
                                focusedLabelColor = Color(0xFF7C4DFF), unfocusedLabelColor = c.textSecondary,
                                disabledTextColor = c.text, disabledBorderColor = Color(0xFF2D3748),
                                disabledLabelColor = c.textSecondary,
                                disabledLeadingIconColor = selectedResolved?.color ?: c.textSecondary,
                                disabledTrailingIconColor = Color(0xFF7C4DFF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Invisible overlay to intercept taps (readOnly TextField doesn't fire onClick)
                        Box(modifier = Modifier.matchParentSize().clickable {
                            merchantCategoryDropdownExpanded = !merchantCategoryDropdownExpanded
                        })
                        DropdownMenu(
                            expanded = merchantCategoryDropdownExpanded,
                            onDismissRequest = { merchantCategoryDropdownExpanded = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = c.surfaceVariant,
                            shadowElevation = 10.dp,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        ) {
                            allMerchantCategoryOptions.forEach { cat ->
                                val isSelected = cat.name == merchantCategoryInput
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(cat.icon, contentDescription = null, tint = cat.color, modifier = Modifier.size(16.dp))
                                            Text(cat.displayName, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                                color = if (isSelected) Color(0xFF7C4DFF) else c.text,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                            if (isSelected) Icon(Icons.Default.Check, contentDescription = null,
                                                tint = Color(0xFF7C4DFF), modifier = Modifier.size(14.dp))
                                        }
                                    },
                                    onClick = { merchantCategoryInput = cat.name; merchantCategoryDropdownExpanded = false }
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
            } // end ProGate (merchant rules)
        }

        // Parser exclusion rules reference card
        if (parserExclusionVisible) item {
            if (c.isBorderless) HorizontalDivider(color = c.flatDivider, thickness = if (c.isDark) 1.dp else 1.5.dp)
            Card(
                colors = CardDefaults.cardColors(containerColor = c.cardBg),
                border = if (c.isBorderless) null else BorderStroke(1.dp, c.expense.copy(alpha = 0.25f)),
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
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = 5,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("due", "emi", "otp", "load", "apply", "loan", "eligibility", "mandate", "approved").forEach { kw ->
                                Surface(color = c.expense.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                                    Text(kw, fontSize = 10.sp, color = c.expense, fontWeight = FontWeight.SemiBold,
                                        softWrap = false, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            }
                        }
                    }

                    // OTP / promo exclusions from SmsParser
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ALSO REJECTED (OTP / Promo / Reminder):", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                        val otpItems = listOf(
                            "reminder:", "pay before", "outstanding", "do not share", "earn cashback",
                            "will be debited", "requesting money",
                            "one time password", "verification code", "scheduled transfer"
                        ) // sorted shortest → longest; items covered by AUTO-REJECT keywords removed
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val W = maxWidth.value
                            val estCharW = 5.5f  // conservative: 9sp ≈ 5.5dp/char
                            val estPad = 10f     // 5dp each side
                            val gap = 4f

                            // Weight-aware greedy: each row gives equal width to all N chips.
                            // The LONGEST item in the row determines the minimum chip width.
                            // Constraint: (W - (N-1)*gap) / N  >=  longestLen * estCharW + estPad
                            val chipRows = mutableListOf<List<String>>()
                            var rowStart = 0
                            while (rowStart < otpItems.size) {
                                var rowEnd = rowStart
                                while (rowEnd + 1 < otpItems.size) {
                                    val newEnd = rowEnd + 1
                                    val n = newEnd - rowStart + 1
                                    val longestLen = otpItems[newEnd].length // sorted asc → last is longest
                                    val chipW = (W - (n - 1) * gap) / n
                                    if (chipW >= longestLen * estCharW + estPad) rowEnd = newEnd else break
                                }
                                chipRows.add(otpItems.subList(rowStart, rowEnd + 1))
                                rowStart = rowEnd + 1
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                chipRows.forEach { rowItems ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        rowItems.forEach { kw ->
                                            Surface(color = Color(0xFFFF9800).copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f)) {
                                                Text(kw, fontSize = 9.sp, color = Color(0xFFFF9800), fontWeight = FontWeight.SemiBold,
                                                    softWrap = false, textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // REQUIRED inclusion keywords
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("REQUIRED — must contain at least one:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.income)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            maxItemsInEachRow = 4,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("debited", "credited", "spent", "received", "deducted", "sent", "paid",
                                "withdrawn", "transfer", "payment", "charge", "txn", "salary",
                                "refund", "deposited", "autopay").forEach { kw ->
                                Surface(color = c.income.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp), modifier = Modifier.weight(1f)) {
                                    Text(kw, fontSize = 10.sp, color = c.income, fontWeight = FontWeight.SemiBold,
                                        softWrap = false, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp))
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

// ═══════════════════════════════════════════════════════════════════════════
// DIALOGS & UTILITY COMPOSABLES
// ═══════════════════════════════════════════════════════════════════════════

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
private fun ShrinkingSingleLineText(
    text: String,
    color: Color,
    initialFontSize: Float,
    minFontSize: Float,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Start
) {
    // Keyed on BOTH text and initialFontSize — keying on `text` alone meant that once the
    // dialog shrank (e.g. screen got smaller) and the font size was reduced to fit, the
    // remembered value never recovered when the screen returned to its normal size (since
    // `text`, e.g. the date/time label, hadn't changed). Including `initialFontSize` — which
    // is recomputed from the available height every time the container resizes — forces the
    // state to reset to the correct starting size whenever the layout's proportions change.
    var fontSize by remember(text, initialFontSize) { mutableStateOf(initialFontSize.sp) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize.value > minFontSize) {
                fontSize = (fontSize.value * 0.85f).coerceAtLeast(minFontSize).sp
            }
        }
    )
}

/** Forces a full-screen [Dialog]'s own Android Window to draw edge-to-edge (so
 * WindowInsets.safeDrawing / safeDrawingPadding are dispatched correctly) AND matches its
 * status/navigation bar icon contrast to the dialog's own background — instead of silently
 * inheriting whatever appearance the host Activity's window last set. Without this, a dialog
 * whose content extends behind the status bar (as all our full-screen dialogs do) can leave
 * status bar icons unreadable (e.g. light icons over a light dialog background) whenever the
 * dialog's theme differs from what the icons were last set up for.
 */
@Composable
private fun EdgeToEdgeDialogWindowEffect(isDarkBackground: Boolean) {
    val dialogView = LocalView.current
    SideEffect {
        (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, dialogView)
            insetsController.isAppearanceLightStatusBars = !isDarkBackground
            insetsController.isAppearanceLightNavigationBars = !isDarkBackground
        }
    }
}

/** Full-screen dialog to log a new cash flow (expense or income).
 * Features an inline 4-function calculator, category/account pickers,
 * date-time picker, and notes field.
 * Remembers the last used category and account for convenience.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    var categorySelection by remember { mutableStateOf(viewModel.getLastUsedCategory("EXPENSE").ifBlank { "FOOD" }) }
    var accountSelection by remember { mutableStateOf(viewModel.getLastUsedAccount()) }
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

    // Known payee names (from full transaction history) — used to flag a brand-new
    // Payee/Merchant entry with a "NEW" badge, the same visual cue used for freshly
    // imported SMS records elsewhere in the app.
    val txsForNewPayeeCheck by viewModel.allTransactions.collectAsStateWithLifecycle()
    val knownPayeeNames = remember(txsForNewPayeeCheck) {
        txsForNewPayeeCheck.map { it.title.trim().lowercase() }.toSet()
    }
    val isNewPayee = title.isNotBlank() && title.trim().lowercase() !in knownPayeeNames

    LaunchedEffect(accounts) {
        if (accounts.isNotEmpty() && accountSelection.isBlank()) {
            accountSelection = viewModel.getLastUsedAccount().ifBlank { accounts.first().name }
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

    val calcRowDef = remember {
        listOf(
            listOf("+" to "op", "7" to "num", "8" to "num", "9" to "num"),
            listOf("-" to "op", "4" to "num", "5" to "num", "6" to "num"),
            listOf("×" to "op", "1" to "num", "2" to "num", "3" to "num"),
            listOf("÷" to "op", "00" to "num", "0" to "num", "." to "num")
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // Force the dialog's own Window to draw edge-to-edge (so WindowInsets.safeDrawing /
        // safeDrawingPadding is dispatched correctly) and keep status/nav bar icons legible
        // against this dialog's own background.
        EdgeToEdgeDialogWindowEffect(isDarkBackground = c.isDark)
        // decorFitsSystemWindows(false) alone doesn't reliably stop the OS from still
        // resizing/panning this Window on every device/OS version when the keyboard opens
        // — that residual, uncontrolled resize was the "portrait glitch" (a small
        // shrink/shift creeping in despite excluding the IME inset below). Explicitly
        // disabling ALL automatic adjustment here guarantees the ONLY thing that can ever
        // move this dialog in response to the keyboard is our own manual offset further
        // down (which only applies in landscape) — portrait is now mathematically
        // guaranteed to never react to the keyboard at all.
        val dialogViewForIme = LocalView.current
        SideEffect {
            (dialogViewForIme.parent as? DialogWindowProvider)?.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
        }
        Surface(modifier = Modifier.fillMaxSize(), color = c.bg) {
            // Manual "pan" instead of a Window-level SOFT_INPUT_ADJUST_PAN: this dialog's
            // Window already has decorFitsSystemWindows(false) set (edge-to-edge), and once
            // that's set Android expects the app to handle IME insets itself rather than
            // relying on the legacy adjustPan/adjustResize window behavior — so setting
            // SOFT_INPUT_ADJUST_PAN on the window here silently has no effect. Instead we
            // read the live IME height and shift the WHOLE column up with a plain offset —
            // offsets never affect measurement/layout size, so nothing shrinks.
            //
            // LANDSCAPE ONLY, and only a fraction of the raw IME height, capped — portrait
            // must stay completely untouched (its rows are tall enough that the keyboard
            // never covers the focused field there), and shifting by the FULL IME height in
            // landscape pushed the very top row (Payee box) up far enough to disappear
            // behind the status bar. Reduced by ~1/4 (not half) of the raw height, capped
            // at 90dp.
            //
            // Portrait must NEVER read WindowInsets.ime at all (not even indirectly via
            // safeDrawing.exclude(ime)) — the animated ime inset value changes every frame
            // while the keyboard is sliding in/out, and Compose recomposes/remeasures any
            // reader of that combined insets object on every one of those frames even
            // though the excluded net result is always 0dp. That per-frame remeasure of the
            // BoxWithConstraints below (whose `scale` is derived straight from its measured
            // height) is what was still visible as a brief shrink/pan in portrait despite
            // SOFT_INPUT_ADJUST_NOTHING. Building portrait's padding purely from
            // statusBars + navigationBars + displayCutout — none of which ever change when
            // the keyboard animates — makes it truly immune, not just "offset by zero".
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscapeOrientation = configuration.screenWidthDp > configuration.screenHeightDp
            val outerModifier = if (isLandscapeOrientation) {
                val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
                val imeOffsetDp = (with(LocalDensity.current) { imeBottomPx.toDp() } * 0.75f).coerceAtMost(90.dp)
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                    .offset(y = -imeOffsetDp)
            } else {
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(WindowInsets.displayCutout)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(outerModifier)
            ) {

                // ── Body: every section (including the top controls row and the
                // calculator) gets a proportional weight of the available height. This
                // guarantees the WHOLE dialog always fits exactly — no scrolling, no
                // overflow, nothing ever hidden or clipped. The Close/Title/Save row used
                // to be a FIXED-height Surface living OUTSIDE this weight system — if the
                // dialog's total measured height was ever wrong (a known Compose Dialog
                // insets bug — see repo memory), that fixed row would consume its normal
                // height regardless, silently pushing the proportional error entirely onto
                // the content below it (worst-case: the calculator). Folding it into the
                // SAME weighted Column means every section — including the top row —
                // shares in any shrink/error equally, instead of one part of the screen
                // absorbing 100% of the discrepancy.
                // Material's OutlinedTextField / OutlinedButton both enforce their own
                // minimum touch-target height, which silently broke this math in earlier
                // attempts — replaced below with plain Surface/BasicTextField equivalents
                // that have no such floor and can shrink to whatever weight gives them.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val totalH = maxHeight
                    // Landscape packs the SAME sections into far fewer stacked rows per
                    // column (2-column layout below), so each row gets a much bigger share
                    // of totalH there than the equivalent portrait row does. Every size
                    // computed from `scale` further down needs to know this up front so
                    // landscape text/icons can be sized to actually fill their (bigger) box
                    // instead of looking small — see the isLandscape branches below.
                    val isLandscape = maxWidth > totalH

                    // Shared date/time state
                    val dateLabel = remember(selectedTimestamp) { java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(selectedTimestamp)) }
                    val timeLabel = remember(selectedTimestamp) { java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(selectedTimestamp)) }
                    val dialogCtxShared = LocalContext.current
                    val isDarkForPickerShared = isSystemInDarkTheme()
                    val pickerCtx = remember(isDarkForPickerShared, dialogCtxShared) { android.view.ContextThemeWrapper(dialogCtxShared, if (isDarkForPickerShared) android.R.style.Theme_Material_Dialog else android.R.style.Theme_Material_Light_Dialog) }

                    // Account / category shared values
                    val acctType  = accounts.find { it.name == accountSelection }?.type
                    val acctIcon  = walletIconFor(accountSelection, acctType)
                    val acctColor = when (acctType) { "CASH" -> c.income; "BANK" -> c.accent; "CREDIT_CARD" -> c.expense; "DEBIT_CARD" -> Color(0xFF0EA5E9); "WALLET" -> Color(0xFFFF9800); else -> c.accent }
                    val catIcon   = selectedCategory?.icon ?: Icons.Default.Category
                    val catColor  = selectedCategory?.color ?: c.accent

                    // Fonts/paddings shrink together with the screen — same totalH the calculator uses.
                    val scale  = (totalH.value / 700f).coerceIn(0.55f, 1f)
                    // Slightly more breathing room between rows, as requested.
                    val gap    = (7.dp * scale).coerceAtLeast(4.dp)
                    // Landscape variants are deliberately bigger at the same `scale` — each
                    // row there gets proportionally more of totalH than in portrait (fewer
                    // rows sharing the same column height), so a portrait-sized font/icon
                    // would look undersized inside a taller landscape box. All of these still
                    // safely shrink-to-fit (onTextLayout / ShrinkingSingleLineText) if a box
                    // ever ends up smaller than expected, so this can never overflow.
                    // lbFsz dialed back down (15→12/13) — the previous bump made the
                    // Account/Category sub-labels look too big/heavy.
                    val lbFsz  = ((if (isLandscape) 13f else 12f) * scale).coerceAtLeast(10f)
                    val inFsz  = ((if (isLandscape) 17f else 14f) * scale).coerceAtLeast(if (isLandscape) 12f else 10f)
                    // Dedicated, slightly bigger font just for the Payee/Merchant field —
                    // Date/Time intentionally keeps using its own dedicated `dtFsz` below so
                    // neither grows along with the other. Landscape floor bumped 11f→17f —
                    // landscape's shorter totalH keeps `scale` pinned near its 0.55 floor,
                    // which was silently overriding the bigger 19f base and leaving this
                    // stuck at its old, too-small floor regardless.
                    val payeeFsz = ((if (isLandscape) 19f else 16f) * scale).coerceAtLeast(if (isLandscape) 17f else 11f)
                    // Landscape Date/Time bumped up a bit, as requested — portrait unchanged
                    // (still uses the shared `inFsz`).
                    val dtFsz  = ((if (isLandscape) 21f else 14f) * scale).coerceAtLeast(if (isLandscape) 14f else 10f)
                    // Landscape floor bumped 13f→18f — same `scale`-pinned-to-floor issue
                    // as payeeFsz above was keeping this stuck too small under the titlebar.
                    val tgFsz  = ((if (isLandscape) 22f else 16f) * scale).coerceAtLeast(if (isLandscape) 18f else 13f)
                    // Landscape icon box dialed back a touch (26→22) — was reported bigger
                    // than it needed to be.
                    val icSz   = if (isLandscape) (22.dp * scale).coerceAtLeast(18.dp) else (17.dp * scale).coerceAtLeast(13.dp)
                    val bsSz   = (40.dp * scale).coerceAtLeast(26.dp)
                    val amtFszBase   = if (isLandscape) 30f else 24f
                    val titleFszBase = if (isLandscape) 27f else 21f
                    val saveFszBase  = if (isLandscape) 19f else 16f
                    // `scale` is often near its 0.55 floor in landscape (short totalH), which
                    // was crushing titleFszBase/saveFszBase back down close to their old
                    // small size despite the bigger base — these floors are raised
                    // specifically for landscape so the title/Save text actually stays big,
                    // matching the (already fixed-size, unscaled) bigger Close icon.
                    val titleFszFloor = if (isLandscape) 20f else 14f
                    val saveFszFloor  = if (isLandscape) 15f else 11f

                    // A shared, centered text style — disables extra font-padding and centers
                    // line-height so short text aligns visually with the middle of its
                    // container instead of drifting toward the bottom/border as the container
                    // shrinks (Compose's default Text vertical metrics otherwise leave
                    // asymmetric ascent/descent space around short single-line text). Declared
                    // early so every section below (type toggle, account/category buttons) can
                    // share it.
                    val centeredBtnTextStyle = LocalTextStyle.current.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)
                    )

                    // A bordered, clickable row with NO enforced Material min-size — unlike
                    // OutlinedButton, this can shrink to whatever height weight() gives it.
                    // NOTE: uses the plain Surface constructor + Modifier.clickable — NOT
                    // Surface(onClick = ...), which silently applies a 48dp minimum
                    // interactive-component size and would reintroduce the same
                    // "clipped calculator" bug this rewrite is meant to fix.
                    @Composable
                    fun CustomOutlinedButton(modifier: Modifier, borderColor: Color, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp), onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
                        Surface(shape = shape, color = Color.Transparent, border = BorderStroke(1.dp, borderColor), modifier = modifier.clickable(onClick = onClick)) {
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
                        }
                    }

                    // A bordered text field built on BasicTextField — NO enforced Material
                    // min-height either, so it truly shrinks with the proportional layout.
                    // The label only floats up onto the border when the field is FOCUSED or
                    // already has content — matching the real Material OutlinedTextField
                    // behavior used in the Update Transaction dialog — instead of always
                    // sitting on the border regardless of focus.
                    //
                    // The floating label is offset UPWARD by half its own line height so it
                    // visually straddles the border line (its opaque background "cuts" a
                    // gap in the border stroke) exactly like Update Transaction's fields,
                    // instead of sitting fully inside the box. The content row below is
                    // reserved its own real space (a Spacer, not just padding under an
                    // overlay) sized to clear the label's lower half, so typed text can
                    // never end up hidden behind the label chip.
                    @Composable
                    fun CompactTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier, singleLine: Boolean = true, maxLines: Int = 1, showNewBadge: Boolean = false, contentFontSizeSp: Float? = null, reserveTopClearance: Boolean = true) {
                        val vAlign = if (singleLine) Alignment.CenterStart else Alignment.TopStart
                        var isFocused by remember { mutableStateOf(false) }
                        val floated = isFocused || value.isNotEmpty()
                        val fieldFsz = contentFontSizeSp ?: inFsz
                        val labelFs = (lbFsz * 0.82f).coerceAtLeast(8f)
                        // Approximate rendered line-height for the label's font size, used
                        // to center the label vertically ON the border line (half above,
                        // half below it) and to reserve just enough content-row clearance.
                        val labelLineHeight = labelFs * 1.25f
                        val topGap = (labelLineHeight / 2f + 4f).dp
                        val borderAlpha = if (isFocused) 0.9f else 0.4f
                        // A small fixed clearance ABOVE the bordered box itself (not just
                        // the internal label gap) — the floating label straddles the TOP
                        // border and pokes above the box's own bounds, which looked cramped
                        // against whatever sits directly above it (Type toggle / Date-time
                        // row) with no breathing room. Reserved unconditionally (not just
                        // while floated) so the box's size/position never jumps as it
                        // focuses/unfocuses. Skippable (reserveTopClearance = false) for
                        // instances that sit beside same-height sibling boxes in a Row
                        // (e.g. landscape's icon-only Account/Category buttons), where
                        // shrinking only this box would make it visibly shorter than them.
                        Box(modifier = if (reserveTopClearance) modifier.padding(top = 5.dp) else modifier) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, c.accent.copy(borderAlpha), RoundedCornerShape(8.dp))
                            ) {
                                // Reserved gap for the floating label — only present once
                                // floated. While unfocused/empty (no gap), the placeholder
                                // is perfectly vertically centered in the WHOLE box, exactly
                                // like Update Transaction's fields; it shifts down to make
                                // room for the label the moment it floats.
                                Spacer(Modifier.height(if (floated) topGap else 0.dp))
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = if (singleLine) 0.dp else 6.dp),
                                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
                                ) {
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = vAlign) {
                                        // Inline placeholder — only shown while unfocused AND
                                        // empty, i.e. exactly when the label is NOT floated.
                                        if (!floated) {
                                            Text(placeholder, fontSize = fieldFsz.sp, color = c.textSecondary.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        val focusManagerForField = LocalFocusManager.current
                                        BasicTextField(
                                            value = value,
                                            onValueChange = onValueChange,
                                            singleLine = singleLine,
                                            maxLines = maxLines,
                                            // includeFontPadding = false removes Compose's default
                                            // extra ascent/descent spacing that was pushing this
                                            // text visibly off-center (toward the bottom) inside
                                            // its Box despite Alignment.CenterStart — same fix
                                            // already used by `centeredBtnTextStyle` elsewhere.
                                            textStyle = TextStyle(fontSize = fieldFsz.sp, color = c.text, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                            cursorBrush = SolidColor(c.accent),
                                            // Always a "Done" key (checkmark), even for the
                                            // multi-line Notes field — Notes previously fell
                                            // back to the default newline/return icon since it's
                                            // not singleLine, unlike Payee which got "Done" by
                                            // default. Pressing it now just dismisses the
                                            // keyboard instead of inserting a newline.
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                            keyboardActions = KeyboardActions(onDone = { focusManagerForField.clearFocus() }),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(if (!singleLine) Modifier.fillMaxHeight() else Modifier)
                                                .onFocusChanged { isFocused = it.isFocused }
                                        )
                                    }
                                    // "NEW" badge — sits INSIDE the box at its right-most end
                                    // (same visual chip used for newly-imported records),
                                    // reserved via a real Row slot so it never overlaps text.
                                    if (showNewBadge && floated && value.isNotBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(color = c.income.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                            Text("NEW", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = c.income, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    }
                                }
                            }
                            // Floating label — only rendered once focused/filled. Offset
                            // upward by half its own line height so its vertical CENTER
                            // lands exactly on the border line (straddling it, same as a
                            // real Material OutlinedTextField), with an opaque background
                            // matching the dialog so it visually "cuts" a gap in the border.
                            if (floated) {
                                Text(
                                    placeholder,
                                    fontSize = labelFs.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isFocused) c.accent else c.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = centeredBtnTextStyle,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = 8.dp, y = -(labelLineHeight / 2f).dp)
                                        .background(c.bg)
                                        .padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }

                    @Composable
                    fun TypeToggle(modifier: Modifier) {
                        Row(modifier = modifier.background(c.text.copy(0.05f), RoundedCornerShape(6.dp)).padding((2.dp * scale).coerceAtLeast(1.dp))) {
                            listOf("EXPENSE" to "Expense", "INCOME" to "Income").forEach { (type, label) ->
                                val sel = transactionType == type
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (sel) (if (type == "EXPENSE") c.expense else c.income) else Color.Transparent).clickable { transactionType = type; categorySelection = if (type == "EXPENSE") "FOOD" else "SALARY" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    var fs by remember(scale) { mutableStateOf(tgFsz.sp) }
                                    Text(label, color = if (sel) Color.White else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = fs, style = centeredBtnTextStyle, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false, onTextLayout = { r -> if (r.hasVisualOverflow && fs.value > 9f) fs = (fs.value * 0.85f).sp })
                                }
                            }
                        }
                    }

                    @Composable
                    fun DateTimeRow(modifier: Modifier, fontSize: Float = inFsz) {
                        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CustomOutlinedButton(modifier = Modifier.weight(1f).fillMaxHeight(), borderColor = c.accent.copy(0.5f), shape = RoundedCornerShape(percent = 50), onClick = { val cal = java.util.Calendar.getInstance().apply { timeInMillis = selectedTimestamp }; android.app.DatePickerDialog(pickerCtx, { _, y, m, d -> val u = java.util.Calendar.getInstance().apply { timeInMillis = selectedTimestamp; set(y, m, d) }; selectedTimestamp = u.timeInMillis }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show() }) {
                                ShrinkingSingleLineText(dateLabel, c.text, fontSize, 8f, Modifier.fillMaxWidth(), FontWeight.SemiBold, TextAlign.Center)
                            }
                            CustomOutlinedButton(modifier = Modifier.weight(1f).fillMaxHeight(), borderColor = c.accent.copy(0.5f), shape = RoundedCornerShape(percent = 50), onClick = { val cal = java.util.Calendar.getInstance().apply { timeInMillis = selectedTimestamp }; android.app.TimePickerDialog(pickerCtx, { _, h, min -> val u = java.util.Calendar.getInstance().apply { timeInMillis = selectedTimestamp; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min) }; selectedTimestamp = u.timeInMillis }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), false).show() }) {
                                ShrinkingSingleLineText(timeLabel, c.text, fontSize, 8f, Modifier.fillMaxWidth(), FontWeight.SemiBold, TextAlign.Center)
                            }
                        }
                    }

                    @Composable
                    fun AccountCategoryRow(modifier: Modifier) {
                        // Pure weight-based split — label gets weight(0.36f), button gets
                        // weight(0.64f) of the SAME Column, using Compose's own weight
                        // algorithm exactly like every other row in this dialog. This is
                        // mathematically guaranteed to shrink at the identical rate as
                        // sibling rows (no custom formula that can silently drift out of
                        // proportion). The label starts at the SAME font size (`lbFsz`) as
                        // every other label in this dialog — consistent sizing — and only
                        // auto-shrinks-on-overflow (like ShrinkingSingleLineText) if it
                        // genuinely doesn't fit, instead of starting from an arbitrary
                        // fixed guess that was too small to begin with. The label's share
                        // was bumped from 0.32f to 0.36f — it was still shrinking below its
                        // intended size on shorter screens.
                        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(0.36f), contentAlignment = Alignment.CenterStart) {
                                    var lfs by remember(scale) { mutableStateOf(lbFsz.sp) }
                                    Text("Account", fontSize = lfs, color = c.textSecondary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false, modifier = Modifier.padding(start = 2.dp), onTextLayout = { r -> if (r.hasVisualOverflow && lfs.value > 7f) lfs = (lfs.value * 0.9f).sp })
                                }
                                // Fixed font size — long names WRAP to a second line
                                // instead of shrinking; the button never grows past its
                                // weighted height, so the icon is bumped up a bit to stay
                                // visually balanced against 2-line text.
                                CustomOutlinedButton(modifier = Modifier.fillMaxWidth().weight(0.64f), borderColor = acctColor, onClick = { showWalletPicker = true }) {
                                    Icon(acctIcon, null, tint = acctColor, modifier = Modifier.size(icSz * 1.15f))
                                    Spacer(Modifier.width(6.dp))
                                    Text(accountSelection.ifBlank { "Account" }, fontSize = inFsz.sp, lineHeight = (inFsz + 1f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = c.text, style = centeredBtnTextStyle, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
                                }
                            }
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(0.36f), contentAlignment = Alignment.CenterStart) {
                                    var lfs by remember(scale) { mutableStateOf(lbFsz.sp) }
                                    Text("Category", fontSize = lfs, color = c.textSecondary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false, modifier = Modifier.padding(start = 2.dp), onTextLayout = { r -> if (r.hasVisualOverflow && lfs.value > 7f) lfs = (lfs.value * 0.9f).sp })
                                }
                                CustomOutlinedButton(modifier = Modifier.fillMaxWidth().weight(0.64f), borderColor = catColor, onClick = { showCategoryPicker = true }) {
                                    Icon(catIcon, null, tint = catColor, modifier = Modifier.size(icSz * 1.15f))
                                    Spacer(Modifier.width(6.dp))
                                    Text(selectedCategory?.displayName ?: "Category", fontSize = inFsz.sp, lineHeight = (inFsz + 1f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = c.text, style = centeredBtnTextStyle, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // Landscape-only compact row: Account + Category shown icon-only (no
                    // text label, no "Account"/"Category" captions — just the picker icon)
                    // sitting side by side, together forming the left half of the row; the
                    // Notes box forms the other half. All three boxes share the row's full
                    // height, so they render at identical size.
                    @Composable
                    fun AccountCategoryNotesRow(modifier: Modifier) {
                        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CustomOutlinedButton(modifier = Modifier.weight(1f).fillMaxHeight(), borderColor = acctColor, onClick = { showWalletPicker = true }) {
                                    Icon(acctIcon, contentDescription = "Account", tint = acctColor, modifier = Modifier.size(icSz))
                                }
                                CustomOutlinedButton(modifier = Modifier.weight(1f).fillMaxHeight(), borderColor = catColor, onClick = { showCategoryPicker = true }) {
                                    Icon(catIcon, contentDescription = "Category", tint = catColor, modifier = Modifier.size(icSz))
                                }
                            }
                            // reserveTopClearance = false — this Notes box sits in a Row
                            // right beside the Account/Category icon buttons, all sharing
                            // the exact same fillMaxHeight() slot; reserving the usual
                            // label clearance here would shrink ONLY this box, making it
                            // visibly shorter than its row-mates.
                            CompactTextField(value = notesStr, onValueChange = { notesStr = it }, placeholder = "Notes", modifier = Modifier.weight(1f).fillMaxHeight(), singleLine = false, maxLines = 3, reserveTopClearance = false)
                        }
                    }

                    @Composable
                    fun AmountDisplay(modifier: Modifier) {
                        Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.5.dp, amountColor.copy(0.5f)), color = Color.Transparent, modifier = modifier) {
                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                    if (hasOp) Text(calcExpr, fontSize = (11f * scale).coerceAtLeast(9f).sp, color = c.textSecondary, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    var amtFs by remember(calcExpr, calcResult, scale) { mutableStateOf((amtFszBase * scale).coerceAtLeast(14f).sp) }
                                    Text(when { calcResult != null -> "₹ ${formatCalcNum(calcResult)}"; calcExpr.isEmpty() -> "₹ 0"; else -> "₹ $calcExpr" }, fontSize = amtFs, fontWeight = FontWeight.Bold, color = amountColor, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false, onTextLayout = { r -> if (r.hasVisualOverflow && amtFs.value > 12f) amtFs = (amtFs.value * 0.85f).sp })
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.size(bsSz).clip(RoundedCornerShape(8.dp)).background(c.accent.copy(0.12f)).combinedClickable(onClick = { onCalcKey("⌫") }, onLongClick = { onCalcKey("C") }), contentAlignment = Alignment.Center) {
                                    Text("⌫", fontSize = (bsSz.value * 0.44f).sp, fontWeight = FontWeight.Bold, color = c.accent)
                                }
                            }
                        }
                    }

                    // ── Calculator — gets a guaranteed proportional share of the height,
                    // exactly like every other row above it (weight-based, never "leftover").
                    // Key labels auto-shrink on overflow (the same proven pattern used by
                    // ShrinkingSingleLineText elsewhere) so they ALWAYS fit their cell,
                    // regardless of any estimation error in the initial font-size guess.
                    @Composable
                    fun Calculator(modifier: Modifier) {
                        BoxWithConstraints(modifier = modifier) {
                            val kH = maxHeight / 4
                            val kW = maxWidth / 4
                            val kNumFsInit = (minOf(kH.value * 0.32f, kW.value * 0.36f)).coerceIn(10f, 22f)
                            val kOpFsInit  = (minOf(kH.value * 0.36f, kW.value * 0.40f)).coerceIn(11f, 24f)
                            Column(modifier = Modifier.fillMaxSize()) {
                                calcRowDef.forEach { row ->
                                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                        row.forEach { (key, role) ->
                                            val kBg  = if (role == "op") c.accent.copy(0.09f) else Color.Transparent
                                            val kClr = if (role == "op") c.accent else c.text
                                            val kBdr = if (role == "op") BorderStroke(0.5.dp, c.accent.copy(0.5f)) else BorderStroke(0.5.dp, c.text.copy(0.22f))
                                            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(1.5.dp).clip(RoundedCornerShape(4.dp)).background(kBg).border(kBdr, RoundedCornerShape(4.dp)).clickable { onCalcKey(key) }, contentAlignment = Alignment.Center) {
                                                var fs by remember(kH, kW, role) { mutableStateOf((if (role == "num") kNumFsInit else kOpFsInit).sp) }
                                                Text(key, fontSize = fs, fontWeight = if (role == "num") FontWeight.Medium else FontWeight.Bold, color = kClr, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false, onTextLayout = { r -> if (r.hasVisualOverflow && fs.value > 8f) fs = (fs.value * 0.85f).sp })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Landscape gets its own compact 2-column layout (right: amount +
                    // calculator, left: everything else) so the calculator doesn't get
                    // squeezed to an unusable size under short landscape heights. Portrait
                    // keeps the original single-column proportional layout completely
                    // untouched below — this flag (computed earlier, alongside `scale`)
                    // only decides WHICH branch renders.

                    if (isLandscape) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Left column — header, type toggle, payee, date/time, and the
                            // compact icon-only account/category + notes row. Extra spacing
                            // (gap * 1.4) between rows here specifically, as requested.
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(gap * 1.4f + 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().weight(1.05f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(CircleShape).clickable(onClick = onDismiss),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close", tint = c.text, modifier = Modifier.size(28.dp))
                                    }
                                    Text("Log Cashflow", fontWeight = FontWeight.Bold, fontSize = (titleFszBase * scale).coerceAtLeast(titleFszFloor).sp, color = c.text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(
                                        modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable(enabled = resolvedAmount > 0.0) {
                                            val cleanTitle = if (title.isBlank()) "Merchant Log" else title
                                            viewModel.saveLastUsedAccount(accountSelection)
                                            viewModel.saveLastUsedCategory(transactionType, categorySelection)
                                            onConfirm(cleanTitle, resolvedAmount, categorySelection, transactionType, makeNoteWithAccount(notesStr, accountSelection), selectedTimestamp)
                                        }.padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Save", fontWeight = FontWeight.Bold, color = c.accent, fontSize = (saveFszBase * scale).coerceAtLeast(saveFszFloor).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                TypeToggle(modifier = Modifier.fillMaxWidth().weight(1.0f))
                                // Weight trimmed 1.3f→1.15f, as requested (a little smaller).
                                CompactTextField(value = title, onValueChange = { title = it }, placeholder = "Payee / Merchant", modifier = Modifier.fillMaxWidth().weight(1.15f), showNewBadge = isNewPayee, contentFontSizeSp = payeeFsz)
                                // Weight trimmed 1.0f→0.85f, as requested (a little smaller).
                                DateTimeRow(modifier = Modifier.fillMaxWidth().weight(0.85f), fontSize = dtFsz)
                                // Account + Category (icon-only, side by side) form one half
                                // of this row; Notes forms the other half — all same height.
                                // Weight trimmed further, 1.0f→0.85f, as requested.
                                AccountCategoryNotesRow(modifier = Modifier.fillMaxWidth().weight(0.85f))
                            }
                            // Right column — amount display on top, calculator filling the
                            // rest. Amount's weight was trimmed from 1.6f to 1.15f (closer to
                            // one calculator row's share) since it was reported too big; the
                            // freed space goes to the calculator instead.
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(gap)
                            ) {
                                AmountDisplay(modifier = Modifier.fillMaxWidth().weight(1.15f))
                                Calculator(modifier = Modifier.fillMaxWidth().weight(4.65f).padding(bottom = 2.dp))
                            }
                        }
                    } else {
                    // ── Portrait layout — every section below gets a weight() proportional
                    // to its natural size at full height; as totalH shrinks, ALL sections —
                    // including the calculator — shrink together in lockstep. Nothing
                    // scrolls, nothing is ever pushed off-screen, and the calculator's
                    // bottom row can never be clipped because its slot is guaranteed by the
                    // same weight system as everything else, not "whatever space happens to
                    // be left over".
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        // Close / Title / Save — now weighted like everything else instead
                        // of a fixed-height Surface sitting outside the proportional system.
                        // Weight bumped 0.9f→1.05f for a bigger title bar, as requested.
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1.05f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(CircleShape).clickable(onClick = onDismiss),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = c.text, modifier = Modifier.size(20.dp))
                            }
                            Text("Log Cashflow", fontWeight = FontWeight.Bold, fontSize = (titleFszBase * scale).coerceAtLeast(titleFszFloor).sp, color = c.text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(
                                modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable(enabled = resolvedAmount > 0.0) {
                                    val cleanTitle = if (title.isBlank()) "Merchant Log" else title
                                    viewModel.saveLastUsedAccount(accountSelection)
                                    viewModel.saveLastUsedCategory(transactionType, categorySelection)
                                    onConfirm(cleanTitle, resolvedAmount, categorySelection, transactionType, makeNoteWithAccount(notesStr, accountSelection), selectedTimestamp)
                                }.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Save", fontWeight = FontWeight.Bold, color = c.accent, fontSize = (saveFszBase * scale).coerceAtLeast(saveFszFloor).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TypeToggle(modifier = Modifier.fillMaxWidth().weight(1.0f))
                        // Dedicated bigger font (payeeFsz) — Date/Time below intentionally
                        // keeps the smaller shared `inFsz` so it doesn't grow along with it.
                        // Weight also bumped slightly (1.18f → 1.3f) so the box stays just a
                        // little bigger than the Date/Time row (weight 1.0f), not equal to it.
                        CompactTextField(value = title, onValueChange = { title = it }, placeholder = "Payee / Merchant", modifier = Modifier.fillMaxWidth().weight(1.3f), showNewBadge = isNewPayee, contentFontSizeSp = payeeFsz)
                        // Pill-shaped, matches Update Transaction's date/time boxes; still
                        // shrinks proportionally since it's weight-based like everything else.
                        DateTimeRow(modifier = Modifier.fillMaxWidth().weight(1.0f))
                        // Bumped from 1.5f to 1.7f (together with the internal 0.68f button
                        // split) so the Account/Category pickers render close to Update
                        // Transaction's 44dp button height without looking oversized.
                        AccountCategoryRow(modifier = Modifier.fillMaxWidth().weight(1.7f))
                        // Notes gets a taller, ~3-row box for multi-line notes.
                        CompactTextField(value = notesStr, onValueChange = { notesStr = it }, placeholder = "Notes (Optional)", modifier = Modifier.fillMaxWidth().weight(2.3f), singleLine = false, maxLines = 3)
                        // Weight matches a single calculator row's share (7.0f / 4 rows =
                        // 1.75f) instead of an arbitrary value.
                        AmountDisplay(modifier = Modifier.fillMaxWidth().weight(1.75f))
                        // No top padding here — the Column's own spacedBy(gap) already
                        // supplies the gap above the calculator. The Compose BOM bump to
                        // 2025.08.00 fixed the Dialog WindowInsets bug (see repo memory),
                        // so safeDrawingPadding() on the outer Column now correctly clears
                        // the nav bar — only a tiny 2dp buffer is kept for visual breathing room.
                        Calculator(modifier = Modifier.fillMaxWidth().weight(7.0f).padding(bottom = 2.dp))
                    }
                    }               // end if/else (landscape/portrait)
                }               // end content BoxWithConstraints
            }                   // end Column(fillMaxSize) inside Surface
        }                       // end Surface(fillMaxSize)
    }                           // end Dialog


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
            addActionLabel = "Add New Category",
            onAddAction = {
                showCategoryPicker = false
                showQuickAddCategory = true
            }
        )
    }

    if (showWalletPicker) {
        WalletSelectionDialog(
            walletOptions = selectablesWallets.map { walletName -> walletName to (accounts.find { it.name == walletName }?.type) },
            selectedWallet = accountSelection,
            onSelect = {
                accountSelection = it
                showWalletPicker = false
            },
            onDismiss = { showWalletPicker = false },
            addActionLabel = "Add New Account",
            onAddAction = {
                showWalletPicker = false
                showQuickAddAccount = true
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
                showQuickAddCategory = false
                // Switch selection only once the category is actually written and visible
                // globally (this dialog's own `customCats` flow, the Budgets screen, and
                // every other picker all observe the same DB-backed flow) — avoids a brief
                // "nothing selected" flash from racing the async write.
                viewModel.addCustomCategory(cleanName, iconName, colorHex, type, onComplete = {
                    categorySelection = cleanName
                })
                if (type == "EXPENSE" && initialBudget > 0.0) {
                    viewModel.saveBudget(cleanName, cleanName, initialBudget)
                }
            }
        )
    }
}

@Composable
private fun FullScreenEditorDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit
) {
    val c = LocalAppColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        EdgeToEdgeDialogWindowEffect(isDarkBackground = c.isDark)
        Surface(modifier = Modifier.fillMaxSize(), color = c.surface) {
            // safeDrawingPadding on the column handles ALL insets (status bar, nav bar,
            // display cutout) in a single modifier, preventing double-counting and
            // avoiding lateral shifts in landscape mode.
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Surface(shadowElevation = 4.dp, color = c.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = c.text)
                        }
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = c.text,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Action area — caller injects Delete + Save icons here
                        actions()
                    }
                }
                HorizontalDivider(color = c.divider)
                // Content column — minimal trailing space (last item visibility only).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun AdaptiveEditorDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit
) {
    val c = LocalAppColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        EdgeToEdgeDialogWindowEffect(isDarkBackground = c.isDark)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 520.dp)
                    .heightIn(max = maxHeight * 0.90f),
                color = c.surface,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    // ── Header ──────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                            .heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.text, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = c.text)
                        }
                    }
                    HorizontalDivider(color = c.divider)
                    // ── Scrollable fields ────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content
                    )
                    HorizontalDivider(color = c.divider)
                    // ── Action row ───────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}

/** Dialog to update an existing transaction.
 * Supports type change, amount edit, category/account reassignment,
 * note editing, timestamp change, and optional batch-apply to all
 * same-payee transactions.
 */
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
        // Show only user-written content — all internal tags ([Acc:], [To:], [T:A], [IncRef:]) stripped
        mutableStateOf(userNoteFrom(tx.note))
    }
    var showSmsBody by remember { mutableStateOf(false) }
    var selectedTimestamp by remember { mutableStateOf(tx.timestamp) }
    var showWalletPicker by remember { mutableStateOf(false) }
    var showToWalletPicker by remember { mutableStateOf(false) }
    var toAccountSelection by remember { mutableStateOf(tx.getTransferDestName() ?: "") }
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

    FullScreenEditorDialog(
        title = "Update Transaction",
        onDismiss = onDismiss,
        actions = {
            // Delete (trash icon) + Save (text button) in title bar.
            // The confirmation prompt for this action lives in the caller (the outer
            // `selectedTxForEdit?.let` block in MainAppScreen already shows an "Are you
            // sure?" AlertDialog before calling viewModel.deleteTransaction), so tapping
            // this icon must invoke onDelete() directly — an extra confirmation dialog
            // here previously meant the user had to confirm the deletion TWICE.
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete transaction", tint = c.expense)
            }
            TextButton(
                onClick = {
                    val dAmt = amountStr.toDoubleOrNull() ?: 0.0
                    // Balance Sync/BALANCE_UPDATE snapshots can legitimately be negative
                    // (e.g. a Credit Card's outstanding-debt snapshot is stored as a
                    // negative amount) — requiring >= 0.0 here silently blocked saving
                    // ANY edit to an already-negative balance update record (even one that
                    // only touched the title/date and never the amount), with no error
                    // shown to the user. Any parsed amount is valid for this type.
                    if ((if (editType == "BALANCE_UPDATE") true else dAmt > 0.0) && title.isNotBlank()) {
                        onConfirm(
                            tx.copy(
                                title = title,
                                amount = dAmt,
                                category = categorySelection,
                                type = editType,
                                timestamp = selectedTimestamp,
                                note = if (editType == "TRANSFER") {
                                    val userNote = userNoteFrom(notesStr.ifBlank { null })
                                    "[Acc: $accountSelection] [To: $toAccountSelection]${if (userNote.isNotBlank()) " $userNote" else ""}"
                                } else {
                                    rebuildNote(notesStr.ifBlank { null }, accountSelection, tx.note)
                                }
                            ),
                            applyToAllPayees
                        )
                    }
                }
            ) { Text("Save", fontWeight = FontWeight.Bold, color = c.accent, fontSize = 15.sp) }
        },
        content = {
                val amountColor = when (editType) {
                    "EXPENSE" -> c.expense
                    "INCOME" -> c.income
                    "DUPLICATE" -> c.textSecondary
                    else -> c.accent
                }
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount (₹)", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = amountColor
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = amountColor, unfocusedTextColor = amountColor,
                        focusedBorderColor = amountColor, unfocusedBorderColor = amountColor.copy(0.5f),
                        focusedLabelColor = amountColor, unfocusedLabelColor = amountColor.copy(0.7f)
                        // NOTE: deliberately no focused/unfocusedContainerColor override — a
                        // non-transparent container makes Material3 paint the floating
                        // label's border-cutout backdrop in a fixed default color instead of
                        // blending with the dialog background, producing a mismatched white
                        // patch behind "Amount" that the other fields (which stay fully
                        // transparent) don't have.
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Text("TRANSACTION TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EXPENSE", "INCOME", "TRANSFER", "DUPLICATE", "BALANCE_UPDATE").forEach { t ->
                            val sel = editType == t
                            val tLabel = when (t) { "BALANCE_UPDATE" -> "BAL"; "DUPLICATE" -> "DUP"; "TRANSFER" -> "XFR"; else -> t.take(3) }
                            Surface(
                                color = if (sel) c.accent.copy(0.15f) else Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, if (sel) c.accent else c.divider),
                                modifier = Modifier.weight(1f).clickable { editType = t }
                            ) {
                                Text(tLabel, fontSize = 9.sp, color = if (sel) c.accent else c.textSecondary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Payee / Merchant", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                TransactionDateTimePicker(
                    selectedTimestamp = selectedTimestamp,
                    onTimestampChange = { selectedTimestamp = it }
                )

                val editAccType = accounts.find { it.name == accountSelection }?.type
                val editAccColor = when (editAccType) { "CASH" -> c.income; "BANK" -> c.accent; "CREDIT_CARD" -> c.expense; "DEBIT_CARD" -> Color(0xFF0EA5E9); "WALLET" -> Color(0xFFFF9800); else -> c.accent }
                // Fixed font size — long names WRAP to a second line instead of shrinking
                // the font, and the button itself never grows past its 44dp height; the
                // icon is a little bigger to stay visually balanced against 2-line text.
                val acctCatFontSize = 13.sp
                val acctCatIconSize = 20.dp
                when {
                    editType == "TRANSFER" -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("From Account", fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                                OutlinedButton(onClick = { showWalletPicker = true }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, editAccColor.copy(0.4f)), modifier = Modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Icon(walletIconFor(accountSelection, editAccType), null, tint = editAccColor, modifier = Modifier.size(acctCatIconSize)); Spacer(Modifier.width(6.dp))
                                    Text(accountSelection.ifBlank { "Account" }, fontSize = acctCatFontSize, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("To Account", fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                                val toAccType = accounts.find { it.name == toAccountSelection }?.type
                                val toAccColor = when (toAccType) { "CASH" -> c.income; "BANK" -> c.accent; "CREDIT_CARD" -> c.expense; "DEBIT_CARD" -> Color(0xFF0EA5E9); "WALLET" -> Color(0xFFFF9800); else -> c.accent }
                                OutlinedButton(onClick = { showToWalletPicker = true }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, toAccColor.copy(0.4f)), modifier = Modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Icon(walletIconFor(toAccountSelection, toAccType), null, tint = toAccColor, modifier = Modifier.size(acctCatIconSize)); Spacer(Modifier.width(6.dp))
                                    Text(toAccountSelection.ifBlank { "Select" }, fontSize = acctCatFontSize, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    !isNoCategoryType -> {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Account", fontSize = 11.sp, color = c.textSecondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                                OutlinedButton(onClick = { showWalletPicker = true }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, editAccColor.copy(0.4f)), modifier = Modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Icon(walletIconFor(accountSelection, editAccType), null, tint = editAccColor, modifier = Modifier.size(acctCatIconSize)); Spacer(Modifier.width(6.dp))
                                    Text(accountSelection.ifBlank { "Account" }, fontSize = acctCatFontSize, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Category", fontSize = 11.sp, color = c.textSecondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                                val catColor = selectedCategory?.color ?: c.accent
                                val catIcon = selectedCategory?.icon ?: Icons.Default.Category
                                OutlinedButton(onClick = { showCategoryPicker = true }, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, catColor.copy(0.4f)), modifier = Modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                    Icon(catIcon, null, tint = catColor, modifier = Modifier.size(acctCatIconSize)); Spacer(Modifier.width(6.dp))
                                    Text(selectedCategory?.displayName ?: "Category", fontSize = acctCatFontSize, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    else -> PickerButton("Account", accountSelection, walletIconFor(accountSelection, editAccType), editAccColor) { showWalletPicker = true }
                }

                OutlinedTextField(
                    value = notesStr,
                    onValueChange = { notesStr = it },
                    label = { Text("Note (Optional)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, focusedBorderColor = c.accent, focusedLabelColor = c.accent,
                        unfocusedTextColor = c.text, unfocusedBorderColor = c.accent.copy(0.35f)
                    ),
                    // Roughly 3 rows tall by default (instead of 1), and free to grow up to
                    // 6 rows for longer notes — sized relative to what's actually available
                    // rather than a fixed single line.
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                // SMS Body — hidden by default, tap to reveal
                if (!tx.smsBody.isNullOrBlank()) {
                    Surface(
                        onClick = { showSmsBody = !showSmsBody },
                        shape = RoundedCornerShape(8.dp),
                        color = c.surfaceVariant,
                        border = BorderStroke(1.dp, c.divider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "SMS Body",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = c.textSecondary
                                )
                                Icon(
                                    if (showSmsBody) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (showSmsBody) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    tx.smsBody ?: "",
                                    fontSize = 11.sp,
                                    color = c.textSecondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }

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
                            Text("Same category to all '${title.ifBlank { "..." }}' entries", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                        }
                        Switch(
                            checked = applyToAllPayees,
                            onCheckedChange = { applyToAllPayees = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = c.accent, checkedTrackColor = c.accent.copy(alpha = 0.4f))
                        )
                    }
                }
        }
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
            addActionLabel = "Add New Account",
            onAddAction = {
                showWalletPicker = false
                showQuickAddAccount = true
            }
        )
    }

    if (showToWalletPicker) {
        WalletSelectionDialog(
            walletOptions = selectablesWallets.map { walletName -> walletName to (accounts.find { it.name == walletName }?.type) },
            selectedWallet = toAccountSelection,
            onSelect = {
                toAccountSelection = it
                showToWalletPicker = false
            },
            onDismiss = { showToWalletPicker = false }
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
            addActionLabel = "Add New Category",
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
                showQuickAddCategory = false
                // Same fix as AddTransactionDialog — switch selection only after the write
                // actually completes, so it's guaranteed globally visible immediately
                // (Budgets screen, other pickers, etc. all read the same DB-backed flow).
                viewModel.addCustomCategory(cleanName, iconName, colorHex, type, onComplete = {
                    categorySelection = cleanName
                })
                if (type == "EXPENSE" && initialBudget > 0.0) {
                    viewModel.saveBudget(cleanName, cleanName, initialBudget)
                }
            }
        )
    }
}

/** Reusable date + time picker row using native Android pickers.
 * Themed to match the current light/dark mode.
 */
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                border = BorderStroke(1.dp, c.accent.copy(0.45f)),
                modifier = Modifier.weight(1f)
            ) {
                ShrinkingSingleLineText(dateLabel, c.text, 14f, 9f, Modifier, FontWeight.SemiBold)
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
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                border = BorderStroke(1.dp, c.accent.copy(0.45f)),
                modifier = Modifier.weight(1f)
            ) {
                ShrinkingSingleLineText(timeLabel, c.text, 14f, 9f, Modifier, FontWeight.SemiBold)
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
private fun ModernDialogFrame(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    onDismiss: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalAppColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // BoxWithConstraints ties the dialog's max height to the ACTUAL available
        // screen space (with systemBarsPadding accounted for) instead of a hardcoded
        // dp cap. This guarantees a consistent margin/bottom-padding around the
        // dialog on every screen size — taller content (e.g. category icon grid)
        // no longer risks touching or overflowing the screen edge.
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = c.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 460.dp)
                    .heightIn(max = maxHeight * 0.88f)
                    .imePadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // ── Header: colored icon avatar + title + circular close button ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = iconColor.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(
                            shape = CircleShape,
                            color = c.text.copy(alpha = 0.06f),
                            modifier = Modifier.size(32.dp).clickable(onClick = onDismiss)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = c.textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = c.divider)
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = content
                    )
                    if (actions != null) {
                        HorizontalDivider(color = c.divider)
                        Row(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            content = actions
                        )
                    }
                }
            }
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
    ModernDialogFrame(
        title = "Select Wallet",
        icon = Icons.Default.AccountBalanceWallet,
        iconColor = c.accent,
        onDismiss = onDismiss
    ) {
        walletOptions.forEach { (walletName, walletType) ->
            val active = selectedWallet == walletName
            val acctColor = when (walletType) {
                "CASH"        -> c.income
                "BANK"        -> c.accent
                "CREDIT_CARD" -> c.expense
                "WALLET"      -> Color(0xFFFF9800)
                else          -> c.accent
            }
            Surface(
                color = if (active) acctColor.copy(alpha = 0.15f) else c.text.copy(alpha = 0.04f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (active) acctColor else c.divider),
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
                        tint = acctColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(walletName, color = if (active) acctColor else c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (active) Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = acctColor, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (addActionLabel != null && onAddAction != null) {
            Surface(
                onClick = onAddAction,
                shape = RoundedCornerShape(16.dp),
                color = c.accent.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = CircleShape, color = c.accent.copy(alpha = 0.18f), modifier = Modifier.size(28.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(addActionLabel, color = c.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
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
    ModernDialogFrame(
        title = "Select Category",
        icon = Icons.Default.Category,
        iconColor = c.accent,
        onDismiss = onDismiss
    ) {
        // "None / Remove" row — resets to the type-appropriate default category
        val clearActive = selectedCategoryName.isBlank() || selectedCategoryName.equals(clearCategoryName, ignoreCase = true)
        Surface(
            color = if (clearActive) c.textTertiary.copy(alpha = 0.12f) else c.text.copy(alpha = 0.04f),
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
                color = if (active) category.color.copy(alpha = 0.15f) else c.text.copy(alpha = 0.04f),
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
                    Text(category.displayName, color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (active) Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = category.color, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (addActionLabel != null && onAddAction != null) {
            Surface(
                onClick = onAddAction,
                shape = RoundedCornerShape(16.dp),
                color = c.accent.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = CircleShape, color = c.accent.copy(alpha = 0.18f), modifier = Modifier.size(28.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(addActionLabel, color = c.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun walletIconFor(name: String, type: String?): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        type == "CASH" || name.contains("cash", ignoreCase = true) -> Icons.Default.Money
        type == "BANK" || name.contains("bank", ignoreCase = true) -> Icons.Default.AccountBalance
        type == "CREDIT_CARD" || type == "DEBIT_CARD" || name.contains("card", ignoreCase = true) -> Icons.Default.CreditCard
        type == "WALLET" || name.contains("wallet", ignoreCase = true) -> Icons.Default.AccountBalanceWallet
        else -> Icons.Default.AccountBalanceWallet
    }
}

/** Picks a representative icon + color for a notification based on its title, so the
 * notification list has some visual rhythm instead of being plain text rows — mirrors how
 * native notification centers show a per-source icon. */
private fun notificationIconFor(title: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    val t = title.lowercase()
    return when {
        t.contains("category") -> Icons.Default.Category to Color(0xFF9C27B0)
        t.contains("balance") -> Icons.Default.AccountBalanceWallet to Color(0xFF0EA5E9)
        t.contains("budget") -> Icons.Default.PieChart to Color(0xFFFF9800)
        t.contains("sms") || t.contains("import") -> Icons.Default.Sms to Color(0xFF00897B)
        t.contains("account") -> Icons.Default.AccountBalance to Color(0xFF3B82F6)
        t.contains("transfer") -> Icons.Default.SwapHoriz to Color(0xFF7E57C2)
        t.contains("delet") -> Icons.Default.Delete to Color(0xFFE53935)
        else -> Icons.Default.Notifications to Color(0xFF607D8B)
    }
}

/** Native-feeling relative time for the notification list — "Just now" / "5m ago" / "3h ago"
 * / "Yesterday" / an absolute short date beyond that, instead of a raw timestamp string. */
private fun relativeTimeText(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = (now - timestamp).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diffMs < minute -> "Just now"
        diffMs < hour -> "${diffMs / minute}m ago"
        diffMs < day -> "${diffMs / hour}h ago"
        diffMs < 2 * day -> "Yesterday"
        diffMs < 7 * day -> "${diffMs / day}d ago"
        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
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
    val types = listOf("CASH", "BANK", "CREDIT_CARD", "DEBIT_CARD", "WALLET")
    val typeColor = when (type) { "CASH" -> c.income; "BANK" -> c.accent; "CREDIT_CARD" -> c.expense; "DEBIT_CARD" -> Color(0xFF0EA5E9); "WALLET" -> Color(0xFFFF9800); else -> c.accent }

    ModernDialogFrame(
        title = "Add New Account",
        icon = walletIconFor(name, type),
        iconColor = typeColor,
        onDismiss = onDismiss,
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(percent = 50),
                border = BorderStroke(1.dp, c.text.copy(alpha = 0.2f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(
                onClick = {
                    onAdd(name, amount.toDoubleOrNull() ?: 0.0, type, name.filter { it.isDigit() }.takeLast(4).ifBlank { null }, openingBalanceTimestamp)
                },
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Add Account", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Account Name (e.g. SBI Bank)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.text,
                focusedBorderColor = c.accent,
                focusedLabelColor = c.accent
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Initial Ledger Balance (₹)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.text,
                focusedBorderColor = c.accent,
                focusedLabelColor = c.accent
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        TransactionDateTimePicker(
            selectedTimestamp = openingBalanceTimestamp,
            onTimestampChange = { openingBalanceTimestamp = it },
            label = "Opening Balance Date & Time"
        )
        Column {
            Text("ACCOUNT CATEGORY / TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.textSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { option ->
                    val active = option == type
                    val optColor = when (option) { "CASH" -> c.income; "BANK" -> c.accent; "CREDIT_CARD" -> c.expense; "DEBIT_CARD" -> Color(0xFF0EA5E9); "WALLET" -> Color(0xFFFF9800); else -> c.accent }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) optColor.copy(alpha = 0.15f) else c.text.copy(alpha = 0.04f))
                            .border(1.dp, if (active) optColor else c.divider, RoundedCornerShape(10.dp))
                            .clickable { type = option }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(option.replace("_", " "), color = if (active) optColor else c.textSecondary, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
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
    val selectedIconVec = suitableIconsList.firstOrNull { it.first == selectedIconName }?.second ?: Icons.Default.Category

    ModernDialogFrame(
        title = "Add New Category",
        icon = selectedIconVec,
        iconColor = selectedColor,
        onDismiss = onDismiss,
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(percent = 50),
                border = BorderStroke(1.dp, c.text.copy(alpha = 0.2f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(
                onClick = { onAdd(name, type, selectedIconName, selectedColorHex, initialBudgetAmt.toDoubleOrNull() ?: 0.0) },
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Add Category", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Category Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.text,
                focusedBorderColor = c.accent,
                focusedLabelColor = c.accent
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("EXPENSE", "INCOME").forEach { option ->
                val active = option == type
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) c.accent.copy(alpha = 0.15f) else c.text.copy(alpha = 0.04f))
                        .border(1.dp, if (active) c.accent else c.divider, RoundedCornerShape(10.dp))
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
                label = { Text("Monthly Budget Limit (₹, Optional)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.text,
                    focusedBorderColor = c.accent,
                    focusedLabelColor = c.accent
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text("Select Icon", fontSize = 12.sp, color = c.textSecondary, fontWeight = FontWeight.Bold)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(58.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
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
}

/** Dialog offering Excel (.xlsx) and PDF export of all transactions. */
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
    ModernDialogFrame(
        title = "Export Financial Records",
        icon = Icons.Default.Upload,
        iconColor = c.accent,
        onDismiss = onDismiss,
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(percent = 50),
                border = BorderStroke(1.dp, c.text.copy(alpha = 0.2f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Dismiss", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(
                onClick = {
                    val fileName = "autoledger-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.xlsx"
                    excelExporter.launch(fileName)
                },
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Excel", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(
                onClick = {
                    val fileName = "autoledger-${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.pdf"
                    pdfExporter.launch(fileName)
                },
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(containerColor = c.income, contentColor = c.text),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) { Text("PDF", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    ) {
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
}

/** Backup center — auto-backup frequency, custom folder, restore from list. */
@Composable
fun BackupDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    val isPaid by viewModel.isPaidFeaturesEnabled.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val freq by viewModel.backupFrequency.collectAsStateWithLifecycle()
    val customBackupPath by viewModel.customBackupPath.collectAsStateWithLifecycle()
    val lastTime by viewModel.lastBackupTime.collectAsStateWithLifecycle()
    val availableBackups by viewModel.availableBackups.collectAsStateWithLifecycle()

    // UI states
    var isBackingUp by remember { mutableStateOf(false) }
    var backupStatusText by remember { mutableStateOf("") }
    var tempPathInput by remember(customBackupPath) { mutableStateOf(customBackupPath) }
    var isEditingPath by remember { mutableStateOf(false) }
    var restoreConfirmItem by remember { mutableStateOf<com.example.viewmodel.BackupItem?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var exportedCsv by remember { mutableStateOf(false) }

    // Refresh backups list when dialog is shown or custom path changes
    LaunchedEffect(customBackupPath) {
        viewModel.refreshAvailableBackups()
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackupToUri(context, uri)
            exportedCsv = true
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isRestoring = true
            viewModel.restoreFromBackupUri(context, uri) { success, errMsg ->
                isRestoring = false
                if (success) {
                    Toast.makeText(context, "Backup restored successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to restore backup: ${errMsg ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
                viewModel.refreshAvailableBackups()
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist read/write permission across reboots
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            val path = uri.toString()
            viewModel.setCustomBackupPath(path)
            Toast.makeText(context, "Backup folder updated!", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isBackingUp && !isRestoring) onDismiss() },
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(c.accent.copy(alpha = 0.12f), CircleShape)
                            .border(1.dp, c.accent.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = c.accent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Backup & Recovery",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.text
                    )
                    Text(
                        "Automate, sync, or restore offline",
                        fontSize = 11.sp,
                        color = c.textSecondary
                    )
                }

                HorizontalDivider(color = c.divider)

                // 1. Auto Backup Frequency
                ProGate(isPaid = isPaid, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "AUTOMATIC BACKUP FREQUENCY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textSecondary,
                        letterSpacing = 0.5.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "MANUAL" to "Off",
                            "DAILY" to "Daily",
                            "WEEKLY" to "Weekly",
                            "MONTHLY" to "Monthly"
                        ).forEach { (value, label) ->
                            val active = freq == value
                            Surface(
                                color = if (active) c.accent else c.surfaceVariant,
                                border = BorderStroke(1.dp, if (active) c.accent else c.border),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.setBackupFrequency(value) }
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) Color.White else c.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                } // end ProGate (auto backup frequency)

                // 2. Backup Storage Path
                ProGate(isPaid = isPaid, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "BACKUP STORAGE LOCATION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.textSecondary,
                        letterSpacing = 0.5.sp
                    )
                    Surface(
                        color = c.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, c.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(c.accent.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Show cloud icon for cloud/Drive paths, folder for local
                                    val isCloud = customBackupPath.contains("com.google.android.apps.docs") ||
                                        customBackupPath.contains("cloud") ||
                                        customBackupPath.contains("onedrive") ||
                                        customBackupPath.contains("dropbox")
                                    Icon(
                                        if (isCloud) Icons.Default.Cloud else Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = c.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    // Determine if cloud path and pick label accordingly
                                    val isGoogleDrive = customBackupPath.contains("com.google.android.apps.docs")
                                    val isOneDrive = customBackupPath.contains("onedrive") || customBackupPath.contains("microsoft")
                                    val isDropbox = customBackupPath.contains("dropbox")
                                    val folderLabel = when {
                                        isGoogleDrive -> "Google Drive"
                                        isOneDrive    -> "OneDrive"
                                        isDropbox     -> "Dropbox"
                                        customBackupPath.isEmpty() -> "Local Backup Folder"
                                        else          -> "Local Backup Folder"
                                    }
                                    Text(
                                        folderLabel,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = c.text
                                    )
                                val currentPath = when {
                                    customBackupPath.isEmpty() -> viewModel.getBackupFolder(false, "").absolutePath
                                    isGoogleDrive || isOneDrive || isDropbox -> null  // handled below as cloud
                                    customBackupPath.startsWith("content://") -> {
                                        try {
                                            val uri = android.net.Uri.parse(customBackupPath)
                                            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                                            val decoded = java.net.URLDecoder.decode(docId, "UTF-8")
                                            if (decoded.startsWith("primary:")) "/storage/emulated/0/" + decoded.removePrefix("primary:")
                                            else decoded
                                        } catch (_: Exception) { customBackupPath }
                                    }
                                    else -> customBackupPath
                                }
                                // Display path
                                val displayPath = when {
                                    isGoogleDrive -> "Synced to Google Drive"
                                    isOneDrive    -> "Synced to OneDrive"
                                    isDropbox     -> "Synced to Dropbox"
                                    currentPath == null -> "Cloud Storage"
                                    else -> currentPath.removePrefix("/storage/emulated/0").trimStart('/').let { if (it.isEmpty()) "Internal Storage" else it }
                                }
                                    Text(
                                        displayPath,
                                        fontSize = 11.sp,
                                        color = c.textSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (customBackupPath.isEmpty()) {
                                        Text(
                                            "Using default Scoped Storage folder",
                                            fontSize = 9.sp,
                                            color = c.accent,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                Row {
                                    if (customBackupPath.isNotEmpty()) {
                                        TextButton(
                                            onClick = {
                                                viewModel.setCustomBackupPath("")
                                                Toast.makeText(context, "Reset to default location", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("Reset", fontSize = 11.sp, color = c.expense)
                                        }
                                    }
                                }
                            }

                            // Choose folder button (replaces manual path text input)
                            Button(
                                onClick = { folderPickerLauncher.launch(null) },
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Choose Folder", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                } // end ProGate (backup storage path)

                // 3. Backup Button and Stats
                ProGate(isPaid = isPaid, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isBackingUp) {
                        Surface(
                            color = c.accent.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, c.accent.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(color = c.accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(backupStatusText, fontSize = 12.sp, color = c.text, fontWeight = FontWeight.Medium)
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isBackingUp = true
                                        backupStatusText = "Connecting to Secure Storage..."
                                        delay(700)
                                        backupStatusText = "Consolidating Database Snapshots..."
                                        delay(600)
                                        backupStatusText = "Encrypting backup archive (JSON format)..."
                                        delay(600)
                                        backupStatusText = "Saving to secure local system folders..."
                                        delay(500)
                                        viewModel.executeBackupNow { success, errMsg ->
                                            isBackingUp = false
                                            if (success) {
                                                val isCloud = customBackupPath.contains("com.google.android.apps.docs") ||
                                                    customBackupPath.contains("onedrive") || customBackupPath.contains("dropbox")
                                                val dest = when {
                                                    isCloud && customBackupPath.contains("com.google.android.apps.docs") -> "Google Drive"
                                                    isCloud -> "Cloud"
                                                    customBackupPath.startsWith("content://") -> "Selected Folder"
                                                    else -> "Local Storage"
                                                }
                                                Toast.makeText(context, "Backup saved to $dest!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Backup failed: ${errMsg ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = c.income),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1.5f),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Back Up Now", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text("LAST BACKUP", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.textTertiary)
                                val dateStr = if (lastTime == 0L) "Never" else {
                                    val df = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
                                    df.format(java.util.Date(lastTime))
                                }
                                Text(dateStr, fontSize = 11.sp, color = c.textSecondary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                } // end ProGate (backup button)

                HorizontalDivider(color = c.divider)

                // 4. Restore List
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "RESTORE FROM RECENT BACKUPS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textSecondary,
                            letterSpacing = 0.5.sp
                        )
                        IconButton(
                            onClick = {
                                viewModel.refreshAvailableBackups()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Backups List",
                                tint = c.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (availableBackups.isEmpty()) {
                        Surface(
                            color = c.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, c.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Storage, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(24.dp))
                                Text("No recent automatic or offline backups found. Tap 'Back Up Now' to create one.", fontSize = 11.sp, color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Show all available backups
                            availableBackups.forEach { item ->
                                Surface(
                                    color = c.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, c.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { restoreConfirmItem = item }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .background(
                                                    if (item.isGoogleStorage) c.accent.copy(alpha = 0.12f)
                                                    else c.textSecondary.copy(alpha = 0.12f),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (item.isGoogleStorage) Icons.Default.Cloud else Icons.Default.Storage,
                                                contentDescription = null,
                                                tint = if (item.isGoogleStorage) c.accent else c.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            val storageMode = if (item.isGoogleStorage) "Cloud" else "Local"
                                            Text(
                                                item.name.ifBlank { "$storageMode Backup" },
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = c.text,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            val df = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault())
                                            val sizeKb = String.format(java.util.Locale.getDefault(), "%.1f KB", item.sizeBytes / 1024.0)
                                            Text("$storageMode • ${df.format(java.util.Date(item.timestamp))} • $sizeKb", fontSize = 10.sp, color = c.textSecondary)
                                        }
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = "Restore",
                                            tint = c.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        IconButton(
                                            onClick = { viewModel.deleteBackup(item) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Backup File",
                                                tint = c.expense,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = c.divider)

                // 5. Manual Backup Actions + Close — grouped in their own nested Column so
                // the gap between them stays small regardless of the outer 16dp section gap.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "MANUAL BACKUP",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = c.textSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
                                    createDocLauncher.launch("exported_backup_$date.csv")
                                },
                                border = BorderStroke(1.dp, c.border),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Export CSV", fontSize = 11.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (exportedCsv) {
                            Text("CSV exported successfully!", fontSize = 10.sp, color = c.income, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent.copy(alpha = 0.15f), contentColor = c.text),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Close Backup Center", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.text)
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = c.surface,
        shape = RoundedCornerShape(20.dp)
    )

    // ── Dialog Subcomponents (Account Selector, Warning, Progress Loader) ────────────────

if (restoreConfirmItem != null) {
        val backupItem = restoreConfirmItem!!
        var cleanRestore by remember { mutableStateOf(false) }

        ModernDialogFrame(
            title = "Restore Backup",
            icon = Icons.Default.CloudDownload,
            iconColor = if (cleanRestore) c.expense else c.accent,
            onDismiss = { restoreConfirmItem = null },
            actions = {
                OutlinedButton(
                    onClick = { restoreConfirmItem = null },
                    shape = RoundedCornerShape(percent = 50),
                    border = BorderStroke(1.dp, c.text.copy(alpha = 0.2f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Button(
                    onClick = {
                        val itemToRestore = backupItem
                        val doClean = cleanRestore
                        restoreConfirmItem = null
                        isRestoring = true
                        coroutineScope.launch {
                            delay(600)
                            viewModel.executeRestore(itemToRestore, doClean) { success, errMsg ->
                                isRestoring = false
                                if (success) {
                                    Toast.makeText(context, "Backup restored successfully!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Failed to restore: ${errMsg ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = if (cleanRestore) c.expense else c.accent),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (cleanRestore) "Replace All" else "Merge Restore", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        ) {
            Text(
                "Restoring backup from ${
                    java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(backupItem.timestamp))
                }.",
                color = c.text, fontSize = 13.sp
            )

            // Merge info (default behaviour)
            Surface(
                color = c.accent.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, c.accent.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "By default only missing records are added — your existing data is kept intact.",
                    color = c.accent, fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Clean restore toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable { cleanRestore = !cleanRestore }
            ) {
                Checkbox(
                    checked = cleanRestore,
                    onCheckedChange = { cleanRestore = it },
                    colors = CheckboxDefaults.colors(checkedColor = c.expense)
                )
                Column {
                    Text("Clean restore (replace all data)", color = if (cleanRestore) c.expense else c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Wipes current records before restoring", color = c.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    if (isRestoring) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = null,
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                ) {
                    CircularProgressIndicator(color = c.accent)
                    Text("Restoring database snap... please wait", color = c.text)
                }
            },
            containerColor = c.surface
        )
    }
}

/** Import a CSV or JSON backup file from storage. */
@Composable
fun RestoreBackupDialog(
    viewModel: FinanceViewModel,
    onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    val context = LocalContext.current

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.restoreFromBackupUri(context, uri) { success, errMsg ->
                if (success) {
                    Toast.makeText(context, "Backup restored successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to restore backup: ${errMsg ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
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
                    "Restore from a CSV or JSON backup file",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )

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
                    Triple(Icons.Default.AccountBalanceWallet,        "Accounts",     "All wallets recreated with their types"),
                    Triple(Icons.AutoMirrored.Filled.ReceiptLong,     "Transactions", "All records with date, amount, category"),
                    Triple(Icons.Default.PieChart,                    "Budgets",      "Monthly limits and expected income targets"),
                    Triple(Icons.Default.MergeType,                   "Existing Data","Preserved — only missing records are added")
                ).forEachIndexed { idx, (icon, title, sub) ->
                    val tint = if (idx == 3) c.expense else c.accent
                    val bg   = if (idx == 3) c.expense.copy(0.1f) else c.accent.copy(0.08f)
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
                        onClick = { openDocLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/json", "*/*")) },
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.expense,
                            contentColor = c.text
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Browse File", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = c.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// PRO GATE — dims content and overlays a lock badge; tapping shows upgrade UI.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ProGate(
    isPaid: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var showUpgrade by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Box(modifier = Modifier.alpha(if (isPaid) 1f else 0.45f)) { content() }
        if (!isPaid) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { showUpgrade = true }
            )
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                color = Color(0xFFFFA000),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(9.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("PRO", fontSize = 7.sp, color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
            }
        }
    }
    if (showUpgrade) {
        ProUpgradeDialog(
            viewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
            onDismiss = { showUpgrade = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PRO UPGRADE DIALOG — shown when a locked feature is tapped.
// Also shown from MainAppScreen when the title is tapped (via showProUpgrade).
// Activation: user pays offline → receives an activation code from the team.
// ─────────────────────────────────────────────────────────────────────────────
private val VALID_ACTIVATION_CODES = setOf(
    "ALP2026-SACHET","ALP2026-ANNUAL", "ALP2026-LIFETIME",
    "LEDGER-PRO-2026"
)

/** Trial codes → number of days active before auto-expiry. */
private val TRIAL_ACTIVATION_CODES: Map<String, Int> = mapOf(
    "ALP-TRIAL7-26"  to 7,
    "ALP-TRIAL45-26" to 45
)

/** Pro upgrade screen — feature list, pricing plans, UPI payment, activation code entry. */
@Composable
fun ProUpgradeDialog(
    autoTrackedThisMonth: Int = 0,
    viewModel: FinanceViewModel? = null,
    onDismiss: () -> Unit
) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    var codeInput by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf(1) }  // 0=Sachet, 1=Annual, 2=Lifetime
    val isPaid = viewModel?.isPaidFeaturesEnabled?.collectAsStateWithLifecycle()?.value ?: false

    // UPI payment launcher — checks Status from UPI app response
    val upiLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val status = result.data?.getStringExtra("Status")
            ?: result.data?.getStringExtra("status") ?: ""
        when {
            status.equals("SUCCESS", ignoreCase = true) -> {
                viewModel?.setPaidFeaturesEnabled(true)
                viewModel?.addProStatusInAppNotification(true)
                Toast.makeText(context, "✦ AutoLedger Pro activated! Payment confirmed.", Toast.LENGTH_LONG).show()
                onDismiss()
            }
            result.resultCode != android.app.Activity.RESULT_CANCELED && status.isNotEmpty() -> {
                Toast.makeText(context, "Payment status: $status. If you've paid, enter your activation code below.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val planAmountsStr = listOf("39.00", "149.00", "299.00")
    val planNames = listOf("Sachet Pass 3M", "Annual Pass", "Lifetime License")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = c.bg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.ime))
            ) {
                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── Header ────────────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = CircleShape, color = c.accent.copy(0.15f), modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = c.accent, modifier = Modifier.size(26.dp))
                            }
                        }
                        Column {
                            Text("AutoLedger Pro", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = c.text)
                            Text("Automate your finances", fontSize = 13.sp, color = c.textSecondary)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = c.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }

                    // ── Savings counter ───────────────────────────────────────
                    if (autoTrackedThisMonth > 0 || isPaid) {
                        Surface(
                            color = c.accent.copy(0.08f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, c.accent.copy(0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val count = if (autoTrackedThisMonth > 0) autoTrackedThisMonth else 87
                                    Text("This month we auto-tracked", fontSize = 11.sp, color = c.textSecondary)
                                    Text("$count transactions", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = c.accent)
                                    Text("saving you from hours of typing ✦", fontSize = 11.sp, color = c.textSecondary)
                                }
                            }
                        }
                    }

                    // ── Pro features list ─────────────────────────────────────
                    Text("WHAT YOU UNLOCK", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = c.textSecondary, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.Start))
                    val features = listOf(
                        Icons.Default.Sms to "Auto SMS Scan" to "Auto-Tracks UPI, bank & wallet transactions",
                        Icons.Default.Category to "Smart Categorisation" to "Auto-maps merchants to the right category",
                        Icons.Default.CloudUpload to "Cloud Backup" to "Never lose your data — auto-backs up daily",
                        Icons.Default.FilterList to "Advanced Filters" to "Filter by category, account & date range",
                        Icons.Default.DateRange to "Long-range Analysis" to "3-month, 6-month & yearly charts",
                        Icons.Default.Calculate to "Budget Tools" to "Budgeted-only view, income targets & more"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        features.forEach { (iconLabel, desc) ->
                            val (icon, label) = iconLabel
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(shape = CircleShape, color = c.accent.copy(0.12f), modifier = Modifier.size(30.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp)) }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = c.text)
                                    Text(desc, fontSize = 11.sp, color = c.textSecondary)
                                }
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = c.income, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    HorizontalDivider(color = c.divider)

                    // ── Pricing plans ─────────────────────────────────────────
                    Text("CHOOSE YOUR PLAN", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = c.textSecondary, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.Start))
                    val plans = listOf(
                        Triple("🧪 Sachet Pass", "₹39 for 3 months", "Just testing it out"),
                        Triple("⭐ Annual Pass", "₹149 per year", "Best Value — Save 38%"),
                        Triple("♾ Lifetime License", "₹299 one-time", "Most Popular — Pay once, use forever")
                    )
                    plans.forEachIndexed { idx, (title, price, tag) ->
                        val isSelected = selectedPlan == idx
                        Surface(
                            color = if (isSelected) c.accent.copy(0.10f) else c.surface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) c.accent else c.border),
                            modifier = Modifier.fillMaxWidth().clickable { selectedPlan = idx }
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioButton(selected = isSelected, onClick = { selectedPlan = idx },
                                    colors = RadioButtonDefaults.colors(selectedColor = c.accent))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
                                        if (idx == 1) Surface(color = c.income.copy(0.15f), shape = RoundedCornerShape(4.dp)) {
                                            Text("BEST VALUE", fontSize = 7.sp, color = c.income, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                        }
                                    }
                                    Text(price, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (isSelected) c.accent else c.text)
                                    Text(tag, fontSize = 11.sp, color = c.textSecondary)
                                }
                            }
                        }
                    }

                    // ── Tagline ────────────────────────────────────────────────
                    Surface(color = c.income.copy(0.07f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "\"Automate your budget for less than the price of one cutting chai a month.\"",
                            fontSize = 12.sp, color = c.income, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp)
                        )
                    }

                    // ── UPI Buy Button ────────────────────────────────────────
                    Button(
                        onClick = {
                            val upiUri = android.net.Uri.Builder()
                                .scheme("upi").authority("pay")
                                .appendQueryParameter("pa", "tamilselvanmsr@oksbi")
                                .appendQueryParameter("pn", "AutoLedger")
                                .appendQueryParameter("am", planAmountsStr[selectedPlan])
                                .appendQueryParameter("cu", "INR")
                                .appendQueryParameter("tn", "AutoLedger ${planNames[selectedPlan]}")
                                .build()
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, upiUri)
                            try {
                                upiLauncher.launch(intent)
                            } catch (_: android.content.ActivityNotFoundException) {
                                Toast.makeText(context, "No UPI app found. Install GPay, PhonePe, or Paytm and try again.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.income),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Buy Pro — ₹${planAmountsStr[selectedPlan].removeSuffix(".00")}",
                            fontWeight = FontWeight.ExtraBold, fontSize = 16.sp
                        )
                    }
                    Text(
                        "Opens your UPI app (GPay / PhonePe / Paytm). Pro unlocks instantly on successful payment.",
                        fontSize = 10.sp, color = c.textSecondary, textAlign = TextAlign.Center
                    )

                    HorizontalDivider(color = c.divider)

                    // ── Activation code entry ─────────────────────────────────
                    Text("HAVE AN ACTIVATION CODE?", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = c.textSecondary, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.Start))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.uppercase().replace(" ", ""); codeError = false },
                        label = { Text("Enter activation code", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        isError = codeError,
                        supportingText = if (codeError) {{ Text("Invalid code. Please check and try again.", color = c.expense, fontSize = 11.sp) }} else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.accent, focusedLabelColor = c.accent,
                            focusedTextColor = c.text, unfocusedTextColor = c.text
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val trimmed = codeInput.trim()
                            val trialDays = TRIAL_ACTIVATION_CODES[trimmed]
                            val alreadyUsed = viewModel?.isCodeAlreadyUsed(trimmed) == true
                            when {
                                trimmed.equals("DEACTIVATE", ignoreCase = true) -> {
                                    viewModel?.setPaidFeaturesEnabled(false)
                                    viewModel?.addProStatusInAppNotification(false)
                                    Toast.makeText(context, "AutoLedger Pro deactivated.", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                alreadyUsed -> {
                                    codeError = true
                                    Toast.makeText(context, "This code has already been used on this device.", Toast.LENGTH_SHORT).show()
                                }
                                trialDays != null -> {
                                    viewModel?.activateProWithExpiry(trialDays)
                                    viewModel?.addProStatusInAppNotification(true)
                                    viewModel?.markCodeUsed(trimmed)
                                    val expiry = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                                        .format(java.util.Date(System.currentTimeMillis() + trialDays * 86_400_000L))
                                    Toast.makeText(context, "✦ AutoLedger Pro trial ($trialDays days) activated! Expires $expiry.", Toast.LENGTH_LONG).show()
                                    onDismiss()
                                }
                                VALID_ACTIVATION_CODES.contains(trimmed) -> {
                                    viewModel?.setPaidFeaturesEnabled(true)
                                    viewModel?.addProStatusInAppNotification(true)
                                    viewModel?.markCodeUsed(trimmed)
                                    Toast.makeText(context, "✦ AutoLedger Pro activated! Enjoy all features.", Toast.LENGTH_LONG).show()
                                    onDismiss()
                                }
                                else -> codeError = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                        shape = RoundedCornerShape(12.dp),
                        enabled = codeInput.isNotBlank()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Activate with Code", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── “Maybe Later” ── inside scroll so keyboard doesn’t bury it
                    Surface(
                        color = c.text.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = c.textSecondary)
                        ) {
                            Text("Maybe later", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                } // end scrollable
            }
        }
    }
}

/** Returns [startMs, endMs] timestamp range for [mode] anchored to [anchorTime]. */
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

/** Settings for Accounts tab — visibility toggles, CC details, SMS blocklist management. */
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
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = c.accent,
        checkedTrackColor = c.accent.copy(alpha = 0.45f),
        uncheckedThumbColor = c.textSecondary,
        uncheckedTrackColor = c.divider
    )

    AdaptiveEditorDialog(
        title = "Account Settings",
        onDismiss = onDismiss,
        content = {
            // ── Show Credit Card Details ──────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = c.accent.copy(alpha = 0.15f), modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CreditCard, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp)) }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show Credit Card Details", color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Display available limit & due amount on cards", color = c.textSecondary, fontSize = 11.sp)
                }
                Switch(checked = showCreditCardDetails, onCheckedChange = { viewModel.setShowCreditCardDetails(it) }, colors = switchColors)
            }
            HorizontalDivider(color = c.divider)

            // ── Account visibility ─────────────────────────────────
            Text("ACCOUNT VISIBILITY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSecondary, letterSpacing = 0.8.sp)
            accounts.forEach { acc ->
                val visible = !hiddenAccountIds.contains(acc.id)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = (if (visible) c.accent else c.textTertiary).copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(walletIconFor(acc.name, acc.type), contentDescription = null, tint = if (visible) c.accent else c.textTertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(acc.name, color = if (visible) c.text else c.textTertiary, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Switch(
                        checked = visible,
                        onCheckedChange = { v -> viewModel.setAccountHidden(acc.id, !v) },
                        modifier = Modifier.scale(0.8f),
                        colors = switchColors
                    )
                }
            }
            HorizontalDivider(color = c.divider)

            // ── SMS import blocklist ────────────────────────────────
            Column {
                Text("SMS IMPORT BLOCKLIST", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSecondary, letterSpacing = 0.8.sp)
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
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (patternInput.isNotBlank()) {
                                viewModel.addSmsBlocklistPattern(patternInput.trim())
                                patternInput = ""
                            }
                        },
                        border = BorderStroke(1.dp, c.accent.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add pattern", tint = c.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
            smsBlocklistPatterns.toList().forEach { pattern ->
                Surface(
                    color = c.expense.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, c.expense.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Block, contentDescription = null, tint = c.expense, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(pattern, color = c.expense, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { viewModel.removeSmsBlocklistPattern(pattern) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = c.expense, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // ── Accounts blocked via the Delete Account dialog (persist even after account deleted) ──
            if (blockedAccountNames.isNotEmpty()) {
                HorizontalDivider(color = c.divider)
                Text("BLOCKED ACCOUNTS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.textSecondary, letterSpacing = 0.8.sp)
                blockedAccountNames.toList().forEach { name ->
                    Surface(
                        color = c.expense.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, c.expense.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, color = c.expense, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Blocked from SMS import", color = c.textSecondary, fontSize = 10.sp)
                            }
                            IconButton(onClick = { viewModel.unblockAccountByName(name) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Unblock", tint = c.expense, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            if (smsBlocklistPatterns.isNotEmpty()) {
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
        },
        actions = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.bg)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

/** Returns [startMs, endMs] for the analytics view, delegating to getPeriodRange. */
fun getAnalyticsRange(monthYear: String, filter: String, anchorTimeMs: Long = -1L): Pair<Long, Long> {
    val anchor = if (anchorTimeMs > 0) anchorTimeMs else System.currentTimeMillis()
    // Use the same period logic as Records view so both tabs show identical date ranges
    return when (filter) {
        "DAILY"   -> getPeriodRange(DisplayMode.DAILY, anchor)
        "WEEKLY"  -> getPeriodRange(DisplayMode.WEEKLY, anchor)
        "MONTHLY" -> getPeriodRange(DisplayMode.MONTHLY, anchor)
        "3M"      -> getPeriodRange(DisplayMode.THREE_MONTHS, anchor)
        "6M"      -> getPeriodRange(DisplayMode.SIX_MONTHS, anchor)
        "1Y"      -> getPeriodRange(DisplayMode.ONE_YEAR, anchor)
        else      -> getPeriodRange(DisplayMode.MONTHLY, anchor)
    }
}

/** Returns a new "yyyy-MM" string shifted by [amount] months. */
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

/**
 * Maps a budget usage percentage to a colour:
 *  ≤30% → soft green (safe)   30-50% → yellow   50-75% → amber
 *  75-90% → orange            90-100% → red      >100% → dark red
 */
fun budgetProgressColor(percent: Double, appColors: com.example.ui.theme.AppColors): Color {
    return when {
        percent > 100.0 -> Color(0xFF991B1B)  // >100%: dark red (over budget)
        percent > 90.0  -> Color(0xFFEF4444)  // >90%: red (critical)
        percent > 75.0  -> Color(0xFFFF6B00)  // >75%: vivid orange
        percent > 50.0  -> Color(0xFFFFD500)  // >50%: bright yellow
        percent > 30.0  -> Color(0xFF16A34A)  // >30%: vibrant green (ok)
        else            -> Color(0xFF86EFAC)  // ≤30%: soft green (safe)
    }
}

/** Returns the canonical budget key for a category (always uses category.name). */
fun resolveBudgetCategoryName(category: DisplayCategory, editedName: String): String {
    // Always use category.name as the budget key.
    // For standard categories (even if icon/color customised), name is the enum value (e.g. "FOOD").
    // For user-renamed categories, name is the renamed value.
    // This ensures saveBudget and the activeBudgets lookup both use the same key.
    return category.name
}

/** Shifts the Analytics period by [amount] steps, updating the ViewModel anchor. */
fun shiftAnalyticsPeriod(viewModel: FinanceViewModel, monthYear: String, timeFilter: String, amount: Int, anchorTimeMs: Long = -1L) {
    val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    val cal = if ((timeFilter == "WEEKLY" || timeFilter == "DAILY") && anchorTimeMs > 0) {
        Calendar.getInstance().apply { timeInMillis = anchorTimeMs }
    } else {
        Calendar.getInstance().apply {
            try { time = sdf.parse(monthYear) ?: Date() } catch (_: Exception) { time = Date() }
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    when (timeFilter) {
        "DAILY"  -> cal.add(Calendar.DAY_OF_YEAR, amount)
        "WEEKLY" -> cal.add(Calendar.DAY_OF_YEAR, amount * 7)
        "MONTHLY" -> cal.add(Calendar.MONTH, amount)
        "3M" -> cal.add(Calendar.MONTH, amount * 3)
        "6M" -> cal.add(Calendar.MONTH, amount * 6)
        "1Y" -> cal.add(Calendar.YEAR, amount)
        else -> cal.add(Calendar.MONTH, amount)
    }
    viewModel.setAnchorDate(cal.timeInMillis)
}

/** Returns a human-readable label for the Analytics period bar. */
fun formatAnalyticsPeriodLabel(monthYear: String, timeFilter: String, anchorTimeMs: Long = -1L): String {
    val (startMs, endMs) = getAnalyticsRange(monthYear, timeFilter, anchorTimeMs)
    val startCal = Calendar.getInstance().apply { timeInMillis = startMs }
    val endCal = Calendar.getInstance().apply { timeInMillis = endMs }
    return when (timeFilter) {
        "DAILY"  -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(endCal.time)
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

/** Shifts the Records period by [amount] steps (±1) in the given display [mode]. */
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

/** Returns a human-readable period label for the top bar (e.g. "July 2026", "Week 28–4 Jul"). */
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

// ──────────────────────────────────────────────────────────────────────────────
// GESTURE RECOGNITION OVERLAY
// Passively observes all touch strokes (never consumes events) and triggers:
//   • Draw "S"               → Scan Inbox
//   • Draw clockwise arc/⭕  → Backup
//   • Draw counter-clockwise arc/⭕ → Restore
// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun GestureRecognitionOverlay(
    onScan: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    val c = LocalAppColors.current
    var strokePoints by remember { mutableStateOf<List<androidx.compose.ui.geometry.Offset>>(emptyList()) }
    var label by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        // Draw the user's stroke as a glowing trail
        if (strokePoints.size > 1) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path()
                path.moveTo(strokePoints.first().x, strokePoints.first().y)
                strokePoints.drop(1).forEach { path.lineTo(it.x, it.y) }
                drawPath(
                    path = path,
                    color = c.accent.copy(alpha = 0.55f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 6.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = androidx.compose.ui.graphics.drawscope.Stroke.DefaultMiter.let {
                            androidx.compose.ui.graphics.StrokeJoin.Round
                        }
                    )
                )
            }
        }

        // Gesture feedback badge
        label?.let { lbl ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                color = c.accent.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 12.dp
            ) {
                Text(
                    lbl,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
                )
            }
        }

        // Invisible passthrough layer — observes touches via PointerEventPass.Final
        // so it never blocks scrolling, tapping, or swipe navigation below.
        Box(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pts = mutableListOf(down.position)
                    strokePoints = pts.toList()

                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            pts.add(it.position)
                            strokePoints = pts.toList()
                        }
                    } while (event.changes.any { it.pressed })

                    // Analyse stroke
                    val gesture = detectDrawnGesture(pts)
                    if (gesture != null) {
                        label = when (gesture) {
                            "SCAN"    -> "✦ Scanning Inbox…"
                            "BACKUP"  -> "✦ Backup…"
                            "RESTORE" -> "✦ Restore…"
                            else      -> null
                        }
                        scope.launch {
                            kotlinx.coroutines.delay(150)
                            when (gesture) {
                                "SCAN"    -> onScan()
                                "BACKUP"  -> onBackup()
                                "RESTORE" -> onRestore()
                            }
                            kotlinx.coroutines.delay(1400)
                            label = null
                            strokePoints = emptyList()
                        }
                    } else {
                        scope.launch {
                            kotlinx.coroutines.delay(250)
                            strokePoints = emptyList()
                        }
                    }
                }
            }
        )
    }
}

/**
 * Recognises three gesture shapes from a list of screen-space points:
 *
 *  "SCAN"    — Letter "S": non-closed stroke with ≥2 horizontal direction reversals
 *  "BACKUP"  — Clockwise arc/circle (positive shoelace signed area in screen coords)
 *  "RESTORE" — Counter-clockwise arc/circle (negative signed area)
 *
 * Returns null for any stroke that doesn't clearly match a shape, so normal
 * taps, scrolls, and swipes are never misidentified.
 */
private fun detectDrawnGesture(stroke: List<androidx.compose.ui.geometry.Offset>): String? {
    if (stroke.size < 12) return null

    // Total arc-length of the stroke
    val strokeLen = stroke.zipWithNext().sumOf { (a, b) ->
        sqrt(((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)).toDouble())
    }.toFloat()
    if (strokeLen < 160f) return null   // too short — filter out taps & micro-swipes

    val minX = stroke.minOf { it.x }; val maxX = stroke.maxOf { it.x }
    val minY = stroke.minOf { it.y }; val maxY = stroke.maxOf { it.y }
    val w = maxX - minX
    val h = maxY - minY

    // ── Closed-loop test ──────────────────────────────────────────────────────
    val start = stroke.first(); val end = stroke.last()
    val closure = sqrt(((start.x - end.x) * (start.x - end.x) + (start.y - end.y) * (start.y - end.y)).toDouble()).toFloat()
    val isClosed = closure < strokeLen * 0.38f && w > 55f && h > 55f

    if (isClosed) {
        // Signed area via the shoelace formula.
        // In screen coords (Y downward): positive → clockwise, negative → CCW.
        var signedArea = 0.0
        for (i in stroke.indices) {
            val a = stroke[i]; val b = stroke[(i + 1) % stroke.size]
            signedArea += (a.x.toDouble() * b.y - b.x.toDouble() * a.y)
        }
        return if (signedArea > 0.0) "BACKUP" else "RESTORE"
    }

    // ── S-shape test ─────────────────────────────────────────────────────────
    // Must be taller than wide (portrait orientation of the letter),
    // have significant height, and at least 2 reversals on the X axis.
    if (h > 80f && w > 35f) {
        val xChanges = countDirectionChanges(stroke.map { it.x }, minDelta = w * 0.18f)
        if (xChanges >= 2) return "SCAN"
    }

    return null
}

/**
 * Counts how many times a 1-D sequence of float values reverses direction,
 * ignoring reversals smaller than [minDelta] to suppress noise.
 */
private fun countDirectionChanges(values: List<Float>, minDelta: Float = 15f): Int {
    var changes = 0
    var prevDir = 0
    var anchor = values.firstOrNull() ?: return 0
    for (v in values) {
        val delta = v - anchor
        if (kotlin.math.abs(delta) >= minDelta) {
            val dir = if (delta > 0) 1 else -1
            if (prevDir != 0 && dir != prevDir) changes++
            prevDir = dir
            anchor = v
        }
    }
    return changes
}
