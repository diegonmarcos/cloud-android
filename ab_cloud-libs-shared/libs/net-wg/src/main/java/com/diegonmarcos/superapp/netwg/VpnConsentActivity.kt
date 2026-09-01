package com.diegonmarcos.superapp.netwg

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.wireguard.android.backend.GoBackend

/**
 * The one screen this library APK has, and it exists for a rule that cannot
 * be worked around: VpnService.prepare() grants consent to the package that
 * OWNS the service. The service is here now, so the app can no longer ask
 * on its own behalf - it launches this, and this asks.
 *
 * No UI of its own: it either finishes immediately (consent already held)
 * or shows the system dialog and finishes with its result. RESULT_OK means
 * the tunnel may be started.
 */
class VpnConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prepare = runCatching { GoBackend.VpnService.prepare(this) }.getOrNull()
        if (prepare == null) {
            setResult(RESULT_OK)
            finish()
        } else {
            startActivityForResult(prepare, REQ_CONSENT)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONSENT) {
            setResult(resultCode)
            finish()
        }
    }

    private companion object { const val REQ_CONSENT = 8021 }
}
