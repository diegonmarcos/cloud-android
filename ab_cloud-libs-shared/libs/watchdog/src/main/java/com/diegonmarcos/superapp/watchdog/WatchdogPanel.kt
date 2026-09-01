package com.diegonmarcos.superapp.watchdog

/**
 * One live `tui --serve` session, wherever it happens to be running.
 *
 * Two implementations, one protocol: [WatchdogLocal.Panel] talks to a child
 * process in this app's own sandbox, [WatchdogSsh.Panel] to one on the far
 * side of a loopback ssh. Both speak the same line protocol to the same
 * binary, so the bridge above them holds a WatchdogPanel and never learns
 * which — that is what lets the app fall back from one to the other without
 * the page noticing.
 */
interface WatchdogPanel {
    /** Blocks until one whole frame has arrived; null once the panel is gone. */
    fun readFrame(): String?
    fun key(name: String)
    fun tick()
    fun resize(cols: Int, rows: Int)
    fun close()
}
