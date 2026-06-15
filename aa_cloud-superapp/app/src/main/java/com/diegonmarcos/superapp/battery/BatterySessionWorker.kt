package com.diegonmarcos.superapp.battery

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic background tick that calls [BatterySessionStats.read] —
 * keeps the discharge anchor + charge-counter sample + cycle
 * accumulator fresh even when the app is fully backgrounded /
 * process-killed. The user shouldn't have to OPEN the SuperApp for
 * the battery rate to be accurate.
 *
 * Why this exists: the user's report:
 *   "the battery just now was at 100% but it started clocking and
 *    started computing just now at 86% when I requested the info,
 *    while it should be doing in background"
 *
 * Without periodic ticks the only paths that update the anchor are:
 *   • PowerStateReceiver firing on ACTION_POWER_(DIS)CONNECTED —
 *     blocked by Samsung Sleeping Apps for backgrounded apps.
 *   • read() called when the user OPENS a battery surface — too
 *     late; transition_detected anchors at "now" instead of at the
 *     real unplug moment.
 *
 * 15-minute periodic worker fixes both: even when the receiver is
 * suppressed, WorkManager wakes the process briefly every 15 min,
 * runs read(), and the transition-detection path catches the flip
 * with at most 15 min of lag (vs hours).
 *
 * 15 min is the Android-mandated minimum for PeriodicWorkRequest;
 * we use the floor for tightest accuracy. ExistingPeriodicWorkPolicy
 * .KEEP makes re-scheduling on every App.onCreate a no-op so the
 * 15-min cadence is stable across activity restarts.
 *
 * Constraints: NONE. We deliberately don't require network /
 * charging / battery-not-low — those would let the OS skip ticks
 * exactly when discharge tracking matters MOST. The work itself is
 * a ~1 ms BatterySessionStats.read() so the cost of every tick is
 * negligible vs the accuracy gain.
 */
class BatterySessionWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        EnergyLedger.wake("bg.battery_worker")
        runCatching { BatterySessionStats.read(applicationContext) }
        // Piggyback the energy watchdog on the same 15-min wakeup — one
        // coarse background sample per tick (cheap; the screen-on
        // foreground sampler adds the fine resolution).
        runCatching { EnergyWatchdog.sample(applicationContext) }
        return Result.success()
    }

    companion object {
        /** Unique work name — KEEP policy keys on this so calling
         *  schedule() many times in a row doesn't restart the
         *  cadence. */
        const val UNIQUE_NAME = "battery_session_tick"

        /** Idempotent — call from App.onCreate. Reschedules ONLY when
         *  no existing periodic work with this name is already
         *  enqueued (ExistingPeriodicWorkPolicy.KEEP). */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BatterySessionWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(Constraints.NONE).build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
