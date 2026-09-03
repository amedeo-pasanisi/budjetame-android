package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure form logic (ticket #20), ported from the web app's
 * transactionFields.tsx + balanceProjection.ts: which Wallets a type may
 * use, the amount gate, the Cash-negative balance projection, and the option
 * labels. These are the cheap spots where porting bugs hide (spec #13
 * testing decisions).
 */
class TransactionFormModelTest {

    private fun wallet(
        id: Int,
        type: WalletType = WalletType.CASH,
        balance: String = "0.00",
        frozen: Boolean = false,
    ) = WalletDto(id, "Wallet $id", type, balance, frozen, "2026-08-01T10:00:00Z")

    private fun category(id: Int, type: CategoryType) =
        CategoryDto(id, "Category $id", type, "🍕", "#000000", "2026-08-01T10:00:00Z")

    private fun recurringCost(id: Int, nextUnpaid: String) = RecurringCostDto(
        id = id,
        name = "Cost $id",
        amount = "10.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        // Every definition always carries a start date (ADR-0024).
        start_date = "2026-08-01",
        next_due_date = nextUnpaid,
        next_unpaid_occurrence_date = nextUnpaid,
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun recurringIncome(id: Int, nextUnpaid: String) = RecurringIncomeDto(
        id = id,
        name = "Income $id",
        amount = "10.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        // Every definition always carries a start date (ADR-0024).
        start_date = "2026-08-01",
        next_due_date = nextUnpaid,
        next_unpaid_occurrence_date = nextUnpaid,
        created_at = "2026-08-01T10:00:00Z",
    )

    // --- Wallet eligibility ---

    @Test
    fun `frozen wallets are never assignable`() {
        val wallets = listOf(
            wallet(1),
            wallet(2, frozen = true),
        )
        assertEquals(listOf(1), activeWallets(wallets).map { it.id })
    }

    @Test
    fun `spendable wallets exclude contact and frozen wallets`() {
        val wallets = listOf(
            wallet(1, WalletType.CASH),
            wallet(2, WalletType.CONTACT),
            wallet(3, WalletType.CHECKING),
            wallet(4, WalletType.CREDIT_CARD, frozen = true),
        )
        assertEquals(
            listOf(1, 3),
            spendableWallets(wallets).map { it.id },
        )
    }

    @Test
    fun `an income's wallet sentinel never creates a contact wallet`() {
        // An Income's Wallet field is restricted to Checking/Credit Card/Cash
        // — money coming in from a Contact is a Transfer (ADR-0017).
        val income = walletCreateAllowedTypes(TransactionType.INCOME, WalletFieldTarget.WALLET)
        assertEquals(NON_CONTACT_WALLET_TYPES, income)
        assertFalse(WalletType.CONTACT in income!!)

        // Everywhere else nothing is locked (null, like the web's
        // `allowedTypes === undefined`): an Expense's Wallet field may create
        // a Contact Wallet — the consumption the contact paid for — as may a
        // Transfer's From/To, where Contact Wallets belong.
        assertNull(walletCreateAllowedTypes(TransactionType.EXPENSE, WalletFieldTarget.WALLET))
        assertNull(walletCreateAllowedTypes(TransactionType.EXPENSE, WalletFieldTarget.SOURCE))
        assertNull(walletCreateAllowedTypes(TransactionType.TRANSFER, WalletFieldTarget.SOURCE))
        assertNull(walletCreateAllowedTypes(TransactionType.TRANSFER, WalletFieldTarget.DESTINATION))
    }

    @Test
    fun `categories match the transaction type and never a transfer`() {
        val categories = listOf(
            category(1, CategoryType.EXPENSE),
            category(2, CategoryType.INCOME),
        )
        assertEquals(listOf(1), matchingCategories(categories, TransactionType.EXPENSE).map { it.id })
        assertEquals(listOf(2), matchingCategories(categories, TransactionType.INCOME).map { it.id })
        assertTrue(matchingCategories(categories, TransactionType.TRANSFER).isEmpty())
    }

    // --- Amount gate ---

    @Test
    fun `the amount gate accepts only strictly positive numbers`() {
        assertEquals(BigDecimal("5.00"), parseAmount(" 5.00 "))
        assertEquals(BigDecimal("0.01"), parseAmount("0.01"))
        assertNull(parseAmount(""))
        assertNull(parseAmount("   "))
        assertNull(parseAmount("0"))
        assertNull(parseAmount("-1.00"))
        assertNull(parseAmount("abc"))
    }

    // --- Projection ---

    @Test
    fun `an expense projection subtracts and an income projection adds`() {
        val expense = projectBalance(
            currentBalance = "100.00",
            type = TransactionType.EXPENSE,
            newAmount = BigDecimal("30.00"),
            editedAmount = null,
        )
        assertEquals("100.00", expense.before.toPlainString())
        assertEquals("70.00", expense.after.toPlainString())

        val income = projectBalance(
            currentBalance = "100.00",
            type = TransactionType.INCOME,
            newAmount = BigDecimal("30.00"),
            editedAmount = null,
        )
        assertEquals("100.00", income.before.toPlainString())
        assertEquals("130.00", income.after.toPlainString())
    }

    @Test
    fun `an edit removes the old amount before applying the new one`() {
        val edited = projectBalance(
            currentBalance = "100.00",
            type = TransactionType.EXPENSE,
            newAmount = BigDecimal("25.00"),
            editedAmount = BigDecimal("30.00"),
        )
        // The current balance already includes the old −30: before is 130.
        assertEquals("130.00", edited.before.toPlainString())
        assertEquals("105.00", edited.after.toPlainString())
    }

    @Test
    fun `a transfer projection restores each leg before moving the new amount`() {
        val create = projectTransfer(
            sourceBalance = "100.00",
            destinationBalance = "5.00",
            newAmount = BigDecimal("40.00"),
            editedAmount = null,
        )
        assertEquals("100.00", create.source.before.toPlainString())
        assertEquals("60.00", create.source.after.toPlainString())
        assertEquals("5.00", create.destination.before.toPlainString())
        assertEquals("45.00", create.destination.after.toPlainString())

        val edit = projectTransfer(
            sourceBalance = "60.00",
            destinationBalance = "45.00",
            newAmount = BigDecimal("30.00"),
            editedAmount = BigDecimal("40.00"),
        )
        assertEquals("100.00", edit.source.before.toPlainString())
        assertEquals("70.00", edit.source.after.toPlainString())
        assertEquals("5.00", edit.destination.before.toPlainString())
        assertEquals("35.00", edit.destination.after.toPlainString())
    }

    @Test
    fun `only a cash wallet going negative warns`() {
        val cash = wallet(1, WalletType.CASH)
        val checking = wallet(2, WalletType.CHECKING)
        assertTrue(isCashNegativeWarning(cash, BigDecimal("-0.01")))
        assertFalse(isCashNegativeWarning(cash, BigDecimal("0.00")))
        assertFalse(isCashNegativeWarning(checking, BigDecimal("-0.01")))
        assertFalse(isCashNegativeWarning(null, BigDecimal("-0.01")))
    }

    @Test
    fun `the wallet option label carries the balance`() {
        assertEquals("Wallet 1 (€100.00)", walletOptionLabel(wallet(1, WalletType.CASH, "100.00")))
    }

    // --- The Recurring Cost link caption (web issue #57) ---

    @Test
    fun `no picked link means no caption`() {
        assertNull(payingOccurrenceDate(null, null, null, listOf(recurringCost(1, "2026-08-01"))))
    }

    @Test
    fun `a new pick names the picked cost's oldest unpaid occurrence`() {
        val costs = listOf(
            recurringCost(1, "2026-08-01"),
            recurringCost(2, "2026-07-15"),
        )
        assertEquals("2026-07-15", payingOccurrenceDate(null, null, 2, costs))
        assertEquals("2026-08-01", payingOccurrenceDate(1, "2026-08-01", 1, costs))
    }

    @Test
    fun `editing the very link already on the row keeps the stored pin`() {
        // The pin is stored at link time and never recomputed: the cost's
        // list view may advertise a different oldest Unpaid by now, and the
        // caption must still name the Occurrence the row actually pays.
        assertEquals(
            "2026-06-01",
            payingOccurrenceDate(
                storedLinkId = 1,
                storedPin = "2026-06-01",
                pickedId = 1,
                costs = listOf(recurringCost(1, "2026-08-01")),
            ),
        )
    }

    @Test
    fun `a pick missing from the list names no occurrence`() {
        assertNull(payingOccurrenceDate(null, null, 9, listOf(recurringCost(1, "2026-08-01"))))
    }

    // --- The Recurring Income link caption (web issue #61), the mirror ---

    @Test
    fun `no picked income link means no caption`() {
        assertNull(payingIncomeOccurrenceDate(null, null, null, listOf(recurringIncome(1, "2026-08-01"))))
    }

    @Test
    fun `a new income pick names the picked income's oldest unpaid occurrence`() {
        val incomes = listOf(
            recurringIncome(1, "2026-08-01"),
            recurringIncome(2, "2026-07-15"),
        )
        assertEquals("2026-07-15", payingIncomeOccurrenceDate(null, null, 2, incomes))
        assertEquals("2026-08-01", payingIncomeOccurrenceDate(1, "2026-08-01", 1, incomes))
    }

    @Test
    fun `editing the very income link already on the row keeps the stored pin`() {
        // The pin is stored at link time and never recomputed: the income's
        // list view may advertise a different oldest Unpaid by now, and the
        // caption must still name the Occurrence the row actually pays.
        assertEquals(
            "2026-06-01",
            payingIncomeOccurrenceDate(
                storedLinkId = 1,
                storedPin = "2026-06-01",
                pickedId = 1,
                incomes = listOf(recurringIncome(1, "2026-08-01")),
            ),
        )
    }

    @Test
    fun `an income pick missing from the list names no occurrence`() {
        assertNull(payingIncomeOccurrenceDate(null, null, 9, listOf(recurringIncome(1, "2026-08-01"))))
    }
}
