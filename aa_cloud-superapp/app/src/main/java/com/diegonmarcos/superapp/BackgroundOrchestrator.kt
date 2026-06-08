package com.diegonmarcos.superapp

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * Central pause/resume switch for background work in the SuperApp.
 *
 * Triggered by the active launcher theme's `background_pause` feature
 * flag (build.json::ui.launcher_themes[*].features.background_pause).
 * Cloud Power Saving sets it true → on theme application MainActivity
 * calls [applyForTheme] and we tear down every battery-hungry
 * subsystem we own: Maps location tracker, ntfy / message foreground
 * services, WorkManager periodic jobs, Phone-tab cache warm-up
 * threads. When the user flips back to Cloud or Minimalist the same
 * call resumes them by no-op (each producer re-arms on its own next
 * onCreate / onResume cycle).
 *
 * EXCEPTION: WireGuard. Per Diego — Power Saving mirrors Samsung /
 * Apple / Pixel power-save (cuts compute, keeps connectivity), so
 * the WG tunnel stays up. The `wireguard_required` feature flag
 * actually pins WG ON in this mode rather than off.
 *
 * Resilient to missing modules: every call site is wrapped in a
 * runCatching block so the orchestrator never crashes the launcher
 * on a build that doesn't include a particular module (e.g. CI
 * builds without libs:maps).
 */
object BackgroundOrchestrator {

    /** Apply the power-save policy expressed by the active theme.
     *  Called from MainActivity.onResume and on every theme change. */
    fun applyForTheme(context: Context, features: LauncherThemes.Features) {
        if (features.backgroundPause) pauseAll(context) else resumeAll(context)
    }

    /** Pause everything battery-hungry. WG explicitly excluded. */
    private fun pauseAll(context: Context) {
        stopMapsTracker(context)
        cancelPeriodicWork(context)
        // Phone-tab warm-up is a one-shot thread fired from
        // MainActivity.onCreate — nothing to stop after it completes,
        // but we DON'T fire it again on Power Saving themes (the
        // launcher's PhoneAppsFragment.warmUp now reads features
        // before kicking off and bails on power-save).
    }

    private fun resumeAll(context: Context) {
        // Subsystems re-arm on their own normal lifecycle hooks; we
        // don't have to explicitly start anything here. Keeping the
        // hook so future producers (e.g. an analytics ping batcher)
        // have somewhere to land their resume logic.
    }

    /** Issue a stop intent to libs:maps's LocationTrackerService.
     *  Resolved by string class-name so app/ doesn't need a hard
     *  compile dep on libs:maps's service class. */
    private fun stopMapsTracker(context: Context) = runCatching {
        val ctx = context.applicationContext
        val intent = Intent().apply {
            setClassName(
                ctx.packageName,
                "com.diegonmarcos.superapp.maps.LocationTrackerService",
            )
            action = "com.diegonmarcos.superapp.maps.ACTION_STOP"
        }
        // stopService is the safe form — no-op if the service isn't
        // running, no crash if the class doesn't exist on this build.
        try { ctx.stopService(intent) } catch (_: Throwable) { /* ignore */ }
    }

    /** Cancel every WorkManager periodic / one-shot job we've enqueued
     *  by tag. Producers that want their work to survive Power Saving
     *  mode can simply enqueue under a different tag. */
    private fun cancelPeriodicWork(context: Context) = runCatching {
        val wm = WorkManager.getInstance(context.applicationContext)
        TAGGED_JOBS.forEach { tag ->
            runCatching { wm.cancelAllWorkByTag(tag) }
        }
    }

    /** Tags we recognise. Keep in sync with whatever the updater /
     *  background producers enqueue under. Adding a new tag is one
     *  entry here — no other site needs to know about it. */
    private val TAGGED_JOBS = setOf(
        "superapp-updater-check",
        "superapp-ntfy-poll",
        "superapp-analytics-batch",
    )
}
