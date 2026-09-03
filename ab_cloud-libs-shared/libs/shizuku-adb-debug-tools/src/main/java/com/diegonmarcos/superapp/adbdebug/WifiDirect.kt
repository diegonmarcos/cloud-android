package com.diegonmarcos.superapp.adbdebug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

/**
 * Fallback self-created WiFi for Wireless Debugging: WifiP2pManager (Wi-Fi
 * Direct) creates a P2P group where this device is the group owner, which
 * behaves like a local access point ("DIRECT-…" SSID) — no external network,
 * no internet routing. Used when [LocalHotspot] (LocalOnlyHotspot) refuses
 * to start (some Samsung builds reject it while Wireless Debugging is being
 * enabled).
 *
 * requires ACCESS_FINE_LOCATION (<33) or NEARBY_WIFI_DEVICES (33+), plus the
 * manifest perms ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / NEARBY_WIFI_DEVICES.
 */
object WifiDirect {

    sealed class Status {
        object Idle : Status()
        data class Active(val ssid: String, val passphrase: String, val ownerIp: String? = null) : Status()
        data class Failed(val reason: String) : Status()
    }

    @Volatile private var manager: WifiP2pManager? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var receiverCtx: Context? = null
    @Volatile private var status: Status = Status.Idle

    fun status(): Status = status

    private fun initialize(ctx: Context): Pair<WifiP2pManager, WifiP2pManager.Channel>? = runCatching {
        val app = ctx.applicationContext
        val mgr = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return null
        val ch = mgr.initialize(app, Looper.getMainLooper(), null) ?: return null
        manager = mgr; channel = ch
        mgr to ch
    }.getOrNull()

    /** Create the P2P group. [onResult] fires on the main thread. */
    fun start(ctx: Context, onResult: (Status) -> Unit) {
        val app = ctx.applicationContext
        val (mgr, ch) = initialize(app) ?: run {
            status = Status.Failed("No WifiP2pManager"); onResult(status); return
        }
        runCatching { registerReceiver(app, mgr, ch, onResult) }

        // BUSY / "framework busy" almost always means a PRIOR group (ours, from an
        // earlier tap, or a persistent one) is still up. createGroup then refuses.
        // So always removeGroup() FIRST, then create; and on BUSY, remove + retry
        // once. removeGroup is async, so chain create in its callback.
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Actual SSID/passphrase/owner arrive via WIFI_P2P_CONNECTION_CHANGED_ACTION
                // -> requestGroupInfo(); this just confirms the request was accepted.
            }
            override fun onFailure(reasonCode: Int) {
                // We already removeGroup()'d before this create, so a BUSY here means
                // the framework is genuinely occupied (another app / Wi-Fi P2P mid-op).
                val why = when (reasonCode) {
                    WifiP2pManager.ERROR -> "generic error (often WiFi busy / another P2P group active)"
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported on this device"
                    WifiP2pManager.BUSY -> "P2P framework busy — toggle WiFi off/on, or use the local-WiFi flow, then retry"
                    else -> "code=$reasonCode"
                }
                status = Status.Failed(why)
                onResult(status)
            }
        }

        // Remove any stale group first, THEN create (chained in the callback so it
        // actually serializes). removeGroup failing just means there was none.
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { doCreate(mgr, ch, listener) }
                override fun onFailure(reasonCode: Int) { doCreate(mgr, ch, listener) }
            })
        }.onFailure { doCreate(mgr, ch, listener) }
    }


    private fun doCreate(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName("DIRECT-cloudsa")
                    .setPassphrase(randomPassphrase())
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    .enablePersistentMode(false)
                    .build()
                mgr.createGroup(ch, config, listener)
            } else {
                mgr.createGroup(ch, listener)
            }
        }.onFailure { e ->
            status = Status.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Tear the group down + unregister the receiver. */
    fun stop() {
        runCatching {
            val mgr = manager; val ch = channel
            if (mgr != null && ch != null) mgr.removeGroup(ch, null)
        }
        runCatching { receiver?.let { br -> receiverCtx?.unregisterReceiver(br) } }
        receiver = null
        receiverCtx = null
        status = Status.Idle
    }

    private fun registerReceiver(
        ctx: Context,
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onResult: (Status) -> Unit,
    ) {
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        val br = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                        if (group == null) return@requestGroupInfo
                        status = runCatching { describe(group) }
                            .getOrDefault(Status.Active("(unknown)", "(unknown)"))
                        onResult(status)
                    }
                }
            }
        }
        ctx.registerReceiver(br, filter)
        receiver = br
        receiverCtx = ctx
    }

    private fun describe(group: WifiP2pGroup): Status {
        val ssid = group.networkName ?: "(unknown)"
        val pass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.passphrase ?: "(open)" else "(open)"
        // Group-owner default IP for Wi-Fi Direct is conventionally 192.168.49.1;
        // there is no direct API to read it off WifiP2pGroup, so it's reported
        // only when this device is the owner (the common case with createGroup()).
        val ownerIp = if (group.isGroupOwner) "192.168.49.1" else null
        return Status.Active(ssid, pass, ownerIp)
    }

    private fun randomPassphrase(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..10).map { chars.random() }.joinToString("")
    }
}
