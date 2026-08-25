package com.diegonmarcos.cloudkeyboard

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Crash takeout for Cloud-Keyboard. On any uncaught exception, writes the stack
 * to a single, predictably-named SHARED file at the root of Downloads:
 *   Download/cloud-keyboard-log-error.log
 * so it can be pulled from Termux (~/storage/Download) or any file manager
 * without adb/logcat/root. Always overwritten → exactly one current file.
 *
 * This mirrors the superapp's CrashLogger.writeLatestErrorLog. The two apps
 * share no code module (the keyboard libs were split out of the superapp), so
 * this small helper is duplicated by design — behaviour parity, not shared code.
 * No storage permission needed on Android 10+ (own MediaStore rows). Chains to
 * the previous handler so the system crash dialog still shows.
 */
object CrashTakeout {
    private const val TAG = "CrashTakeout"

    fun install(ctx: Context, fileName: String) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val body = buildString {
                    append("=== Cloud-Keyboard crash ===\n")
                    append("when:    ").append(java.util.Date()).append('\n')
                    append("thread:  ").append(thread.name).append('\n')
                    append("device:  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n")
                    append(Log.getStackTraceString(ex))
                }
                writeLatest(app, fileName, body)
            }
            prev?.uncaughtException(thread, ex)
        }
    }

    private fun writeLatest(ctx: Context, fileName: String, body: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cr = ctx.contentResolver
                // Fixed unique name → match on DISPLAY_NAME alone; delete prior copy.
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
            // Last-ditch fallback: app-private external dir (visible in file manager).
            runCatching { File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, fileName).writeText(body) }
            Log.w(TAG, "MediaStore write failed; wrote private fallback", t)
        }
    }
}
