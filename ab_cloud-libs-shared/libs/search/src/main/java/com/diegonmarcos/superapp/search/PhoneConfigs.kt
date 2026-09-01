package com.diegonmarcos.superapp.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Installed Android Settings screens, as search hits — enumerated from the
 * device's Settings package(s): whatever resolves ACTION_SETTINGS, plus any
 * `*.settings` package (OEMs ship several). Only EXPORTED, ENABLED activities
 * that carry their OWN label are indexed, so the list reads "Wi-Fi",
 * "Bluetooth", "Display"… instead of dozens of identical "Settings" rows.
 *
 * Pure platform, no app policy, so it lives in the library rather than behind
 * [SearchSheet.Host]: every app that wants a phone-settings scope wants
 * exactly this. Contrast with the installed-apps scope, which stays host-side
 * because an app may filter it (a guest profile, a whitelist) and the library
 * must not quietly hand out the full app list.
 */
object PhoneConfigs {

    fun hits(ctx: Context, scopeId: String): List<SearchHit> {
        val pm = ctx.packageManager
        val pkgs = linkedSetOf<String>()
        runCatching {
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
                ?.activityInfo?.packageName?.let { pkgs += it }
        }
        // OEM secondary settings packages (Samsung etc.) — keep it generic.
        runCatching {
            pm.getInstalledApplications(0)
                .map { it.packageName }
                .filter { it.endsWith(".settings") || it.contains(".settings.") }
                .forEach { pkgs += it }
        }
        val out = mutableListOf<SearchHit>()
        for (pkg in pkgs) {
            val pi = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: continue
            for (ai in pi.activities ?: emptyArray()) {
                if (!ai.exported || !ai.enabled) continue
                // Self-labelled only — skip activities that fall back to the
                // app label (avoids a wall of identical "Settings" rows).
                if (ai.labelRes == 0 && ai.nonLocalizedLabel == null) continue
                val label = ai.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) continue
                out += SearchHit(label, "Settings · $pkg", scopeId,
                    settingsComponent = ComponentName(pkg, ai.name))
            }
        }
        // Stable, de-duped by visible label (OEMs alias the same screen).
        return out.distinctBy { it.label.lowercase() }.sortedBy { it.label.lowercase() }
    }
}
