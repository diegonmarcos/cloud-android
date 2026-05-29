package com.diegonmarcos.superapp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verbose logger: every call goes to BOTH logcat AND a trace file on external
 * storage so the user can read the log without adb (some Termux + nix-on-droid
 * builds don't expose `logcat`). Path:
 *   /sdcard/Android/data/com.diegonmarcos.superapp/files/trace/trace.log
 * The file rolls when it exceeds 256KB.
 */
object Trace {
    private const val TAG = "Trace"
    private const val MAX_BYTES = 256 * 1024L
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var traceFile: File? = null

    fun install(context: Context) {
        try {
            val dir = File(context.getExternalFilesDir(null), "trace").apply { mkdirs() }
            traceFile = File(dir, "trace.log")
            openWriter(append = true)
            d(TAG, "── verbose trace started at ${Date()}  vc:${BuildConfig.VERSION_CODE}")
        } catch (t: Throwable) {
            Log.e(TAG, "Trace.install failed", t)
        }
    }

    fun v(tag: String, msg: String)       { Log.v(tag, msg); write("V", tag, msg, null) }
    fun d(tag: String, msg: String)       { Log.d(tag, msg); write("D", tag, msg, null) }
    fun i(tag: String, msg: String)       { Log.i(tag, msg); write("I", tag, msg, null) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(tag, msg, t); write("W", tag, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); write("E", tag, msg, t) }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        try {
            traceFile?.let { f -> if (f.length() > MAX_BYTES) rollover() }
            val w = writer ?: return
            w.print(fmt.format(Date())); w.print(' ')
            w.print(level); w.print('/'); w.print(tag); w.print(": ")
            w.println(msg)
            t?.printStackTrace(w)
            w.flush()
        } catch (ignored: Throwable) { /* logging failures are non-fatal */ }
    }

    @Synchronized
    private fun rollover() {
        try {
            writer?.close()
            traceFile?.let { f ->
                val backup = File(f.parentFile, "trace.log.1")
                if (backup.exists()) backup.delete()
                f.renameTo(backup)
            }
            openWriter(append = false)
            d(TAG, "── rolled over (prev kept as trace.log.1)")
        } catch (ignored: Throwable) { }
    }

    @Synchronized
    private fun openWriter(append: Boolean) {
        val f = traceFile ?: return
        writer = PrintWriter(FileOutputStream(f, append).bufferedWriter())
    }
}
