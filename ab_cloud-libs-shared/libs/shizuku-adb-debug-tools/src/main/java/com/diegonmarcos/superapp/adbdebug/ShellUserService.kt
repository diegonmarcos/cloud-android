package com.diegonmarcos.superapp.adbdebug

/**
 * Shizuku UserService implementation — instantiated by the Shizuku
 * server inside a process running as the SHELL uid (2000) when the app
 * binds it via [rikka.shizuku.Shizuku.bindUserService]. That elevated
 * context is what lets [exec] run `dumpsys usb`, `dumpsys battery`,
 * `cat /sys/class/power_supply/...`, and `pm grant … DUMP` — none of
 * which a normal app uid (or the SELinux-hardened proc/sys path) can do.
 *
 * Contract requirements (rikka Shizuku-API):
 *   • a public no-arg constructor (the server instantiates by name).
 *   • implement the AIDL [IShellService.Stub].
 *   • [destroy] maps to the fixed transaction id the server calls on
 *     teardown; we exit the hosting process so it doesn't linger.
 *
 * Mirrors app/'s ShizukuUserService (energy) but is general-purpose —
 * this is the lib's self-contained "adb shell" executor.
 */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        // Stop the shell-context process the server spun up for us.
        System.exit(0)
    }

    override fun exec(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            if (err.isNotBlank() && out.isBlank()) "ERR: $err" else out
        } catch (e: Throwable) {
            "ERR: ${e.message}"
        }
    }
}
