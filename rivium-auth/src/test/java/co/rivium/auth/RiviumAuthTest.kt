package co.rivium.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RiviumAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var storage: MemorySessionStorage
    private lateinit var auth: RiviumAuth
    private val requests = CopyOnWriteArrayList<Pair<String, RecordedRequest>>()
    private var handler: (path: String, body: JSONObject, req: RecordedRequest) -> MockResponse = { _, _, _ -> MockResponse().setResponseCode(500) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                requests += path to request
                val text = request.body.readUtf8()
                val body = if (text.isBlank()) JSONObject() else JSONObject(text)
                bodies[request] = body
                return handler(path, body, request)
            }
        }
        server.start()
    }

    private val bodies = java.util.concurrent.ConcurrentHashMap<RecordedRequest, JSONObject>()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun make(restored: StoredSession? = null) {
        storage = MemorySessionStorage(restored)
        auth = RiviumAuth()
        auth.configure("rv_live_test", server.url("/").toString(), OkHttpClient(), storage)
    }

    private fun calls(path: String) = requests.filter { it.first == path }.map { it.second }
    private fun bodyOf(req: RecordedRequest) = bodies[req]!!

    private fun json(status: Int, body: String, delayMs: Long = 0) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
        .apply { if (delayMs > 0) setBodyDelay(delayMs, TimeUnit.MILLISECONDS) }

    private fun session(access: String, refresh: String, user: String = "user-1", expiresIn: Int = 3600, delayMs: Long = 0) =
        json(200, """{"accessToken":"$access","expiresIn":$expiresIn,"expiresAt":"2026-10-16T12:00:00.000Z","refreshToken":"$refresh","userId":"$user"}""", delayMs)

    // ------------------------------------------------------------ Sign-in

    @Test
    fun signInStoresTokens() = runBlocking {
        handler = { path, _, _ -> if (path == "/v1/anonymous") session("acc-1", "ref-1", "u-anon") else MockResponse().setResponseCode(500) }
        make()

        val result = auth.signInAnonymously()

        assertEquals("u-anon", result.userId)
        assertEquals(StoredSession("ref-1", "u-anon"), storage.load())
        assertEquals("u-anon", auth.currentUserId)
        assertEquals("u-anon", auth.authState.value)
        assertEquals("acc-1", auth.getAccessToken())
        assertEquals("a fresh access token is served from memory", 1, requests.size)
        assertEquals("rv_live_test", requests[0].second.getHeader("x-api-key"))
    }

    @Test
    fun restoredSessionIsSignedInWithoutNetwork() {
        make(StoredSession("ref-0", "u-1"))
        assertEquals("u-1", auth.currentUserId)
        assertTrue(requests.isEmpty())
    }

    // ------------------------------------------------------------ Single-flight refresh

    @Test
    fun concurrentTokenRequestsShareOneRefresh() = runBlocking {
        handler = { path, _, _ -> if (path == "/v1/token/refresh") session("acc-2", "ref-2", "u-1", delayMs = 300) else MockResponse().setResponseCode(500) }
        make(StoredSession("ref-1", "u-1"))

        val tokens = coroutineScope {
            (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) { auth.getAccessToken() } }.awaitAll()
        }

        assertEquals(List(8) { "acc-2" }, tokens)
        assertEquals(1, calls("/v1/token/refresh").size)
    }

    @Test
    fun allProviderShapesShareOneRefresh() = runBlocking {
        handler = { path, _, _ -> if (path == "/v1/token/refresh") session("acc-2", "ref-2", "u-1", delayMs = 200) else MockResponse().setResponseCode(500) }
        make(StoredSession("ref-1", "u-1"))
        // The exact types Rivium Push, Chat and Sync take.
        val push: suspend () -> String? = auth.tokenProvider
        val chat: suspend () -> String = auth.strictTokenProvider
        val sync: () -> String? = auth.blockingTokenProvider

        val results = coroutineScope {
            listOf(
                async(kotlinx.coroutines.Dispatchers.IO) { push() },
                async(kotlinx.coroutines.Dispatchers.IO) { chat() },
                async(kotlinx.coroutines.Dispatchers.IO) { sync() },
            ).awaitAll()
        }

        assertEquals(listOf("acc-2", "acc-2", "acc-2"), results)
        assertEquals(1, calls("/v1/token/refresh").size)
    }

    // ------------------------------------------------------------ Rotation

    @Test
    fun refreshRotationSavesNewRefreshToken() = runBlocking {
        var issued = 1
        handler = { path, _, _ ->
            if (path == "/v1/token/refresh") {
                issued++
                session("acc-$issued", "ref-$issued", "u-1")
            } else MockResponse().setResponseCode(500)
        }
        make(StoredSession("ref-1", "u-1"))

        assertEquals("acc-2", auth.getAccessToken())
        assertEquals("ref-2", storage.load()?.refreshToken)
        assertEquals("acc-3", auth.getAccessToken(forceRefresh = true))
        assertEquals("ref-3", storage.load()?.refreshToken)

        val sent = calls("/v1/token/refresh").map { bodyOf(it).getString("refreshToken") }
        assertEquals("each refresh presents the newest token", listOf("ref-1", "ref-2"), sent)
    }

    @Test
    fun refreshesBeforeExpiry() = runBlocking {
        handler = { path, _, _ ->
            when (path) {
                "/v1/anonymous" -> session("acc-short", "ref-1", expiresIn = 100)
                "/v1/token/refresh" -> session("acc-new", "ref-2")
                else -> MockResponse().setResponseCode(500)
            }
        }
        make()
        auth.signInAnonymously()

        // 100 s left is inside the 2-minute window: refresh now.
        assertEquals("acc-new", auth.getAccessToken())
        assertEquals(1, calls("/v1/token/refresh").size)
    }

    // ------------------------------------------------------------ Session end

    @Test
    fun revokedSessionSignsOut() = runBlocking {
        handler = { path, _, _ ->
            if (path == "/v1/token/refresh") json(401, """{"code":"SESSION_REVOKED","message":"Session ended, sign in again"}""")
            else MockResponse().setResponseCode(500)
        }
        make(StoredSession("ref-1", "u-1"))
        val seen = CopyOnWriteArrayList<String?>()
        auth.addAuthStateListener { seen += it }

        assertNull(auth.getAccessToken())
        assertNull(storage.load())
        assertNull(auth.currentUserId)
        assertNull(auth.authState.value)
        assertEquals(listOf("u-1", null), seen.toList())
    }

    @Test
    fun strictProviderThrowsWhenSignedOut() = runBlocking {
        make()
        try {
            auth.strictTokenProvider()
            fail("expected NOT_SIGNED_IN")
        } catch (e: RiviumAuthException) {
            assertEquals(RiviumAuthErrorCode.NOT_SIGNED_IN, e.code)
        }
        assertNull(auth.tokenProvider())
        assertNull(auth.blockingTokenProvider())
    }

    @Test
    fun networkErrorKeepsSession() = runBlocking {
        make(StoredSession("ref-1", "u-1"))
        server.shutdown()
        try {
            auth.getAccessToken()
            fail("expected a network error")
        } catch (e: RiviumAuthException) {
            assertEquals(RiviumAuthErrorCode.NETWORK_ERROR, e.code)
        }
        assertEquals("ref-1", storage.load()?.refreshToken)
        assertEquals("u-1", auth.currentUserId)
    }

    @Test
    fun signOutClearsLocallyAndEndsServerSession() = runBlocking {
        handler = { path, _, _ -> if (path == "/v1/anonymous") session("a", "ref-1") else MockResponse().setResponseCode(204) }
        make()
        auth.signInAnonymously()
        auth.signOut()

        assertNull(storage.load())
        assertNull(auth.currentUserId)
        assertEquals("ref-1", bodyOf(calls("/v1/sign-out").single()).getString("refreshToken"))
    }

    // ------------------------------------------------------------ Linking and errors

    @Test
    fun linkProviderAlreadyLinkedIsTyped() = runBlocking {
        handler = { path, _, _ ->
            when (path) {
                "/v1/anonymous" -> session("acc-1", "ref-1", "u-anon")
                "/v1/link/google" -> json(409, """{"code":"PROVIDER_ALREADY_LINKED","message":"This account is already used by another user"}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        make()
        auth.signInAnonymously()
        val nonce = RiviumNonce.generate()

        try {
            auth.linkGoogle("id", nonce)
            fail("expected PROVIDER_ALREADY_LINKED")
        } catch (e: RiviumAuthException) {
            assertTrue(e.isProviderAlreadyLinked)
            assertEquals(409, e.httpStatus)
        }
        assertEquals("u-anon", auth.currentUserId)
        assertEquals("ref-1", storage.load()?.refreshToken)
        assertEquals("Bearer acc-1", calls("/v1/link/google").single().getHeader("Authorization"))
        assertEquals(nonce, bodyOf(calls("/v1/link/google").single()).getString("nonce"))
    }

    @Test
    fun googleSignInSendsTheRawNonce() = runBlocking {
        handler = { path, _, _ ->
            if (path == "/v1/google") session("acc-1", "ref-1", "u-g") else MockResponse().setResponseCode(500)
        }
        make()
        val raw = RiviumNonce.generate()
        val result = auth.signInWithGoogle("g.id.token", raw)

        assertEquals("u-g", result.userId)
        val body = bodyOf(calls("/v1/google").single())
        assertEquals("g.id.token", body.getString("idToken"))
        assertEquals(raw, body.getString("nonce"))
    }

    @Test
    fun linkReplacesSessionAndEndsTheOldOne() = runBlocking {
        handler = { path, _, _ ->
            when (path) {
                "/v1/anonymous" -> session("acc-1", "ref-anon", "u-1")
                "/v1/link/apple" -> session("acc-2", "ref-apple", "u-1")
                else -> MockResponse().setResponseCode(204)
            }
        }
        make()
        auth.signInAnonymously()
        val result = auth.linkApple("id", "n0123456789abcdef", authorizationCode = "code")

        assertEquals("u-1", result.userId)
        assertEquals("ref-apple", storage.load()?.refreshToken)
        val body = bodyOf(calls("/v1/link/apple").single())
        assertEquals("n0123456789abcdef", body.getString("nonce"))
        assertEquals("code", body.getString("authorizationCode"))
        assertFalse("null fields are left out", body.has("name"))

        repeat(50) { if (calls("/v1/sign-out").isEmpty()) delay(20) }
        assertEquals("ref-anon", bodyOf(calls("/v1/sign-out").single()).getString("refreshToken"))
    }

    @Test
    fun rateLimitedCarriesRetryAfter() = runBlocking {
        handler = { _, _, _ -> json(429, """{"code":"RATE_LIMITED","message":"Too many attempts","retryAfterSec":42}""") }
        make()
        try {
            auth.signIn("a@b.co", "secret-pass")
            fail("expected RATE_LIMITED")
        } catch (e: RiviumAuthException) {
            assertEquals(RiviumAuthErrorCode.RATE_LIMITED, e.code)
            assertEquals(42, e.retryAfterSec)
        }
    }

    @Test
    fun signedInCallRetriesOnceWithFreshToken() = runBlocking {
        handler = { path, _, req ->
            when (path) {
                "/v1/anonymous" -> session("stale", "ref-1", "u-1")
                "/v1/token/refresh" -> session("fresh", "ref-2", "u-1")
                "/v1/me" ->
                    if (req.getHeader("Authorization") == "Bearer stale") json(401, """{"code":"UNAUTHENTICATED","message":"Sign-in required"}""")
                    else json(200, """{"id":"u-1","anonymous":true,"emailVerified":false,"createdAt":"2026-10-16T10:00:00.000Z","providers":[]}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        make()
        auth.signInAnonymously()
        val user = auth.me()

        assertEquals("u-1", user.id)
        assertTrue(user.isAnonymous)
        assertEquals(2, calls("/v1/me").size)
        assertEquals(user, auth.currentUser)
    }

    @Test
    fun signUpWithoutSessionStaysSignedOut() = runBlocking {
        handler = { _, _, _ -> json(200, """{"userId":"u-9","verificationSent":true,"session":null}""") }
        make()
        val result = auth.signUp("a@b.co", "long-password-1", "myapp://verify")
        assertEquals(SignUpResult("u-9", verificationSent = true, signedIn = false), result)
        assertNull(auth.currentUserId)
    }

    @Test
    fun methodsAndTelegram() = runBlocking {
        handler = { path, _, _ ->
            when (path) {
                "/v1/methods" -> json(200, """{"methods":["anonymous","telegram"],"telegramBot":"my_bot"}""")
                "/v1/telegram/code" -> json(200, """{"sent":false,"botUrl":"https://t.me/my_bot?start=signin","expiresIn":300}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        make()
        val methods = auth.getMethods()
        assertTrue(methods.isEnabled("telegram"))
        assertEquals("my_bot", methods.telegramBot)
        val code = auth.requestTelegramCode("+37491234567")
        assertFalse(code.sent)
        assertEquals("https://t.me/my_bot?start=signin", code.botUrl)
    }

    @Test
    fun notConfigured() = runBlocking {
        try {
            RiviumAuth().signInAnonymously()
            fail("expected SDK_NOT_CONFIGURED")
        } catch (e: RiviumAuthException) {
            assertEquals(RiviumAuthErrorCode.SDK_NOT_CONFIGURED, e.code)
        }
    }

    // ------------------------------------------------------------ Helpers

    @Test
    fun nonce() {
        val raw = RiviumNonce.generate()
        assertEquals(32, raw.length)
        assertNotEquals(raw, RiviumNonce.generate())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RiviumNonce.sha256("abc"))
    }

    @Test
    fun tokenFromLink() {
        assertEquals("abc 123", RiviumAuth.tokenFromLink("myapp://auth?x=1&token=abc%20123#f"))
        assertNull(RiviumAuth.tokenFromLink("myapp://auth"))
    }

    // ------------------------------------------------------------ SDK header

    @Test
    fun everyRequestSendsSdkHeader() = runBlocking {
        handler = { path, _, _ -> if (path == "/v1/anonymous") session("acc-1", "ref-1", "u-anon") else MockResponse().setResponseCode(204) }
        make()
        auth.signInAnonymously()
        auth.signOut()

        assertTrue(requests.size >= 2)
        requests.forEach { (path, req) -> assertEquals(path, "auth-android/0.1.1", req.getHeader("X-Rivium-SDK")) }
    }

    @Test
    fun versionMatchesGradleProperties() {
        val file = listOf(java.io.File("../gradle.properties"), java.io.File("gradle.properties")).first { it.exists() }
        val props = java.util.Properties().apply { file.inputStream().use { load(it) } }
        assertEquals(props.getProperty("VERSION_NAME"), SDK_VERSION)
        val buildFile = java.io.File(file.parentFile, "rivium-auth/build.gradle.kts").readText()
        assertTrue("build.gradle.kts fallback", buildFile.contains("?: \"$SDK_VERSION\")"))
        assertEquals("auth-android/$SDK_VERSION", SDK_HEADER)
    }
}
