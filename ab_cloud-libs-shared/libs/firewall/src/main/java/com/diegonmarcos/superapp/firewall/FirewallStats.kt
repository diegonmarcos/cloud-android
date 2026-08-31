package com.diegonmarcos.superapp.firewall

import android.content.Context
import org.json.JSONObject

/**
 * How many times each app's rule has ACTIVATED — the "Fires" column in the
 * rules table.
 *
 * ── WHAT THIS COUNTS, AND WHAT IT DOES NOT ───────────────────────────────
 * A "fire" is one transition of an app from allowed to blocked under the live
 * conditions: the transport changed to one its rule forbids, it dropped to the
 * background while `background = false`, or a direction/VPN axis started
 * applying. It is a count of RULE ACTIVATIONS, not of packets or connections.
 *
 * That distinction is forced by the engine, not chosen. [FirewallVpnService]
 * routes blocked apps into the tun and drains it — `input.read(buf)` and
 * discard. The drain loop sees raw bytes with no uid, so it cannot attribute a
 * single dropped packet to an app. Per-connection counts need firestack's
 * `Flow(protocol, uid, …)` callback, which is written and compiled
 * (libs/firewall/phase3-firestack) but not the active VpnService yet.
 *
 * So the UI must not call this "blocked connections". It is "times this rule
 * became active", and labelling it otherwise would put a number in a security
 * screen that means something different from what it says.
 *
 * Counting on the TRANSITION rather than per recompute is deliberate:
 * onConditionsChanged fires on every network change and every screen on/off, so
 * counting each pass would turn "how often did this rule bite" into "how often
 * did anything change", which is the same number for every app.
 */
object FirewallStats {

    private const val FILE = "firewall_stats"
    private const val KEY_FIRES = "fires"   // { pkg: Int }
    private const val KEY_LAST = "last"     // { pkg: epochMillis }
    private const val KEY_ACTIVE = "active" // packages blocked as of the last pass

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun obj(ctx: Context, key: String): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString(key, "{}")!!) }.getOrDefault(JSONObject())

    /** Times this app's rule has become active. 0 when it never has. */
    fun fires(ctx: Context, pkg: String): Int = obj(ctx, KEY_FIRES).optInt(pkg, 0)

    /** Epoch millis of the most recent activation, or 0. */
    fun lastFired(ctx: Context, pkg: String): Long = obj(ctx, KEY_LAST).optLong(pkg, 0L)

    /**
     * Record the currently-blocked set, incrementing only the apps that were
     * NOT blocked on the previous pass.
     *
     * Called from the engine on every conditions change. Idempotent for an
     * unchanged set, which is what makes the count meaningful: a phone sitting
     * on Wi-Fi with the screen toggling does not inflate every counter.
     */
    fun recordPass(ctx: Context, blocked: Set<String>) {
        val previous = obj(ctx, KEY_ACTIVE).keys().asSequence().toSet()
        val newlyBlocked = blocked - previous
        if (newlyBlocked.isNotEmpty()) {
            val fires = obj(ctx, KEY_FIRES)
            val last = obj(ctx, KEY_LAST)
            val now = System.currentTimeMillis()
            for (pkg in newlyBlocked) {
                fires.put(pkg, fires.optInt(pkg, 0) + 1)
                last.put(pkg, now)
            }
            prefs(ctx).edit()
                .putString(KEY_FIRES, fires.toString())
                .putString(KEY_LAST, last.toString())
                .apply()
        }
        // Store the set even when nothing changed — it is the baseline the next
        // pass diffs against, and a stale one would re-count on the next change.
        val active = JSONObject()
        for (pkg in blocked) active.put(pkg, 1)
        prefs(ctx).edit().putString(KEY_ACTIVE, active.toString()).apply()
    }

    /** Clear all counters. The active-set baseline goes too, or the next pass
     *  would see every currently-blocked app as "newly blocked" and re-count. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(KEY_FIRES).remove(KEY_LAST).remove(KEY_ACTIVE).apply()
    }

    /** Total activations across all apps — the header line in the table. */
    fun totalFires(ctx: Context): Int {
        val f = obj(ctx, KEY_FIRES)
        return f.keys().asSequence().sumOf { f.optInt(it, 0) }
    }
}
