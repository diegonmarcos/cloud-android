package com.diegonmarcos.superapp.firewall

import android.content.Context
import org.json.JSONObject

/** Traffic direction filter (the "general" axis). NONE = don't filter by
 *  direction; ALL = block everything; IN = block incoming; OUT = block outgoing. */
enum class Direction { NONE, ALL, IN, OUT }

/** Active network class. VPN = a cloud-VPN tunnel is the effective transport. */
enum class Transport { WIFI, CELLULAR, VPN, OTHER }

/**
 * Per-app firewall rule — a SUM of independent axes, all combined (a flow is
 * blocked if ANY axis blocks it):
 *
 *  - [wifi] / [cellular] / [vpn]  — is data allowed on that transport?
 *  - [background]                 — is data allowed while the app is backgrounded?
 *  - [direction]                  — general block-all / block-incoming / block-outgoing
 *
 * e.g. "Wi-Fi all data, cellular no data, no background data" =
 *      AppRule(wifi=true, cellular=false, vpn=true, background=false).
 *
 * Defaults are all-allow ([isDefault]) → an app with no configured axes is
 * unrestricted and isn't persisted.
 */
data class AppRule(
    val wifi: Boolean = true,
    val cellular: Boolean = true,
    val vpn: Boolean = true,
    val background: Boolean = true,
    val direction: Direction = Direction.NONE,
) {
    val isDefault: Boolean
        get() = wifi && cellular && vpn && background && direction == Direction.NONE

    /** true when the transport axis blocks data on [t]. */
    fun transportBlocks(t: Transport): Boolean = when (t) {
        Transport.WIFI -> !wifi
        Transport.CELLULAR -> !cellular
        Transport.VPN -> !vpn
        Transport.OTHER -> false
    }

    /** true when the direction axis blocks a [flow]-direction connection. */
    fun directionBlocks(flow: Direction): Boolean = when (direction) {
        Direction.ALL -> true
        Direction.IN -> flow == Direction.IN
        Direction.OUT -> flow == Direction.OUT
        Direction.NONE -> false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("wifi", wifi); put("cellular", cellular); put("vpn", vpn)
        put("background", background); put("direction", direction.name)
    }

    companion object {
        fun fromJson(o: JSONObject): AppRule = AppRule(
            wifi = o.optBoolean("wifi", true),
            cellular = o.optBoolean("cellular", true),
            vpn = o.optBoolean("vpn", true),
            background = o.optBoolean("background", true),
            direction = runCatching { Direction.valueOf(o.optString("direction", "NONE")) }
                .getOrDefault(Direction.NONE),
        )
    }
}

/**
 * Per-app rule persistence (user data, in SharedPreferences). Single source
 * of truth for the rules; the engine and UI both read/write through here.
 */
object FirewallRules {
    private const val FILE = "firewall_policies"
    private const val KEY = "rules" // { pkg: AppRule-json }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun root(ctx: Context): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString(KEY, "{}")!!) }.getOrDefault(JSONObject())

    /** Every package that has a rule stored. */
    fun rules(ctx: Context): Map<String, AppRule> {
        val r = root(ctx)
        return r.keys().asSequence().associateWith { AppRule.fromJson(r.getJSONObject(it)) }
    }

    /** The app's rule, or the all-allow default when unset. */
    fun rule(ctx: Context, pkg: String): AppRule = rules(ctx)[pkg] ?: AppRule()

    /** Store a rule. A default (all-allow) rule clears the entry. */
    fun setRule(ctx: Context, pkg: String, r: AppRule) {
        val root = root(ctx)
        if (r.isDefault) root.remove(pkg) else root.put(pkg, r.toJson())
        prefs(ctx).edit().putString(KEY, root.toString()).apply()
    }

    /** Apps with a non-default (restricting) rule — what the engine enforces. */
    fun configured(ctx: Context): Map<String, AppRule> = rules(ctx).filterValues { !it.isDefault }
}
