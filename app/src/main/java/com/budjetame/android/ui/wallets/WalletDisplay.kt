package com.budjetame.android.ui.wallets

import androidx.annotation.StringRes
import com.budjetame.android.R
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType

/**
 * Presentation-only grouping for the Wallets screen, ported from the web
 * app's WalletsScreen (issue #47): fixed sections, A→Z case-insensitive
 * sorting, and frozen wallets kept apart from their type sections.
 */

private val WALLET_SECTION_TYPES: List<WalletType> = listOf(
    WalletType.CONTACT,
    WalletType.CHECKING,
    WalletType.CREDIT_CARD,
    WalletType.CASH,
)

/** Singular type label for row subtitles (mirrors the web app): a string
 * resource the composable resolves in the Account's Locale (ADR-0010). */
@StringRes
fun walletTypeLabel(type: WalletType): Int = when (type) {
    WalletType.CONTACT -> R.string.wallets_type_contact
    WalletType.CHECKING -> R.string.wallets_type_checking
    WalletType.CREDIT_CARD -> R.string.wallets_type_credit_card
    WalletType.CASH -> R.string.wallets_type_cash
}

/** Plural section header (mirrors the web app); "Cash" has no plural. */
@StringRes
fun walletSectionLabel(type: WalletType): Int = when (type) {
    WalletType.CONTACT -> R.string.wallets_section_contact
    WalletType.CHECKING -> R.string.wallets_section_checking
    WalletType.CREDIT_CARD -> R.string.wallets_section_credit_card
    WalletType.CASH -> R.string.wallets_section_cash
}

data class WalletSection(
    val type: WalletType,
    @StringRes val label: Int,
    val items: List<WalletDto>,
)

/** Fixed-order sections, empty sections kept (the screen hides them). */
fun walletSections(wallets: List<WalletDto>): List<WalletSection> =
    WALLET_SECTION_TYPES.map { type ->
        WalletSection(
            type = type,
            label = walletSectionLabel(type),
            items = wallets
                .filter { it.type == type && !it.frozen }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
        )
    }

/** One flat A→Z list across types, matching the active sections' sort. */
fun frozenWalletsOf(wallets: List<WalletDto>): List<WalletDto> =
    wallets.filter { it.frozen }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
