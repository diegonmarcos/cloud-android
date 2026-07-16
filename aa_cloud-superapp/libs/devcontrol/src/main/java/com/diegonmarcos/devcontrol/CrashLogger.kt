package com.diegonmarcos.devcontrol

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registers a global Thread.UncaughtExceptionHandler that writes every
 * uncaught crash to a file the developer can pull via adb / file manager.
 *
 * Path: getExternalFilesDir(null)/crashes/<timestamp>.txt
 * Reachable on-device at:
 *   /storage/emulated/0/Android/data/com.diegonmarcos.superapp/files/crashes/
 *
 * After saving, we chain to the previous handler so Android still kills the
 * process (otherwise the app would hang in a half-dead state).
 */
object CrashLogger {
    private const val TAG = "CrashLogger"

    fun install(context: Context) {
        val crashDir = File(context.getExternalFilesDir(null), "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    val cfg = DevControl.config
                    pw.println("=== ${cfg.appId} crash ===")
                    pw.println("when:        ${Date()}")
                    pw.println("thread:      ${thread.name}")
                    pw.println("version:     ${cfg.versionName} (vc:${cfg.versionCode})")
                    pw.println("git:         ${cfg.gitSha}")
                    pw.println("device:      ${Build.MANUFACTURER} ${Build.MODEL}")
                    pw.println("android:     ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
                val payload = sw.toString()

                // (1) app's external files dir — survives across runs, app-readable.
                val privateFile = File(crashDir, "crash-$timestamp.txt")
                privateFile.writeText(payload)
                Log.e(TAG, "wrote ${privateFile.absolutePath}")

                // (1b) Append to the day-bucketed 0_logs/<app>/<date>-CRASH.log
                // (shared /sdcard/0_logs when All-files access is held). Same-day
                // crashes stack in one file.
                LogExport.appendCrash(context, payload + "\n")

                // (2) Public Download/superapp-logs/ via MediaStore — Termux + any
                // file manager can read /sdcard/Download/superapp-logs/. No
                // permission needed on Android 10+ (scoped storage).
                writeToPublicDownloads(context, "crash-$timestamp.txt", payload)

                // (2b) ALSO a single, predictably-named latest-crash file at the
                // ROOT of Downloads: Download/cloud-superapp-log-error.log. Always
                // overwritten, so there's exactly one file to grab (no timestamp
                // hunting) — matches the keyboard's cloud-keyboard-log-error.log.
                writeLatestErrorLog(context, "${DevControl.config.appId}-log-error.log", payload)

                // (3) Also dump a snapshot of the live Trace file alongside the
                // crash — the last N log lines are usually what diagnose the
                // failure mode (the crash file just has the throw site).
                val traceFile = File(File(context.getExternalFilesDir(null), "trace"), "trace.log")
                if (traceFile.exists()) {
                    writeToPublicDownloads(context, "trace-$timestamp.log", traceFile.readText())
                }

                // (4) Surface in the in-app notification feed so the user
                // sees this without digging into files. Body keeps the
                // crash class + first line of message — enough to triage.
                runCatching {
                    val cls  = throwable.javaClass.simpleName
                    val msg  = throwable.message?.lineSequence()?.firstOrNull().orEmpty()
                    // App wires this to its own notification feed (keeps the lib
                    // app-agnostic — no dependency on the app's NotificationStore).
                    DevControl.config.onCrash?.invoke(
                        "App crashed (${thread.name})",
                        if (msg.isBlank()) cls else "$cls: $msg",
                    )
                }
            } catch (saveError: Throwable) {
                Log.e(TAG, "failed to save crash", saveError)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Insert a text file into Downloads/superapp-logs/ via MediaStore. */
    internal fun writeToPublicDownloads(context: Context, fileName: String, body: String) {
        try {
            val sub = "${DevControl.config.appId}-logs"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$sub")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return
                context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                Log.i(TAG, "wrote Download/$sub/$fileName via MediaStore ($uri)")
            } else {
                // Legacy fallback (Android 9 and below).
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
                dir.mkdirs()
                File(dir, fileName).writeText(body)
                Log.i(TAG, "wrote legacy ${dir}/$fileName")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore write failed", t)
        }
    }

    /** Overwrite a single, predictably-named latest-crash file in the ROOT of
     *  Downloads (Download/<fileName>) so it's trivial to find and pull from
     *  Termux (~/storage/Download) or any file manager. Deletes any prior copy
     *  first so there is always exactly one current file (no "(1)"/"(2)" dupes).
     *  No permission needed on Android 10+ (scoped storage / own MediaStore rows). */
    internal fun writeLatestErrorLog(context: Context, fileName: String, body: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cr = context.contentResolver
                // Our fixed name is unique, so match on DISPLAY_NAME alone.
                runCatching {
                    cr.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME}=?",
                        arrayOf(fileName),
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                cr.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                Log.i(TAG, "wrote Download/$fileName via MediaStore ($uri)")
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dir, fileName).writeText(body)
                Log.i(TAG, "wrote legacy Download/$fileName")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "latest error log write failed", t)
        }
    }
}
