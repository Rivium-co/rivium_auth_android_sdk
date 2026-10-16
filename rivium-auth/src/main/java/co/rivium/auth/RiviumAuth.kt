package co.rivium.auth

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** A token provider in the shape Rivium Push accepts: the user's token, or null when signed out. */
typealias RiviumAuthTokenProvider = suspend () -> String?

/** A token provider in the shape Rivium Chat accepts: the user's token, or an exception when signed out. */
typealias RiviumAuthStrictTokenProvider = suspend () -> String

/** A token provider in the shape Rivium Sync accepts: blocking, called on a background thread. */
typealias RiviumAuthBlockingTokenProvider = () -> String?

/** Told the user id on every sign-in, sign-out and session end. */
fun interface AuthStateListener {
    fun onAuthStateChanged(userId: String?)
}

/** A listener registration. Call [remove] to stop receiving changes. */
fun interface ListenerRegistration {
    fun remove()
}

/**
 * Rivium Auth: sign-in for your app's users.
 *
 * ```kotlin
 * RiviumAuth.shared.configure(applicationContext, "rv_live_...")
 * RiviumAuth.shared.signInAnonymously()
 * ```
 *
 * The refresh token is stored encrypted with a Keystore key; the access token
 * stays in memory and is refreshed about two minutes before it expires.
 */
class RiviumAuth internal constructor() {

    companion object {
        /** The one instance apps use. */
        @JvmStatic
        val shared: RiviumAuth by lazy { RiviumAuth() }

        const val DEFAULT_BASE_URL = "https://auth-users.rivium.co"

        /** The `token` query parameter of a reset, verification or magic link. */
        @JvmStatic
        fun tokenFromLink(link: String): String? {
            val query = link.substringAfter('?', "").substringBefore('#')
            return query.split('&')
                .firstOrNull { it.substringBefore('=') == "token" }
                ?.substringAfter('=', "")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotEmpty() }
        }
    }

    @Volatile private var http: HttpClient? = null
    @Volatile private var session: SessionManager? = null
    @Volatile private var cachedUser: RiviumUser? = null
    private val listeners = CopyOnWriteArrayList<AuthStateListener>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow<String?>(null)

    /** The signed-in user id, or null. Collect it to follow sign-in and sign-out. */
    val authState: StateFlow<String?> = _authState.asStateFlow()

    // ---------------------------------------------------------------- Setup

    /**
     * Sets the project's public API key. Call once, in `Application.onCreate`,
     * before any other call. A session saved by an earlier launch is restored.
     *
     * @param apiKey The public project key (`rv_live_...`).
     * @param baseUrl Only for testing against another Rivium Auth server.
     */
    @JvmOverloads
    fun configure(context: Context, apiKey: String, baseUrl: String = DEFAULT_BASE_URL) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        configure(apiKey, baseUrl, client, KeystoreSessionStorage(context, apiKey))
    }

    internal fun configure(apiKey: String, baseUrl: String, client: OkHttpClient, storage: SessionStorage, clock: () -> Long = System::currentTimeMillis) {
        val http = HttpClient(baseUrl, apiKey, client)
        val manager = SessionManager(http, storage, clock) { userChanged(it) }
        this.http = http
        this.session = manager
        this.cachedUser = null
        _authState.value = manager.restoredUserId
    }

    // ---------------------------------------------------------------- State

    /** The signed-in user id, or null. */
    val currentUserId: String? get() = session?.currentUserId

    val isSignedIn: Boolean get() = currentUserId != null

    /** The profile last loaded by [me] for the signed-in user, or null. */
    val currentUser: RiviumUser? get() = cachedUser?.takeIf { it.id == currentUserId }

    /**
     * Adds a listener told the user id on every sign-in, sign-out and session end
     * (including a session revoked on the server), and once right away. Called on
     * the thread that made the change; post to the main thread to touch views.
     */
    fun addAuthStateListener(listener: AuthStateListener): ListenerRegistration {
        listeners += listener
        listener.onAuthStateChanged(currentUserId)
        return ListenerRegistration { listeners -= listener }
    }

    private fun userChanged(userId: String?) {
        if (cachedUser?.id != userId) cachedUser = null
        _authState.value = userId
        listeners.forEach { it.onAuthStateChanged(userId) }
    }

    // ---------------------------------------------------------------- Tokens

    /** The signed-in user's access token, refreshed when about to expire. Null when signed out. */
    @JvmOverloads
    suspend fun getAccessToken(forceRefresh: Boolean = false): String? =
        sessionManager().validAccessToken(forceRefresh)

    /** For Rivium Push: `RiviumPushConfig(..., tokenProvider = RiviumAuth.shared.tokenProvider)`. */
    val tokenProvider: RiviumAuthTokenProvider = { getAccessToken() }

    /** For Rivium Chat: `RiviumChatConfig(..., tokenProvider = RiviumAuth.shared.strictTokenProvider)`. */
    val strictTokenProvider: RiviumAuthStrictTokenProvider = {
        getAccessToken() ?: throw RiviumAuthException.notSignedIn()
    }

    /**
     * For Rivium Sync: `RiviumSyncConfig.builder(key).userTokenProvider(RiviumAuth.shared.blockingTokenProvider)`.
     * Blocks the calling thread; never call it on the main thread.
     */
    val blockingTokenProvider: RiviumAuthBlockingTokenProvider = {
        runBlocking { getAccessToken() }
    }

    // ---------------------------------------------------------------- Anonymous

    suspend fun signInAnonymously(): AuthResult = start(client().sendForSession("POST", "v1/anonymous"))

    // ---------------------------------------------------------------- Apple / Google

    /**
     * Signs in with an Apple ID token whose nonce is [RiviumNonce.sha256] of [rawNonce].
     * Pass [authorizationCode] so the Apple sign-in can be revoked when the user
     * deletes the account, and [fullName] on the first sign-in.
     */
    @JvmOverloads
    suspend fun signInWithApple(idToken: String, rawNonce: String, authorizationCode: String? = null, fullName: String? = null): AuthResult =
        start(client().sendForSession("POST", "v1/apple", social(idToken, rawNonce, authorizationCode, fullName)))

    /**
     * Signs in with a Google ID token requested with the nonce [RiviumNonce.sha256] of
     * [rawNonce]. [rawNonce] (16-256 characters) is required: Rivium Auth refuses a
     * Google sign-in without it.
     */
    suspend fun signInWithGoogle(idToken: String, rawNonce: String): AuthResult =
        start(client().sendForSession("POST", "v1/google", social(idToken, rawNonce, null, null)))

    /** Adds Apple to the signed-in user. Throws [RiviumAuthErrorCode.PROVIDER_ALREADY_LINKED] when taken. */
    @JvmOverloads
    suspend fun linkApple(idToken: String, rawNonce: String, authorizationCode: String? = null, fullName: String? = null): AuthResult =
        link("v1/link/apple", social(idToken, rawNonce, authorizationCode, fullName))

    /**
     * Adds Google to the signed-in user. Throws [RiviumAuthErrorCode.PROVIDER_ALREADY_LINKED] when taken.
     * [rawNonce] works as in [signInWithGoogle].
     */
    suspend fun linkGoogle(idToken: String, rawNonce: String): AuthResult =
        link("v1/link/google", social(idToken, rawNonce, null, null))

    private fun social(idToken: String, nonce: String, code: String?, name: String?) =
        mapOf("idToken" to idToken, "nonce" to nonce, "authorizationCode" to code, "name" to name)

    // ---------------------------------------------------------------- E-mail and password

    /**
     * Creates an account. When the project requires a verified e-mail the user is
     * not signed in until they follow the link ([SignUpResult.signedIn] is false).
     */
    @JvmOverloads
    suspend fun signUp(email: String, password: String, redirectUrl: String? = null): SignUpResult {
        val json = client().send("POST", "v1/password/sign-up", mapOf("email" to email, "password" to password, "redirectUrl" to redirectUrl))
            ?: throw HttpClient.invalidResponse(null)
        val session = json.optJSONObject("session")?.let { HttpClient.parseSession(it) }
        if (session != null) start(session)
        return SignUpResult(
            userId = json.optString("userId"),
            verificationSent = json.optBoolean("verificationSent", false),
            signedIn = session != null,
        )
    }

    suspend fun signIn(email: String, password: String): AuthResult =
        start(client().sendForSession("POST", "v1/password/sign-in", mapOf("email" to email, "password" to password)))

    /** Gives the signed-in (typically anonymous) user an e-mail and password, keeping its id. */
    suspend fun linkPassword(email: String, password: String): AuthResult =
        link("v1/link/password", mapOf("email" to email, "password" to password))

    /** Sends a password-reset link. Succeeds whether or not the address has an account. */
    suspend fun sendPasswordReset(email: String, redirectUrl: String) {
        client().send("POST", "v1/password/forgot", mapOf("email" to email, "redirectUrl" to redirectUrl))
    }

    /** Sets a new password with the token from the reset link. Every session of the user ends. */
    suspend fun resetPassword(token: String, newPassword: String) {
        client().send("POST", "v1/password/reset", mapOf("token" to token, "password" to newPassword))
    }

    /** Confirms the e-mail with the token from the verification link. */
    suspend fun verifyEmail(token: String) {
        client().send("POST", "v1/email/verify", mapOf("token" to token))
    }

    /** Sends the signed-in user a new verification link. */
    suspend fun sendEmailVerification(redirectUrl: String) {
        authorized { http, token -> http.send("POST", "v1/email/send-verification", mapOf("redirectUrl" to redirectUrl), token) }
    }

    // ---------------------------------------------------------------- Magic link

    /** E-mails a sign-in link. Succeeds whether or not the address has an account. */
    suspend fun sendMagicLink(email: String, redirectUrl: String) {
        client().send("POST", "v1/magic-link/send", mapOf("email" to email, "redirectUrl" to redirectUrl))
    }

    /** Signs in with the token from a magic link (see [tokenFromLink]). The account is created on first use. */
    suspend fun signInWithMagicLink(token: String): AuthResult =
        start(client().sendForSession("POST", "v1/magic-link/consume", mapOf("token" to token)))

    // ---------------------------------------------------------------- Telegram

    /**
     * Asks for a sign-in code in Telegram. When [TelegramCodeResult.sent] is false,
     * open [TelegramCodeResult.botUrl] so the user starts the bot and shares their phone.
     *
     * @param phone International format, e.g. "+37491234567".
     */
    suspend fun requestTelegramCode(phone: String): TelegramCodeResult {
        val json = client().send("POST", "v1/telegram/code", mapOf("phone" to phone)) ?: throw HttpClient.invalidResponse(null)
        return TelegramCodeResult(
            sent = json.optBoolean("sent", false),
            botUrl = if (json.isNull("botUrl")) null else json.optString("botUrl").takeIf { it.isNotEmpty() },
            expiresIn = json.optInt("expiresIn", 300),
        )
    }

    suspend fun verifyTelegramCode(phone: String, code: String): AuthResult =
        start(client().sendForSession("POST", "v1/telegram/verify", mapOf("phone" to phone, "code" to code)))

    // ---------------------------------------------------------------- Project and user

    /** The sign-in methods this project offers. */
    suspend fun getMethods(): AuthMethods {
        val json = client().send("GET", "v1/methods") ?: throw HttpClient.invalidResponse(null)
        val list = json.optJSONArray("methods")
        return AuthMethods(
            methods = (0 until (list?.length() ?: 0)).map { list!!.getString(it) },
            telegramBot = if (json.isNull("telegramBot")) null else json.optString("telegramBot").takeIf { it.isNotEmpty() },
        )
    }

    /** Loads the signed-in user's profile (also kept as [currentUser]). */
    suspend fun me(): RiviumUser {
        val json = authorized { http, token -> http.send("GET", "v1/me", bearer = token) } ?: throw HttpClient.invalidResponse(null)
        val providers = json.optJSONArray("providers")
        val user = RiviumUser(
            id = json.getString("id"),
            isAnonymous = json.optBoolean("anonymous", false),
            emailVerified = json.optBoolean("emailVerified", false),
            createdAt = if (json.isNull("createdAt")) null else json.optString("createdAt"),
            providers = (0 until (providers?.length() ?: 0)).map { providers!!.getString(it) },
        )
        if (user.id == currentUserId) cachedUser = user
        return user
    }

    // ---------------------------------------------------------------- Sign-out and deletion

    /** Signs out on this device. Never fails: the session is forgotten even when offline. */
    suspend fun signOut() {
        val manager = session ?: return
        val http = http ?: return
        val refreshToken = manager.clear() ?: return
        try {
            http.send("POST", "v1/sign-out", mapOf("refreshToken" to refreshToken))
        } catch (e: RiviumAuthException) {
            // Best effort: the session is already gone on this device.
        }
    }

    /** Ends every session of the user, on every device, then signs out here. */
    suspend fun signOutEverywhere() {
        authorized { http, token -> http.send("POST", "v1/sign-out-everywhere", bearer = token) }
        sessionManager().clear()
    }

    /** Deletes the user and everything Rivium Auth holds about them, then signs out. */
    suspend fun deleteAccount() {
        authorized { http, token -> http.send("DELETE", "v1/me", bearer = token) }
        sessionManager().clear()
    }

    // ---------------------------------------------------------------- Internals

    private fun client(): HttpClient = http ?: throw RiviumAuthException.notConfigured()

    private fun sessionManager(): SessionManager = session ?: throw RiviumAuthException.notConfigured()

    /** Starts the session a sign-in returned and ends the one it replaces. */
    private fun start(tokens: SessionTokens): AuthResult {
        val http = client()
        val replaced = sessionManager().adopt(tokens)
        if (replaced != null) {
            // The previous session (for example the anonymous one just upgraded) is no
            // longer reachable from this device: end it on the server.
            background.launch {
                try {
                    http.send("POST", "v1/sign-out", mapOf("refreshToken" to replaced))
                } catch (e: RiviumAuthException) {
                    // Best effort.
                }
            }
        }
        return AuthResult(tokens.userId, tokens.created)
    }

    private suspend fun link(path: String, body: Map<String, String?>): AuthResult {
        val tokens = authorized { http, token -> http.sendForSession("POST", path, body, token) }
        return start(tokens)
    }

    /** Runs a signed-in call; when the server refuses the access token, gets a fresh one and tries once more. */
    private suspend fun <T> authorized(call: suspend (HttpClient, String) -> T): T {
        val http = client()
        val manager = sessionManager()
        val token = manager.validAccessToken() ?: throw RiviumAuthException.notSignedIn()
        return try {
            call(http, token)
        } catch (e: RiviumAuthException) {
            val refused = e.httpStatus == 401 &&
                (e.code == RiviumAuthErrorCode.UNAUTHENTICATED || e.code == RiviumAuthErrorCode.TOKEN_INVALID)
            if (!refused) throw e
            val fresh = manager.validAccessToken(forceRefresh = true) ?: throw RiviumAuthException.notSignedIn()
            call(http, fresh)
        }
    }
}
