package com.diegonmarcos.devcontrol

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlin.system.exitProcess

/**
 * Main-thread bridge for the worker-thread HTTP server. The server posts UI
 * work onto the main Looper via [runOnMain], inside which it calls the
 * registered [DevControl.host] (nav / action / haptic / state). Host
 * registration lives on [DevControl.host] now (the Activity sets it), so this
 * object is just the thread hop + the process restart.
 */
object DevControlBridge {

    private val main = Handler(Looper.getMainLooper())

    fun runOnMain(block: () -> Unit) = main.post(block)

    /**
     * Restart the whole process. Schedules a launch intent via AlarmManager
     * for ~150ms in the future, then kills the current process — the alarm
     * wakes after the kill and Android cold-starts the app.
     */
    fun restartApp(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        val mgr = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 150, pi)
        (DevControl.host as? Activity)?.finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
