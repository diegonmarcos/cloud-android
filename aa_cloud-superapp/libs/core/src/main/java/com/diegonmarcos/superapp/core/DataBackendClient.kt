package com.diegonmarcos.superapp.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client half of [IDataBackend]: binds an engine library's APK and calls it.
 *
 * One instance per engine. Binds lazily, holds the connection (rebinding per
 * call would cost a service start on every keystroke in a search box), and
 * answers with a JSON error object rather than throwing when the engine APK
 * is not installed - a device missing one engine should lose that one screen,
 * not crash the app.
 *
 * Call off the main thread: binding blocks.
 */
class DataBackendClient(
    context: Context,
    private val enginePackage: String,
    private val engineService: String,
) {
    private val ctx = context.applicationContext
    @Volatile private var remote: IDataBackend? = null
    @Volatile private var latch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder?.takeIf { it.pingBinder() }?.let { IDataBackend.Stub.asInterface(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
    }

    fun isInstalled(): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(enginePackage, 0); true
    }.getOrDefault(false)

    @Synchronized
    fun bindBlocking(timeoutMs: Long = 4000): Boolean {
        if (remote != null) return true
        if (!isInstalled()) return false
        val l = CountDownLatch(1)
        latch = l
        val intent = Intent().setClassName(enginePackage, engineService)
        val started = runCatching {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!started) return false
        runCatching { l.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return remote != null
    }

    /**
     * Call [method]. Returns the engine's JSON, or a JSON error object - the
     * SAME shape either way, so callers (typically a WebView bridge handing
     * the string straight to JS) need no special case for a missing engine.
     */
    fun call(method: String, vararg args: String): String {
        if (remote == null && !bindBlocking()) return errorJson(
            "$enginePackage is not installed — install it from Configs → Constellation → Libs")
        return runCatching { remote?.call(method, arrayOf(*args)) }
            .getOrNull() ?: errorJson("$enginePackage did not answer $method")
    }

    fun methods(): List<String> =
        runCatching { remote?.methods()?.toList() }.getOrNull().orEmpty()

    private fun errorJson(message: String): String =
        """{"error":${quote(message)}}"""

    private companion object {
        /** Minimal JSON string escaping - no serializer dependency in core. */
        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"'  -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
