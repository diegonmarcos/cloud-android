package com.diegonmarcos.superapp.watchdog

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
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
 * WHY ChannelExec AND NOT A SHELL
 * This asks one question per refresh — "draw me a screen this wide" — and
 * wants the answer as a string. A pty would mean parsing a stream for a prompt
 * to know where the answer ended, which is how terminal automation usually
 * goes wrong. exec gives an exit status and a clean EOF.
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

    private val keyFile get() = File(ctx.filesDir, "watchdog_id_ecdsa")
    private val pubFile get() = File(ctx.filesDir, "watchdog_id_ecdsa.pub")

    /** The backends the build baked in — host, port and user are data. */
    private val targets: JSONObject by lazy {
        JSONObject(String(Base64.decode(BuildConfig.WATCHDOG_TARGETS_JSON_B64, Base64.DEFAULT)))
            .getJSONObject("backends")
    }

    fun backendKeys(): List<String> = targets.keys().asSequence().toList()

    fun label(key: String): String =
        targets.optJSONObject(key)?.optString("label") ?: key

    private fun ensureKey() {
        if (keyFile.isFile() && pubFile.isFile()) return
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        kp.writePrivateKey(keyFile.absolutePath)
        kp.writePublicKey(pubFile.absolutePath, "cloud-watchdog@android")
        kp.dispose()
        // The private key is inside the app sandbox already; tightening the
        // mode as well costs nothing and means a misconfigured backup or a
        // shared-storage bug cannot hand it out.
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
    }

    /** The one line to add to ~/.ssh/authorized_keys in the target env. */
    fun publicKeyLine(): String {
        ensureKey()
        return pubFile.readText().trim()
    }

    private fun connect(key: String): Session {
        session?.let { if (it.isConnected) return it }
        ensureKey()
        val t = targets.getJSONObject(key)
        jsch.addIdentity(keyFile.absolutePath)
        val s = jsch.getSession(t.getString("user"), t.getString("host"), t.getInt("port"))
        // The host is this device. There is no man in the middle on loopback to
        // be protected from, and a known_hosts prompt no one can answer would
        // simply mean the app never connects — the same choice cloud-ide makes.
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(CONNECT_MS)
        session = s
        return s
    }

    /**
     * Run one command and return its stdout.
     *
     * stderr is folded into the failure message rather than the result: a
     * screen with an error message rendered into it looks like a reading.
     */
    fun exec(backend: String, cmd: String): Result<String> = runCatching {
        val ch = connect(backend).openChannel("exec") as ChannelExec
        ch.setCommand(cmd)
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
    fun screen(backend: String, cols: Int, rows: Int): Result<String> =
        exec(backend, "${BuildConfig.WATCHDOG_CMD} $cols $rows")

    fun close() {
        session?.disconnect()
        session = null
    }

    private companion object {
        /** Loopback: a connection that has not landed in two seconds is not slow, it is absent. */
        const val CONNECT_MS = 2_000
    }
}
