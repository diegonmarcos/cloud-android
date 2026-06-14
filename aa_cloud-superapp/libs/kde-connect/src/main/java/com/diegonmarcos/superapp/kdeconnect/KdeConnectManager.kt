package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/**
 * Self-contained KDE-Connect client. Drives the documented LAN protocol with
 * our OWN code (no org.kde app, no GPL source). Works BOTH directions:
 *
 *  • OUTBOUND (we dial peer:1716): the socket is BOUND to the wg0 (VPN)
 *    transport so it egresses the tunnel with the wg0 source IP the peer can
 *    route back to — without this the dial leaks onto the default route (and,
 *    when home Wi-Fi also uses 10.0.0.0/24, hits a LAN host that RSTs →
 *    "connection refused"). We write our identity, then handshake as the TLS
 *    SERVER (protocol rule: the TCP dialer is the TLS server).
 *  • INBOUND (peer dials us on 1716-1764): a ServerSocket accepts, reads the
 *    peer's identity, and handshakes as the TLS CLIENT. This is what lets a
 *    pair request STARTED on the Surface actually reach us.
 *
 * After TLS both sides re-exchange identity encrypted (v8); known peers are
 * cert-pinned. Pairing = remembering the peer's certificate.
 */
object KdeConnectManager : KdeLink.Listener {

    private const val TAG = "KdeConnect/Manager"
    const val MIN_PORT = 1716
    const val MAX_PORT = 1764

    enum class State { CONNECTING, HANDSHAKING, NEEDS_PAIRING, PAIRED, DISCONNECTED, ERROR }
    interface Listener { fun onState(deviceId: String, state: State, detail: String) }

    @Volatile var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context
    private val links = ConcurrentHashMap<String, KdeLink>()
    private val pairRequested = ConcurrentHashMap.newKeySet<String>()
    private val trust by lazy { KdeTrustStore(app) }

    @Volatile private var server: ServerSocket? = null
    /** The port our inbound server actually bound (advertised in our identity
     *  so peers know where to reconnect). Defaults to MIN_PORT until bound. */
    @Volatile private var serverPort: Int = MIN_PORT

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        KdeNotifications.ensureChannel(app)
    }

    fun isPaired(deviceId: String): Boolean = trust.isPaired(deviceId)
    fun pairedDeviceIds(): Set<String> = trust.pairedDeviceIds()
    fun isConnected(deviceId: String): Boolean = links[deviceId]?.isOpen == true
    fun ownDeviceId(): String = KdeIdentity.deviceId(app)
    fun listenPort(): Int = serverPort

    /** Start the inbound link server (idempotent). Lets the Surface INITIATE —
     *  e.g. a pair request started over there reaches us here. Runs while the
     *  app process lives; a foreground service for always-on is the next phase. */
    @Synchronized
    fun ensureServer() {
        if (server != null) return
        val srv = openServerSocket() ?: return
        server = srv
        serverPort = srv.localPort
        Thread({
            while (!srv.isClosed) {
                val sock = try { srv.accept() } catch (t: Throwable) { break }
                Thread({ handleInbound(sock) }, "kde-inbound").apply { isDaemon = true }.start()
            }
        }, "kde-server").apply { isDaemon = true; start() }
        Log.i(TAG, "inbound server listening on :$serverPort")
    }

    private fun openServerSocket(): ServerSocket? {
        for (p in MIN_PORT..MAX_PORT) runCatching { return ServerSocket(p) }
        Log.w(TAG, "no free port in $MIN_PORT-$MAX_PORT for inbound server")
        return null
    }

    /** Dial [host]:[port] over wg0 and handshake. Returns the peer deviceId. */
    suspend fun connect(host: String, port: Int = MIN_PORT): Result<String> =
        withContext(Dispatchers.IO) {
            emit(host, State.CONNECTING, "dialing $host:$port over wg0")
            runCatching {
                val plain = Socket()
                // CRITICAL: pin egress to the wg0 (VPN) transport. Otherwise the
                // dial leaks to the default route → wrong host → connection refused.
                vpnNetwork()?.bindSocket(plain)
                plain.connect(InetSocketAddress(host, port), 6000)
                // TCP dialer writes its identity in plaintext first.
                plain.getOutputStream().apply {
                    write(myIdentity().serialize().toByteArray(Charsets.UTF_8)); flush()
                }
                emit(host, State.HANDSHAKING, "TLS handshake")
                finishHandshake(plain, host, port, weDialed = true)
            }.onFailure {
                Log.w(TAG, "connect($host) failed: ${it.message}")
                emit(host, State.ERROR, it.message ?: "connect failed")
            }
        }

    /** Inbound connection accepted by [ensureServer]. The peer is the TCP
     *  dialer, so the peer wrote its identity first and WE are the TLS client. */
    private fun handleInbound(plain: Socket) {
        runCatching {
            // Read (and discard) the peer's plaintext identity — the trusted copy
            // comes from the encrypted v8 exchange inside finishHandshake.
            readLine(plain.getInputStream())
            val host = plain.inetAddress?.hostAddress ?: "?"
            finishHandshake(plain, host, plain.port, weDialed = false)
        }.onFailure { Log.i(TAG, "inbound handshake failed: ${it.message}") }
    }

    /** Shared TLS bring-up for both directions. [weDialed] decides the TLS role
     *  (dialer = server, accepter = client) per the protocol. */
    private fun finishHandshake(plain: Socket, host: String, port: Int, weDialed: Boolean): String {
        val ssl = KdeCrypto.sslContext(app).socketFactory
            .createSocket(plain, host, port, true) as SSLSocket
        ssl.useClientMode = !weDialed         // dialer → TLS server; accepter → TLS client
        if (weDialed) ssl.needClientAuth = true   // (server only) require + capture peer cert
        ssl.startHandshake()
        val peerCert: Certificate = ssl.session.peerCertificates.firstOrNull()
            ?: error("peer presented no certificate")

        // Protocol v8: re-exchange identity over the encrypted channel. Write
        // ours, read exactly one line (no read-ahead) so the link reader takes
        // over cleanly. Both ends write-then-read, so no deadlock.
        ssl.outputStream.apply {
            write(myIdentity().serialize().toByteArray(Charsets.UTF_8)); flush()
        }
        val secureLine = readLine(ssl.inputStream) ?: error("no secure identity")
        val peerIdentity = NetworkPacket.parse(secureLine) ?: error("malformed secure identity")
        val peerDeviceId = peerIdentity.getString("deviceId")
        require(peerDeviceId.isNotBlank()) { "peer sent no deviceId" }

        if (trust.isPaired(peerDeviceId) && !trust.matchesPinned(peerDeviceId, peerCert)) {
            ssl.close(); error("certificate mismatch for $peerDeviceId — refusing")
        }

        val link = KdeLink(
            socket = ssl,
            peerDeviceId = peerDeviceId,
            peerName = peerIdentity.getString("deviceName", peerDeviceId),
            peerIncoming = caps(peerIdentity, "incomingCapabilities"),
            peerOutgoing = caps(peerIdentity, "outgoingCapabilities"),
            peerCertificate = peerCert,
            listener = this,
        )
        links.put(peerDeviceId, link)?.close()
        link.start()
        emit(peerDeviceId,
            if (trust.isPaired(peerDeviceId)) State.PAIRED else State.NEEDS_PAIRING,
            link.peerName)
        return peerDeviceId
    }

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
        if (packet.getBoolean("pair")) {
            // Accepted (our request) OR an inbound request from our own device.
            trust.trust(link.peerDeviceId, link.peerCertificate)
            if (link.peerDeviceId !in pairRequested) {
                // Peer initiated — confirm back so both ends trust each other.
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

    // ── helpers ──────────────────────────────────────────────────────────
    private fun myIdentity(): NetworkPacket = KdeIdentity.packet(
        app, serverPort,
        KdePluginRegistry.incomingCapabilities, KdePluginRegistry.outgoingCapabilities,
    )

    /** The active VPN-transport network (native WireGuard), or null. */
    private fun vpnNetwork(): Network? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun emit(id: String, state: State, detail: String) =
        main.post { listener?.onState(id, state, detail) }

    private fun caps(p: NetworkPacket, key: String): Set<String> {
        val arr = p.body.optJSONArray(key) ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

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
