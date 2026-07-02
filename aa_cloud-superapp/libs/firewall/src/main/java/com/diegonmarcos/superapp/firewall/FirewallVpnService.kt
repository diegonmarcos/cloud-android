package com.diegonmarcos.superapp.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * No-root per-app firewall engine (SHIPPING interim). A local tun captures
 * traffic; apps whose per-app policy blocks them UNDER THE CURRENT conditions
 * (transport × energy) fall into the tun and are drained/dropped, everything
 * else bypasses the tunnel.
 *
 * The blocked set is DYNAMIC: recomputed from [FirewallRules] policies + live
 * [FirewallConditions] and re-applied on every network/screen change.
 *
 * Direction (IN/OUT) split and running alongside the WireGuard tunnel are the
 * staged firestack merge (libs/firewall/phase3-firestack/ + upstreams.firestack)
 * — not wired into the shipped build yet. See README.
 */
class FirewallVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var draining = false
    private var drainThread: Thread? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf(); START_NOT_STICKY }
            else -> {
                // FGS contract: startForeground quickly on every start path.
                startForeground(NOTIF_ID, buildNotification(0))
                onConditionsChanged(); registerWatchers(); START_STICKY
            }
        }
    }

    /** True when any app has a policy at all — the firewall has a job to do. */
    private fun hasWork(ctx: Context): Boolean = FirewallRules.policies(ctx).isNotEmpty()

    /** Apps to drop right now, given live conditions. */
    private fun effectiveBlocked(ctx: Context): Set<String> {
        val t = FirewallConditions.transport(ctx)
        return FirewallRules.policies(ctx)
            .filterKeys { it != packageName } // never block the launcher itself
            .filter { (pkg, rules) ->
                FirewallDecider.interimBlocked(rules, t, FirewallConditions.energy(ctx, pkg))
            }
            .keys
    }

    /** (Re)establish the tun to match the current effective block set. */
    private fun onConditionsChanged() {
        val ctx = applicationContext
        if (!hasWork(ctx)) { teardown(); stopSelf(); return }
        rebuild(ctx, effectiveBlocked(ctx))
    }

    private fun rebuild(ctx: Context, blocked: Set<String>) {
        teardownTun()
        if (blocked.isEmpty()) {
            // Policies exist but none match current conditions — keep watching,
            // tunnel no one.
            startForeground(NOTIF_ID, buildNotification(0)); return
        }
        val builder = Builder()
            .setSession("Superapp Firewall")
            .addAddress(TUN_ADDR4, 32)
            .addAddress(TUN_ADDR6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setBlocking(true)

        // Exclude every app that is NOT currently blocked → only blocked apps
        // enter the tun. Always exclude ourselves.
        val self = packageName
        for (pkg in packageManager.getInstalledApplications(0).map { it.packageName }) {
            if (pkg == self || pkg !in blocked) runCatching { builder.addDisallowedApplication(pkg) }
        }

        tun = runCatching { builder.establish() }.getOrNull()
        if (tun == null) {
            Log.w(TAG, "establish() null — VPN slot busy or consent missing")
            FirewallPrefs.setEnabled(ctx, false)
            teardown(); stopSelf(); return
        }
        FirewallPrefs.setEnabled(ctx, true)
        startForeground(NOTIF_ID, buildNotification(blocked.size))
        startDrain()
    }

    /** React to transport / screen changes by recomputing the block set. */
    private fun registerWatchers() {
        val ctx = applicationContext
        if (netCallback == null) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) = onConditionsChanged()
                override fun onLost(n: Network) = onConditionsChanged()
                override fun onCapabilitiesChanged(n: Network, c: android.net.NetworkCapabilities) =
                    onConditionsChanged()
            }.also { runCatching { cm?.registerDefaultNetworkCallback(it) } }
        }
        if (screenReceiver == null) {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) = onConditionsChanged()
            }.also {
                registerReceiver(it, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                })
            }
        }
    }

    /** Drain the tun and discard — blocked apps' packets go nowhere. */
    private fun startDrain() {
        val fd = tun?.fileDescriptor ?: return
        draining = true
        drainThread = Thread {
            val buf = ByteArray(32 * 1024)
            runCatching {
                FileInputStream(fd).use { input ->
                    while (draining) {
                        val n = input.read(buf)
                        if (n < 0) break
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun teardownTun() {
        draining = false
        drainThread?.interrupt(); drainThread = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun teardown() {
        teardownTun()
        netCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    override fun onDestroy() { teardown(); super.onDestroy() }

    override fun onRevoke() {
        teardown()
        FirewallPrefs.setEnabled(applicationContext, false)
        stopSelf()
    }

    private fun buildNotification(blockedCount: Int): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Firewall", NotificationManager.IMPORTANCE_LOW)
        )
        val text = if (blockedCount == 0) "Watching — no app blocked under current network"
        else "$blockedCount app(s) blocked from the network"
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Firewall active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "FirewallVpn"
        const val ACTION_STOP = "com.diegonmarcos.superapp.firewall.STOP"
        private const val TUN_ADDR4 = "10.111.222.1"
        private const val TUN_ADDR6 = "fd00:1:1:1::1"
        private const val CHANNEL = "firewall"
        private const val NOTIF_ID = 0x10C
    }
}
