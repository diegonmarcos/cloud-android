package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.apk.ApkIntegrity
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.install.InstallChannel
import com.diegonmarcos.superapp.updater.install.InstallGate
import com.diegonmarcos.superapp.updater.install.SessionInstall
import com.diegonmarcos.superapp.updater.install.ShellInstall
import com.diegonmarcos.superapp.updater.install.UpdateInstaller
import com.diegonmarcos.superapp.updater.source.ApkSource
import com.diegonmarcos.superapp.updater.source.GhcrClient
import com.diegonmarcos.superapp.updater.source.GhcrSource
import com.diegonmarcos.superapp.updater.source.ReleaseSource
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
    sealed class State(
        /** APK size in bytes: the DOWNLOAD size when the manifest was reached,
         *  otherwise the installed APK's own size, and 0 when neither is known
         *  (blocked, or the check failed before either was available). Carried
         *  on the base class because every variant has a size to show and the
         *  UI should not have to branch to find it. */
        val bytes: Long,
    ) {
        class Installed(val versionName: String, val versionCode: Long, val sha12: String, bytes: Long = 0L) : State(bytes)
        class UpdateAvailable(val versionName: String?, val remoteDigest12: String, bytes: Long = 0L) : State(bytes)
        class Missing(bytes: Long = 0L) : State(bytes)
        class Blocked : State(0L)
        class Error(val message: String) : State(0L)
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
        if (app.blocked) return State.Blocked()
        val installed = installedInfo(ctx, app)
        // THE RELEASE ASSET DECIDES, WHEN THERE IS ONE.
        //
        // GHCR answers 401/403 for a package that is private OR absent, and an
        // app that ships only on its GH Release now has no package at all — so
        // probing GHCR reports 403 for something that is downloadable right
        // now. That is the failure this branch exists to end, and the comment
        // below already recorded it happening to cloud-watchdog.
        //
        // The release asset has no visibility of its own: it IS the repo. If
        // it answers, it is the truth about whether this app can be installed.
        releaseStatus(app, installed)?.let { return it }
        return try {
            val client = GhcrClient(app.registry, app.namespace, app.image)
            val layer = remoteLayer(app, client, client.token())
            val remote12 = layer.digest.substringAfter(':').take(12)
            // Valid manifest ⇒ remote APK exists. Not installed ⇒ offer install.
            if (installed == null) return State.Missing(layer.size)
            // Code-identity short-circuit for the SELF entry: builds are not
            // byte-reproducible, so a same-commit rebuild has a different APK
            // sha and would show a phantom "update available". When this is our
            // own package and the manifest revision matches our built-in git
            // sha, we are up to date regardless of bytes. (Foreign apps don't
            // expose their revision, so sha comparison remains their signal.)
            if (app.pkg == ctx.packageName && layer.revision != null &&
                layer.revision == BuildConfig.GIT_SHORT_SHA) {
                return State.Installed(installed.versionName, installed.versionCode, installed.sha.take(12), layer.size)
            }
            val currentSha = "sha256:" + installed.sha
            if (currentSha == layer.digest)
                State.Installed(installed.versionName, installed.versionCode, installed.sha.take(12), layer.size)
            else
                State.UpdateAvailable(installed.versionName, remote12, layer.size)
        } catch (e: GhcrClient.HttpException) {
            // NOT INSTALLED IS A LOCAL FACT. Whether the registry answers has no
            // bearing on it, so a failed probe must not turn "missing" into
            // "unknown" — that hid exactly the apps the ◯ Missing filter exists
            // to find. 404 already did the right thing; 401 and 403 did not, and
            // GHCR answers 401/403 for a package that is private OR absent, which
            // is the normal state of anything not yet published. cloud-watchdog
            // (401) and cloud-infra-desktop-termux-boot (403) both vanished.
            // Installed + unreachable registry stays an honest error: there we
            // genuinely cannot tell whether an update is waiting.
            installed?.let {
                if (e.code == 404) State.Installed(it.versionName, it.versionCode, it.sha.take(12), it.bytes)
                else State.Error("HTTP ${e.code}")
            } ?: State.Missing()
        } catch (t: Throwable) {
            // An installed app whose remote check THREW must not masquerade as
            // "Installed" — that silently hides real updates behind transient
            // network failures. On an IPv6-only carrier (no route to 1.1.1.1)
            // every GHCR token fetch throws UnknownHost, and the store showed
            // "Installed" while an update sat on the release (2026-08-30).
            // Missing stays a local fact; a failed check on an installed app
            // is an honest Error.
            installed?.let { State.Error("check failed: ${t.message ?: t.javaClass.simpleName}") }
                ?: State.Missing()
        }
    }

    /**
     * State from the release asset, or null when this app declares none or the
     * probe fails — in which case the caller falls through to GHCR.
     *
     * SIZE, NOT A DIGEST, AND THAT IS A REAL LIMITATION. A release asset
     * carries no content digest over a HEAD, so "is an update waiting" is
     * answered by comparing bytes. Two builds of the same commit differ in
     * size rarely but can; two different commits with identical size are
     * possible and would read as up to date. GHCR's digest is strictly better
     * at THAT question — and strictly worse at the question underneath it,
     * which is whether the app can be reached at all. An app the store cannot
     * see is not made safer by the precision with which it could have compared
     * it, so access wins and the weaker signal is stated rather than hidden.
     */
    private fun releaseStatus(app: App, installed: Installed?): State? {
        if (app.releaseUrl.isBlank()) return null
        val size = runCatching {
            val c = (java.net.URL(app.releaseUrl).openConnection() as java.net.HttpURLConnection)
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = true
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val code = c.responseCode
            val len = c.contentLengthLong
            c.disconnect()
            if (code !in 200..299) return@runCatching -1L
            len
        }.getOrDefault(-1L)
        if (size < 0) return null
        val i = installed ?: return State.Missing(size)
        return if (i.bytes == size) {
            State.Installed(i.versionName, i.versionCode, i.sha.take(12), size)
        } else {
            State.UpdateAvailable(i.versionName, "release", size)
        }
    }

    /** Download the GHCR blob, verify sha, install/update [app] (foreign pkg). */
    fun install(ctx: Context, app: App) {
        commit(ctx, app, download(ctx, app))
    }

    /**
     * Fetch and verify [app]'s APK, WITHOUT installing it. Returns the file.
     *
     * Separate from [commit] because a batch downloads everything first and
     * only then starts installing: an install prompt blocks on the user, so
     * interleaving the two made every tap wait on the next app's network
     * fetch. Downloading first means the prompts run back to back.
     */
    /**
     * Fetch [app]'s APK from the first source that can serve it, verified.
     *
     * A LIST, not a chain of early returns: the two paths used to be private
     * functions with `releaseDownload(...)?.let { return it }` between them,
     * and nothing held them to the same contract — so one verified a digest
     * and the other verified a length it could skip. Adding a third source is
     * now one entry here, and it cannot be added without returning evidence.
     *
     * Separate from [commit] because a batch downloads everything first and
     * installs afterwards: an install prompt blocks on the user, so
     * interleaving made every tap wait on the next app's network fetch.
     */
    private val sources: List<ApkSource> = listOf(ReleaseSource, GhcrSource)

    fun download(ctx: Context, app: App): VerifiedApk {
        UpdateProgress.update(UpdateProgress.State.CheckingManifest)
        for (source in sources) {
            val apk = source.fetch(ctx, app) ?: continue
            Log.i(TAG, "download ${app.id}: ${source.name} → ${apk.evidence}")
            return apk
        }
        error("no source could provide a verified APK for ${app.id} " +
            "(tried ${sources.joinToString { it.name }})")
    }

    /** [GhcrSource] needs the manifest layer; the resolution logic (ABI tag
     *  first, then the universal one) stays here with the rest of the fleet
     *  model rather than being duplicated into the source. */
    internal fun remoteLayerFor(app: App, client: GhcrClient, token: String) =
        remoteLayer(app, client, token)

    /** Hand a verified APK to the installer: the first channel that accepts it
     *  wins. Shell first (installs with NO dialog at all), PackageInstaller
     *  second (prompts, but always available). Cheap — the work was the
     *  download. */
    fun commit(ctx: Context, app: App, apk: VerifiedApk) {
        for (channel in channels) {
            if (channel.install(ctx, app, apk)) {
                Log.i(TAG, "install committed via ${channel.name}: ${app.label} (${app.pkg})")
                return
            }
        }
        error("no install channel accepted ${app.pkg} " +
            "(tried ${channels.joinToString { it.name }})")
    }

    private val channels: List<InstallChannel> = listOf(ShellInstall, SessionInstall)


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
                is State.Missing -> mode != Mode.UPDATES
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

        // ── PHASE 1: download everything, install nothing ───────────────────
        // An install prompt blocks on the user. Interleaved, every tap was
        // followed by a wait for the NEXT app's blob, so a ten-app batch spent
        // most of its time with the user watching a spinner between dialogs.
        // Fetching first means phase 2 is pure prompts, back to back.
        //
        // It also makes the batch atomic in the way that matters: a network
        // that dies half way now fails BEFORE anything was installed, instead
        // of leaving the fleet half-updated.
        // Pair<App, VerifiedApk>, not Pair<App, File>: the batch downloads
        // everything first and installs afterwards, so the evidence has to
        // survive that gap. Carrying a File here would have been the one
        // remaining way to reach commit() with something unverified.
        val staged = ArrayList<Pair<App, VerifiedApk>>(batch.size)
        batch.forEachIndexed { i, app ->
            if (UpdateProgress.cancelRequested) {
                Log.i(TAG, "installAll cancelled by user during download")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                return 0
            }
            UpdateProgress.beginBatch("↓ ${app.label}", i + 1, batch.size)
            try {
                staged += app to download(ctx, app)
            } catch (c: java.util.concurrent.CancellationException) {
                Log.i(TAG, "download ${app.label} cancelled")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                UpdateProgress.endBatch()
                return 0
            } catch (t: Throwable) {
                // One dead image must not cost the other nine their update.
                Log.w(TAG, "installAll download ${app.label}: ${t.message}")
            }
        }
        if (staged.isEmpty()) {
            UpdateProgress.endBatch()
            return 0
        }

        // ── PHASE 2: install, strictly one at a time ────────────────────────
        // Sequential is not a style choice: PackageInstaller sessions collide,
        // and each unanswered prompt holds a session against the 50-session cap.
        var acted = 0
        staged.forEachIndexed { i, (app, apk) ->
            if (UpdateProgress.cancelRequested) {
                Log.i(TAG, "installAll cancelled by user after $acted install(s)")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                return acted
            }
            UpdateProgress.beginBatch(app.label, i + 1, staged.size)
            try {
                // commit() blocks until this install settles: UpdateInstaller
                // runs every install through InstallGate, so the batch does not
                // arm/await itself any more. That moved the guarantee to the
                // one place EVERY caller passes through - including the
                // per-row install buttons, which never came through here.
                commit(ctx, app, apk)
                acted++
            } catch (t: Throwable) {
                // The APK stays in the cache, so a retry reuses it - the whole
                // reason downloads are content-addressed.
                Log.w(TAG, "installAll commit ${app.label}: ${t.message}")
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

    private data class Installed(val versionName: String, val versionCode: Long, val sha: String, val bytes: Long)

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
            Installed(pi.versionName ?: "—", code,
                if (path != null) ApkIntegrity.sha256(File(path)) else "",
                if (path != null) File(path).length() else 0L)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
