package com.diegonmarcos.superapp

/**
 * Shizuku UserService implementation — instantiated by the Shizuku
 * server inside a process running as the SHELL uid (2000) when the app
 * binds it via [Shizuku.bindUserService]. That elevated context is what
 * lets [exec] run `dumpsys batterystats` (which a normal app uid can't),
 * giving exact per-app energy attribution.
 *
 * Contract requirements (rikka Shizuku-API):
 *   • a public no-arg constructor (the server instantiates by name).
 *   • implement the AIDL [IUserService.Stub].
 *   • [destroy] maps to the fixed transaction id the server calls on
 *     teardown; we exit the hosting process so it doesn't linger.
 */
class ShizukuUserService : IUserService.Stub() {

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
