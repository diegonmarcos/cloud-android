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
 * GET the artifact with a user-supplied credential and turn every failure
 * mode into a NAMED [Kind] instead of a blank state. The caller (app/) owns
 * the dialog and the apply step.
 *
 * FOUR IMPORT ROUTES, ONE CLIENT. Configs → Profile offers a pasted Authelia
 * bearer, an Authelia browser login, a GitHub login and a GitHub SSH key —
 * but three of those are the same GET with a different header, so they all
 * land in [request] and inherit one failure taxonomy. Only the SSH route is
 * genuinely different (it speaks git, not HTTP) and lives in the app module.
 *
 * Authelia auth is deliberately credential-in-a-header, NOT an in-app OAuth
 * dance: the token or session cookie is validated server-side by the existing
 * introspect-proxy / `@bearer` gate in front of the endpoint. So there is no
 * client secret here, no redirect URI, and nothing to keep in sync with
 * Authelia's client config.
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
        /** 401 from introspect-proxy (token present but bad/expired), OR a 3xx
         *  redirect to the Authelia login page (token missing/empty). Same
         *  cause, so the same name. */
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
        return request(
            url = endpoint(baseUrl, pathTemplate, user),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            secret = bearer,
            authHint = "Paste a current Authelia bearer token and try again.",
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
    }

    /**
     * Same artifact, same route, but authenticated by the session cookie a
     * browser login already established instead of a pasted token.
     *
     * This is the whole difference between the Bearer and the OWebAuth tiles:
     * Authelia accepts either, so the transport, the failure taxonomy and the
     * apply step are shared and only the header changes. The cookie is as
     * sensitive as the token and gets the same treatment — never logged, never
     * persisted, [redact]ed out of any echoed error body.
     */
    fun fetchWithCookie(
        baseUrl: String,
        pathTemplate: String,
        user: String,
        cookie: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Outcome {
        if (baseUrl.isBlank()) {
            return fail(Kind.MALFORMED, "No config source configured (build.json::ui.config_source.base_url is empty)")
        }
        return request(
            url = endpoint(baseUrl, pathTemplate, user),
            headers = mapOf("Cookie" to cookie),
            secret = cookie,
            authHint = "The browser session carried no Authelia cookie for this route — sign in again.",
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
    }

    /**
     * One GET, arbitrary headers, the same named-failure taxonomy.
     *
     * Public because the GitHub importers need it: fetching the artifact out of
     * a repo is the same problem as fetching it from the config route, and a
     * second HTTP client with a second set of half-considered error cases is
     * exactly what this object exists to prevent.
     *
     * [secret] is not sent anywhere — it is what [redact] scrubs out of logs
     * and displayed bodies, so pass whatever credential the headers carry.
     */
    fun request(
        url: String,
        headers: Map<String, String>,
        secret: String,
        authHint: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        accept: String = "application/json",
    ): Outcome {
        if (secret.isBlank()) {
            return fail(Kind.UNAUTHORIZED, "No credential supplied")
        }
        Log.i(TAG, "GET $url (credential ${secret.length} chars, not logged)")

        var conn: HttpURLConnection? = null
        val code: Int
        val body: String
        val location: String
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // NEVER follow redirects. The Authelia forward-auth gate answers
                // an unauthenticated request with `302 → auth.diegonmarcos.com/?rd=…`.
                // Following that fetches an HTML LOGIN PAGE with status 200, which
                // would surface as "malformed JSON" — or worse, look like success.
                // The redirect itself is the diagnosis, so we keep it.
                instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Cloud-SuperApp-ConfigSync/1")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            code = conn.responseCode
            location = conn.getHeaderField("Location").orEmpty()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (t: Throwable) {
            // UnknownHostException / SocketTimeoutException / SSLException /
            // ConnectException all land here — the request never completed.
            Log.w(TAG, "network failure for $url: ${t.javaClass.simpleName}: ${redact(t.message.orEmpty(), secret)}")
            return fail(
                Kind.NETWORK,
                "Could not reach $url — ${t.javaClass.simpleName}: ${redact(t.message ?: "no detail", secret)}",
            )
        } finally {
            conn?.disconnect()
        }

        val snippet = redact(body.take(300), secret)
        Log.i(TAG, "HTTP $code, ${body.length} bytes" + if (location.isBlank()) "" else ", Location=$location")

        return when {
            // A redirect AWAY from the API host is the Authelia login page.
            // Empirically: no/blank token → 302 to auth.diegonmarcos.com/?rd=…,
            // whereas a bogus token → a real 401 from introspect-proxy. Both mean
            // "not authenticated", so both must READ as that and not as a broken
            // response body.
            code in 300..399 && !redirectStaysOnHost(url, location) -> fail(
                Kind.UNAUTHORIZED,
                "HTTP $code — token missing or not accepted: the gateway redirected to a login page" +
                    (redirectHost(url, location)?.let { " at $it" } ?: "") +
                    ". $authHint",
            )
            // Same-host 3xx is not an auth failure — but we did not follow it, so
            // say exactly that rather than reporting an empty body.
            code in 300..399 -> fail(
                Kind.SERVER,
                "HTTP $code — the endpoint redirected to $location. Redirects are not followed; " +
                    "point ui.config_source at the final URL.",
            )
            code == 401 -> fail(
                Kind.UNAUTHORIZED,
                "HTTP 401 — credential rejected (bad or expired). $authHint\n$snippet",
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

    /** Host a `Location` header resolves to, relative URLs included. */
    private fun redirectHost(requestUrl: String, location: String): String? {
        if (location.isBlank()) return null
        return runCatching { URL(URL(requestUrl), location).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * True only when the redirect stays on the API host. A blank Location is
     * treated as leaving — an unusable 3xx is closer to "not authenticated"
     * than to "fine", and the Authelia gate is the only thing that redirects
     * this route in practice.
     */
    private fun redirectStaysOnHost(requestUrl: String, location: String): Boolean {
        val target = redirectHost(requestUrl, location) ?: return false
        val origin = runCatching { URL(requestUrl).host }.getOrNull().orEmpty()
        return target.equals(origin, ignoreCase = true)
    }

    /** Strip the credential out of anything we are about to log or display. */
    private fun redact(text: String, secret: String): String =
        if (secret.isBlank()) text else text.replace(secret, "«credential»")

    private fun fail(kind: Kind, message: String): Outcome.Failed {
        Log.w(TAG, "$kind: ${message.lineSequence().first()}")
        return Outcome.Failed(kind, message)
    }
}
