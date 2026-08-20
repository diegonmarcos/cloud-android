package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

// comms: silent self-update installer. Replaces the old ACTION_VIEW +
// FileProvider hand-off (which ALWAYS shows the system installer dialog) with a
// PackageInstaller session. When the Auto-update toggle is ON (default) and the
// user has granted "Install unknown apps", the session commits with
// USER_ACTION_NOT_REQUIRED → the update installs with no tap. Without the grant
// Android transparently falls back to a prompt (routed via CommsInstallReceiver),
// so nothing breaks; the toggle OFF forces the prompt on purpose. Mirrors the
// Kotlin constellation apps' AutoUpdatePrefs + UpdateInstaller.

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CommsInstaller {
    private static final String TAG = "CommsInstall";

    // comms: runtime Auto-update toggle. Default ON — declared in
    // build.json::forks.mail...install_mode="silent". User-overridable in
    // Configs → About → Cloud Mail.
    static final String PREF_AUTO_SILENT = "comms_auto_update_silent";

    static boolean autoSilent(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_AUTO_SILENT, true);
    }

    static void setAutoSilent(Context ctx, boolean on) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(PREF_AUTO_SILENT, on).apply();
    }

    /** Whether the OS will let us install without a prompt — the "install
     *  unknown apps" special access. USER_ACTION_NOT_REQUIRED silently degrades
     *  to a prompt without it, so this drives the About grant-row status. */
    static boolean canInstallSilently(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ctx.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Install [apk] via PackageInstaller. When [silent] the session asks for
     * USER_ACTION_NOT_REQUIRED (no dialog) — Android honours it only once the
     * "install unknown apps" grant is held, else it emits STATUS_PENDING_USER_
     * ACTION which CommsInstallReceiver turns back into the normal prompt.
     */
    static void install(Context ctx, File apk, boolean silent) throws Exception {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            params.setRequireUserAction(silent
                    ? PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    : PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            // Android 14/15 ECM restricts apps installed without a package
            // source — our updater IS Cloud Mail's app store.
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);

        reapStaleSessions(installer);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                piFlags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId,
                    new Intent(ctx, CommsInstallReceiver.class).setPackage(ctx.getPackageName()),
                    piFlags);
            session.commit(pending.getIntentSender());
        } catch (Throwable t) {
            // close() on the try-with-resources session only releases OUR handle.
            // The session itself stays alive in the system until it is committed
            // or abandoned, and survives reboots in
            // /data/system/install_sessions.xml. So every failed install burned
            // one of the 50 slots Android allows an installer without
            // INSTALL_PACKAGES, permanently, until installs died with "Too many
            // active sessions for UID <uid>". Abandon on the way out; the throw
            // still propagates so the caller reports failure exactly as before.
            try {
                installer.abandonSession(sessionId);
            } catch (Throwable ignored) {
                Log.w(TAG + " could not abandon session " + sessionId);
            }
            throw t;
        }
        Log.i(TAG + " committed session " + sessionId + " silent=" + silent);
    }

    /**
     * Abandon our own leftover sessions before opening a new one.
     *
     * Android caps an installer that lacks INSTALL_PACKAGES (signature|privileged
     * - a sideloaded APK can never hold it) at 50 concurrent sessions, then
     * refuses every new one. The catch above stops NEW leaks; this clears what a
     * device already carries, so an affected phone heals itself on the next
     * install instead of needing `pm install-abandon` over adb.
     *
     * Only sessions we own are visible here (getMySessions), so this can never
     * disturb another installer's work.
     */
    private static void reapStaleSessions(PackageInstaller installer) {
        List<PackageInstaller.SessionInfo> mine;
        try {
            mine = installer.getMySessions();
        } catch (Throwable t) {
            return;
        }
        if (mine == null || mine.isEmpty())
            return;

        // Never touch a session a client currently has open - that is a real
        // install in flight. Everything else is ours to reclaim.
        List<PackageInstaller.SessionInfo> idle = new ArrayList<>();
        for (PackageInstaller.SessionInfo info : mine)
            if (!info.isActive())
                idle.add(info);
        if (idle.isEmpty())
            return;

        List<PackageInstaller.SessionInfo> victims = new ArrayList<>();
        if (mine.size() >= NEAR_CAP) {
            // AT THE CAP the cap IS the problem: createSession fails outright,
            // so waiting for sessions to age out just means more failed
            // installs. Oldest first, freeing enough slots to work in and
            // keeping the newest, which is the one the user is most likely
            // looking at. Losing a pending prompt is recoverable; a
            // permanently stuck installer is not.
            Collections.sort(idle, new Comparator<PackageInstaller.SessionInfo>() {
                @Override
                public int compare(PackageInstaller.SessionInfo a, PackageInstaller.SessionInfo b) {
                    return Long.compare(createdAt(a), createdAt(b));
                }
            });
            int free = idle.size() - (NEAR_CAP - HEADROOM);
            if (free < 1)
                free = 1;
            victims.addAll(idle.subList(0, Math.min(free, idle.size())));
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Below the cap there is no urgency, so only reclaim sessions old
            // enough that they cannot still be a prompt awaiting an answer.
            long now = System.currentTimeMillis();
            for (PackageInstaller.SessionInfo info : idle)
                if (now - info.getCreatedMillis() > STALE_SESSION_MS)
                    victims.add(info);
        }
        // else: getCreatedMillis is API 29+; with no age to test, do nothing
        // until the at-cap branch above takes over.

        int reaped = 0;
        for (PackageInstaller.SessionInfo info : victims) {
            try {
                installer.abandonSession(info.getSessionId());
                reaped++;
            } catch (Throwable ignored) {
            }
        }
        if (reaped > 0)
            Log.w(TAG + " abandoned " + reaped + " of " + mine.size() + " install session(s)");
    }

    private static long createdAt(PackageInstaller.SessionInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? info.getCreatedMillis() : 0L;
    }

    /** Android's own limit for an installer without INSTALL_PACKAGES is 50. */
    private static final int NEAR_CAP = 40;
    /** Slots to free when we are already at the cap. */
    private static final int HEADROOM = 8;
    private static final long STALE_SESSION_MS = 60L * 60L * 1000L;
}
