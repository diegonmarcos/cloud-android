package com.diegonmarcos.superapp.battery

import android.os.Debug
import java.util.concurrent.ConcurrentHashMap

/**
 * Intra-app energy ledger — answers "what INSIDE Cloud SuperApp is
 * consuming?". The OS won't break our own battery use down by
 * component, so each subsystem self-reports its cost here and the
 * /api/energy/self debug endpoint surfaces the breakdown.
 *
 * What we can measure precisely about OUR OWN process (no privilege):
 *   • CPU time per code path — [Debug.threadCpuTimeNanos] gives the
 *     calling thread's accumulated CPU nanos; we diff it around a
 *     block via [track]. This is real on-CPU time, the dominant
 *     proxy for compute energy.
 *   • wakeups — how often a subsystem pulls the process out of idle
 *     ([wake]); frequent wakeups defeat Doze and cost disproportionately.
 *   • bytes — network/IO bytes a subsystem moves ([bytes]); radio
 *     energy scales with bytes + how often the radio is woken.
 *   • calls — invocation count, for "cost per call" context.
 *
 * Tags are dotted namespaces so the endpoint can group by prefix:
 *   ui.galaxy, ui.shader, ui.island.wave, music.session, bg.battery,
 *   bg.energy_sampler, net.c3_health, location.tracker, …
 *
 * Thread-safe (ConcurrentHashMap + per-stat synchronized accumulate).
 * Zero cost when a tag is never touched. Reset on demand from the
 * debug endpoint so a measurement window can be scoped.
 */
object EnergyLedger {

    data class Stat(
        var cpuNanos: Long = 0L,
        var calls: Long = 0L,
        var wakeups: Long = 0L,
        var bytes: Long = 0L,
        var lastActiveMs: Long = 0L,
    )

    private val stats = ConcurrentHashMap<String, Stat>()
    @Volatile private var sinceMs: Long = 0L

    /** Stamp the window start. Pass the wall-clock now (the object
     *  can't call System.currentTimeMillis from a deterministic
     *  context, but the ledger is runtime-only so it's fine here). */
    fun markStart() { sinceMs = System.currentTimeMillis() }

    private fun stat(tag: String): Stat = stats.getOrPut(tag) { Stat() }

    /** Measure the CALLING THREAD's CPU time across [body] and add it
     *  to [tag]. Use around the hot work of a subsystem (a shader
     *  frame, a session-state evaluation, a parse). Returns body's
     *  result so it drops into expression position. */
    inline fun <T> track(tag: String, body: () -> T): T {
        val start = Debug.threadCpuTimeNanos()
        try {
            return body()
        } finally {
            val delta = Debug.threadCpuTimeNanos() - start
            // threadCpuTimeNanos returns -1 when unsupported; guard.
            addCpu(tag, if (delta > 0) delta else 0L)
        }
    }

    fun addCpu(tag: String, nanos: Long) {
        val s = stat(tag)
        synchronized(s) {
            s.cpuNanos += nanos
            s.calls += 1
            s.lastActiveMs = System.currentTimeMillis()
        }
    }

    /** Record a process wakeup attributed to [tag] (alarm fire,
     *  WorkManager tick, push callback, sensor batch, …). */
    fun wake(tag: String) {
        val s = stat(tag)
        synchronized(s) {
            s.wakeups += 1
            s.lastActiveMs = System.currentTimeMillis()
        }
    }

    /** Record [n] network/IO bytes moved by [tag]. */
    fun bytes(tag: String, n: Long) {
        if (n <= 0L) return
        val s = stat(tag)
        synchronized(s) {
            s.bytes += n
            s.lastActiveMs = System.currentTimeMillis()
        }
    }

    /** Immutable copy for the debug endpoint. */
    fun snapshot(): Pair<Long, Map<String, Stat>> {
        val copy = HashMap<String, Stat>(stats.size)
        for ((k, v) in stats) synchronized(v) {
            copy[k] = v.copy()
        }
        return sinceMs to copy
    }

    fun reset() {
        stats.clear()
        sinceMs = System.currentTimeMillis()
    }
}
