package com.diegonmarcos.superapp.updater

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
