package com.diegonmarcos.superapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

/**
 * Screen-lock orchestrator — wraps [DevicePolicyManager] so callers
 * (the home-screen double-tap detector + Configs/About status row)
 * don't have to know about ComponentName, Intent boilerplate, or the
 * "is this admin already active?" check. Single source of truth for:
 *   • the admin component identity
 *   • activation request flow (system dialog)
 *   • lock-now invocation
 *   • status string for the Permissions UI
 *
 * Backed by [LockScreenAdminReceiver] + res/xml/device_admin_policy.xml
 * (force-lock scope ONLY — see the policy file for the why).
 *
 * Permission cost to the user: ONE entry in
 *   Settings → Security → Device admin apps
 * which lists "Lock screen on home double-tap" as the only granted
 * capability. No wipe, no reset, no warnings.
 */
object ScreenLocker {

    /** ComponentName of [LockScreenAdminReceiver]. Stable across
     *  process restarts — only depends on `ctx.packageName`. */
    fun componentName(ctx: Context): ComponentName =
        ComponentName(ctx, LockScreenAdminReceiver::class.java)

    private fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** True iff the user has confirmed the Device Admin grant
     *  dialog. False covers both "never granted" and "granted then
     *  revoked from Settings". Safe to call before activation. */
    fun isActive(ctx: Context): Boolean =
        runCatching { dpm(ctx).isAdminActive(componentName(ctx)) }.getOrDefault(false)

    /** Locks the screen NOW if admin is active. Returns true on
     *  success, false when admin isn't granted (the only error path
     *  in practice — `lockNow` is documented as non-throwing once
     *  admin is active). The caller surfaces a friendly Toast on
     *  false so the user knows where to enable it. */
    fun lock(ctx: Context): Boolean = runCatching {
        if (!isActive(ctx)) return false
        dpm(ctx).lockNow()
        true
    }.getOrDefault(false)

    /** Open the system Device Admin add-dialog targeted at our own
     *  receiver. Activity-scoped because the system dialog needs a
     *  task to attach to. The user sees a single-policy ("force
     *  lock") summary + an explanation we control. */
    fun requestActivation(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(activity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allows the Cloud SuperApp to lock the screen on a home double-tap. " +
                    "Only one capability is granted (force-lock). Revoke any time in " +
                    "Settings → Security → Device admin apps.",
            )
        }
        activity.startActivity(intent)
    }

    /** Open Settings → Security → Device admin apps directly when
     *  admin is already active and the user wants to revoke it from
     *  the same Configs/About row that surfaced it. */
    fun openSystemDeviceAdminSettings(ctx: Context) {
        val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** Human-readable status for Configs/About → Permissions →
     *  "Device admin (lock)". Mirrors the shape of the other
     *  specialAccess* helpers (`✓ Granted` / `⚠ Not granted`). */
    fun statusString(ctx: Context): String =
        if (isActive(ctx)) "✓ Granted — double-tap home to lock"
        else               "⚠ Not granted — tap action below to enable"

    /** Attach a double-tap-to-lock gesture detector to any [View]
     *  (typically the home-screen root). Single-tap events still
     *  reach children — GestureDetector only fires onDoubleTap when
     *  TWO down-up cycles happen within the system's double-tap
     *  timeout (≈300ms) without movement in between. If admin isn't
     *  granted yet, the double-tap surfaces a Toast pointing the
     *  user at the right Configs/About row instead of silently
     *  doing nothing — the feature would otherwise feel broken.
     *
     *  Returns Unit (not the GestureDetector instance) — caller
     *  doesn't need to retain it; the closure capture inside the
     *  setOnTouchListener keeps it alive for the view's lifetime. */
    @SuppressLint("ClickableViewAccessibility")
    fun attachDoubleTapLock(root: View) {
        val det = GestureDetector(root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val ok = lock(root.context)
                if (!ok) {
                    Toast.makeText(
                        root.context,
                        "Enable Device Admin in Configs → About → Permissions → Special access",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return true
            }
        })
        root.isClickable = true
        root.setOnTouchListener { _, e ->
            // Return true so the gesture stream stays with the
            // listener even on empty space. Children get FIRST dibs
            // via ViewGroup.dispatchTouchEvent — so clickable rows /
            // tiles still receive their taps; the listener only sees
            // events the children didn't claim.
            det.onTouchEvent(e)
            true
        }
    }
}
