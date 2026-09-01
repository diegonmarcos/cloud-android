// Shizuku UserService interface. The implementation
// (ShizukuUserService) runs in shell context (uid 2000) when bound via
// Shizuku, so exec() can run privileged shell commands like
// `dumpsys batterystats --charged` for exact per-app energy attribution.
package com.diegonmarcos.superapp;

interface IUserService {
    // destroy() uses the fixed transaction id Shizuku's server invokes
    // when it tears the service down — required by the Shizuku contract.
    void destroy() = 16777114;

    // Run a shell command and return its combined stdout.
    String exec(String command) = 1;
}
