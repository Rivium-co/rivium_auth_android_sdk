package co.rivium.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the session: the refresh token (persisted) and the access token (memory
 * only). Refreshes are single-flight: callers that ask for a token at once wait
 * on one [Mutex], one refresh request goes out, and the rest reuse its result.
 */
internal class SessionManager(
    private val http: HttpClient,
    private val storage: SessionStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUserChange: (String?) -> Unit,
) {
    private val refreshMutex = Mutex()
    private val state = Any()

    private var refreshToken: String?
    private var userId: String?
    private var accessToken: String? = null
    private var accessExpiryMs = 0L
    /** Bumped whenever the session is replaced or ended. */
    private var generation = 0L
    /** Bumped whenever a new access token arrives. */
    private var tokenSeq = 0L

    val restoredUserId: String?

    init {
        val stored = storage.load()
        refreshToken = stored?.refreshToken
        userId = stored?.userId
        restoredUserId = stored?.userId
    }

    val currentUserId: String? get() = synchronized(state) { userId }

    /** Starts a session from a sign-in. Returns the refresh token it replaced, if any. */
    fun adopt(tokens: SessionTokens): String? {
        val previous: String?
        val changed: Boolean
        synchronized(state) {
            previous = refreshToken
            storage.save(StoredSession(tokens.refreshToken, tokens.userId))
            generation++
            tokenSeq++
            refreshToken = tokens.refreshToken
            accessToken = tokens.accessToken
            accessExpiryMs = clock() + tokens.expiresInSec * 1000
            changed = userId != tokens.userId
            userId = tokens.userId
        }
        if (changed) onUserChange(tokens.userId)
        return previous?.takeIf { it != tokens.refreshToken }
    }

    /** Ends the session locally. Returns its refresh token, if there was one. */
    fun clear(): String? {
        val previous: String?
        val changed: Boolean
        synchronized(state) {
            previous = refreshToken
            storage.clear()
            generation++
            refreshToken = null
            accessToken = null
            accessExpiryMs = 0L
            changed = userId != null
            userId = null
        }
        if (changed) onUserChange(null)
        return previous
    }

    private fun freshToken(): String? = synchronized(state) {
        accessToken?.takeIf { accessExpiryMs - clock() > REFRESH_SKEW_MS }
    }

    /**
     * A valid access token, refreshed when missing or within two minutes of
     * expiry. Null when nobody is signed in or the session just ended (the
     * user is then signed out). Throws on network and server errors; the
     * session is kept.
     */
    suspend fun validAccessToken(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) freshToken()?.let { return it }
        val seqBefore = synchronized(state) { tokenSeq }
        return refreshMutex.withLock {
            // Someone refreshed while this caller waited for the lock: reuse it.
            val seqNow = synchronized(state) { tokenSeq }
            if (seqNow != seqBefore || !forceRefresh) freshToken()?.let { return@withLock it }
            val (sent, gen) = synchronized(state) { refreshToken to generation }
            if (sent == null) return@withLock null
            // A refresh burns the old token on the server: finish and save it
            // even if the caller is cancelled meanwhile.
            withContext(NonCancellable) { refresh(sent, gen, retried = false) }
        }
    }

    private suspend fun refresh(sent: String, gen: Long, retried: Boolean): String? {
        try {
            val tokens = http.sendForSession("POST", "v1/token/refresh", mapOf("refreshToken" to sent))
            var changed = false
            synchronized(state) {
                if (generation != gen) return accessToken // Replaced meanwhile; keep the newer session.
                // Persist the rotated refresh token first: the old one is already burned.
                storage.save(StoredSession(tokens.refreshToken, tokens.userId))
                tokenSeq++
                refreshToken = tokens.refreshToken
                accessToken = tokens.accessToken
                accessExpiryMs = clock() + tokens.expiresInSec * 1000
                changed = userId != tokens.userId
                userId = tokens.userId
            }
            if (changed) onUserChange(tokens.userId)
            return tokens.accessToken
        } catch (e: RiviumAuthException) {
            if (synchronized(state) { generation != gen }) return synchronized(state) { accessToken }
            if (e.endsSession) {
                // Another process of this app may have rotated the token already: use its newer one once.
                val stored = storage.load()
                if (!retried && e.code == RiviumAuthErrorCode.TOKEN_INVALID && stored != null && stored.refreshToken != sent) {
                    synchronized(state) { refreshToken = stored.refreshToken }
                    return refresh(stored.refreshToken, gen, retried = true)
                }
                clear()
                return null
            }
            // Offline or a server hiccup: an access token that has not actually expired still works.
            synchronized(state) { accessToken?.takeIf { accessExpiryMs > clock() } }?.let { return it }
            throw e
        }
    }

    companion object {
        /** Refresh this long before the access token actually expires. */
        const val REFRESH_SKEW_MS = 120_000L
    }
}
