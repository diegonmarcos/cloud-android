package com.diegonmarcos.superapp.battery

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
        val now = System.currentTimeMillis()
        val pct = readCurrentPct(ctx)
        when (intent.action) {
            Intent.ACTION_POWER_DISCONNECTED -> {
                // Cable pulled — mint discharge anchor + clear the now-
                // stale charge anchor in one atomic edit. read()'s lazy
                // fallback handles the rare cold-install case where the
                // receiver never fired.
                if (pct in 0..100) {
                    sp.edit()
                        .putLong("unplug_ts", now)
                        .putInt("unplug_pct", pct)
                        .putString("anchor_source", "disconnect_event")
                        .remove("plug_ts")
                        .remove("plug_pct")
                        .remove("anchor_source_plug")
                        .apply()
                }
            }
            Intent.ACTION_POWER_CONNECTED -> {
                // Cable inserted — mint charge anchor + clear the now-
                // stale discharge anchor. Symmetric to the disconnect
                // path so "Since plugged in" pins to the REAL connect
                // moment instead of whenever the user happens to open
                // Configs/About/Battery & Usage or the battery popup.
                if (pct in 0..100) {
                    sp.edit()
                        .putLong("plug_ts", now)
                        .putInt("plug_pct", pct)
                        .putString("anchor_source_plug", "connect_event")
                        .remove("unplug_ts")
                        .remove("unplug_pct")
                        .remove("anchor_source")
                        .apply()
                }
            }
        }
    }

    /** Read the sticky ACTION_BATTERY_CHANGED to get the instantaneous
     *  percent at the moment the connect / disconnect event fired. */
    private fun readCurrentPct(ctx: Context): Int {
        val sticky = ctx.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return -1
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }
}
