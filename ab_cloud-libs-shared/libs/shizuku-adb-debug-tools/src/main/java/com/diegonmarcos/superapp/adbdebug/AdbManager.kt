package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
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
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Our [AbsAdbConnectionManager] — the embedded ADB client that pairs with
 * the phone's OWN Wireless-Debugging adbd on 127.0.0.1 and opens shell
 * streams as uid 2000. This is the self-contained "Shizuku": no third-party
 * app, no PC. (Same mechanism the open-source app LADB uses.)
 *
 * libadb requires us to supply a stable device keypair + self-signed
 * certificate — adb's pairing/TLS identity. We generate RSA-2048 + a
 * self-signed X.509 once (BouncyCastle, identical recipe to KdeCrypto),
 * persist to an app-private PKCS12, and reuse it so a paired adbd keeps
 * trusting us across reconnects.
 */
class AdbManager private constructor(ctx: Context) : AbsAdbConnectionManager() {

    private val keyStore: KeyStore = loadOrCreate(ctx.applicationContext)

    init {
        // adb's feature negotiation keys off the client API level.
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey =
        keyStore.getKey(ALIAS, PASSWORD) as PrivateKey

    override fun getCertificate(): Certificate =
        keyStore.getCertificate(ALIAS)

    override fun getDeviceName(): String = "CloudSuperApp"

    companion object {
        private const val KEYSTORE_FILE = "adbdebug_identity.p12"
        private const val ALIAS = "identity"
        // Guards only the on-disk PKCS12 (already in app-private storage);
        // not a network/credential secret — a fixed local value is fine.
        private val PASSWORD = "adbdebug-local".toCharArray()
        private val bcProvider by lazy { BouncyCastleProvider() }

        @Volatile private var instance: AdbManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(ctx: Context): AdbManager =
            instance ?: AdbManager(ctx).also { instance = it }

        private fun loadOrCreate(ctx: Context): KeyStore {
            val file = File(ctx.filesDir, KEYSTORE_FILE)
            val ks = KeyStore.getInstance("PKCS12")
            if (file.exists()) {
                runCatching { file.inputStream().use { ks.load(it, PASSWORD) } }
                    .onFailure { file.delete() }
            }
            if (!file.exists() || !ks.containsAlias(ALIAS)) {
                ks.load(null, null)
                val kp = KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(2048) }.generateKeyPair()
                val cert = selfSign(kp)
                ks.setKeyEntry(ALIAS, kp.private, PASSWORD, arrayOf<Certificate>(cert))
                file.outputStream().use { ks.store(it, PASSWORD) }
            }
            return ks
        }

        private fun selfSign(kp: KeyPair): X509Certificate {
            val subject = X500Name("CN=CloudSuperApp, OU=adbdebug, O=diegonmarcos")
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 60 * 60 * 1000)
            val notAfter = Date(now + 20L * 365 * 24 * 60 * 60 * 1000)
            val builder = JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now), notBefore, notAfter, subject, kp.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(bcProvider).build(kp.private)
            return JcaX509CertificateConverter()
                .setProvider(bcProvider)
                .getCertificate(builder.build(signer))
        }
    }
}
