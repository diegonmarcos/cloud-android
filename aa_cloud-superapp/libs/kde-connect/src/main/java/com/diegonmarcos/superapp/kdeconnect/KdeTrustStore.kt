package com.diegonmarcos.superapp.kdeconnect

import android.content.Context
import android.util.Base64
import java.security.cert.Certificate
import java.security.cert.CertificateFactory

/**
 * Persistent record of paired peers. Pairing in KDE Connect = remembering the
 * peer's TLS certificate; every subsequent connection is verified by pinning
 * that exact cert (so a different cert for a known deviceId is rejected as an
 * impostor). Stored as base64 DER in SharedPreferences, keyed by deviceId.
 */
class KdeTrustStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaired(deviceId: String): Boolean = sp.contains(key(deviceId))

    fun pairedDeviceIds(): Set<String> =
        sp.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()

    /** Store the peer cert on successful pairing. */
    fun trust(deviceId: String, cert: Certificate) {
        sp.edit().putString(key(deviceId),
            Base64.encodeToString(cert.encoded, Base64.NO_WRAP)).apply()
    }

    fun untrust(deviceId: String) { sp.edit().remove(key(deviceId)).apply() }

    /** The pinned cert for a paired device, or null if not paired. */
    fun storedCert(deviceId: String): Certificate? {
        val b64 = sp.getString(key(deviceId), null) ?: return null
        return runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(Base64.decode(b64, Base64.NO_WRAP).inputStream())
        }.getOrNull()
    }

    /** True iff [presented] equals the pinned cert for [deviceId]. */
    fun matchesPinned(deviceId: String, presented: Certificate): Boolean {
        val stored = storedCert(deviceId) ?: return false
        return stored.encoded.contentEquals(presented.encoded)
    }

    private fun key(deviceId: String) = PREFIX + deviceId

    companion object {
        private const val PREFS = "kdeconnect_trust"
        private const val PREFIX = "cert_"
    }
}
