package com.diegonmarcos.superapp.firewall

import android.content.Context
import org.json.JSONObject

/** Traffic direction filter (the "general" axis). NONE = don't filter by
 *  direction; ALL = block everything; IN = block incoming; OUT = block outgoing. */
enum class Direction { NONE, ALL, IN, OUT }

/** Active network class. VPN = a cloud-VPN tunnel is the effective transport. */
enum class Transport { WIFI, CELLULAR, VPN, OTHER }

/**
 * Cloud-VPN mode — a STRONG override (not a parallel toggle). When set to a
 * tunnel, the app becomes VPN-ONLY: it can talk ONLY through that WireGuard
 * tunnel, and the [AppRule.wifi] / [AppRule.cellular] toggles are ignored.
 *  - [NONE]           — no override; use the wifi/cellular toggles.
 *  - [WG0_ONLY]       — force routing through wg0 (private mesh) only.
 *  - [WG_PUBLIC_ONLY] — force routing through wg-public only.
 */
enum class VpnMode { NONE, WG0_ONLY, WG_PUBLIC_ONLY }

/**
 * Per-app firewall rule. The parallel axes ([wifi], [cellular], [background])
 * combine as a SUM (blocked if ANY blocks). [vpnMode] is a STRONG override: a
 * non-NONE value forces the app VPN-only through that tunnel and overrides the
 * wifi/cellular toggles. [background] and [direction] still apply on top.
 *
 * e.g. "Wi-Fi all data, cellular no data, no background data" =
 *      AppRule(wifi=true, cellular=false, background=false).
 *      "wg0 VPN only" = AppRule(vpnMode=WG0_ONLY).
 */
data class AppRule(
    val wifi: Boolean = true,
    val cellular: Boolean = true,
    val background: Boolean = true,
    val vpnMode: VpnMode = VpnMode.NONE,
    val direction: Direction = Direction.NONE,
) {
    val isDefault: Boolean
        get() = wifi && cellular && background && vpnMode == VpnMode.NONE && direction == Direction.NONE

    /** VPN-only override active — the app may talk only through the VPN. */
    val vpnOnly: Boolean get() = vpnMode != VpnMode.NONE

    /**
     * true when the transport axis blocks data on [t]. Under a VPN-only
     * override, everything except the VPN transport is blocked (the wifi /
     * cellular toggles no longer apply); the firestack merge routes the app
     * through the specific tunnel ([vpnMode]) — the interim drain-engine can
     * only gate on "is the active transport a VPN".
     */
    fun transportBlocks(t: Transport): Boolean =
        if (vpnOnly) t != Transport.VPN
        else when (t) {
            Transport.WIFI -> !wifi
            Transport.CELLULAR -> !cellular
            Transport.VPN -> false
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
        put("wifi", wifi); put("cellular", cellular); put("background", background)
        put("vpnMode", vpnMode.name); put("direction", direction.name)
    }

    companion object {
        fun fromJson(o: JSONObject): AppRule = AppRule(
            wifi = o.optBoolean("wifi", true),
            cellular = o.optBoolean("cellular", true),
            background = o.optBoolean("background", true),
            vpnMode = runCatching { VpnMode.valueOf(o.optString("vpnMode", "NONE")) }
                .getOrDefault(VpnMode.NONE),
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
