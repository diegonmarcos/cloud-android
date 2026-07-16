package com.diegonmarcos.devcontrol

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Day-bucketed on-device log export under 0_logs/<app>/:
 *   0_logs/cloud-superapp/<yyyy-MM-dd>-LOGCAT.log  — continuous logcat, appended
 *   0_logs/cloud-superapp/<yyyy-MM-dd>-CRASH.log   — uncaught crashes, appended
 *
 * <app> is BuildConfig.GHCR_IMAGE (data-driven from build.json::release.ghcr) so
 * every constellation app lands in its own subfolder of one shared 0_logs tree.
 * Root is the SHARED /sdcard/0_logs when All-files access is held
 * (MANAGE_EXTERNAL_STORAGE), else the app-scoped external dir (still reachable).
 * Both files APPEND: same-day crashes stack; the logcat file grows all day and
 * a new one starts at midnight.
 */
object LogExport {
    private const val TAG = "LogExport"
    private const val ROOT = "0_logs"
    private val app get() = DevControl.config.appId
    @Volatile private var streaming = false

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 0_logs/<app>/ — shared when All-files access is granted, else app-scoped. */
    fun dir(context: Context): File {
        val shared = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        val base = if (shared) File(Environment.getExternalStorageDirectory(), ROOT)
                   else File(context.getExternalFilesDir(null), ROOT)
        return File(base, app).apply { mkdirs() }
    }

    private fun file(context: Context, kind: String) = File(dir(context), "${today()}-$kind.log")

    /** Append the crash payload to today's CRASH log. Best-effort, never throws —
     *  it runs inside the uncaught-exception handler. */
    fun appendCrash(context: Context, text: String) {
        runCatching { file(context, "CRASH").appendText(text) }
            .onFailure { Log.w(TAG, "crash append failed", it) }
    }

    /**
     * Start one daemon thread that streams THIS process's logcat to today's
     * LOGCAT file (append), rolling to a new file when the calendar day flips.
     * Idempotent — a second call is a no-op. Apps can only read their own logs
     * without the privileged READ_LOGS permission, so this captures the app's
     * own output — exactly what diagnoses a pre-open crash.
     * ponytail: flush per line so the tail survives a crash; logcat I/O, not hot.
     */
    fun startLogcat(context: Context) {
        if (streaming) return
        streaming = true
        Thread({
            try {
                val proc = ProcessBuilder("logcat", "-v", "time")
                    .redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().use { reader ->
                    var curDay = today()
                    var w = appendWriter(file(context, "LOGCAT"))
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            val now = today()
                            if (now != curDay) {           // midnight → new day file
                                runCatching { w.flush(); w.close() }
                                curDay = now
                                w = appendWriter(file(context, "LOGCAT"))
                            }
                            w.write(line); w.write("\n"); w.flush()
                        }
                    } finally { runCatching { w.flush(); w.close() } }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "logcat stream failed", t)
            } finally { streaming = false }
        }, "log-export-logcat").apply { isDaemon = true }.start()
    }

    private fun appendWriter(f: File) =
        OutputStreamWriter(FileOutputStream(f, /* append = */ true), Charsets.UTF_8)
}
