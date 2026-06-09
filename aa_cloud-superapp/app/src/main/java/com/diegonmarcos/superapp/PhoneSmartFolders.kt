package com.diegonmarcos.superapp

import android.content.Context
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
 *   • install_source_not  — PackageManager.getInstallSourceInfo
 *                           .installingPackageName not in values.
 *                           Catches sideloads (null source) + F-Droid
 *                           + Aurora + GitHub APKs. API 30+; pre-30
 *                           falls back to deprecated getInstallerPackageName.
 */
object PhoneSmartFolders {

    data class Rule(val type: String, val values: List<String>) {
        fun matches(ctx: Context, app: PhoneApp): Boolean = when (type) {
            "pkg_prefix" -> values.any { app.packageName.startsWith(it) }
            "pkg_eq"     -> values.any { app.packageName == it }
            "install_source_not" -> {
                val src = installerOf(ctx, app.packageName)
                // null source = pure sideload (no installer recorded) →
                // counts as "not in Play" so the alt-stores rule keeps it.
                src == null || !values.contains(src)
            }
            else -> false
        }

        private fun installerOf(ctx: Context, pkg: String): String? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getInstallerPackageName(pkg)
            }
        }.getOrNull()
    }

    data class SmartFolder(val id: String, val title: String, val rule: Rule)

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
            val valuesArr = ruleObj.optJSONArray("values") ?: continue
            val values = mutableListOf<String>()
            for (j in 0 until valuesArr.length()) {
                val v = valuesArr.optString(j)
                if (v.isNotBlank()) values.add(v)
            }
            if (type.isBlank() || values.isEmpty()) continue
            out.add(SmartFolder(id, title, Rule(type, values)))
        }
        out
    }.getOrDefault(emptyList())
}
