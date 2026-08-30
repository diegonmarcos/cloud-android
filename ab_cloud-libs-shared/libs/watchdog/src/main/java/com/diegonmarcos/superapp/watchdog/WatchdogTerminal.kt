package com.diegonmarcos.superapp.watchdog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.termux.cloud.ICloudExec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Run a command in our Termux, by binding to it.
 *
 * WHAT THIS REPLACES
 * [WatchdogSsh] reaches a terminal by opening an ssh connection to 127.0.0.1,
 * which only works when an sshd is installed, running, on the port this build
 * guessed and holding our key. Four things, each of which can be false on a
 * phone, and the app could not tell which one was — every failure arrived as
 * the same ECONNREFUSED.
 *
 * Binding has one failure mode instead of four, and it is legible: either our
 * Termux is installed or it is not. Nothing to configure, nothing to start, no
 * key to deploy, no port. It works because we own that terminal now — the
 * service is in our fork, behind a signature-level permission, so only APKs
 * signed with the constellation key can call it.
 *
 * WHY THE SSH PATH STAYS
 * It is the only way to another MACHINE. This reaches the phone; the fleet is
 * still ssh, and always was going to be.
 */
class WatchdogTerminal(private val ctx: Context) {

    @Volatile private var svc: ICloudExec? = null
    @Volatile private var binding = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = ICloudExec.Stub.asInterface(binder)
            binding = false
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Termux was updated or killed. Dropped rather than retried here:
            // the next exec binds again, and a reconnect loop against an app
            // the user is uninstalling is a battery drain with no reader.
            svc = null
        }
    }

    @Volatile private var latch: CountDownLatch? = null

    /** Whether our Termux is installed at all — the one thing that can be wrong. */
    fun available(): Boolean =
        ctx.packageManager.resolveService(intent(), 0) != null

    private fun intent() = Intent(ACTION).apply { setPackage(TERMUX_PKG) }

    /**
     * Bind, waiting up to [BIND_MS].
     *
     * Blocking on purpose: every caller here wants to run something, and an
     * async bind would only move the same wait somewhere less obvious. Callers
     * are already on a background thread — [WatchdogBridge] runs them on its
     * pool — so there is no main thread to hold up.
     */
    private fun connect(): ICloudExec? {
        svc?.let { return it }
        synchronized(this) {
            svc?.let { return it }
            val l = CountDownLatch(1)
            latch = l
            binding = true
            if (!ctx.bindService(intent(), conn, Context.BIND_AUTO_CREATE)) {
                binding = false
                return null
            }
            l.await(BIND_MS, TimeUnit.MILLISECONDS)
            return svc
        }
    }

    /**
     * Run a command and get its output, or an explanation.
     *
     * The failure is named rather than folded into an empty string: "Termux is
     * not installed" and "the command exited 127" send you to two different
     * places, and the previous design collapsed both into a blank screen.
     */
    fun exec(
        executable: String,
        args: Array<String> = emptyArray(),
        stdin: String? = null,
        workdir: String? = null,
        timeoutMs: Int = 30_000,
    ): Result<String> = runCatching {
        val s = connect() ?: error(
            if (available()) "Termux did not answer within ${BIND_MS}ms"
            else "our Termux is not installed on this device"
        )
        val b = s.exec(executable, args, stdin, workdir, timeoutMs)
            ?: error("no answer from the terminal")
        b.getString("error")?.let { error(it) }
        val exit = b.getInt("exit", -1)
        if (exit != 0) {
            val err = b.getString("stderr").orEmpty().trim()
            error("$executable exited $exit" + if (err.isEmpty()) "" else ": ${err.take(300)}")
        }
        b.getString("stdout").orEmpty()
    }

    /**
     * Absolute path to a binary the terminal ships, asked of the terminal
     * rather than assumed. nativeLibraryDir differs by ABI, by install
     * location and across Android versions, so a path built on this side is a
     * guess that breaks on somebody else's phone.
     */
    fun toolPath(name: String): String? =
        runCatching { connect()?.toolsDir() }.getOrNull()?.let { "$it/$name" }

    /** This terminal's ${'$'}PREFIX — where a binary we placed there actually is. */
    fun prefix(): String? = runCatching { connect()?.prefix() }.getOrNull()

    fun close() {
        if (svc != null || binding) runCatching { ctx.unbindService(conn) }
        svc = null
        binding = false
    }

    companion object {
        /** Our Termux fork. Same applicationId as upstream — see its README. */
        // OUR terminal fork, not the official app: renamed com.termux ->
        // cld.termux (3aba806aa) precisely so both coexist on the phone.
        // The AIDL descriptor stays com.termux.cloud.ICloudExec — the Java
        // namespace was deliberately NOT renamed, only the applicationId.
        const val TERMUX_PKG = "cld.termux"
        const val ACTION = "cld.termux.CLOUD_EXEC"

        /** Loopback-fast. A bind that has not landed by now is not slow, it is absent. */
        const val BIND_MS = 3_000L
    }
}
