package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/**
 * Self-contained KDE-Connect client. Drives the documented LAN protocol with
 * our OWN code (no org.kde app, no GPL source):
 *
 *   dial peer:1716  →  write our identity (plaintext)  →  TLS handshake as the
 *   SERVER (we dialed, so per the protocol we are the SSL server; mutual-TLS
 *   captures the peer's cert)  →  re-exchange identity encrypted (protocol v8)
 *   →  pin-check known devices  →  pump packets through [KdePluginRegistry].
 *
 * Pairing = remembering the peer's certificate ([KdeTrustStore]); reconnects
 * verify the pinned cert. A single object owns all live links so the page and
 * (future) background service share one view.
 */
object KdeConnectManager : KdeLink.Listener {

    private const val TAG = "KdeConnect/Manager"
    const val DEFAULT_PORT = 1716
    /** tcpPort we advertise for inbound reconnection. Until the background
     *  server lands we don't accept inbound, but the active (dial-out) link
     *  is fully functional; advertising the canonical port is harmless. */
    private const val ADVERTISED_TCP_PORT = DEFAULT_PORT

    enum class State { CONNECTING, HANDSHAKING, NEEDS_PAIRING, PAIRED, DISCONNECTED, ERROR }

    interface Listener { fun onState(deviceId: String, state: State, detail: String) }

    @Volatile var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context
    private val links = ConcurrentHashMap<String, KdeLink>()
    private val pairRequested = ConcurrentHashMap.newKeySet<String>()
    private val trust by lazy { KdeTrustStore(app) }

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        KdeNotifications.ensureChannel(app)
    }

    fun isPaired(deviceId: String): Boolean = trust.isPaired(deviceId)
    fun pairedDeviceIds(): Set<String> = trust.pairedDeviceIds()
    fun isConnected(deviceId: String): Boolean = links[deviceId]?.isOpen == true
    fun ownDeviceId(): String = KdeIdentity.deviceId(app)

    /** Connect to [host]:[port], handshake, and register the link. Returns the
     *  discovered peer deviceId on success. Blocking I/O — call off the main
     *  thread (it suspends on IO). */
    suspend fun connect(host: String, port: Int = DEFAULT_PORT): Result<String> =
        withContext(Dispatchers.IO) {
            emit(host, State.CONNECTING, "dialing $host:$port")
            runCatching {
                val myIdentity = KdeIdentity.packet(
                    app, ADVERTISED_TCP_PORT,
                    KdePluginRegistry.incomingCapabilities,
                    KdePluginRegistry.outgoingCapabilities,
                )
                val plain = Socket()
                plain.connect(InetSocketAddress(host, port), 6000)
                // We are the TCP client → per protocol we WRITE our identity in
                // plaintext first; the peer (TCP server) reads it.
                plain.getOutputStream().apply {
                    write(myIdentity.serialize().toByteArray(Charsets.UTF_8)); flush()
                }

                emit(host, State.HANDSHAKING, "TLS handshake")
                val ssl = KdeCrypto.sslContext(app).socketFactory
                    .createSocket(plain, host, port, true) as SSLSocket
                ssl.useClientMode = false        // we dialed → we are the TLS SERVER
                ssl.needClientAuth = true        // mutual TLS — require + capture peer cert
                ssl.startHandshake()
                val peerCert: Certificate = ssl.session.peerCertificates.firstOrNull()
                    ?: error("peer presented no certificate")

                // Protocol v8: re-exchange identity over the encrypted channel.
                // Write ours, then read exactly one line (no read-ahead) so the
                // link's own reader starts clean right after it.
                ssl.outputStream.apply {
                    write(myIdentity.serialize().toByteArray(Charsets.UTF_8)); flush()
                }
                val secureLine = readLine(ssl.inputStream) ?: error("no secure identity")
                val peerIdentity = NetworkPacket.parse(secureLine)
                    ?: error("malformed secure identity")
                val peerDeviceId = peerIdentity.getString("deviceId")
                require(peerDeviceId.isNotBlank()) { "peer sent no deviceId" }

                // Pin known devices — a different cert for a paired id = impostor.
                if (trust.isPaired(peerDeviceId) && !trust.matchesPinned(peerDeviceId, peerCert)) {
                    ssl.close()
                    error("certificate mismatch for $peerDeviceId — refusing")
                }

                val link = KdeLink(
                    socket = ssl,
                    peerDeviceId = peerDeviceId,
                    peerName = peerIdentity.getString("deviceName", peerDeviceId),
                    peerIncoming = caps(peerIdentity, "incomingCapabilities"),
                    peerOutgoing = caps(peerIdentity, "outgoingCapabilities"),
                    peerCertificate = peerCert,
                    listener = this@KdeConnectManager,
                )
                links.put(peerDeviceId, link)?.close()
                link.start()
                emit(peerDeviceId,
                    if (trust.isPaired(peerDeviceId)) State.PAIRED else State.NEEDS_PAIRING,
                    link.peerName)
                peerDeviceId
            }.onFailure {
                Log.w(TAG, "connect($host) failed: ${it.message}")
                emit(host, State.ERROR, it.message ?: "connect failed")
            }
        }

    /** Ask a connected peer to pair (it shows an accept dialog; on accept it
     *  replies with pair=true and we store its cert). */
    fun requestPair(deviceId: String): Boolean {
        val link = links[deviceId] ?: return false
        pairRequested += deviceId
        return link.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) { put("pair", true) })
    }

    fun unpair(deviceId: String) {
        links[deviceId]?.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) { put("pair", false) })
        trust.untrust(deviceId)
        pairRequested -= deviceId
        emit(deviceId, State.DISCONNECTED, "unpaired")
    }

    fun sendPing(deviceId: String, message: String? = null): Boolean =
        links[deviceId]?.send(PingPlugin.build(message)) ?: false

    fun disconnect(deviceId: String) { links.remove(deviceId)?.close() }

    // ── KdeLink.Listener ─────────────────────────────────────────────────
    override fun onPacket(link: KdeLink, packet: NetworkPacket) {
        when (packet.type) {
            NetworkPacket.TYPE_PAIR -> handlePair(link, packet)
            else -> if (trust.isPaired(link.peerDeviceId)) {
                KdePluginRegistry.dispatch(app, link, packet)
            } else {
                Log.i(TAG, "ignoring ${packet.type} from unpaired ${link.peerDeviceId}")
            }
        }
    }

    override fun onDisconnect(link: KdeLink) {
        links.remove(link.peerDeviceId, link)
        emit(link.peerDeviceId, State.DISCONNECTED, "link closed")
    }

    private fun handlePair(link: KdeLink, packet: NetworkPacket) {
        val pair = packet.getBoolean("pair")
        if (pair) {
            // Accepted (our request) or an inbound request from our own device:
            // store the peer cert. If we didn't initiate, confirm back.
            trust.trust(link.peerDeviceId, link.peerCertificate)
            if (link.peerDeviceId !in pairRequested) {
                link.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) { put("pair", true) })
            }
            pairRequested -= link.peerDeviceId
            emit(link.peerDeviceId, State.PAIRED, link.peerName)
        } else {
            trust.untrust(link.peerDeviceId)
            pairRequested -= link.peerDeviceId
            emit(link.peerDeviceId, State.DISCONNECTED, "unpaired by ${link.peerName}")
        }
    }

    private fun emit(id: String, state: State, detail: String) =
        main.post { listener?.onState(id, state, detail) }

    private fun caps(p: NetworkPacket, key: String): Set<String> {
        val arr = p.body.optJSONArray(key) ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    /** Read one '\n'-terminated UTF-8 line WITHOUT buffering past it, so the
     *  link's reader can take over the stream cleanly afterwards. */
    private fun readLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString()
            if (b != '\r'.code) buf.append(b.toChar())
        }
    }
}
