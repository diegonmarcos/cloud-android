package com.diegonmarcos.superapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Manifest-registered receiver that captures the EXACT moment the
 * user unplugs / plugs in the device, regardless of whether the
 * SuperApp is in the foreground.
 *
 * Why this exists: [BatterySessionStats] used to mint the anchor on
 * first read of Configs/About/Battery & Usage. If the user unplugged
 * at 100 % and then used the phone for hours without opening the
 * SuperApp, the "Since last charge" panel anchored at the 95 %
 * (or wherever) reading at first open, showing a bogus ~0s elapsed.
 * Now the anchor is written by THIS receiver the instant the cable
 * is pulled — accurate elapsed time AND accurate consumed-pct AND
 * a true 100 % → x % delta the next time the page renders.
 *
 * ACTION_POWER_CONNECTED + ACTION_POWER_DISCONNECTED are explicitly
 * exempt from the Android-8+ implicit-broadcast manifest restriction
 * (see ChangeId 173678720), so manifest registration delivers them
 * reliably even with the app fully cold.
 *
 * We do NOT register for ACTION_BATTERY_CHANGED in the manifest —
 * it's a sticky high-frequency broadcast and the docs explicitly
 * warn against static-registering it (would wake the process every
 * ~5 seconds while charging). For instantaneous percentage at the
 * moment of disconnect we read the sticky intent directly inside
 * onReceive, which is safe + cheap (one allocation, one int extra).
 */
class PowerStateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val sp = ctx.getSharedPreferences("battery_session", Context.MODE_PRIVATE)
        when (intent.action) {
            Intent.ACTION_POWER_DISCONNECTED -> {
                val sticky = ctx.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                )
                val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                if (pct in 0..100) {
                    sp.edit()
                        .putLong("unplug_ts", System.currentTimeMillis())
                        .putInt("unplug_pct", pct)
                        .putString("anchor_source", "disconnect_event")
                        .apply()
                }
            }
            Intent.ACTION_POWER_CONNECTED -> {
                // On the cable — anchor is stale by definition.
                // Clear so the NEXT disconnect mints a fresh one.
                sp.edit().clear().apply()
            }
        }
    }
}
