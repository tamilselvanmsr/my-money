package com.example.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class TransactionType {
    EXPENSE, INCOME
}

/**
 * Built-in categories.
 *
 * IMPORTANT: Never rename an enum constant — [name] is the DB primary key stored in
 * transactions, budgets, and custom_categories.  To change what the user sees, update
 * [displayName] only.
 *
 * Icons shown here are the defaults used when the user has NOT customised the category.
 * They must match the corresponding key in [CategoryResolver.getIconFromIconName] so that
 * the icon picker pre-selects the right icon when the user opens the edit dialog.
 */
enum class ExpenseCategory(
    val displayName: String,
    val icon: ImageVector,
    val color: Color,
    val type: String = "EXPENSE"
) {
    // ── Expense ──────────────────────────────────────────────────────────────
    FOOD("Food",                       Icons.Default.Restaurant,      Color(0xFFFF9800)),
    SHOPPING("Shopping",               Icons.Default.ShoppingBag,     Color(0xFFE91E63)),
    TRANSPORT("Transport",             Icons.Default.LocalTaxi,       Color(0xFF03A9F4)),
    BILLS("Bills & Utilities",         Icons.Default.ReceiptLong,     Color(0xFF9C27B0)),
    ENTERTAINMENT("Entertainment",     Icons.Default.LocalPlay,       Color(0xFFFF5722)),
    HEALTHCARE("Healthcare",           Icons.Default.MedicalServices, Color(0xFF00ACC1)),
    EDUCATION("Education",             Icons.Default.School,          Color(0xFF009688)),
    OTHERS("Others / Misc",            Icons.Default.Category,        Color(0xFF607D8B)),
    CAR("Car",                         Icons.Default.DirectionsCar,   Color(0xFF0288D1)),
    ELECTRONICS("Electronics",         Icons.Default.Devices,         Color(0xFF7B1FA2)),
    INSURANCE("Insurance",             Icons.Default.Security,        Color(0xFFEC407A)),
    SOCIAL("Social",                   Icons.Default.Group,           Color(0xFF26A69A)),
    TAX("Tax",                         Icons.Default.Percent,         Color(0xFF795548)),
    SPORT("Sport",                     Icons.Default.SportsSoccer,    Color(0xFF8BC34A)),
    GYM("Gym",                         Icons.Default.FitnessCenter,   Color(0xFF455A64)),
    RECHARGE("Recharge",               Icons.Default.Smartphone,      Color(0xFF7986CB)),
    DEBT("Debt / Loan",                Icons.Default.CreditCard,      Color(0xFF5D4037)),
    FUEL("Fuel",                       Icons.Default.LocalGasStation, Color(0xFFF44336)),
    CLOTHES("Clothes",                 Icons.Default.Checkroom,       Color(0xFFC2185B)),
    // DirectionsRun matches the "shoes" icon key in suitableIconsList / getIconFromIconName
    SHOES("Shoes & Footwear",          Icons.Default.DirectionsRun,   Color(0xFF5C6BC0)),
    POCKET_MONEY("Pocket Money",       Icons.Default.Payments,        Color(0xFFFFA000)),
    // WaterDrop matches the "fruits" icon key in suitableIconsList / getIconFromIconName
    FRUITS("Fruits & Veggies",         Icons.Default.WaterDrop,       Color(0xFF4CAF50)),
    TRAVEL("Travel & Commute",         Icons.Default.Flight,          Color(0xFF3949AB)),
    BIKE("Bike & Maintenance",         Icons.Default.TwoWheeler,      Color(0xFFFF8F00)),
    GIFTING_FRIENDS("Gifting Friends", Icons.Default.CardGiftcard,    Color(0xFFAD1457)),
    INVESTMENT("Investment",           Icons.Default.TrendingUp,      Color(0xFF1565C0)),
    MUTUAL_FUND("Mutual Fund",         Icons.Default.AccountBalance,  Color(0xFF00897B)),
    ETF("ETF",                         Icons.Default.BarChart,        Color(0xFF1976D2)),
    RENT("Rent",                       Icons.Default.Home,            Color(0xFF7E57C2)),
    COOKING("Cooking",                 Icons.Default.Kitchen,         Color(0xFFFF7043)),
    GROCERIES("Groceries",             Icons.Default.ShoppingCart,    Color(0xFF388E3C)),
    SOFT_HOT_DRINKS("Tea & Soft Drinks", Icons.Default.LocalCafe,     Color(0xFF8D6E63)),
    /** Kept for DB backward-compat; hidden from pickers via [CategoryResolver.BUILTIN_HIDDEN]. */
    OUTSIDE_FOOD("Outside Food",       Icons.Default.Fastfood,        Color(0xFFFFB300)),

    // ── Income ───────────────────────────────────────────────────────────────
    SALARY("Salary",                   Icons.Default.AttachMoney,     Color(0xFF2E7D32), "INCOME"),
    CC_SETTLEMENT("CC Settlement",     Icons.Default.Payment,         Color(0xFF00BFA5), "INCOME"),
    PROVIDENT_FUND("Provident Fund",   Icons.Default.AccountBalance,  Color(0xFF4527A0), "INCOME"),
    CASHBACK("Cashback",               Icons.Default.Redeem,          Color(0xFF14B8A6), "INCOME"),
    COUPONS("Coupons",                 Icons.Default.CardGiftcard,    Color(0xFFFF4081), "INCOME"),
    GRANTS("Grants",                   Icons.Default.Handshake,       Color(0xFF00E676), "INCOME"),
    REFUNDS("Refunds",                 Icons.Default.Cached,          Color(0xFF00B0FF), "INCOME"),
    RENTAL("Rental",                   Icons.Default.Domain,          Color(0xFF651FFF), "INCOME"),
    SALE("Sale",                       Icons.Default.Storefront,      Color(0xFFFF9100), "INCOME"),
    REWARDS("Rewards",                 Icons.Default.MilitaryTech,    Color(0xFF76FF03), "INCOME"),
    COINS("Coins",                     Icons.Default.Savings,         Color(0xFFFBC02D), "INCOME"),
    UPI("UPI",                         Icons.Default.QrCode,          Color(0xFF0EA5E9), "INCOME"),
    ADJUST("Balance Adjust",           Icons.Default.SwapVert,        Color(0xFF00E5FF), "INCOME"),
    /** Kept for DB backward-compat; hidden from pickers via [CategoryResolver.BUILTIN_HIDDEN]. */
    POCKET_MONEY_INC("Pocket Money (Received)", Icons.Default.Savings, Color(0xFF2E7D32), "INCOME");

    companion object {
        /**
         * Look up a category by its DB key (case-insensitive).
         * Handles the legacy "INCOME" type string; falls back to [OTHERS] for unknown names.
         */
        fun fromString(name: String): ExpenseCategory =
            if (name.equals("INCOME", ignoreCase = true)) SALARY
            else entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OTHERS
    }
}

/**
 * A unified view of a category, whether it comes from the built-in enum or a user
 * customisation stored in the database.
 *
 * [name]        — stable DB key; for built-in overrides this is always the enum constant name
 *                 (e.g. "GROCERIES"), never a drifted display-name variant like "Groceries".
 * [displayName] — human-readable label shown in the UI.
 * [isCustom]    — true when backed by a [CustomCategory] DB row.
 * [customId]    — primary key of that row (0 for pure built-in entries).
 */
data class DisplayCategory(
    val name: String,
    val displayName: String,
    val icon: ImageVector,
    val color: Color,
    val isCustom: Boolean = false,
    val customId: Int = 0,
    val type: String = "EXPENSE"
)

/**
 * Single source of truth for turning raw DB data into [DisplayCategory] objects.
 *
 * Precedence rule (highest to lowest):
 *   1. A non-hidden [CustomCategory] row whose name matches (case-insensitive) → custom icon/color
 *   2. A matching [ExpenseCategory] enum constant → built-in icon/color
 *   3. Fallback placeholder (grey "Category" icon)
 *
 * The canonical [DisplayCategory.name] is ALWAYS the enum constant name for built-in
 * categories (even when a custom override exists), preventing key drift in budgets and
 * transactions.
 */
object CategoryResolver {

    /**
     * Category names that are kept in the enum only for DB backward-compatibility.
     * They are never shown in any picker or list.
     */
    val BUILTIN_HIDDEN = setOf("outside_food", "pocket_money_inc")

    // ── Icon name → vector mapping ────────────────────────────────────────────
    //
    // Every key in [suitableIconsList] (MainActivity) must have an entry here.
    // Multiple aliases for the same icon are supported (e.g. "shoes" and "directionsrun"
    // both resolve to DirectionsRun).
    //
    fun getIconFromIconName(name: String): ImageVector {
        return when (name.split(":").first().lowercase().trim()) {
            // Dining / food
            "restaurant"        -> Icons.Default.Restaurant
            "fastfood"          -> Icons.Default.Fastfood
            "outside_food"      -> Icons.Default.Fastfood
            "coffee"            -> Icons.Default.LocalCafe
            "localcafe"         -> Icons.Default.LocalCafe
            "soft_hot_drinks"   -> Icons.Default.LocalCafe
            "kitchen"           -> Icons.Default.Kitchen
            "cooking"           -> Icons.Default.Kitchen
            "shoppingcart"      -> Icons.Default.ShoppingCart
            "groceries"         -> Icons.Default.ShoppingCart
            "fruits"            -> Icons.Default.WaterDrop   // matches suitableIconsList
            "waterdrop"         -> Icons.Default.WaterDrop
            "eco"               -> Icons.Default.Eco
            "localbar"          -> Icons.Default.LocalBar
            "drinks"            -> Icons.Default.LocalBar

            // Shopping / retail
            "shoppingbag"       -> Icons.Default.ShoppingBag
            "shopping"          -> Icons.Default.ShoppingBag
            "checkroom"         -> Icons.Default.Checkroom
            "clothes"           -> Icons.Default.Checkroom
            "directionsrun"     -> Icons.Default.DirectionsRun
            "shoes"             -> Icons.Default.DirectionsRun  // matches suitableIconsList
            "laptop"            -> Icons.Default.Laptop
            "online_shopping"   -> Icons.Default.Laptop

            // Transport
            "localtaxi"         -> Icons.Default.LocalTaxi
            "transport"         -> Icons.Default.LocalTaxi
            "cab"               -> Icons.Default.LocalTaxi
            "directionscar"     -> Icons.Default.DirectionsCar
            "car"               -> Icons.Default.DirectionsCar
            "twowheeler"        -> Icons.Default.TwoWheeler
            "bike"              -> Icons.Default.TwoWheeler
            "flight"            -> Icons.Default.Flight
            "travel"            -> Icons.Default.Flight
            "airportshuttle"    -> Icons.Default.AirportShuttle
            "transportation"    -> Icons.Default.AirportShuttle
            "localgasstation"   -> Icons.Default.LocalGasStation
            "fuel"              -> Icons.Default.LocalGasStation

            // Home / utilities
            "home"              -> Icons.Default.Home
            "rent"              -> Icons.Default.Home
            "receiptlong"       -> Icons.Default.ReceiptLong
            "bills"             -> Icons.Default.ReceiptLong
            "smartphone"        -> Icons.Default.Smartphone
            "recharge"          -> Icons.Default.Smartphone
            "bolt"              -> Icons.Default.Bolt

            // Health / wellness
            "medicalservices"   -> Icons.Default.MedicalServices
            "healthcare"        -> Icons.Default.MedicalServices
            "localpharmacy"     -> Icons.Default.LocalPharmacy
            "pharmacy"          -> Icons.Default.LocalPharmacy
            "fitnesscenter"     -> Icons.Default.FitnessCenter
            "fitness"           -> Icons.Default.FitnessCenter
            "gym"               -> Icons.Default.FitnessCenter
            "spa"               -> Icons.Default.Spa
            "beauty"            -> Icons.Default.Spa

            // Finance / money
            "attachmoney"       -> Icons.Default.AttachMoney
            "salary"            -> Icons.Default.AttachMoney
            "creditcard"        -> Icons.Default.CreditCard
            "debt"              -> Icons.Default.CreditCard
            "payments"          -> Icons.Default.Payments
            "pocket_money"      -> Icons.Default.Payments
            "savings"           -> Icons.Default.Savings
            "coins"             -> Icons.Default.Savings
            "pocket_money_inc"  -> Icons.Default.Savings
            "trendindup"        -> Icons.Default.TrendingUp
            "investment"        -> Icons.Default.TrendingUp
            "accountbalance"    -> Icons.Default.AccountBalance
            "mutual_fund"       -> Icons.Default.AccountBalance
            "provident_fund"    -> Icons.Default.AccountBalance
            "barchart"          -> Icons.Default.BarChart
            "etf"               -> Icons.Default.BarChart
            "payment"           -> Icons.Default.Payment
            "cc_settlement"     -> Icons.Default.Payment
            "swapvert"          -> Icons.Default.SwapVert
            "adjust"            -> Icons.Default.SwapVert

            // Rewards / cashback
            "redeem"            -> Icons.Default.Redeem
            "cashback"          -> Icons.Default.Redeem
            "cached"            -> Icons.Default.Cached
            "refunds"           -> Icons.Default.Cached
            "militarytech"      -> Icons.Default.MilitaryTech
            "rewards"           -> Icons.Default.MilitaryTech
            "emojievents"       -> Icons.Default.EmojiEvents
            "awards"            -> Icons.Default.EmojiEvents

            // Business / income
            "storefront"        -> Icons.Default.Storefront
            "sale"              -> Icons.Default.Storefront
            "domain"            -> Icons.Default.Domain
            "rental"            -> Icons.Default.Domain
            "handshake"         -> Icons.Default.Handshake
            "grants"            -> Icons.Default.Handshake
            "work"              -> Icons.Default.Work
            "qrcode"            -> Icons.Default.QrCode
            "upi"               -> Icons.Default.QrCode

            // Entertainment / lifestyle
            "localplay"         -> Icons.Default.LocalPlay
            "entertainment"     -> Icons.Default.LocalPlay
            "movie"             -> Icons.Default.Movie
            "musicnote"         -> Icons.Default.MusicNote
            "music"             -> Icons.Default.MusicNote
            "hotel"             -> Icons.Default.Hotel
            "beachaccess"       -> Icons.Default.BeachAccess
            "vacation"          -> Icons.Default.BeachAccess
            "celebration"       -> Icons.Default.Celebration
            "party"             -> Icons.Default.Celebration
            "cake"              -> Icons.Default.Cake
            "birthday"          -> Icons.Default.Cake
            "fireplace"         -> Icons.Default.Fireplace
            "campfire"          -> Icons.Default.Fireplace
            "sportssoccer"      -> Icons.Default.SportsSoccer
            "sport"             -> Icons.Default.SportsSoccer

            // People / social
            "group"             -> Icons.Default.Group
            "social"            -> Icons.Default.Group
            "childcare"         -> Icons.Default.ChildCare
            "children"          -> Icons.Default.ChildCare
            "pets"              -> Icons.Default.Pets
            "pet"               -> Icons.Default.Pets
            "cardgiftcard"      -> Icons.Default.CardGiftcard
            "gift"              -> Icons.Default.CardGiftcard
            "gifting_friends"   -> Icons.Default.CardGiftcard
            "coupons"           -> Icons.Default.CardGiftcard

            // Education / misc
            "school"            -> Icons.Default.School
            "education"         -> Icons.Default.School
            "percent"           -> Icons.Default.Percent
            "tax"               -> Icons.Default.Percent
            "security"          -> Icons.Default.Security
            "insurance"         -> Icons.Default.Security
            "devices"           -> Icons.Default.Devices
            "electronics"       -> Icons.Default.Devices
            "build"             -> Icons.Default.Build
            "maintenance"       -> Icons.Default.Build
            "category"          -> Icons.Default.Category
            "others"            -> Icons.Default.Category

            else                -> Icons.Default.Category
        }
    }

    /**
     * Resolve a single category DB key to a [DisplayCategory].
     *
     * The returned [DisplayCategory.name] is always the canonical enum key for built-in
     * categories (e.g. "GROCERIES"), even when the custom override stored a different casing.
     */
    fun resolve(name: String, customList: List<CustomCategory>): DisplayCategory {
        // Legacy entries stored as bare "INCOME" before per-category typing was introduced
        if (name.equals("INCOME", ignoreCase = true)) {
            return DisplayCategory(
                name = ExpenseCategory.SALARY.name,
                displayName = ExpenseCategory.SALARY.displayName,
                icon = ExpenseCategory.SALARY.icon,
                color = ExpenseCategory.SALARY.color,
                type = ExpenseCategory.SALARY.type
            )
        }

        // Look for a visible (non-hidden) custom entry whose name matches
        val custom = customList.firstOrNull {
            it.name.equals(name, ignoreCase = true) &&
                !it.iconName.startsWith("hidden:") &&
                it.iconName != "hidden"
        }
        if (custom != null) return buildFromCustom(custom)

        // Fall through to built-in
        val standard = ExpenseCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (standard != null) {
            return DisplayCategory(
                name = standard.name,
                displayName = standard.displayName,
                icon = standard.icon,
                color = standard.color,
                type = standard.type
            )
        }

        // Unknown name — show a generic placeholder
        return DisplayCategory(
            name = name,
            displayName = name,
            icon = Icons.Default.Category,
            color = Color(0xFF607D8B)
        )
    }

    /**
     * Build the full, deduplicated list of selectable categories.
     *
     * Key behaviours:
     * - Built-in categories appear in enum declaration order.
     * - A custom override of a built-in replaces it **in the same position** in the list,
     *   applying the user's icon/color while keeping the canonical enum key as [DisplayCategory.name].
     * - Brand-new user-created categories (no matching enum entry) are appended at the end.
     * - Categories hidden by the user (iconName = "hidden:…") or by [BUILTIN_HIDDEN] are excluded.
     * - The list is guaranteed to have no duplicate names (case-insensitive).
     */
    fun getAll(customList: List<CustomCategory>): List<DisplayCategory> {
        // Collect names that have been explicitly hidden
        val hiddenKeys = customList
            .filter { it.iconName.startsWith("hidden:") || it.iconName == "hidden" }
            .map { it.name.lowercase() }
            .toSet()

        // Build a map: canonical-enum-key (lowercase) → custom override for that standard category
        // Last write wins (handles accidental duplicate DB rows for the same name).
        val standardOverrides = mutableMapOf<String, DisplayCategory>()

        // Pure custom additions (no matching built-in) — LinkedHashMap preserves insertion order;
        // last write wins for the same name.
        val customAdditions = LinkedHashMap<String, DisplayCategory>()

        customList.forEach { custom ->
            if (custom.iconName.startsWith("hidden:") || custom.iconName == "hidden") return@forEach

            val standardMatch = ExpenseCategory.entries
                .firstOrNull { it.name.equals(custom.name, ignoreCase = true) }

            val entry = buildFromCustom(custom, canonicalEnumName = standardMatch?.name)

            if (standardMatch != null) {
                // Override an existing built-in — key is always the uppercase enum name
                standardOverrides[standardMatch.name.lowercase()] = entry
            } else {
                // Pure user-created category
                customAdditions[custom.name.lowercase()] = entry
            }
        }

        val result = mutableListOf<DisplayCategory>()

        // 1. Walk enum in declaration order; apply override in-place when present
        ExpenseCategory.entries.forEach { std ->
            val key = std.name.lowercase()
            if (hiddenKeys.contains(key) || BUILTIN_HIDDEN.contains(key)) return@forEach

            result.add(
                standardOverrides[key] ?: DisplayCategory(
                    name = std.name,
                    displayName = std.displayName,
                    icon = std.icon,
                    color = std.color,
                    type = std.type
                )
            )
        }

        // 2. Append brand-new custom categories that don't override any built-in
        customAdditions.values.forEach { entry ->
            if (!hiddenKeys.contains(entry.name.lowercase())) {
                result.add(entry)
            }
        }

        return result
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Convert a [CustomCategory] DB row into a [DisplayCategory].
     *
     * @param canonicalEnumName  If this row overrides a built-in, pass the exact
     *   [ExpenseCategory.name] so the returned [DisplayCategory.name] is the stable DB key.
     *   Pass null for brand-new user-created categories.
     */
    private fun buildFromCustom(
        custom: CustomCategory,
        canonicalEnumName: String? = null
    ): DisplayCategory {
        val parts = custom.iconName.split(":")
        val iconKey  = parts.getOrNull(0) ?: "category"
        val typeKey  = parts.getOrNull(1) ?: "EXPENSE"

        val icon  = getIconFromIconName(iconKey)
        val color = try {
            Color(android.graphics.Color.parseColor(custom.colorHex))
        } catch (_: Exception) {
            Color(0xFF6750A4)
        }

        // Use the standard's display name for overrides so the UI label is always consistent
        val standardMatch = if (canonicalEnumName != null)
            ExpenseCategory.entries.firstOrNull { it.name == canonicalEnumName }
        else
            ExpenseCategory.entries.firstOrNull { it.name.equals(custom.name, ignoreCase = true) }

        return DisplayCategory(
            name        = canonicalEnumName ?: custom.name,
            displayName = standardMatch?.displayName ?: custom.name,
            icon        = icon,
            color       = color,
            isCustom    = true,
            customId    = custom.id,
            type        = typeKey
        )
    }
}

