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

// comms: self-updater — checks the GitHub Releases API for a new Cloud Mail
// build every 6h and on every app open (throttled to 1h). Detects updates by
// comparing the build stamp embedded in the release TAG (cloud-comms-mail-
// YYYYMMDD.HHMMSS) against BuildConfig.COMMS_BUILD_TIMESTAMP — CI computes ONE
// BUILD_DATE and uses it for both, so an APK exactly equals its own release
// (published_at would be minutes later than the build → self-update loop).
// Downloads directly from the release asset URL — no GHCR token needed.
//
// Wire: ApplicationEx calls init(this) after WorkerAutoUpdate.init(this).
// Data-driven: all GH coords come from BuildConfig fields injected by
// build.json::forks.mail.build.gradle_props via the build engine.
// Static helpers (fetchLatestRelease, downloadApk) are package-private so
// FragmentOptionsCommsAbout can run an inline foreground check without WorkManager.

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CommsUpdateWorker extends Worker {
    private static final String TAG = "CommsUpdate";
    private static final long INTERVAL_HOURS = 6;
    private static final String WORK_NAME = "CommsUpdateWorker";
    private static final String WORK_NAME_NOW = "CommsUpdateWorkerNow";
    private static final int NOTIFICATION_ID = 99001;
    // comms: GitHub releases Atom — all ea_* apps publish releases here on CI
    private static final String RELEASES_FEED_URL =
            "https://github.com/diegonmarcos/cloud-unix/releases.atom";
    private static final String RSS_ACCOUNT_UUID = "cloud-comms-rss-account";

    // comms: user-overridable installed stamp (About → Update channel override)
    static final String PREF_STAMP_OVERRIDE = "comms_build_stamp_override";

    public CommsUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Effective installed build stamp: the user's override pref wins over the
     * CI-baked BuildConfig value. Lets the user re-point a bad baked stamp
     * without reinstalling — set "dev" (or any old stamp) to force an update,
     * a future stamp to freeze, empty to use the baked stamp again.
     */
    static String installedStamp(Context ctx) {
        String override = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(PREF_STAMP_OVERRIDE, "");
        return TextUtils.isEmpty(override) ? BuildConfig.COMMS_BUILD_TIMESTAMP : override;
    }

    /**
     * "dev" = ALWAYS outdated: a dev-stamped build must be able to pull the
     * newest release. The pre-0047 guard inverted this ("dev" → up to date),
     * which left every dev-baked install permanently update-blind.
     * Otherwise YYYYMMDD.HHMMSS stamps compare lexicographically in time order.
     */
    static boolean isOutdated(String installed, String remote) {
        return "dev".equals(installed) || remote.compareTo(installed) > 0;
    }

    /** Metadata returned by the GitHub Releases API for the latest release. */
    static class ReleaseMeta {
        final String buildStamp;   // YYYYMMDD.HHMMSS from the tag — same string CI bakes into COMMS_BUILD_TIMESTAMP
        final String downloadUrl;  // browser_download_url of the matching APK asset
        ReleaseMeta(String buildStamp, String downloadUrl) {
            this.buildStamp = buildStamp;
            this.downloadUrl = downloadUrl;
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            Log.i(TAG + " checking GitHub Releases for update");
            ReleaseMeta rel = fetchLatestRelease();
            String installed = installedStamp(ctx);
            if (!isOutdated(installed, rel.buildStamp)) {
                Log.i(TAG + " up to date (installed=" + installed
                        + " remote=" + rel.buildStamp + ")");
                return Result.success();
            }
            Log.i(TAG + " update available: remote=" + rel.buildStamp
                    + " > installed=" + installed);
            // comms: always surface the notification (the "message"), then —
            // when Auto-update is ON (default) — download + install silently
            // in the background with no user tap. OFF leaves it at the
            // notification so the user installs manually from About.
            notifyUpdate(ctx);
            if (CommsInstaller.autoSilent(ctx)) {
                File apk = new File(ctx.getCacheDir(), "updates/cloud-mail-update.apk");
                downloadApk(rel.downloadUrl, apk);
                CommsInstaller.install(ctx, apk, true);
            }
            return Result.success();
        } catch (Throwable ex) {
            Log.w(ex);
            return Result.retry();
        }
    }

    // ── GitHub Releases helpers — package-private for FragmentOptionsCommsAbout ──

    /**
     * Fetches the newest Cloud Mail GitHub Release and returns its tag build
     * stamp + APK asset URL. Public repo — no auth needed.
     *
     * NOT /releases/latest: the unix repo's release space is shared by many
     * artifacts (qute-standalone, cloud-terminal, waydroid-apks, ...) and any
     * of them can hold the repo-wide "Latest" badge — which made this method
     * throw "asset not found in release qute-standalone-latest". Instead we
     * list releases and pick the highest build stamp among OUR tag prefix
     * (cloud-comms-mail-YYYYMMDD.HHMMSS, derived from the asset name).
     * Throws if no release with our prefix carries the APK asset.
     */
    static ReleaseMeta fetchLatestRelease() throws Exception {
        // "cloud-comms-mail.apk" → tag prefix "cloud-comms-mail-"
        String tagPrefix = BuildConfig.COMMS_RELEASE_ASSET_NAME
                .replace(".apk", "") + "-";
        String url = "https://api.github.com/repos/"
                + BuildConfig.COMMS_GH_OWNER + "/" + BuildConfig.COMMS_GH_REPO
                + "/releases?per_page=30";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            if (c.getResponseCode() != 200)
                throw new IOException("GitHub releases HTTP " + c.getResponseCode());
            JSONArray rels = new JSONArray(slurp(c.getInputStream()));
            ReleaseMeta best = null;
            for (int r = 0; r < rels.length(); r++) {
                JSONObject rel = rels.getJSONObject(r);
                String tag = rel.getString("tag_name");
                if (!tag.startsWith(tagPrefix))
                    continue;
                // tag = cloud-comms-<fork>-YYYYMMDD.HHMMSS → stamp after last '-'
                String buildStamp = tag.substring(tag.lastIndexOf('-') + 1);
                if (best != null && buildStamp.compareTo(best.buildStamp) <= 0)
                    continue;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (BuildConfig.COMMS_RELEASE_ASSET_NAME.equals(asset.getString("name"))) {
                        best = new ReleaseMeta(buildStamp, asset.getString("browser_download_url"));
                        break;
                    }
                }
            }
            if (best == null)
                throw new IOException("no release with tag prefix '" + tagPrefix
                        + "' carrying asset '" + BuildConfig.COMMS_RELEASE_ASSET_NAME + "'");
            return best;
        } finally {
            c.disconnect();
        }
    }

    /**
     * Downloads the APK from a GitHub release asset browser_download_url to dest.
     * Follows the S3 redirect that GitHub always returns.
     */
    static void downloadApk(String url, File dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        c.setInstanceFollowRedirects(false); // handle manually to avoid mixed-HTTP→HTTPS issues
        try {
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                c = (HttpURLConnection) new URL(location).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(120_000);
                code = c.getResponseCode();
            }
            if (code != 200) throw new IOException("download HTTP " + code);
            dest.getParentFile().mkdirs();
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    // ── notification (background worker → user taps to open update UI) ──────

    private void notifyUpdate(Context ctx) {
        // Deep-link straight to the Cloud Mail About tab (the update UI), not the
        // settings root. ActivitySetup with no "target" extra shows FragmentOptions,
        // which reads the "tab" extra and selects that page — "comms_about" is the
        // About tab's TAB_LABELS id. setAction keeps this PendingIntent distinct
        // from other ActivitySetup ones under FLAG_UPDATE_CURRENT (FairEmail pattern).
        Intent intent = new Intent(ctx, ActivitySetup.class)
                .setAction("comms_about")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("tab", "comms_about");
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(ctx, "update")
                .setSmallIcon(R.drawable.twotone_update_24)
                .setContentTitle(ctx.getString(R.string.title_comms_update_available))
                .setContentText(ctx.getString(R.string.title_comms_update_tap))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notif);
    }

    // ── releases feed seeder ─────────────────────────────────────────────────

    // comms: auto-subscribe to the GitHub releases Atom feed so all ea_* app
    // release entries appear in the RSS inbox without any manual setup.
    // Idempotent — skips if the feed already exists.
    static void seedReleaseFeed(final Context ctx) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // CommsAccounts owns the Feeds account + feed-folder writes.
                    // The previous hand-rolled EntityAccount here violated the
                    // @NonNull host/user/password/port columns → the insert threw
                    // and the catch below swallowed it, so the feed never seeded
                    // on installs where the Feeds account didn't already exist.
                    long id = CommsAccounts.createRssFeed(
                            DB.getInstance(ctx), RELEASES_FEED_URL, "Cloud Apps — Releases");
                    Log.i(TAG + " releases feed " + (id < 0 ? "already present" : "seeded id=" + id));
                } catch (Throwable ex) {
                    Log.w(TAG + " seedReleaseFeed " + ex);
                }
            }
        }, "CommsReleaseFeedSeeder").start();
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    static void init(Context context) {
        try {
            PeriodicWorkRequest request =
                    new PeriodicWorkRequest.Builder(CommsUpdateWorker.class,
                            INTERVAL_HOURS, TimeUnit.HOURS)
                            .setConstraints(new Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Throwable ex) {
            Log.w(ex);
        }
        seedReleaseFeed(context); // comms: ensure releases atom is in RSS inbox
    }

    // ponytail: checkNow kept for completeness but no longer called by the About button
    static void checkNow(Context context) {
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CommsUpdateWorker.class)
                    .setConstraints(new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build();
            WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME_NOW, ExistingWorkPolicy.REPLACE, request);
        } catch (Throwable ex) {
            Log.w(ex);
        }
    }

    private static String slurp(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
