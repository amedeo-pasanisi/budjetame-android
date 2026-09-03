package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure presentation logic for the ledger rows and the filter chrome,
 * ported from the web app's transactions.ts + TransactionsScreen (ticket
 * #19): the description-led row title with the "Uncategorized" label, the
 * signed amount, the frozen-wallet read-only determination, the filter
 * option labels, and the filtered chips line's chips (web issue #92,
 * ticket #35). These are the cheap spots where porting bugs hide (spec
 * #13 testing decisions), so they get direct JVM tests.
 */
class TransactionDisplayTest {

    private fun transaction(
        id: Int,
        type: TransactionType,
        amount: String = "5.00",
        walletId: Int? = null,
        sourceWalletId: Int? = null,
        destinationWalletId: Int? = null,
        categoryId: Int? = null,
        description: String? = null,
        latitude: String? = null,
        longitude: String? = null,
    ) = TransactionDto(
        id = id,
        type = type,
        amount = amount,
        date = "2026-08-01",
        wallet_id = walletId,
        source_wallet_id = sourceWalletId,
        destination_wallet_id = destinationWalletId,
        category_id = categoryId,
        description = description,
        latitude = latitude,
        longitude = longitude,
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun wallet(id: Int, frozen: Boolean = false) =
        WalletDto(id, "Wallet $id", WalletType.CASH, "0.00", frozen, "2026-08-01T10:00:00Z")

    private fun namedWallet(id: Int, name: String, frozen: Boolean = false) =
        WalletDto(id, name, WalletType.CASH, "0.00", frozen, "2026-08-01T10:00:00Z")

    private fun category(id: Int, name: String) =
        CategoryDto(id, name, CategoryType.EXPENSE, "🍕", "#ef4444", "2026-08-01T10:00:00Z")

    private val activeWallet = wallet(1)
    private val frozenWallet = wallet(2, frozen = true)

    private fun recurringCost(id: Int, name: String) = RecurringCostDto(
        id = id,
        name = name,
        amount = "10.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        next_due_date = "2026-09-01",
        next_unpaid_occurrence_date = "2026-08-01",
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun recurringIncome(id: Int, name: String) = RecurringIncomeDto(
        id = id,
        name = name,
        amount = "10.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        next_due_date = "2026-09-01",
        next_unpaid_occurrence_date = "2026-08-01",
        created_at = "2026-08-01T10:00:00Z",
    )

    // --- Row title ---

    @Test
    fun `the title leads with the category then the whole description`() {
        assertEquals(
            "Food · Coffee at home",
            transactionTitle(
                transaction(1, TransactionType.EXPENSE, categoryId = 3, description = " Coffee at home "),
                categoryName = "Food",
            ),
        )
    }

    @Test
    fun `an uncategorized expense is labeled Uncategorized`() {
        assertEquals(
            "Uncategorized",
            transactionTitle(transaction(1, TransactionType.EXPENSE), categoryName = null),
        )
        // The label stands in the Category's leading slot.
        assertEquals(
            "Uncategorized · Groceries",
            transactionTitle(
                transaction(1, TransactionType.EXPENSE, description = "Groceries"),
                categoryName = null,
            ),
        )
    }

    @Test
    fun `an opening balance keeps its fixed label whatever else is set`() {
        assertEquals(
            "Opening balance",
            transactionTitle(
                transaction(1, TransactionType.OPENING_BALANCE, categoryId = 3, description = "Start"),
                categoryName = "Food",
            ),
        )
    }

    @Test
    fun `without a category or description the type word survives`() {
        assertEquals("Income", transactionTitle(transaction(1, TransactionType.INCOME), categoryName = null))
        assertEquals("Transfer", transactionTitle(
            transaction(1, TransactionType.TRANSFER, sourceWalletId = 1, destinationWalletId = 2),
            categoryName = null,
        ))
    }

    @Test
    fun `a whitespace-only description counts as blank`() {
        assertEquals(
            "Uncategorized",
            transactionTitle(
                transaction(1, TransactionType.EXPENSE, description = "   "),
                categoryName = null,
            ),
        )
        assertNull(descriptionText("   "))
        assertNull(descriptionText(null))
        assertEquals("Coffee", descriptionText("  Coffee  "))
    }

    // --- Signed amount ---

    @Test
    fun `expenses and incomes carry signs and money movements never do`() {
        assertEquals("-€12.50", signedAmount(transaction(1, TransactionType.EXPENSE, amount = "12.50")))
        assertEquals("+€12.50", signedAmount(transaction(1, TransactionType.INCOME, amount = "12.50")))
        assertEquals("€12.50", signedAmount(transaction(1, TransactionType.TRANSFER, amount = "12.50")))
        assertEquals("€12.50", signedAmount(transaction(1, TransactionType.OPENING_BALANCE, amount = "12.50")))
    }

    // --- Frozen-wallet read-onlyness ---

    @Test
    fun `a transaction on a frozen wallet is read-only`() {
        assertTrue(isOnFrozenWallet(transaction(1, TransactionType.EXPENSE, walletId = 2), listOf(activeWallet, frozenWallet)))
        assertFalse(isEditable(transaction(1, TransactionType.EXPENSE, walletId = 2), listOf(activeWallet, frozenWallet)))
        assertTrue(isEditable(transaction(1, TransactionType.EXPENSE, walletId = 1), listOf(activeWallet, frozenWallet)))
    }

    @Test
    fun `a transfer is frozen when either leg is frozen`() {
        val bothActive = transaction(1, TransactionType.TRANSFER, sourceWalletId = 1, destinationWalletId = 3)
        val frozenSource = transaction(1, TransactionType.TRANSFER, sourceWalletId = 2, destinationWalletId = 3)
        val frozenDestination = transaction(1, TransactionType.TRANSFER, sourceWalletId = 3, destinationWalletId = 2)
        val wallets = listOf(activeWallet, frozenWallet, wallet(3))
        assertFalse(isOnFrozenWallet(bothActive, wallets))
        assertTrue(isOnFrozenWallet(frozenSource, wallets))
        assertTrue(isOnFrozenWallet(frozenDestination, wallets))
    }

    @Test
    fun `a transaction whose wallet is unknown counts as read-only`() {
        assertTrue(isOnFrozenWallet(transaction(1, TransactionType.EXPENSE, walletId = 99), listOf(activeWallet)))
        assertFalse(isEditable(transaction(1, TransactionType.EXPENSE, walletId = 99), listOf(activeWallet)))
    }

    @Test
    fun `an opening balance never offers edit entry points even on an active wallet`() {
        assertFalse(isEditable(transaction(1, TransactionType.OPENING_BALANCE, walletId = 1), listOf(activeWallet)))
    }

    // --- Wallet / category labels ---

    @Test
    fun `wallet names fall back to Frozen wallet when unknown and transfers show both legs`() {
        assertEquals("Wallet 1", walletName(listOf(activeWallet), 1))
        assertEquals("Frozen wallet", walletName(listOf(activeWallet), null))
        assertEquals("Frozen wallet", walletName(listOf(activeWallet), 99))
        assertEquals(
            "Wallet 1 → Wallet 2",
            walletLabel(
                transaction(1, TransactionType.TRANSFER, sourceWalletId = 1, destinationWalletId = 2),
                listOf(activeWallet, frozenWallet),
            ),
        )
        assertEquals(
            "Wallet 1",
            walletLabel(transaction(1, TransactionType.EXPENSE, walletId = 1), listOf(activeWallet)),
        )
    }

    @Test
    fun `the filter wallet option marks frozen wallets and carries the balance`() {
        assertEquals("Cash (€100.00)", walletFilterLabel(activeWallet.copy(name = "Cash", balance = "100.00")))
        assertEquals(
            "Old Card · Frozen (€0.00)",
            walletFilterLabel(frozenWallet.copy(name = "Old Card")),
        )
    }

    @Test
    fun `the recurring filter label names the picked definition or all transactions`() {
        // A Recurring Cost and a Recurring Income may share an id: each
        // pick resolves within its own kind's list, never the other's.
        val rent = recurringCost(1, "Rent")
        val salary = recurringIncome(1, "Salary")
        val freelance = recurringIncome(2, "Freelance")
        val costs = listOf(rent)
        val incomes = listOf(salary, freelance)

        assertEquals("All transactions", recurringFilterLabel(null, costs, incomes))
        assertEquals(
            "Rent",
            recurringFilterLabel(RecurringFilter(RecurringFilterKind.COST, rent.id), costs, incomes),
        )
        // The same id on the income side is the Salary, never the Rent.
        assertEquals(
            "Salary",
            recurringFilterLabel(RecurringFilter(RecurringFilterKind.INCOME, salary.id), costs, incomes),
        )
        // An id that exists only on the other kind is no match for this pick.
        assertEquals(
            "",
            recurringFilterLabel(RecurringFilter(RecurringFilterKind.COST, freelance.id), costs, incomes),
        )
        // A pick whose definition is gone from the refreshed lists reads empty.
        assertEquals(
            "",
            recurringFilterLabel(RecurringFilter(RecurringFilterKind.COST, 99), costs, incomes),
        )
    }

    @Test
    fun `a location exists exactly when both coordinates are present`() {
        assertFalse(hasLocation(transaction(1, TransactionType.EXPENSE, latitude = "1.0")))
        assertTrue(hasLocation(transaction(1, TransactionType.EXPENSE, latitude = "1.0", longitude = "2.0")))
    }

    // --- Filtered chips line (web issue #92, ticket #35) ---

    @Test
    fun `one chip per set filter in the web's order with plain names and merged dates`() {
        val state = TransactionsViewModel.UiState(
            wallets = listOf(namedWallet(1, "Old Card"), namedWallet(2, "Cash")),
            categories = listOf(category(1, "Food")),
            recurringCosts = listOf(recurringCost(1, "Rent")),
            recurringIncomes = listOf(recurringIncome(1, "Salary")),
            filterWalletId = 2,
            filterCategoryId = 1,
            filterFromDate = "2026-01-01",
            filterToDate = "2026-01-31",
            filterRecurring = RecurringFilter(RecurringFilterKind.COST, 1),
        )
        // Wallet, category, the two dates merged into one range chip, the
        // recurring definition — the chips read in the web's order, with
        // the plain names (no balances, no frozen marks, no icons).
        assertEquals(
            listOf(
                FilterChipSpec(FilterChipKey.WALLET, "Cash"),
                FilterChipSpec(FilterChipKey.CATEGORY, "Food"),
                FilterChipSpec(FilterChipKey.DATES, "2026-01-01 – 2026-01-31"),
                FilterChipSpec(FilterChipKey.RECURRING, "Rent"),
            ),
            activeFilterChips(state),
        )
    }

    @Test
    fun `a lone date bound chips as From or To and none as no chip`() {
        val base = TransactionsViewModel.UiState(
            wallets = listOf(namedWallet(1, "Cash")),
            categories = listOf(category(1, "Food")),
            recurringCosts = listOf(recurringCost(1, "Rent")),
            filterWalletId = 1,
            filterCategoryId = 1,
        )
        // From alone: one chip "From …" between the category chip and the
        // recurring chip, in the web's order.
        assertEquals(
            listOf(
                FilterChipSpec(FilterChipKey.WALLET, "Cash"),
                FilterChipSpec(FilterChipKey.CATEGORY, "Food"),
                FilterChipSpec(FilterChipKey.DATES, "From 2026-01-01"),
            ),
            activeFilterChips(base.copy(filterFromDate = "2026-01-01")),
        )
        // To alone: "To …". Neither bound: no date chip at all.
        assertEquals(
            FilterChipSpec(FilterChipKey.DATES, "To 2026-01-31"),
            activeFilterChips(base.copy(filterToDate = "2026-01-31")).last(),
        )
        assertNull(filterDateChipLabel(null, null))
        assertNull(activeFilterChips(base).firstOrNull { it.key == FilterChipKey.DATES })
    }

    @Test
    fun `the recurring chip resolves each kind against its own list`() {
        // A Recurring Cost and a Recurring Income may share an id (and a
        // name): the chip must read the picked kind's own list.
        val state = TransactionsViewModel.UiState(
            wallets = listOf(namedWallet(1, "Cash")),
            categories = listOf(category(1, "Food")),
            recurringCosts = listOf(recurringCost(1, "Rent")),
            recurringIncomes = listOf(recurringIncome(1, "Salary")),
            filterWalletId = 1,
            filterRecurring = RecurringFilter(RecurringFilterKind.INCOME, 1),
        )
        assertEquals(
            listOf(
                FilterChipSpec(FilterChipKey.WALLET, "Cash"),
                FilterChipSpec(FilterChipKey.RECURRING, "Salary"),
            ),
            activeFilterChips(state),
        )
    }

    @Test
    fun `a set filter whose entity the lists do not know shows no chip`() {
        // Still loading, or the entity vanished: the filter is kept (the
        // panel select remains the way back to it), but its chip — and
        // only its chip — is omitted.
        val state = TransactionsViewModel.UiState(
            wallets = listOf(namedWallet(1, "Cash")),
            categories = listOf(category(1, "Food")),
            filterWalletId = 99,
            filterCategoryId = 1,
        )
        assertEquals(
            listOf(FilterChipSpec(FilterChipKey.CATEGORY, "Food")),
            activeFilterChips(state),
        )
        // The recurring pick whose definition vanished reads no chip too.
        val orphaned = state.copy(
            filterWalletId = 1,
            filterCategoryId = null,
            filterRecurring = RecurringFilter(RecurringFilterKind.COST, 99),
        )
        assertEquals(
            listOf(FilterChipSpec(FilterChipKey.WALLET, "Cash")),
            activeFilterChips(orphaned),
        )
    }
}
