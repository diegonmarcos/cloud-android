package com.diegonmarcos.ide.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Detect whether GHCR has a hub APK whose sha256 differs from the currently
 * installed one, and download it if so. Returns null when already up to date.
 * Mirrors ea_cloud-superapp's UpdateChecker (self-update of the hub APK from
 * com.diegonmarcos.ide.BuildConfig.GHCR_IMAGE at com.diegonmarcos.ide.BuildConfig.AUTO_UPDATE_TAG).
 */
internal class UpdateChecker(private val context: Context) {
    private val tag = "Ide/Update/Check"
    private val client = GhcrClient(image = com.diegonmarcos.ide.BuildConfig.GHCR_IMAGE)

    data class Update(val remoteDigest: String, val remoteSize: Long, val assetTitle: String, val downloadedTo: File)

    fun check(): Update? {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        try {
            // ABI-aware: pull the GHCR tag matching THIS device's ABI so an x86
            // device updates from the x86_64 artifact, arm64 from arm64, etc.
            val abiTag = com.diegonmarcos.ide.BuildConfig.AUTO_UPDATE_TAG + deviceAbiSuffix()
            val token = client.token()
            val layer = client.manifest(abiTag, token)
            val currentDigest = "sha256:" + currentInstalledApkSha256()
            if (currentDigest == layer.digest) {
                Log.i(tag, "up to date: $currentDigest ($abiTag)")
                UpdateProgress.update(UpdateProgress.State.UpToDate(abiTag))
                return null
            }
            Log.i(tag, "update available: $currentDigest → ${layer.digest}")

            val target = File(context.cacheDir, "update-${layer.digest.substringAfter(':').take(12)}.apk")
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0, layer.size))
            client.blob(layer.digest, token, target) { bytes, total ->
                val totalKnown = if (total > 0) total else layer.size
                val pct = if (totalKnown > 0) ((bytes * 100) / totalKnown).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, totalKnown))
            }

            val downloadedSha = "sha256:" + sha256(target)
            if (downloadedSha != layer.digest) {
                target.delete()
                val m = "digest mismatch: $downloadedSha != ${layer.digest}"
                UpdateProgress.update(UpdateProgress.State.Failed(m))
                error(m)
            }
            return Update(layer.digest, layer.size, layer.title, target)
        } catch (t: Throwable) {
            UpdateProgress.update(UpdateProgress.State.Failed(t.message ?: t.toString()))
            throw t
        }
    }

    /** GHCR tag suffix for this device's ABI, from the baked build.json::release.abis
     *  map. First matching Build.SUPPORTED_ABIS entry wins; its default abi → ""
     *  (the `latest` tag), others → "-<abi>". Unknown device ABI → "" (default). */
    private fun deviceAbiSuffix(): String = runCatching {
        val abis = org.json.JSONObject(
            String(android.util.Base64.decode(
                com.diegonmarcos.ide.BuildConfig.ABIS_JSON_B64, android.util.Base64.DEFAULT)))
        for (abi in android.os.Build.SUPPORTED_ABIS) {
            val o = abis.optJSONObject(abi) ?: continue
            return if (o.optBoolean("default", false)) "" else "-$abi"
        }
        ""
    }.getOrDefault("")

    private fun currentInstalledApkSha256(): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val path = info.applicationInfo?.sourceDir
            ?: error("PackageManager returned null applicationInfo for ${context.packageName}")
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
