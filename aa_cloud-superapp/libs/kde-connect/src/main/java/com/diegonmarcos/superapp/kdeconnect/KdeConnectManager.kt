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
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/**
 * Self-contained KDE-Connect client — OUR code, no org.kde app, no GPL source.
 * The handshake + pairing were proven by live protocol probing against the
 * real Surface kdeconnectd (Plasma 6, protocol v8, TLSv1.3):
 *
 *   1. Dial <peer>:1716 over wg0 (socket BOUND to wg0, else it leaks to the
 *      default route → wrong host → "connection refused").
 *   2. Write our identity in plaintext (the dialer sends identity first).
 *   3. TLS handshake as the SERVER (peer sends ClientHello → peer is the TLS
 *      client); request its cert (mutual TLS). deviceId = the peer cert CN —
 *      and the cert CN MUST equal the deviceId we advertise, or the peer drops
 *      us. The peer does NOT send an identity packet over TLS.
 *   4. Write our identity over the encrypted channel (prompts the peer), then
 *      pump packets.
 *   5. Pairing (v8): the pair REQUEST carries {pair:true, timestamp:<sec>};
 *      the ACCEPT reply is {pair:true} (no timestamp). A request without the
 *      timestamp is rejected. Pairing = remembering the peer cert (pinned).
 *
 * We do NOT run an inbound server: the desktop never dials back over wg
 * (proven), and binding 1716 would steal it from the official KDE Connect app.
 * The active outbound link is fully bidirectional, so pairing works either way
 * over it once connected.
 */
object KdeConnectManager : KdeLink.Listener {

    private const val TAG = "KdeConnect/Manager"
    const val DEFAULT_PORT = 1716

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

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        KdeNotifications.ensureChannel(app)
    }

    fun isPaired(deviceId: String): Boolean = trust.isPaired(deviceId)
    fun pairedDeviceIds(): Set<String> = trust.pairedDeviceIds()
    fun isConnected(deviceId: String): Boolean = links[deviceId]?.isOpen == true
    fun ownDeviceId(): String = KdeIdentity.deviceId(app)

    /** Dial [host]:[port] over wg0 and bring up the link. Returns the peer
     *  deviceId (its cert CN). Blocking I/O — runs off the main thread. */
    suspend fun connect(host: String, port: Int = DEFAULT_PORT): Result<String> =
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
                // REQUEST the peer cert but don't REQUIRE it: needClientAuth=true
                // can abort the handshake (the working probe used no client-auth
                // requirement); wantClientAuth still hands us the cert KDE sends.
                ssl.wantClientAuth = true
                ssl.startHandshake()
                register(ssl, host)
            }.onFailure {
                Log.w(TAG, "connect($host) failed: ${it.message}")
                emit("", host, State.ERROR, it.message ?: "connect failed")
            }
        }

    /** Post-handshake registration. deviceId = peer cert CN (the peer sends no
     *  identity packet over TLS). Writes our identity over TLS (prompts the
     *  peer), then starts the read loop so kdeconnect.pair is handled. */
    private fun register(ssl: SSLSocket, host: String): String {
        val peerCert: Certificate = runCatching { ssl.session.peerCertificates.firstOrNull() }
            .getOrNull() ?: error("peer presented no certificate")
        val deviceId = (peerCert as? X509Certificate)?.let { cnOf(it) }
            ?: error("peer cert has no CN")

        // Write our identity over the encrypted channel (this is what prompts
        // the peer to engage), then bring up the link.
        ssl.outputStream.apply {
            write(myIdentity().serialize().toByteArray(Charsets.UTF_8)); flush()
        }
        val alreadyPaired = trust.isPaired(deviceId)
        if (alreadyPaired && !trust.matchesPinned(deviceId, peerCert)) {
            ssl.close(); error("certificate mismatch for $deviceId — refusing")
        }

        val name = labelFor(host) ?: deviceId
        val link = KdeLink(
            socket = ssl, peerDeviceId = deviceId, peerName = name,
            peerIncoming = emptySet(), peerOutgoing = emptySet(),
            peerCertificate = peerCert, listener = this,
        )
        links.put(deviceId, link)?.close()
        link.start()
        emit(deviceId, host, if (alreadyPaired) State.PAIRED else State.NEEDS_PAIRING, name)

        // Not paired yet → request pairing immediately (the proven probe flow:
        // connect → identity → pair{true,timestamp}). The desktop shows its
        // accept dialog; accepting there replies {pair:true} and we pin it.
        if (!alreadyPaired) {
            emit(deviceId, host, State.NEEDS_PAIRING, "$name — pair request sent, accept on the device")
            requestPair(deviceId)
        }
        return deviceId
    }

    /** Request pairing (v8): {pair:true, timestamp:<unix seconds>}. The peer
     *  rejects a request missing the timestamp, or if clocks differ >30 min. */
    fun requestPair(deviceId: String): Boolean {
        val link = links[deviceId] ?: return false
        pairRequested += deviceId
        return link.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) {
            put("pair", true)
            put("timestamp", System.currentTimeMillis() / 1000L)
        })
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
                // Peer initiated the request → ACCEPT reply is {pair:true} (no
                // timestamp). We don't re-validate the timestamp (own mesh).
                link.send(NetworkPacket.of(NetworkPacket.TYPE_PAIR) { put("pair", true) })
            }
            pairRequested -= link.peerDeviceId
            emit(link.peerDeviceId, null, State.PAIRED, link.peerName)
        } else {
            trust.untrust(link.peerDeviceId); pairRequested -= link.peerDeviceId
            emit(link.peerDeviceId, null, State.DISCONNECTED, "unpaired/rejected by ${link.peerName}")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────
    // We advertise the canonical port but run no server (see class doc); the
    // peer never dials us back over wg, so this is informational only.
    private fun myIdentity(): NetworkPacket = KdeIdentity.packet(
        app, DEFAULT_PORT,
        KdePluginRegistry.incomingCapabilities, KdePluginRegistry.outgoingCapabilities,
    )

    /** CN of an X.509 subject — KDE binds the deviceId to the cert CN. Regex
     *  over the RFC2253 DN (javax.naming.ldap is absent on Android); KDE
     *  deviceIds contain no commas, so this is safe. */
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
}
