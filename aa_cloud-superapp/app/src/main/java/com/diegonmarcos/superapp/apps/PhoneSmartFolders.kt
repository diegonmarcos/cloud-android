package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.datamanager.AppNetworkProvider
import com.diegonmarcos.superapp.datamanager.AppUsageProvider

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Base64
import org.json.JSONArray

/**
 * Smart Folders rendered at the bottom of the Home Apps/Phone tab.
 * Orthogonal cross-cut view: apps still belong to their topical
 * folder above; Smart Folders are dynamic filters over the SAME
 * LauncherApps enumeration the page uses.
 *
 * Data source = build.json::ui.phone_smart_folders, baked into
 * BuildConfig.UI_PHONE_SMART_FOLDERS_B64 by app/build.gradle.
 *
 * Rule types:
 *   • pkg_prefix          — any pkg startsWith one of values
 *   • pkg_eq              — any pkg equals one of values
 *   • recent_used         — RANKING. Packages by most-recent use
 *                           (UsageStatsManager.lastTimeUsed), capped at
 *                           `limit`. Needs usage-access.
 *   • active_since        — RANKING. Packages used within the last
 *                           `window_h` hours, most-recent first (optional
 *                           `limit`). Needs usage-access.
 *   • most_opened         — RANKING. Packages by launch count
 *                           (MOVE_TO_FOREGROUND events, 7d), capped at
 *                           `limit`. Needs usage-access.
 *   • top_network         — RANKING. Packages by total bytes rx+tx
 *                           (NetworkStatsManager, 7d), capped at `limit`.
 *                           Needs usage-access.
 *   • top_battery         — RANKING. Packages by estimated mAh
 *                           (libs:battery EnergyWatchdog.perAppEstimate),
 *                           capped at `limit`. Needs usage-access.
 *   • most_used_time      — RANKING. Packages by total foreground time
 *                           (UsageStats.totalTimeInForeground sum, 7d), capped
 *                           at `limit`. Needs usage-access.
 *   All ranking rules degrade to an empty folder (hidden) when the
 *   usage-access grant is missing — see the providers' runCatching.
 *   • install_source_not  — PackageManager.getInstallSourceInfo
 *                           .installingPackageName not in values.
 *                           CONSERVATIVE: requires a CONCRETE non-Play
 *                           installer AND excludes system apps. Null
 *                           installer (Smart-Switch migrations, OEM
 *                           pre-installs whose metadata was stripped)
 *                           is NOT counted as alt-store — too many
 *                           false-positives (Booking, OneNote, Outlook,
 *                           Samsung Notes, Wearable, Contacts, Configs
 *                           all leak in otherwise). System apps
 *                           (ApplicationInfo.FLAG_SYSTEM) are excluded
 *                           regardless of installer. API 30+;
 *                           pre-30 falls back to deprecated
 *                           getInstallerPackageName.
 */
object PhoneSmartFolders {

    data class Rule(
        val type: String,
        val values: List<String>,
        val limit: Int = 0,
        val windowH: Int = 0,
    ) {
        fun matches(ctx: Context, app: PhoneApp): Boolean = when (type) {
            "pkg_prefix" -> values.any { app.packageName.startsWith(it) }
            "pkg_eq"     -> values.any { app.packageName == it }
            "install_source_in" -> {
                // Show ONLY apps whose install-source-of-record (installing
                // OR initiating package) is one of `values` — bucket apps by
                // the specific store/installer that owns their updates.
                // Verified against real on-device values via
                // /api/phone/classify (installing/initiating/system dump).
                val srcs = installSourcesOf(ctx, app.packageName)
                srcs.any { values.contains(it) }
            }
            "install_source_not" -> {
                if (isSystemApp(ctx, app.packageName)) false
                else {
                    // Show ONLY apps whose source we KNOW and where NONE of the
                    // source fields (installing + initiating) is a first-party
                    // store. Checking both fields stops Play apps leaking when
                    // the installing package differs from the initiating one
                    // (common after updates / restores).
                    val srcs = installSourcesOf(ctx, app.packageName)
                    srcs.isNotEmpty() && srcs.none { values.contains(it) }
                }
            }
            // recently_installed is a ranking rule (sort + take), handled in
            // [SmartFolder.select], not a per-app predicate.
            else -> false
        }

        private fun isSystemApp(ctx: Context, pkg: String): Boolean = runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }.getOrDefault(false)

        /** The concrete (non-null) install-source package names for [pkg] —
         *  installingPackageName AND initiatingPackageName on API 30+, or the
         *  single deprecated installer pre-30. Empty = unknown source. */
        private fun installSourcesOf(ctx: Context, pkg: String): Set<String> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = ctx.packageManager.getInstallSourceInfo(pkg)
                setOfNotNull(info.installingPackageName, info.initiatingPackageName)
            } else {
                @Suppress("DEPRECATION")
                setOfNotNull(ctx.packageManager.getInstallerPackageName(pkg))
            }
        }.getOrDefault(emptySet())
    }

    data class SmartFolder(val id: String, val title: String, val rule: Rule, val group: String? = null) {
        /** The apps this smart folder shows, from the master [apps] list.
         *  Predicate rules filter; the ranking rules (recently_installed +
         *  the usage/network/battery ones) order a provider's ranked
         *  package list against `apps` and cap at `limit`. */
        fun select(ctx: Context, apps: List<PhoneApp>): List<PhoneApp> {
            val cap = if (rule.limit > 0) rule.limit else 7
            return when (rule.type) {
                "recently_installed" -> apps
                    .filter { it.firstInstallTime > 0L }
                    .sortedByDescending { it.firstInstallTime }
                    .take(if (rule.limit > 0) rule.limit else 12)
                "recent_used" -> rankToApps(AppUsageProvider.recentUsed(ctx), apps).take(cap)
                "active_since" -> {
                    val ranked = rankToApps(
                        AppUsageProvider.activeSince(ctx, if (rule.windowH > 0) rule.windowH else 48),
                        apps,
                    )
                    if (rule.limit > 0) ranked.take(rule.limit) else ranked
                }
                "most_opened" -> rankToApps(AppUsageProvider.mostOpened(ctx), apps).take(cap)
                "most_used_time" -> rankToApps(AppUsageProvider.mostUsedTime(ctx), apps).take(cap)
                "top_network" -> rankToApps(AppNetworkProvider.topByBytes(ctx), apps).take(cap)
                "top_battery" -> rankToApps(
                    EnergyWatchdog.perAppEstimate(ctx).map { it.pkg }, apps).take(cap)
                else -> apps.filter { rule.matches(ctx, it) }
            }
        }

        /** Map a provider's ranked package list onto the launchable master
         *  [apps], preserving rank order and dropping packages not in the
         *  set (uninstalled, non-launchable, profile-filtered, or our own). */
        private fun rankToApps(ranked: List<String>, apps: List<PhoneApp>): List<PhoneApp> {
            if (ranked.isEmpty()) return emptyList()
            val byPkg = apps.associateBy { it.packageName }
            return ranked.mapNotNull { byPkg[it] }
        }
    }

    fun loadFromBuildConfig(): List<SmartFolder> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_PHONE_SMART_FOLDERS_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        val out = mutableListOf<SmartFolder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title")
            val ruleObj = o.optJSONObject("rule") ?: continue
            if (id.isBlank() || title.isBlank()) continue
            val type = ruleObj.optString("type")
            if (type.isBlank()) continue
            val values = mutableListOf<String>()
            ruleObj.optJSONArray("values")?.let { valuesArr ->
                for (j in 0 until valuesArr.length()) {
                    val v = valuesArr.optString(j)
                    if (v.isNotBlank()) values.add(v)
                }
            }
            val limit = ruleObj.optInt("limit", 0)
            val windowH = ruleObj.optInt("window_h", 0)
            // Predicate rules need `values`; limit-ranked rules need a
            // positive `limit`; active_since needs a positive `window_h`.
            val valid = when (type) {
                "recently_installed", "recent_used",
                "most_opened", "top_network", "top_battery", "most_used_time" -> limit > 0
                "active_since" -> windowH > 0
                else -> values.isNotEmpty()
            }
            if (!valid) continue
            val group = o.optString("group").takeIf { it.isNotBlank() }
            out.add(SmartFolder(id, title, Rule(type, values, limit, windowH), group))
        }
        out
    }.getOrDefault(emptyList())
}
