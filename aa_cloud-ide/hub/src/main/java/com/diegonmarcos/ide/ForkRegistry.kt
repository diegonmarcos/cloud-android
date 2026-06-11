package com.diegonmarcos.ide

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import org.json.JSONObject

/**
 * The set of forks the hub knows about, read from `build.json::forks` baked into
 * BuildConfig.FORKS_JSON_B64 — NO hardcoded list (FIRE rule 6). Each entry maps a
 * domain ("editor"/"files"/"utils") to the fork's applicationId, tracker dir and
 * blocked state, plus a runtime check of whether that APK is installed.
 */
data class Fork(
    val domain: String,
    val appId: String,
    val trackerDir: String,
    val license: String,
    val runtime: String,
    val priority: Int,
    val blockedOn: String?,
) {
    fun isInstalled(ctx: Context): Boolean =
        try {
            ctx.packageManager.getPackageInfo(appId, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}

object ForkRegistry {
    val forks: List<Fork> by lazy { parse() }

    fun byDomain(domain: String): Fork? = forks.firstOrNull { it.domain == domain }

    private fun parse(): List<Fork> {
        val json = String(Base64.decode(BuildConfig.FORKS_JSON_B64, Base64.DEFAULT))
        val root = JSONObject(json)
        return root.keys().asSequence().map { domain ->
            val o = root.getJSONObject(domain)
            Fork(
                domain = domain,
                appId = o.optString("app_id"),
                trackerDir = o.optString("tracker_dir"),
                license = o.optString("license"),
                runtime = o.optString("runtime"),
                priority = o.optInt("priority", 99),
                blockedOn = o.optString("blocked_on").takeIf { it.isNotEmpty() && it != "null" },
            )
        }.sortedBy { it.priority }.toList()
    }
}
