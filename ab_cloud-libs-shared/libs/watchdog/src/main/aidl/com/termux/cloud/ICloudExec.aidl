package com.termux.cloud;

/**
 * Run a command in this terminal and get the output back.
 *
 * Termux already had two ways in and neither could do this. RUN_COMMAND fires
 * and forgets — the interesting part, what the command printed, stays on this
 * side of the sandbox. A terminal session is interactive, so reading it means
 * guessing where the output ended by watching for a prompt or a pause, which
 * on a loaded phone truncates. The constellation's workaround was to ssh to
 * loopback, which made every dashboard depend on an sshd being installed,
 * running, on the expected port and holding the right key.
 *
 * This is the fourth way, and the only one that is a function call: arguments
 * in, stdout/stderr/exit out, blocking until the command is done. It is
 * available only to callers signed with the same key (see the
 * signature-level permission in the manifest), so owning both ends is what
 * makes it safe to expose at all.
 */
interface ICloudExec {

    /**
     * Run [executable] with [args] and block until it exits.
     *
     * Returns a Bundle: "stdout" and "stderr" as Strings, "exit" as an int,
     * and "error" as a String present only when the command could not be run
     * or outlived timeoutMs. An "error" Bundle still carries whatever output
     * was produced before it was cut off.
     *
     * stdin may be null. workdir may be null for the Termux home directory.
     * timeoutMs <= 0 means the built-in ceiling rather than forever: a binder
     * call that never returns wedges the caller's thread, and a phone has no
     * one sitting at it to press ^C.
     */
    Bundle exec(String executable, in String[] args, String stdin, String workdir, int timeoutMs);

    /**
     * This terminal's $PREFIX, so a caller can find the binaries it placed
     * here rather than assuming a path that is only true on one phone.
     */
    String prefix();

    /**
     * Where this terminal keeps the fleet binaries it ships.
     *
     * They ride inside this APK rather than being pushed here at runtime: a
     * caller in another sandbox cannot write into this app's files, and the
     * previous design worked around that by streaming them over ssh — which
     * is the dependency this service exists to remove. Shipping them together
     * also means the panel and the app driving it can never be different
     * builds.
     */
    String toolsDir();
}
