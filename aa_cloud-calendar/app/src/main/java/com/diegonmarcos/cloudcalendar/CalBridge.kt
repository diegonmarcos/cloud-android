package com.diegonmarcos.cloudcalendar

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.diegonmarcos.superapp.cal.CalDav
import com.diegonmarcos.superapp.cal.CalDavConfig
import com.diegonmarcos.superapp.cal.CalSync
import com.diegonmarcos.superapp.cal.CalTodo
import com.diegonmarcos.superapp.cal.CalendarStore
import com.diegonmarcos.superapp.cal.Calendars
import com.diegonmarcos.superapp.cal.TodoStore
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

    private fun subscriptions() = Calendars.parse(
        String(Base64.decode(BuildConfig.CALENDARS_B64, Base64.DEFAULT), Charsets.UTF_8)
    )

    @JavascriptInterface
    fun calendars(): String {
        val arr = JSONArray()
        for (sub in subscriptions()) {
            arr.put(JSONObject().apply {
                put("id", sub.id)
                put("name", sub.name)
                put("category", sub.category)
                put("color", sub.color)
                put("enabled", sub.enabled)
            })
        }
        return arr.toString()
    }

    // fromUtcMillis/toUtcMillis are taken as String and parsed to Long here:
    // JS numbers passed over the WebView JS bridge are doubles, which lose
    // precision for 64-bit epoch-millis values, so callers must stringify
    // them before passing across the bridge.
    @JavascriptInterface
    fun events(fromUtcMillis: String, toUtcMillis: String): String {
        val from = fromUtcMillis.toLongOrNull() ?: 0L
        val to = toUtcMillis.toLongOrNull() ?: 0L
        val ids = subscriptions().map { it.id }
        val arr = JSONArray()
        for (e in CalendarStore.eventsFor(ctx, ids, from, to)) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("calendarId", e.calendarId)
                put("title", e.title)
                put("start", e.startUtcMillis)
                put("end", e.endUtcMillis)
                put("allDay", e.allDay)
                put("location", e.location)
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun sync(): String {
        if (!syncRunning) {
            syncRunning = true
            executor.execute {
                try {
                    val report = CalSync.syncAll(ctx, subscriptions())
                    lastOk = report.ok
                    lastSkipped = report.skipped
                    lastFailed = report.failed
                    lastMessages = report.messages
                } finally {
                    syncRunning = false
                }
            }
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
        val arr = JSONArray()
        for (col in TodoStore.collections(ctx)) {
            val todos = TodoStore.todosFor(ctx, col.href)
            arr.put(JSONObject().apply {
                put("id", col.href)
                put("name", col.displayName)
                put("color", col.color)
                put("openCount", todos.count { it.status != "COMPLETED" })
                put("totalCount", todos.size)
            })
        }
        return arr.toString()
    }

    /** projectId "" means all projects. Cache-only read, same reasoning
     *  as [projects]. */
    @JavascriptInterface
    fun todos(projectId: String): String {
        val all = if (projectId.isBlank()) TodoStore.allTodos(ctx) else TodoStore.todosFor(ctx, projectId)
        val arr = JSONArray()
        for (t in all) arr.put(t.toBridgeJson())
        return arr.toString()
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
        var id = ""
        try {
            val o = JSONObject(json)
            id = o.optString("id", "")
            val cfg = CaldavPrefs.get(ctx)
                ?: return saveResult(false, "", "CalDAV is not configured")

            val existing = if (id.isBlank()) null else TodoStore.findById(ctx, id)
            if (id.isNotBlank() && existing == null) {
                return saveResult(false, id, "task not found in local cache")
            }
            val isCreate = existing == null

            val projectId = if (isCreate) o.optString("projectId", "") else existing.collectionId
            if (projectId.isBlank()) return saveResult(false, "", "projectId is required to create a task")

            val dueMillis = o.optString("due", "").toLongOrNull()
            val merged = CalTodo(
                uid = existing?.uid ?: UUID.randomUUID().toString(),
                collectionId = projectId,
                href = existing?.href ?: "",
                etag = existing?.etag ?: "",
                summary = if (o.has("summary")) o.optString("summary", "") else existing?.summary ?: "",
                description = if (o.has("description")) o.optString("description", "") else existing?.description ?: "",
                status = if (o.has("status")) o.optString("status", "NEEDS-ACTION") else existing?.status ?: "NEEDS-ACTION",
                dueUtcMillis = if (o.has("due")) dueMillis else existing?.dueUtcMillis,
                dueIsDate = existing?.dueIsDate ?: false,
                priority = if (o.has("priority")) o.optInt("priority", 0) else existing?.priority ?: 0,
                percentComplete = existing?.percentComplete,
                createdUtcMillis = existing?.createdUtcMillis,
                lastModifiedUtcMillis = existing?.lastModifiedUtcMillis,
                completedUtcMillis = existing?.completedUtcMillis,
                unknownLines = existing?.unknownLines ?: emptyList(),
            )

            val saved = CalDav.putTodo(cfg, projectId, merged, isCreate)
            TodoStore.upsertTodo(ctx, saved)
            return saveResult(true, saved.bridgeId, "")
        } catch (e: Exception) {
            return saveResult(false, id, e.message ?: e.javaClass.simpleName)
        }
    }

    /** done is "true"/"false" (bridge string convention, see [events]). */
    @JavascriptInterface
    fun setTodoStatus(id: String, done: String): String {
        val cfg = CaldavPrefs.get(ctx) ?: return okErr(false, "CalDAV is not configured")
        val existing = TodoStore.findById(ctx, id) ?: return okErr(false, "task not found in local cache")
        val markDone = done == "true"
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            status = if (markDone) "COMPLETED" else "NEEDS-ACTION",
            percentComplete = if (markDone) 100 else existing.percentComplete,
            completedUtcMillis = if (markDone) now else null,
        )
        return try {
            val saved = CalDav.putTodo(cfg, existing.collectionId, updated, isCreate = false)
            TodoStore.upsertTodo(ctx, saved)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun deleteTodo(id: String): String {
        val cfg = CaldavPrefs.get(ctx) ?: return okErr(false, "CalDAV is not configured")
        val existing = TodoStore.findById(ctx, id) ?: return okErr(false, "task not found in local cache")
        return try {
            CalDav.deleteTodo(cfg, existing)
            TodoStore.removeTodo(ctx, existing.collectionId, existing.uid)
            okErr(true, "")
        } catch (e: Exception) {
            okErr(false, e.message ?: e.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun syncTodos(): String {
        val cfg = CaldavPrefs.get(ctx)
        if (!todoSyncRunning && cfg != null) {
            todoSyncRunning = true
            executor.execute {
                try {
                    val report = CalDav.syncAll(ctx, cfg)
                    todoLastOk = report.ok
                    todoLastFailed = report.failed
                    todoLastMessages = report.messages
                } finally {
                    todoSyncRunning = false
                }
            }
        } else if (cfg == null) {
            todoLastMessages = listOf("CalDAV is not configured")
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
        val cfg = CaldavPrefs.get(ctx) ?: return JSONObject().apply {
            put("ok", false); put("error", "CalDAV is not configured"); put("collections", JSONArray())
        }.toString()
        return try {
            val collections = CalDav.discoverCollections(cfg)
            val arr = JSONArray()
            for (c in collections) arr.put(JSONObject().apply { put("id", c.href); put("name", c.displayName) })
            JSONObject().apply { put("ok", true); put("error", ""); put("collections", arr) }.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: e.javaClass.simpleName)
                put("collections", JSONArray())
            }.toString()
        }
    }

    // ---- small JSON helpers -----------------------------------------------

    private fun CalTodo.toBridgeJson(): JSONObject = JSONObject().apply {
        put("id", bridgeId)
        put("uid", uid)
        put("projectId", collectionId)
        put("summary", summary)
        put("description", description)
        put("status", status)
        put("due", dueUtcMillis?.toString() ?: "")
        put("priority", priority)
        put("completedAt", completedUtcMillis?.toString() ?: "")
    }

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

        fun get(ctx: Context): CalDavConfig? {
            val p = prefs(ctx)
            val url = p.getString(KEY_URL, "") ?: ""
            if (url.isBlank()) return null
            val username = p.getString(KEY_USERNAME, "") ?: ""
            val password = p.getString(KEY_PASSWORD, "") ?: ""
            return CalDavConfig(url, username, password)
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
