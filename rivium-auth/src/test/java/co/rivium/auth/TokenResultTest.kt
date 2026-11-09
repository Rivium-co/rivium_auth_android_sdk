package co.rivium.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** getTokenResult: Firebase's GetTokenResult, from a token shaped like the ones Rivium signs. */
class TokenResultTest {
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun jwt(payload: JSONObject) = "${b64("""{"alg":"ES256","typ":"JWT"}""")}.${b64(payload.toString())}.c2ln"
    private val now = 1_793_000_000L
    private fun standard() = JSONObject(
        """{"iss":"rivium","aud":"rivium_user","sub":"ru_user1","pid":"p1","iat":$now,"exp":${now + 3600},
           "auth_time":${now - 86400},"amr":["google"],"meta":{"pid":"p1","srv":true,"iat":$now}}""",
    )

    @Test
    fun decodesEveryFieldFlatWithCustomClaimsAndNoMeta() {
        val p = standard().put("plan", "pro").put("team", JSONObject().put("id", "t1").put("tags", org.json.JSONArray().put("red")))
            .put("نقش", "مدیر 👋").put("note", JSONObject.NULL)
        val r = RiviumTokenResult.fromToken(jwt(p))!!
        assertEquals("pro", r.claims["plan"])
        assertEquals(mapOf("id" to "t1", "tags" to listOf("red")), r.claims["team"])
        assertEquals("مدیر 👋", r.claims["نقش"])
        assertNull(r.claims["note"])
        assertEquals("ru_user1", r.claims["sub"])
        assertFalse(r.claims.containsKey("meta"))
        assertEquals(now + 3600, r.expirationTimestamp)
        assertEquals(now, r.issuedAtTimestamp)
        assertEquals(now - 86400, r.authTimestamp)
        assertEquals("google", r.signInProvider)
    }

    @Test
    fun brokenTokensGiveNullAndOldTokensFallBackToIssueTime() {
        assertNull(RiviumTokenResult.fromToken("one-part"))
        assertNull(RiviumTokenResult.fromToken("a.!!!.c"))
        assertNull(RiviumTokenResult.fromToken(jwt(JSONObject().put("sub", "x"))))
        val old = standard().apply { remove("auth_time") }
        assertEquals(now, RiviumTokenResult.fromToken(jwt(old))!!.authTimestamp)
    }

    @Test
    fun fromTheClientForceRefreshGetsNewClaimsAtOnce() = runBlocking {
        var plan = "free"
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val iat = System.currentTimeMillis() / 1000
                val token = jwt(standard().put("iat", iat).put("exp", iat + 3600).put("plan", plan))
                return MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"accessToken":"$token","expiresIn":3600,"expiresAt":"2099-01-01T00:00:00.000Z","refreshToken":"refresh-1-xxxxxxxxxxxxxxxx","userId":"ru_user1"}""",
                )
            }
        }
        server.start()
        try {
            val auth = RiviumAuth()
            auth.configure("rv_live_test", server.url("/").toString(), OkHttpClient(), MemorySessionStorage(null))
            assertNull(auth.getTokenResult())
            auth.signInAnonymously()
            assertEquals("free", auth.getTokenResult()!!.claims["plan"])
            plan = "pro" // the developer's server changed the claims
            assertEquals("free", auth.getTokenResult()!!.claims["plan"]) // the same token until it is refreshed
            assertEquals("pro", auth.getTokenResult(forceRefresh = true)!!.claims["plan"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun nestedCustomClaimsFromTheRiviumServerComeOutByNameAndTokenFieldsWin() {
        val p = standard().apply { remove("auth_time") }
            .put("claims", JSONObject().put("levels", org.json.JSONArray().put("A1")).put("sub", "someone-else"))
        val r = RiviumTokenResult.fromToken(jwt(p))!!
        assertEquals(listOf("A1"), r.claims["levels"])
        assertEquals("ru_user1", r.claims["sub"])
        assertFalse(r.claims.containsKey("claims"))
        assertEquals(now, r.authTimestamp)
    }
}
