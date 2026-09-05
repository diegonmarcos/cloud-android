package com.diegonmarcos.superapp.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * THE FLOOR. Install an APK with ZERO privileged access.
 *
 * ## Why this exists
 * Every other install path in this module needs something the device might not
 * have: [com.diegonmarcos.superapp.updater.install.ShellInstall] needs a live
 * shell channel (Shizuku or a paired embedded adb client), and the
 * PackageInstaller session path is currently disabled outright (see
 * `Fleet.ALLOW_PROMPTING_FALLBACK`). So a phone that has lost its privileged
 * channel cannot install anything — INCLUDING the build that would give it the
 * channel back. That is a terminal state reachable by doing nothing, and the
 * only escape was a human with a USB cable, which does not scale past one
 * device, let alone thousands.
 *
 * This path needs none of it. It hands a `content://` URI to the ordinary
 * Android package installer with `ACTION_VIEW`, which is the same thing a file
 * manager does when you tap an APK. The user sees the standard system
 * confirmation and nothing else. It is slower and noisier than the silent
 * path, and that is precisely why it is the FALLBACK and not the default —
 * but it cannot be taken away by a lost pairing.
 *
 * ## What it does NOT do
 * It does not download, and it does not verify. It CANNOT: the only way in is
 * [Fleet.download], which returns a
 * [com.diegonmarcos.superapp.updater.apk.VerifiedApk], and that type's
 * constructor is private with every factory ending at `ApkIntegrity`. So the
 * bytes handed to the system installer here carry exactly the same guarantee
 * (release sha256 sidecar, or GHCR layer digest, or declared length) as the
 * bytes handed to the privileged installer. There is no second downloader and
 * no weakened check — writing one would have meant duplicating the code this
 * deliberately reuses.
 *
 * ## FileProvider authority
 * `<applicationId>.updater.fileprovider`, declared in THIS module's manifest
 * rather than each app's. Every constellation app shares the
 * `com.diegonmarcos.*` package prefix and several of them link `libs:updater`,
 * so a hand-written authority is a collision waiting to happen — two installed
 * apps declaring the same provider authority makes the second one fail to
 * install at all. `${applicationId}` is unique by construction, and declaring
 * it once here means a new consumer cannot forget it or get it wrong.
 */
object BootstrapInstall {

    private const val TAG = "Updater/Bootstrap"

    /** Must match the `android:authorities` in this module's manifest. */
    fun authority(ctx: Context): String = ctx.packageName + ".updater.fileprovider"

    /**
     * What the direct-install path is about to hand the system, described well
     * enough for the UI to show the user that it really is newer.
     *
     * [installedCode] is null when the app is not installed at all, which is a
     * legitimate bootstrap case (a lib the fleet needs, or a reinstall after a
     * failed update wiped the package).
     */
    data class Candidate(
        val appId: String,
        val label: String,
        val pkg: String,
        val file: File,
        val evidence: String,
        val versionName: String?,
        val versionCode: Long,
        val installedVersionName: String?,
        val installedCode: Long?,
    ) {
        /** True when this really is an upgrade. The UI must be able to say so
         *  rather than asking the user to trust a button. */
        val isNewer: Boolean get() = installedCode == null || versionCode > installedCode

        /** "3 days behind", or null when nothing decodable to say. */
        val behindBy: String? get() =
            installedCode?.let { BuildAge.behindBy(it, versionCode) }

        fun versionLine(): String {
            val have = when {
                installedCode == null -> "not installed"
                else -> "${installedVersionName ?: "?"} (${BuildAge.describe(installedCode)})"
            }
            val get = "${versionName ?: "?"} (${BuildAge.describe(versionCode)})"
            return "installed: $have\navailable: $get"
        }
    }

    /**
     * Fetch + verify [app] through the ordinary source ladder and describe the
     * result. Network, so off the main thread. Throws with the source's own
     * message when nothing could serve verified bytes — a silent null here
     * would put the caller right back in the "it did nothing and said nothing"
     * failure this whole feature exists to end.
     */
    fun stage(ctx: Context, app: Fleet.App): Candidate {
        val apk = Fleet.download(ctx, app)        // VerifiedApk — verification is its type
        val identity = com.diegonmarcos.superapp.updater.apk.ApkIntegrity.identify(ctx, apk.file)
        val installed = installedInfo(ctx, app)
        return Candidate(
            appId = app.id,
            label = app.label,
            pkg = identity?.pkg ?: app.pkg,
            file = apk.file,
            evidence = apk.evidence,
            versionName = archiveVersionName(ctx, apk.file),
            versionCode = identity?.versionCode ?: -1L,
            installedVersionName = installed?.first,
            installedCode = installed?.second,
        )
    }

    /**
     * The intent that shows the standard system install confirmation.
     *
     * `ACTION_VIEW` + `application/vnd.android.package-archive` is the public,
     * never-deprecated door. `ACTION_INSTALL_PACKAGE` targets the same screen
     * but has been deprecated since API 29 and is refused outright on some
     * OEM builds, so it is only the fallback — and a fallback we can only
     * reach when the primary resolves to nothing.
     */
    fun installIntent(ctx: Context, file: File): Intent {
        val uri = uriFor(ctx, file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(ctx.packageManager) != null) return view
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_RETURN_RESULT, false)
    }

    /**
     * A `content://` URI for [file], copying it into our cache first if it is
     * somewhere the provider does not cover.
     *
     * Both sources currently write straight into `cacheDir`, which the
     * provider's paths already export — so the copy is dead weight today and
     * exists only so that a future source writing elsewhere degrades to a slow
     * install rather than to an `IllegalArgumentException` at the last step.
     */
    private fun uriFor(ctx: Context, file: File): Uri {
        runCatching { return FileProvider.getUriForFile(ctx, authority(ctx), file) }
            .onFailure { Log.w(TAG, "not under an exported path (${file.absolutePath}): ${it.message} — copying") }
        val dir = File(ctx.cacheDir, "bootstrap").apply { mkdirs() }
        val copy = File(dir, file.name)
        file.copyTo(copy, overwrite = true)
        return FileProvider.getUriForFile(ctx, authority(ctx), copy)
    }

    /**
     * The whole affordance: fetch, verify, hand to the system installer.
     *
     * Returns the [Candidate] that was offered, or the failure. Never throws
     * at the caller — the recovery screen is the LAST surface a stranded user
     * reaches, and a crash there is the end of the line.
     */
    fun launch(ctx: Context, app: Fleet.App): Result<Candidate> = runCatching {
        val candidate = stage(ctx, app)
        Log.i(TAG, "direct install ${candidate.appId}: ${candidate.pkg} " +
                   "versionCode ${candidate.versionCode} (installed ${candidate.installedCode ?: "none"}), " +
                   candidate.evidence)
        ctx.startActivity(installIntent(ctx, candidate.file))
        candidate
    }.onFailure { Log.w(TAG, "direct install ${app.id} failed: ${it.message}") }

    /** Whether "Install unknown apps" is held. NOT a gate — the system asks for
     *  it inline when missing — but the UI should be able to warn first. */
    fun canRequestInstalls(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.packageManager.canRequestPackageInstalls()
        else true

    private fun installedInfo(ctx: Context, app: Fleet.App): Pair<String?, Long>? {
        val pkg = Fleet.installedId(ctx, app) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo(pkg, 0)
            pi.versionName to versionCodeOf(pi)
        }.getOrNull()
    }

    private fun archiveVersionName(ctx: Context, file: File): String? = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionName
    }.getOrNull()

    /** The installed versionCode of [pkg], or null when it is not installed. */
    fun installedVersionCode(ctx: Context, pkg: String): Long? = try {
        @Suppress("DEPRECATION")
        versionCodeOf(ctx.packageManager.getPackageInfo(pkg, 0))
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun versionCodeOf(pi: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
}
