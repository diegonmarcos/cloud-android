package com.diegonmarcos.superapp.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Constellation AppStore engine — checks / installs / updates / uninstalls
 * EVERY constellation APK, not just self. Reuses [GhcrClient] (OCI digest pull)
 * and [UpdateInstaller] (installs foreign packages). No R references — the app
 * module owns the manifest source (BuildConfig.CONSTELLATION_FLEET_B64) and the
 * UI; this engine is pure mechanism.
 *
 * Flat model (data-driven): the fleet JSON is auto-scanned from each app's own
 * build.json by data/regen.sh → data/constellation-fleet.json → BuildConfig.
 */
object Fleet {
    private const val TAG = "Fleet"

    data class App(
        val id: String,
        val label: String,
        val pkg: String,
        val registry: String,
        val namespace: String,
        val image: String,
        val tag: String,
        val blocked: Boolean,
    )

    /** Live state of one app on this device vs. its GHCR image. */
    sealed class State {
        data class Installed(val versionName: String, val versionCode: Long, val sha12: String) : State()
        data class UpdateAvailable(val versionName: String?, val remoteDigest12: String) : State()
        object Missing : State()
        object Blocked : State()
        data class Error(val message: String) : State()
    }

    /** Decode BuildConfig.CONSTELLATION_FLEET_B64 (base64 JSON) into apps. */
    fun parse(fleetB64: String): List<App> {
        return try {
            val json = String(Base64.decode(fleetB64, Base64.DEFAULT))
            val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                App(
                    id = o.getString("id"),
                    label = o.optString("label", o.getString("id")),
                    pkg = o.getString("package"),
                    registry = o.getString("registry"),
                    namespace = o.getString("namespace"),
                    image = o.getString("image"),
                    tag = o.optString("tag", "latest"),
                    blocked = o.optBoolean("blocked", false),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parse failed: ${t.message}")
            emptyList()
        }
    }

    /** ABI-aware tag: try `<tag>-<deviceAbi>` first, fall back to `<tag>`. */
    private fun remoteLayer(app: App, client: GhcrClient, token: String): GhcrClient.ManifestLayer {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        if (abi != null) {
            try {
                return client.manifest("${app.tag}-$abi", token)
            } catch (_: Throwable) { /* fall back to the universal tag */ }
        }
        return client.manifest(app.tag, token)
    }

    /** Compute install/update status for one app. Network per call. */
    fun status(ctx: Context, app: App): State {
        if (app.blocked) return State.Blocked
        val installed = installedInfo(ctx, app.pkg)
        return try {
            val client = GhcrClient(app.registry, app.namespace, app.image)
            val layer = remoteLayer(app, client, client.token())
            val remote12 = layer.digest.substringAfter(':').take(12)
            // Valid manifest ⇒ remote APK exists. Not installed ⇒ offer install.
            if (installed == null) return State.Missing
            val currentSha = "sha256:" + installed.sha
            if (currentSha == layer.digest)
                State.Installed(installed.versionName, installed.versionCode, installed.sha.take(12))
            else
                State.UpdateAvailable(installed.versionName, remote12)
        } catch (e: GhcrClient.HttpException) {
            if (e.code == 404)
                installed?.let { State.Installed(it.versionName, it.versionCode, it.sha.take(12)) } ?: State.Missing
            else State.Error("HTTP ${e.code}")
        } catch (t: Throwable) {
            installed?.let { State.Installed(it.versionName, it.versionCode, it.sha.take(12)) }
                ?: State.Error(t.message ?: t.toString())
        }
    }

    /** Download the GHCR blob, verify sha, install/update [app] (foreign pkg). */
    fun install(ctx: Context, app: App) {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        val client = GhcrClient(app.registry, app.namespace, app.image)
        val token = client.token()
        val layer = remoteLayer(app, client, token)
        val target = File(ctx.cacheDir, "fleet-${app.id}-${layer.digest.substringAfter(':').take(12)}.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, layer.size))
        client.blob(layer.digest, token, target) { bytes, total ->
            val t = if (total > 0) total else layer.size
            val pct = if (t > 0) ((bytes * 100) / t).toInt().coerceIn(0, 100) else 0
            UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, t))
        }
        val dl = "sha256:" + sha256(target)
        if (dl != layer.digest) {
            target.delete()
            UpdateProgress.update(UpdateProgress.State.Failed("digest mismatch for ${app.label}"))
            error("digest mismatch: $dl != ${layer.digest}")
        }
        UpdateInstaller(ctx).install(target, app.pkg)
        Log.i(TAG, "install committed: ${app.label} (${app.pkg})")
    }

    /** Uninstall [pkg] via PackageInstaller (system confirm dialog). */
    fun uninstall(ctx: Context, pkg: String) {
        val intent = Intent(ctx, PackageInstallerReceiver::class.java)
            .setPackage(ctx.packageName)
            .putExtra(PackageInstallerReceiver.EXTRA_OP, PackageInstallerReceiver.OP_UNINSTALL)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, pkg.hashCode(), intent, flags)
        ctx.packageManager.packageInstaller.uninstall(pkg, pi.intentSender)
    }

    private data class Installed(val versionName: String, val versionCode: Long, val sha: String)

    private fun installedInfo(ctx: Context, pkg: String): Installed? = try {
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val path = pi.applicationInfo?.sourceDir
        Installed(pi.versionName ?: "—", code, if (path != null) sha256(File(path)) else "")
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
