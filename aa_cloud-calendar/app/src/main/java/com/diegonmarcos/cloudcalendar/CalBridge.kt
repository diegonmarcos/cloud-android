package com.diegonmarcos.cloudcalendar

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.webkit.JavascriptInterface
import com.diegonmarcos.superapp.core.DataBackendClient
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * JS bridge exposed to calendar.html as `window.CalBridge`.
 *
 * SECURITY: this bridge is only safe to attach because the WebView it
 * is registered on loads a LOCAL asset we control
 * (file:///android_asset/calendar.html) — see MainActivity. Never
 * attach a JavascriptInterface bridge to a WebView that can navigate
 * to remote/attacker-controlled content: any page loaded there would
 * gain the same Java-callable surface as our own UI.
 */
class CalBridge(private val ctx: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var syncRunning = false
    @Volatile private var lastOk = 0
    @Volatile private var lastSkipped = 0
    @Volatile private var lastFailed = 0
    @Volatile private var lastMessages: List<String> = emptyList()

    @Volatile private var todoSyncRunning = false
    @Volatile private var todoLastOk = 0
    @Volatile private var todoLastFailed = 0
    @Volatile private var todoLastMessages: List<String> = emptyList()

    // ── the engine ───────────────────────────────────────────────────────────
    // Calendar data work lives in Cloud-Lib-Cal.apk now; this class is the
    // front end. Credentials are NOT sent there to live - they stay in this
    // app's EncryptedSharedPreferences and ride along on the calls that need
    // them, so nothing is orphaned by the engine having its own storage.
    private val client by lazy {
        DataBackendClient(ctx, "com.diegonmarcos.cloudlib.cal",
                          "com.diegonmarcos.superapp.cal.CalBackendService")
    }

    /** Call the engine. Seeds once, first time, before anything else. */
    private fun engine(method: String, vararg args: String): String {
        seedOnce()
        return client.call(method, *args)
    }

    private fun cfgJson(): String = CaldavPrefs.getJson(ctx) ?: ""

    /**
     * Hand the engine the tasks a re-sync cannot rebuild, exactly once.
     *
     * TodoStore moved into the engine's private storage with it. Almost all of
     * it re-syncs; a task created offline and never PUT (blank href) does not.
     * Guarded by a pref AND by the engine's own hasData, so a reinstall of
     * either side cannot double-seed or clobber newer data.
     */
    @Volatile private var seeded = false
    @Synchronized
    private fun seedOnce() {
        if (seeded) return
        seeded = true
        val prefs = ctx.getSharedPreferences("cal_bridge", Context.MODE_PRIVATE)
        if (prefs.getBoolean("seeded", false)) return
        val legacy = ctx.getSharedPreferences("cal_legacy_todos", Context.MODE_PRIVATE)
            .getString("todos", null)
        if (legacy.isNullOrBlank()) { prefs.edit().putBoolean("seeded", true).apply(); return }
        val ok = runCatching { JSONObject(client.call("seed", legacy)).optBoolean("ok") }
            .getOrDefault(false)
        // Only mark it done when it actually landed - a failed seed must be
        // retried, not silently skipped forever.
        if (ok) prefs.edit().putBoolean("seeded", true).apply()
    }

    private fun subscriptions() = Calendars.parse(
        String(Base64.decode(BuildConfig.CALENDARS_B64, Base64.DEFAULT), Charsets.UTF_8)
    )

    @JavascriptInterface
    fun calendars(): String {
        return engine("calendars")
    }

    // fromUtcMillis/toUtcMillis are taken as String and parsed to Long here:
    // JS numbers passed over the WebView JS bridge are doubles, which lose
    // precision for 64-bit epoch-millis values, so callers must stringify
    // them before passing across the bridge.
    @JavascriptInterface
    fun events(fromUtcMillis: String, toUtcMillis: String): String {
        return engine("events", fromUtcMillis, toUtcMillis)
    }

    @JavascriptInterface
    fun sync(): String {
        // Kick the engine off our own executor and keep the UI-state flags
        // here: "is a sync running" is what the spinner reads, and the engine
        // deliberately returns a report rather than tracking it.
        if (syncRunning) return JSONObject().put("started", false).toString()
        syncRunning = true
        executor.execute {
            try {
                val r = JSONObject(engine("sync"))
                lastOk = r.optInt("ok"); lastSkipped = r.optInt("skipped")
                lastFailed = r.optInt("failed")
                lastMessages = (0 until (r.optJSONArray("messages")?.length() ?: 0))
                    .map { r.optJSONArray("messages")!!.optString(it) }
            } finally { syncRunning = false }
        }
        return JSONObject().put("started", true).toString()
    }

    @JavascriptInterface
    fun syncStatus(): String {
        val messages = JSONArray()
        for (m in lastMessages) messages.put(m)
        return JSONObject().apply {
            put("running", syncRunning)
            put("ok", lastOk)
            put("skipped", lastSkipped)
            put("failed", lastFailed)
            put("messages", messages)
        }.toString()
    }

    // ======================================================================
    // ToDo / Projects (CalDAV VTODO)
    // ======================================================================

    /** projects() = the discovered VTODO collections (CalendarStore.eventsFor's
     *  cache-only-read pattern: this never touches the network — it reads
     *  whatever [TodoStore] last cached, so the tab renders instantly
     *  offline. Call [syncTodos] to refresh from the server. */
    @JavascriptInterface
    fun projects(): String {
        return engine("projects")
    }

    /** projectId "" means all projects. Cache-only read, same reasoning
     *  as [projects]. */
    @JavascriptInterface
    fun todos(projectId: String): String {
        return engine("todos", projectId)
    }

    /** Create (id empty) or update (id set) one task. This performs the
     *  CalDAV PUT synchronously and returns the real ok/error outcome —
     *  unlike [sync]/[syncStatus]'s fire-and-poll shape, the contract
     *  here is a single call returning a definitive result, so there is
     *  no way to satisfy it without waiting for the network round trip.
     *  JavascriptInterface calls already run off Android's main/UI
     *  thread (the WebView core thread), so this blocks only that JS
     *  call in the page, not app rendering — but it is still a
     *  network wait on the bridge thread, worth flagging explicitly. */
    @JavascriptInterface
    fun saveTodo(json: String): String {
        return engine("saveTodo", json, cfgJson())
    }

    /** done is "true"/"false" (bridge string convention, see [events]). */
    @JavascriptInterface
    fun setTodoStatus(id: String, done: String): String {
        return engine("setTodoStatus", id, done, cfgJson())
    }

    @JavascriptInterface
    fun deleteTodo(id: String): String {
        return engine("deleteTodo", id, cfgJson())
    }

    @JavascriptInterface
    fun syncTodos(): String {
        if (todoSyncRunning) return JSONObject().put("started", false).toString()
        todoSyncRunning = true
        executor.execute {
            try {
                val r = JSONObject(engine("syncTodos", cfgJson()))
                todoLastOk = r.optInt("ok"); todoLastFailed = r.optInt("failed")
                todoLastMessages = (0 until (r.optJSONArray("messages")?.length() ?: 0))
                    .map { r.optJSONArray("messages")!!.optString(it) }
            } finally { todoSyncRunning = false }
        }
        return JSONObject().put("started", true).toString()
    }

    @JavascriptInterface
    fun todoSyncStatus(): String {
        val messages = JSONArray()
        for (m in todoLastMessages) messages.put(m)
        return JSONObject().apply {
            put("running", todoSyncRunning)
            put("ok", todoLastOk)
            put("failed", todoLastFailed)
            put("messages", messages)
        }.toString()
    }

    /** NEVER returns the password — only whether one is stored. */
    @JavascriptInterface
    fun caldavConfig(): String {
        val (url, username) = CaldavPrefs.urlAndUsername(ctx)
        return JSONObject().apply {
            put("url", url)
            put("username", username)
            put("hasPassword", CaldavPrefs.hasPassword(ctx))
        }.toString()
    }

    /** Writes CalDAV URL/username/password into EncryptedSharedPreferences.
     *  This is the ONLY place these secrets are ever persisted — never
     *  BuildConfig, never a log line. `password` may be omitted to leave
     *  the previously-stored password unchanged (so the Configs screen
     *  can update the URL/username without forcing password re-entry). */
    @JavascriptInterface
    fun setCaldavConfig(json: String): String {
        return try {
            val o = JSONObject(json)
            val url = o.optString("url", "").trim()
            val username = o.optString("username", "").trim()
            if (url.isNotEmpty() && !url.startsWith("https://", ignoreCase = true)) {
                return okErr(false, "CalDAV URL must use https://")
            }
            val password = if (o.has("password")) o.optString("password", "") else null
            CaldavPrefs.set(ctx, url, username, password)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Round-trips discovery only (no writes) so the Configs screen can
     *  validate a URL/username/password before saving. Synchronous for
     *  the same reason [saveTodo] is — see its kdoc. */
    @JavascriptInterface
    fun testCaldav(): String {
        return engine("testCaldav", cfgJson())
    }

    // ---- small JSON helpers -----------------------------------------------

    private fun saveResult(ok: Boolean, id: String, error: String): String =
        JSONObject().apply { put("ok", ok); put("id", id); put("error", error) }.toString()

    private fun okErr(ok: Boolean, error: String): String =
        JSONObject().apply { put("ok", ok); put("error", error) }.toString()

    /**
     * CalDAV URL/username/password at rest, via EncryptedSharedPreferences
     * (androidx.security). This is the ONLY place these credentials are
     * read/written in the app — see [setCaldavConfig]/[caldavConfig].
     * Never logged, never returned in full ([caldavConfig] omits the
     * password entirely), never baked into BuildConfig.
     */
    private object CaldavPrefs {
        private const val FILE = "caldav_secure_prefs"
        private const val KEY_URL = "url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"

        @Volatile private var cached: SharedPreferences? = null

        private fun prefs(ctx: Context): SharedPreferences {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val appCtx = ctx.applicationContext
                val masterKey = MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val p = EncryptedSharedPreferences.create(
                    appCtx,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                cached = p
                return p
            }
        }

        /** The CalDAV config as the wire JSON the engine expects, or null.
         *  Returns JSON rather than a library type because the app no longer
         *  links libs:cal - credentials stay HERE and travel per call. */
        fun getJson(ctx: Context): String? {
            val p = prefs(ctx)
            val url = p.getString(KEY_URL, "") ?: ""
            if (url.isBlank()) return null
            return JSONObject()
                .put("baseUrl", url)
                .put("username", p.getString(KEY_USERNAME, "") ?: "")
                .put("password", p.getString(KEY_PASSWORD, "") ?: "")
                .toString()
        }

        fun urlAndUsername(ctx: Context): Pair<String, String> {
            val p = prefs(ctx)
            return (p.getString(KEY_URL, "") ?: "") to (p.getString(KEY_USERNAME, "") ?: "")
        }

        fun hasPassword(ctx: Context): Boolean =
            !prefs(ctx).getString(KEY_PASSWORD, "").isNullOrEmpty()

        fun set(ctx: Context, url: String, username: String, password: String?) {
            val editor = prefs(ctx).edit()
                .putString(KEY_URL, url)
                .putString(KEY_USERNAME, username)
            if (password != null) editor.putString(KEY_PASSWORD, password)
            editor.apply()
        }
    }
}
