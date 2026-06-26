package com.example.utils

import com.example.data.Account

private val SBI_REGEX = Regex("\\bsbi\\b", RegexOption.IGNORE_CASE)

fun inferSmsBankCode(senderHeader: String?, smsBody: String, accountRef: String? = null): String {
    val refBank = accountRef?.substringBefore('-')?.trim()?.uppercase().orEmpty()
    val cleanSender = senderHeader?.uppercase().orEmpty()
    val lowerBody = smsBody.lowercase()

    val senderBank = when {
        cleanSender.contains("IDIB") || cleanSender.contains("INDIANBANK") || cleanSender.contains("INDIANBANK") || cleanSender.contains("IND") -> "IND"
        cleanSender.contains("SBI") -> "SBI"
        cleanSender.contains("HDFC") -> "HDFC"
        cleanSender.contains("ICICI") -> "ICICI"
        cleanSender.contains("AXIS") -> "AXIS"
        cleanSender.contains("PNB") -> "PNB"
        else -> ""
    }
    if (senderBank.isNotBlank()) {
        return senderBank
    }

    if (refBank.isNotBlank() && refBank != "BANK" && !refBank.all { it.isDigit() }) {
        return refBank
    }

    return when {
        lowerBody.contains("indian bank") || lowerBody.contains("indianbank") || lowerBody.contains("indusind") -> "IND"
        lowerBody.contains("state bank") || SBI_REGEX.containsMatchIn(lowerBody) -> "SBI"
        lowerBody.contains("hdfc") -> "HDFC"
        lowerBody.contains("icici") -> "ICICI"
        lowerBody.contains("axis") -> "AXIS"
        lowerBody.contains("pnb") || lowerBody.contains("punjab national") -> "PNB"
        lowerBody.contains("indusind") -> "IND"
        else -> {
            val parts = cleanSender.split("-")
            when {
                parts.size > 1 && parts[1].length >= 3 -> parts[1].take(4)
                cleanSender.length >= 2 -> cleanSender.filter { it.isLetter() }.take(4)
                else -> "Bank"
            }
        }
    }.ifBlank { "Bank" }
}

fun smsBankMatchesAccount(bankCode: String, accountName: String): Boolean {
    val accNameUpper = accountName.uppercase()
    return when (bankCode.uppercase()) {
        "SBI" -> accNameUpper.contains("SBI") || accNameUpper.contains("STATE BANK")
        "IND" -> accNameUpper.contains("INDIAN BANK") || accNameUpper.split("\\s+".toRegex()).any { it.startsWith("IND") }
        "HDFC" -> accNameUpper.contains("HDFC")
        "ICICI" -> accNameUpper.contains("ICICI")
        "AXIS" -> accNameUpper.contains("AXIS")
        "PNB" -> accNameUpper.contains("PNB") || accNameUpper.contains("PUNJAB")
        "BANK", "" -> true
        else -> accNameUpper.contains(bankCode.uppercase())
    }
}

fun smsDisplayBankName(bankCode: String): String {
    return when (bankCode.uppercase()) {
        "IND" -> "Indian Bank"
        "SBI" -> "SBI"
        "HDFC" -> "HDFC"
        "ICICI" -> "ICICI"
        "AXIS" -> "AXIS"
        "PNB" -> "PNB"
        else -> bankCode
    }
}

fun isSmsTrackingBlocked(account: Account, blockedAccountIds: Set<String>): Boolean {
    return blockedAccountIds.contains(account.id)
}