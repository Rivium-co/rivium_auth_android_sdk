package co.rivium.auth

/**
 * An error from Rivium Auth.
 *
 * Branch on [code] (see [RiviumAuthErrorCode]), never on [message]: the message
 * is an English hint for developers, the code is stable.
 *
 * ```kotlin
 * try {
 *     RiviumAuth.shared.linkGoogle(idToken, rawNonce)
 * } catch (e: RiviumAuthException) {
 *     if (e.code == RiviumAuthErrorCode.PROVIDER_ALREADY_LINKED) {
 *         // This Google account already belongs to another user.
 *     }
 * }
 * ```
 *
 * @property httpStatus The HTTP status, or 0 when the request never got an answer.
 * @property retryAfterSec For [RiviumAuthErrorCode.RATE_LIMITED]: seconds to wait.
 * @property fields For [RiviumAuthErrorCode.INVALID_INPUT]: refused fields and why.
 */
class RiviumAuthException(
    val code: String,
    message: String,
    val httpStatus: Int = 0,
    val retryAfterSec: Int? = null,
    val fields: Map<String, String>? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val isProviderAlreadyLinked: Boolean get() = code == RiviumAuthErrorCode.PROVIDER_ALREADY_LINKED

    /** The session cannot be refreshed any more: the user has to sign in again. */
    internal val endsSession: Boolean
        get() = code == RiviumAuthErrorCode.SESSION_REVOKED ||
            code == RiviumAuthErrorCode.SESSION_EXPIRED ||
            code == RiviumAuthErrorCode.TOKEN_INVALID ||
            code == RiviumAuthErrorCode.USER_DISABLED

    override fun toString(): String = "RiviumAuthException($code, $httpStatus): $message"

    internal companion object {
        fun notSignedIn() = RiviumAuthException(RiviumAuthErrorCode.NOT_SIGNED_IN, "No user is signed in")
        fun notConfigured() = RiviumAuthException(
            RiviumAuthErrorCode.SDK_NOT_CONFIGURED,
            "Call RiviumAuth.shared.configure(context, apiKey) first",
        )
    }
}

/** Error codes. Server codes keep their server spelling. */
object RiviumAuthErrorCode {
    const val INVALID_INPUT = "INVALID_INPUT"
    const val INVALID_API_KEY = "INVALID_API_KEY"
    const val METHOD_DISABLED = "METHOD_DISABLED"
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val EMAIL_TAKEN = "EMAIL_TAKEN"
    const val EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED"
    const val WEAK_PASSWORD = "WEAK_PASSWORD"
    const val INVALID_ID_TOKEN = "INVALID_ID_TOKEN"
    const val NONCE_REUSED = "NONCE_REUSED"
    const val PROVIDER_ALREADY_LINKED = "PROVIDER_ALREADY_LINKED"
    const val TOKEN_INVALID = "TOKEN_INVALID"
    const val SESSION_EXPIRED = "SESSION_EXPIRED"
    const val SESSION_REVOKED = "SESSION_REVOKED"
    const val USER_DISABLED = "USER_DISABLED"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val LIMIT_REACHED = "LIMIT_REACHED"
    const val PRODUCT_DISABLED = "PRODUCT_DISABLED"
    const val MAU_LIMIT = "MAU_LIMIT"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val INTERNAL = "INTERNAL"

    /** [RiviumAuth.configure] was not called. */
    const val SDK_NOT_CONFIGURED = "SDK_NOT_CONFIGURED"
    /** The call needs a signed-in user and there is none. */
    const val NOT_SIGNED_IN = "NOT_SIGNED_IN"
    /** No connection, a timeout, or the server could not be reached. */
    const val NETWORK_ERROR = "NETWORK_ERROR"
    /** The server answered something the SDK could not read. */
    const val INVALID_RESPONSE = "INVALID_RESPONSE"
}
