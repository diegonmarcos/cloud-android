package com.diegonmarcos.superapp.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Mattermost REST API v4 client — enough to drive the
 * Comms/Mattermost page (login + list categories + list channels +
 * derive unread counts). Plain [HttpURLConnection] + [org.json] —
 * no OkHttp / Retrofit deps, the SuperApp already pulls org.json
 * via the Android framework.
 *
 * Threading: every method blocks. Callers MUST run on a background
 * thread (we use a plain Thread inside MattermostFragment;
 * libs/mail follows the same pattern with JmapClient).
 *
 * Source of truth for endpoints: https://api.mattermost.com (v4).
 */
class MattermostClient(private val serverUrl: String, private val token: String) {

    /** POST /api/v4/users/login.
     *  Returns the user object on success + populates `token` via the
     *  outer [LoginResult] (Mattermost returns the session token in
     *  the `Token` response header, NOT the JSON body). Throws on
     *  HTTP non-2xx or unparseable JSON. */
    fun login(loginId: String, password: String): LoginResult {
        val body = JSONObject().apply {
            put("login_id", loginId)
            put("password", password)
        }.toString()
        val conn = open("POST", "/api/v4/users/login", body, includeAuth = false)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("login failed: HTTP $code · $err")
            }
            val tk = conn.getHeaderField("Token")
                ?: throw IOException("login response missing Token header")
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            return LoginResult(
                token = tk,
                user = MattermostUser(
                    id = resp.optString("id"),
                    username = resp.optString("username"),
                    email = resp.optString("email"),
                ),
            )
        } finally { conn.disconnect() }
    }

    /** GET /api/v4/users/me/teams — list every team the user is a
     *  member of. Most personal users have exactly one team. */
    fun listMyTeams(): List<MattermostTeam> {
        val arr = JSONArray(getJson("/api/v4/users/me/teams"))
        val out = mutableListOf<MattermostTeam>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += MattermostTeam(
                id = o.optString("id"),
                displayName = o.optString("display_name"),
                name = o.optString("name"),
            )
        }
        return out
    }

    /** GET /api/v4/users/{user_id}/teams/{team_id}/channels/categories
     *  — user-defined category groupings (Favorites / Channels / DMs /
     *  custom). The `categories` array is wrapped in an outer object
     *  with `order` and `categories` keys. */
    fun listCategories(userId: String, teamId: String): List<MattermostCategory> {
        val resp = JSONObject(getJson("/api/v4/users/$userId/teams/$teamId/channels/categories"))
        val arr = resp.optJSONArray("categories") ?: return emptyList()
        val out = mutableListOf<MattermostCategory>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val chIds = o.optJSONArray("channel_ids")
            val ids = mutableListOf<String>()
            if (chIds != null) {
                for (j in 0 until chIds.length()) ids += chIds.optString(j)
            }
            out += MattermostCategory(
                id = o.optString("id"),
                displayName = o.optString("display_name"),
                type = o.optString("type"),
                channelIds = ids,
            )
        }
        return out
    }

    /** GET /api/v4/users/{user_id}/teams/{team_id}/channels — all
     *  channels the user is a member of in this team. */
    fun listChannels(userId: String, teamId: String): List<MattermostChannel> {
        val arr = JSONArray(getJson("/api/v4/users/$userId/teams/$teamId/channels"))
        val out = mutableListOf<MattermostChannel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += MattermostChannel(
                id = o.optString("id"),
                teamId = o.optString("team_id"),
                type = o.optString("type"),
                displayName = o.optString("display_name"),
                name = o.optString("name"),
                totalMsgCount = o.optLong("total_msg_count"),
            )
        }
        return out
    }

    /** GET /api/v4/users/{user_id}/teams/{team_id}/channels/members
     *  — per-channel membership view state (msg_count + mention_count
     *  drive the unread badge). */
    fun listChannelMembers(userId: String, teamId: String): List<MattermostMember> {
        val arr = JSONArray(getJson("/api/v4/users/$userId/teams/$teamId/channels/members"))
        val out = mutableListOf<MattermostMember>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += MattermostMember(
                channelId = o.optString("channel_id"),
                userId = o.optString("user_id"),
                msgCount = o.optLong("msg_count"),
                mentionCount = o.optLong("mention_count"),
            )
        }
        return out
    }

    // ─────────────────────────── internals ───────────────────────────

    private fun getJson(path: String): String {
        val conn = open("GET", path, null, includeAuth = true)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("GET $path failed: HTTP $code · $err")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    private fun open(method: String, path: String, body: String?, includeAuth: Boolean): HttpURLConnection {
        val base = serverUrl.trimEnd('/')
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (includeAuth && token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return conn
    }

    /** Login return type — carries both the session token (header) and
     *  the user payload (body), since the caller wants to persist both
     *  to [MattermostPrefs]. */
    data class LoginResult(val token: String, val user: MattermostUser)
}
