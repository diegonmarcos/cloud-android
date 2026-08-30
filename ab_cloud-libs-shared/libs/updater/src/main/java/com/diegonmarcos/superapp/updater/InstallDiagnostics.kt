package com.diegonmarcos.superapp.updater

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The report the Diagnose screen shows after a failed install.
 *
 * Written because "INSTALL_PARSE_FAILED_NOT_APK: failed to load asset path"
 * names a symptom and hides every cause: a truncated stage, a full disk, a
 * signature that does not match the installed copy, a stale session holding a
 * slot. Each of those is one line of local state, and none of it reaches the
 * person looking at the phone. So collect them all, once, in an order that
 * reads top-down: what failed, then the file, then the device, then the app.
 *
 * Plain text on purpose — it is read by a human first, pasted into a chat
 * second, and parsed by a tool a distant third. Everything here is local and
 * non-secret: no tokens, no account identifiers, no message contents.
 */
object InstallDiagnostics {

    private fun ts() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1024      -> "%.1f kB".format(b / 1024.0)
        else           -> "$b B"
    }

    private fun freeBytes(path: File): Long = runCatching {
        val s = StatFs(path.absolutePath); s.availableBlocksLong * s.blockSizeLong
    }.getOrDefault(-1L)

    /**
     * [apk] is the file we tried to install, when there was one — its size and
     * the free space beside it are the pair that explains a truncated stage.
     */
    fun collect(ctx: Context, appId: String, pkg: String, error: String, apk: File? = null): String {
        val sb = StringBuilder(4096)
        fun line(k: String, v: Any?) = sb.append(k.padEnd(22)).append(": ").append(v).append('\n')

        sb.append("── install failure ────────────────────────────────\n")
        line("when", ts())
        line("app", appId)
        line("package", pkg)
        line("error", error)

        sb.append("\n── the file ───────────────────────────────────────\n")
        if (apk == null) {
            line("apk", "(none — failed before download)")
        } else {
            line("apk", apk.absolutePath)
            line("exists", apk.exists())
            line("size", if (apk.exists()) "${apk.length()} B (${human(apk.length())})" else "—")
            // A zip ends with the End Of Central Directory record; a truncated
            // download is a valid prefix of a valid APK and looks fine until
            // the installer reads the tail. This is the cheapest way to tell
            // "the download stopped early" from "the APK is wrong".
            // The ABIs the APK actually carries, against the ones this device
            // accepts. INSTALL_FAILED_NO_MATCHING_ABIS says only that they did
            // not intersect; these two lines say WHICH, and that is the whole
            // diagnosis — an x86_64-only build published under the phone's
            // asset name looks identical to a correct one until you read them.
            line("apk ABIs", runCatching {
                java.util.zip.ZipFile(apk).use { z ->
                    z.entries().asSequence()
                        .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                        .map { it.name.split('/')[1] }.toSortedSet()
                        .ifEmpty { sortedSetOf("(none — no native libs)") }
                        .joinToString(",")
                }
            }.getOrDefault("(unreadable)"))
            line("device ABIs", Build.SUPPORTED_ABIS.joinToString(","))
            line("zip tail ok", runCatching {
                apk.exists() && apk.length() > 22 && java.util.zip.ZipFile(apk).use { it.size() > 0 }
            }.getOrDefault(false))
        }

        sb.append("\n── space ──────────────────────────────────────────\n")
        line("free (data)", human(freeBytes(Environment.getDataDirectory())))
        line("free (external)", human(freeBytes(ctx.getExternalFilesDir(null) ?: ctx.filesDir)))
        line("free (cache)", human(freeBytes(ctx.cacheDir)))

        sb.append("\n── installer state ────────────────────────────────\n")
        line("can install unknown", runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()
        }.getOrDefault(false))
        // Android caps an installer at 50 concurrent sessions; leaked ones are
        // invisible and eventually make every install fail.
        line("our open sessions", runCatching {
            ctx.packageManager.packageInstaller.mySessions.size
        }.getOrDefault(-1))

        sb.append("\n── installed copy ─────────────────────────────────\n")
        val info = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        if (info == null) {
            line("installed", "no — this is a first install, not an update")
        } else {
            line("installed", "yes")
            line("versionName", info.versionName)
            line("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            line("installer", runCatching {
                ctx.packageManager.getInstallerPackageName(pkg)
            }.getOrNull() ?: "unknown")
        }

        sb.append("\n── device ─────────────────────────────────────────\n")
        line("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("abis", Build.SUPPORTED_ABIS.joinToString(","))
        line("host app", "${ctx.packageName} ${BuildConfig.GIT_SHORT_SHA}")

        sb.append("\n── recent log (this app only) ─────────────────────\n")
        // ONE logcat reader in the constellation: devtools owns it, the About
        // page's live viewer and the debug API's /diagnostics/logcat use the
        // same one. No second copy here.
        sb.append(runCatching {
            com.diegonmarcos.superapp.devtools.AppDebugServer.readLogcat(120)
        }.getOrElse { "(unavailable: ${it.message})\n" })
        sb.append("\nFull live log: Configs > About > Logcat (All | Errors, copy-all).\n")
        return sb.toString()
    }

}
