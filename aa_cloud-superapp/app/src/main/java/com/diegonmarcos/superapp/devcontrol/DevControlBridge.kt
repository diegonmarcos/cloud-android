package com.diegonmarcos.superapp.devcontrol
import com.diegonmarcos.superapp.MainActivity

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Bridge between the worker-thread HTTP server and the foreground UI.
 *
 * MainActivity registers itself via [register] in onResume and
 * unregisters in onPause. The server thread posts work onto the main
 * Looper via [runOnMain], inside which it can call the registered
 * activity's exposed dispatch helpers.
 *
 * If no activity is registered, calls become no-ops and the
 * server's reply still completes (the user can re-launch the app to
 * complete the action).
 */
object DevControlBridge {

    interface ActivityHost {
        fun onTileFromServer(target: String)
        fun onActionFromServer(actionType: String)
        fun firePresetHaptic(preset: String)
        fun stateSnapshot(): Map<String, String>
    }

    @Volatile private var hostRef: WeakReference<ActivityHost>? = null
    private val main = Handler(Looper.getMainLooper())

    fun register(host: ActivityHost) { hostRef = WeakReference(host) }
    fun unregister(host: ActivityHost) {
        if (hostRef?.get() === host) hostRef = null
    }
    fun host(): ActivityHost? = hostRef?.get()

    fun runOnMain(block: () -> Unit) = main.post(block)

    /**
     * Restart the whole process. Schedules a launch intent via AlarmManager
     * for ~150ms in the future, then kills the current process — the
     * alarm wakes up after the kill and Android cold-starts the app.
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
        if (hostRef?.get() is Activity) (hostRef!!.get() as Activity).finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
