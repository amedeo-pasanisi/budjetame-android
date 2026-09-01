package com.budjetame.android

import com.budjetame.android.data.TokenStorage

/** Test double for the Keystore-backed TokenStore. */
class InMemoryTokenStorage : TokenStorage {
    var stored: String? = null
        private set

    override fun save(token: String) {
        stored = token
    }

    override fun load(): String? = stored

    override fun clear() {
        stored = null
    }
}
