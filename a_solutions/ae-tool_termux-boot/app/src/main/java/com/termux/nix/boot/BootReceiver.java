package com.termux.nix.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Runs one command inside Nix-on-Droid at device boot.
 *
 * WHY THIS IS NOT A FORK OF termux-boot
 * -------------------------------------
 * Termux:Boot declares android:sharedUserId="com.termux", so it executes as the
 * same uid as the Termux app. That single fact buys it two things we cannot have:
 *
 *   1. It can listFiles() the boot directory, which is 0700 and owned by the
 *      app uid.
 *   2. It can start TermuxService, which is android:exported="false" and so is
 *      reachable only from inside the app's own uid.
 *
 * Android grants a shared uid only to apps signed with the SAME certificate.
 * Nix-on-Droid is signed by F-Droid; we do not hold that key and cannot obtain
 * it, so this app can never share com.termux.nix's uid. A forked termux-boot
 * would fail to install (INSTALL_FAILED_SHARED_USER_INCOMPATIBLE), and if it
 * somehow installed it could do neither (1) nor (2).
 *
 * THE DOOR THAT IS ACTUALLY OPEN
 * ------------------------------
 * RunCommandService is android:exported="true", guarded by a RUN_COMMAND
 * permission declared protectionLevel="dangerous" rather than "signature". A
 * foreign-signed app may hold a dangerous permission with user consent, so this
 * path is reachable. The host additionally requires allow-external-apps=true in
 * termux.properties; that file is deployed declaratively by the Nix flake.
 *
 * CONSEQUENCE FOR THE DESIGN
 * --------------------------
 * We still cannot enumerate the 0700 boot directory, so this app does not try.
 * It launches ONE fixed script, and that script — running as the host uid —
 * does the enumeration. So the boot-script set is data on the device, not code
 * in this APK: adding or changing scripts never requires rebuilding it.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "cloud-boot";

    /** Host package. Every action, extra and permission name derives from it. */
    private static final String NOD = BuildConfig.NOD_PACKAGE_NAME;

    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";

    /**
     * The ONLY executable reachable from here. TermuxService runs the command
     * with ProcessBuilder in the plain Android app context -- OUTSIDE proot --
     * where /nix does not exist (it is a proot bind of files/usr/nix). Every
     * binary in files/usr/bin is a symlink into /nix/store, so all of them are
     * dangling from out here. Exactly two real files exist in that directory:
     * proot-static and this one.
     *
     * Verified the hard way on galaxy 2026-08-21: pointing RUN_COMMAND_PATH at
     * the boot script directly failed with
     *   Cannot run program ".../files/usr/bin/env": error=2
     * because the script's own "#!/usr/bin/env sh" shebang resolved to a
     * dangling symlink. The script was fine; nothing that reaches /nix can run.
     */
    private static final String LOGIN = "/data/data/" + NOD + "/files/usr/bin/login";

    /**
     * The boot script, passed as an ARGUMENT to login rather than executed.
     * login enters proot and does `exec /usr/bin/env "$@"` with the Nix session
     * environment sourced, so from there the shebang resolves and the nix
     * profile is on PATH.
     *
     * It lives in the host's home directory and is written by the Nix flake,
     * which is what guarantees the path is stable.
     */
    private static final String BOOT_RUNNER =
        "/data/data/" + NOD + "/files/home/.termux/boot-runner.sh";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        Intent run = new Intent(NOD + ".RUN_COMMAND");
        run.setClassName(NOD, RUN_COMMAND_SERVICE);
        run.putExtra(NOD + ".RUN_COMMAND_PATH", LOGIN);
        run.putExtra(NOD + ".RUN_COMMAND_ARGUMENTS", new String[]{ "sh", BOOT_RUNNER });
        // Background, i.e. an app shell rather than a visible terminal session:
        // nothing is on screen at boot and a foreground session would steal it.
        run.putExtra(NOD + ".RUN_COMMAND_BACKGROUND", true);
        run.putExtra(NOD + ".RUN_COMMAND_WORKDIR", "/data/data/" + NOD + "/files/home");

        try {
            // Boot is exactly when background-start restrictions apply, so the
            // service must be started in the foreground form on O+. The host
            // promotes it to a foreground service with its own notification.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(run);
            } else {
                context.startService(run);
            }
            Log.i(LOG_TAG, "requested " + BOOT_RUNNER + " via " + LOGIN);
        } catch (Exception e) {
            // Never crash the boot broadcast. The most likely causes are the
            // RUN_COMMAND permission not yet granted, or allow-external-apps
            // being unset -- both are recoverable and neither is worth an ANR.
            Log.e(LOG_TAG, "could not start " + RUN_COMMAND_SERVICE + " in " + NOD, e);
        }
    }
}
