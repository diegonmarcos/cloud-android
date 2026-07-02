package com.diegonmarcos.superapp.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Tunnel

/**
 * Phase-3 MERGED engine — ONE VpnService that runs the firestack gVisor
 * netstack AND per-app filtering AND the WireGuard cloud-VPN proxy together,
 * replacing BOTH the interim drain-engine and `GoBackend$VpnService`. This
 * resolves the single-VPN-slot limit: firestack owns the slot; WireGuard runs
 * as an in-netstack proxy, and [FirewallFlowBridge] decides each flow.
 *
 * Flow of control:
 *   establish tun (fd) → Intra.connect(fd, mtu, bridge) → register the WG
 *   peer as a firestack proxy → firestack calls the bridge per connection,
 *   which returns Block / Exit(direct) / <wg-proxy-id>(via cloud VPN).
 *
 * No re-establish-on-network-change machinery is needed (unlike the drain
 * engine): the bridge reads live conditions per flow, so rules re-evaluate
 * continuously with zero teardown.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * RUNNER-COMPLETION (compiles only against the built firestack.aar):
 *  1. [buildBridge] — `Intra.connect` wants the full `Bridge` union
 *     (Console/Controller/DNSListener/ProxyListener/FlowListener). This class
 *     supplies the FlowListener via [FirewallFlowBridge]; the remaining
 *     listeners are thin log/no-op stubs whose EXACT method set comes from the
 *     aar (mirror RethinkDNS `GoVpnAdapter`/`GoIntraListener`). Marked TODO.
 *  2. [registerCloudVpn] — the exact WG-proxy add call (see
 *     firestack intra/ipn/wgproxy.go + RethinkDNS RpnProxyManager). Marked TODO.
 * ─────────────────────────────────────────────────────────────────────────
 */
class FirestackTunnelService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var tunnel: Tunnel? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf(); START_NOT_STICKY }
            else -> {
                startForeground(NOTIF_ID, notification())
                establish(); START_STICKY
            }
        }
    }

    private fun establish() {
        val ctx = applicationContext
        if (FirewallRules.policies(ctx).isEmpty()) { teardown(); stopSelf(); return }
        teardown()

        val fd = buildTun() ?: run {
            Log.w(TAG, "tun establish() returned null — VPN consent missing?")
            FirewallPrefs.setEnabled(ctx, false); stopSelf(); return
        }
        tun = fd
        tunnel = runCatching {
            // Connect3(fd, tunmtu, bridge): the simplest firestack entry.
            Intra.connect3(fd.fd, TUN_MTU, buildBridge())
        }.onFailure { Log.e(TAG, "firestack connect failed", it) }.getOrNull()

        if (tunnel == null) { FirewallPrefs.setEnabled(ctx, false); teardown(); stopSelf(); return }
        registerCloudVpn()
        FirewallPrefs.setEnabled(ctx, true)
    }

    private fun buildTun(): ParcelFileDescriptor? {
        val b = Builder()
            .setSession("Superapp Firewall")
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDR4, 24)
            .addAddress(TUN_ADDR6, 120)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setBlocking(true)
        // Never capture our own launcher.
        runCatching { b.addDisallowedApplication(packageName) }
        return runCatching { b.establish() }.getOrNull()
    }

    /** RUNNER TODO(1): assemble the full firestack Bridge. FlowListener is the
     *  decision half ([FirewallFlowBridge]); the other listeners are no-op/log
     *  stubs whose exact generated signatures come from the aar. */
    private fun buildBridge(): com.celzero.firestack.intra.Bridge {
        val flow = FirewallFlowBridge(applicationContext, FirewallController.cloudVpn)
        // The aar's `Bridge` is a union interface. Compose `flow` with the
        // remaining listeners here — see FirestackBridgeAssembly + RethinkDNS.
        return FirestackBridgeAssembly(flow).asBridge()
    }

    /** RUNNER TODO(2): register the WG peer as a firestack proxy so ALLOW_VPN
     *  flows route through the cloud tunnel. Exact call: firestack
     *  intra/ipn/wgproxy.go (getProxies().addProxy / newWgProxy). */
    private fun registerCloudVpn() {
        val cfg = FirewallController.cloudVpn.wgConfig() ?: return
        val t = tunnel ?: return
        runCatching {
            // e.g. t.proxies.addProxy(FirewallController.cloudVpn.proxyId(), cfg)
            Log.i(TAG, "cloud-VPN proxy register: TODO(runner) — cfg present=${cfg.isNotEmpty()}")
        }.onFailure { Log.e(TAG, "WG proxy register failed", it) }
    }

    private fun teardown() {
        runCatching { tunnel?.disconnect() }
        tunnel = null
        runCatching { tun?.close() }
        tun = null
    }

    override fun onDestroy() { teardown(); super.onDestroy() }
    override fun onRevoke() { teardown(); FirewallPrefs.setEnabled(applicationContext, false); stopSelf() }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Firewall", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Firewall + Cloud VPN active")
            .setContentText("Per-app filtering via firestack netstack")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "FirestackTun"
        const val ACTION_STOP = "com.diegonmarcos.superapp.firewall.STOP"
        private const val TUN_MTU = 1280
        private const val TUN_ADDR4 = "10.111.222.1"
        private const val TUN_ADDR6 = "fd66:f83a:c650::1"
        private const val CHANNEL = "firewall"
        private const val NOTIF_ID = 0x10C
    }
}
