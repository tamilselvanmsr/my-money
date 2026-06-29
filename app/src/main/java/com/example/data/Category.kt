package com.example.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class TransactionType {
    EXPENSE, INCOME
}

enum class ExpenseCategory(
    val displayName: String,
    val icon: ImageVector,
    val color: Color,
    val type: String = "EXPENSE"
) {
    // Legacy / original categories
    FOOD("Food", Icons.Default.Restaurant, Color(0xFFFF9800)),
    SHOPPING("Shopping", Icons.Default.ShoppingBag, Color(0xFFE91E63)),
    TRANSPORT("Transport", Icons.Default.LocalTaxi, Color(0xFF03A9F4)),
    BILLS("Bills & Utilities", Icons.Default.ReceiptLong, Color(0xFF9C27B0)),
    ENTERTAINMENT("Entertainment", Icons.Default.LocalPlay, Color(0xFFFF5722)),
    HEALTHCARE("Healthcare", Icons.Default.MedicalServices, Color(0xFF00ACC1)),
    EDUCATION("Education", Icons.Default.School, Color(0xFF009688)),
    OTHERS("Others / Misc", Icons.Default.Category, Color(0xFF607D8B)),

    // Newly requested Expense categories
    CAR("Car", Icons.Default.DirectionsCar, Color(0xFF0288D1)),
    ELECTRONICS("Electronics", Icons.Default.Devices, Color(0xFF7B1FA2)),
    INSURANCE("Insurance", Icons.Default.Security, Color(0xFFEC407A)),
    SOCIAL("Social", Icons.Default.Group, Color(0xFF26A69A)),
    TAX("Tax", Icons.Default.Percent, Color(0xFF795548)),
    SPORT("Sport", Icons.Default.SportsSoccer, Color(0xFF8BC34A)),
    GYM("Gym", Icons.Default.FitnessCenter, Color(0xFF455A64)),
    RECHARGE("Recharge", Icons.Default.Smartphone, Color(0xFF7986CB)),
    
    // Additional requested standard categories (Expense)
    DEBT("Debt / Loan", Icons.Default.CreditCard, Color(0xFF5D4037)),
    FUEL("Fuel", Icons.Default.LocalGasStation, Color(0xFFF44336)),
    CLOTHES("Clothes", Icons.Default.Checkroom, Color(0xFFC2185B)),
    SHOES("Shoes & Footwear", Icons.Default.ShoppingBag, Color(0xFF5C6BC0)),
    POCKET_MONEY("Pocket Money", Icons.Default.Payments, Color(0xFFFFA000)),
    FRUITS("Fruits & Veggies", Icons.Default.Eco, Color(0xFF4CAF50)),
    TRAVEL("Travel & Commute", Icons.Default.Flight, Color(0xFF3949AB)),
    BIKE("Bike & Maintenance", Icons.Default.TwoWheeler, Color(0xFFFF8F00)),
    GIFTING_FRIENDS("Gifting Friends", Icons.Default.CardGiftcard, Color(0xFFAD1457)),
    INVESTMENT("Investment", Icons.Default.TrendingUp, Color(0xFF1565C0)),
    MUTUAL_FUND("Mutual Fund", Icons.Default.AccountBalance, Color(0xFF00897B)),
    ETF("ETF", Icons.Default.BarChart, Color(0xFF1976D2)),
    RENT("Rent", Icons.Default.Home, Color(0xFF7E57C2)),
    COOKING("Cooking", Icons.Default.Kitchen, Color(0xFFFF7043)),
    OUTSIDE_FOOD("Outside Food", Icons.Default.Fastfood, Color(0xFFFFB300)),
    GROCERIES("Groceries", Icons.Default.ShoppingCart, Color(0xFF26C6DA)),
    SOFT_HOT_DRINKS("Tea & Soft Drinks", Icons.Default.LocalCafe, Color(0xFF8D6E63)),

    // Newly requested Income categories
    CASHBACK("Cashback", Icons.Default.Redeem, Color(0xFF14B8A6), "INCOME"),
    COUPONS("Coupons", Icons.Default.CardGiftcard, Color(0xFFFF4081), "INCOME"),
    GRANTS("Grants", Icons.Default.Handshake, Color(0xFF00E676), "INCOME"),
    REFUNDS("Refunds", Icons.Default.Cached, Color(0xFF00B0FF), "INCOME"),
    RENTAL("Rental", Icons.Default.Domain, Color(0xFF651FFF), "INCOME"),
    SALARY("Salary", Icons.Default.AttachMoney, Color(0xFF2E7D32), "INCOME"),
    SALE("Sale", Icons.Default.Storefront, Color(0xFFFF9100), "INCOME"),
    REWARDS("Rewards", Icons.Default.MilitaryTech, Color(0xFF76FF03), "INCOME"),
    COINS("Coins", Icons.Default.Savings, Color(0xFFFBC02D), "INCOME"),
    UPI("UPI", Icons.Default.QrCode, Color(0xFF0EA5E9), "INCOME"),
    POCKET_MONEY_INC("Pocket Money (Received)", Icons.Default.Savings, Color(0xFF2E7D32), "INCOME"),
    CC_SETTLEMENT("CC Settlement", Icons.Default.Payment, Color(0xFF00BFA5), "INCOME"),
    ADJUST("Balance Adjust", Icons.Default.SwapVert, Color(0xFF00E5FF), "INCOME"),
    PROVIDENT_FUND("Provident Fund", Icons.Default.AccountBalance, Color(0xFF4527A0), "INCOME");

    companion object {
        fun fromString(name: String): ExpenseCategory {
            if (name.equals("INCOME", ignoreCase = true)) {
                return SALARY
            }
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OTHERS
        }
    }
}

data class DisplayCategory(
    val name: String,
    val displayName: String,
    val icon: ImageVector,
    val color: Color,
    val isCustom: Boolean = false,
    val customId: Int = 0,
    val type: String = "EXPENSE"
)

object CategoryResolver {
    private fun legacyIncomeCategory(): DisplayCategory {
        return DisplayCategory(
            name = ExpenseCategory.SALARY.name,
            displayName = ExpenseCategory.SALARY.displayName,
            icon = ExpenseCategory.SALARY.icon,
            color = ExpenseCategory.SALARY.color,
            isCustom = false,
            type = ExpenseCategory.SALARY.type
        )
    }

    fun getIconFromIconName(name: String): ImageVector {
        val cleanName = name.split(":").firstOrNull() ?: name
        return when (cleanName.lowercase()) {
            "restaurant" -> Icons.Default.Restaurant
            "shopping" -> Icons.Default.ShoppingBag
            "transport" -> Icons.Default.LocalTaxi
            "car" -> Icons.Default.DirectionsCar
            "bills" -> Icons.Default.ReceiptLong
            "entertainment" -> Icons.Default.LocalPlay
            "healthcare" -> Icons.Default.MedicalServices
            "education" -> Icons.Default.School
            "home" -> Icons.Default.Home
            "work" -> Icons.Default.Work
            "fitness" -> Icons.Default.FitnessCenter
            "gym" -> Icons.Default.FitnessCenter
            "flight" -> Icons.Default.Flight
            "coffee" -> Icons.Default.LocalCafe
            "recharge" -> Icons.Default.Smartphone
            "sport" -> Icons.Default.SportsSoccer
            "electronics" -> Icons.Default.Devices
            "insurance" -> Icons.Default.Security
            "social" -> Icons.Default.Group
            "tax" -> Icons.Default.Percent
            "transportation" -> Icons.Default.AirportShuttle
            "cashback" -> Icons.Default.Redeem
            "coupons" -> Icons.Default.CardGiftcard
            "grants" -> Icons.Default.Handshake
            "refunds" -> Icons.Default.Cached
            "rental" -> Icons.Default.Domain
            "salary" -> Icons.Default.AttachMoney
            "sale" -> Icons.Default.Storefront
            "rewards" -> Icons.Default.MilitaryTech
            "coins" -> Icons.Default.Savings
            "handshake" -> Icons.Default.Handshake
            "localgasstation" -> Icons.Default.LocalGasStation
            "checkroom" -> Icons.Default.Checkroom
            "payments" -> Icons.Default.Payments
            "eco" -> Icons.Default.Eco
            "twowheeler" -> Icons.Default.TwoWheeler
            "bolt" -> Icons.Default.Bolt
            "cardgiftcard" -> Icons.Default.CardGiftcard
            "creditcard" -> Icons.Default.CreditCard
            "qrcode" -> Icons.Default.QrCode
            "upi" -> Icons.Default.QrCode
            "investment" -> Icons.Default.TrendingUp
            "mutual_fund" -> Icons.Default.AccountBalance
            "etf" -> Icons.Default.BarChart
            "rent" -> Icons.Default.Home
            "cooking" -> Icons.Default.Kitchen
            "outside_food" -> Icons.Default.Fastfood
            "soft_hot_drinks" -> Icons.Default.LocalCafe
            "adjust" -> Icons.Default.SwapVert
            "groceries" -> Icons.Default.ShoppingCart
            // Icons added in v32 (were missing — caused icon to reset to "Others")
            "awards" -> Icons.Default.EmojiEvents
            "kitchen" -> Icons.Default.Kitchen
            "hotel" -> Icons.Default.Hotel
            "movie" -> Icons.Default.Movie
            "music" -> Icons.Default.MusicNote
            "gift" -> Icons.Default.CardGiftcard
            "children" -> Icons.Default.ChildCare
            "pet" -> Icons.Default.Pets
            "pharmacy" -> Icons.Default.LocalPharmacy
            "online_shopping" -> Icons.Default.Laptop
            "maintenance" -> Icons.Default.Build
            "drinks" -> Icons.Default.LocalBar
            "fruits" -> Icons.Default.WaterDrop
            "campfire" -> Icons.Default.Fireplace
            "shoes" -> Icons.Default.DirectionsRun
            "party" -> Icons.Default.Celebration
            "birthday" -> Icons.Default.Cake
            "vacation" -> Icons.Default.BeachAccess
            "beauty" -> Icons.Default.Spa
            "cab" -> Icons.Default.LocalTaxi
            "others" -> Icons.Default.Category
            else -> Icons.Default.Category
        }
    }

    fun resolve(name: String, customList: List<CustomCategory>): DisplayCategory {
        if (name.equals("INCOME", ignoreCase = true)) {
            return legacyIncomeCategory()
        }

        val custom = customList.firstOrNull { it.name.equals(name, ignoreCase = true) && !it.iconName.startsWith("hidden:") && it.iconName != "hidden" }
        if (custom != null) {
            val parts = custom.iconName.split(":")
            val actualIconName = parts.getOrNull(0) ?: "category"
            val customType = parts.getOrNull(1) ?: "EXPENSE"
            
            val icon = getIconFromIconName(actualIconName)
            val parsedColor = try {
                Color(android.graphics.Color.parseColor(custom.colorHex))
            } catch (e: Exception) {
                Color(0xFF6750A4)
            }
            // If this custom entry overrides a standard category, keep the standard's display name
            val standardMatch = ExpenseCategory.entries.firstOrNull { it.name.equals(custom.name, ignoreCase = true) }
            return DisplayCategory(
                name = custom.name,
                displayName = standardMatch?.displayName ?: custom.name,
                icon = icon,
                color = parsedColor,
                isCustom = true,
                customId = custom.id,
                type = customType
            )
        }

        val standard = ExpenseCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (standard != null) {
            return DisplayCategory(
                name = standard.name,
                displayName = standard.displayName,
                icon = standard.icon,
                color = standard.color,
                isCustom = false,
                type = standard.type
            )
        }
        
        return DisplayCategory(
            name = name,
            displayName = name,
            icon = Icons.Default.Category,
            color = Color(0xFF607D8B),
            isCustom = false,
            type = "EXPENSE"
        )
    }

    fun getAll(customList: List<CustomCategory>): List<DisplayCategory> {
        val list = mutableListOf<DisplayCategory>()
        val hiddenNames = customList.filter { it.iconName.startsWith("hidden:") || it.iconName == "hidden" }.map { it.name.lowercase() }
        // OUTSIDE_FOOD kept in enum for DB backward-compat but excluded from the picker.
        // POCKET_MONEY_INC (received pocket money) is income-only, hidden to avoid confusion.
        val builtinHidden = setOf("outside_food", "pocket_money_inc")

        ExpenseCategory.entries.forEach { standard ->
            if (!hiddenNames.contains(standard.name.lowercase()) && !builtinHidden.contains(standard.name.lowercase())) {
                list.add(
                    DisplayCategory(
                        name = standard.name,
                        displayName = standard.displayName,
                        icon = standard.icon,
                        color = standard.color,
                        isCustom = false,
                        type = standard.type
                    )
                )
            }
        }

        customList.forEach { custom ->
            if (!custom.iconName.startsWith("hidden:") && custom.iconName != "hidden") {
                val parts = custom.iconName.split(":")
                val actualIconName = parts.getOrNull(0) ?: "category"
                val customType = parts.getOrNull(1) ?: "EXPENSE"

                val icon = getIconFromIconName(actualIconName)
                val parsedColor = try {
                    Color(android.graphics.Color.parseColor(custom.colorHex))
                } catch (e: Exception) {
                    Color(0xFF6750A4)
                }
                
                // Remove override first
                list.removeAll { it.name.equals(custom.name, ignoreCase = true) }
                
                // If this custom entry overrides a standard category, keep the standard's display name
                val standardMatch = ExpenseCategory.entries.firstOrNull { it.name.equals(custom.name, ignoreCase = true) }
                list.add(
                    DisplayCategory(
                        name = custom.name,
                        displayName = standardMatch?.displayName ?: custom.name,
                        icon = icon,
                        color = parsedColor,
                        isCustom = true,
                        customId = custom.id,
                        type = customType
                    )
                )
            }
        }
        return list
    }
}

