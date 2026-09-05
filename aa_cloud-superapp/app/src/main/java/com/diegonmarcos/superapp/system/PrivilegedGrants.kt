package com.diegonmarcos.superapp.system

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.BuildConfig as UpdaterBuildConfig
import org.json.JSONArray

/**
 * The ONE place that answers "which (package, permission) pairs may this app
 * grant itself, and are they granted yet?".
 *
 * It exists because there were two answers and they disagreed. The worker
 * (PrivilegedPlaneWorker) resolved targets dynamically while the Permissions
 * screen still iterated each build.json entry's `apps` array — so the moment an
 * entry dropped its `apps` array to go dynamic, READ_LOGS produced zero UI rows
 * and VANISHED from the screen while still being granted in the background. A
 * permissions screen that silently omits a permission is worse than no screen.
 * Both callers now read from here, so they cannot drift again.
 *
 * ── Why the platform decides, not a list ──────────────────────────────────
 * Which permissions `pm grant` can touch is decided by ONE bit in the platform:
 * PROTECTION_FLAG_DEVELOPMENT. We read that bit off the running device via
 * PackageManager.getPermissionInfo instead of hardcoding permission names.
 * Consequences, both of them the point:
 *
 *  - It cannot go stale. A hardcoded list drifts from AOSP on every API level
 *    and needs a per-API-level table to stay honest; this needs none, because
 *    it is the very same bit that makes `pm grant` succeed or fail.
 *  - It cannot over-grant. A permission without the development flag is never
 *    even attempted — there is no name we could get wrong in the "too broad"
 *    direction.
 *
 * The fleet filter is the other half and is equally load-bearing: these are
 * signature|privileged|development permissions and must never leak to a
 * third-party app that merely happens to request one.
 */
object PrivilegedGrants {

    /** One grantable pair. `label` is curated wording when build.json has some. */
    data class Target(val pkg: String, val perm: String, val label: String)

    /**
     * The constellation's own packages — the only packages we will ever grant.
     *
     * Same source the Constellation AppStore uses (auto-scanned from every
     * app's build.json by data/regen.sh), so adding an app to the fleet is all
     * it takes to bring it into the privileged plane. `altId` is included
     * because a resigned stock upstream APK is installed under its ORIGINAL
     * package name. `ctx.packageName` is added unconditionally: this app must
     * stay in the set even if it is somehow missing from the baked fleet, since
     * WRITE_SECURE_SETTINGS on this app is what bootstraps the whole plane.
     *
     * @param installedOnly drop fleet entries not present on this device, so
     *   callers that run shell commands per package do not fill the log with
     *   failures for apps that were simply never installed.
     */
    fun fleetPackages(ctx: Context, installedOnly: Boolean = false): Set<String> {
        val declared = runCatching { Fleet.parse(UpdaterBuildConfig.CONSTELLATION_FLEET_B64) }
            .getOrDefault(emptyList())
            .flatMapTo(linkedSetOf(ctx.packageName)) { listOfNotNull(it.pkg, it.altId) }
        if (!installedOnly) return declared
        return declared.filterTo(linkedSetOf()) { pkg ->
            runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    /**
     * Every (fleet package × development-protected permission it requests) pair.
     *
     * @param includeGranted false (the worker) → only work that still needs
     *   doing, which keeps the grant log honest about what actually changed.
     *   true (the Permissions screen) → every pair with its REAL current state,
     *   because a UI that hides granted rows is a UI that lies about coverage.
     */
    fun resolve(ctx: Context, includeGranted: Boolean = false): List<Target> {
        val entries = curatedEntries()
        val labels = entries.mapNotNull { e ->
            e.optString("perm").takeIf { it.isNotEmpty() }?.let { it to e.optString("label") }
        }.toMap()
        val out = linkedMapOf<Pair<String, String>, Target>()

        // (a) Explicit `apps` arrays stay a verbatim OVERRIDE — the only way to
        // NARROW a grant on purpose. WRITE_SECURE_SETTINGS must go to this app
        // and nothing else; dynamic resolution would hand it to every fleet app
        // that declares it. Explicit means explicit: no development-flag check,
        // no manifest check, exactly the listed packages.
        for (e in entries) {
            val perm = e.optString("perm").takeIf { it.isNotEmpty() } ?: continue
            val arr = e.optJSONArray("apps") ?: continue
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i).takeIf { it.isNotEmpty() } ?: continue
                out[pkg to perm] = Target(pkg, perm, labels[perm].orEmpty().ifEmpty { shortName(perm) })
            }
        }

        // (b) Everything else is derived. For each INSTALLED fleet package, take
        // the permissions its own manifest requests — `pm grant` for a package
        // that never declared the permission is an error, not a silent win, so
        // widening a hand-written list blindly buys nothing — and keep only the
        // development-flagged ones.
        val overridden = entries.mapNotNull { e ->
            e.optString("perm").takeIf { it.isNotEmpty() && (e.optJSONArray("apps")?.length() ?: 0) > 0 }
        }.toSet()
        for (pkg in fleetPackages(ctx)) {
            val requested = runCatching {
                ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
            }.getOrNull() ?: continue
            for (perm in requested) {
                if (perm == null || perm in overridden || (pkg to perm) in out) continue
                if (!isDevelopmentPermission(ctx, perm)) continue
                out[pkg to perm] = Target(pkg, perm, labels[perm].orEmpty().ifEmpty { shortName(perm) })
            }
        }

        val all = out.values.toList()
        return if (includeGranted) all else all.filterNot { isGranted(ctx, it) }
    }

    fun isGranted(ctx: Context, t: Target): Boolean =
        ctx.packageManager.checkPermission(t.perm, t.pkg) == PackageManager.PERMISSION_GRANTED

    /**
     * `pm grant`, plus the app-op that some permissions secretly also need.
     *
     * PACKAGE_USAGE_STATS is signature|privileged|development|**appop**: for an
     * appop-backed permission `pm grant` REPORTS SUCCESS and access stays
     * denied until the matching app-op is set — a false success that reads as
     * "granted" in every log and every UI while the feature is still broken.
     * AppOpsManager.permissionToOp is public API and returns null for non-appop
     * permissions, so this self-selects with no list to maintain.
     *
     * @param exec runs a shell command on whatever privileged channel the
     *   caller has (embedded adb / Shizuku) and returns its output, or null
     *   when the channel is down. Every command carries `2>&1 && echo OK` so a
     *   failure shows up as its stderr rather than as empty output.
     * @return raw shell output of the command(s), or null if the channel gave
     *   nothing back at all.
     */
    fun grant(ctx: Context, t: Target, exec: (String) -> String?): String? {
        val grantOut = exec("pm grant ${t.pkg} ${t.perm} 2>&1 && echo OK")
        val op = runCatching { AppOpsManager.permissionToOp(t.perm) }.getOrNull() ?: return grantOut
        val opOut = exec("appops set ${t.pkg} $op allow 2>&1 && echo OK")
        return listOfNotNull(grantOut?.trim(), opOut?.trim()?.let { "appops $op: $it" })
            .joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /**
     * Ask the running platform whether `perm` carries PROTECTION_FLAG_DEVELOPMENT.
     *
     * runCatching is mandatory, not defensive habit: getPermissionInfo throws
     * NameNotFoundException for OEM/unknown permission names, and fleet
     * manifests do request vendor permissions. One unknown name must not abort
     * the sweep for every package after it.
     */
    private fun isDevelopmentPermission(ctx: Context, perm: String): Boolean = runCatching {
        val info = ctx.packageManager.getPermissionInfo(perm, 0)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.protectionFlags
            else @Suppress("DEPRECATION") (info.protectionLevel and PermissionInfo.PROTECTION_MASK_FLAGS)
        flags and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0
    }.getOrDefault(false)

    /** build.json::ui.permissions.privileged[], baked into BuildConfig as base64. */
    private fun curatedEntries(): List<org.json.JSONObject> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_PERMISSIONS_PRIVILEGED_B64, Base64.DEFAULT)))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun shortName(perm: String) = perm.substringAfterLast('.')
}
