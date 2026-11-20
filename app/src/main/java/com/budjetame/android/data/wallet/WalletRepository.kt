package com.budjetame.android.data.wallet

import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletCreateRequest
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.api.WalletUpdateRequest
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/** The wallet operations screens call (UI-independent). */
interface WalletGateway {
    suspend fun fetchWallets(): List<WalletDto>
    suspend fun createWallet(name: String, type: WalletType, openingBalance: String): WalletDto
    suspend fun renameWallet(id: Int, name: String): WalletDto
    suspend fun freezeWallet(id: Int)
    suspend fun unfreezeWallet(id: Int): WalletDto

    /** Delete a frozen, history-free Wallet (web ADR-0034); answers the
     * deleted row, kept for the Undo. */
    suspend fun deleteWallet(id: Int): WalletDto

    /** Bring a deleted Wallet back from its held row, still frozen. */
    suspend fun undoWalletDelete(row: WalletDto): WalletDto
}

/** The API-backed WalletGateway (web issue #17). */
class ApiWalletRepository(private val api: WalletApi) : WalletGateway {

    override suspend fun fetchWallets(): List<WalletDto> =
        call { api.list(includeFrozen = true) }

    override suspend fun createWallet(
        name: String,
        type: WalletType,
        openingBalance: String,
    ): WalletDto =
        call { api.create(WalletCreateRequest(name = name, type = type, opening_balance = openingBalance)) }

    override suspend fun renameWallet(id: Int, name: String): WalletDto =
        call { api.rename(id, WalletUpdateRequest(name = name)) }

    override suspend fun freezeWallet(id: Int) {
        call { api.freeze(id) }
    }

    override suspend fun unfreezeWallet(id: Int): WalletDto =
        call { api.unfreeze(id) }

    override suspend fun deleteWallet(id: Int): WalletDto =
        call { api.delete(id) }

    override suspend fun undoWalletDelete(row: WalletDto): WalletDto =
        call { api.undo(row) }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
