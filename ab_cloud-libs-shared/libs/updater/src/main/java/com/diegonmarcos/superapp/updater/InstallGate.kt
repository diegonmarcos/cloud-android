package com.diegonmarcos.superapp.updater

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serialises fleet installs for real.
 *
 * PackageInstaller.commit() only HANDS OVER a session: it returns immediately
 * and the install proceeds asynchronously, with the outcome arriving at
 * [PackageInstallerReceiver]. So a loop that calls commit() N times in a row
 * is sequential dispatch, not sequential installation - it starts N installs
 * at once. That races in three ways:
 *
 *   - concurrent sessions on the same installer, which is exactly the
 *     collision the batch is supposed to avoid;
 *   - N stacked system confirm dialogs, where answering one reveals another
 *     and it is unclear which app each belongs to;
 *   - N sessions held open simultaneously against Android's 50-session cap,
 *     the failure ("Too many active sessions for UID") this whole area exists
 *     to prevent.
 *
 * So the batch arms a gate before commit and waits on it after. The receiver
 * opens it when the install reaches a state where starting the next one is
 * safe.
 */
internal object InstallGate {

    private val gates = ConcurrentHashMap<String, CountDownLatch>()

    /**
     * Process-wide: ONE install at a time, whoever started it.
     *
     * Per-batch serialisation was not enough. Three other paths start installs
     * and none of them went through the batch:
     *   - the Constellation list's per-row install button, which spawns a
     *     thread per tap, so three taps started three installs at once;
     *   - ApkInstallWorker and UpdateWorker, which can fire while a batch or a
     *     manual install is already running.
     * All of them collide the same way: concurrent sessions, stacked confirm
     * dialogs, and sessions piling up against Android's 50-session cap.
     */
    private val installLock = ReentrantLock()

    /**
     * Run [commitSession] as the only install in flight, and wait for it to
     * settle before releasing the next caller.
     *
     * This is the single choke point: it lives in UpdateInstaller.install, so
     * every path - batch, per-row button, worker - is serialised without each
     * having to remember to do it.
     */
    fun serialised(pkg: String, timeoutMs: Long, commitSession: () -> Unit) {
        installLock.withLock {
            // Armed inside the lock and before commit: the result can arrive
            // before commit() returns, and a gate armed afterwards would wait
            // for an event that already happened.
            arm(pkg)
            try {
                commitSession()
            } catch (t: Throwable) {
                open(pkg)   // never strand the next caller on a failed commit
                throw t
            }
            await(pkg, timeoutMs)
        }
    }

    /** How long one install may hold the queue. The user may be answering a
     *  system dialog, so it is generous; it exists only so a receiver that
     *  never fires cannot wedge every later install. */
    const val SETTLE_MS = 3L * 60L * 1000L

    /** Arm before commit, so a result that arrives fast cannot be missed. */
    fun arm(pkg: String) {
        gates[pkg] = CountDownLatch(1)
    }

    /**
     * Block until [pkg]'s install settles, or [timeoutMs] elapses.
     *
     * The timeout is a backstop, not the mechanism: a receiver that never
     * fires (process death, a session the system silently drops) must not
     * wedge the batch forever.
     */
    fun await(pkg: String, timeoutMs: Long): Boolean {
        val latch = gates[pkg] ?: return true
        val settled = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!settled) Log.w(TAG, "install of $pkg did not settle in ${timeoutMs}ms — continuing")
        gates.remove(pkg)
        return settled
    }

    /**
     * Open the gate. Called for terminal outcomes AND for
     * STATUS_PENDING_USER_ACTION once the prompt has been surfaced.
     *
     * Pending-user-action counts as settled on purpose: a background pass
     * cannot show the dialog, so it posts a notification and the session then
     * waits on a human who may never answer. Blocking there would stall the
     * whole batch indefinitely. The per-pass cap is what bounds how many such
     * sessions can pile up - not this gate.
     */
    fun open(pkg: String) {
        gates[pkg]?.countDown()
    }

    private const val TAG = "InstallGate"
}
