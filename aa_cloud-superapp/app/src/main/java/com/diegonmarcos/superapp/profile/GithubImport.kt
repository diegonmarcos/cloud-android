package com.diegonmarcos.superapp.profile

import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.ConfigSyncClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub half of Configs → Profile → Config import.
 *
 * Two of the four import tiles come through here, and they differ ONLY in how
 * the credential is obtained:
 *
 *   • "Import GH OWebAuth" — OAuth device flow. The app asks GitHub for a user
 *     code, the user approves it in a browser, the app polls until GitHub hands
 *     back an access token. No client secret is involved (that is the whole
 *     point of the device grant), so the client_id can live in build.json as
 *     plain data.
 *   • "Import GH SSH" — a private key instead of a token; see [GitSshVault].
 *
 * Once a token exists, reading the artifact is one authenticated GET of the
 * repo's contents route, which is why this file has no git implementation in
 * it. The SSH route cannot do that — GitHub speaks only the git wire protocol
 * over SSH — so that one, and only that one, pays for a real clone.
 *
 * SECRET HYGIENE: the access token is held in a local, never written to prefs,
 * never logged, and handed to [ConfigSyncClient.request] as the `secret` so it
 * is redacted out of any echoed error body.
 *
 * Diagnose with:  adb logcat -s ConfigSync
 */
object GithubImport {

    private const val TAG = "ConfigSync"

    /** What the device-code call returns; [userCode] and [verificationUri] are
     *  what the dialog shows the user. */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    sealed class Step {
        data class Pending(val message: String) : Step()
        data class Token(val accessToken: String) : Step()
        data class Failed(val message: String) : Step()
    }

    /** True when the build carries a client_id. The tile says so rather than
     *  failing halfway through a flow that never had a chance. */
    fun deviceFlowConfigured(): Boolean = BuildConfig.UI_GH_OAUTH_CLIENT_ID.isNotBlank()

    /**
     * Ask GitHub to mint a device + user code pair. Blocking.
     */
    fun requestDeviceCode(): Result<DeviceCode> {
        val clientId = BuildConfig.UI_GH_OAUTH_CLIENT_ID
        if (clientId.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "No GitHub OAuth client_id in this build. Set " +
                        "ui.config_source.github_oauth.client_id in build.json and rebuild."
                )
            )
        }
        return postForm(
            BuildConfig.UI_GH_OAUTH_DEVICE_URL,
            mapOf("client_id" to clientId, "scope" to BuildConfig.UI_GH_OAUTH_SCOPE),
        ).mapCatching { o ->
            val err = o.optString("error")
            if (err.isNotBlank()) error("$err — ${o.optString("error_description", "no detail")}")
            DeviceCode(
                deviceCode = o.getString("device_code"),
                userCode = o.getString("user_code"),
                verificationUri = o.optString("verification_uri", "https://github.com/login/device"),
                intervalSeconds = o.optInt("interval", 5).coerceAtLeast(1),
                expiresInSeconds = o.optInt("expires_in", 900),
            )
        }
    }

    /**
     * One poll of the token endpoint. Returns [Step.Pending] while the user has
     * not approved yet — the caller drives the loop so it can keep the dialog
     * responsive and honour cancellation.
     *
     * `authorization_pending` and `slow_down` are NORMAL, not errors: the
     * device grant is defined to answer that way until approval, and treating
     * them as failure is the classic way to make this flow look broken.
     */
    fun pollForToken(deviceCode: String): Step {
        val clientId = BuildConfig.UI_GH_OAUTH_CLIENT_ID
        val result = postForm(
            BuildConfig.UI_GH_OAUTH_TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        val o = result.getOrElse { return Step.Failed("Token poll failed: ${it.message}") }

        val token = o.optString("access_token")
        if (token.isNotBlank()) return Step.Token(token)

        return when (val err = o.optString("error")) {
            "authorization_pending" -> Step.Pending("Waiting for you to approve the code…")
            "slow_down" -> Step.Pending("GitHub asked us to slow down; still waiting…")
            "expired_token" -> Step.Failed("The code expired before it was approved. Start again.")
            "access_denied" -> Step.Failed("Approval was declined in the browser.")
            "" -> Step.Failed("GitHub returned neither a token nor an error.")
            else -> Step.Failed("$err — ${o.optString("error_description", "no detail")}")
        }
    }

    /**
     * Read the config artifact out of the vault repo with a token.
     *
     * `Accept: application/vnd.github.raw` makes the contents route return the
     * file itself rather than the JSON envelope with a base64 blob in it, so
     * the body handed back is already the artifact and needs no unwrapping.
     */
    fun fetchArtifact(token: String): ConfigSyncClient.Outcome {
        val repo = BuildConfig.UI_CONFIG_GIT_REPO
        val path = BuildConfig.UI_CONFIG_GIT_PATH
        val ref = BuildConfig.UI_CONFIG_GIT_REF
        if (repo.isBlank() || path.isBlank()) {
            return ConfigSyncClient.Outcome.Failed(
                ConfigSyncClient.Kind.MALFORMED,
                "No vault repo configured (build.json::ui.config_source.git.repo / .path are empty)",
            )
        }
        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$ref"
        Log.i(TAG, "github: GET contents $repo/$path@$ref")
        return ConfigSyncClient.request(
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "X-GitHub-Api-Version" to "2022-11-28",
            ),
            secret = token,
            authHint = "The GitHub token is not valid for $repo, or lacks `repo` scope on a private repository.",
            connectTimeoutMs = BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
            readTimeoutMs = BuildConfig.UI_CONFIG_SOURCE_READ_MS,
            accept = "application/vnd.github.raw",
        )
    }

    /** Form POST returning a JSON object. GitHub answers these endpoints with
     *  form encoding by default, so `Accept: application/json` is required or
     *  the body comes back as `a=b&c=d` and every parse fails. */
    private fun postForm(url: String, fields: Map<String, String>): Result<JSONObject> {
        var conn: HttpURLConnection? = null
        return try {
            val payload = fields.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS
                readTimeout = BuildConfig.UI_CONFIG_SOURCE_READ_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Cloud-SuperApp-ConfigSync/1")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("HTTP $code with an empty body from $url")
            Result.success(JSONObject(body))
        } catch (t: Throwable) {
            Log.w(TAG, "github POST $url failed: ${t.javaClass.simpleName}")
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }
}
