package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.util.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Presentation-only logic for the Transaction form (ticket #20), ported from
 * the web app's transactionFields.tsx + balanceProjection.ts: which Wallets
 * a type may use, the Cash-negative balance projection, and the option
 * labels. These are the cheap spots where porting bugs hide, so they get
 * direct JVM tests.
 */

/** Expense/Income move money through the user's own Wallets; only a Transfer
 * (and, for an Expense, ADR-0017's contact-paid consumption) touches Contact
 * Wallets. */
val NON_CONTACT_WALLET_TYPES: Set<WalletType> = setOf(
    WalletType.CHECKING,
    WalletType.CREDIT_CARD,
    WalletType.CASH,
)

/** Wallets a form may assign (frozen ones are read-only, ADR-0002). */
fun activeWallets(wallets: List<WalletDto>): List<WalletDto> =
    wallets.filterNot { it.frozen }

/** Wallets an Expense/Income may default to: active and non-Contact — an
 * Expense on a Contact Wallet is a deliberate pick, and an Income never may. */
fun spendableWallets(wallets: List<WalletDto>): List<WalletDto> =
    activeWallets(wallets).filter { it.type in NON_CONTACT_WALLET_TYPES }

/** The Categories an Expense/Income's Category select offers. */
fun matchingCategories(categories: List<CategoryDto>, type: TransactionType): List<CategoryDto> {
    val categoryType = when (type) {
        TransactionType.EXPENSE -> CategoryType.EXPENSE
        TransactionType.INCOME -> CategoryType.INCOME
        else -> return emptyList()
    }
    return categories.filter { it.type == categoryType }
}

/** A Wallet option in the form: "Name (€balance)" — frozen Wallets never
 * reach the form's pickers, so no Frozen mark here. */
fun walletOptionLabel(wallet: WalletDto): String =
    "${wallet.name} (${Money.formatEuros(wallet.balance)})"

/**
 * The draft's amount as a positive BigDecimal, or null when blank, not a
 * number, or not strictly positive — the mandatory-amount gate.
 */
fun parseAmount(raw: String): BigDecimal? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return try {
        val value = BigDecimal(trimmed)
        if (value > BigDecimal.ZERO) value else null
    } catch (_: NumberFormatException) {
        null
    }
}

/** The projected Balance of one Wallet. */
data class BalanceProjection(val before: BigDecimal, val after: BigDecimal)

/** The projected Balances of both Wallets a Transfer moves between. */
data class TransferProjection(
    val source: BalanceProjection,
    val destination: BalanceProjection,
)

/**
 * Project the one Wallet an Expense/Income moves. The Wallet's current
 * Balance already includes the Transaction being edited, so the preview
 * removes its old contribution before applying the new amount (web issue
 * #24); `editedAmount` is null when creating.
 */
fun projectBalance(
    currentBalance: String,
    type: TransactionType,
    newAmount: BigDecimal,
    editedAmount: BigDecimal?,
): BalanceProjection {
    val before = BigDecimal(currentBalance) - signedContribution(type, editedAmount)
    val after = before + signed(type, newAmount)
    return BalanceProjection(before = before.toCents(), after = after.toCents())
}

/**
 * Project both Wallets a Transfer moves between. The source's Balance
 * includes the outgoing leg and the destination's includes the incoming leg;
 * removing the old amount restores each pre-Transfer Balance.
 */
fun projectTransfer(
    sourceBalance: String,
    destinationBalance: String,
    newAmount: BigDecimal,
    editedAmount: BigDecimal?,
): TransferProjection {
    val oldAmount = editedAmount ?: BigDecimal.ZERO
    val sourceBefore = BigDecimal(sourceBalance) + oldAmount
    val destinationBefore = BigDecimal(destinationBalance) - oldAmount
    return TransferProjection(
        source = BalanceProjection(before = sourceBefore.toCents(), after = (sourceBefore - newAmount).toCents()),
        destination = BalanceProjection(before = destinationBefore.toCents(), after = (destinationBefore + newAmount).toCents()),
    )
}

/** True when a draft would leave the given Cash Wallet negative — the
 * pre-save ⚠ line. Only Cash Wallets warn (CONTEXT.md). */
fun isCashNegativeWarning(wallet: WalletDto?, after: BigDecimal?): Boolean =
    wallet?.type == WalletType.CASH && after != null && after < BigDecimal.ZERO

/** The signed money movement of an Expense (−) or Income (+). */
private fun signed(type: TransactionType, amount: BigDecimal): BigDecimal = when (type) {
    TransactionType.EXPENSE -> amount.negate()
    TransactionType.INCOME -> amount
    else -> BigDecimal.ZERO
}

/** The signed effect of the edited Transaction on its Wallet's Balance. */
private fun signedContribution(type: TransactionType, editedAmount: BigDecimal?): BigDecimal =
    if (editedAmount == null) BigDecimal.ZERO else signed(type, editedAmount)

/** Round a projection to cents so float arithmetic noise never leaks into the
 * display or the warning (the boundary x − x is exact either way). */
private fun BigDecimal.toCents(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
