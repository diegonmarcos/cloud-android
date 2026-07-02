package com.diegonmarcos.superapp.firewall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Traffic direction a rule blocks. IN/OUT are enforced only by the
 *  firestack netstack (Phase 3); the drain-engine treats non-IN as block-all. */
enum class Direction { ALL, IN, OUT;
    fun matches(flow: Direction) = this == ALL || this == flow
}

/** Active network class. VPN = a cloud-VPN tunnel is the effective transport. */
enum class Transport { WIFI, CELL, VPN, OTHER }

/** Per-app energy state = (device screen) × (app foreground). ACTIVE only
 *  when the screen is on AND the app is foreground; BACKGROUND otherwise. */
enum class Energy { ACTIVE, BACKGROUND }

/**
 * One atomic block condition. A flow is blocked by this rule when its
 * direction matches [block] AND the current [Transport] is in [transports]
 * AND the current [Energy] is in [energy]. Presets ship as DATA in
 * assets/firewall_presets.json (never hardcoded here — CLAUDE rule 6).
 */
data class RuleSpec(
    val id: String,
    val label: String,
    val block: Direction,
    val transports: Set<Transport>,
    val energy: Set<Energy>,
) {
    /** Direction-aware flow test — true when this rule blocks the flow. */
    fun blocks(flow: Direction, t: Transport, e: Energy): Boolean =
        block.matches(flow) && t in transports && e in energy

    /** Condition-only test (direction ignored) — the shipping drain-engine can
     *  only drop-all, so it applies a rule whenever its condition holds and it
     *  isn't an inbound-only rule (which drop-all can't express). */
    fun appliesInterim(t: Transport, e: Energy): Boolean =
        block != Direction.IN && t in transports && e in energy

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label); put("block", block.name)
        put("transports", JSONArray(transports.map { it.name }))
        put("energy", JSONArray(energy.map { it.name }))
    }

    companion object {
        fun fromJson(o: JSONObject): RuleSpec = RuleSpec(
            id = o.getString("id"),
            label = o.optString("label", o.getString("id")),
            block = Direction.valueOf(o.getString("block")),
            transports = o.getJSONArray("transports").toEnumSet { Transport.valueOf(it) },
            energy = o.getJSONArray("energy").toEnumSet { Energy.valueOf(it) },
        )
    }
}

private inline fun <T> JSONArray.toEnumSet(map: (String) -> T): Set<T> =
    (0 until length()).map { map(getString(it)) }.toSet()

/**
 * Loads predefined presets from the bundled asset (single source of truth,
 * data-driven) and persists the user's per-app policies (user data, in
 * SharedPreferences). A policy = the list of rules assigned to one package;
 * a flow is blocked if ANY rule in the list applies (OR).
 */
object FirewallRules {
    private const val ASSET = "firewall_presets.json"
    private const val FILE = "firewall_policies"
    private const val KEY = "policies" // { pkg: [RuleSpec, ...] }

    @Volatile private var presetCache: List<RuleSpec>? = null

    /** The predefined building-block rules, loaded once from the asset. */
    fun presets(ctx: Context): List<RuleSpec> = presetCache ?: synchronized(this) {
        presetCache ?: run {
            val json = ctx.applicationContext.assets.open(ASSET)
                .bufferedReader().use { it.readText() }
            JSONObject(json).getJSONArray("presets")
                .let { a -> (0 until a.length()).map { RuleSpec.fromJson(a.getJSONObject(it)) } }
                .also { presetCache = it }
        }
    }

    fun preset(ctx: Context, id: String): RuleSpec? = presets(ctx).firstOrNull { it.id == id }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Every package that has at least one rule assigned. */
    fun policies(ctx: Context): Map<String, List<RuleSpec>> {
        val root = runCatching { JSONObject(prefs(ctx).getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
        return root.keys().asSequence().associateWith { pkg ->
            root.getJSONArray(pkg).let { a ->
                (0 until a.length()).map { RuleSpec.fromJson(a.getJSONObject(it)) }
            }
        }
    }

    fun policy(ctx: Context, pkg: String): List<RuleSpec> = policies(ctx)[pkg].orEmpty()

    /** Replace a package's policy. Empty list clears it (app fully allowed). */
    fun setPolicy(ctx: Context, pkg: String, rules: List<RuleSpec>) {
        val root = runCatching { JSONObject(prefs(ctx).getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
        if (rules.isEmpty()) root.remove(pkg)
        else root.put(pkg, JSONArray(rules.map { it.toJson() }))
        prefs(ctx).edit().putString(KEY, root.toString()).apply()
    }
}
