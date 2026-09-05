package com.diegonmarcos.superapp.recovery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.content.Context
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.updater.Advisory

/**
 * The SECONDARY advisory surface: a notification, for the user who never opens
 * the app — which is exactly the user whose device has quietly stopped
 * updating. [RecoveryBanner] is primary because it cannot be suppressed; this
 * is the only one that reaches someone who is not looking.
 *
 * ## The channel id is a one-way decision
 * A NotificationChannel's importance, sound and vibration are IMMUTABLE after
 * first creation. Nothing an app does later changes them — not recreating the
 * channel with different arguments, not reinstalling; only the user, in system
 * settings. cloud-mail spent hours on exactly this. So the id below is
 * versioned, and the rule is written down where the next person will read it:
 *
 *     CHANGING THE IMPORTANCE MEANS A NEW ID, not new arguments.
 *
 * Importance is HIGH, deliberately and unlike the deliberately-silenced sync
 * channel. This notification fires only when the device cannot repair itself,
 * which is both rare and the single most consequential thing the app has to
 * say. [Advisory.shouldNotify] enforces the rate limit that earns it.
 *
 * ## Why a channel of its own
 * Sharing the `constellation` channel would mean the routine "N updates
 * installed" notice and "this device can no longer update itself" are one
 * switch: a user who silences the chatter silences the emergency too.
 * ConstellationWorker's ready-to-install notification stays on `constellation`;
 * this one is separate on purpose, and the two must not be merged.
 */
object RecoveryNotifier {

    /** v1 — see the class note before ever changing this string. */
    private const val CHANNEL = "fleet-advisory-v1"

    /** One id per advisory, derived from its dedupe key, so a second advisory
     *  cannot silently replace the first. */
    private fun idOf(item: Advisory.Item) = 0xADD0 + (item.id.hashCode() and 0x0FFF)

    private fun manager(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = manager(ctx)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "Update problems", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Raised only when this device cannot update itself, " +
                "with a one-tap direct install to recover."
        })
    }

    /**
     * Raise every advisory that is currently due, respecting the rate limit.
     * Cheap and idempotent — call it from the same places that refresh the
     * banner.
     */
    fun post(ctx: Context, items: List<Advisory.Item>) {
        if (items.isEmpty()) return
        ensureChannel(ctx)
        // A POSTED NOTE IS NOT AN EMERGENCY. This channel is IMPORTANCE_HIGH
        // because it was built for "this device can no longer update itself",
        // and firing it for "maintenance tonight 22:00" would spend that
        // urgency on routine notices until the user silences the channel — at
        // which point the one message that had to get through cannot. INFO
        // items are the message board and live on the banner only; WARN and
        // STUCK are the machine saying something is wrong, and still notify.
        items.filter { it.severity != Advisory.Severity.INFO }
            .filter { Advisory.shouldNotify(ctx, it) }.forEach { item ->
            val pi = PendingIntent.getActivity(
                ctx, idOf(item), RecoveryActivity.intent(ctx, item.appId),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0),
            )
            manager(ctx).notify(idOf(item), Notification.Builder(ctx, CHANNEL)
                .setContentTitle(item.title)
                // The old dead end was a four-second Toast reading "no install
                // channel accepted <pkg>". The reason is kept — it is the only
                // thing that tells a reader what broke — but it now travels
                // with a tap target that fixes it.
                .setContentText(item.detail.lineSequence().firstOrNull() ?: "")
                .setStyle(Notification.BigTextStyle().bigText(item.detail))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        }
    }

    /** Drop a resolved advisory's notification. A warning that outlives the
     *  problem is how the next real one gets ignored. */
    fun cancel(ctx: Context, item: Advisory.Item) = manager(ctx).cancel(idOf(item))

    /** Called when the user reaches the recovery screen: they are now looking
     *  at the fix, so the pointer to it has done its job. */
    fun cancelAll(ctx: Context) {
        val fleet = runCatching {
            com.diegonmarcos.superapp.updater.Fleet.parse(
                com.diegonmarcos.superapp.updater.BuildConfig.CONSTELLATION_FLEET_B64)
        }.getOrDefault(emptyList())
        Advisory.current(ctx, fleet).forEach { cancel(ctx, it) }
    }
}
