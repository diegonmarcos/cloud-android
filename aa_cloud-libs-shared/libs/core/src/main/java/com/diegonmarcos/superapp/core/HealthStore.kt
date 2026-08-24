package com.diegonmarcos.superapp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * MyHealth — LOCAL-ONLY persistence sink.
 *
 * Lives in libs:core so libs:health pages can write and any future
 * consumer (launcher widget, dynamic-island hook, voice integration)
 * can read without pulling all of libs:health's Compose surface.
 *
 * CONTRACT — this is the single explicit promise the user trusted us with:
 *   • Every value put here STAYS here. SharedPreferences only.
 *   • No file is ever opened in MODE_WORLD_READABLE / WORLD_WRITEABLE.
 *   • No producer in this codebase ever wires HealthStore values into
 *     a network request. The instrumented test in libs:health asserts
 *     this with StrictMode (any network op during a snapshot read
 *     fails the test).
 *   • No automated sync, no Job, no broadcast. Reads are user-triggered.
 *
 * Schema (keys, all under PREFS = "health_store"):
 *   • last_snapshot_json    — last DailySnapshot serialised as JSON
 *   • last_snapshot_ts      — epoch-ms when readDailySnapshot() returned
 *   • snapshot_history_json — append-only JSONArray of (ts, snapshot)
 *                             pairs, capped at MAX_HISTORY.
 *   • source_last_seen_json — { pkg: epochMs } map of last record
 *                             observed for each producer (Garmin Connect,
 *                             Google Fit, Samsung Health, etc.).
 *   • permissions_last_grant_ts — epoch-ms the user last granted HC
 *                             permissions inside this app. Used by the
 *                             Permissions page to show "last asked".
 *
 * Why SharedPreferences and not Room: every value fits on one screen,
 * write volume is low (a few snapshots per day), and matching the rest
 * of libs:core (NotificationStore, WalletStore in libs:wallet) keeps
 * the on-device storage surface uniform — one file per concern.
 */
object HealthStore {
    private const val PREFS = "health_store"
    private const val KEY_LAST_JSON      = "last_snapshot_json"
    private const val KEY_LAST_TS        = "last_snapshot_ts"
    private const val KEY_HISTORY_JSON   = "snapshot_history_json"
    private const val KEY_SOURCES_JSON   = "source_last_seen_json"
    private const val KEY_PERM_GRANT_TS  = "permissions_last_grant_ts"
    private const val MAX_HISTORY        = 365  // ~one year of dailies

    /** Persist the latest daily snapshot. Appends to history (capped),
     *  updates `last_*`, and merges any sources mentioned. Returns the
     *  epoch-ms used as the canonical timestamp. */
    fun persistDaily(context: Context, snapshotJson: String): Long {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val historyRaw = p.getString(KEY_HISTORY_JSON, "[]") ?: "[]"
        val history = runCatching { JSONArray(historyRaw) }.getOrDefault(JSONArray())
        val entry = JSONObject().apply {
            put("ts", now)
            put("snapshot", JSONObject(snapshotJson))
        }
        history.put(entry)
        while (history.length() > MAX_HISTORY) history.remove(0)
        val sourceMapRaw = p.getString(KEY_SOURCES_JSON, "{}") ?: "{}"
        val sourceMap    = runCatching { JSONObject(sourceMapRaw) }.getOrDefault(JSONObject())
        runCatching {
            JSONObject(snapshotJson).optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) sourceMap.put(arr.getString(i), now)
            }
        }
        p.edit()
            .putString(KEY_LAST_JSON, snapshotJson)
            .putLong(KEY_LAST_TS, now)
            .putString(KEY_HISTORY_JSON, history.toString())
            .putString(KEY_SOURCES_JSON, sourceMap.toString())
            .apply()
        return now
    }

    /** Latest snapshot JSON, or null if nothing has been written yet. */
    fun lastSnapshotJson(context: Context): String? =
        prefs(context).getString(KEY_LAST_JSON, null)

    fun lastSnapshotTs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_TS, 0L)

    fun history(context: Context): JSONArray =
        runCatching { JSONArray(prefs(context).getString(KEY_HISTORY_JSON, "[]") ?: "[]") }
            .getOrDefault(JSONArray())

    /** Map of producer package name → epoch-ms last observed. Drives
     *  the Sources page and the Stats page's write-rate panel. */
    fun sourceLastSeen(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_SOURCES_JSON, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return obj.keys().asSequence().associateWith { obj.getLong(it) }
    }

    fun recordPermissionGrant(context: Context) {
        prefs(context).edit().putLong(KEY_PERM_GRANT_TS, System.currentTimeMillis()).apply()
    }
    fun lastPermissionGrantTs(context: Context): Long =
        prefs(context).getLong(KEY_PERM_GRANT_TS, 0L)

    /** Approximate on-disk footprint of HealthStore's own SharedPrefs
     *  file. Stats page surfaces this so the user can see exactly how
     *  much state we keep (always tiny — the snapshots are summary
     *  numbers, never raw record blobs). */
    fun footprintBytes(context: Context): Long = try {
        val f = context.applicationContext.getSharedPreferencesFile()
        if (f.exists()) f.length() else 0L
    } catch (_: Throwable) { 0L }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Context.getSharedPreferencesFile() =
        java.io.File(filesDir.parentFile, "shared_prefs/$PREFS.xml")
}
