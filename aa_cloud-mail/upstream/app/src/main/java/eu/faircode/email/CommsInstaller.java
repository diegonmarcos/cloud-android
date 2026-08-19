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
        }
        Log.i(TAG + " committed session " + sessionId + " silent=" + silent);
    }
}
