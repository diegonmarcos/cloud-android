package com.diegonmarcos.superapp.cloud

import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The acting half of the container sheet — c3-infra-api's operations routes.
 *
 * The API already draws the line this UI needs. It exposes a container and its
 * service as SEPARATE things:
 *
 *   POST /vms/{vm}/containers/{name}/{start|stop|restart|update}
 *   POST /vms/{vm}/services/{service}/{start|stop}
 *
 * which is exactly the Container / App split the sheet renders. So the two
 * columns are not a presentation choice layered on top of one endpoint — they
 * are the two things the infrastructure actually distinguishes: the container
 * is the box, the service is what systemd runs inside it.
 *
 * EVERY CALL IS AUTHENTICATED. These routes sit behind Caddy's forward_auth on
 * api.diegonmarcos.com/c3-infra-api, so an Authelia bearer is mandatory. The
 * token is the one libs:ops already keeps for Dagu ([com.diegonmarcos.superapp
 * .ops.dagu.DaguPrefs]) — reused rather than re-collected, because a second
 * place to paste the same token is a second place for it to go stale.
 *
 * Failures are NAMED. "Action failed" on a stop button is indistinguishable
 * from a container that ignored the request, and this screen can stop things
 * people depend on.
 *
 * Diagnose with:  adb logcat -s C3Ops
 */
object OpsClient {

    const val TAG = "C3Ops"

    enum class Kind { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, SERVER, NETWORK, NO_TOKEN, NOT_CONFIGURED }

    sealed class Outcome {
        data class Ok(val message: String) : Outcome()
        data class Failed(val kind: Kind, val message: String) : Outcome()
    }

    /** Container-level: the box itself. */
    fun container(vm: String, name: String, action: String, bearer: String): Outcome =
        post("/vms/$vm/containers/$name/$action", bearer, "container $name: $action")

    /** Service-level: what runs inside the box. */
    fun service(vm: String, service: String, action: String, bearer: String): Outcome =
        post("/vms/$vm/services/$service/$action", bearer, "service $service: $action")

    /**
     * Trigger a GitHub Actions `workflow_dispatch` run.
     *
     * The GitHub token is NOT in this app, deliberately. GitHub only accepts a
     * repo-scoped PAT for workflow_dispatch, and an APK is an unzippable
     * archive — a token shipped inside one is a published token. So the app
     * calls c3-infra-api's POST /workflows/dispatch with the Authelia bearer
     * it already holds, and the server, which is where fleet secrets already
     * live, holds the PAT and makes the GitHub call.
     *
     * Success requires the proxy to answer `{"ok":true}`. A 2xx alone is not
     * enough: the route reports GitHub refusing the dispatch inside its own
     * response, so accepting any 2xx would turn a refused run into a green
     * toast.
     */
    fun dispatchWorkflow(repo: String, workflow: String, ref: String, bearer: String): Outcome {
        val payload = JSONObject()
            .put("repo", repo)
            .put("workflow", workflow)
            .put("ref", ref)
            .toString()
        val outcome = post("/workflows/dispatch", bearer, "dispatch $workflow ($repo@$ref)", payload)
        if (outcome is Outcome.Failed) return outcome
        val body = (outcome as Outcome.Ok).message
        // The proxy answers 501 when it has no GITHUB_TOKEN and 502 when
        // GitHub refuses, both of which land in Failed above. This last check
        // catches the remaining case: a 2xx whose body did not confirm.
        if (!body.contains("\"ok\":true")) {
            return fail(Kind.SERVER,
                "The dispatch proxy answered 2xx but did not confirm the run started.\n$body")
        }
        return Outcome.Ok("✓ dispatched $workflow on $ref")
    }

    /**
     * Blocking POST. Call from a background thread.
     *
     * [json] is null for the container/service routes: they are addressed
     * entirely by their path, so an empty POST is the whole request.
     */
    private fun post(path: String, bearer: String, what: String, json: String? = null): Outcome {
        val base = BuildConfig.UI_C3_OPS_BASE_URL.trimEnd('/')
        if (base.isEmpty()) {
            return fail(Kind.NOT_CONFIGURED,
                "No ops endpoint in this build (build.json::ui.c3_ops.base_url is empty).")
        }
        if (bearer.isBlank()) {
            return fail(Kind.NO_TOKEN,
                "No Authelia token on this device. Configs → Profile → Config import, or the " +
                "Dagu login, supplies one; these routes are behind forward_auth and cannot be " +
                "called without it.")
        }

        val url = base + path
        Log.i(TAG, "POST $url (token ${bearer.length} chars, not logged)")
        var conn: HttpURLConnection? = null
        val code: Int
        val body: String
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 30_000          // start/update can be slow
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $bearer")
                setRequestProperty("User-Agent", "Cloud-SuperApp-C3Ops/1")
            }
            conn.outputStream.use {
                it.write(json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
            }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "network failure: ${t.javaClass.simpleName}")
            return fail(Kind.NETWORK, "Could not reach $url — ${t.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }

        val snippet = body.take(200).replace(bearer, "«token»")
        Log.i(TAG, "HTTP $code for $what")
        return when {
            // A redirect here is the login page, same as the config route.
            code in 300..399 -> fail(Kind.UNAUTHORIZED,
                "HTTP $code — redirected to a login page. The Authelia token is missing or expired.")
            code == 401 -> fail(Kind.UNAUTHORIZED, "HTTP 401 — token rejected (bad or expired).\n$snippet")
            code == 403 -> fail(Kind.FORBIDDEN, "HTTP 403 — token accepted but not allowed to do this.\n$snippet")
            code == 404 -> fail(Kind.NOT_FOUND,
                "HTTP 404 — the API knows no such VM/container for this path. Check the vm and " +
                "name recorded in data/services_*.json.\n$snippet")
            code >= 500 -> fail(Kind.SERVER, "HTTP $code — the ops API errored.\n$snippet")
            code !in 200..299 -> fail(Kind.SERVER, "HTTP $code.\n$snippet")
            else -> Outcome.Ok("✓ $what" + if (snippet.isBlank()) "" else "\n$snippet")
        }
    }

    private fun fail(kind: Kind, message: String): Outcome.Failed {
        Log.w(TAG, "$kind: ${message.lineSequence().first()}")
        return Outcome.Failed(kind, message)
    }
}
