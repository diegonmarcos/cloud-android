// Shizuku UserService interface for the general-purpose adb-shell
// channel. The implementation (ShellUserService) runs in SHELL context
// (uid 2000) when bound via Shizuku, so exec() can run privileged
// commands — `dumpsys usb`, `dumpsys battery`, `cat /sys/class/...`,
// `pm grant` — that the app's own uid cannot.
package com.diegonmarcos.superapp.adbdebug;

interface IShellService {
    // destroy() uses the fixed transaction id Shizuku's server invokes
    // when it tears the service down — required by the Shizuku contract.
    void destroy() = 16777114;

    // Run `sh -c <command>` in shell context, return combined stdout
    // (or "ERR: <stderr>" when the command only wrote to stderr).
    String exec(String command) = 1;
}
