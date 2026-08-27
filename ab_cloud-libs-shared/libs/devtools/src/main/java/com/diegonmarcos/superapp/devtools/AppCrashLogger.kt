package com.diegonmarcos.superapp.devtools

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes every uncaught crash to getExternalFilesDir(null)/crashes/ so
 * AppDebugServer's /api/diagnostics/crashes has something to serve.
 *
 * Without this the endpoint is honest but useless in the seven apps that
 * never had a crash handler — and logcat alone is not a substitute, because
 * the ring buffer routinely rolls past a crash before anyone goes looking.
 *
 * Relationship to SuperApp's own CrashLogger
 * ------------------------------------------
 * SuperApp keeps its richer handler (public Downloads copies, in-app
 * notification feed). Both end up installed: this one first (a
 * ContentProvider runs before Application.onCreate), SuperApp's second, so
 * SuperApp's fires first and chains to this one. Both write the same
 * timestamped filename in the same directory, so the second write overwrites
 * the first with equivalent content and you still end up with exactly one
 * file per crash. That collision is intentional — not a bug to work around.
 */
object AppCrashLogger {
    private const val TAG = "AppCrashLogger"

    /** Keep newest N; a crash loop would otherwise fill the app's data dir. */
    private const val KEEP = 20

    fun install(context: Context) {
        val ctx = context.applicationContext
        val crashDir = File(ctx.getExternalFilesDir(null), "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("=== ${ctx.packageName} crash ===")
                    pw.println("when:    ${Date()}")
                    pw.println("thread:  ${thread.name}")
                    pw.println("package: ${ctx.packageName}")
                    pw.println("version: ${versionLabel(ctx)}")
                    pw.println("device:  ${Build.MANUFACTURER} ${Build.MODEL}")
                    pw.println("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
                File(crashDir, "crash-$ts.txt").writeText(sw.toString())
                prune(crashDir)
            }.onFailure { Log.w(TAG, "crash write failed: $it") }

            // Chain, so Android still kills the process. Skipping this leaves
            // the app wedged half-dead instead of restarting cleanly. There is
            // normally always a previous handler (Android installs
            // KillApplicationHandler); kill the process ourselves if not,
            // rather than returning into a dead thread.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun versionLabel(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        "${pi.versionName} (vc:$vc)"
    }.getOrDefault("unknown")

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }
}
