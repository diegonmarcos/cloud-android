package com.diegonmarcos.superapp.devtools

/**
 * ONE long-lived `logcat` reader per app process, feeding an in-memory ring
 * buffer that every diagnostic surface reads from.
 *
 * ── Why a stream instead of `logcat -d` per call ─────────────────────────
 * Android 13+ puts full-device log access behind a user-consent dialog, and
 * that consent CANNOT be made permanent. Verified in AOSP android15-release:
 *
 *   platform.xml               READ_LOGS maps the process into the `log` gid
 *   LogPermissions.cpp:137     clientHasLogCredentials() == "carries that gid"
 *   LogReader.cpp:260          a client WITHOUT it is served its own uid's
 *                              lines immediately and silently; one WITH it is
 *                              routed to LogcatManagerService
 *   LogcatManagerService:83    the approval is an in-memory map entry cleared
 *                              60 SECONDS after it is granted
 *
 * The gate is a property of the PROCESS (the gid), not of the command — so
 * narrowing a call with `--pid` or `--uid` changes the output and nothing
 * else. Every separate `logcat` exec is a new logd connection, a new
 * startThread(), and therefore a fresh prompt whenever the previous approval
 * has aged past its minute. That is what made the dialog reappear endlessly.
 *
 * A single reader collapses that to ONE request per process lifetime. Once
 * approved, the request is structurally unrevocable: LogReaderList.cpp:56-81
 * moves the entry OUT of `pending_reader_threads_` into
 * `running_reader_threads_`, and HandlePendingThread — the only path
 * approve/decline can reach — searches the pending list alone and returns
 * false for anything already running. The 60s expiry in the framework only
 * discards the cached decision for FUTURE requests; nothing tears down a
 * live stream.
 *
 * ── Uniform, with no per-app branching ───────────────────────────────────
 * An app WITHOUT READ_LOGS gets exactly the same stream and never prompts at
 * all — logd just hands it its own uid's lines. So the fleet and the SuperApp
 * run identical code and differ only in what logd chooses to feed them, which
 * is the one place that decision belongs.
 */
object LogPipe {

    /** Sentinel for [tail]: do not filter by uid. */
    const val ANY_UID = -1

    /**
     * Ring capacity, and the history seeded at startup.
     *
     * `logcat` without `-d` dumps what is already buffered and THEN follows,
     * so `-T SEED_LINES` is what stops a reader started lazily from answering
     * "the last 300 lines" with only the handful logged since it woke up.
     */
    private const val MAX_LINES = 4000
    private const val SEED_LINES = 2000

    private val lock = Object()
    private val lines = ArrayDeque<String>(MAX_LINES)

    @Volatile private var reader: Thread? = null
    @Volatile private var streaming = false
    @Volatile private var restarts = 0
    @Volatile private var lastError: String? = null
    @Volatile private var startedAtMs = 0L

    /**
     * Newest [n] lines, optionally narrowed.
     *
     * @param uid keep only this uid's lines ([ANY_UID] for all). Filtering
     *   here rather than with `logcat --uid` is what lets ONE stream serve
     *   both the all-apps and the single-app view.
     * @param errorsOnly keep only E/F levels.
     *
     * NEVER returns an empty string. A broken reader and a genuinely quiet
     * buffer are different answers and must read differently — an empty
     * response on the surface used to diagnose everything else would be a
     * failure reporting success.
     */
    fun tail(n: Int, uid: Int = ANY_UID, errorsOnly: Boolean = false): String {
        ensureStarted()
        val snapshot = synchronized(lock) { lines.toList() }
        val matched = snapshot.asSequence()
            .filter { uid == ANY_UID || uidOf(it) == uid }
            .filter { !errorsOnly || levelOf(it) == 'E' || levelOf(it) == 'F' }
            .toList()
        if (matched.isNotEmpty()) return matched.takeLast(n).joinToString("\n") + "\n"
        return if (streaming) "(no matching lines — reader is HEALTHY, ${snapshot.size} buffered; " +
            "$health)\n"
        else "logcat stream DOWN — $health\n"
    }

    /** One-line health string; embedded in every empty answer above and worth
     *  logging on its own when a caller wants the state without the text. */
    val health: String
        get() {
            val age = if (startedAtMs == 0L) -1 else (System.currentTimeMillis() - startedAtMs) / 1000
            return "streaming=$streaming restarts=$restarts uptime=${age}s" +
                (lastError?.let { " lastError=$it" } ?: "")
        }

    /**
     * Start the reader once. Idempotent and safe from any thread.
     *
     * Deliberately lazy: starting it in an Application.onCreate would make the
     * SuperApp prompt on EVERY launch even when nobody opens a log screen.
     * Started on first read, the prompt costs one dialog and only for a user
     * who actually asked for logs.
     */
    @Synchronized
    fun ensureStarted() {
        if (reader?.isAlive == true) return
        startedAtMs = System.currentTimeMillis()
        reader = Thread({ pump() }, "LogPipe").apply { isDaemon = true; start() }
    }

    /**
     * Read the stream forever, restarting it if it ends.
     *
     * logd can drop a reader (buffer overrun, logd restart), and a dead pump
     * that stayed quiet would silently freeze every diagnostic at whatever was
     * buffered when it died — the exact silent-failure shape this whole class
     * exists to remove. So a restart is recorded, never hidden, and `lastError`
     * survives into [health] even after a successful reconnect.
     */
    private fun pump() {
        var backoffMs = 1_000L
        while (true) {
            var proc: Process? = null
            try {
                proc = ProcessBuilder(
                    "logcat", "-b", "all", "-v", "threadtime", "-v", "uid",
                    "-T", SEED_LINES.toString(),
                ).redirectErrorStream(true).start()
                streaming = true
                backoffMs = 1_000L
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lock) {
                        lines.addLast(line)
                        while (lines.size > MAX_LINES) lines.removeFirst()
                    }
                }
                // forEachLine returning means EOF: logcat exited on its own.
                lastError = "stream ended (logcat exited)"
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
            } finally {
                streaming = false
                runCatching { proc?.destroy() }
            }
            restarts++
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                return
            }
            backoffMs = minOf(backoffMs * 2, 60_000L)
        }
    }

    /**
     * uid column of a `-v threadtime -v uid` line, or -1.
     *
     * Layout is `MM-DD HH:MM:SS.mmm UID PID TID L TAG: msg`. Anything else —
     * logcat's own `--------- beginning of main` separators most of all — has
     * no uid and must never match a uid filter.
     */
    private fun uidOf(line: String): Int {
        val f = line.trim().split(WHITESPACE)
        return if (f.size > 2) f[2].toIntOrNull() ?: -1 else -1
    }

    /** Level column of the same layout, or ' ' when the line is not a record. */
    private fun levelOf(line: String): Char {
        val f = line.trim().split(WHITESPACE)
        return if (f.size > 5 && f[5].length == 1) f[5][0] else ' '
    }

    private val WHITESPACE = Regex("\\s+")
}
