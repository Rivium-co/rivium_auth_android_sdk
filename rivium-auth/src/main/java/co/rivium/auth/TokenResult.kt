package co.rivium.auth

import org.json.JSONArray
import org.json.JSONObject

/**
 * The access token with its decoded fields, like Firebase's `GetTokenResult` (times in seconds since 1970, UTC).
 *
 * For display only (a "Pro" badge, unlocked lessons): the app can read the token but never change it. Rivium's servers
 * check the token on every request and decide access with rules.
 */
data class RiviumTokenResult(
    /** The access token itself (the same one [RiviumAuth.getAccessToken] gives). */
    val token: String,
    /**
     * Every field of the token: `sub` (the user id), `pid`, `iss`, `aud`, `iat`, `exp`, `auth_time`, `amr`, and the
     * custom claims your server set with `setCustomUserClaims` (for example `plan`, `role`). Objects are [Map]s, lists
     * are [List]s, JSON null is `null`.
     */
    val claims: Map<String, Any?>,
    /** When this token expires. */
    val expirationTimestamp: Long,
    /** When this token was issued. */
    val issuedAtTimestamp: Long,
    /** When the user signed in: the session's start; a refresh doesn't change it. */
    val authTimestamp: Long,
    /** How the user signed in: `anonymous`, `apple`, `google`, `password`, `magic_link`, `telegram`. */
    val signInProvider: String?,
) {
    companion object {
        /** Reads a token; null when it isn't one Rivium signs. */
        @JvmStatic
        fun fromToken(token: String): RiviumTokenResult? {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val payload = try {
                JSONObject(String(base64UrlDecode(parts[1]), Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }
            val exp = (payload.opt("exp") as? Number)?.toLong() ?: return null
            val iat = (payload.opt("iat") as? Number)?.toLong() ?: return null
            val claims = toMap(payload).toMutableMap().apply { remove("meta") } // Rivium's own bookkeeping, not a claim
            val amr = payload.optJSONArray("amr")?.opt(0) as? String
            return RiviumTokenResult(
                token = token,
                claims = claims,
                expirationTimestamp = exp,
                issuedAtTimestamp = iat,
                authTimestamp = (payload.opt("auth_time") as? Number)?.toLong() ?: iat,
                signInProvider = amr,
            )
        }

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** base64url without android.util.Base64 (a stub in JVM tests) or java.util.Base64 (API 26+; minSdk is 23). */
        internal fun base64UrlDecode(input: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (ch in input.trimEnd('=')) {
                val v = ALPHABET.indexOf(if (ch == '+') '-' else if (ch == '/') '_' else ch)
                require(v >= 0) { "not base64url" }
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xff)
                }
            }
            return out.toByteArray()
        }

        private fun toMap(o: JSONObject): Map<String, Any?> =
            o.keys().asSequence().associateWith { plain(o.opt(it)) }

        private fun plain(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
            else -> v
        }
    }
}
