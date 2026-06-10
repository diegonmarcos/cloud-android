package com.diegonmarcos.superapp

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Tiny AccessibilityService whose ONLY job is to host
 * [performGlobalAction] calls for [AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN].
 *
 * Why this exists (Smart Lock + Garmin trusted device):
 * --------------------------------------------------------
 * [android.app.admin.DevicePolicyManager.lockNow] also locks the
 * screen, but on most Android builds it sets the strong-auth-
 * required bit — which DISABLES biometric unlock + every Smart Lock
 * "trusted" path (Trusted Device for a watch, Trusted Place for
 * home, Voice Match, etc.) on the next unlock. Smart Lock users
 * (e.g. with a paired Garmin Connect IQ watch) lose the keep-
 * unlocked benefit until they punch in the PIN. That's a security-
 * theatre regression we don't want.
 *
 * Android 9 (API 28) added GLOBAL_ACTION_LOCK_SCREEN as the
 * "press the power button" equivalent — same UX as the hardware
 * lock, Smart Lock + biometric stay fully functional, no
 * strong-auth side effect. This service is the canonical way to
 * call it from a third-party app.
 *
 * Permission cost to the user: one entry in
 *   Settings → Accessibility → Installed services → Cloud SuperApp
 *
 * No event handling — we declare android:accessibilityEventTypes=""
 * in res/xml/lock_screen_accessibility_service.xml so the system
 * never wakes the service with events. The only purpose of the bind
 * is so [performGlobalAction] resolves to a valid service token.
 */
class LockScreenAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /** No-op — we declared accessibilityEventTypes="" so this should
     *  never fire, but the abstract method requires a body. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** Required abstract method; no resources to release. */
    override fun onInterrupt() {}

    companion object {
        /** The running service instance, valid only while the user
         *  has the service enabled AND the system has bound it.
         *  @Volatile so writes from onServiceConnected (system
         *  binder thread) publish to readers on the main thread. */
        @Volatile
        var instance: LockScreenAccessibilityService? = null
            private set

        /** Persistent enablement check — independent of whether the
         *  service is currently BOUND (the bind state can flap on
         *  device events). Reads the ENABLED_ACCESSIBILITY_SERVICES
         *  secure setting which is a colon-separated list of
         *  `<pkg>/<class>` entries. We match either the canonical
         *  fully-qualified name or the manifest short-form (`.LockScreenAccessibilityService`),
         *  since both styles appear in the wild depending on how the
         *  user toggled the service on. */
        fun isEnabled(ctx: Context): Boolean = runCatching {
            val raw = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val full = "${ctx.packageName}/${LockScreenAccessibilityService::class.java.name}"
            val short = "${ctx.packageName}/.${LockScreenAccessibilityService::class.java.simpleName}"
            raw.split(':').any { entry ->
                entry.equals(full, ignoreCase = true) ||
                    entry.equals(short, ignoreCase = true)
            }
        }.getOrDefault(false)
    }
}
