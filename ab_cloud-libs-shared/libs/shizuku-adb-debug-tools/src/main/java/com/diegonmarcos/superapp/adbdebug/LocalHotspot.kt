package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Self-created WiFi for Wireless Debugging when there is no external
 * network to join: WifiManager.startLocalOnlyHotspot() spins up a
 * device-local access point (not internet-routed) and keeps the radio
 * ON + attached to a network, which is what adbd over WiFi (API 30+)
 * needs. The [android.net.wifi.LocalOnlyHotspotReservation] is held
 * here so the AP survives past the call that created it — releasing it
 * (or losing the process) tears the AP down.
 *
 * requires ACCESS_FINE_LOCATION (runtime) + CHANGE_WIFI_STATE (manifest),
 * or startLocalOnlyHotspot throws SecurityException.
 */
object LocalHotspot {

    sealed class Status {
        object Idle : Status()
        data class Active(val ssid: String, val passphrase: String) : Status()
        data class Failed(val reason: String) : Status()
    }

    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var status: Status = Status.Idle

    fun status(): Status = status

    /** Start the local-only hotspot. [onResult] fires on the main thread. */
    fun start(ctx: Context, onResult: (Status) -> Unit) {
        val app = ctx.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) { status = Status.Failed("No WifiManager"); onResult(status); return }
        runCatching {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    status = runCatching { describe(res) }
                        .getOrDefault(Status.Active("(unknown)", "(unknown)"))
                    onResult(status)
                }
                override fun onStopped() {
                    reservation = null
                    status = Status.Idle
                }
                override fun onFailed(reasonCode: Int) {
                    reservation = null
                    status = Status.Failed("startLocalOnlyHotspot failed, code=$reasonCode")
                    onResult(status)
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { e ->
            status = Status.Failed(e.message ?: e.javaClass.simpleName)
            onResult(status)
        }
    }

    /** Release the reservation, tearing the AP down. */
    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        status = Status.Idle
    }

    private fun describe(res: WifiManager.LocalOnlyHotspotReservation): Status {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cfg = res.softApConfiguration
            if (cfg != null) return Status.Active(cfg.ssid ?: "(hidden)", cfg.passphrase ?: "(open)")
        }
        @Suppress("DEPRECATION")
        val cfg = res.wifiConfiguration
        return Status.Active(cfg?.SSID ?: "(hidden)", cfg?.preSharedKey ?: "(open)")
        // ponytail: SoftApConfiguration.passphrase is null for OPEN/no-security
        // APs on some OEM builds rather than throwing — "(open)" covers both.
    }
}
