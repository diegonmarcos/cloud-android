package com.diegonmarcos.superapp.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.BuildConfig as UpdaterBuildConfig
import org.json.JSONArray

/**
 * Makes the privileged plane self-healing across reboots with no Shizuku.
 *
 * The hard constraint: on a non-rooted phone only adbd can spawn a shell-uid
 * (2000) process, so "auto-start Shizuku" reduces to "run adb unattended" —
 * and that fails after every reboot for one reason only: Android turns
 * Wireless Debugging OFF on reboot. So:
 *
 *  1. If we hold WRITE_SECURE_SETTINGS (granted once by this very plane, see
 *     step 3), flip `adb_wifi_enabled` back on.
 *  2. Autoconnect the embedded adb client (mDNS `_adb-tls-connect` + the
 *     keys stored at the one-time pairing) with backoff — adbd and its mDNS
 *     advert take a while after boot.
 *  3. Once up, `pm grant` every entry of build.json::ui.permissions.privileged
 *     to its apps. That list includes WRITE_SECURE_SETTINGS for this app, so
 *     the FIRST successful connect (right after the user pairs once) is what
 *     bootstraps step 1 for every boot after. Pair once, ever.
 *
 * pm grant of a signature perm persists across reboots and in-place updates.
 */
class PrivilegedPlaneWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        enableWirelessDebugging(ctx)

        var last = ""
        for (attempt in 1..ATTEMPTS) {
            val (ok, msg) = EmbeddedAdbChannel.autoConnect(ctx)
            last = msg
            if (ok) {
                Log.i(TAG, "privileged plane up on attempt $attempt: $msg")
                selfGrant(ctx)
                return Result.success()
            }
            Log.i(TAG, "attempt $attempt/$ATTEMPTS: $msg")
            // ponytail: linear backoff capped at 30s; total budget ~3 min.
            Thread.sleep(minOf(5_000L * attempt, 30_000L))
        }
        Log.w(TAG, "privileged plane NOT up after $ATTEMPTS attempts: $last (paired yet?)")
        return Result.failure()
    }

    private fun enableWirelessDebugging(ctx: Context) {
        val granted = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS not granted yet - cannot flip adb_wifi_enabled; relying on it being on")
            return
        }
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
            Log.i(TAG, "adb_wifi_enabled=1")
        }.onFailure { Log.w(TAG, "adb_wifi_enabled write failed: ${it.message}") }
    }

    /** Data-driven: build.json::ui.permissions.privileged[] (B64 in BuildConfig) -> pm grant. */
    private fun selfGrant(ctx: Context) {
        val json = runCatching { String(Base64.decode(BuildConfig.UI_PERMISSIONS_PRIVILEGED_B64, Base64.DEFAULT)) }
            .getOrNull() ?: return
        val list = runCatching { JSONArray(json) }.getOrNull() ?: return
        val touched = linkedSetOf(ctx.packageName)
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val perm = e.optString("perm"); if (perm.isEmpty()) continue
            // An explicit `apps` array stays an OVERRIDE: it is the only way to
            // narrow a grant deliberately (WRITE_SECURE_SETTINGS must NOT go to
            // the whole fleet). Absent/empty means "resolve it, don't guess".
            val targets = explicitApps(e.optJSONArray("apps")).ifEmpty { resolveTargets(ctx, perm) }
            Log.i(TAG, "$perm targets: ${targets.joinToString()}")
            for (pkg in targets) {
                val out = EmbeddedAdbChannel.exec(ctx, "pm grant $pkg $perm 2>&1 && echo OK")
                Log.i(TAG, "pm grant $pkg $perm -> ${out?.trim()?.take(120)}")
            }
            touched.addAll(targets)
        }
        batteryWhitelistFleet(ctx, touched)
    }

    /** The `apps` override, verbatim. Empty when the key is absent or empty. */
    private fun explicitApps(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    /**
     * Dynamic targets = FLEET ∩ packages whose OWN manifest requests `perm`.
     * Both halves are load-bearing:
     *
     *  - The manifest filter, because `pm grant` is a SILENT no-op (worse: an
     *    error we would have to read) for a package that never declared the
     *    permission. Widening a hand-written list blindly buys nothing.
     *  - The fleet filter, because these are signature|privileged|development
     *    permissions; they must never leak to a third-party app that happens to
     *    request them.
     *
     * This replaced a hardcoded list that had gone stale and stayed stale:
     * READ_LOGS named only mail + superapp while FOUR fleet manifests declare it
     * (superapp, mail, nix-on-droid, termux), so nix-on-droid and termux kept
     * re-showing the "allow access to all device logs?" dialog on every boot and
     * nothing in the logs said why. Derived beats hardcoded — it cannot go stale
     * when an app is added, and it cannot over-grant.
     *
     * Fleet source is the same one the Constellation AppStore uses:
     * Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64), auto-scanned from every
     * app's build.json by data/regen.sh. `altId` is included because a resigned
     * stock upstream APK is installed under its original package name.
     */
    private fun resolveTargets(ctx: Context, perm: String): List<String> {
        val fleet = runCatching { Fleet.parse(UpdaterBuildConfig.CONSTELLATION_FLEET_B64) }
            .getOrDefault(emptyList())
            .flatMapTo(linkedSetOf(ctx.packageName)) { listOfNotNull(it.pkg, it.altId) }
        return runCatching {
            ctx.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { it.requestedPermissions?.contains(perm) == true }
                .map { it.packageName }
        }.getOrDefault(emptyList()).filter { it in fleet }
    }

    /**
     * Doze battery-optimization exemption for the fleet, via shell. On Samsung
     * (SM-G996B) Doze kills even a foreground sync service unless the app is on
     * the deviceidle whitelist — measured 2026-09-03: cloud-mail's process was
     * dead and JMAP stopped fetching whenever the phone idled. `pm grant` can't
     * set this (it is not a runtime permission); `dumpsys deviceidle whitelist
     * +<pkg>` can, and it persists. Whitelist every distinct app RESOLVED from
     * the privileged list (mail, superapp, ...) so the whole constellation keeps
     * syncing in the background with no per-app battery dialog. Takes the
     * resolved set rather than re-reading the `apps` arrays: those are now
     * optional, and re-deriving them here would silently drop mail off the
     * whitelist the moment an entry switched to dynamic resolution.
     */
    private fun batteryWhitelistFleet(ctx: Context, pkgs: Set<String>) {
        for (pkg in pkgs) {
            val out = EmbeddedAdbChannel.exec(ctx, "dumpsys deviceidle whitelist +$pkg 2>&1 && echo OK")
            Log.i(TAG, "deviceidle whitelist +$pkg -> ${out?.trim()?.take(120)}")
        }
    }

    companion object {
        private const val TAG = "PrivilegedPlane"
        private const val ATTEMPTS = 8
        // Settings.Global key; not a public constant but stable since Android 11.
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}
