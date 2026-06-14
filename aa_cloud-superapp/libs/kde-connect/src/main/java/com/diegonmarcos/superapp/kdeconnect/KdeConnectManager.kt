package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/**
 * Self-contained KDE-Connect client — OUR code, no org.kde app, no GPL source.
 *
 * Uses the canonical, version-robust KDE handshake where WE are always the
 * TCP-accepter / TLS-client (the "receiver connects back" model):
 *
 *   Connect  →  send a UDP identity NUDGE to <peer>:1716 over wg0 (source =
 *               our wg0 IP, advertising our inbound tcpPort)  →  the peer dials
 *               BACK to us  →  our ServerSocket accepts  →  we read the peer's
 *               identity in PLAINTEXT (works for protocol v7 AND v8) →
 *               TLS handshake as the CLIENT (mutual TLS captures the peer
 *               cert)  →  for v8 only, re-exchange identity encrypted  →  pin
 *               known peers  →  plugins.
 *
 * The same accept path also serves pair requests STARTED on the desktop. The
 * earlier direct-TCP-dial path was v8-only (it never learned a v7 peer's
 * identity → "peer sent no deviceId"); the nudge model fixes that.
 *
 * Pairing = remembering the peer's certificate; reconnects are cert-pinned.
 */
object KdeConnectManager : KdeLink.Listener {

    private const val TAG = "KdeConnect/Manager"
    const val MIN_PORT = 1716
    const val MAX_PORT = 1764

    enum class State { CONNECTING, HANDSHAKING, NEEDS_PAIRING, PAIRED, DISCONNECTED, ERROR }

    /** [host] is the peer's wg IP (so the UI can map a callback back to the
     *  declared device even before a deviceId is known). */
    interface Listener { fun onState(deviceId: String, host: String?, state: State, detail: String) }

    @Volatile var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var app: Context
    private val links = ConcurrentHashMap<String, KdeLink>()
    private val pairRequested = ConcurrentHashMap.newKeySet<String>()
    private val trust by lazy { KdeTrustStore(app) }

    @Volatile private var server: ServerSocket? = null
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

    /** Start the inbound link server (idempotent) on the first free port in
     *  1716-1764 (1716 itself is often the installed official app). Both the
     *  nudge-callback and desktop-initiated pairing land here. */
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
        Log.w(TAG, "no free port in $MIN_PORT-$MAX_PORT")
        return null
    }

    /** Connect to a peer: ensure our server is up, then UDP-nudge it so it
     *  dials back. The actual link is reported asynchronously via [Listener]
     *  keyed on [host]. */
    fun nudge(host: String, port: Int = MIN_PORT) {
        ensureServer()
        emit("", host, State.CONNECTING, "nudging $host — waiting for it to connect back to :$serverPort")
        Thread({
            runCatching { sendUdpIdentity(host, port) }
                .onFailure { emit("", host, State.ERROR, "udp nudge failed: ${it.message}") }
        }, "kde-nudge").apply { isDaemon = true }.start()
    }

    /** A peer dialed us (nudge-callback or desktop-initiated). The peer is the
     *  TCP dialer, so it wrote its identity first and WE are the TLS client. */
    private fun handleInbound(plain: Socket) {
        val host = plain.inetAddress?.hostAddress ?: "?"
        runCatching {
            // Plaintext identity the dialer wrote — the ONLY identity for v7,
            // and the protocolVersion that decides whether v8 re-exchange runs.
            val plainLine = readLine(plain.getInputStream()) ?: error("no identity from $host")
            val plainIdentity = NetworkPacket.parse(plainLine) ?: error("malformed identity from $host")
            finishHandshake(plain, host, plain.port, plainIdentity)
        }.onFailure {
            Log.i(TAG, "inbound from $host failed: ${it.message}")
            emit("", host, State.ERROR, it.message ?: "handshake failed")
            runCatching { plain.close() }
        }
    }

    /** TLS bring-up as the CLIENT, then resolve the peer identity (v8 encrypted
     *  re-exchange, else the v7 plaintext one), pin-check, and register. */
    private fun finishHandshake(plain: Socket, host: String, port: Int, plainIdentity: NetworkPacket): String {
        emit("", host, State.HANDSHAKING, "TLS handshake with $host")
        val ssl = KdeCrypto.sslContext(app).socketFactory
            .createSocket(plain, host, port, true) as SSLSocket
        ssl.useClientMode = true               // peer dialed us → peer is TLS server, we are client
        ssl.startHandshake()
        val peerCert: Certificate = ssl.session.peerCertificates.firstOrNull()
            ?: error("peer presented no certificate")

        val peerProtocol = plainIdentity.getInt("protocolVersion", 7)
        val peerIdentity: NetworkPacket = if (peerProtocol >= 8) {
            // v8: re-exchange identity over the encrypted channel.
            ssl.outputStream.apply {
                write(myIdentity().serialize().toByteArray(Charsets.UTF_8)); flush()
            }
            val secureLine = readLine(ssl.inputStream) ?: error("no secure identity")
            NetworkPacket.parse(secureLine) ?: error("malformed secure identity")
        } else {
            // v7: the plaintext identity IS the identity (no re-exchange).
            plainIdentity
        }

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
        emit(peerDeviceId, host,
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
        emit(deviceId, null, State.DISCONNECTED, "unpaired")
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
        emit(link.peerDeviceId, null, State.DISCONNECTED, "link closed")
    }

    private fun handlePair(link: KdeLink, packet: NetworkPacket) {
        if (packet.getBoolean("pair")) {
            trust.trust(link.peerDeviceId, link.peerCertificate)
            if (link.peerDeviceId !in pairRequested) {
                link.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) { put("pair", true) })
            }
            pairRequested -= link.peerDeviceId
            emit(link.peerDeviceId, null, State.PAIRED, link.peerName)
        } else {
            trust.untrust(link.peerDeviceId)
            pairRequested -= link.peerDeviceId
            emit(link.peerDeviceId, null, State.DISCONNECTED, "unpaired by ${link.peerName}")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private fun myIdentity(): NetworkPacket = KdeIdentity.packet(
        app, serverPort,
        KdePluginRegistry.incomingCapabilities, KdePluginRegistry.outgoingCapabilities,
    )

    /** Unicast our identity to [host]:[port] over wg0 so the source IP is our
     *  wg0 address (the peer dials back to it on our advertised tcpPort). */
    private fun sendUdpIdentity(host: String, port: Int) {
        DatagramSocket().use { sock ->
            vpnNetwork()?.bindSocket(sock)
            val bytes = myIdentity().serialize().toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(host, port)))
        }
        Log.i(TAG, "sent UDP identity nudge to $host:$port (advertised tcpPort=$serverPort)")
    }

    private fun vpnNetwork(): Network? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun emit(id: String, host: String?, state: State, detail: String) =
        main.post { listener?.onState(id, host, state, detail) }

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
