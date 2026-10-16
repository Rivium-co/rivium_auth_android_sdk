package co.rivium.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What survives an app restart. The access token is never stored. */
internal data class StoredSession(val refreshToken: String, val userId: String)

internal interface SessionStorage {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

/**
 * Keeps the session in private SharedPreferences, encrypted with an AES-GCM
 * key that lives in the Android Keystore and never leaves it. A copy restored
 * on another device (backup) cannot be decrypted and reads as signed out.
 */
internal class KeystoreSessionStorage(context: Context, apiKey: String) : SessionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val entry = "session." + MessageDigest.getInstance("SHA-256")
        .digest(apiKey.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    @Synchronized
    override fun load(): StoredSession? {
        val stored = prefs.getString(entry, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            val json = JSONObject(String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8))
            StoredSession(json.getString("refreshToken"), json.getString("userId"))
        } catch (e: Exception) {
            // Key gone (restored backup, reset lock screen) or data damaged: start signed out.
            prefs.edit().remove(entry).apply()
            null
        }
    }

    @Synchronized
    override fun save(session: StoredSession) {
        try {
            val plain = JSONObject()
                .put("refreshToken", session.refreshToken)
                .put("userId", session.userId)
                .toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(plain)
            // commit(): the rotated refresh token must be on disk before it is used.
            prefs.edit().putString(entry, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
        } catch (e: Exception) {
            // The session keeps working in memory; it just will not survive a restart.
        }
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(entry).commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "co.rivium.auth"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "co.rivium.auth.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** For tests, and for apps that do not want the session to outlive the process. */
internal class MemorySessionStorage(private var value: StoredSession? = null) : SessionStorage {
    @Synchronized override fun load(): StoredSession? = value
    @Synchronized override fun save(session: StoredSession) { value = session }
    @Synchronized override fun clear() { value = null }
}
