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
        // Real installed package of a resigned STOCK upstream APK that isn't
        // repackaged to `pkg` yet (chat=com.mattermost.rnbeta,
        // matrix=io.element.android.x). Detection accepts either; null = the
        // APK already declares `pkg` (patched forks + self).
        val altId: String?,
        val registry: String,
        val namespace: String,
        val image: String,
        val tag: String,
        val asset: String,
        val releaseUrl: String,
        val repoUrl: String,
        val ghcrPage: String,
        val blocked: Boolean,
        // "app" (default) or "lib" — a companion APK that ships engines behind
        // AIDL bound services instead of UI. Data-driven from each app's
        // build.json::release.kind; splits the Constellation page's Apps/Libs tabs.
        val kind: String,
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
                    altId = o.optString("alt_id").takeIf { it.isNotEmpty() },
                    registry = o.getString("registry"),
                    namespace = o.getString("namespace"),
                    image = o.getString("image"),
                    tag = o.optString("tag", "latest"),
                    asset = o.optString("asset", ""),
                    releaseUrl = o.optString("release_url", ""),
                    repoUrl = o.optString("repo_url", ""),
                    ghcrPage = o.optString("ghcr_page", ""),
                    blocked = o.optBoolean("blocked", false),
                    kind = o.optString("kind", "app").takeIf { it.isNotEmpty() } ?: "app",
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parse failed: ${t.message}")
            emptyList()
        }
    }

    /** ABI-aware tag. Across the constellation, x86_64 is the ONLY ABI that
     *  publishes a suffixed variant tag (`<tag>-x86_64`); arm64 is the default
     *  publish under the universal `<tag>`. The old code composed
     *  `<tag>-arm64-v8a`, which never exists — a guaranteed 404 + fallback
     *  round-trip on every arm64 device. Only reach for the suffix on x86_64. */
    private fun remoteLayer(app: App, client: GhcrClient, token: String): GhcrClient.ManifestLayer {
        if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
            try {
                return client.manifest("${app.tag}-x86_64", token)
            } catch (_: Throwable) { /* variant not published yet — universal tag */ }
        }
        return client.manifest(app.tag, token)
    }

    /** Compute install/update status for one app. Network per call. */
    fun status(ctx: Context, app: App): State {
        if (app.blocked) return State.Blocked
        val installed = installedInfo(ctx, app)
        return try {
            val client = GhcrClient(app.registry, app.namespace, app.image)
            val layer = remoteLayer(app, client, client.token())
            val remote12 = layer.digest.substringAfter(':').take(12)
            // Valid manifest ⇒ remote APK exists. Not installed ⇒ offer install.
            if (installed == null) return State.Missing
            // Code-identity short-circuit for the SELF entry: builds are not
            // byte-reproducible, so a same-commit rebuild has a different APK
            // sha and would show a phantom "update available". When this is our
            // own package and the manifest revision matches our built-in git
            // sha, we are up to date regardless of bytes. (Foreign apps don't
            // expose their revision, so sha comparison remains their signal.)
            if (app.pkg == ctx.packageName && layer.revision != null &&
                layer.revision == BuildConfig.GIT_SHORT_SHA) {
                return State.Installed(installed.versionName, installed.versionCode, installed.sha.take(12))
            }
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
        // Raw fleet threads aren't WorkManager — the Cancel button reaches them
        // only through UpdateProgress.cancelRequested.
        client.blob(layer.digest, token, target, { UpdateProgress.cancelRequested }) { bytes, total ->
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
        // Keep the verified APK, drop this app's superseded ones. Keeping it
        // means a retry after a failed install reuses the download instead of
        // pulling the blob again.
        client.pruneCache("fleet-${app.id}-", target)
        UpdateInstaller(ctx).install(target, app.pkg)
        Log.i(TAG, "install committed: ${app.label} (${app.pkg})")
    }

    /** Which apps installAll acts on. UPDATES = only apps ALREADY installed
     *  that have a newer image ("Update all" + background auto-update — never
     *  touches apps the user hasn't installed). MISSING = only not-yet-installed
     *  apps ("Install all"). ALL = both. */
    enum class Mode { UPDATES, MISSING, ALL }

    /**
     * Install/update fleet apps, filtered by [mode] (see [Mode]). Sequential
     * (PackageInstaller sessions mustn't collide); per-app failures don't abort
     * the rest. Returns how many were acted on.
     */
    fun installAll(
        ctx: Context,
        apps: List<App>,
        mode: Mode = Mode.ALL,
        limit: Int = Int.MAX_VALUE,
    ): Int {
        // Decide the work-list FIRST (status checks, no overlay yet) so the
        // batch header can show a correct "N/total" — otherwise the overlay's
        // bar just resets 0→100 per app with no context (scrambled-progress bug).
        val todo = apps.filter { app ->
            if (app.blocked) return@filter false
            when (status(ctx, app)) {
                is State.UpdateAvailable -> mode != Mode.MISSING
                State.Missing -> mode != Mode.UPDATES
                else -> false
            }
        }
        // Cap the batch. [limit] is Int.MAX_VALUE for user-initiated "Update
        // all"/"Install all" - those run in the foreground, the system dialog
        // appears, and each session resolves within seconds. A BACKGROUND pass
        // passes a real limit, and is additionally held to whatever session
        // headroom actually remains: its installs cannot show a dialog, so each
        // leaves a notification holding a PackageInstaller session until the
        // user answers it. Unbounded over a 40-entry fleet that is 40 sessions
        // from one pass, and Android refuses new ones past 50 with "Too many
        // active sessions for UID".
        val batch = if (limit >= todo.size) todo
                    else todo.take(minOf(limit, UpdateInstaller(ctx).freeSessionSlots()))
        if (batch.size < todo.size) {
            // Never a silent cap: the rest are picked up by the next pass, and
            // the log has to say so or "acted on 3 apps" reads as "3 needed it".
            Log.i(TAG, "installAll capped at ${batch.size} of ${todo.size} app(s) " +
                       "(limit=$limit, session headroom decides the rest); " +
                       "remainder deferred to the next pass")
        }
        UpdateProgress.beginDownload() // disarm any stale cancel before the batch
        var acted = 0
        batch.forEachIndexed { i, app ->
            // Cancel between apps: stop launching new installs the moment the
            // user hits Cancel (the in-flight blob loop bails on its own poll).
            if (UpdateProgress.cancelRequested) {
                Log.i(TAG, "installAll cancelled by user after $acted app(s)")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                return acted
            }
            UpdateProgress.beginBatch(app.label, i + 1, batch.size)
            try {
                install(ctx, app)
                acted++
            } catch (c: java.util.concurrent.CancellationException) {
                Log.i(TAG, "install ${app.label} cancelled")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                UpdateProgress.endBatch()
                return acted
            } catch (t: Throwable) {
                Log.w(TAG, "installAll ${app.label}: ${t.message}")
            }
        }
        // Clear the batch context; the async install commits still drive the
        // overlay to Done/Failed via PackageInstallerReceiver.
        UpdateProgress.endBatch()
        return acted
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

    /** The package actually on the device for this app — pkg if present, else
     *  the stock upstream altId. null when neither is installed. Used by the UI
     *  for Open / Uninstall so they target the real installed package. */
    fun installedId(ctx: Context, app: App): String? =
        listOfNotNull(app.pkg, app.altId).firstOrNull { pkgInstalled(ctx, it) }

    private fun pkgInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun installedInfo(ctx: Context, app: App): Installed? {
        val pkg = installedId(ctx, app) ?: return null
        return try {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo(pkg, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
            val path = pi.applicationInfo?.sourceDir
            Installed(pi.versionName ?: "—", code, if (path != null) sha256(File(path)) else "")
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
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
