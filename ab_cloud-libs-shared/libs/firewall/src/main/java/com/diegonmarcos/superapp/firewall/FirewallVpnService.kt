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
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import java.io.FileInputStream

/**
 * No-root per-app firewall engine (shipping interim). A local tun captures
 * traffic; apps blocked by their [AppRule] UNDER THE CURRENT conditions
 * (transport × background) fall into the tun and are drained/dropped, while
 * every other app is excluded and uses the network normally.
 *
 * The tun is established whenever the firewall is enabled — even with zero
 * apps currently blocked — so toggling the firewall on VISIBLY activates the
 * VPN (key icon) and rules take effect live. The blocked set is recomputed on
 * every network/screen change.
 *
 * Direction IN/OUT and running alongside the WireGuard tunnel are the staged
 * firestack merge (libs/firewall/phase3-firestack/); the drain-engine enforces
 * transport + background + block-all only.
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
                startForeground(NOTIF_ID, buildNotification(0))
                onConditionsChanged(); registerWatchers(); START_STICKY
            }
        }
    }

    /** Apps to drop right now, given each app's rule × live conditions. */
    private fun effectiveBlocked(ctx: Context): Set<String> {
        val t = FirewallConditions.transport(ctx)
        return FirewallRules.configured(ctx)
            .filterKeys { it != packageName } // never block the launcher itself
            .filter { (pkg, rule) ->
                FirewallDecider.interimBlocked(rule, t, FirewallConditions.isBackground(ctx, pkg))
            }
            .keys
    }

    private fun onConditionsChanged() = rebuild(applicationContext, effectiveBlocked(applicationContext))

    /** (Re)establish the tun. Established whenever enabled, even if [blocked]
     *  is empty (VPN on, blocks no one) — so the toggle visibly turns on. */
    private fun rebuild(ctx: Context, blocked: Set<String>) {
        teardownTun()
        val builder = Builder()
            .setSession("Superapp Firewall")
            .setMtu(MTU)
            .addAddress(TUN_ADDR4, 32)
            .addAddress(TUN_ADDR6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TUN_ADDR4) // some OEMs reject a VPN with no DNS
            .setBlocking(true)

        // Exclude every app that is NOT currently blocked → only blocked apps
        // enter the tun. Always exclude ourselves. With an empty block set,
        // everyone is excluded → the VPN is up but drops nothing.
        val self = packageName
        for (pkg in packageManager.getInstalledApplications(0).map { it.packageName }) {
            if (pkg == self || pkg !in blocked) runCatching { builder.addDisallowedApplication(pkg) }
        }

        tun = runCatching { builder.establish() }.getOrElse {
            Log.e(TAG, "establish() threw", it); null
        }
        if (tun == null) {
            Log.w(TAG, "establish() null — VPN consent missing or slot busy")
            Toast.makeText(ctx, "Firewall couldn't start (VPN permission or another VPN active)", Toast.LENGTH_LONG).show()
            FirewallPrefs.setEnabled(ctx, false)
            teardown(); stopSelf(); return
        }
        FirewallPrefs.setEnabled(ctx, true)
        startForeground(NOTIF_ID, buildNotification(blocked.size))
        if (blocked.isNotEmpty()) startDrain()
    }

    /** React to transport / screen changes by recomputing the block set. */
    private fun registerWatchers() {
        val ctx = applicationContext
        if (netCallback == null) {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            // Deliver callbacks on the main thread so rebuild()'s startForeground
            // / Toast are main-thread-safe (default delivery is a binder thread).
            val main = Handler(Looper.getMainLooper())
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) = onConditionsChanged()
                override fun onLost(n: Network) = onConditionsChanged()
                override fun onCapabilitiesChanged(n: Network, c: android.net.NetworkCapabilities) =
                    onConditionsChanged()
            }.also { runCatching { cm?.registerDefaultNetworkCallback(it, main) } }
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
        val text = if (blockedCount == 0) "On — no app blocked under the current network"
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
        private const val MTU = 1500
        private const val TUN_ADDR4 = "10.111.222.1"
        private const val TUN_ADDR6 = "fd00:1:1:1::1"
        private const val CHANNEL = "firewall"
        private const val NOTIF_ID = 0x10C
    }
}
