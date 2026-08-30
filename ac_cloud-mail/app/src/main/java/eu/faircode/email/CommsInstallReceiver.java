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

// comms: PackageInstaller status callback for CommsInstaller. On a silent
// session where the "install unknown apps" grant is missing, Android returns
// STATUS_PENDING_USER_ACTION with a confirmation Intent. When the update came
// from the FOREGROUND (About → Check for updates) we can launch it directly.
// But the 6h background auto-update runs in a WorkManager job, and Android 10+
// BLOCKS background activity starts → startActivity silently no-ops and the
// update dies invisibly ("auto update not working"). So we ALSO post a
// high-priority notification wrapping the confirm Intent as a PendingIntent —
// notifications can launch activities from the background. Registered
// exported=false in the manifest.

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class CommsInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "CommsInstall";
    private static final String CHANNEL = "update";
    private static final int NOTIF_ID = 99002;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null)
                return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Foreground path: launch the system installer directly.
            boolean launched = false;
            try {
                ctx.startActivity(confirm);
                launched = true;
            } catch (Throwable ex) {
                Log.w(TAG + " direct confirm launch blocked (background?): " + ex);
            }
            // Background-safe fallback: a tap-to-install notification. Harmless
            // if the direct launch already worked (user just dismisses it).
            if (!launched)
                notifyConfirm(ctx, confirm);
        } else {
            Log.i(TAG + " status=" + status + (message == null ? "" : " msg=" + message));
        }
    }

    private void notifyConfirm(Context ctx, Intent confirm) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null)
                return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH));
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, NOTIF_ID, confirm, flags);
            Notification n = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.twotone_update_24)
                    .setContentTitle(ctx.getString(R.string.title_comms_update_available))
                    .setContentText(ctx.getString(R.string.title_comms_update_tap))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Throwable ex) {
            Log.w(TAG + " notifyConfirm " + ex);
        }
    }
}
