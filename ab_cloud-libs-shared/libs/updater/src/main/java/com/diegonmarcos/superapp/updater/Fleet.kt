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
     * IDENTITY FIRST, SIZE ONLY AS FALLBACK. The ship engine publishes a
     * "<asset>.sha256" sidecar next to every APK, so the exact bytes on the
     * release can be compared against the exact bytes installed.
     *
     * This used to compare SIZE alone, and that silently broke the store. On
     * 2026-08-30 several different builds all landed on exactly 32,012,393
     * bytes; the installed APK and the release asset were different content
     * (sha dbf86559… vs c8ff19ec…) at identical size, so this returned
     * Installed and — because release wins over registry — gated the GHCR
     * digest check behind it. The store reported "no update" permanently.
     * A size compare cannot answer "are these the same bytes"; only a digest
     * can, which is why the sidecar exists. Size remains the fallback purely
     * for apps that have not been re-shipped with a sidecar yet.
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
        releaseSha256(app)?.let { remote ->
            return if (i.sha.equals(remote, ignoreCase = true)) {
                State.Installed(i.versionName, i.versionCode, i.sha.take(12), size)
            } else {
                State.UpdateAvailable(i.versionName, remote.take(12), size)
            }
        }
        return if (i.bytes == size) {
            State.Installed(i.versionName, i.versionCode, i.sha.take(12), size)
        } else {
            State.UpdateAvailable(i.versionName, "release", size)
        }
    }

    /**
     * The hex sha256 the ship engine published beside the release asset, or
     * null when this app has not been re-shipped with a sidecar yet (in which
     * case the caller falls back to the size compare). Never throws: a missing
     * or malformed sidecar must degrade to the old behaviour, not to an error.
     */
    private fun releaseSha256(app: App): String? = runCatching {
        val c = (java.net.URL(app.releaseUrl + ".sha256").openConnection()
                as java.net.HttpURLConnection)
        c.instanceFollowRedirects = true
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        val body = if (c.responseCode in 200..299) {
            c.inputStream.bufferedReader().use { it.readText() }
        } else null
        c.disconnect()
        // Either a bare digest or the sha256sum(1) form "<hex>  <filename>".
        body?.trim()?.substringBefore(' ')?.trim()?.lowercase()
            ?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }
    }.getOrNull()

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
     *  that have a newer image ("Update all" — never touches apps the user
     *  hasn't installed). MISSING = only not-yet-installed apps ("Install
     *  all"). ALL = both.
     *
     *  AUTO = what the unattended background pass wants, and the reason libs
     *  used to be invisible to it. It is UPDATES for `kind == "app"` and ALL
     *  for `kind == "lib"`. Both workers used to pass UPDATES, which drops
     *  every [State.Missing] — and a lib is Missing on any device that never
     *  installed it, which is most of them, so all 36 lib entries were skipped
     *  on every pass forever.
     *
     *  Treating a missing lib as installable is not a loosening of the
     *  "never auto-install what the user didn't choose" rule; it is that rule
     *  applied correctly. A lib APK (ab_cloud-libs-shared/lib-apks, one
     *  product flavor per module, applicationId com.diegonmarcos.cloudlib.*)
     *  has no launcher entry and no UI: it is a dependency payload that apps
     *  reach through AIDL behind a signature-level permission. The user never
     *  chose it and never can — the app that needs it did. */
    enum class Mode { UPDATES, MISSING, ALL, AUTO }

    /**
     * What one pass actually did, and — when it did nothing — WHY.
     *
     * installAll used to return a bare Int, so "everything is up to date",
     * "the PackageInstaller session budget is exhausted", "there is no
     * privileged channel so I refuse to spam you with 40 dialogs" and "another
     * batch holds the lease" were all the same observable event: `acted on 0
     * app(s)`. That is indistinguishable from the feature being broken, which
     * is exactly how it was read. [reason] is a one-line, always-logged
     * explanation; [silent] and [channel] say whether the pass could act
     * without user interaction at all.
     */
    data class Pass(
        val acted: Int,
        /** Entries that needed work before any cap was applied. */
        val considered: Int,
        /** Entries this pass was actually allowed to attempt. */
        val batched: Int,
        val silent: Boolean,
        val channel: String?,
        val reason: String,
    )

    /**
     * Install/update fleet apps, filtered by [mode] (see [Mode]). Sequential
     * (PackageInstaller sessions mustn't collide); per-app failures don't abort
     * the rest. Returns how many were acted on.
     */
    /** One batch at a time, process-wide. FIVE callers reach this — the
     *  Update-All action, two Constellation buttons, and TWO periodic
     *  auto-update workers (UpdateWorker and ConstellationWorker) — and none
     *  of them knew about the others. Two overlapping batches were measured on
     *  2026-08-31: one thread installing cloud-ide → cloud-wallet at ~35s an
     *  app while a second re-downloaded mail, news and wallet underneath it.
     *
     *  Duplicated downloads were the cheap half of that. UpdateProgress is a
     *  single global, so the second batch's beginBatch() relabelled the
     *  overlay mid-install and its endBatch() CLEARED it while the first was
     *  still running — the update list emptying with nothing visibly
     *  installed, which is exactly what a silent failure looks like from the
     *  outside even though every install was succeeding.
     *
     *  A losing caller returns 0 rather than queuing: every trigger asks for
     *  the same thing ("bring the fleet up to date"), so the batch already in
     *  flight IS that request being served. Auto-update and Update-All stop
     *  being two functions here and become one machine with two triggers. */
    /**
     * Whether an install can complete with NO user interaction at all.
     *
     * True only when a shell channel (Shizuku / embedded adb) is live: that
     * runs `pm install` as uid 2000, which holds INSTALL_PACKAGES, so nothing
     * is shown and no PackageInstaller session is opened.
     *
     * USER_ACTION_NOT_REQUIRED on the session path is NOT the same thing and is
     * why "silent" can look broken: Android honours it only for a package this
     * app is already the installer of record for, and silently prompts for
     * every other one. An app the user installed by hand therefore always costs
     * one confirmation before it can ever update quietly.
     *
     * This is also what decides whether the unattended pass needs a cap — see
     * [installAll]'s callers.
     */
    fun silentCapable(ctx: Context): Boolean = activeShellChannel(ctx) != null

    /** Name of the live shell channel, for status lines. */
    fun silentChannelName(ctx: Context): String? = activeShellChannel(ctx)?.name()

    /**
     * The batch lease: WHO holds the single-flight slot, and SINCE WHEN.
     *
     * This was an AtomicBoolean with neither an owner nor a bound. A
     * foreground "Update all" that finished its downloads and then sat on
     * unanswered PackageInstaller prompts kept the flag set for as long as the
     * user ignored the notifications, and every auto pass in between took the
     * `return 0` at the top and logged nothing a human would act on. The
     * feature was starvable by doing nothing, indefinitely.
     *
     * The lease fixes both halves: it names the holder, so the skip line says
     * who is holding it, and it expires, so a stale holder is taken over
     * instead of winning forever. Identity CAS on the Lease instance means the
     * taken-over owner's release is a harmless no-op rather than a slot it
     * hands back while someone else is using it.
     */
    private class Lease(val owner: String, val startedAt: Long)

    private val lease = java.util.concurrent.atomic.AtomicReference<Lease?>(null)

    /** A real batch is N downloads plus N sequential installs. 30 minutes is
     *  far past the slowest honest one on a bad network and far short of
     *  "the user left the prompts unanswered until tomorrow". */
    private const val BATCH_STALE_MS = 30L * 60L * 1000L

    private fun acquireLease(owner: String): Lease? {
        while (true) {
            val mine = Lease(owner, android.os.SystemClock.elapsedRealtime())
            val held = lease.get()
            if (held == null) {
                if (lease.compareAndSet(null, mine)) return mine
                continue
            }
            val ageMs = mine.startedAt - held.startedAt
            if (ageMs < BATCH_STALE_MS) return null
            if (lease.compareAndSet(held, mine)) {
                Log.w(TAG, "batch lease held by '${held.owner}' for ${ageMs / 1000}s — past " +
                           "the ${BATCH_STALE_MS / 1000}s stale bound, so '$owner' is taking " +
                           "it over rather than being starved by it")
                return mine
            }
        }
    }

    private fun releaseLease(mine: Lease) { lease.compareAndSet(mine, null) }

    fun installAll(
        ctx: Context,
        apps: List<App>,
        mode: Mode = Mode.ALL,
        limit: Int = Int.MAX_VALUE,
    ): Int = runBatch(ctx, apps, mode, limit, owner = "installAll($mode)").acted

    /**
     * THE unattended entry point. Both periodic workers call this and nothing
     * else, so the pass they run is one machine with two triggers instead of
     * two callers that each re-derived the mode and the cap and disagreed
     * about them (whichever worker won the lease race used to decide whether
     * three apps or the whole fleet installed).
     *
     * Silent here means no visible progress: [UpdateProgress.quiet] is held
     * for the duration so nothing is drawn over whatever the user is doing.
     * Every event still goes to logcat under [TAG], ungated.
     */
    fun autoPass(ctx: Context, apps: List<App>, owner: String): Pass {
        // The cap exists for ONE reason: a SessionInstall leaves a
        // tap-to-confirm notification holding a PackageInstaller session until
        // the user answers it, and Android refuses new sessions past 50 per
        // UID. A shell install opens no session and shows nothing, so with a
        // privileged channel live there is nothing to cap.
        val limit = if (silentCapable(ctx)) Int.MAX_VALUE else BuildConfig.AU_MAX_PER_PASS
        UpdateProgress.quiet = true
        return try {
            runBatch(ctx, apps, Mode.AUTO, limit, owner)
        } finally {
            UpdateProgress.quiet = false
        }
    }

    private fun runBatch(
        ctx: Context,
        apps: List<App>,
        mode: Mode,
        limit: Int,
        owner: String,
    ): Pass {
        val mine = acquireLease(owner)
        if (mine == null) {
            val holder = lease.get()?.owner ?: "?"
            Log.i(TAG, "$owner: batch '$holder' is already in flight — this trigger is " +
                       "already being served, skipping")
            return Pass(0, 0, 0, silentCapable(ctx), silentChannelName(ctx),
                "skipped: batch '$holder' already in flight")
        }
        return try {
            installAllLocked(ctx, apps, mode, limit)
        } finally {
            releaseLease(mine)
        }
    }

    private fun installAllLocked(
        ctx: Context,
        apps: List<App>,
        mode: Mode,
        limit: Int,
    ): Pass {
        // Decide the work-list FIRST (status checks, no overlay yet) so the
        // batch header can show a correct "N/total" — otherwise the overlay's
        // bar just resets 0→100 per app with no context (scrambled-progress bug).
        val silent = silentCapable(ctx)
        val channel = silentChannelName(ctx)
        val todo = apps.filter { app ->
            if (app.blocked) return@filter false
            val state = status(ctx, app)
            val take = when (state) {
                is State.UpdateAvailable -> mode != Mode.MISSING
                // AUTO installs a MISSING lib but never a missing app. See
                // [Mode.AUTO]: a lib has no launcher entry and is reached over
                // AIDL by whichever app needs it, so "the user never chose it"
                // is not a reason to skip it — it is the reason it exists.
                is State.Missing ->
                    mode == Mode.MISSING || mode == Mode.ALL ||
                        (mode == Mode.AUTO && app.kind == "lib")
                else -> false
            }
            // Always-on, ungated: a silent pass must still be fully readable
            // with `logcat -s Fleet`. Ids, packages and digests only — nothing
            // here is a secret, and no URL or token is ever printed.
            if (take) Log.i(TAG, "$mode selects ${app.kind} ${app.id} (${app.pkg}): " +
                                 describe(state))
            take
        }
        Log.i(TAG, "$mode scanned ${apps.size} fleet entries → ${todo.size} need work " +
                   "(unattended-capable: $silent" +
                   (if (channel != null) " via $channel" else ", no privileged channel") + ")")
        if (todo.isEmpty()) {
            return Pass(0, 0, 0, silent, channel,
                "nothing to do — all ${apps.size} fleet entries are current")
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
        val slots = UpdateInstaller(ctx).freeSessionSlots()
        val batch = if (limit >= todo.size) todo
                    else todo.take(minOf(limit, slots))
        // THE SILENT ZERO. take(0) used to return an empty batch and the pass
        // reported "acted on 0 app(s)" — the same line it prints when there is
        // genuinely nothing to do. These are opposite situations: one is
        // healthy, the other is a stuck queue that only the user can unstick,
        // and they were indistinguishable from outside.
        if (batch.isEmpty()) {
            return Pass(0, todo.size, 0, silent, channel,
                "CANNOT ACT: ${todo.size} update(s) waiting but 0 installable right now — " +
                "no privileged shell channel, and PackageInstaller session headroom is " +
                "$slots. Every unanswered tap-to-install notification holds a session " +
                "against the 50-per-UID cap, so answer or dismiss the pending ones, or " +
                "bring up the embedded adb / Shizuku channel for truly unattended installs")
        }
        // Never a silent cap: the rest are picked up by the next pass, and a
        // log line alone reads as "3 needed it" instead of "3 of N could run
        // now" — this note rides along on every beginBatch label below so the
        // overlay says so too, not just logcat.
        val capNote = if (batch.size < todo.size) {
            Log.i(TAG, "installAll capped at ${batch.size} of ${todo.size} app(s) " +
                       "(limit=$limit, session headroom decides the rest); " +
                       "remainder deferred to the next pass")
            " (capped at ${batch.size} of ${todo.size})"
        } else ""
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
                return Pass(0, todo.size, batch.size, silent, channel,
                    "cancelled by the user during download")
            }
            UpdateProgress.beginBatch("↓ ${app.label}$capNote", i + 1, batch.size)
            try {
                staged += app to download(ctx, app)
            } catch (c: java.util.concurrent.CancellationException) {
                Log.i(TAG, "download ${app.label} cancelled")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                UpdateProgress.endBatch()
                return Pass(0, todo.size, batch.size, silent, channel,
                    "cancelled by the user while downloading ${app.label}")
            } catch (t: Throwable) {
                // One dead image must not cost the other nine their update.
                Log.w(TAG, "installAll download ${app.label}: ${t.message}")
            }
        }
        if (staged.isEmpty()) {
            UpdateProgress.endBatch()
            return Pass(0, todo.size, batch.size, silent, channel,
                "no install attempted: all ${batch.size} download(s) failed — see the " +
                "per-entry 'installAll download' warnings above for the reason")
        }

        // ── PHASE 2: install, strictly one at a time ────────────────────────
        // Sequential is not a style choice: PackageInstaller sessions collide,
        // and each unanswered prompt holds a session against the 50-session cap.
        var acted = 0
        staged.forEachIndexed { i, (app, apk) ->
            if (UpdateProgress.cancelRequested) {
                Log.i(TAG, "installAll cancelled by user after $acted install(s)")
                UpdateProgress.update(UpdateProgress.State.Cancelled)
                return Pass(acted, todo.size, batch.size, silent, channel,
                    "cancelled by the user after $acted install(s)")
            }
            UpdateProgress.beginBatch("${app.label}$capNote", i + 1, staged.size)
            try {
                // commit() blocks until this install settles: UpdateInstaller
                // runs every install through InstallGate, so the batch does not
                // arm/await itself any more. That moved the guarantee to the
                // one place EVERY caller passes through - including the
                // per-row install buttons, which never came through here.
                commit(ctx, app, apk)
                acted++
                Log.i(TAG, "installed ${app.kind} ${app.id} (${app.pkg}) " +
                           "[${i + 1}/${staged.size}] via ${if (silent) channel else "PackageInstaller"}")
            } catch (t: Throwable) {
                // The APK stays in the cache, so a retry reuses it - the whole
                // reason downloads are content-addressed.
                Log.w(TAG, "installAll commit ${app.label}: ${t.message}")
            }
        }
        // Clear the batch context; the async install commits still drive the
        // overlay to Done/Failed via PackageInstallerReceiver.
        UpdateProgress.endBatch()
        val deferred = todo.size - batch.size
        return Pass(acted, todo.size, batch.size, silent, channel,
            "installed $acted of ${staged.size} staged (${todo.size} needed work" +
            (if (deferred > 0) ", $deferred deferred to the next pass" else "") + ") " +
            (if (silent) "silently via $channel"
             else "via PackageInstaller — each one PROMPTS, because no privileged " +
                  "shell channel is available"))
    }

    /** One-line, log-safe rendering of a [State]. No URLs, no tokens. */
    private fun describe(s: State): String = when (s) {
        is State.UpdateAvailable ->
            "update ${s.versionName ?: "?"} → ${s.remoteDigest12} (${s.bytes} bytes)"
        is State.Missing -> "not installed (${s.bytes} bytes)"
        is State.Installed -> "current ${s.versionName} (code ${s.versionCode}, ${s.sha12})"
        is State.Blocked -> "blocked"
        is State.Error -> "check failed: ${s.message}"
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
