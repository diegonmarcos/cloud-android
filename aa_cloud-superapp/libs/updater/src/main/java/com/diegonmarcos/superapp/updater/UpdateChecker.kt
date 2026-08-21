package com.diegonmarcos.superapp.updater

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Detect whether GHCR has an APK whose sha256 differs from the currently
 * installed APK. Returns null when no update is needed.
 */
internal class UpdateChecker(private val context: Context) {
    private val tag = "Updater/Check"
    private val client = GhcrClient()

    /** A remote update that differs from the installed APK. Carries the manifest
     *  info + the pull token so [download] can fetch the blob in the same run
     *  without a second manifest/token round-trip. */
    data class Available(
        val remoteDigest: String,
        val remoteSize: Long,
        val assetTitle: String,
        val token: String,
    )

    /** Manifest-only check — NO blob download. Returns [Available] when GHCR has
     *  a differing APK for this ABI, null when already up to date. Cheap enough
     *  to run on metered networks so the caller can decide download vs. prompt. */
    fun available(): Available? {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        try {
            val token = client.token()
            // ABI-aware: x86_64 (Waydroid/emulator) pulls `latest-x86_64`,
            // arm64 phones pull `latest`. See AbiUpdateTag.
            val layer = client.manifest(AbiUpdateTag.current(), token)
            // Code-identity short-circuit: same git commit → same code even if
            // APK bytes differ (non-reproducible build) → no spurious update.
            if (layer.revision != null && layer.revision == BuildConfig.GIT_SHORT_SHA) {
                Log.i(tag, "remote revision ${layer.revision} == installed — up to date")
                UpdateProgress.reset()
                return null
            }
            val currentDigest = "sha256:" + currentInstalledApkSha256()
            if (currentDigest == layer.digest) {
                Log.i(tag, "current matches remote: $currentDigest")
                UpdateProgress.reset()
                return null
            }
            Log.i(tag, "update available: $currentDigest → ${layer.digest}")
            return Available(layer.digest, layer.size, layer.title, token)
        } catch (e: GhcrClient.HttpException) {
            // A 404 means the GHCR tag for THIS device's ABI hasn't been
            // published yet (e.g. an x86_64 build before its CI variant has
            // shipped). That's "no update available", NOT a failure — don't
            // surface a scary URL error. Any other status is a real failure.
            if (e.code == 404) {
                Log.i(tag, "no remote build for this ABI yet (404: ${e.target}) — treating as up to date")
                UpdateProgress.reset()
                return null
            }
            UpdateProgress.update(UpdateProgress.State.Failed(e.message ?: e.toString()))
            throw e
        } catch (t: Throwable) {
            UpdateProgress.update(UpdateProgress.State.Failed(t.message ?: t.toString()))
            throw t
        }
    }

    /** Fetch the blob for an [Available] update, verify its sha256, and return
     *  the on-disk APK. [shouldCancel] is polled during the download so the
     *  Cancel button aborts it. Sets Downloading progress; on digest mismatch
     *  or error flips to Failed and throws. */
    fun download(a: Available, shouldCancel: () -> Boolean = { false }): File {
        try {
            val target = File(context.cacheDir, "update-${a.remoteDigest.substringAfter(':').take(12)}.apk")
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0, a.remoteSize))
            client.blob(a.remoteDigest, a.token, target, shouldCancel) { bytes, total ->
                val totalKnown = if (total > 0) total else a.remoteSize
                val pct = if (totalKnown > 0) ((bytes * 100) / totalKnown).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, totalKnown))
            }
            val downloadedSha = "sha256:" + sha256(target)
            if (downloadedSha != a.remoteDigest) {
                target.delete()
                UpdateProgress.update(UpdateProgress.State.Failed(
                    "digest mismatch: $downloadedSha != ${a.remoteDigest}"))
                error("downloaded digest $downloadedSha != manifest ${a.remoteDigest}")
            }
            client.pruneCache("update-", target)
            return target
        } catch (c: java.util.concurrent.CancellationException) {
            // Cancel button: cancelNow() already set state to Cancelled — don't
            // overwrite it with Failed. Rethrow so the worker unwinds.
            Log.i(tag, "download cancelled by user")
            throw c
        } catch (t: Throwable) {
            UpdateProgress.update(UpdateProgress.State.Failed(t.message ?: t.toString()))
            throw t
        }
    }

    /** sha256 of the running APK file. */
    private fun currentInstalledApkSha256(): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        // PackageInfo.applicationInfo became @Nullable in Android 15
        // (API 35). Treat null as a hard fail — without the install
        // path we can't verify the running APK against GHCR.
        val path = info.applicationInfo?.sourceDir
            ?: error("PackageManager returned null applicationInfo for ${context.packageName} — cannot compute installed APK sha256")
        return sha256(File(path))
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
