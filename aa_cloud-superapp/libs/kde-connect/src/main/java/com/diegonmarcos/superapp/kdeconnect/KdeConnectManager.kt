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
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/**
 * Self-contained KDE-Connect client — OUR code, no org.kde app, no GPL source.
 *
 * The handshake is the REAL one, established by live protocol probing against
 * the Surface (kdeconnectd, Plasma 6.x, TLSv1.3):
 *   1. Dial <peer>:1716 over wg0 (socket BOUND to the wg0 transport, else the
 *      dial leaks to the default route → wrong host → "connection refused").
 *   2. Write our identity in plaintext (the TCP dialer sends identity first).
 *   3. TLS handshake as the SERVER (the peer sends ClientHello → it is the TLS
 *      client); request its certificate (mutual TLS).
 *   4. The peer's deviceId is the CN of its certificate — the peer does NOT
 *      send an identity packet over TLS (observed: it sends kdeconnect.pair).
 *   5. Write our identity over the encrypted channel (this is what prompts the
 *      peer to react), then pump packets — the peer's kdeconnect.pair drives
 *      pairing. Pairing = remembering the peer cert; reconnects are pinned.
 *
 * An inbound server also accepts peer-initiated connections (best-effort; the
 * desktop must be able to reach us back over wg0).
 */
object KdeConnectManager : KdeLink.Listener {

    private const val TAG = "KdeConnect/Manager"
    const val MIN_PORT = 1716
    const val MAX_PORT = 1764

    enum class State { CONNECTING, HANDSHAKING, NEEDS_PAIRING, PAIRED, DISCONNECTED, ERROR }

    /** [host] = the peer wg IP, so the UI can map an async callback to the
     *  declared device before a deviceId is known. */
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

    /** Dial [host]:[port] over wg0 and bring up the link. Returns the peer
     *  deviceId (its cert CN). Blocking I/O — runs off the main thread. */
    suspend fun connect(host: String, port: Int = MIN_PORT): Result<String> =
        withContext(Dispatchers.IO) {
            emit("", host, State.CONNECTING, "dialing $host:$port over wg0")
            runCatching {
                val plain = Socket()
                vpnNetwork()?.bindSocket(plain)   // egress over wg0 (source = our wg IP)
                plain.connect(InetSocketAddress(host, port), 6000)
                plain.getOutputStream().apply {
                    write(myIdentity().serialize().toByteArray(Charsets.UTF_8)); flush()
                }
                emit("", host, State.HANDSHAKING, "TLS handshake")
                val ssl = KdeCrypto.sslContext(app).socketFactory
                    .createSocket(plain, host, port, true) as SSLSocket
                ssl.useClientMode = false        // we dialed → we are the TLS server
                ssl.needClientAuth = true        // request the peer cert (its deviceId source)
                ssl.startHandshake()
                register(ssl, host, plainIdentity = null)
            }.onFailure {
                Log.w(TAG, "connect($host) failed: ${it.message}")
                emit("", host, State.ERROR, it.message ?: "connect failed")
            }
        }

    /** Inbound (peer-initiated) link. The peer is the TCP dialer (wrote its
     *  identity first) so WE are the TLS client. */
    fun ensureServer() {
        if (server != null) return
        synchronized(this) {
            if (server != null) return
            val srv = openServerSocket() ?: return
            server = srv; serverPort = srv.localPort
            Thread({
                while (!srv.isClosed) {
                    val s = try { srv.accept() } catch (t: Throwable) { break }
                    Thread({ handleInbound(s) }, "kde-inbound").apply { isDaemon = true }.start()
                }
            }, "kde-server").apply { isDaemon = true; start() }
            Log.i(TAG, "inbound server on :$serverPort")
        }
    }

    private fun openServerSocket(): ServerSocket? {
        for (p in MIN_PORT..MAX_PORT) runCatching { return ServerSocket(p) }
        return null
    }

    private fun handleInbound(plain: Socket) {
        val host = plain.inetAddress?.hostAddress ?: "?"
        runCatching {
            val line = readLine(plain.getInputStream()) ?: error("no identity from $host")
            val plainIdentity = NetworkPacket.parse(line)
            val ssl = KdeCrypto.sslContext(app).socketFactory
                .createSocket(plain, host, plain.port, true) as SSLSocket
            ssl.useClientMode = true             // peer dialed → peer is TLS server
            ssl.startHandshake()
            register(ssl, host, plainIdentity)
        }.onFailure {
            Log.i(TAG, "inbound $host failed: ${it.message}")
            runCatching { plain.close() }
        }
    }

    /** Common post-handshake registration. deviceId = peer cert CN (the peer
     *  doesn't send an identity packet over TLS); [plainIdentity] (inbound
     *  only) just enriches the display name. */
    private fun register(ssl: SSLSocket, host: String, plainIdentity: NetworkPacket?): String {
        val peerCert: Certificate = ssl.session.peerCertificates.firstOrNull()
            ?: error("peer presented no certificate")
        val deviceId = (peerCert as? X509Certificate)?.let { cnOf(it) }
            ?: plainIdentity?.getString("deviceId")?.takeIf { it.isNotBlank() }
            ?: error("peer cert has no CN and no identity deviceId")

        // Writing our identity over the encrypted channel is what prompts the
        // peer to proceed (observed: it then sends kdeconnect.pair).
        ssl.outputStream.apply {
            write(myIdentity().serialize().toByteArray(Charsets.UTF_8)); flush()
        }

        if (trust.isPaired(deviceId) && !trust.matchesPinned(deviceId, peerCert)) {
            ssl.close(); error("certificate mismatch for $deviceId — refusing")
        }

        val name = plainIdentity?.getString("deviceName")?.takeIf { it.isNotBlank() }
            ?: labelFor(host) ?: deviceId
        val link = KdeLink(
            socket = ssl, peerDeviceId = deviceId, peerName = name,
            peerIncoming = emptySet(), peerOutgoing = emptySet(),
            peerCertificate = peerCert, listener = this,
        )
        links.put(deviceId, link)?.close()
        link.start()
        emit(deviceId, host,
            if (trust.isPaired(deviceId)) State.PAIRED else State.NEEDS_PAIRING, name)
        return deviceId
    }

    fun requestPair(deviceId: String): Boolean {
        val link = links[deviceId] ?: return false
        pairRequested += deviceId
        return link.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) { put("pair", true) })
    }

    fun unpair(deviceId: String) {
        links[deviceId]?.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) { put("pair", false) })
        trust.untrust(deviceId); pairRequested -= deviceId
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
            } else Log.i(TAG, "ignoring ${packet.type} from unpaired ${link.peerDeviceId}")
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
            trust.untrust(link.peerDeviceId); pairRequested -= link.peerDeviceId
            emit(link.peerDeviceId, null, State.DISCONNECTED, "unpaired by ${link.peerName}")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private fun myIdentity(): NetworkPacket = KdeIdentity.packet(
        app, serverPort,
        KdePluginRegistry.incomingCapabilities, KdePluginRegistry.outgoingCapabilities,
    )

    /** CN of an X.509 subject — KDE binds the deviceId to the cert CN.
     *  Regex over the RFC2253 DN (javax.naming.ldap.LdapName is absent on
     *  Android); KDE deviceIds contain no commas, so this is safe. */
    private fun cnOf(cert: X509Certificate): String? =
        Regex("(?:^|,)\\s*CN=([^,]+)")
            .find(cert.subjectX500Principal.name)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun labelFor(host: String): String? =
        KdeConnectConfig.get().devices.firstOrNull { it.wgIp == host }?.label

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
