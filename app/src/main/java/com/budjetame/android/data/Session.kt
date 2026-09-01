package com.budjetame.android.data

/**
 * The in-memory session: the JWT is loaded from the encrypted TokenStore at
 * start and cached here so the API transport's interceptor can read it
 * synchronously. Keystore I/O happens only on save/clear.
 */
class Session(private val store: TokenStorage) {

    @Volatile
    private var current: String? = store.load()

    val token: String? get() = current

    fun save(token: String) {
        current = token
        store.save(token)
    }

    fun clear() {
        current = null
        store.clear()
    }
}
