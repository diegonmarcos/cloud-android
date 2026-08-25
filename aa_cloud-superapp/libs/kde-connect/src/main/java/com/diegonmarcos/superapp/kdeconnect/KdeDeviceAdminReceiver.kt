package com.diegonmarcos.superapp.kdeconnect

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin receiver for KDE Connect remote lock (force-lock policy only).
 * Enrolled once by the user; [LockDevicePlugin] then calls lockNow() when the
 * desktop requests a lock. No behaviour beyond enabling the policy.
 */
class KdeDeviceAdminReceiver : DeviceAdminReceiver()
