package com.diegonmarcos.superapp.cal

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The calendar BACK END: subscriptions, event queries, CalDAV task
 * collections and task mutations, all as JSON.
 *
 * Credentials deliberately do NOT live here. The app holds them in its own
 * EncryptedSharedPreferences and passes them in per call, so moving this work
 * into Cloud-Lib-Cal.apk orphans nobody's stored CalDAV login - a migration
 * that silently made people re-enter a password would be a worse outcome than
 * leaving the module linked.
 *
 * Every method blocks and does real network I/O where CalDAV is involved,
 * exactly like the underlying engines. Callers run it off the main thread.
 */
class CalEngine(context: Context) {

    private val ctx = context.applicationContext

    /** build.json's sibling data/calendars.json, baked into THIS module. */
    private val subscriptions: List<CalSubscription> by lazy {
        Calendars.parse(String(Base64.decode(BuildConfig.CALENDARS_B64, Base64.DEFAULT), Charsets.UTF_8))
    }

    private fun cfg(json: String?): CalDavConfig? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val base = o.optString("baseUrl"); val user = o.optString("username")
        val pass = o.optString("password")
        return if (base.isBlank() || user.isBlank()) null
               else CalDavConfig(base, user, pass)
    }

    fun calendars(): String = JSONArray().apply {
        subscriptions.forEach { s ->
            put(JSONObject()
                .put("id", s.id).put("name", s.name).put("category", s.category)
                .put("color", s.color).put("enabled", s.enabled)
                .put("lastSync", CalendarStore.lastSync(ctx, s.id)))
        }
    }.toString()

    fun events(fromUtc: Long, toUtc: Long): String {
        val ids = subscriptions.filter { it.enabled }.map { it.id }
        return JSONArray().apply {
            CalendarStore.eventsFor(ctx, ids, fromUtc, toUtc).forEach { e ->
                put(JSONObject()
                    .put("id", e.id).put("calendarId", e.calendarId)
                    .put("title", e.title).put("description", e.description)
                    .put("location", e.location)
                    .put("start", e.startUtcMillis).put("end", e.endUtcMillis)
                    .put("allDay", e.allDay))
            }
        }.toString()
    }

    /** Blocking, networked. Returns the report rather than tracking "running"
     *  state - that is UI state and belongs to whoever drew the spinner. */
    fun sync(): String {
        val r = CalSync.syncAll(ctx, subscriptions)
        return JSONObject()
            .put("ok", r.ok).put("skipped", r.skipped).put("failed", r.failed)
            .put("messages", JSONArray(r.messages)).toString()
    }

    fun projects(): String = JSONArray().apply {
        TodoStore.collections(ctx).forEach { c ->
            put(JSONObject()
                .put("id", c.href).put("name", c.displayName).put("color", c.color))
        }
    }.toString()

    fun todos(projectId: String): String = JSONArray().apply {
        val list = if (projectId.isBlank()) TodoStore.allTodos(ctx)
                   else TodoStore.todosFor(ctx, projectId)
        list.forEach { put(todoJson(it)) }
    }.toString()

    /** Writes locally first, then to the server when a config is supplied, so
     *  a task is never lost to a failed round trip. */
    fun saveTodo(todoJson: String, cfgJson: String?): String {
        val o = JSONObject(todoJson)
        val local = CalTodo(
            uid = o.optString("uid").ifBlank { java.util.UUID.randomUUID().toString() },
            collectionId = o.optString("projectId"),
            href = o.optString("href"),
            etag = o.optString("etag"),
            summary = o.optString("summary"),
            description = o.optString("description"),
            status = o.optString("status").ifBlank { "NEEDS-ACTION" },
            dueUtcMillis = if (o.has("due") && !o.isNull("due")) o.optLong("due") else null,
            dueIsDate = o.optBoolean("dueIsDate", false),
        )
        TodoStore.upsertTodo(ctx, local)
        val c = cfg(cfgJson) ?: return JSONObject().put("ok", true).put("synced", false).toString()
        return runCatching {
            val saved = CalDav.putTodo(c, local.collectionId, local, local.href.isBlank())
            TodoStore.upsertTodo(ctx, saved)
            JSONObject().put("ok", true).put("synced", true).put("todo", todoJson(saved)).toString()
        }.getOrElse { t ->
            // Local write already landed; report the server failure honestly
            // instead of pretending the task saved everywhere.
            JSONObject().put("ok", true).put("synced", false)
                .put("error", t.message ?: t.toString()).toString()
        }
    }

    fun setTodoStatus(projectId: String, uid: String, done: Boolean, cfgJson: String?): String {
        val existing = TodoStore.todosFor(ctx, projectId).firstOrNull { it.uid == uid }
            ?: return JSONObject().put("error", "not_found").toString()
        val updated = existing.copy(status = if (done) "COMPLETED" else "NEEDS-ACTION")
        TodoStore.upsertTodo(ctx, updated)
        val c = cfg(cfgJson) ?: return JSONObject().put("ok", true).put("synced", false).toString()
        return runCatching {
            TodoStore.upsertTodo(ctx, CalDav.putTodo(c, updated.collectionId, updated, false))
            JSONObject().put("ok", true).put("synced", true).toString()
        }.getOrElse { t ->
            JSONObject().put("ok", true).put("synced", false)
                .put("error", t.message ?: t.toString()).toString()
        }
    }

    fun deleteTodo(projectId: String, uid: String, cfgJson: String?): String {
        val existing = TodoStore.todosFor(ctx, projectId).firstOrNull { it.uid == uid }
        TodoStore.removeTodo(ctx, projectId, uid)
        val c = cfg(cfgJson)
        if (c == null || existing == null || existing.href.isBlank())
            return JSONObject().put("ok", true).put("synced", false).toString()
        return runCatching {
            CalDav.deleteTodo(c, existing)
            JSONObject().put("ok", true).put("synced", true).toString()
        }.getOrElse { t ->
            JSONObject().put("ok", true).put("synced", false)
                .put("error", t.message ?: t.toString()).toString()
        }
    }

    /** Discover collections and pull their tasks. Blocking, networked. */
    fun syncTodos(cfgJson: String?): String {
        val c = cfg(cfgJson)
            ?: return JSONObject().put("error", "no CalDAV config supplied").toString()
        var ok = 0; var failed = 0
        val messages = JSONArray()
        val collections = runCatching { CalDav.discoverCollections(c) }.getOrElse { t ->
            return JSONObject().put("error", t.message ?: t.toString()).toString()
        }
        TodoStore.replaceCollections(ctx, collections)
        collections.forEach { col ->
            runCatching { TodoStore.replaceTodos(ctx, col.href, CalDav.fetchTodos(c, col)); ok++ }
                .onFailure { failed++; messages.put("${col.displayName}: ${it.message}") }
        }
        return JSONObject().put("ok", ok).put("failed", failed).put("messages", messages).toString()
    }

    fun testCaldav(cfgJson: String?): String {
        val c = cfg(cfgJson)
            ?: return JSONObject().put("ok", false).put("error", "no config").toString()
        return runCatching {
            JSONObject().put("ok", true).put("collections", CalDav.discoverCollections(c).size).toString()
        }.getOrElse { t ->
            JSONObject().put("ok", false).put("error", t.message ?: t.toString()).toString()
        }
    }

    private fun todoJson(t: CalTodo): JSONObject = JSONObject()
        .put("uid", t.uid).put("projectId", t.collectionId)
        .put("href", t.href).put("etag", t.etag)
        .put("summary", t.summary).put("description", t.description)
        .put("status", t.status)
        .put("due", t.dueUtcMillis ?: JSONObject.NULL)
        .put("dueIsDate", t.dueIsDate)
}
