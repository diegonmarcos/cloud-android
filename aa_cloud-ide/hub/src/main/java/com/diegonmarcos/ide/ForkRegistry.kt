package com.diegonmarcos.ide

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import org.json.JSONObject

/**
 * The set of apps the hub knows about, read from `build.json::forks` baked into
 * BuildConfig.FORKS_JSON_B64 — NO hardcoded list (FIRE rule 6).
 *
 * Wrapper model (owner decision 2026-06-11): a fork with an `embedded` block is
 * SELF-CONTAINED — its real upstream APK rides inside this hub APK
 * (assets/forks/<domain>.apk) and installs as `embeddedPackage`
 * (e.g. com.foxdebug.acode / com.amaze.filemanager). The HOME shows ONLY
 * embedded forks: exactly two — Acode + Amaze File Manager. Launch / installed
 * checks use [launchPackage] (the embedded id when present, else our fork id).
 */
data class Fork(
    val domain: String,
    val appId: String,
    val displayName: String,
    val trackerDir: String,
    val license: String,
    val runtime: String,
    val pinnedTag: String,
    val priority: Int,
    val blockedOn: String?,
    val embeddedPackage: String?,
) {
    /** The package this fork actually runs as on-device today. */
    val launchPackage: String get() = embeddedPackage ?: appId

    /** Bundled asset name inside assets/forks/ (seeded by bundle-forks). */
    val assetName: String get() = "$domain.apk"

    val isEmbedded: Boolean get() = embeddedPackage != null

    fun isInstalled(ctx: Context): Boolean =
        try {
            ctx.packageManager.getPackageInfo(launchPackage, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** Who installed the on-device copy (Android records the installer pkg). */
    fun installerOf(ctx: Context): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30)
            ctx.packageManager.getInstallSourceInfo(launchPackage).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(launchPackage)
    }.getOrNull()

    /** TRUE child APK: installed on this phone BY the Cloud-IDE wrapper (from
     *  its internal bundle) — not a pre-existing store/F-Droid copy. */
    fun isChildInstall(ctx: Context): Boolean =
        isInstalled(ctx) && installerOf(ctx) == ctx.packageName
}

object ForkRegistry {
    val forks: List<Fork> by lazy { parse() }

    /** The home surface: ONLY the self-contained apps (Acode + Amaze FM). */
    val homeForks: List<Fork> get() = forks.filter { it.isEmbedded }

    fun byDomain(domain: String): Fork? = forks.firstOrNull { it.domain == domain }

    private fun parse(): List<Fork> {
        val json = String(Base64.decode(BuildConfig.FORKS_JSON_B64, Base64.DEFAULT))
        val root = JSONObject(json)
        return root.keys().asSequence().map { domain ->
            val o = root.getJSONObject(domain)
            Fork(
                domain = domain,
                appId = o.optString("app_id"),
                displayName = o.optString("display_name").ifEmpty {
                    domain.replaceFirstChar { it.uppercase() }
                },
                trackerDir = o.optString("tracker_dir"),
                license = o.optString("license"),
                runtime = o.optString("runtime"),
                pinnedTag = o.optString("pinned_tag"),
                priority = o.optInt("priority", 99),
                blockedOn = o.optString("blocked_on").takeIf { it.isNotEmpty() && it != "null" },
                embeddedPackage = o.optJSONObject("embedded")?.optString("package")
                    ?.takeIf { it.isNotEmpty() },
            )
        }.sortedBy { it.priority }.toList()
    }
}
