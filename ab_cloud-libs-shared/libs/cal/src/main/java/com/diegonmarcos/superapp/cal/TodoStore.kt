package com.diegonmarcos.superapp.cal

import android.content.Context
import org.json.JSONArray

/**
 * SharedPreferences-backed local cache of synced VTODOs and their
 * parent collections ("projects"), so the ToDo/Projects UI renders
 * instantly offline. Mirrors [CalendarStore]'s pattern exactly: one
 * prefs file, JSON strings as values, whole-collection swap on sync
 * (no merge — a CalDAV `calendar-query` doesn't expose incremental
 * diffs any more than the ICS feeds [CalendarStore] caches do).
 *
 * Writes (create/update/delete of a task) are intentionally NOT
 * queued here for offline replay — see [CalDav] callers in
 * CalBridge.kt. Every write is attempted against the server
 * synchronously; if that fails (offline, timeout, HTTP error) the
 * caller surfaces the failure in its `error` field and this cache is
 * left untouched, rather than optimistically applying a change here
 * that the server never actually received.
 */
object TodoStore {
    private const val PREFS = "todo_cache"
    private const val TODOS_PREFIX = "todos_"       // + collectionId (href)
    private const val COLLECTIONS_KEY = "collections"
    private const val LAST_SYNC_KEY = "last_sync"

    // Same rationale/cap as CalendarStore.MAX_EVENTS_PER_CALENDAR: prefs
    // values are held in memory and written as one blob per key, so an
    // unbounded task list would bloat memory/disk I/O on every read/write.
    private const val MAX_TODOS_PER_COLLECTION = 5000

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- collections ("projects") -------------------------------------

    fun replaceCollections(ctx: Context, collections: List<CalDavCollection>) {
        val arr = JSONArray()
        for (c in collections) {
            arr.put(org.json.JSONObject().apply {
                put("href", c.href)
                put("displayName", c.displayName)
                put("color", c.color)
            })
        }
        prefs(ctx).edit().putString(COLLECTIONS_KEY, arr.toString()).apply()
    }

    fun collections(ctx: Context): List<CalDavCollection> {
        val raw = prefs(ctx).getString(COLLECTIONS_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CalDavCollection(
                    href = o.optString("href", ""),
                    displayName = o.optString("displayName", ""),
                    color = o.optString("color", "#4a86e8"),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---- todos ----------------------------------------------------------

    /** Replace the full cached task set for one collection. Called
     *  after a successful fetch — always a wholesale swap, never a
     *  merge (see class kdoc). */
    fun replaceTodos(ctx: Context, collectionId: String, todos: List<CalTodo>) {
        val capped = if (todos.size > MAX_TODOS_PER_COLLECTION) {
            todos.take(MAX_TODOS_PER_COLLECTION)
        } else {
            todos
        }
        val arr = JSONArray()
        for (t in capped) arr.put(t.toJson())
        prefs(ctx).edit().putString(TODOS_PREFIX + collectionId, arr.toString()).apply()
    }

    fun todosFor(ctx: Context, collectionId: String): List<CalTodo> {
        val raw = prefs(ctx).getString(TODOS_PREFIX + collectionId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CalTodo.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** Union of every cached task across every known collection. */
    fun allTodos(ctx: Context): List<CalTodo> {
        val out = mutableListOf<CalTodo>()
        for (c in collections(ctx)) out.addAll(todosFor(ctx, c.href))
        return out
    }

    /** Insert-or-replace one task (matched by uid) within its
     *  collection's cached list. Used right after a successful
     *  server write so the UI reflects it without waiting for the
     *  next full sync. */
    fun upsertTodo(ctx: Context, todo: CalTodo) {
        val current = todosFor(ctx, todo.collectionId).toMutableList()
        val idx = current.indexOfFirst { it.uid == todo.uid }
        if (idx >= 0) current[idx] = todo else current.add(todo)
        replaceTodos(ctx, todo.collectionId, current)
    }

    /** Remove one task (matched by uid) from its collection's cached
     *  list, right after a successful server DELETE. */
    fun removeTodo(ctx: Context, collectionId: String, uid: String) {
        val current = todosFor(ctx, collectionId).filterNot { it.uid == uid }
        replaceTodos(ctx, collectionId, current)
    }

    /** Find one cached task by its bridge id — see [CalTodo.encodeBridgeId]. */
    fun findById(ctx: Context, id: String): CalTodo? {
        val (collectionId, uid) = CalTodo.decodeBridgeId(id) ?: return null
        return todosFor(ctx, collectionId).firstOrNull { it.uid == uid }
    }

    fun lastSync(ctx: Context): Long = prefs(ctx).getLong(LAST_SYNC_KEY, 0L)

    fun setLastSync(ctx: Context, millis: Long) {
        prefs(ctx).edit().putLong(LAST_SYNC_KEY, millis).apply()
    }
}
