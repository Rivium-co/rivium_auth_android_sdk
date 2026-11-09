package co.rivium.auth.ui

import co.rivium.auth.RiviumAuth
import co.rivium.auth.MemorySessionStorage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A fake Rivium Auth server for the sign-in screen. */
class FakeAuthServer(var methods: List<String>, var telegramBot: String? = null) {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<Pair<RecordedRequest, JSONObject>>()

    /** When set, every call except `/v1/methods` waits for it (a slow server). */
    @Volatile var gate: CountDownLatch? = null

    /** `/v1/methods` fails while true. */
    @Volatile var methodsDown = false

    /** Answers for POST paths; anything else 404. */
    val answers = java.util.concurrent.ConcurrentHashMap<String, (JSONObject) -> MockResponse>()

    private val n = AtomicInteger()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = request.body.readUtf8()
                val body = if (text.isBlank()) JSONObject() else JSONObject(text)
                requests += request to body
                val path = request.path!!.substringBefore('?')
                if (path == "/v1/methods") {
                    if (methodsDown) return MockResponse().setResponseCode(503).setBody("{\"code\":\"INTERNAL\",\"message\":\"down\"}")
                    return json(JSONObject().put("methods", JSONArray(methods)).put("telegramBot", telegramBot ?: JSONObject.NULL))
                }
                gate?.await(10, TimeUnit.SECONDS)
                return answers[path]?.invoke(body) ?: err("NOT_FOUND", 404)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun session(userId: String): MockResponse {
        val i = n.incrementAndGet()
        return json(
            JSONObject().put("accessToken", "access-$i").put("expiresIn", 3600)
                .put("refreshToken", "refresh-$i-xxxxxxxxxxxxxxxxxxxx").put("userId", userId),
        )
    }

    fun bodies(path: String): List<JSONObject> = requests.filter { it.first.path!!.substringBefore('?') == path }.map { it.second }
    fun calls(path: String): List<RecordedRequest> = requests.filter { it.first.path!!.substringBefore('?') == path }.map { it.first }

    fun client(): RiviumAuth = RiviumAuth().apply {
        configure("rv_live_test", server.url("/").toString(), OkHttpClient(), MemorySessionStorage())
    }

    fun shutdown() = server.shutdown()

    companion object {
        fun json(body: Any, status: Int = 200): MockResponse = MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body.toString())

        fun err(code: String, status: Int): MockResponse =
            json(JSONObject().put("code", code).put("message", "hint for developers"), status)
    }
}
