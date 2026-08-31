package com.diegonmarcos.superapp.watchdog

import android.content.Context
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The terminal, reached the way cloud-ide reaches it.
 *
 * WHY SSH TO LOOPBACK AND NOT AN INTENT
 * nix-on-droid is another app with its own uid, and Android gives us no way to
 * run a command inside it and read the output back — RUN_COMMAND fires and
 * forgets, and the interesting output is on the other side of a sandbox. What
 * DOES cross is 127.0.0.1: the loopback interface is shared between app
 * sandboxes, so its sshd is reachable without any cross-UID barrier and
 * without the packet leaving the phone. cloud-ide already talks to both envs
 * this way, so this is the constellation's existing answer rather than a
 * second one.
 *
 * WHY ChannelExec AND NOT A PTY
 * Two shapes, both exec. [screen] asks one question and reads one answer, for
 * the static report. [open] starts `tui --serve` and keeps it: keys down its
 * stdin, frames off its stdout, one process for the whole session so the panel
 * keeps which tab, which sort and which row the cursor is on.
 *
 * Neither needs a pty, and that is the point. A pty means finding the end of
 * an answer by watching for a prompt or a pause, which paints half-drawn
 * screens the moment the far side is slow — and on a throttled free-tier box
 * that is most of the time. A sentinel line the transcript cannot contain says
 * exactly where a screen ends.
 *
 * KEYS
 * Generated here on first use and never leaving the app: an ECDSA nistp256
 * pair in [Context.filesDir]. [publicKeyLine] is the single authorized_keys
 * line to paste into the env — the same setup step cloud-ide asks for, and
 * deliberately manual, because an app that could write its own key into
 * another environment's authorized_keys would be a worse thing than a
 * setup step.
 */
class WatchdogSsh(private val ctx: Context) {

    private val jsch = JSch()
    private var session: Session? = null

    private val keyFile get() = File(ctx.filesDir, "cloud_constellation_ed25519")

    /** The backends the build baked in — host, port and user are data. */
    private val targets: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.WATCHDOG_TARGETS_JSON_B64, Base64.DEFAULT)))
            .getJSONObject("backends")
    }

    fun backendKeys(): List<String> = targets.keys().asSequence().toList()

    fun label(key: String): String =
        targets.optJSONObject(key)?.optString("label") ?: key

    /**
     * Write out the DECLARED constellation key.
     *
     * Not generated. One key is shared by every app that reaches a phone
     * terminal, so an environment can authorize it BEFORE any app has run:
     * termux/authorized_keys in cloud-u-linux ships the public half and the
     * installer provisions it. A key an app invents at first run cannot be
     * provisioned for — authorized_keys would have to wait for the app to
     * exist, be launched, and have its key read off a screen and pasted, once
     * per app per phone, forever. That step is why this app had a setup
     * screen; it does not need one now.
     *
     * The private half is baked from the vault at build time
     * (build.sh::_resolve_ssh_key), the same shape as the one shared signing
     * key. Written to filesDir because JSch wants a path, not bytes.
     */
    private fun ensureKey() {
        if (keyFile.isFile()) return
        val pem = runCatching {
            String(Base64.decode(BuildConfig.CLOUD_SSH_KEY_B64, Base64.DEFAULT))
        }.getOrDefault("")
        // NO FALLBACK TO A GENERATED KEY. One the env has never authorized
        // fails at connect with "permission denied", which reads as a broken
        // app; saying the build lacked the vault points at the actual cause.
        check(pem.isNotBlank()) {
            "no declared ssh key in this build — built without the vault " +
                "(build.sh::_resolve_ssh_key). This app does not generate one."
        }
        keyFile.writeText(pem)
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
    }

    /**
     * The public half, for a diagnostic screen — NOT a setup step any more.
     * The env is provisioned from cloud-u-linux/da__my-konsole/termux/
     * authorized_keys; this exists so an "unreachable" message can show which
     * key was offered when that provisioning has not happened yet.
     */
    fun publicKeyLine(): String = PUBLIC_KEY

    /** Which env the live session actually reached — not which one was asked for. */
    @Volatile var activeBackend: String? = null
        private set

    private fun dial(key: String): Session {
        ensureKey()
        val t = targets.getJSONObject(key)
        jsch.addIdentity(keyFile.absolutePath)
        val s = jsch.getSession(t.getString("user"), t.getString("host"), t.getInt("port"))
        // The host is this device. There is no man in the middle on loopback to
        // be protected from, and a known_hosts prompt no one can answer would
        // simply mean the app never connects — the same choice cloud-ide makes.
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(CONNECT_MS)
        return s
    }

    /**
     * The preferred env, then the others — a real fallback, not a preference.
     *
     * Which Linux is installed on a phone is not something this app can know
     * and not something a user should have to tell it twice: nix-on-droid and
     * Termux are both plausible, either may be the only one present, and either
     * may simply not be running its sshd right now. So the preferred one is
     * tried first and the rest are tried after, in declaration order.
     *
     * The failure that matters is reported from the PREFERRED env, not from
     * whichever was tried last: "termux: connection refused" on a phone that
     * only ever had nix-on-droid sends the reader to the wrong place.
     */
    private fun connect(key: String): Session {
        session?.let { if (it.isConnected) return it }
        val order = listOf(key) + backendKeys().filter { it != key }
        var first: Throwable? = null
        for (k in order) {
            try {
                val s = dial(k)
                session = s
                activeBackend = k
                return s
            } catch (t: Throwable) {
                if (first == null) first = t
            }
        }
        activeBackend = null
        throw IllegalStateException(first?.message ?: "no terminal reachable on this device")
    }

    /**
     * Every remote command, run by /bin/sh instead of by whoever the account's
     * login shell happens to be.
     *
     * `ssh host "…"` does not run a command, it hands a STRING to the user's
     * shell — and this env's is fish, which has no `(cmd &)` subshell. The
     * sampler launcher is exactly that shape, so it died at parse time with
     * "fish: command substitutions not allowed here", the launcher is
     * best-effort so nothing surfaced, and every snapshot afterwards failed
     * with "the sampler did not publish" — a message about the far side that
     * was really about this line. Which shell a user prefers is not something
     * this app gets to have an opinion about; sending POSIX and asking for a
     * POSIX shell is.
     */
    private fun posix(cmd: String) = "sh -c '" + cmd.replace("'", "'\\''") + "'"

    /**
     * Run one command and return its stdout.
     *
     * stderr is folded into the failure message rather than the result: a
     * screen with an error message rendered into it looks like a reading.
     */
    fun exec(backend: String, cmd: String): Result<String> = runCatching {
        val ch = connect(backend).openChannel("exec") as ChannelExec
        ch.setCommand(posix(cmd))
        val err = ByteArrayOutputStream()
        ch.setErrStream(err)
        val out = ch.inputStream
        ch.connect(CONNECT_MS)
        val body = out.readBytes().toString(Charsets.UTF_8)
        while (!ch.isClosed) Thread.sleep(20)
        val code = ch.exitStatus
        ch.disconnect()
        if (code != 0) {
            val why = err.toString(Charsets.UTF_8.name()).trim().ifEmpty { "exit $code" }
            throw IllegalStateException(why)
        }
        body
    }

    /**
     * One screen, at the grid the DEVICE asked for.
     *
     * The size is the caller's because only the caller knows it: a phone, that
     * phone rotated, and a foldable opened are three different terminals, and
     * the panel already knows how to be any width. That is the whole reason it
     * is asked to draw rather than being reimplemented here.
     */
    fun screen(backend: String, cols: Int, rows: Int): Result<String> {
        ensureTools(backend).getOrThrow()
        ensureDaemon(backend)
        return exec(backend, "'$REMOTE_DIR/$BIN_PANEL' tui $cols $rows")
    }

    /**
     * A SESSION, not a request: one long-lived `tui --serve` on the far side,
     * keys written to its stdin and frames read off its stdout.
     *
     * One process per session rather than one per refresh, because the panel
     * has state — which tab, which sort, which row the cursor is on — and a
     * fresh process per keystroke would forget all of it between presses. This
     * is the same reason a terminal runs one program rather than re-launching
     * it for every key.
     *
     * Line-oriented, so no pty is needed: a frame ends at a sentinel the
     * transcript cannot contain, which means the reader knows the screen is
     * COMPLETE rather than inferring it from a pause. A pause-based reader
     * paints half-drawn screens the moment the far side is slow, which on a
     * throttled free-tier box is most of the time.
     */
    inner class Panel internal constructor(private val ch: ChannelExec) : WatchdogPanel {
        private val stdin = ch.outputStream
        private val stdout = ch.inputStream.bufferedReader()

        /** Blocks until one whole frame has arrived. */
        override fun readFrame(): String? {
            val sb = StringBuilder()
            while (true) {
                val line = stdout.readLine() ?: return null
                if (line == FRAME_END) return sb.toString()
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
            runCatching { ch.disconnect() }
        }
    }

    /**
     * Put this library's own binaries where the terminal can run them.
     *
     * NOTHING IS INSTALLED ON THE PHONE. The env is where a command runs, not
     * somewhere anything lives: the only thing that ever reaches it is the ssh
     * public key, and its own installer puts that there. So the tools ride
     * inside the APK and are streamed into a scratch directory under the
     * terminal's own cache — a bare nix-on-droid works with nothing fetched,
     * nothing added to PATH, and no way for the panel to drift from the app
     * driving it, because they shipped together.
     *
     * Pushed only when the size differs, which is enough: these come from one
     * release and change together, so a byte-identical length means the same
     * build. A hash would cost a second round trip on every open to answer a
     * question the length already answers.
     */
    private fun ensureTools(backend: String): Result<Unit> = runCatching {
        val dir = REMOTE_DIR
        // Source is nativeLibraryDir, not assets: the binaries moved there so
        // this app can also RUN them itself (see WatchdogLocal). They keep
        // their plain names on the far side — the `lib*.so` spelling buys
        // execute permission on Android and means nothing in a shell.
        for ((lib, name) in listOf(LIB_DAEMON to BIN_DAEMON, LIB_PANEL to BIN_PANEL)) {
            val src = File(ctx.applicationInfo.nativeLibraryDir, lib)
            if (!src.isFile) error("$lib missing — this build carries no binary for this device's ABI")
            val local = src.readBytes()
            val have = exec(backend, "stat -c %s '$dir/$name' 2>/dev/null || echo 0")
                .getOrDefault("0").trim().toLongOrNull() ?: 0L
            if (have == local.size.toLong()) continue
            // cat > file: the env may have no scp, no rsync and no curl, and
            // this needs none of them — the bytes ride the channel already open.
            val ch = connect(backend).openChannel("exec") as ChannelExec
            ch.setCommand(posix("mkdir -p '$dir' && cat > '$dir/$name.new' && " +
                "chmod +x '$dir/$name.new' && mv -f '$dir/$name.new' '$dir/$name'"))
            val stdin = ch.outputStream
            ch.connect(CONNECT_MS)
            stdin.write(local)
            stdin.flush()
            stdin.close()
            while (!ch.isClosed) Thread.sleep(20)
            val code = ch.exitStatus
            ch.disconnect()
            if (code != 0) error("could not place $name (exit $code)")
        }
    }

    /**
     * The sampler, running in the env, because the panel reads what it
     * publishes and shows an empty screen without it. Started detached and
     * headless — a phone has no tray and no D-Bus session for one.
     */
    private fun ensureDaemon(backend: String) {
        exec(backend,
            "pgrep -x $BIN_DAEMON >/dev/null 2>&1 || " +
            "(nohup '$REMOTE_DIR/$BIN_DAEMON' --no-tray >/dev/null 2>&1 &) ; true")
            // Best effort, but no longer silent: this failing is the reason
            // every later snapshot says the sampler did not publish, and it
            // used to be the one step that could not be seen failing.
            .onFailure { Log.w(WatchdogTerminal.TAG, "sampler ($backend): ${it.message}") }
    }

    /**
     * The numbers, with no UI around them.
     *
     * The app carries its own interface and asks the machine only for what
     * changes, so this is the whole of what crosses the wire on a refresh —
     * `my-watchdog-tui snapshot`, one envelope, no page. It is also why a
     * failure here is survivable in a way a failed `open` was not: the
     * dashboard is already on screen, and a refresh that does not land leaves
     * the last one there with a stale marker rather than an error where the UI
     * should be.
     */
    fun snapshot(backend: String): Result<String> = runCatching {
        ensureTools(backend).getOrThrow()
        ensureDaemon(backend)
        exec(backend, "'$REMOTE_DIR/$BIN_PANEL' snapshot").getOrThrow()
    }

    /** Start the panel and hand back the channel to drive it. */
    fun open(backend: String, cols: Int, rows: Int): Result<Panel> = runCatching {
        ensureTools(backend).getOrThrow()
        ensureDaemon(backend)
        val ch = connect(backend).openChannel("exec") as ChannelExec
        ch.setCommand(posix("'$REMOTE_DIR/$BIN_PANEL' tui --serve $cols $rows"))
        // stdin has to be a pipe we own: this is the half that carries the
        // keystrokes, and jsch defaults it to nothing.
        ch.setInputStream(null, true)
        ch.setErrStream(System.err)
        val s = Panel(ch)
        ch.connect(CONNECT_MS)
        s
    }

    fun close() {
        session?.disconnect()
        session = null
        activeBackend = null
    }

    companion object {
        /**
         * The declared public half, mirrored from cloud-u-linux's
         * da__my-konsole/termux/authorized_keys. Committed in both places on
         * purpose: a public key is not a secret, and an app that can name the
         * key it offers can explain a refusal instead of just failing.
         */
        const val PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICP/TWd0q7KEm29dOrPMX5sEn/8THgsrdHJ1NfPiKElK cloud-constellation@android"

        /**
         * Where this app's own binaries live in the terminal env. Under the
         * env's cache rather than its PATH: they belong to the app, are
         * replaced when the app is, and nothing else should find them by
         * accident.
         */
        const val REMOTE_DIR = ".cache/cloud-watchdog/bin"
        const val BIN_DAEMON = "my-watchdog"
        const val BIN_PANEL = "my-watchdog-tui"

        /** The same two inside the APK, named so Android will exec them. */
        const val LIB_DAEMON = WatchdogLocal.DAEMON
        const val LIB_PANEL = WatchdogLocal.BIN

        /** Must match monitor::FRAME_END on the other side. */
        const val FRAME_END = "@@WATCHDOG-FRAME-END@@"

        /** Loopback: a connection that has not landed in two seconds is not slow, it is absent. */
        const val CONNECT_MS = 2_000
    }
}
