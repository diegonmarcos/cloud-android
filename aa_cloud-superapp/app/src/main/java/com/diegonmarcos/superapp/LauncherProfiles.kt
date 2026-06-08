package com.diegonmarcos.superapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data-driven launcher-profile behavior decoder + filter helpers.
 *
 * Reads BuildConfig.UI_LAUNCHER_PROFILES_B64 (baked from
 * build.json::ui.launcher_profiles) once at first access and exposes a
 * typed [Behavior] per profile id. PhoneAppsFragment / WireGuard
 * control / Section dispatch call into these helpers instead of
 * hardcoding per-profile branches.
 *
 * Three policy axes today (extend by adding a JSON key + a field):
 *   • app_filter    — "all" | "whitelist"
 *   • whitelist     — list of TAGS (not package names!) — see resolveAllowedPackages
 *   • wireguard     — "on" | "off"
 *   • allow_intents — "all" | List<tag>
 *
 * FIRE rule 6 — adding a new profile is a JSON entry; Kotlin reads.
 */
object LauncherProfiles {

    data class Behavior(
        val appFilter:     String,        // "all" | "whitelist"
        val whitelist:     List<String>,  // tag list — empty when appFilter == "all"
        val wireguard:     String,        // "on" | "off"
        val allowIntents:  Any,           // "all" (String) or List<String>
    ) {
        val isLockdown: Boolean get() = appFilter == "whitelist"
        val wireguardOff: Boolean get() = wireguard.equals("off", ignoreCase = true)

        companion object {
            val PASSTHROUGH = Behavior(
                appFilter    = "all",
                whitelist    = emptyList(),
                wireguard    = "on",
                allowIntents = "all",
            )
        }
    }

    private val byId: Map<String, Behavior> by lazy { parse() }

    fun behaviorFor(profileId: String): Behavior = byId[profileId] ?: Behavior.PASSTHROUGH
    fun behaviorFor(profile: LauncherProfile): Behavior = behaviorFor(profile.id)

    /** Resolve the set of allowed package names for the active profile.
     *  Returns null when [Behavior.appFilter] is "all" (passthrough —
     *  caller should NOT filter). Returns a possibly-empty set otherwise. */
    fun allowedPackagesFor(context: Context, profile: LauncherProfile): Set<String>? {
        val b = behaviorFor(profile)
        if (!b.isLockdown) return null
        val out = mutableSetOf<String>()
        b.whitelist.forEach { tag ->
            out.addAll(packagesForTag(context, tag))
        }
        return out
    }

    /** Map a whitelist tag → concrete installed package names. Today
     *  we resolve "browser" via Android's standard browser-app probe;
     *  other tags map straight through as package-name literals (so
     *  a JSON whitelist of ["com.example.myapp"] still works). */
    private fun packagesForTag(context: Context, tag: String): Set<String> = when (tag) {
        "browser" -> browserPackages(context)
        else      -> setOf(tag)
    }

    /** Every app that handles `Intent.ACTION_VIEW http://` — the
     *  canonical "is this a browser?" probe. Captures the system
     *  browser, Chrome, Firefox, Brave, Samsung Internet, etc. */
    private fun browserPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DEFAULT_ONLY
        val matches: List<ResolveInfo> = try {
            pm.queryIntentActivities(probe, flags)
        } catch (_: Throwable) { emptyList() }
        return matches.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private fun parse(): Map<String, Behavior> {
        val raw = runCatching {
            String(Base64.decode(BuildConfig.UI_LAUNCHER_PROFILES_B64, Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = mutableMapOf<String, Behavior>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val b = o.optJSONObject("behavior") ?: JSONObject()
            val whitelist = mutableListOf<String>()
            val wlArr = b.optJSONArray("whitelist") ?: JSONArray()
            for (j in 0 until wlArr.length()) {
                val s = wlArr.optString(j); if (s.isNotBlank()) whitelist.add(s)
            }
            val rawAllow = b.opt("allow_intents")
            val allow: Any = when (rawAllow) {
                is JSONArray -> (0 until rawAllow.length()).mapNotNull {
                    rawAllow.optString(it).ifBlank { null }
                }
                is String -> rawAllow
                else -> "all"
            }
            out[id] = Behavior(
                appFilter    = b.optString("app_filter",   "all"),
                whitelist    = whitelist,
                wireguard    = b.optString("wireguard",    "on"),
                allowIntents = allow,
            )
        }
        return out
    }
}
