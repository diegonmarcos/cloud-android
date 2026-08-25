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

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

// comms: periodic worker that fetches RSS/Atom feeds into their feed folders, modeled on WorkerCleanup
public class WorkerFeedSync extends Worker {
    private static final int FEED_INTERVAL = 6; // hours
    private static final int FETCH_TIMEOUT = 20 * 1000; // milliseconds

    public WorkerFeedSync(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        Log.i("Instance " + getName());
    }

    @NonNull
    @Override
    public Result doWork() {
        Thread.currentThread().setPriority(THREAD_PRIORITY_BACKGROUND);

        EntityLog.log(getApplicationContext(),
                "Running " + getName() +
                        " process=" + android.os.Process.myPid());

        sync(getApplicationContext());

        return Result.success();
    }

    private static void sync(Context context) {
        DB db = DB.getInstance(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // comms: (re)provision the SUPER RSS READER channel tree from the gateway
        // before polling, so channels added/removed server-side are reflected
        // each run. Offline-tolerant — a fetch failure leaves the existing tree.
        if (prefs.getBoolean("comms_cloud_channels", false)) {
            try {
                ensureChannelTree(context, db, prefs);
            } catch (Throwable ex) {
                Log.w("comms: channels.json provisioning failed", ex);
            }
        }

        List<EntityFolder> folders = db.folder().getFolders();
        if (folders == null)
            return;

        // With ~150 feed folders sharing one gateway host, a single unreachable
        // gateway must not cost 150×20s of sequential timeouts (WorkManager kills
        // the job at ~10 min). Once a host fails to connect, skip its remaining
        // feeds this run. ponytail: one HashSet, no parallelism.
        Set<String> deadHosts = new HashSet<>();
        long now = System.currentTimeMillis();
        for (EntityFolder folder : folders) {
            if (folder.feed_url == null)
                continue;
            // comms: per-feed poll interval. The worker ticks every FEED_INTERVAL
            // (6h); a feed's effective interval = FEED_INTERVAL * poll_factor
            // (Edit properties → poll factor, default 1). Skip feeds not yet due
            // so noisy channels can be slowed without a separate schedule.
            int factor = (folder.poll_factor == null) ? 1 : Math.max(1, folder.poll_factor);
            long effectiveMs = (long) FEED_INTERVAL * 3600_000L * factor;
            if (factor > 1 && folder.last_sync != null && now - folder.last_sync < effectiveMs)
                continue;
            String host = Uri.parse(folder.feed_url).getHost();
            if (host != null && deadHosts.contains(host))
                continue;
            try {
                syncFeed(context, db, folder);
                db.folder().setFolderLastSync(folder.id, now); // mark polled (drives the due-check)
            } catch (Throwable ex) {
                if (host != null && isConnectFailure(ex))
                    deadHosts.add(host);
                // One bad feed should not kill the whole run
                Log.w(folder.name + " feed error", ex);
                EntityLog.log(context, "Feed sync error" +
                        " folder=" + folder.name +
                        " url=" + folder.feed_url +
                        " ex=" + Log.formatThrowable(ex));
            }
        }
    }

    // Fetch the channel taxonomy from <base>/channels.json and (re)build the
    // folder tree. base pref = ".../feed" (same as the profiles flow); each
    // channel.feed is origin-relative ("/feed/c/<topic>.atom").
    private static void ensureChannelTree(Context context, DB db, SharedPreferences prefs) throws Throwable {
        // Default to the baked infra endpoint (BuildConfig.COMMS_RSS_BASE =
        // https://rss.diegonmarcos.com/feed) so the SUPER RSS READER tree
        // auto-provisions on first run WITHOUT the user opening Cloud Feeds and
        // typing our own URL. wg0-only + feed_auth=none → no token needed. A
        // user override (set via the Cloud Feeds dialog) still wins.
        String base = prefs.getString("comms_cloud_base_url", BuildConfig.COMMS_RSS_BASE);
        if (TextUtils.isEmpty(base))
            return;
        String url = base.endsWith("/") ? base + "channels.json" : base + "/channels.json";

        HttpURLConnection conn = ConnectionHelper.openConnectionUnsafe(
                context, url, FETCH_TIMEOUT, FETCH_TIMEOUT);
        String token = prefs.getString("comms_cloud_token", null);
        if (!TextUtils.isEmpty(token))
            conn.setRequestProperty("Authorization", "Bearer " + token);

        JSONObject root;
        try (InputStream is = conn.getInputStream()) {
            root = new JSONObject(new String(Helper.readBytes(is), "UTF-8"));
        } finally {
            conn.disconnect();
        }

        JSONArray arr = root.optJSONArray("channels");
        if (arr == null)
            return;
        List<CommsAccounts.CommsChannel> channels = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            channels.add(new CommsAccounts.CommsChannel(
                    o.optString("id"), o.optString("title"),
                    o.optString("path"), o.optString("feed")));
        }
        int n = CommsAccounts.ensureFeedTree(context, db, channels, root.optString("base", base));
        EntityLog.log(context, "comms: RSS channel tree provisioned channels=" + n);
    }

    private static boolean isConnectFailure(Throwable ex) {
        while (ex != null) {
            if (ex instanceof java.net.SocketTimeoutException
                    || ex instanceof java.net.ConnectException
                    || ex instanceof java.net.UnknownHostException
                    || ex instanceof java.net.NoRouteToHostException)
                return true;
            ex = ex.getCause();
        }
        return false;
    }

    private static void syncFeed(Context context, DB db, EntityFolder folder) throws Throwable {
        List<FeedParser.FeedItem> items;

        HttpURLConnection connection = null;
        try {
            connection = ConnectionHelper.openConnectionUnsafe(
                    context, folder.feed_url, FETCH_TIMEOUT, FETCH_TIMEOUT);
            // comms: cloud profile feeds (rss-gateway on rss.diegonmarcos.com/feed/) require
            // Bearer auth. Token is stored in SharedPreferences by FragmentDialogCloudFeeds.
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String cloudToken = prefs.getString("comms_cloud_token", null);
            if (cloudToken != null && !cloudToken.isEmpty()
                    && folder.feed_url != null
                    && folder.feed_url.contains("/feed/")) {
                connection.setRequestProperty("Authorization", "Bearer " + cloudToken);
            }
            try (InputStream is = connection.getInputStream()) {
                FeedParser.Feed feed = FeedParser.parse(is);
                items = feed.items;
                // comms: minimal input — when the user only pasted a URL (folder name
                // still equals the feed URL), auto-name the folder from the feed's own
                // <title> on first successful fetch.
                if (feed.title != null && !feed.title.isEmpty()
                        && folder.feed_url.equals(folder.name)) {
                    folder.name = feed.title;
                    db.folder().setFolderName(folder.id, feed.title);
                }
            }
        } finally {
            if (connection != null)
                connection.disconnect();
        }

        if (items == null)
            return;

        for (FeedParser.FeedItem item : items) {
            long uid = (long) item.guid.hashCode();

            // Dedupe: skip items already present in this folder
            if (db.message().getMessageByUid(folder.id, uid) != null)
                continue;

            String html = (item.html == null ? "" : item.html);
            String text = HtmlHelper.getText(context, html);

            EntityMessage message = new EntityMessage();
            message.account = folder.account;
            message.folder = folder.id;
            message.uid = uid;
            message.msgid = EntityMessage.generateMessageId();
            message.from = buildFrom(folder.name);
            message.subject = item.title;
            message.size = (long) html.length();
            message.total = message.size;
            message.content = false;
            message.received = item.date;
            message.sent = item.date;
            message.seen = false;
            message.ui_seen = false;
            message.preview = HtmlHelper.getPreview(text);

            try {
                db.beginTransaction();
                message.id = db.message().insertMessage(message);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            // Write the body file and mark content available (mirrors Core POP path)
            File file = message.getFile(context);
            Helper.writeText(file, html);
            db.message().setMessageContent(message.id,
                    true,
                    HtmlHelper.getLanguage(context, message.subject, text),
                    null,
                    message.preview,
                    null);

            Log.i(folder.name + " feed added uid=" + uid + " subject=" + item.title);
        }
    }

    private static Address[] buildFrom(String name) {
        try {
            // Synthetic sender = feed title; address kept stable per-feed
            return new Address[]{new InternetAddress("feed@localhost", name)};
        } catch (Throwable ex) {
            Log.w(ex);
            return null;
        }
    }

    static void init(Context context) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean enabled = prefs.getBoolean("enabled", true);
            if (enabled) {
                Log.i("Queuing " + getName() + " every " + FEED_INTERVAL + " hours");

                PeriodicWorkRequest workRequest =
                        new PeriodicWorkRequest.Builder(WorkerFeedSync.class, FEED_INTERVAL, TimeUnit.HOURS)
                                .setInitialDelay(FEED_INTERVAL, TimeUnit.HOURS)
                                .build();
                WorkManager.getInstance(context)
                        .enqueueUniquePeriodicWork(getName(), ExistingPeriodicWorkPolicy.KEEP, workRequest);

                Log.i("Queued " + getName());
            } else {
                Log.i("Cancelling " + getName());
                WorkManager.getInstance(context).cancelUniqueWork(getName());
                Log.i("Cancelled " + getName());
            }
        } catch (Throwable ex) {
            // https://issuetracker.google.com/issues/138465476
            Log.w(ex);
        }
    }

    private static String getName() {
        return WorkerFeedSync.class.getSimpleName();
    }

    // comms: immediate one-shot feed poll. Used by pull-to-refresh on a feed
    // folder (EntityOperation.sync short-circuit) and by the Cloud Feeds dialog
    // after (re)provisioning the channel tree, so content appears at once
    // instead of waiting for the next 6h periodic tick. KEEP: rapid triggers
    // coalesce into the one running poll.
    static void oneShot(Context context) {
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WorkerFeedSync.class).build();
            WorkManager.getInstance(context)
                    .enqueueUniqueWork(getName() + "Now", ExistingWorkPolicy.KEEP, request);
        } catch (Throwable ex) {
            Log.w(ex);
        }
    }
}
