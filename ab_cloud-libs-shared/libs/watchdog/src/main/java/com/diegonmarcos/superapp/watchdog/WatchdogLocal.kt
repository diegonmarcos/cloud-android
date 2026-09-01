package com.diegonmarcos.superapp.watchdog

import android.content.Context
import java.io.File

/**
 * The panel, running in THIS app's own process. No ssh, no terminal, no phone
 * setup — open the app and the screen is there.
 *
 * WHY THIS EXISTS
 * Every pixel the app shows is a transcription of the panel's ratatui buffer,
 * so with the panel only ever reachable over ssh, a refused connection left
 * nothing to draw and the app opened on an error message instead of a UI. The
 * dashboard was one sshd port away from existing at all — on a phone that is
 * its own machine, monitored by an app that already carries the binary.
 *
 * WHY THE BINARY LIVES IN jniLibs
 * Android will not execute a file the app wrote itself: since API 29, W^X
 * means nothing under filesDir or an unpacked asset can carry +x, which is
 * why these shipped as assets and could only ever be streamed somewhere else
 * to run. [Context.ApplicationInfo.nativeLibraryDir] is the one directory an
 * app can both ship into and exec from, so the panel is packaged as
 * `libmywatchdogtui.so`. It is not a shared library and nothing dlopens it —
 * the `.so` name is the price of admission to the only executable directory
 * Android offers. Termux ships its bootstrap the same way.
 *
 * It is a static musl aarch64 build, so there is no interpreter to find and
 * no libc to match: exactly the property that makes it runnable both here and
 * inside a nix-on-droid proot without rebuilding.
 *
 * WHAT IT CAN AND CANNOT SEE
 * A normal app uid, so /proc/stat, /proc/meminfo, /proc/loadavg and the app's
 * own processes read fine, while other apps' /proc entries are hidden by
 * hidepid — the phone's own numbers are real, its process table is partial.
 * For the fleet the daemon is still the source, and for anything needing a
 * different uid [WatchdogSsh] is still the way there. This is the local view,
 * not a replacement for either.
 */
class WatchdogLocal(private val ctx: Context) {

    /** The shipped panel, or null on a device this build has no binary for. */
    private fun binary(): File? {
        val f = File(ctx.applicationInfo.nativeLibraryDir, BIN)
        return if (f.canExecute()) f else null
    }

    /** Whether the local panel is available at all — drives the fallback. */
    fun available(): Boolean = binary() != null

    /**
     * The sampler, ours, kept for the life of the app.
     *
     * The panel renders a snapshot it does not itself collect, so without this
     * the local view comes up correctly framed and completely empty — which
     * looks exactly like a bug and is the failure mode most likely to be
     * mistaken for one. Started once and reused: a second sampler on the same
     * machine is two processes writing one snapshot, which is how oci-mail
     * ended up with two daemons and an 8GB read.
     */
    @Volatile private var daemon: Process? = null

    private fun ensureDaemon() {
        if (daemon?.isAlive == true) return
        val bin = File(ctx.applicationInfo.nativeLibraryDir, DAEMON)
        if (!bin.canExecute()) return
        daemon = runCatching {
            ProcessBuilder(bin.absolutePath, "--no-tray")
                .directory(ctx.cacheDir)
                .redirectErrorStream(true)
                .start()
        }.getOrNull()
    }

    fun open(cols: Int, rows: Int): Result<Panel> = runCatching {
        val bin = binary() ?: error("no bundled panel for this device's ABI")
        ensureDaemon()
        val p = ProcessBuilder(bin.absolutePath, "tui", "--serve", "$cols", "$rows")
            // Its own cache dir: the panel writes a snapshot beside itself and
            // nativeLibraryDir is read-only, so without this it starts in one
            // and cannot write to the other.
            .directory(ctx.cacheDir)
            // Merged, deliberately: a panic on stderr then arrives in the same
            // stream as the frames and shows up ON the screen instead of
            // vanishing into a log nobody reads while the app hangs waiting
            // for a frame that is never coming.
            .redirectErrorStream(true)
            .start()
        Panel(p)
    }

    /**
     * Same protocol as the ssh panel, over pipes instead of a channel — the
     * sentinel is what makes a frame complete, so neither side needs a pty
     * and neither infers the end of a screen from a pause.
     */
    class Panel internal constructor(private val proc: Process) : WatchdogPanel {
        private val stdin = proc.outputStream
        private val stdout = proc.inputStream.bufferedReader()

        override fun readFrame(): String? {
            val sb = StringBuilder()
            while (true) {
                val line = stdout.readLine() ?: return null
                if (line == WatchdogSsh.FRAME_END) return sb.toString()
                sb.append(line).append('\n')
            }
        }

        private fun send(line: String) {
            stdin.write((line + "\n").toByteArray())
            stdin.flush()
        }

        override fun key(name: String) = send("key:$name")
        override fun tick() = send("tick")
        override fun resize(cols: Int, rows: Int) = send("size:${cols}x$rows")

        override fun close() {
            runCatching { send("quit") }
            // Asked first, then insisted on: `quit` lets it put the terminal
            // back and flush, but a panel already wedged would otherwise
            // survive the activity and keep sampling in the background.
            runCatching { proc.waitFor() }
            runCatching { proc.destroy() }
        }
    }

    companion object {
        /**
         * Must match the jniLibs name in build.gradle. Packaged per-ABI, so
         * Android picks the right one and this never names an architecture.
         */
        const val BIN = "libmywatchdogtui.so"

        /** The sampler that fills the snapshot the panel draws. */
        const val DAEMON = "libmywatchdog.so"
    }
}
