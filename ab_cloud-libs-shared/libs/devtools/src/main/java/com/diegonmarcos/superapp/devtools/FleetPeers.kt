package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Who else is on the mesh, and how to wake them.
 *
 * A member is any installed package shipping a devtools [FleetTokenProvider] —
 * the provider is the membership marker, so the list cannot drift out of sync
 * with a hardcoded roster the way a per-app manifest list would.
 *
 * Visibility note: Android 11+ filters package queries, but apps signed with the
 * same certificate stay mutually visible, and fleet membership IS the signing
 * key. That is why this needs no QUERY_ALL_PACKAGES — a broad grant in fifteen
 * apps to answer a question the signature already answers. If [list] ever comes
 * back shorter than the installed fleet, that assumption is what to check first.
 */
object FleetPeers {
    private const val TAG = "FleetPeers"

    /** Installed mesh members, this app included. */
    fun list(ctx: Context): List<String> = runCatching {
        ctx.packageManager.getInstalledPackages(PackageManager.GET_PROVIDERS)
            .filter { pi ->
                pi.providers?.any { it.authority == "${pi.packageName}.fleet" } == true
            }
            .map { it.packageName }
            .sorted()
    }.onFailure { Log.w(TAG, "peer list failed: $it") }.getOrElse { emptyList() }

    /**
     * Start a member's process without launching an activity.
     *
     * Touching a ContentProvider starts its hosting process if it is not already
     * running, and unlike `startActivity` that is not subject to the background
     * activity launch restrictions Android 10+ applies — an app sitting in the
     * background cannot reliably launch anything, but it can always do this.
     * Once the process is up its DebugInitProvider runs and [AppDebugServer]
     * binds a port, which is the whole point: reach an app's logs without
     * needing a human to tap its icon first.
     *
     * A force-stopped app stays stopped. Nothing short of the user opening it
     * revives it, by design, and no amount of provider poking changes that.
     */
    fun wake(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val uri = Uri.parse("content://$pkg.fleet/token")
        // The row itself is the peer's token and is deliberately discarded —
        // waking a peer is not a reason to hand its credential to the caller.
        return runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { true } ?: false
        }.onFailure { Log.w(TAG, "wake $pkg failed: $it") }.getOrDefault(false)
    }
}
