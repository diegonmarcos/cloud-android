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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

// Notification dismissal (swipe away) only.
// This used to be routed through ServiceUI, but a delete intent starting a background
// service is not reliably delivered (restricted app standby bucket, OEM battery managers,
// https://issuetracker.google.com/issues/159152393). When it is dropped, ui_ignored is
// never set, and the next boot's clearNotifyingMessages() re-posts the notification the
// user already swiped away. A manifest receiver is not subject to those restrictions.
public class ReceiverUI extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("Receiver UI intent=" + intent);

        if (intent == null)
            return;

        final String action = intent.getAction();
        if (action == null)
            return;

        final Context ctx = context.getApplicationContext();
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] parts = action.split(":");
                    long id = (parts.length > 1 ? Long.parseLong(parts[1]) : -1);

                    switch (parts[0]) {
                        case "clear":
                            clear(ctx, id);
                            break;

                        case "ignore":
                            ignore(ctx, id);
                            break;

                        default:
                            Log.w("Unknown UI action=" + parts[0]);
                    }
                } catch (Throwable ex) {
                    Log.e(ex);
                } finally {
                    result.finish();
                }
            }
        }, "ui-dismiss").start();
    }

    // Summary notification swiped away: ignore every message it stood for
    static void clear(@NonNull Context context, long group) {
        // Group
        // < 0: folder
        // = 0: unified
        // > 0: account
        DB db = DB.getInstance(context);
        int cleared;
        if (group < 0)
            cleared = db.message().ignoreAll(null, -group, null);
        else
            cleared = db.message().ignoreAll(group == 0 ? null : group, null, null);
        EntityLog.log(context, EntityLog.Type.Notification,
                "Notify clear group=" + group + " cleared=" + cleared);
    }

    // Single notification swiped away
    static void ignore(@NonNull Context context, long id) {
        DB db = DB.getInstance(context);
        EntityMessage message = db.message().getMessage(id);
        if (message == null)
            return;
        db.message().setMessageUiIgnored(message.id, true);
        EntityLog.log(context, EntityLog.Type.Notification,
                "Notify ignore id=" + message.id);
    }
}
