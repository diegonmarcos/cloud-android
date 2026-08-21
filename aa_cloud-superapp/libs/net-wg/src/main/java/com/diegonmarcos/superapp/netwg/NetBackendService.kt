package com.diegonmarcos.superapp.netwg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.diegonmarcos.superapp.net.INetBackend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

/**
 * The far side of [INetBackend]: the only process on the device that holds
 * a GoBackend, and therefore the only APK carrying libwg-go.so.
 *
 * Every app that wants a tunnel binds here instead of linking the engine.
 * The tunnel object is OURS - the caller's Tunnel cannot cross the binder,
 * so callers address tunnels by name and AidlBackend delivers the state
 * callback locally.
 */
class NetBackendService : Service() {

    /** One tunnel per name. GoBackend tracks the active tunnel by identity,
     *  so handing it a fresh object per call would make it report empty
     *  statistics for a tunnel it is in fact running. */
    private val tunnels = HashMap<String, NamedTunnel>()

    private class NamedTunnel(private val n: String) : Tunnel {
        @Volatile var state: Tunnel.State = Tunnel.State.DOWN
        override fun getName(): String = n
        override fun onStateChange(newState: Tunnel.State) { state = newState }
    }

    private val backend by lazy { GoBackend(applicationContext) }

    private fun tunnelFor(name: String): NamedTunnel =
        synchronized(tunnels) { tunnels.getOrPut(name) { NamedTunnel(name) } }

    private val binder = object : INetBackend.Stub() {

        override fun getState(tunnelName: String?): String =
            runCatching { backend.getState(tunnelFor(tunnelName.orEmpty())).name }
                .getOrElse { Tunnel.State.DOWN.name }

        override fun setState(tunnelName: String?, state: String?, wgQuickConfig: String?): String {
            val tunnel = tunnelFor(tunnelName.orEmpty())
            val want = runCatching { Tunnel.State.valueOf(state.orEmpty()) }
                .getOrDefault(Tunnel.State.DOWN)
            // A null config is legal going DOWN and fatal going UP; let the
            // parse error surface rather than silently leaving it down.
            val cfg: Config? = wgQuickConfig?.takeIf { it.isNotBlank() }
                ?.let { Config.parse(it.byteInputStream().bufferedReader()) }
            return runCatching { backend.setState(tunnel, want, cfg).name }
                .onFailure { Log.w(TAG, "setState($tunnelName, $want) failed", it) }
                .getOrElse { Tunnel.State.DOWN.name }
        }

        override fun getStatisticsRaw(tunnelName: String?): String {
            // Statistics is not parcelable and we do not want an AIDL type
            // that has to track upstream WireGuard; the raw text round-trips
            // through the one parser both sides share.
            val t = tunnelFor(tunnelName.orEmpty())
            return runCatching { backend.getStatisticsRaw(t) }.getOrDefault("")
        }

        override fun getVersion(): String =
            runCatching { backend.version }.getOrElse { "unknown" }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object { const val TAG = "NetBackendService" }
}
