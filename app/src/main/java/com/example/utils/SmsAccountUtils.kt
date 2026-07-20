package com.example.utils

import com.example.data.Account

private val SBI_REGEX = Regex("\\bsbi\\b", RegexOption.IGNORE_CASE)

// Maps DLT sender code fragments → internal short code (longest-match first).
// Format in sender: "XX-BANKCODE-S" → we strip non-alpha chars and look up BANKCODE.
private val SENDER_CODE_MAP: List<Pair<String, String>> = listOf(
    // SBI family
    "SBICARD" to "SBI", "SBICRD"  to "SBI", "SBIPSG" to "SBI",
    "SBIMBI"  to "SBI", "SBIBNK"  to "SBI", "SBI"    to "SBI",
    // HDFC
    "HDFCBK"  to "HDFC", "HDFCSM"  to "HDFC", "HDFC"   to "HDFC",
    // ICICI
    "ICICIBK" to "ICICI", "ICICIB"  to "ICICI", "ICICI"  to "ICICI",
    // Axis
    "AXISBN"  to "AXIS",  "AXIBK"   to "AXIS",  "AXIS"   to "AXIS",
    // Kotak
    "KOTAKB"  to "KOTAK", "KOTAKS"  to "KOTAK", "KOTAK"  to "KOTAK",
    // IndusInd
    "INDUSIND" to "INDUS", "INDUSB"  to "INDUS", "INDUSL" to "INDUS",
    "INDUSIN"  to "INDUS",
    // Indian Bank (IDIB / legacy IND)
    "INDIANBANK" to "IND", "INDIANB" to "IND", "INDBKS" to "IND",
    "INDBNK"   to "IND",  "IDIBK"   to "IND",  "IDIB"   to "IND",
    "INDBK"    to "IND",  // common real-world DLT fragment, e.g. "JD-INDBK-S"
    "ALBNK"    to "IND",  // Allahabad Bank (merged into Indian Bank 2020)
    // IOB — Indian Overseas Bank
    "IOBBK"    to "IOB",  "IOBBNK"  to "IOB",  "IOB"    to "IOB",
    // Bank of India
    "BOIMNB"   to "BOI",  "BOIBNK"  to "BOI",  "BOI"    to "BOI",
    // Bank of Baroda (including merged Vijaya & Dena banks)
    "BOBIMU"   to "BOB",  "BOBSMS"  to "BOB",  "BOBBNK" to "BOB",
    "VIJBNK"   to "BOB",  "DENABN"  to "BOB",  "BOB"    to "BOB",
    // Canara Bank (includes merged Syndicate Bank)
    "CNRBNK"   to "CANARA", "CANBNK" to "CANARA", "CANARA" to "CANARA",
    "SYNBNK"   to "CANARA", "CAN"    to "CANARA",
    // UCO Bank
    "UCOBNK"   to "UCO",  "UCO"     to "UCO",
    // Union Bank (includes merged Andhra, Corporation)
    "UNIONBK"  to "UNION", "UNIONB" to "UNION", "UNTBNK" to "UNION",
    "UNIONBI"  to "UNION", "ANDBNK" to "UNION", "CORBNK" to "UNION",
    "UBI"      to "UNION",
    // PNB — Punjab National Bank (includes merged OBC, United Bank)
    "PNBSMS"   to "PNB",  "PNBBNK"  to "PNB",  "PNB"    to "PNB",
    // Yes Bank
    "YESBNK"   to "YES",  "YESBK"   to "YES",   "YES"    to "YES",
    // IDFC First Bank
    "IDFCFB"   to "IDFC", "IDFCFIR" to "IDFC",  "IDFC"   to "IDFC",
    // RBL Bank
    "RBLBNK"   to "RBL",  "RBLSMK"  to "RBL",   "RBL"    to "RBL",
    // Federal Bank
    "FEDBNK"   to "FED",  "FEDBK"   to "FED",   "FED"    to "FED",
    // Standard Chartered
    "SCBNKI"   to "SC",   "STANCH"  to "SC",    "SCBNK"  to "SC",
    // Citibank
    "CITIBNK"  to "CITI", "CITYBN"  to "CITI",  "CITI"   to "CITI",
    // Central Bank of India
    "CENTBK"   to "CENTRAL", "CBI"  to "CENTRAL",
    // South Indian Bank
    "SIBBNK"   to "SIB",  "SIB"     to "SIB",
    // DCB Bank
    "DCBBNK"   to "DCB",  "DCB"     to "DCB",
    // Punjab & Sind Bank
    "PSBNK"    to "PSB",  "PSB"     to "PSB",
    // Maharashtra Bank
    "MAHABK"   to "MHB",  "MHB"     to "MHB",
    // J&K Bank
    "JKBANK"   to "JKB",  "JKB"     to "JKB",
    // Saraswat Bank
    "SARSWT"   to "SARSWT",
    // Tamil Nadu Mercantile Bank
    "TNMBNK"   to "TNM",
    // Bandhan Bank
    "BANDHN"   to "BANDHAN", "BANDHANB" to "BANDHAN",
    // Karnataka Bank
    "KARBNK"   to "KBL",  "KBL"     to "KBL",
    // Nainital Bank
    "NAINITAL" to "NAINITAL",
    // Jammu & Kashmir Bank
    "JKBSNK"   to "JKB",
)

/** Infers bank short-code from the DLT sender header ONLY.
 *
 *  The SMS body is intentionally NOT used as a source for bank-code inference:
 *  transfer/payment SMS messages frequently mention the PAYEE bank (e.g. "transfer to My HDFC Ac")
 *  which would falsely identify the recipient's bank as the sender's bank.
 *
 *  Lookup order:
 *    1. Exact match of a sender segment against SENDER_CODE_MAP
 *    2. Prefix match  — sender segment starts with a known key (handles variants like "IDFCFirstBK")
 *    3. accountRef prefix  — only when the ref itself encodes the bank (e.g. "HDFC-1234")
 *    4. Fallback → "Bank"
 */
fun inferSmsBankCode(senderHeader: String?, smsBody: String, accountRef: String? = null): String {
    val refBank = accountRef?.substringBefore('-')?.trim()?.uppercase().orEmpty()
    val cleanSender = (senderHeader ?: "").uppercase()

    val senderSegments = cleanSender.split(Regex("[^A-Za-z]+")).filter { it.isNotBlank() }

    // 1. Exact match
    for (segment in senderSegments) {
        val match = SENDER_CODE_MAP
            .filter { (key, _) -> segment == key }
            .maxByOrNull { (key, _) -> key.length }
        if (match != null) return match.second
    }

    // 2. Prefix match — sender segment *starts with* a known key (min key length 3 to avoid noise)
    //    e.g. "IDFCFIRSTBK" starts with "IDFC" → IDFC First Bank
    //         "HDFCBANK"    starts with "HDFC" → HDFC
    for (segment in senderSegments) {
        if (segment.length < 4) continue
        val prefixMatch = SENDER_CODE_MAP
            .filter { (key, _) -> key.length >= 3 && segment.startsWith(key) }
            .maxByOrNull { (key, _) -> key.length }
        if (prefixMatch != null) return prefixMatch.second
    }

    // 3. accountRef prefix  (e.g. accountRef = "HDFC-1234" when passed from ensureAccountExists)
    if (refBank.isNotBlank() && refBank != "BANK" && !refBank.all { it.isDigit() }) {
        val refMatch = SENDER_CODE_MAP.firstOrNull { (key, _) -> refBank == key }
        if (refMatch != null) return refMatch.second
        if (refBank.length >= 2) return refBank
    }

    // Body scan intentionally removed — see function doc above.
    return "Bank"
}

/** Maps known sender code fragments to proper account-name display strings. */
fun smsDisplayBankName(bankCode: String): String {
    return when (bankCode.uppercase()) {
        "SBI"      -> "SBI"
        "HDFC"     -> "HDFC"
        "ICICI"    -> "ICICI"
        "AXIS"     -> "Axis"
        "KOTAK"    -> "Kotak"
        "INDUS"    -> "IndusInd"
        "IND"      -> "Indian Bank"
        "IOB"      -> "IOB"
        "BOI"      -> "Bank of India"
        "BOB"      -> "Bank of Baroda"
        "CANARA"   -> "Canara Bank"
        "UCO"      -> "UCO Bank"
        "UNION"    -> "Union Bank"
        "PNB"      -> "PNB"
        "YES"      -> "Yes Bank"
        "IDFC"     -> "IDFC First"
        "RBL"      -> "RBL Bank"
        "FED"      -> "Federal Bank"
        "SC"       -> "Standard Chartered"
        "CITI"     -> "Citi"
        "CENTRAL"  -> "Central Bank"
        "SIB"      -> "South Indian Bank"
        "DCB"      -> "DCB Bank"
        "PSB"      -> "Punjab & Sind Bank"
        "MHB"      -> "Maharashtra Bank"
        "JKB"      -> "J&K Bank"
        "SARSWT"   -> "Saraswat Bank"
        "TNM"      -> "TMB"
        "BANDHAN"  -> "Bandhan Bank"
        "KBL"      -> "Karnataka Bank"
        "NAINITAL" -> "Nainital Bank"
        "BANK"     -> "Bank"
        else       -> bankCode   // preserve as-is (e.g. custom short codes)
    }
}

fun smsBankMatchesAccount(bankCode: String, accountName: String): Boolean {
    val n = accountName.uppercase()
    return when (bankCode.uppercase()) {
        "SBI"     -> n.contains("SBI") || n.contains("STATE BANK")
        "HDFC"    -> n.contains("HDFC")
        "ICICI"   -> n.contains("ICICI")
        "AXIS"    -> n.contains("AXIS")
        "KOTAK"   -> n.contains("KOTAK")
        "INDUS"   -> n.contains("INDUSIND") || n.contains("INDUS")
        "IND"     -> n.contains("INDIAN BANK") || n.contains("IND ")
        "IOB"     -> n.contains("IOB") || n.contains("INDIAN OVERSEAS")
        "BOI"     -> n.contains("BANK OF INDIA") || n.contains(" BOI ")
        "BOB"     -> n.contains("BANK OF BARODA") || n.contains(" BOB ")
        "CANARA"  -> n.contains("CANARA") || n.contains("SYNDICATE")
        "UCO"     -> n.contains("UCO")
        "UNION"   -> n.contains("UNION BANK") || n.contains("ANDHRA BANK")
        "PNB"     -> n.contains("PNB") || n.contains("PUNJAB NATIONAL")
        "YES"     -> n.contains("YES BANK") || n.startsWith("YES ")
        "IDFC"    -> n.contains("IDFC")
        "RBL"     -> n.contains("RBL")
        "FED"     -> n.contains("FEDERAL")
        "SC"      -> n.contains("STANDARD CHARTERED")
        "CITI"    -> n.contains("CITI")
        "CENTRAL" -> n.contains("CENTRAL BANK")
        "SIB"     -> n.contains("SOUTH INDIAN")
        "DCB"     -> n.contains("DCB")
        "BANK", ""-> true
        else      -> n.contains(bankCode.uppercase())
    }
}

/** Classifies which kind of "card" wording (if any) an SMS uses.
 *
 *  Real-world Indian bank/card-issuer SMS are asymmetric about this wording, which is why
 *  a bare "card" mention can't be treated as ambiguous/neutral the way it first seems:
 *   - Credit-card issuers overwhelmingly brand their SMS with just the card name — e.g.
 *     "SBI Card", "HDFC Bank Card ending 1234" — and rarely spell out the word "credit"
 *     at all. So a bare "card" mention (no "debit" qualifier) is, in practice, almost
 *     always a CREDIT card, not an unknown/ambiguous case.
 *   - Genuine debit-card SWIPE alerts, by contrast, are the ones that reliably DO spell
 *     out "debit card" explicitly; a plain bank debit (UPI/NEFT/etc.) never mentions
 *     "card" in the first place.
 *  So: explicit "debit card" is the one case that must be pulled OUT of the default
 *  card→CREDIT assumption (otherwise it gets wrongly tracked as a revolving CREDIT_CARD
 *  with credit-limit fields that make no sense for it) — everything else that mentions
 *  "card" defaults to CREDIT.
 */
enum class SmsCardKind { CREDIT, DEBIT, NONE }

fun inferSmsCardKind(smsBody: String, senderHeader: String?): SmsCardKind {
    val body = smsBody.lowercase()
    return when {
        body.contains("debit card") -> SmsCardKind.DEBIT
        body.contains("credit card") || body.contains("card") ||
            (senderHeader ?: "").uppercase().contains("CARD") -> SmsCardKind.CREDIT
        else -> SmsCardKind.NONE
    }
}

/** Single source of truth for the account `type` + auto-generated display name derived
 *  from a card-kind classification, shared by every SMS-import entry point so the same
 *  SMS wording always produces the same account type/name regardless of which code path
 *  (foreground scan vs background SmsReceiver) handled it — and so accounts are always
 *  matched/updated afterwards by the card/account's last-4 digits, never by name text.
 *
 *  - CREDIT → CREDIT_CARD type (tracks available/total credit limit), named "{Bank} CC".
 *  - DEBIT  → DEBIT_CARD type — a distinct type from CREDIT_CARD since a debit card has
 *             no independent credit line of its own (draws straight from the linked bank
 *             account, no credit-limit tracking), but still its own selectable account
 *             type (not folded into plain BANK) — named "{Bank} Card" (kept short, no
 *             "Debit" wording).
 *  - NONE   → ordinary bank account, unchanged from the existing naming.
 */
fun accountTypeAndLabelFor(kind: SmsCardKind, displayBankName: String, last4: String): Pair<String, String> {
    return when (kind) {
        SmsCardKind.CREDIT -> "CREDIT_CARD" to "$displayBankName CC ·$last4"
        SmsCardKind.DEBIT  -> "DEBIT_CARD" to "$displayBankName Card ·$last4"
        SmsCardKind.NONE   -> "BANK" to if (displayBankName.endsWith("Bank", ignoreCase = true))
            "$displayBankName ·$last4" else "$displayBankName Bank ·$last4"
    }
}


fun isSmsTrackingBlocked(account: Account, blockedAccountIds: Set<String>): Boolean {
    return blockedAccountIds.contains(account.id)
}