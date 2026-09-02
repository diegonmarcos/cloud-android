package com.diegonmarcos.superapp.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The ONLY door into this app, and it opens INWARD.
 *
 * nix-on-droid measures — it has the mesh keys, the fleet declaration and an
 * ssh client; this app has none of them. The env's `my-watchdog-tui
 * android-bridge` loop talks to this receiver with `am broadcast`, which any
 * uid may send and which hands the receiver's result data straight back to
 * the caller's stdout:
 *
 *   am broadcast -a …WANTS -n <pkg>/<this>            → data="oci-apps"
 *   am broadcast -a …PUSH  --es alias A --es id I
 *                --ei part P --ei parts N --es gz B64 → the envelope, gzip+base64,
 *                                                       ≤100 KB per part (argv limit)
 *   am broadcast -a …LOG                              → the app's own last lines
 *
 * Not a ContentProvider: Android's `content` tool goes through
 * getContentProviderExternal, which only the shell uid may call. Broadcasts
 * carry no such gate, so every step here is runnable by hand over ssh and the
 * app's own log is readable the same way — the observability the previous
 * design never had.
 *
 * Callers: this uid and the env's uid (checked where the platform tells us).
 */
class BridgeReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (!allowed(ctx)) {
            resultData = "denied"
            return
        }
        when (intent.action) {
            ACTION_WANTS -> resultData = Store.wanted(ctx)
            ACTION_LOG -> resultData = Store.log(ctx).takeLast(40).joinToString("\n")
            ACTION_PUSH -> resultData = push(ctx, intent)
            else -> resultData = "unknown action"
        }
    }

    private fun push(ctx: Context, i: Intent): String {
        val alias = i.getStringExtra("alias")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
            ?: return "bad alias"
        val id = i.getStringExtra("id") ?: "0"
        val part = i.getIntExtra("part", 0)
        val parts = i.getIntExtra("parts", 1)
        val gz = i.getStringExtra("gz") ?: return "no gz"
        val partsDir = File(Store.dir(ctx), "parts/$alias-$id").apply { mkdirs() }
        File(partsDir, "$part").writeText(gz)
        val have = (0 until parts).map { File(partsDir, "$it") }
        if (have.any { !it.isFile }) return "part $part/$parts"
        return try {
            val b64 = have.joinToString("") { it.readText() }
            val bytes = GZIPInputStream(ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT))).readBytes()
            if (bytes.size < 2 || bytes[0] != '{'.code.toByte()) return "not json"
            val final = Store.file(ctx, alias)
            val tmp = File(final.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(final)
            Store.append(ctx, "in: $alias ${bytes.size} B")
            Store.arrived(ctx, alias)
            "ok ${bytes.size}"
        } catch (t: Throwable) {
            Store.append(ctx, "in: $alias FAILED ${t.message}")
            "error ${t.message}"
        } finally {
            partsDir.deleteRecursively()
        }
    }

    /** Our own uid, or the env's. The sender uid is only known on 34+; below
     *  that the receiver is still explicit-only, which is the same bar every
     *  exported receiver on those releases has. */
    private fun allowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val uid = sentFromUid
        if (uid == -1 || uid == android.os.Process.myUid()) return true
        return ENV_PACKAGES.any { runCatching { ctx.packageManager.getPackageUid(it, 0) }.getOrNull() == uid }
    }

    companion object {
        const val ACTION_WANTS = "com.diegonmarcos.watchdog.WANTS"
        const val ACTION_PUSH = "com.diegonmarcos.watchdog.PUSH"
        const val ACTION_LOG = "com.diegonmarcos.watchdog.LOG"
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
