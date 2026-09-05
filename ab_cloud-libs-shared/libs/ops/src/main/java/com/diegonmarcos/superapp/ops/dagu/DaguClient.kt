package com.diegonmarcos.superapp.ops.dagu

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bare-bones Dagu REST API v1 client — enough to drive a
 * GitHub-Actions-style workflow list page (one row per registered
 * DAG, coloured status dot, last-run timestamp). Plain
 * [HttpURLConnection] + [org.json] — no OkHttp / Retrofit deps,
 * the SuperApp pulls org.json via the Android framework.
 *
 * Threading: every method blocks. Callers MUST run on a background
 * thread (the fragment uses a plain Thread — no kotlinx-coroutines
 * dep on libs/ops).
 *
 * Auth: Authelia bearer token. The Caddy edge at
 * workflows.diegonmarcos.com forwards anything bearing
 * `Authorization: Bearer ...` through to the introspect-proxy +
 * upstream Dagu without challenging the request with the WebAuthn
 * dance. The user pastes the token (from
 * vault/A0_keys/providers/authelia/oauth/get_token.py output)
 * once into the login form and DaguPrefs persists it encrypted.
 *
 * Source of truth for endpoints: Dagu's swagger.
 */
class DaguClient(private val serverUrl: String, private val token: String) {

    /** GET /api/v1/dags — list every registered DAG with its last
     *  run summary.
     *
     *  Schema note: Dagu renamed this payload between versions. The
     *  deployed server answers with the lower-case shape
     *  `{"dags":[{"dag":{...},"fileName":...,"latestDAGRun":{...}}]}`,
     *  while older builds answered `{"DAGs":[{"Config":{...},
     *  "Status":{...}}]}`. Both are probed, lower-case first, so the
     *  page keeps rendering across a server upgrade in either
     *  direction. */
    fun listDags(): DaguDagList {
        val body = getJson("/api/v1/dags")
        val root = JSONObject(body)
        val arr = root.optJSONArray("dags")
            ?: root.optJSONArray("DAGs")
            ?: return DaguDagList(emptyList())
        val out = mutableListOf<DaguDag>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Config block: `dag` (current) / `Config` / `DAG` (legacy),
            // else the entry itself for the flattest historical shape.
            val cfg = o.optJSONObject("dag")
                ?: o.optJSONObject("Config")
                ?: o.optJSONObject("DAG")
                ?: o
            val name = cfg.optString("name")
                .ifBlank { cfg.optString("Name") }
                .ifBlank { o.optString("fileName") }
            // `fileName` is the path segment the start endpoint takes.
            // Fall back to the name when the server omits it.
            val fileName = o.optString("fileName").ifBlank { name }
            val label = cfg.optString("displayName").ifBlank { name }
            val desc = cfg.optString("description")
                .ifBlank { cfg.optString("Description") }
            // Current schema: schedule is an array of
            // {"expression":"*/10 * * * *","kind":"cron"} objects.
            // Legacy: a bare "Schedule" string, or an array of strings.
            val schedule = cfg.optString("Schedule").ifBlank {
                val sched = cfg.optJSONArray("schedule")
                when {
                    sched == null -> ""
                    sched.optJSONObject(0) != null ->
                        sched.optJSONObject(0)!!.optString("expression")
                    else -> sched.optString(0).orEmpty()
                }
            }
            val statusObj = o.optJSONObject("latestDAGRun")
                ?: o.optJSONObject("Status")
                ?: o.optJSONObject("status")
            val lastRun = statusObj?.let { s ->
                DaguRun(
                    status = if (s.has("status")) s.optInt("status") else s.optInt("Status"),
                    finishedAtMs = parseEpochMs(
                        s.optString("finishedAt").ifBlank { s.optString("FinishedAt") }),
                    startedAtMs = parseEpochMs(
                        s.optString("startedAt").ifBlank { s.optString("StartedAt") }),
                )
            }
            out += DaguDag(
                name = name,
                fileName = fileName,
                displayLabel = label,
                description = desc,
                schedule = schedule,
                lastRun = lastRun,
            )
        }
        return DaguDagList(out)
    }

    /**
     * POST /api/v1/dags/{fileName}/start — create a DAG-run from the
     * DAG definition and start executing it. This is the same call the
     * Dagu web UI's "Start" button makes.
     *
     * Success is NOT "no exception was thrown". Dagu documents 200 with
     * a body whose `dagRunId` is a REQUIRED property, so a response is
     * only accepted when the status is 2xx *and* a non-blank `dagRunId`
     * came back. Anything else — 4xx/5xx, an HTML error page from the
     * Caddy edge, a 200 with an empty body because a proxy swallowed
     * the upstream — raises [IOException] carrying the status code and
     * the server's own message, so the caller has something concrete to
     * show the user.
     *
     * 409 specifically means the DAG is already running under singleton
     * mode; the message is surfaced verbatim rather than translated,
     * because Dagu's wording is already the clearest explanation.
     *
     * Blocking; call from a background thread. Returns the new run id.
     */
    fun startDag(fileName: String): String {
        if (fileName.isBlank()) throw IOException("Cannot start a DAG with no name.")
        // The endpoint declares its request body required, so an empty
        // JSON object is sent even though every property is optional.
        val body = postJson("/api/v1/dags/${encodePathSegment(fileName)}/start", "{}")
        val runId = runCatching { JSONObject(body).optString("dagRunId") }
            .getOrDefault("")
        if (runId.isBlank()) {
            throw IOException(
                "Dagu accepted the request but returned no dagRunId — " +
                    "the run did NOT start. Body: ${body.take(200)}")
        }
        return runId
    }

    // ─────────────────────────── internals ───────────────────────────

    private fun getJson(path: String): String {
        val conn = open("GET", path)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("GET $path failed: HTTP $code · $err")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    /** POST a JSON body and return the response body. Shares [open] —
     *  and therefore the exact same bearer-token auth — with [getJson];
     *  there is deliberately one HTTP path and one auth path in this
     *  class. Non-2xx raises [IOException] with the server's own error
     *  text appended, which is what the UI shows the user. */
    private fun postJson(path: String, json: String): String {
        val conn = open("POST", path)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("POST $path failed: HTTP $code · ${err.take(300)}")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    /** DAG names are plain identifiers today, but they are user data
     *  from a YAML filename, so they are percent-encoded before being
     *  spliced into the URL. `+` is corrected to `%20` because
     *  URLEncoder targets form bodies, not path segments. */
    private fun encodePathSegment(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    private fun open(method: String, path: String): HttpURLConnection {
        val base = serverUrl.trimEnd('/')
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    /** Parse Dagu's ISO-8601 timestamp ("2026-06-10T12:34:56Z" or
     *  "2026-06-10 12:34:56 +0000 UTC") to epoch ms. Returns 0 on
     *  unparseable / empty (the "never ran yet" sentinel). Three
     *  format variants handled because Dagu's schema drift produced
     *  all three in the wild — Go time formatters vs JSON marshallers
     *  vs raw runlog timestamps. */
    private fun parseEpochMs(raw: String): Long {
        if (raw.isBlank() || raw == "0001-01-01T00:00:00Z") return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
        )
        for (p in patterns) {
            runCatching {
                val sdf = java.text.SimpleDateFormat(p, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return sdf.parse(raw)?.time ?: 0L
            }
        }
        return 0L
    }
}
