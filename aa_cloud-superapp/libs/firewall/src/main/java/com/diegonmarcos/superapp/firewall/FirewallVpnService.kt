package com.diegonmarcos.superapp.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * Minimal no-root per-app outbound firewall.
 *
 * Mechanism (zero root, zero native code):
 *  - [establish] builds a local tun with a private address and a default
 *    route, so the OS routes app traffic INTO this tun;
 *  - every ALLOWED app is added via addDisallowedApplication() — those
 *    apps are EXCLUDED from the tunnel and use the real network normally;
 *  - every BLOCKED app therefore falls INTO the tun, where we never
 *    forward its packets (we drain and discard the tun) — so its
 *    connections time out: blocked.
 *
 * Note the inversion: we disallow the ALLOWED apps so that only blocked
 * apps enter the tun. With zero blocked apps we tear down instead of
 * tunnelling the whole device for nothing.
 *
 * Destination-level filtering, DNS control, and WireGuard proxying are
 * deliberately OUT of scope for v1 — they are the future cherry-pick from
 * the RethinkDNS firestack engine (build.json::upstreams.firewall).
 */
class FirewallVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var draining = false
    private var drainThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { teardown(); stopSelf(); START_NOT_STICKY }
            else -> { establish(); START_STICKY }
        }
    }

    private fun establish() {
        val ctx = applicationContext
        val blocked = FirewallPrefs.blocked(ctx)
        if (blocked.isEmpty()) {
            // Nothing to block — don't hold the device VPN slot for nothing.
            FirewallPrefs.setEnabled(ctx, false)
            teardown(); stopSelf(); return
        }
        teardown()

        val builder = Builder()
            .setSession("Superapp Firewall")
            .addAddress(TUN_ADDR4, 32)
            .addAddress(TUN_ADDR6, 128)
            .addRoute("0.0.0.0", 0)   // pull all IPv4 into the tun…
            .addRoute("::", 0)        // …and all IPv6
            .setBlocking(true)

        // Exclude every app that is NOT blocked → only blocked apps enter
        // the tun. Always exclude ourselves so the launcher keeps network
        // access regardless of its own block state.
        val self = packageName
        val installed = packageManager.getInstalledApplications(0).map { it.packageName }
        for (pkg in installed) {
            if (pkg == self || !blocked.contains(pkg)) {
                runCatching { builder.addDisallowedApplication(pkg) }
            }
        }

        tun = runCatching { builder.establish() }.getOrNull()
        if (tun == null) {
            Log.w(TAG, "establish() returned null — VPN consent missing?")
            FirewallPrefs.setEnabled(ctx, false)
            stopSelf(); return
        }
        FirewallPrefs.setEnabled(ctx, true)
        startForeground(NOTIF_ID, buildNotification(blocked.size))
        startDrain()
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
                        // drop: do nothing with the bytes
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun teardown() {
        draining = false
        drainThread?.interrupt(); drainThread = null
        runCatching { tun?.close() }
        tun = null
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
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Firewall active")
            .setContentText("$blockedCount app(s) blocked from the network")
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
