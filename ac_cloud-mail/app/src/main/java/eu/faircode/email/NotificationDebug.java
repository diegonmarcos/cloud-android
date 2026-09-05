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

    Copyright 2018-2025 by Marcel Bokhorst (M66B)
*/

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import com.diegonmarcos.superapp.devtools.AppDebugServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;

// comms: read-only diagnostics served over the loopback debug server.
//
// The persistent-notification bug has burned three fix rounds because every
// theory was argued from code, while the only facts that matter live inside
// NotificationManagerService on the device: which channels exist, at what
// importance the system actually stored them, and which notifications are
// genuinely posted (with which flags, on which channel). This endpoint asks
// the system directly, so the next diagnosis starts from evidence:
//
//   GET /api/mail/notifications
//
// It mutates nothing.
class NotificationDebug {

    private NotificationDebug() {
    }

    static void register(Context context) {
        final Context app = context.getApplicationContext();
        AppDebugServer.INSTANCE.route(
                "mail",
                Collections.singletonList(new AppDebugServer.Op(
                        "notifications",
                        "",
                        "notification channels and active notifications exactly as the system reports them, read-only")),
                (op, query) -> "notifications".equals(op) ? dump(app) : null);
    }

    static String dump(Context context) {
        try {
            NotificationManager nm = Helper.getSystemService(context, NotificationManager.class);

            JSONObject result = new JSONObject();
            result.put("sdk", Build.VERSION.SDK_INT);
            result.put("notificationsEnabled", NotificationHelper.areNotificationsEnabled(nm));

            JSONArray channels = new JSONArray();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                for (NotificationChannel channel : nm.getNotificationChannels()) {
                    JSONObject jchannel = new JSONObject();
                    jchannel.put("id", channel.getId());
                    jchannel.put("name", String.valueOf(channel.getName()));
                    jchannel.put("importance", channel.getImportance());
                    jchannel.put("lockscreenVisibility", channel.getLockscreenVisibility());
                    jchannel.put("group", channel.getGroup());
                    channels.put(jchannel);
                }

                // The two direct verdicts the last fix round needed and could not get:
                // was the legacy channel really deleted, and did the replacement
                // actually stick at IMPORTANCE_NONE (0)?
                NotificationChannel legacy =
                        nm.getNotificationChannel(NotificationHelper.CHANNEL_SERVICE_LEGACY);
                result.put("legacyServiceChannelPresent", legacy != null);
                if (legacy != null)
                    result.put("legacyServiceChannelImportance", legacy.getImportance());

                NotificationChannel service =
                        nm.getNotificationChannel(NotificationHelper.CHANNEL_SERVICE);
                result.put("serviceChannelPresent", service != null);
                if (service != null)
                    result.put("serviceChannelImportance", service.getImportance());
            }
            result.put("channels", channels);

            // Only this app's own notifications are visible here, which is the
            // point: if the persistent row is absent from this list while the user
            // still sees one, the row is the system's (OEM foreground-service
            // surfacing), not ours, and no app-side change can remove it.
            JSONArray active = new JSONArray();
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                Notification notification = sbn.getNotification();
                JSONObject jnotification = new JSONObject();
                jnotification.put("id", sbn.getId());
                jnotification.put("tag", sbn.getTag());
                jnotification.put("postTime", sbn.getPostTime());
                jnotification.put("ongoing", sbn.isOngoing());
                jnotification.put("flags", notification.flags);
                jnotification.put("flagForegroundService",
                        (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
                jnotification.put("flagOngoingEvent",
                        (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
                jnotification.put("flagNoClear",
                        (notification.flags & Notification.FLAG_NO_CLEAR) != 0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    jnotification.put("channel", notification.getChannelId());
                jnotification.put("category", notification.category);
                jnotification.put("visibility", notification.visibility);
                jnotification.put("group", notification.getGroup());
                if (notification.extras != null) {
                    CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (title != null)
                        jnotification.put("title", title.toString());
                    if (text != null)
                        jnotification.put("text", text.toString());
                }
                active.put(jnotification);
            }
            result.put("active", active);

            return result.toString();
        } catch (Throwable ex) {
            Log.e(ex);
            return "{\"error\":" + JSONObject.quote(String.valueOf(ex)) + "}";
        }
    }
}
