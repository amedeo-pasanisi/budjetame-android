package com.budjetame.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the session token lives; tests substitute an in-memory fake. */
interface TokenStorage {
    fun save(token: String)
    fun load(): String?
    fun clear()
}

/**
 * Persists the session JWT encrypted at rest with an AES-GCM key that never
 * leaves the Android Keystore (the Jetpack security-crypto library is
 * deprecated, so this is a small hand-rolled wrapper instead). A token that
 * can no longer be decrypted (e.g. the key was invalidated) is treated as
 * signed out.
 */
class TokenStore(context: Context) : TokenStorage {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun save(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore generates the GCM IV itself (some implementations —
        // e.g. this phone's — reject caller-provided IVs in ENCRYPT mode
        // with InvalidAlgorithmParameterException); read it back for storage.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val stored = Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PREF, stored).apply()
    }

    override fun load(): String? {
        val stored = prefs.getString(KEY_PREF, null) ?: return null
        return try {
            val parts = stored.split(':', limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_PREF).apply()
    }

    companion object {
        private const val PREFS_NAME = "budjetame.session"
        private const val KEY_PREF = "token"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "budjetame-token-key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
