package com.diegonmarcos.superapp.rss

import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * USER vs INFRA classification for ntfy channels.
 *
 * The registry is one flat list of snake_case topics, so a notification about
 * this phone and one about a container restarting arrived indistinguishable in
 * a single alphabetical list. A channel's scope is decided by its PREFIX, and
 * the prefix→scope map is declared in build.json::ui.ntfy rather than written
 * here (FIRE RULE #6): adding `me_` or a new `sec_`-style family is a data
 * edit, not a code change.
 *
 * Unmatched prefixes fall to the LAST declared scope instead of being dropped.
 * A channel appearing in the wrong bucket is visible and fixable; one filtered
 * out of both is invisible, and invisible is how a channel stops being watched.
 */
object NtfyScopes {

    data class Scope(val id: String, val label: String, val subtitle: String, val prefixes: Set<String>)

    @Volatile private var cached: List<Scope>? = null
    @Volatile private var cachedChannels: List<String>? = null

    private fun config(): JSONObject = runCatching {
        JSONObject(String(Base64.decode(BuildConfig.UI_NTFY_B64, Base64.NO_WRAP)))
    }.getOrDefault(JSONObject())

    fun load(): List<Scope> {
        cached?.let { return it }
        val arr = config().optJSONArray("scopes")
        val out = mutableListOf<Scope>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr!!.getJSONObject(i)
            val pa = o.optJSONArray("prefixes")
            val prefixes = buildSet {
                for (j in 0 until (pa?.length() ?: 0)) add(pa!!.getString(j))
            }
            out += Scope(
                id       = o.optString("id"),
                label    = o.optString("label", o.optString("id")),
                subtitle = o.optString("subtitle", ""),
                prefixes = prefixes,
            )
        }
        // A build with no declared scopes still has to render something, and
        // one unnamed bucket beats an empty screen.
        val safe = out.ifEmpty { listOf(Scope("all", "Channels", "", emptySet())) }
        cached = safe
        return safe
    }

    /** The scope owning [topic], by prefix; the last scope catches the rest. */
    fun scopeOf(topic: String, scopes: List<Scope> = load()): Scope {
        val prefix = topic.substringBefore('_', topic)
        return scopes.firstOrNull { prefix in it.prefixes } ?: scopes.last()
    }

    /** Vendored channel list — what the live registry served before it moved
     *  to cloud-data's y_old/ and started answering 404. Used when the fetch
     *  fails AND nothing is cached, so the page lists channels rather than an
     *  error on a cold start. */
    fun fallbackChannels(): List<String> {
        cachedChannels?.let { return it }
        val arr = config().optJSONArray("channels")
        val out = mutableListOf<String>()
        for (i in 0 until (arr?.length() ?: 0)) out.add(arr!!.getString(i))
        cachedChannels = out
        return out
    }
}
