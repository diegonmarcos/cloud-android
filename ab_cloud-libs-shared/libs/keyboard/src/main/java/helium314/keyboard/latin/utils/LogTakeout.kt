// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Persistent debug log takeout for the keyboard's own in-memory [Log] buffer. Writes the
 * current log lines to a single, predictably-named SHARED file at the root of Downloads:
 *   Download/cloud-keyboard-log.log
 * so it can be pulled from Termux (~/storage/Download) or any file manager without
 * adb/logcat/root, and so it survives an IME process restart (unlike logcat, which is
 * lost on process death). Always overwritten -> exactly one current file.
 *
 * Mirrors CrashTakeout's write approach (delete-then-insert via MediaStore, legacy
 * fallback, private-storage fallback) but dumps the running [Log] buffer instead of a
 * crash stack trace, and can be triggered manually or on IME teardown.
 */
object LogTakeout {
    private const val TAG = "LogTakeout"

    fun dump(context: Context, fileName: String = "cloud-keyboard-log.log") {
        runCatching {
            val ctx = context.applicationContext
            val body = buildString {
                append("=== Cloud-Keyboard log ===\n")
                append("when:    ").append(java.util.Date()).append('\n')
                append("device:  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n")
                append(Log.getLog().joinToString("\n"))
            }
            writeLatest(ctx, fileName, body)
        }
    }

    private fun writeLatest(ctx: Context, fileName: String, body: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cr = ctx.contentResolver
                // Fixed unique name -> match on DISPLAY_NAME alone; delete prior copy.
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
