package com.diegonmarcos.ide

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * JSch-backed SSH engine for the cloud-ide terminal.
 *
 * Key management: on first use an ECDSA nistp256 key pair is generated and
 * persisted to [Context.filesDir]/terminal_id_ecdsa (private) and
 * terminal_id_ecdsa.pub (OpenSSH public). [publicKeyOpenSsh] returns the
 * single authorized_keys line for the user to paste into each env.
 *
 * Session management: one [Session] is cached per backend key and reused
 * across shell channels. Dead sessions are reconnected transparently.
 *
 * PTY channels: one [ChannelShell] per terminal id. A background reader thread
 * streams stdout/stderr bytes → [onData] callback. EOF/error → [onExit].
 *
 * File ops ([listDir], [readFile], [writeFile]): short-lived ChannelExec calls,
 * each reusing the cached session.
 *
 * All public methods may block; call on a background thread.
 */
class SshBackend(private val ctx: Context) {

    // ── Key management ────────────────────────────────────────────────────────

    private val jsch = JSch()
    private val privKeyFile = File(ctx.filesDir, "terminal_id_ecdsa")
    private val pubKeyFile  = File(ctx.filesDir, "terminal_id_ecdsa.pub")

    init { ensureKeyPair() }

    private fun ensureKeyPair() {
        if (privKeyFile.exists()) {
            jsch.addIdentity(privKeyFile.absolutePath)
            return
        }
        // Generate ECDSA nistp256 key — modern, compact, widely supported.
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        kp.writePrivateKey(privKeyFile.absolutePath)
        kp.writePublicKey(pubKeyFile.absolutePath, "cloud-ide-terminal")
        kp.dispose()
        jsch.addIdentity(privKeyFile.absolutePath)
    }

    /** Single authorized_keys line to paste into each env's ~/.ssh/authorized_keys. */
    fun publicKeyOpenSsh(): String =
        if (pubKeyFile.exists()) pubKeyFile.readText().trim()
        else "(key not yet generated — open the terminal once first)"

    // ── Session cache ─────────────────────────────────────────────────────────

    // Keyed by backend key string (e.g. "termux").
    private val sessions = ConcurrentHashMap<String, Session>()

    private fun session(target: TerminalTargets.Target): Session {
        val existing = sessions[target.key]
        if (existing != null && existing.isConnected) return existing

        val s = jsch.getSession(target.user, target.host, target.port)
        s.setConfig("StrictHostKeyChecking",    "no")
        s.setConfig("PreferredAuthentications", "publickey")
        s.connect(15_000)   // 15 s connect timeout
        sessions[target.key] = s
        return s
    }

    // ── Shell (PTY) channels ──────────────────────────────────────────────────

    private data class ShellEntry(
        val channel: ChannelShell,
        val stdin: OutputStream,
        val session: Session,
    )
    private val shells = ConcurrentHashMap<String, ShellEntry>()

    /**
     * Opens an SSH shell for terminal [id] with the given [cols]×[rows] PTY
     * size. [onData] receives streamed output as strings; [onExit] fires when
     * the channel closes.
     *
     * If [cwd] is non-empty, `cd '<cwd>'` is sent after the shell starts so
     * the first prompt appears in the right directory.
     *
     * Throws on connection failure — the bridge catches and surfaces the message
     * to the terminal as a red ANSI error line.
     */
    fun openShell(
        target:  TerminalTargets.Target,
        id:      String,
        cols:    Int,
        rows:    Int,
        cwd:     String,
        onData:  (String) -> Unit,
        onExit:  () -> Unit,
    ) {
        val sess = session(target)   // throws on failure
        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")
        ch.setPtySize(cols, rows, 0, 0)
        ch.connect(10_000)

        val stdin = ch.outputStream
        shells[id] = ShellEntry(ch, stdin, sess)

        // Optional cwd navigation — send before the user types anything.
        if (cwd.isNotEmpty()) {
            stdin.write("cd '${cwd.replace("'", "'\\''")}'\n".toByteArray())
            stdin.flush()
        }

        // Background reader — streams raw bytes from the pty.
        Thread({
            try {
                val inp = ch.inputStream
                val buf = ByteArray(8192)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    onData(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // Channel closed or error — fall through to onExit.
            } finally {
                shells.remove(id)
                ch.disconnect()
                onExit()
            }
        }, "ssh-reader-$id").also { it.isDaemon = true }.start()
    }

    /** Write raw bytes to an open shell's stdin. */
    fun write(id: String, data: String) {
        val entry = shells[id] ?: return
        try {
            entry.stdin.write(data.toByteArray(Charsets.UTF_8))
            entry.stdin.flush()
        } catch (_: Exception) { /* channel gone */ }
    }

    /** Resize the pty for an open shell. */
    fun resize(id: String, cols: Int, rows: Int) {
        shells[id]?.channel?.setPtySize(cols, rows, 0, 0)
    }

    /** Disconnect and remove an open shell. */
    fun kill(id: String) {
        shells.remove(id)?.channel?.disconnect()
    }

    // ── File operations ───────────────────────────────────────────────────────

    /**
     * Lists entries in [path] on the remote. Returns list of (name, is_dir)
     * pairs. Skips the synthetic "." and ".." entries. Leading `~` is expanded
     * by the remote shell.
     */
    fun listDir(target: TerminalTargets.Target, path: String): List<Pair<String, Boolean>> {
        val safePath = path.replace("'", "'\\''")
        // One line per entry: name<TAB>0|1 (1 = directory).
        val cmd = "sh -lc 'cd \"$safePath\" && for e in .* *; do " +
            "[ -e \"\$e\" ] || continue; " +
            "[ -d \"\$e\" ] && echo \"\$e\t1\" || echo \"\$e\t0\"; done'"
        val out = execReadAll(target, cmd)
        return out.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val tab = line.lastIndexOf('\t')
                if (tab < 0) return@mapNotNull null
                val name  = line.substring(0, tab)
                val isDir = line.substring(tab + 1).trim() == "1"
                // Filter the always-present synthetic entries that are empty after trim.
                if (name.isEmpty() || name == "." || name == "..") return@mapNotNull null
                name to isDir
            }
    }

    /**
     * Reads [path] on the remote and returns the raw text content.
     * Large files will be loaded entirely into memory — the frontend is
     * expected to call this only for code files of reasonable size.
     */
    fun readFile(target: TerminalTargets.Target, path: String): String {
        val safePath = path.replace("'", "'\\''")
        return execReadAll(target, "cat -- '$safePath'")
    }

    /**
     * Writes [content] to [path] on the remote via `cat > '<path>'`.
     * Simple and robust for text content of reasonable size.
     */
    fun writeFile(target: TerminalTargets.Target, path: String, content: String) {
        val safePath = path.replace("'", "'\\''")
        execWithInput(target, "cat > '$safePath'", content)
    }

    // ── Exec helpers ──────────────────────────────────────────────────────────

    private fun execReadAll(target: TerminalTargets.Target, cmd: String): String {
        val sess = session(target)
        val ch   = sess.openChannel("exec") as ChannelExec
        ch.setCommand(cmd)
        ch.setErrStream(System.err)
        ch.connect(10_000)
        val bytes = ch.inputStream.readBytes()
        ch.disconnect()
        return String(bytes, Charsets.UTF_8)
    }

    private fun execWithInput(target: TerminalTargets.Target, cmd: String, input: String) {
        val sess = session(target)
        val ch   = sess.openChannel("exec") as ChannelExec
        ch.setCommand(cmd)
        ch.setErrStream(System.err)
        // Must retrieve outputStream BEFORE connect; JSch sets up the stdin pipe
        // during the openChannel phase and seals it on connect.
        val stdin = ch.outputStream
        ch.connect(10_000)
        stdin.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        // Drain stdout so the channel completes cleanly.
        ch.inputStream.readBytes()
        ch.disconnect()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Disconnect all open shells and sessions (call from Activity.onDestroy). */
    fun disconnectAll() {
        shells.values.forEach { it.channel.disconnect() }
        shells.clear()
        sessions.values.forEach { if (it.isConnected) it.disconnect() }
        sessions.clear()
    }
}
