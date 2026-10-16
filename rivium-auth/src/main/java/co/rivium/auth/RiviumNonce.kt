package co.rivium.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Nonces for Google and Apple sign-in.
 *
 * Give the provider [sha256] of the raw nonce and give Rivium Auth the raw
 * value. Rivium Auth checks that they match and accepts each nonce once, so a
 * captured ID token cannot be replayed.
 */
object RiviumNonce {
    private const val CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._"
    private val random = SecureRandom()

    /** A random nonce from a secure random generator. Rivium Auth accepts 16 to 256 characters. */
    @JvmStatic
    @JvmOverloads
    fun generate(length: Int = 32): String {
        require(length in 16..256) { "Nonce length must be 16-256" }
        val sb = StringBuilder(length)
        repeat(length) { sb.append(CHARSET[random.nextInt(CHARSET.length)]) }
        return sb.toString()
    }

    /** Lowercase hex SHA-256 of [input]: what the provider must receive as its nonce. */
    @JvmStatic
    fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
