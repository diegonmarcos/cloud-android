package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Our device-identity keypair + self-signed certificate, and the [SSLContext]
 * for KDE-Connect's mutual TLS. Clean-room: this is the standard JCA/Bouncy
 * Castle recipe for a self-signed cert, not KDE's code.
 *
 * Trust model (identical in SHAPE to KDE Connect, implemented independently):
 * the TLS layer accepts ANY peer cert — trust is an APPLICATION decision made
 * after the handshake. [KdeLink] captures the peer cert, and:
 *   • unpaired device → only a pair request may proceed (no data trust yet);
 *   • paired device   → the presented cert MUST equal the pinned one
 *     ([KdeTrustStore.matchesPinned]) or the link is dropped as an impostor.
 * So the permissive TrustManager here is safe; the gate is at the app layer.
 */
object KdeCrypto {

    private const val KEYSTORE_FILE = "kdeconnect_identity.p12"
    private const val ALIAS = "identity"
    // App-private file in filesDir; the password only guards the on-disk
    // PKCS12 blob (which is already in app-private storage), so a fixed
    // local secret is acceptable — it is not a network/credential secret.
    private val PASSWORD = "kdeconnect-local".toCharArray()
    private val bcProvider by lazy { BouncyCastleProvider() }

    @Volatile private var cachedKeyStore: KeyStore? = null

    /** Load-or-generate our identity keystore (keypair + self-signed cert,
     *  CN = our deviceId). Persisted once; the cert CN is bound to the
     *  deviceId so peers can keep trusting us across reconnects. */
    @Synchronized
    fun keyStore(ctx: Context): KeyStore {
        cachedKeyStore?.let { return it }
        val file = File(ctx.filesDir, KEYSTORE_FILE)
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            runCatching { file.inputStream().use { ks.load(it, PASSWORD) } }
                .onFailure { file.delete() }
        }
        if (!file.exists() || !ks.containsAlias(ALIAS)) {
            ks.load(null, null)
            val deviceId = KdeIdentity.deviceId(ctx)
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = selfSign(kp, deviceId)
            ks.setKeyEntry(ALIAS, kp.private, PASSWORD,
                arrayOf<java.security.cert.Certificate>(cert))
            file.outputStream().use { ks.store(it, PASSWORD) }
        }
        cachedKeyStore = ks
        return ks
    }

    /** Our own certificate (sent to peers; its DER is our pinned identity). */
    fun ownCertificate(ctx: Context): X509Certificate =
        keyStore(ctx).getCertificate(ALIAS) as X509Certificate

    private fun selfSign(kp: KeyPair, deviceId: String): X509Certificate {
        // CN = deviceId is what peers key their trust on. O/OU are cosmetic.
        val subject = X500Name("CN=$deviceId, OU=CloudSuperApp, O=diegonmarcos")
        val now = System.currentTimeMillis()
        // Valid from a day ago (clock-skew tolerance) to +20 years; this is a
        // device identity, not a web cert — it should effectively never expire.
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 20L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger.valueOf(now)
        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider).build(kp.private)
        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(builder.build(signer))
    }

    /** SSLContext that presents our cert (KeyManager) and accepts any peer
     *  cert at the TLS layer (app-layer trust — see class doc). Used for both
     *  client- and server-mode sockets; the caller sets the mode + client-auth. */
    fun sslContext(ctx: Context): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(ctx), PASSWORD)
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, trustAll, SecureRandom())
        }
    }
}
