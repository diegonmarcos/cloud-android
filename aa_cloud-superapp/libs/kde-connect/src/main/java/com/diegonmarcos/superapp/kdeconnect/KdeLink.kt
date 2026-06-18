package com.diegonmarcos.superapp.kdeconnect

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.cert.Certificate
import javax.net.ssl.SSLSocket

/**
 * One live, TLS-secured link to a peer. Wraps an already-handshaked
 * [SSLSocket] and pumps newline-delimited [NetworkPacket]s: a background
 * read loop hands inbound packets to [listener]; [send] writes outbound ones
 * under a lock. The link owns nothing about pairing or plugins — that's the
 * manager's job; this is purely the framed transport.
 */
class KdeLink(
    private val socket: SSLSocket,
    val peerDeviceId: String,
    val peerName: String,
    val peerIncoming: Set<String>,
    val peerOutgoing: Set<String>,
    /** The peer's TLS cert if we captured it (null when we don't request the
     *  client cert — pairing still works; the desktop pins OUR cert). */
    val peerCertificate: Certificate?,
    private val listener: Listener,
) {
    interface Listener {
        fun onPacket(link: KdeLink, packet: NetworkPacket)
        fun onDisconnect(link: KdeLink)
    }

    @Volatile private var closed = false
    private val out: OutputStream = socket.outputStream
    private var reader: Thread? = null
    /** Dedicated single writer thread. ALL socket writes go here — never on the
     *  caller's thread: the UI thread triggers sends (ping, remote-control
     *  cards) and a socket write on the main thread throws
     *  NetworkOnMainThreadException, which we'd mistake for a dead link and
     *  close a perfectly healthy connection. One thread also serialises writes. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "kde-link-write-$peerDeviceId").apply { isDaemon = true }
    }

    /** The peer's remote IP on this TLS socket — the address a payload
     *  side-channel connects back to for incoming file transfers. */
    val peerAddress: java.net.InetAddress? get() = runCatching { socket.inetAddress }.getOrNull()

    fun start() {
        reader = Thread({ readLoop() }, "kde-link-$peerDeviceId").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        try {
            val br = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            while (!closed) {
                val line = br.readLine() ?: break
                if (line.isBlank()) continue
                val packet = NetworkPacket.parse(line) ?: continue
                listener.onPacket(this, packet)
            }
        } catch (t: Throwable) {
            if (!closed) Log.i(TAG, "read loop ended for $peerDeviceId: ${t.message}")
        } finally {
            close()   // close() notifies the listener exactly once
        }
    }

    /** Enqueue a packet write on the link's writer thread (never blocks/throws
     *  on the caller). Returns false only if the link is already down. The
     *  serialize() is cheap JSON (no I/O), safe on the caller thread; the actual
     *  socket write + a genuine failure (→ close) happen on the writer. */
    fun send(packet: NetworkPacket): Boolean {
        if (closed) return false
        val bytes = packet.serialize().toByteArray(Charsets.UTF_8)
        return try {
            writer.execute {
                try {
                    out.write(bytes); out.flush()
                } catch (t: Throwable) {
                    Log.w(TAG, "send failed to $peerDeviceId: ${t.message}")
                    close()
                }
            }
            true
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            false   // writer already shut down (link closing)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { writer.shutdownNow() }
        runCatching { socket.close() }
        runCatching { listener.onDisconnect(this) }   // single source of disconnect notification
    }

    val isOpen: Boolean get() = !closed

    companion object { private const val TAG = "KdeConnect/Link" }
}
