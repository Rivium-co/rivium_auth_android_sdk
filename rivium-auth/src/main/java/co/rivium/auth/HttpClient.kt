package co.rivium.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Sends requests to Rivium Auth and turns error bodies into [RiviumAuthException]. */
internal class HttpClient(
    baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun send(
        method: String,
        path: String,
        body: Map<String, String?>? = null,
        bearer: String? = null,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val requestBody = body?.let { map ->
            val json = JSONObject()
            map.forEach { (k, v) -> if (v != null) json.put(k, v) }
            json.toString().toRequestBody(JSON)
        }
        val request = Request.Builder()
            .url("$baseUrl/$path")
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .method(method, requestBody ?: if (method == "POST") EMPTY.toRequestBody(JSON) else null)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw RiviumAuthException(RiviumAuthErrorCode.NETWORK_ERROR, "Could not reach Rivium Auth", cause = e)
        }
        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw RiviumAuthException(RiviumAuthErrorCode.NETWORK_ERROR, "Could not reach Rivium Auth", cause = e)
            }
            if (!it.isSuccessful) throw error(it.code, text, it.header("Retry-After"))
            if (text.isBlank()) return@withContext null
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw invalidResponse(e)
            }
        }
    }

    suspend fun sendForSession(method: String, path: String, body: Map<String, String?>? = null, bearer: String? = null): SessionTokens =
        parseSession(send(method, path, body, bearer) ?: throw invalidResponse(null))

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val EMPTY = "{}"

        fun invalidResponse(cause: Throwable?) =
            RiviumAuthException(RiviumAuthErrorCode.INVALID_RESPONSE, "Unexpected response from Rivium Auth", cause = cause)

        fun parseSession(json: JSONObject): SessionTokens = try {
            SessionTokens(
                accessToken = json.getString("accessToken"),
                expiresInSec = json.getLong("expiresIn"),
                refreshToken = json.getString("refreshToken"),
                userId = json.getString("userId"),
                created = if (json.has("created") && !json.isNull("created")) json.getBoolean("created") else null,
            )
        } catch (e: JSONException) {
            throw invalidResponse(e)
        }

        fun error(status: Int, text: String, retryAfterHeader: String?): RiviumAuthException {
            val json = try { JSONObject(text) } catch (e: JSONException) { null }
            val code = json?.optString("code")?.takeIf { it.isNotEmpty() }
                ?: if (status >= 500) RiviumAuthErrorCode.INTERNAL else RiviumAuthErrorCode.INVALID_RESPONSE
            val fields = json?.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.optString(it) } }
            val retry = json?.takeIf { it.has("retryAfterSec") }?.optInt("retryAfterSec")
                ?: retryAfterHeader?.toIntOrNull()
            return RiviumAuthException(
                code = code,
                message = json?.optString("message")?.takeIf { it.isNotEmpty() } ?: "Request failed ($status)",
                httpStatus = status,
                retryAfterSec = retry,
                fields = fields,
            )
        }
    }
}
