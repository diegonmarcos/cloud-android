package com.diegonmarcos.superapp.kdeconnect

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * kdeconnect.lock — report + control the phone lock state. Reading the state
 * (KeyguardManager) needs no permission; LOCKING needs the device-admin policy
 * ([KdeDeviceAdminReceiver]) which the user enrols once — until then a lock
 * request posts a prompt to enable it.
 */
object LockDevicePlugin : KdePlugin {
    private const val REQUEST = "kdeconnect.lock.request"
    private const val STATE   = "kdeconnect.lock"

    override val id = "lockdevice"
    override val incoming = setOf(REQUEST, STATE)
    override val outgoing = setOf(STATE, REQUEST)

    /** Sender — ask the desktop to lock (or unlock) itself. */
    fun lockRemote(locked: Boolean = true) =
        NetworkPacket.of(REQUEST) { put("setLocked", locked) }
    fun requestState() = NetworkPacket.of(REQUEST) { put("requestLocked", true) }

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        when (packet.type) {
            REQUEST -> if (packet.getBoolean("requestLocked")) sendState(ctx, link)
            STATE   -> if (packet.has("setLocked")) {
                if (packet.getBoolean("setLocked")) lock(ctx)
                sendState(ctx, link)
            }
        }
        return true
    }

    private fun sendState(ctx: Context, link: KdeLink) {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        link.send(NetworkPacket.of(STATE) { put("isLocked", km.isDeviceLocked) })
    }

    private fun lock(ctx: Context) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, KdeDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
        } else {
            KdeNotifications.post(ctx, "Remote lock",
                "Enable the 'KDE Connect lock' device-admin once to allow locking from the desktop.")
            runCatching {
                ctx.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Allows KDE Connect to lock this phone from your desktop.")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
