package com.example

import com.example.utils.inferSmsBankCode
import com.example.utils.smsBankMatchesAccount
import com.example.utils.smsDisplayBankName
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [inferSmsBankCode], [smsDisplayBankName], and [smsBankMatchesAccount].
 *
 * Core invariant under test:
 *   Bank identification is derived SOLELY from the DLT sender header.
 *   The SMS body is NEVER used to determine the bank, because transfer/UPI SMS messages
 *   often mention the PAYEE bank (e.g. "transfer to My HDFC Ac") which must NOT be
 *   mistaken for the sender's bank.
 *
 * Sender format: "XX-BANKCODE-S" or "XX-BANKCODE-T"
 */
class SmsAccountUtilsTest {

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun infer(sender: String?, body: String = "", ref: String? = null) =
        inferSmsBankCode(sender, body, ref)

    // ─── 1. Exact sender-code match ───────────────────────────────────────────

    @Test fun `SBI exact match`() = assertEquals("SBI", infer("VM-SBI-S"))
    @Test fun `SBI card exact match`() = assertEquals("SBI", infer("AX-SBICARD-S"))
    @Test fun `HDFC exact match`() = assertEquals("HDFC", infer("HD-HDFC-T"))
    @Test fun `HDFCBK exact match`() = assertEquals("HDFC", infer("AX-HDFCBK-S"))
    @Test fun `ICICI exact match`() = assertEquals("ICICI", infer("JD-ICICI-S"))
    @Test fun `ICICIB exact match`() = assertEquals("ICICI", infer("VM-ICICIB-T"))
    @Test fun `Axis exact match`() = assertEquals("AXIS", infer("AX-AXIS-T"))
    @Test fun `AXIBK exact match`() = assertEquals("AXIS", infer("JD-AXIBK-S"))
    @Test fun `Kotak exact match`() = assertEquals("KOTAK", infer("BK-KOTAK-S"))
    @Test fun `KOTAKB exact match`() = assertEquals("KOTAK", infer("HD-KOTAKB-T"))
    @Test fun `IndusInd INDUSIND exact match`() = assertEquals("INDUS", infer("JD-INDUSIND-S"))
    @Test fun `IndusInd INDUSB exact match`() = assertEquals("INDUS", infer("AX-INDUSB-S"))
    @Test fun `Indian Bank IDIB exact match`() = assertEquals("IND", infer("JD-IDIB-S"))
    @Test fun `Indian Bank INDBKS exact match`() = assertEquals("IND", infer("VM-INDBKS-T"))
    @Test fun `IOB IOBBK exact match`() = assertEquals("IOB", infer("AX-IOBBK-S"))
    @Test fun `Bank of India BOIMNB exact match`() = assertEquals("BOI", infer("JD-BOIMNB-S"))
    @Test fun `Bank of Baroda BOBIMU exact match`() = assertEquals("BOB", infer("AX-BOBIMU-S"))
    @Test fun `Canara CNRBNK exact match`() = assertEquals("CANARA", infer("HD-CNRBNK-T"))
    @Test fun `PNB exact match`() = assertEquals("PNB", infer("VM-PNB-S"))
    @Test fun `Yes Bank YESBNK exact match`() = assertEquals("YES", infer("BK-YESBNK-S"))
    @Test fun `IDFC IDFCFB exact match`() = assertEquals("IDFC", infer("VM-IDFCFB-S"))
    @Test fun `RBL RBLBNK exact match`() = assertEquals("RBL", infer("JD-RBLBNK-S"))
    @Test fun `Federal FEDBNK exact match`() = assertEquals("FED", infer("AX-FEDBNK-S"))
    @Test fun `Union Bank UNIONBK exact match`() = assertEquals("UNION", infer("HD-UNIONBK-T"))

    // ─── 2. Prefix sender-code match (DLT senders append suffixes) ────────────

    @Test fun `IDFCFirstBK prefix matches IDFC`() {
        // Sender: Az-IDFCFirstBK-S → segment = "IDFCFIRSTBK" → starts with "IDFC"
        assertEquals("IDFC", infer("Az-IDFCFirstBK-S"))
    }

    @Test fun `IDFCFirstBank prefix matches IDFC`() {
        assertEquals("IDFC", infer("VM-IDFCFirstBank-S"))
    }

    @Test fun `HdfcBank prefix matches HDFC`() {
        assertEquals("HDFC", infer("JD-HdfcBank-S"))
    }

    @Test fun `SBIBank prefix matches SBI`() {
        assertEquals("SBI", infer("VM-SBIBank-T"))
    }

    @Test fun `KOTAKS prefix matches KOTAK`() {
        assertEquals("KOTAK", infer("BK-KOTAKS-S"))
    }

    @Test fun `AXISBANK prefix matches AXIS`() {
        assertEquals("AXIS", infer("AX-AXISBANK-S"))
    }

    @Test fun `CANBNK prefix matches CANARA`() {
        assertEquals("CANARA", infer("HD-CANBNK-T"))
    }

    // ─── 3. THE KEY BUG SCENARIO — payee bank in body must NOT override sender ─

    @Test fun `IDFC sender with HDFC mentioned as payee in body`() {
        // This is the exact reported bug: HDFC is the payee, not the sender bank
        val sender = "Az-IDFCFirstBK-S"
        val body = "Your Ac x5678 debited Rs.30,000.00 for transfer to My HDFC Ac x1234 " +
            "dt 08.07.26 Ref 536019608407. If not done by you, call 1800111109. Your IDFCBANK."
        assertEquals("IDFC", infer(sender, body))
    }

    @Test fun `SBI sender with ICICI as payee should return SBI`() {
        val sender = "VM-SBI-S"
        val body = "INR 5000.00 debited from your SBI A/c X1234 to ICICI Ac X5678 via NEFT."
        assertEquals("SBI", infer(sender, body))
    }

    @Test fun `HDFC sender with SBI as payee should return HDFC`() {
        val sender = "HD-HDFCBK-T"
        val body = "Rs.10,000 debited from HDFC Ac XX4321 to SBI Account XX9876 on 07-Jul-26."
        assertEquals("HDFC", infer(sender, body))
    }

    @Test fun `Axis sender with Kotak as payee should return AXIS`() {
        val sender = "AX-AXIS-T"
        val body = "INR 2500.00 debited from Axis Bank A/c XX3456. Transfer to Kotak A/c successful."
        assertEquals("AXIS", infer(sender, body))
    }

    @Test fun `IDFC sender with multiple bank names in body`() {
        val sender = "VM-IDFCFB-S"
        val body = "Rs.500 debited from your IDFC First A/c. Transferred to HDFC Bank, SBI, or ICICI accounts."
        assertEquals("IDFC", infer(sender, body))
    }

    @Test fun `Canara sender with HDFC and SBI mentioned in body`() {
        val sender = "HD-CNRBNK-T"
        val body = "Dear Customer, your Canara A/c XX2345 debited Rs.15000. Transferred to HDFC Bank XX1234."
        assertEquals("CANARA", infer(sender, body))
    }

    // ─── 4. Generic/unrecognised sender — should fall back to "Bank", NOT body ─

    @Test fun `generic sender BNKMSG falls back to Bank even if body has HDFC`() {
        val sender = "VM-BNKMSG-S"
        val body = "Your HDFC Bank account was debited Rs.1000."
        assertEquals("Bank", infer(sender, body))
    }

    @Test fun `generic sender TXNALRT falls back to Bank even if body has SBI`() {
        val sender = "JD-TXNALRT-S"
        val body = "SBI UPI debit of Rs.500 from A/c XX1234."
        assertEquals("Bank", infer(sender, body))
    }

    @Test fun `null sender falls back to Bank`() {
        assertEquals("Bank", infer(null, "HDFC debited Rs.1000 from your account."))
    }

    @Test fun `empty sender falls back to Bank`() {
        assertEquals("Bank", infer("", "HDFC UPI debit Rs.500."))
    }

    @Test fun `sender with only routing prefix AX falls back to Bank`() {
        // Segments: ["AX", "S"] — neither is in the map or long enough for prefix check
        assertEquals("Bank", infer("AX-S", "Some random body mentioning ICICI."))
    }

    // ─── 5. accountRef prefix fallback ────────────────────────────────────────

    @Test fun `accountRef HDFC prefix used when sender is generic`() {
        val result = infer("VM-GENERIC-S", "Some body", ref = "HDFC-1234")
        assertEquals("HDFC", result)
    }

    @Test fun `accountRef SBI prefix used when sender is generic`() {
        val result = infer("JD-UNRECOGNISED-S", "Some body", ref = "SBI-5678")
        assertEquals("SBI", result)
    }

    @Test fun `accountRef digits-only is ignored`() {
        // Pure-digit accountRef should not be treated as a bank code
        val result = infer("VM-BNKMSG-S", "Some body", ref = "1234-5678")
        assertEquals("Bank", result)
    }

    @Test fun `accountRef BANK is ignored`() {
        val result = infer("VM-BNKMSG-S", "Some body", ref = "BANK-1234")
        assertEquals("Bank", result)
    }

    // ─── 6. Sender takes priority even when NO body matches exist ─────────────

    @Test fun `Union Bank sender with empty body`() {
        assertEquals("UNION", infer("HD-UNIONBK-T", ""))
    }

    @Test fun `IOB sender with completely irrelevant body`() {
        assertEquals("IOB", infer("AX-IOBBK-S", "Your OTP is 123456. Do not share."))
    }

    // ─── 7. Case insensitivity of sender ──────────────────────────────────────

    @Test fun `lowercase sender still matches`() {
        assertEquals("HDFC", infer("ax-hdfcbk-s"))
    }

    @Test fun `mixed case IDFCFirstBK still matches via prefix`() {
        assertEquals("IDFC", infer("az-idfcfirstbk-s"))
    }

    @Test fun `mixed case SBICard matches`() {
        assertEquals("SBI", infer("Ax-SBICard-S"))
    }

    // ─── 8. smsDisplayBankName ────────────────────────────────────────────────

    @Test fun `display IDFC returns IDFC First`() = assertEquals("IDFC First", smsDisplayBankName("IDFC"))
    @Test fun `display IOB returns IOB`() = assertEquals("IOB", smsDisplayBankName("IOB"))
    @Test fun `display BOI returns Bank of India`() = assertEquals("Bank of India", smsDisplayBankName("BOI"))
    @Test fun `display BOB returns Bank of Baroda`() = assertEquals("Bank of Baroda", smsDisplayBankName("BOB"))
    @Test fun `display CANARA returns Canara Bank`() = assertEquals("Canara Bank", smsDisplayBankName("CANARA"))
    @Test fun `display UNION returns Union Bank`() = assertEquals("Union Bank", smsDisplayBankName("UNION"))
    @Test fun `display IND returns Indian Bank`() = assertEquals("Indian Bank", smsDisplayBankName("IND"))
    @Test fun `display SBI returns SBI`() = assertEquals("SBI", smsDisplayBankName("SBI"))
    @Test fun `display HDFC returns HDFC`() = assertEquals("HDFC", smsDisplayBankName("HDFC"))
    @Test fun `display unknown code returned as-is`() = assertEquals("UNKNOWN", smsDisplayBankName("UNKNOWN"))
    @Test fun `display Bank returns Bank`() = assertEquals("Bank", smsDisplayBankName("BANK"))
    @Test fun `display is case-insensitive`() = assertEquals("IDFC First", smsDisplayBankName("idfc"))

    // ─── 9. smsBankMatchesAccount ────────────────────────────────────────────

    @Test fun `IDFC code matches IDFC First account name`() =
        assertTrue(smsBankMatchesAccount("IDFC", "IDFC First Bank ·5678"))
    @Test fun `HDFC code matches HDFC account`() =
        assertTrue(smsBankMatchesAccount("HDFC", "HDFC Bank ·1234"))
    @Test fun `IOB code matches IOB account`() =
        assertTrue(smsBankMatchesAccount("IOB", "IOB Bank ·5678"))
    @Test fun `CANARA code matches Canara Bank account`() =
        assertTrue(smsBankMatchesAccount("CANARA", "Canara Bank ·3456"))
    @Test fun `INDUS code matches IndusInd account`() =
        assertTrue(smsBankMatchesAccount("INDUS", "IndusInd Bank ·7890"))
    @Test fun `HDFC code does NOT match SBI account`() =
        assertFalse(smsBankMatchesAccount("HDFC", "SBI Bank ·1234"))
    @Test fun `IDFC code does NOT match HDFC account`() =
        assertFalse(smsBankMatchesAccount("IDFC", "HDFC Bank ·1234"))
    @Test fun `generic BANK code matches anything`() =
        assertTrue(smsBankMatchesAccount("BANK", "Any Bank ·1234"))
}
