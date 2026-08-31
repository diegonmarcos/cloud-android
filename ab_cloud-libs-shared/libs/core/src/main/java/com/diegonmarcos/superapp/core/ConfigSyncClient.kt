package com.diegonmarcos.superapp.core

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud config-artifact fetcher — the network half of
 * Configs → Profile → "OWebAuth Authelia Authentication · Auto Import Configs".
 *
 * Engine only: no `R`, no Fragment, no prefs. It does exactly one thing —
 * GET the artifact with a user-supplied bearer and turn every failure mode
 * into a NAMED [Kind] instead of a blank state. The caller (app/) owns the
 * dialog and the apply step.
 *
 * Auth model is deliberately "paste-a-bearer", NOT an in-app OAuth dance:
 * the pasted Authelia token IS the `Authorization: Bearer …` value, and it
 * is validated server-side by the existing introspect-proxy / `@bearer`
 * gate in front of the endpoint. So there is no client secret here, no
 * redirect URI, and nothing to keep in sync with Authelia's client config.
 *
 * SECRET HYGIENE — the token must never reach a log or a diagnostic file:
 *  • the Authorization header value is never logged;
 *  • [redact] scrubs the token out of any server-supplied error body before
 *    it is logged or shown, in case the endpoint echoes it back.
 *
 * Diagnose with:  adb logcat -s ConfigSync
 */
object ConfigSyncClient {

    /** Stable logcat tag. `logcat -s ConfigSync` shows the whole exchange. */
    const val TAG = "ConfigSync"

    /** Named failure modes. A blank/unknown state is not one of them. */
    enum class Kind {
        /** 401 — token missing, malformed, or expired. */
        UNAUTHORIZED,
        /** 403 — token is valid but lacks the audience/scope this route needs. */
        FORBIDDEN,
        /** 404 — endpoint not published (yet) at this base URL + user slug. */
        NOT_FOUND,
        /** 5xx — the config service itself is down/erroring. */
        SERVER,
        /** DNS / TCP / TLS / timeout — never reached the service. */
        NETWORK,
        /** Reached it, got 2xx, but the body is not the JSON we contracted for. */
        MALFORMED,
    }

    sealed class Outcome {
        /** 2xx + parseable JSON object. [bytes] is body length, for the UI report. */
        data class Ok(val body: JSONObject, val bytes: Int) : Outcome()
        data class Failed(val kind: Kind, val message: String) : Outcome()
    }

    /**
     * Fetch the artifact. Blocking — call from a background dispatcher.
     *
     * @param baseUrl        e.g. `https://api.diegonmarcos.com` (build.json::ui.config_source.base_url)
     * @param pathTemplate   e.g. `/pub/superapp/config/{user}`   ({user} is substituted)
     * @param user           user slug, e.g. `diego`
     * @param bearer         the pasted Authelia token, verbatim
     */
    fun fetch(
        baseUrl: String,
        pathTemplate: String,
        user: String,
        bearer: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Outcome {
        if (baseUrl.isBlank()) {
            return fail(Kind.MALFORMED, "No config source configured (build.json::ui.config_source.base_url is empty)")
        }
        if (bearer.isBlank()) {
            return fail(Kind.UNAUTHORIZED, "No token supplied")
        }
        val url = endpoint(baseUrl, pathTemplate, user)
        Log.i(TAG, "GET $url (token ${bearer.length} chars, not logged)")

        var conn: HttpURLConnection? = null
        val code: Int
        val body: String
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Cloud-SuperApp-ConfigSync/1")
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (t: Throwable) {
            // UnknownHostException / SocketTimeoutException / SSLException /
            // ConnectException all land here — the request never completed.
            Log.w(TAG, "network failure for $url: ${t.javaClass.simpleName}: ${redact(t.message.orEmpty(), bearer)}")
            return fail(
                Kind.NETWORK,
                "Could not reach $url — ${t.javaClass.simpleName}: ${redact(t.message ?: "no detail", bearer)}",
            )
        } finally {
            conn?.disconnect()
        }

        val snippet = redact(body.take(300), bearer)
        Log.i(TAG, "HTTP $code, ${body.length} bytes")

        return when {
            code == 401 -> fail(
                Kind.UNAUTHORIZED,
                "HTTP 401 — token rejected (bad or expired). Get a fresh Authelia token and paste it again.\n$snippet",
            )
            code == 403 -> fail(
                Kind.FORBIDDEN,
                "HTTP 403 — token accepted but not allowed here (missing audience/scope for this route).\n$snippet",
            )
            code == 404 -> fail(
                Kind.NOT_FOUND,
                "HTTP 404 — no config published at $url. Check ui.config_source.user in build.json, or the endpoint is not live yet.\n$snippet",
            )
            code >= 500 -> fail(Kind.SERVER, "HTTP $code — config service error.\n$snippet")
            code !in 200..299 -> fail(Kind.SERVER, "HTTP $code — unexpected status.\n$snippet")
            body.isBlank() -> fail(Kind.MALFORMED, "HTTP $code but the body was empty.")
            else -> try {
                Outcome.Ok(JSONObject(body), body.length)
            } catch (t: Throwable) {
                fail(Kind.MALFORMED, "HTTP $code but the body is not a JSON object: ${t.message}\n$snippet")
            }
        }
    }

    /** `base` + `path` with `{user}` substituted; tolerant of stray slashes. */
    fun endpoint(baseUrl: String, pathTemplate: String, user: String): String {
        val base = baseUrl.trimEnd('/')
        val path = pathTemplate.replace("{user}", user).let { if (it.startsWith("/")) it else "/$it" }
        return base + path
    }

    /** Strip the bearer out of anything we are about to log or display. */
    private fun redact(text: String, bearer: String): String =
        if (bearer.isBlank()) text else text.replace(bearer, "«token»")

    private fun fail(kind: Kind, message: String): Outcome.Failed {
        Log.w(TAG, "$kind: ${message.lineSequence().first()}")
        return Outcome.Failed(kind, message)
    }
}
