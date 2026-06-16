package com.diegonmarcos.superapp.adbdebug

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * Self-contained "adb shell" facade backed by Shizuku.
 *
 * A normal app uid can't read [android.os.BatteryStatsManager], can't
 * `dumpsys usb`, and (on One UI 7+ / Pixel A15+) can't read
 * `/sys/class/power_supply/` nodes because SELinux denies it. Shizuku hands
 * us a process running as the SHELL uid (2000) via a bound
 * [ShellUserService]; from there [exec] runs any command with the same
 * power `adb shell` has.
 *
 * Threading: [bindBlocking] / [exec] are designed to be called OFF the
 * main thread (e.g. from DevControlServer's accept-loop thread). The
 * Shizuku service-connection callback lands on a binder thread; we gate
 * on a [CountDownLatch] so the first exec after a cold start waits for
 * the bind to land instead of racing it.
 */
object ShizukuAdb {

    const val PERMISSION_REQUEST_CODE = 6072

    @Volatile private var service: IShellService? = null
    @Volatile private var latch: CountDownLatch? = null

    private fun args(ctx: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("adbshell")
            .debuggable(false)
            .version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder())
                IShellService.Stub.asInterface(binder) else null
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Shizuku app installed AND its service running (binder alive). */
    fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** This app has been granted Shizuku permission by the user. */
    fun isGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    /** Human status line for the API / UI. */
    fun status(): String = when {
        !isAvailable() -> "Shizuku not running"
        !isGranted()   -> "Shizuku running — permission not granted (call requestPermission)"
        service != null -> "Connected (shell uid 2000)"
        else -> "Granted — not yet bound (call exec/bindBlocking)"
    }

    /**
     * Bind the UserService and wait up to [timeoutMs] for it to land.
     * Idempotent: returns true immediately if already bound. Returns
     * false when Shizuku is unavailable / not granted / the bind times
     * out. Safe to call from any non-main thread.
     */
    @Synchronized
    fun bindBlocking(ctx: Context, timeoutMs: Long = 4000): Boolean {
        if (service != null) return true
        if (!isAvailable() || !isGranted()) return false
        val l = CountDownLatch(1)
        latch = l
        val started = runCatching {
            Shizuku.bindUserService(args(ctx), connection)
        }.isSuccess
        if (!started) return false
        runCatching { l.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return service != null
    }

    fun unbind(ctx: Context) {
        runCatching { Shizuku.unbindUserService(args(ctx), connection, true) }
        service = null
    }

    /**
     * Run a shell command in shell (uid 2000) context. Binds on demand.
     * Returns the command's combined stdout, or null when Shizuku is
     * unavailable / not granted / the bind didn't land. A command that
     * only wrote to stderr surfaces as "ERR: …" (passed through from
     * [ShellUserService]).
     */
    fun exec(ctx: Context, command: String): String? {
        if (service == null && !bindBlocking(ctx)) return null
        return runCatching { service?.exec(command) }.getOrNull()
    }

    /**
     * Self-grant android.permission.DUMP to this app via the shell
     * channel (`pm grant <pkg> android.permission.DUMP`). After this the
     * app can call `dumpsys` IN-PROCESS even when Shizuku is later
     * stopped — the "self-contained" part. Returns a [GrantResult] with
     * the raw shell output and whether the permission is now held.
     */
    fun grantDump(ctx: Context): GrantResult {
        val perm = "android.permission.DUMP"
        val out = exec(ctx, "pm grant ${ctx.packageName} $perm")
            ?: return GrantResult(false, false, "Shizuku unavailable / not granted")
        val held = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        return GrantResult(true, held, out.ifBlank { "(no output — pm grant is silent on success)" })
    }

    data class GrantResult(val ran: Boolean, val held: Boolean, val output: String)
}
