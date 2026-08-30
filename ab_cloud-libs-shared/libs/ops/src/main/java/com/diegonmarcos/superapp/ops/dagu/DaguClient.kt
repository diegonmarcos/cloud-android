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
     *  run summary. Dagu wraps the array in `{"DAGs": [...]}`. */
    fun listDags(): DaguDagList {
        val body = getJson("/api/v1/dags")
        val root = JSONObject(body)
        val arr = root.optJSONArray("DAGs") ?: return DaguDagList(emptyList())
        val out = mutableListOf<DaguDag>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Dagu's DAG payload nests config + status at the top level.
            // We probe both `Config` and `DAG` because the schema has
            // varied across versions; one of the two is always present.
            val cfg = o.optJSONObject("Config") ?: o.optJSONObject("DAG") ?: o
            val name = cfg.optString("Name").ifBlank { o.optString("name") }
            val label = cfg.optString("displayName").ifBlank { name }
            val desc = cfg.optString("Description")
            val schedule = cfg.optString("Schedule").ifBlank {
                cfg.optJSONArray("schedule")?.optString(0).orEmpty()
            }
            val statusObj = o.optJSONObject("Status")
                ?: o.optJSONObject("status")
            val lastRun = statusObj?.let { s ->
                DaguRun(
                    status = s.optInt("Status"),
                    finishedAtMs = parseEpochMs(s.optString("FinishedAt")),
                    startedAtMs = parseEpochMs(s.optString("StartedAt")),
                )
            }
            out += DaguDag(
                name = name,
                displayLabel = label,
                description = desc,
                schedule = schedule,
                lastRun = lastRun,
            )
        }
        return DaguDagList(out)
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
