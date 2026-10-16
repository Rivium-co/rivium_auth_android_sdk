package co.rivium.auth

/** The result of a sign-in, a sign-up that signed in, or a link. */
data class AuthResult(
    val userId: String,
    /** True when this sign-in created the user. Reported for Apple and Google; null otherwise. */
    val isNewUser: Boolean?,
)

/** The result of [RiviumAuth.signUp]. */
data class SignUpResult(
    val userId: String,
    /** A verification e-mail was sent. */
    val verificationSent: Boolean,
    /** False when the project requires a verified e-mail first. */
    val signedIn: Boolean,
)

/** The signed-in user, as returned by [RiviumAuth.me]. */
data class RiviumUser(
    val id: String,
    val isAnonymous: Boolean,
    val emailVerified: Boolean,
    /** ISO 8601 creation time. */
    val createdAt: String?,
    /** Linked sign-in methods: "apple", "google", "password", ... */
    val providers: List<String>,
)

/** The sign-in methods the project offers. Show only these buttons. */
data class AuthMethods(
    /** "anonymous", "apple", "google", "password", "magicLink", "telegram" */
    val methods: List<String>,
    /** The project's Telegram bot username, when Telegram sign-in is on. */
    val telegramBot: String?,
) {
    fun isEnabled(method: String): Boolean = method in methods
}

/** The result of [RiviumAuth.requestTelegramCode]. */
data class TelegramCodeResult(
    /** The code was sent to the user's Telegram chat. */
    val sent: Boolean,
    /** When [sent] is false: open this link so the user starts the bot and shares their phone. */
    val botUrl: String?,
    /** Seconds the code stays valid. */
    val expiresIn: Int,
)

internal data class SessionTokens(
    val accessToken: String,
    val expiresInSec: Long,
    val refreshToken: String,
    val userId: String,
    val created: Boolean?,
)
