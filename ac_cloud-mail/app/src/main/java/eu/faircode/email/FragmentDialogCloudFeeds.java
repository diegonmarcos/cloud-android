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

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

// comms: "Cloud Feeds" dialog — fetches /feed/profiles.json from rss.diegonmarcos.com,
// shows a checkbox list of profiles, and creates one RSS folder per selected profile.
// Token is stored in SharedPreferences (comms_cloud_token) so WorkerFeedSync can inject
// it as Authorization: Bearer on subsequent polls.
public class FragmentDialogCloudFeeds extends FragmentDialogBase {

    private static final String PREF_TOKEN    = "comms_cloud_token";
    private static final String PREF_BASE     = "comms_cloud_base_url";
    private static final String PREF_CHANNELS = "comms_cloud_channels";
    private static final int    FETCH_TIMEOUT = 15_000;

    private EditText etBaseUrl;
    private EditText etToken;
    private CheckBox cbAllChannels;
    private LinearLayout profilesContainer;
    private final List<String[]> loadedProfiles = new ArrayList<>(); // [id, title, feed_path]

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context context = getContext();
        View dview = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_feeds, null);

        etBaseUrl         = dview.findViewById(R.id.etCloudBaseUrl);
        etToken           = dview.findViewById(R.id.etCloudToken);
        Button btnLoad    = dview.findViewById(R.id.btnLoadProfiles);
        cbAllChannels     = dview.findViewById(R.id.cbAllChannels);
        profilesContainer = dview.findViewById(R.id.profilesContainer);

        // Pre-fill from stored preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String savedBase  = prefs.getString(PREF_BASE,  BuildConfig.COMMS_RSS_BASE);
        String savedToken = prefs.getString(PREF_TOKEN, "");
        etBaseUrl.setText(savedBase);
        etToken.setText(savedToken);
        if (cbAllChannels != null)
            cbAllChannels.setChecked(prefs.getBoolean(PREF_CHANNELS, false));

        btnLoad.setOnClickListener(v -> loadProfiles());

        return new AlertDialog.Builder(context)
                .setTitle(R.string.title_comms_cloud_feeds)
                .setView(dview)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String base  = etBaseUrl.getText().toString().trim();
                    String token = etToken.getText().toString().trim();
                    if (TextUtils.isEmpty(base)) {
                        ToastEx.makeText(context, R.string.title_comms_cloud_base_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Whole-tree subscription (SUPER RSS READER) supersedes the
                    // per-profile picker: provision every channel as a folder tree.
                    if (cbAllChannels != null && cbAllChannels.isChecked()) {
                        subscribeChannelTree(base, token);
                        return;
                    }

                    // Collect selected profiles
                    List<String[]> selected = new ArrayList<>();
                    for (int i = 0; i < profilesContainer.getChildCount(); i++) {
                        View child = profilesContainer.getChildAt(i);
                        if (child instanceof CheckBox) {
                            CheckBox cb = (CheckBox) child;
                            if (cb.isChecked()) {
                                String[] meta = (String[]) cb.getTag();
                                selected.add(meta); // [id, title, feed_path]
                            }
                        }
                    }

                    if (selected.isEmpty()) {
                        ToastEx.makeText(context, R.string.title_comms_cloud_no_selection, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Bundle args = new Bundle();
                    args.putString("base", base);
                    args.putString("token", token);
                    String[] ids    = new String[selected.size()];
                    String[] titles = new String[selected.size()];
                    String[] feeds  = new String[selected.size()];
                    for (int i = 0; i < selected.size(); i++) {
                        ids[i]    = selected.get(i)[0];
                        titles[i] = selected.get(i)[1];
                        feeds[i]  = selected.get(i)[2];
                    }
                    args.putStringArray("ids",    ids);
                    args.putStringArray("titles", titles);
                    args.putStringArray("feeds",  feeds);

                    new SimpleTask<Integer>() {
                        @Override
                        protected Integer onExecute(Context ctx, Bundle a) throws Throwable {
                            String b     = a.getString("base");
                            String tok   = a.getString("token");
                            String[] fs  = a.getStringArray("feeds");
                            String[] ts  = a.getStringArray("titles");
                            int created  = 0;

                            // Persist token + base for WorkerFeedSync
                            PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                                    .putString(PREF_TOKEN, tok)
                                    .putString(PREF_BASE,  b)
                                    .apply();

                            DB db = DB.getInstance(ctx);
                            try {
                                db.beginTransaction();
                                for (int i = 0; i < fs.length; i++) {
                                    String feedUrl = b + fs[i];
                                    long id = CommsAccounts.createCloudProfileFeed(db, feedUrl, ts[i]);
                                    if (id >= 0) created++;
                                }
                                db.setTransactionSuccessful();
                            } finally {
                                db.endTransaction();
                            }
                            WorkerFeedSync.init(ctx);
                            return created;
                        }

                        @Override
                        protected void onExecuted(Bundle a, Integer count) {
                            Context ctx = getContext();
                            if (ctx != null)
                                ToastEx.makeText(ctx,
                                        ctx.getString(R.string.title_comms_cloud_feeds_added, count),
                                        Toast.LENGTH_LONG).show();
                        }

                        @Override
                        protected void onException(Bundle a, Throwable ex) {
                            try {
                                Log.unexpectedError(getParentFragmentManager(), ex);
                            } catch (Throwable ignored) { }
                        }
                    }.execute(FragmentDialogCloudFeeds.this, args, "comms:cloud-feeds-create");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    // SUPER RSS READER — fetch <base>/channels.json and provision the whole
    // hierarchical folder tree in one shot (CommsAccounts.ensureFeedTree), then
    // set comms_cloud_channels so WorkerFeedSync keeps it in sync every run.
    private void subscribeChannelTree(String base, String token) {
        Bundle args = new Bundle();
        args.putString("base", base);
        args.putString("token", token);

        new SimpleTask<Integer>() {
            @Override
            protected Integer onExecute(Context ctx, Bundle a) throws Throwable {
                String b   = a.getString("base");
                String tok = a.getString("token");

                PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(PREF_TOKEN, tok)
                        .putString(PREF_BASE, b)
                        .putBoolean(PREF_CHANNELS, true)
                        .apply();

                String url = b.endsWith("/") ? b + "channels.json" : b + "/channels.json";
                HttpURLConnection conn = ConnectionHelper.openConnectionUnsafe(
                        ctx, url, FETCH_TIMEOUT, FETCH_TIMEOUT);
                if (!TextUtils.isEmpty(tok))
                    conn.setRequestProperty("Authorization", "Bearer " + tok);

                JSONObject root;
                try (InputStream is = conn.getInputStream()) {
                    root = new JSONObject(new String(Helper.readBytes(is), "UTF-8"));
                } finally {
                    conn.disconnect();
                }

                JSONArray arr = root.optJSONArray("channels");
                List<CommsAccounts.CommsChannel> channels = new ArrayList<>();
                if (arr != null)
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        channels.add(new CommsAccounts.CommsChannel(
                                o.optString("id"), o.optString("title"),
                                o.optString("path"), o.optString("feed")));
                    }

                DB db = DB.getInstance(ctx);
                int n;
                try {
                    db.beginTransaction();
                    n = CommsAccounts.ensureFeedTree(ctx, db, channels, root.optString("base", b));
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                WorkerFeedSync.init(ctx);
                WorkerFeedSync.oneShot(ctx); // fill the tree now, don't wait 6h
                return n;
            }

            @Override
            protected void onExecuted(Bundle a, Integer count) {
                Context ctx = getContext();
                if (ctx != null)
                    ToastEx.makeText(ctx,
                            ctx.getString(R.string.title_comms_cloud_feeds_added, count),
                            Toast.LENGTH_LONG).show();
            }

            @Override
            protected void onException(Bundle a, Throwable ex) {
                try {
                    Log.unexpectedError(getParentFragmentManager(), ex);
                } catch (Throwable ignored) { }
            }
        }.execute(this, args, "comms:cloud-channels-tree");
    }

    private void loadProfiles() {
        String base  = etBaseUrl.getText().toString().trim();
        String token = etToken.getText().toString().trim();
        if (TextUtils.isEmpty(base)) {
            ToastEx.makeText(getContext(), R.string.title_comms_cloud_base_required, Toast.LENGTH_SHORT).show();
            return;
        }

        Bundle args = new Bundle();
        args.putString("base",  base);
        args.putString("token", token);

        new SimpleTask<List<String[]>>() {
            @Override
            protected List<String[]> onExecute(Context ctx, Bundle a) throws Throwable {
                String b   = a.getString("base");
                String tok = a.getString("token");
                String url = b + "/profiles.json";

                HttpURLConnection conn = ConnectionHelper.openConnectionUnsafe(
                        ctx, url, FETCH_TIMEOUT, FETCH_TIMEOUT);
                if (!TextUtils.isEmpty(tok))
                    conn.setRequestProperty("Authorization", "Bearer " + tok);

                List<String[]> result = new ArrayList<>();
                try (InputStream is = conn.getInputStream()) {
                    byte[] bytes = Helper.readBytes(is);
                    JSONObject root = new JSONObject(new String(bytes, "UTF-8"));
                    JSONArray profiles = root.optJSONArray("profiles");
                    if (profiles != null) {
                        for (int i = 0; i < profiles.length(); i++) {
                            JSONObject p = profiles.getJSONObject(i);
                            String id    = p.optString("id", "");
                            String title = p.optString("title", id);
                            String feed  = p.optString("feed", "/feed/" + id + ".atom");
                            // Build channel summary for subtitle
                            JSONArray chs = p.optJSONArray("channels");
                            StringBuilder sb = new StringBuilder();
                            if (chs != null) {
                                for (int j = 0; j < chs.length() && j < 4; j++) {
                                    if (j > 0) sb.append(", ");
                                    sb.append(chs.getJSONObject(j).optString("name", ""));
                                }
                                if (chs.length() > 4) sb.append(" +").append(chs.length() - 4);
                            }
                            result.add(new String[]{id, title, feed, sb.toString()});
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                return result;
            }

            @Override
            protected void onExecuted(Bundle a, List<String[]> profiles) {
                loadedProfiles.clear();
                loadedProfiles.addAll(profiles);
                populateProfiles(profiles);
            }

            @Override
            protected void onException(Bundle a, Throwable ex) {
                Context ctx = getContext();
                if (ctx != null)
                    ToastEx.makeText(ctx,
                            ctx.getString(R.string.title_comms_cloud_load_error, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
            }
        }.execute(this, args, "comms:cloud-feeds-load");
    }

    private void populateProfiles(List<String[]> profiles) {
        profilesContainer.removeAllViews();
        Context ctx = getContext();
        if (ctx == null) return;

        for (String[] p : profiles) {
            // id=p[0], title=p[1], feed=p[2], channelSummary=p[3]
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 8, 0, 8);

            CheckBox cb = new CheckBox(ctx);
            cb.setText(p[1]);
            cb.setTag(new String[]{p[0], p[1], p[2]});
            cb.setChecked(true);
            row.addView(cb);

            if (!TextUtils.isEmpty(p[3])) {
                TextView tv = new TextView(ctx);
                tv.setText(p[3]);
                tv.setTextSize(12);
                tv.setPadding(48, 0, 0, 0);
                row.addView(tv);
            }
            profilesContainer.addView(row);
        }

        if (profiles.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(R.string.title_comms_cloud_no_profiles);
            profilesContainer.addView(tv);
        }
    }
}
