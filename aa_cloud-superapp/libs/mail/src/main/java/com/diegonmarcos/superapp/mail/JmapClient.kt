package com.diegonmarcos.superapp.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Minimal JMAP client — speaks just enough RFC 8620 to fetch the session
 * descriptor. No third-party lib; everything is HttpURLConnection + the
 * AndroidX/Kotlin stdlib. This is Slice A — login + session display.
 *
 * Layered work after this:
 *   - Slice B: Mailbox/get  (list inboxes/folders)
 *   - Slice C: Email/query + Email/get  (paginated message list)
 *   - Slice D: EventSource push channel
 *   - Slice E: EmailSubmission/set  (compose + send)
 */
class JmapClient(private val server: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Result of a successful session discovery. */
    data class Session(
        val apiUrl: String,
        val downloadUrl: String,
        val uploadUrl: String,
        val eventSourceUrl: String,
        val primaryAccountId: String,
        val accounts: Map<String, String>,   // id → name
        val raw: JsonElement,                // full session JSON for power users
    )

    sealed class Result {
        data class Success(val session: Session) : Result()
        data class HttpError(val code: Int, val body: String) : Result()
        data class NetworkError(val message: String) : Result()
    }

    /**
     * Discovery: GET <server>/.well-known/jmap with Basic auth → session JSON
     * (per RFC 8620 §2). The server may redirect to /jmap/session; HttpURLConnection
     * follows that automatically when instanceFollowRedirects=true.
     */
    fun discover(email: String, password: String): Result {
        val url = URL("${server.trimEnd('/')}/.well-known/jmap")
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", basicAuth(email, password))
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            if (code !in 200..299) return Result.HttpError(code, body)

            val tree = json.parseToJsonElement(body).jsonObject
            val primary = tree["primaryAccounts"]?.jsonObject
                ?.get("urn:ietf:params:jmap:mail")?.jsonPrimitive?.content
                ?: return Result.HttpError(code, "no primaryAccounts.mail in session JSON")
            val accountsObj = tree["accounts"]?.jsonObject ?: emptyMap()
            val accounts = accountsObj.mapValues { (_, v) ->
                v.jsonObject["name"]?.jsonPrimitive?.content ?: "(unnamed)"
            }
            Result.Success(
                Session(
                    apiUrl          = tree["apiUrl"]?.jsonPrimitive?.content ?: "",
                    downloadUrl     = tree["downloadUrl"]?.jsonPrimitive?.content ?: "",
                    uploadUrl       = tree["uploadUrl"]?.jsonPrimitive?.content ?: "",
                    eventSourceUrl  = tree["eventSourceUrl"]?.jsonPrimitive?.content ?: "",
                    primaryAccountId = primary,
                    accounts        = accounts,
                    raw             = tree,
                )
            )
        } catch (t: Throwable) {
            Result.NetworkError(t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        fun basicAuth(user: String, password: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    }
}
