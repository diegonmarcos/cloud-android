package com.diegonmarcos.superapp.system

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin receiver — the bind-target the system needs to enable
 * the app's force-lock policy. Empty by design: we don't observe any
 * admin lifecycle events (enable / disable / password-change). The
 * single capability we use — [android.app.admin.DevicePolicyManager.lockNow]
 * — only requires that the receiver be registered + the policy
 * declared in res/xml/device_admin_policy.xml + the user actively
 * granted via the system dialog.
 *
 * Class kept top-level (not nested in [ScreenLocker]) so the manifest
 * `android:name=".LockScreenAdminReceiver"` short-form resolves
 * without inner-class `$` mangling.
 */
class LockScreenAdminReceiver : DeviceAdminReceiver()
