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

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// comms (my-features): single source of truth for JMAP-account and RSS-feed DB writes.
// Reused by ImportConfigs (the JSON provisioner) AND the interactive config windows
// (FragmentDialogJmap / FragmentDialogRss). Lives in its own file so the write-path
// stays out of upstream FairEmail classes — keeps future upstream merges painless.
public class CommsAccounts {
    static final String RSS_ACCOUNT_UUID = "cloud-comms-rss-account";

    // Create a JMAP account + matching send identity. uuid==null → generate one.
    // password may be "" (JSON import defers it; the interactive window fills it).
    // Returns the new account id, or -1 if uuid already exists.
    static long createJmapAccount(DB db, String uuid,
                                  String displayName, String email, String user, String password,
                                  String host, int port, int encryption, boolean primary) {
        if (TextUtils.isEmpty(uuid))
            uuid = UUID.randomUUID().toString();
        if (db.account().getAccountByUUID(uuid) != null)
            return -1;
        if (password == null)
            password = "";

        EntityAccount account = new EntityAccount();
        account.uuid = uuid;
        account.protocol = EntityAccount.TYPE_JMAP;
        account.host = host;
        account.encryption = encryption;
        account.port = port;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        account.user = user;
        account.password = password;
        account.name = TextUtils.isEmpty(displayName) ? null : displayName;
        account.synchronize = true;
        account.primary = primary && account.synchronize;
        account.poll_interval = EntityAccount.DEFAULT_POLL_INTERVAL;
        account.created = System.currentTimeMillis();
        account.id = db.account().insertAccount(account);

        ensureJmapFolders(db, account.id);

        EntityIdentity identity = new EntityIdentity();
        identity.name = TextUtils.isEmpty(displayName) ? user : displayName;
        identity.email = TextUtils.isEmpty(email) ? user : email;
        identity.account = account.id;
        identity.host = host;
        identity.encryption = encryption;
        identity.port = port;
        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        identity.user = user;
        identity.password = password;
        identity.synchronize = true;
        identity.primary = account.primary;
        db.identity().insertIdentity(identity);

        return account.id;
    }

    // Create the standard JMAP folder set for an account if they don't exist yet.
    // Called on create, on edit AND once at app start (repairJmapFolders) so
    // existing accounts get folders — and folder settings — retroactively.
    // synchronize MUST be true: ServiceSynchronize gates the whole account on
    // SUM(folder.synchronize) > 0 — all-false means the JMAP monitor never
    // starts, silently (no connection, no error). That was the pre-batch-3
    // stub behaviour left behind by accident.
    static void ensureJmapFolders(DB db, long accountId) {
        String[][] types = {
            {EntityFolder.INBOX,  "Inbox"},
            {EntityFolder.SENT,   "Sent"},
            {EntityFolder.DRAFTS, "Drafts"},
            {EntityFolder.TRASH,  "Trash"},
            {EntityFolder.JUNK,   "Junk"},
        };
        for (String[] pair : types) {
            EntityFolder existing = db.folder().getFolderByType(accountId, pair[0]);
            if (existing != null) {
                if (!existing.synchronize)
                    db.folder().setFolderSynchronize(existing.id, true);
                continue;
            }
            // setProperties() via this constructor: synchronize=true for system
            // folders, unified+notify for inbox, sync/keep windows.
            EntityFolder folder = new EntityFolder(pair[1], pair[0]);
            folder.account = accountId;
            folder.poll = true; // JMAP is polled, never IDLE
            folder.local = false;
            folder.subscribed = true;
            folder.selectable = true;
            db.folder().insertFolder(folder);
        }
    }

    // One-shot startup repair (ApplicationEx): flip synchronize back on for
    // JMAP folders provisioned by pre-sync builds.
    static void repairJmapFolders(DB db) {
        List<EntityAccount> accounts = db.account().getAccounts();
        if (accounts != null)
            for (EntityAccount account : accounts)
                if (account.protocol == EntityAccount.TYPE_JMAP)
                    ensureJmapFolders(db, account.id);
    }

    // One-shot startup repair (ApplicationEx): pre-0053 installs created the
    // synthetic Feeds account with synchronize=false, so it (and every feed) was
    // invisible in the drawer/folder list. Flip it on retroactively + drop any
    // stale ops that accumulated on its folders while it was mis-flagged (those
    // would otherwise try to run against the hostless account). The log line is
    // a stable marker for the dex-level tester.
    static void repairRssAccount(DB db) {
        EntityAccount rss = db.account().getAccountByUUID(RSS_ACCOUNT_UUID);
        if (rss == null)
            return;
        if (!rss.synchronize)
            db.account().setAccountSynchronize(rss.id, true);
        if (!Boolean.TRUE.equals(rss.notify))
            db.account().setAccountNotify(rss.id, true);
        List<EntityFolder> folders = db.folder().getFolders(rss.id, false, false);
        if (folders != null)
            for (EntityFolder f : folders)
                // 0062: the 0056 "parity upgrade" (force notify/navigation back
                // on) ran on EVERY start, silently undoing per-channel choices.
                // Defaults are set at creation; existing flags are left alone.
                db.operation().deleteOperations(f.id, null);
        Log.i("comms:rss-repair Feeds account synchronize=true notify=true folders="
                + (folders == null ? 0 : folders.size()));
    }

    // Synthetic local "Feeds" account that owns every RSS folder. Created on first use.
    static EntityAccount ensureRssAccount(DB db) {
        EntityAccount rss = db.account().getAccountByUUID(RSS_ACCOUNT_UUID);
        if (rss != null)
            return rss;

        rss = new EntityAccount();
        rss.uuid = RSS_ACCOUNT_UUID;
        rss.protocol = EntityAccount.TYPE_RSS;
        rss.host = ""; // synthetic local account: host is @NonNull, so empty (not null)
        rss.encryption = EmailService.ENCRYPTION_NONE;
        rss.port = 0;
        rss.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        rss.user = "";
        rss.password = "";
        rss.name = "Feeds";
        // synchronize MUST be true or the Feeds account (and all its feed
        // folders) is invisible: every folder/drawer query filters on
        // account.synchronize (DaoFolder.liveFolders + ~10 siblings, DaoAccount
        // drawer). The sync ENGINES never touch it anyway — ServiceSynchronize
        // gates each account on SUM(folder.synchronize)>0 and feed folders keep
        // synchronize=false, so monitorAccount never starts an IMAP connect to
        // this hostless account (see also the TYPE_RSS guard in monitorAccount
        // and the RSS op short-circuits in EntityOperation). Polling is
        // WorkerFeedSync, entirely separate.
        rss.synchronize = true;
        rss.notify = true; // parity: feed folders can raise new-item notifications
        rss.primary = false;
        rss.poll_interval = EntityAccount.DEFAULT_POLL_INTERVAL;
        rss.created = System.currentTimeMillis();
        rss.id = db.account().insertAccount(rss);
        return rss;
    }

    // Create a cloud profile feed folder (from rss-gateway /feed/<profile>.atom).
    // Behaves identically to createRssFeed but explicitly named for clarity.
    // Returns new folder id, or -1 if a feed with this url already exists.
    static long createCloudProfileFeed(DB db, String feedUrl, String title) {
        return createRssFeed(db, feedUrl, title);
    }

    // Create an RSS/Atom feed folder under the synthetic Feeds account.
    // title may be empty → the url is used as the folder name.
    // Returns the new folder id, or -1 if a feed with this url already exists.
    static long createRssFeed(DB db, String url, String title) {
        EntityAccount rss = ensureRssAccount(db);

        List<EntityFolder> existing = db.folder().getFolders(rss.id, false, false);
        if (existing != null)
            for (EntityFolder f : existing)
                if (url.equals(f.feed_url))
                    return -1;

        EntityFolder folder = new EntityFolder();
        folder.account = rss.id;
        folder.name = TextUtils.isEmpty(title) ? url : title;
        folder.type = EntityFolder.USER;
        folder.feed_url = url;
        folder.local = true;
        folder.synchronize = false;
        folder.subscribed = true;
        folder.selectable = true;
        // parity with the channel-tree path (ensureLeafFolder): notify on new
        // items, show in the unified inbox, + show as a nav-menu shortcut, set
        // once at creation. Nothing re-forces these on later starts (see
        // repairRssAccount) — the user's own toggle choice sticks after this.
        folder.notify = true;
        folder.unified = true;
        folder.navigation = true;
        folder.sync_days = EntityFolder.DEFAULT_SYNC;
        folder.keep_days = EntityFolder.DEFAULT_KEEP;
        return db.folder().insertFolder(folder);
    }

    // ── SUPER RSS READER — hierarchical channel tree ──────────────────────────
    // A channel from the rss-gateway /feed/channels.json: `path` is the full
    // folder path (slash-separated, leaf included), `feed` the per-topic Atom
    // URL (relative or absolute). See build-ntfy-rss-channels.json (cloud side).
    static class CommsChannel {
        final String id, title, path, feed;
        CommsChannel(String id, String title, String path, String feed) {
            this.id = id; this.title = title; this.path = path; this.feed = feed;
        }
    }

    // Provision/refresh the whole feed tree from the server channel list.
    // Idempotent + declarative (server list = source of truth):
    //   - group folders are created for every path prefix (display = segment,
    //     selectable=false so they're pure containers);
    //   - leaf folders carry feed_url; identity is the FEED URL, so a renamed
    //     title or moved path updates in place and keeps its messages/read state;
    //   - folders whose feed_url came from THIS gateway but are no longer in the
    //     list are pruned (scoped by origin → never touches manually-added feeds
    //     or the GitHub releases seed).
    // folder.name = full path (satisfies the (account,name) unique index and
    // avoids leaf-label collisions like Logs/gitea vs Apps/gitea); the drawer
    // shows folder.display.
    static int ensureFeedTree(Context context, DB db, List<CommsChannel> channels, String baseUrl) {
        EntityAccount rss = ensureRssAccount(db);
        // one-shot 0062 migration: pre-existing group folders enter the nav
        // drawer as collapsed containers. Guarded by a pref so later user
        // choices (hide a group, keep it expanded) are never fought again.
        // The "done" flag is written at the very end of this method — NOT
        // here — so a later exception (channel provisioning below can throw)
        // leaves the flag unset and the migration retries next run, instead
        // of the DB writes rolling back (caller-owned transaction) while the
        // non-transactional SharedPreferences flag stays permanently true.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean migrate = !prefs.getBoolean("comms_nav_tree_0062", false);
        if (migrate) {
            List<EntityFolder> existing = db.folder().getFolders(rss.id, false, false);
            if (existing != null)
                for (EntityFolder f : existing)
                    if (f.feed_url == null && !Boolean.TRUE.equals(f.selectable)) {
                        db.folder().setFolderNavigation(f.id, true);
                        db.folder().setFolderCollapsed(f.id, true);
                    }
        }
        String origin = originOf(baseUrl);
        Set<String> liveFeeds = new HashSet<>();
        int n = 0;
        for (CommsChannel ch : channels) {
            if (ch == null || TextUtils.isEmpty(ch.path) || TextUtils.isEmpty(ch.feed))
                continue;
            String feedUrl = resolveFeed(origin, ch.feed);
            liveFeeds.add(feedUrl);

            String[] seg = ch.path.split("/");
            Long parent = null;
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < seg.length - 1; i++) {
                if (seg[i].isEmpty()) continue;
                if (prefix.length() > 0) prefix.append("/");
                prefix.append(seg[i]);
                parent = ensureGroupFolder(db, rss.id, prefix.toString(), seg[i], parent);
            }
            ensureLeafFolder(db, rss.id, ch.path, ch.title, feedUrl, parent);
            n++;
        }
        pruneFeedTree(db, rss.id, origin, liveFeeds);
        if (migrate)
            prefs.edit().putBoolean("comms_nav_tree_0062", true).apply();
        return n;
    }

    private static Long ensureGroupFolder(DB db, long account, String pathName, String label, Long parent) {
        EntityFolder f = db.folder().getFolderByName(account, pathName);
        if (f == null) {
            f = new EntityFolder();
            f.account = account;
            f.name = pathName;      // full path (unique per account)
            f.display = label;      // shown in the drawer
            f.type = EntityFolder.USER;
            f.parent = parent;
            f.local = true;
            f.synchronize = false;
            f.subscribed = true;
            f.selectable = false;   // group node — holds no messages
            // groups are the nav-drawer tree skeleton: shown, but collapsed by
            // default so the drawer stays compact until the user expands them
            f.navigation = true;
            f.collapsed = true;
            f.sync_days = EntityFolder.DEFAULT_SYNC;
            f.keep_days = EntityFolder.DEFAULT_KEEP;
            return db.folder().insertFolder(f);
        }
        // Repair placement/label in a single write (mutate the fetched entity,
        // then one updateFolder — mixing setFolderParent with updateFolder would
        // clobber the just-set parent from the stale in-memory copy).
        // display set ONCE at creation, never overwritten on re-sync — a user
        // rename of a group node (Edit properties → Display) sticks.
        boolean changed = false;
        if (!eqLong(f.parent, parent)) { f.parent = parent; changed = true; }
        if (changed) db.folder().updateFolder(f);
        return f.id;
    }

    private static void ensureLeafFolder(DB db, long account, String pathName, String title,
                                         String feedUrl, Long parent) {
        EntityFolder f = null;
        List<EntityFolder> all = db.folder().getFolders(account, false, false);
        if (all != null)
            for (EntityFolder c : all)
                if (feedUrl.equals(c.feed_url)) { f = c; break; }

        if (f == null) {
            f = new EntityFolder();
            f.account = account;
            f.name = pathName;
            f.display = title;
            f.type = EntityFolder.USER;
            f.feed_url = feedUrl;
            f.parent = parent;
            f.local = true;
            f.synchronize = false;
            f.subscribed = true;
            f.selectable = true;
            // parity with mail folders: notify on new items, show in the unified
            // inbox, + show as a nav-menu shortcut. All user-togglable per-folder
            // (folder context menu).
            f.notify = true;
            f.unified = true;
            f.navigation = true;
            f.sync_days = EntityFolder.DEFAULT_SYNC;
            f.keep_days = EntityFolder.DEFAULT_KEEP;
            db.folder().insertFolder(f);
            return;
        }
        // identity is the feed url — repair name/placement in one write.
        // NOTE: display is set ONCE at creation and NEVER overwritten on
        // re-sync, so a user rename (Edit properties → Display) sticks instead
        // of being clobbered by the server title on the next channels.json pull.
        boolean changed = false;
        if (!pathName.equals(f.name)) { f.name = pathName; changed = true; }
        if (!eqLong(f.parent, parent)) { f.parent = parent; changed = true; }
        if (changed) db.folder().updateFolder(f);
    }

    // Drop feeds this gateway no longer serves, then any group folder left with
    // no children. Scoped to `origin` so manual feeds + the GitHub releases seed
    // are untouched. Single pass; deeply-nested empties clear on the next run.
    private static void pruneFeedTree(DB db, long account, String origin, Set<String> liveFeeds) {
        List<EntityFolder> all = db.folder().getFolders(account, false, false);
        if (all == null)
            return;
        for (EntityFolder f : all)
            if (f.feed_url != null && f.feed_url.startsWith(origin) && !liveFeeds.contains(f.feed_url))
                db.folder().deleteFolder(f.id);

        List<EntityFolder> after = db.folder().getFolders(account, false, false);
        if (after == null)
            return;
        // NOTE: root-level groups (parent == null) must qualify too — the only
        // feed_url==null folders under this account are our own channel-tree
        // groups, so "not anyone's parent" alone is a safe delete condition
        // regardless of nesting depth. A parent-not-null check here used to
        // make a whole top-level category's empty group unprunable forever.
        Set<Long> parents = new HashSet<>();
        for (EntityFolder f : after)
            if (f.parent != null)
                parents.add(f.parent);
        for (EntityFolder f : after)
            if (f.feed_url == null && !parents.contains(f.id))
                db.folder().deleteFolder(f.id);
    }

    private static String originOf(String base) {
        Uri u = Uri.parse(base == null ? "" : base);
        String scheme = u.getScheme();
        String authority = u.getAuthority();
        return (scheme != null && authority != null) ? scheme + "://" + authority : "";
    }

    private static String resolveFeed(String origin, String feed) {
        if (feed.startsWith("http://") || feed.startsWith("https://"))
            return feed;
        return origin + (feed.startsWith("/") ? feed : "/" + feed);
    }

    private static boolean eqLong(Long a, Long b) {
        return a == null ? b == null : a.equals(b);
    }
}
