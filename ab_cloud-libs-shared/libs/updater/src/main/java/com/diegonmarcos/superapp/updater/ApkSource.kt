package com.diegonmarcos.superapp.updater

import android.content.Context
import java.io.File

/**
 * Where an APK can come from.
 *
 * There were two download paths written as two private functions with an early
 * `return` between them, and because nothing forced them to be the same shape
 * their guarantees drifted apart: the GHCR one verified a digest, the release
 * one verified a length — and guarded that check with `if (total > 0 …)`, so a
 * missing Content-Length turned it off entirely. One contract makes the
 * asymmetry impossible to write: every source returns a [VerifiedApk] or
 * nothing, and "try the next one" is a list rather than a control-flow
 * accident.
 */
internal interface ApkSource {
    /** For logs and the Diagnose report — which channel actually served it. */
    val name: String

    /**
     * Fetch [app]'s APK, verified, or null when this source cannot serve it.
     *
     * Null means "try the next source", NOT "this failed" — a release URL that
     * 404s is a reason to fall through to GHCR, not a reason to fail an
     * install the other channel could still complete.
     */
    fun fetch(ctx: Context, app: Fleet.App): VerifiedApk?
}

/**
 * THE RELEASE ASSET FIRST, BECAUSE IT IS THE REPO.
 *
 * A GHCR package is owned by the ACCOUNT, not the repository. GitHub creates
 * every new user-owned package private regardless of the repo it was pushed
 * from, image.source only LINKS it, and there is no API to change it —
 * measured, not assumed: a package created by GITHUB_TOKEN inside this public
 * repo's own workflow came out private, and PATCH /user/packages/... 404s even
 * with write:packages.
 *
 * So GHCR can never follow repo visibility, and a private package on a public
 * repo shows up as 401 — which reads to a user as "the app is gone". A release
 * asset has no visibility of its own: it IS the repo, so a public repo's asset
 * is public, for every app, with nothing to click and nothing to remember.
 */
internal object ReleaseSource : ApkSource {
    override val name = "release"

    override fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        if (app.releaseUrl.isBlank()) return null
        return runCatching {
            val target = File(ctx.cacheDir, "fleet-${app.id}-release.apk")
            val conn = (java.net.URL(app.releaseUrl).openConnection() as java.net.HttpURLConnection)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, total))
            var seen = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (UpdateProgress.cancelRequested) error("cancelled")
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        seen += n
                        val pct = if (total > 0) ((seen * 100) / total).toInt().coerceIn(0, 100) else 0
                        UpdateProgress.update(UpdateProgress.State.Downloading(pct, seen, total))
                    }
                }
            }
            // A truncated download is the failure this catches: an APK that is
            // short is not an APK, and the installer's error for one is far
            // less useful than saying so here.
            //
            // The size check USED to be guarded by `total > 0`, which made it
            // no check at all whenever the CDN omitted Content-Length: total
            // came back -1, the comparison was skipped, and an unverified file
            // went to the installer. That is exactly how a 383 kB
            // fleet-mail-release.apk — 1.3% of a 29 MB APK — reached
            // PackageInstaller and came back
            // "INSTALL_PARSE_FAILED_NOT_APK: Failed to load asset path".
            // Unlike the GHCR path there is no digest here to catch it after
            // the fact, so an unverifiable download must never be RETURNED;
            // returning null falls through to GHCR, which does carry one.
            // One construction, three guarantees: a declared length must
            // exist, must match, and the bytes must actually be a zip. Returning
            // null here falls through to GHCR, which carries a digest — an
            // unverifiable download must never be RETURNED, because the caller
            // cannot tell the difference once it is just a File.
            VerifiedApk.bySize(target, total) ?: run {
                target.delete()
                error("release asset for ${app.id} failed verification — deferring to GHCR")
            }
        }.getOrNull()
    }
}

/**
 * The OCI blob, kept as the fallback because it carries a digest and a
 * manifest — a stronger integrity story than a declared length. Access first,
 * integrity second: an app nobody can download is not made safer by its
 * digest.
 */
internal object GhcrSource : ApkSource {
    override val name = "ghcr"

    override fun fetch(ctx: Context, app: Fleet.App): VerifiedApk? {
        val client = GhcrClient(app.registry, app.namespace, app.image)
        val token = client.token()
        val layer = Fleet.remoteLayerFor(app, client, token)
        val target = File(ctx.cacheDir, "fleet-${app.id}-${layer.digest.substringAfter(':').take(12)}.apk")
        UpdateProgress.update(UpdateProgress.State.Downloading(0, 0L, layer.size))
        // Raw fleet threads aren't WorkManager — the Cancel button reaches them
        // only through UpdateProgress.cancelRequested.
        client.blob(layer.digest, token, target, { UpdateProgress.cancelRequested }) { bytes, total ->
            val t = if (total > 0) total else layer.size
            val pct = if (t > 0) ((bytes * 100) / t).toInt().coerceIn(0, 100) else 0
            UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, t))
        }
        val verified = VerifiedApk.byDigest(target, layer.digest)
        if (verified == null) {
            target.delete()
            UpdateProgress.update(UpdateProgress.State.Failed("digest mismatch for ${app.label}"))
            return null
        }
        // Keep the verified APK, drop this app's superseded ones. Keeping it
        // means a retry after a failed install reuses the download instead of
        // pulling the blob again.
        client.pruneCache("fleet-${app.id}-", target)
        return verified
    }
}
