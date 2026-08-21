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

    /**
     * One-time handoff of the tasks a re-sync CANNOT rebuild.
     *
     * TodoStore lives in PRIVATE app storage, so moving this engine into
     * Cloud-Lib-Cal.apk moves the data directory with it. Almost everything
     * there is a cache of server state and repopulates on the next sync - the
     * exception is a task created offline that was never PUT, which has a
     * blank href and exists nowhere else. Without this handoff those vanish
     * silently on cutover.
     *
     * Deliberately narrow: seeding the server-derived tasks too would risk
     * resurrecting ones deleted on another client. Only the unrecoverable
     * slice moves; sync rebuilds the rest.
     */
    fun seed(todosJson: String): String {
        val arr = runCatching { JSONArray(todosJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "bad payload").toString()
        var taken = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = runCatching { CalTodo.fromJson(o) }.getOrNull() ?: continue
            // Server-derived tasks are re-synced, not seeded.
            if (t.href.isNotBlank()) continue
            TodoStore.upsertTodo(ctx, t); taken++
        }
        return JSONObject().put("ok", true).put("taken", taken).toString()
    }

    /** Whether this engine already holds tasks - lets the app skip the seed. */
    fun hasData(): String =
        JSONObject().put("hasData", TodoStore.allTodos(ctx).isNotEmpty()).toString()

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
        // CalTodo.fromJson is the model's own parser - it knows every field
        // (priority, percentComplete, the RFC timestamps, unknownLines that
        // must round-trip back to the server untouched). Hand-rolling the
        // constructor here dropped five of them and silently discarded the
        // unknown-line passthrough.
        val o = JSONObject(todoJson)
        if (o.optString("uid").isBlank()) o.put("uid", java.util.UUID.randomUUID().toString())
        val local = CalTodo.fromJson(o)
        TodoStore.upsertTodo(ctx, local)
        val c = cfg(cfgJson) ?: return JSONObject().put("ok", true).put("synced", false).toString()
        return runCatching {
            val saved = CalDav.putTodo(c, local.collectionId, local, local.href.isBlank())
            TodoStore.upsertTodo(ctx, saved)
            JSONObject().put("ok", true).put("synced", true).put("todo", todoJson(saved)).toString()
        }.getOrElse { t ->
            // The local write already landed; report the server failure
            // honestly instead of pretending the task saved everywhere.
            JSONObject().put("ok", true).put("synced", false)
                .put("error", t.message ?: t.toString()).toString()
        }
    }

    fun setTodoStatus(bridgeId: String, done: Boolean, cfgJson: String?): String {
        // The UI holds an opaque bridgeId, not (collectionId, uid) - encoding
        // is the model's job, so decode with its own helper.
        val (collectionId, uid) = CalTodo.decodeBridgeId(bridgeId)
            ?: return JSONObject().put("error", "bad_id").toString()
        val existing = TodoStore.todosFor(ctx, collectionId).firstOrNull { it.uid == uid }
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

    fun deleteTodo(bridgeId: String, cfgJson: String?): String {
        val (collectionId, uid) = CalTodo.decodeBridgeId(bridgeId)
            ?: return JSONObject().put("error", "bad_id").toString()
        val existing = TodoStore.todosFor(ctx, collectionId).firstOrNull { it.uid == uid }
        TodoStore.removeTodo(ctx, collectionId, uid)
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

    /**
     * The shape the UI reads — NOT CalTodo.toJson().
     *
     * They differ in ways that would fail silently: the UI keys tasks by `id`
     * (the opaque bridgeId), which toJson does not emit at all, and reads
     * `projectId`/`due`/`completedAt` where toJson writes
     * `collectionId`/`dueUtcMillis`/`completedUtcMillis`. Handing it toJson
     * renders a task list with no ids and no due dates — no error anywhere.
     *
     * toJson stays the wire format for storage and CalDAV round trips; this is
     * the view model.
     */
    private fun todoJson(t: CalTodo): JSONObject = JSONObject().apply {
        put("id", t.bridgeId)
        put("uid", t.uid)
        put("projectId", t.collectionId)
        put("summary", t.summary)
        put("description", t.description)
        put("status", t.status)
        put("due", t.dueUtcMillis?.toString() ?: "")
        put("priority", t.priority)
        put("completedAt", t.completedUtcMillis?.toString() ?: "")
    }
}
