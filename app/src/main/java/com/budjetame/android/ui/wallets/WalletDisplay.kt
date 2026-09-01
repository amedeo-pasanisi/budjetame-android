package com.budjetame.android.ui.wallets

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

/** Singular type label for row subtitles (mirrors the web app). */
fun walletTypeLabel(type: WalletType): String = when (type) {
    WalletType.CONTACT -> "Contact"
    WalletType.CHECKING -> "Checking"
    WalletType.CREDIT_CARD -> "Credit Card"
    WalletType.CASH -> "Cash"
}

/** Plural section header (mirrors the web app); "Cash" has no plural. */
fun walletSectionLabel(type: WalletType): String = when (type) {
    WalletType.CONTACT -> "Contacts"
    WalletType.CHECKING -> "Checking Accounts"
    WalletType.CREDIT_CARD -> "Credit Cards"
    WalletType.CASH -> "Cash"
}

data class WalletSection(
    val type: WalletType,
    val label: String,
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
