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
    private val writeLock = Any()
    private var reader: Thread? = null

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
            if (!closed) listener.onDisconnect(this)
            close()
        }
    }

    /** Write one packet. Returns false if the link is down. */
    fun send(packet: NetworkPacket): Boolean {
        if (closed) return false
        return try {
            synchronized(writeLock) {
                out.write(packet.serialize().toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send failed to $peerDeviceId: ${t.message}")
            close()
            false
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    val isOpen: Boolean get() = !closed

    companion object { private const val TAG = "KdeConnect/Link" }
}
