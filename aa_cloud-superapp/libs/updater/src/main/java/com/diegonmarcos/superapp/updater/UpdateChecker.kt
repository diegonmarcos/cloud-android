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

    data class Update(
        val remoteDigest: String,
        val remoteSize: Long,
        val assetTitle: String,
        val downloadedTo: File,
    )

    /** Returns Update if remote is different; null if already up to date. */
    fun check(): Update? {
        val token = client.token()
        val layer = client.manifest(BuildConfig.AUTO_UPDATE_TAG, token)
        val currentDigest = "sha256:" + currentInstalledApkSha256()
        if (currentDigest == layer.digest) {
            Log.i(tag, "current matches remote: $currentDigest")
            return null
        }
        Log.i(tag, "update available: $currentDigest → ${layer.digest}")

        val target = File(context.cacheDir, "update-${layer.digest.substringAfter(':').take(12)}.apk")
        client.blob(layer.digest, token, target)

        val downloadedSha = "sha256:" + sha256(target)
        if (downloadedSha != layer.digest) {
            target.delete()
            error("downloaded digest $downloadedSha != manifest ${layer.digest}")
        }
        return Update(layer.digest, layer.size, layer.title, target)
    }

    /** sha256 of the running APK file. */
    private fun currentInstalledApkSha256(): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val path = info.applicationInfo.sourceDir
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
