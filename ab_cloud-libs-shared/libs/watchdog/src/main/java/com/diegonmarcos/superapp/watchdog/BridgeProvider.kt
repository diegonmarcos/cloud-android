package com.diegonmarcos.superapp.watchdog

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File

/**
 * The ONLY door into this app, and it opens INWARD.
 *
 * nix-on-droid measures — it has the mesh keys, the fleet declaration and
 * an ssh client; this app has none of them and no longer pretends to. The
 * env's `my-watchdog-tui android-bridge` loop asks which machine the user
 * wants, measures it, and hands the envelope in through Android's own
 * `content write` — a file descriptor over binder, so a 200 KB envelope is
 * not an argv and there is no size to hit.
 *
 *   content query --uri content://<authority>/wants          → one row: alias
 *   content write --uri content://<authority>/snapshot/<a>   ← the envelope on stdin
 *   content query --uri content://<authority>/log            → the app's own log
 *
 * Every step of that is runnable by hand from the env, so the whole chain can
 * be checked over ssh without ever seeing the app — which is what the previous
 * design could not offer, and why it died undiagnosed.
 *
 * Callers: this uid, and the env's uid only.
 */
class BridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor {
        gate()
        val ctx = context!!
        return when (uri.pathSegments.firstOrNull()) {
            "wants" -> MatrixCursor(arrayOf("alias")).apply { addRow(arrayOf(Store.wanted(ctx))) }
            "log" -> MatrixCursor(arrayOf("line")).apply { Store.log(ctx).forEach { addRow(arrayOf(it)) } }
            "snapshots" -> MatrixCursor(arrayOf("alias", "bytes", "mtime")).apply {
                Store.dir(ctx).listFiles()?.filter { it.name.endsWith(".json") }?.forEach {
                    addRow(arrayOf(it.name.removeSuffix(".json"), it.length(), it.lastModified()))
                }
            }
            else -> MatrixCursor(arrayOf("error")).apply { addRow(arrayOf("unknown: $uri")) }
        }
    }

    /** `content write` lands here: the bytes go to a temp file and are renamed
     *  into place when the writer closes, so a reader never sees half a JSON. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        gate()
        val ctx = context!!
        val seg = uri.pathSegments
        if (seg.size != 2 || seg[0] != "snapshot") return null
        val alias = seg[1].takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) } ?: return null
        val final = Store.file(ctx, alias)
        if (!mode.contains('w')) {
            return ParcelFileDescriptor.open(final, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val tmp = File(final.path + ".part")
        val flags = ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
        return ParcelFileDescriptor.open(tmp, flags, Handler(Looper.getMainLooper())) { err ->
            if (err == null && tmp.length() > 2 && tmp.renameTo(final)) {
                Store.append(ctx, "in: $alias ${final.length()} B")
                Store.arrived(ctx, alias)
            } else {
                tmp.delete()
                Store.append(ctx, "in: $alias FAILED ${err?.message ?: "empty"}")
            }
        }
    }

    override fun getType(uri: Uri) = "application/json"
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    /** Our own uid, or the env's. Anyone else gets nothing. */
    private fun gate() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val ok = ENV_PACKAGES.any { runCatching { context!!.packageManager.getPackageUid(it, 0) }.getOrNull() == uid }
        if (!ok) throw SecurityException("uid $uid is not the env")
    }

    companion object {
        const val AUTHORITY = "com.diegonmarcos.watchdog.bridge"
        /** The envs that may push. nix-on-droid under either id it has had. */
        val ENV_PACKAGES = listOf("com.termux.nix", "cld.termux.nix", "com.termux", "cld.termux")
    }
}

/** Files under filesDir/bridge — the whole state of this app. */
object Store {
    private const val LOG_MAX = 200
    @Volatile var onArrive: ((String) -> Unit)? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "bridge").apply { mkdirs() }
    fun file(ctx: Context, alias: String) = File(dir(ctx), "$alias.json")
    private fun wantFile(ctx: Context) = File(dir(ctx), "wants")
    private fun logFile(ctx: Context) = File(dir(ctx), "log")

    fun wanted(ctx: Context): String = runCatching { wantFile(ctx).readText().trim() }.getOrDefault("").ifEmpty { "local" }
    fun want(ctx: Context, alias: String) { wantFile(ctx).writeText(alias.ifEmpty { "local" }); append(ctx, "want: $alias") }

    fun log(ctx: Context): List<String> = runCatching { logFile(ctx).readLines() }.getOrDefault(emptyList())
    @Synchronized fun append(ctx: Context, line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = (log(ctx) + "$ts $line").takeLast(LOG_MAX)
        logFile(ctx).writeText(lines.joinToString("\n") + "\n")
    }

    fun arrived(ctx: Context, alias: String) { onArrive?.invoke(alias) }
}
