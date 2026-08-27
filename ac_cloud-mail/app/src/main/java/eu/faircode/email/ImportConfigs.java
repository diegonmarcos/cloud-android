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
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.UUID;

// comms: single-JSON provisioner — reads one import-configs document and creates
// IMAP/SMTP accounts, JMAP accounts, and RSS feed folders in one shot.
// Credentials are NOT carried by the document (Pillar 7): passwords stay empty and
// are entered later per account. See import-configs.schema.json.
public class ImportConfigs {
    private static final String RSS_ACCOUNT_UUID = "cloud-comms-rss-account";

    static void run(Context context, JSONObject doc) throws JSONException {
        DB db = DB.getInstance(context);

        try {
            db.beginTransaction();

            importImapSmtp(db, doc.optJSONArray("imap_smtp"));
            importJmap(db, doc.optJSONArray("jmap"));
            importRss(db, doc.optJSONArray("rss"));

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        ServiceSynchronize.eval(context, "import configs");
        WorkerFeedSync.init(context);
    }

    private static int encryption(JSONObject jaccount, String key) {
        String security = jaccount.optString(key, "ssl");
        if ("starttls".equalsIgnoreCase(security))
            return EmailService.ENCRYPTION_STARTTLS;
        else if ("none".equalsIgnoreCase(security))
            return EmailService.ENCRYPTION_NONE;
        else
            return EmailService.ENCRYPTION_SSL;
    }

    private static void importImapSmtp(DB db, JSONArray jaccounts) throws JSONException {
        if (jaccounts == null)
            return;

        for (int i = 0; i < jaccounts.length(); i++) {
            JSONObject jaccount = jaccounts.getJSONObject(i);

            String uuid = jaccount.optString("uuid", null);
            if (TextUtils.isEmpty(uuid))
                uuid = UUID.randomUUID().toString();
            if (db.account().getAccountByUUID(uuid) != null)
                continue;

            EntityAccount account = new EntityAccount();
            account.uuid = uuid;
            account.protocol = EntityAccount.TYPE_IMAP;
            account.host = jaccount.getString("imap_host");
            account.encryption = encryption(jaccount, "imap_security");
            account.port = jaccount.getInt("imap_port");
            account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
            account.user = jaccount.getString("user");
            account.password = ""; // entered later
            account.name = jaccount.optString("display_name", null);
            account.synchronize = jaccount.optBoolean("synchronize", true);
            account.primary = jaccount.optBoolean("primary", false) && account.synchronize;
            account.poll_interval = EntityAccount.DEFAULT_POLL_INTERVAL;
            account.created = System.currentTimeMillis();

            account.id = db.account().insertAccount(account);

            EntityIdentity identity = new EntityIdentity();
            identity.name = jaccount.optString("display_name", account.user);
            identity.email = jaccount.getString("primary_address");
            identity.account = account.id;
            identity.host = jaccount.getString("smtp_host");
            identity.encryption = encryption(jaccount, "smtp_security");
            identity.port = jaccount.getInt("smtp_port");
            identity.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
            identity.user = jaccount.getString("user");
            identity.password = ""; // entered later
            identity.synchronize = true;
            identity.primary = account.primary;
            db.identity().insertIdentity(identity);
        }
    }

    private static void importJmap(DB db, JSONArray jaccounts) throws JSONException {
        if (jaccounts == null)
            return;

        for (int i = 0; i < jaccounts.length(); i++) {
            JSONObject jaccount = jaccounts.getJSONObject(i);

            String uuid = jaccount.optString("uuid", null);
            if (TextUtils.isEmpty(uuid))
                uuid = UUID.randomUUID().toString();
            if (db.account().getAccountByUUID(uuid) != null)
                continue;

            EntityAccount account = new EntityAccount();
            account.uuid = uuid;
            account.protocol = EntityAccount.TYPE_JMAP;
            account.host = jaccount.getString("jmap_host");
            account.encryption = encryption(jaccount, "jmap_security");
            account.port = jaccount.optInt("jmap_port", 443);
            account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
            account.user = jaccount.getString("user");
            account.password = ""; // entered later
            account.name = jaccount.optString("display_name", null);
            account.synchronize = jaccount.optBoolean("synchronize", true);
            account.primary = jaccount.optBoolean("primary", false) && account.synchronize;
            account.poll_interval = EntityAccount.DEFAULT_POLL_INTERVAL;
            account.created = System.currentTimeMillis();

            account.id = db.account().insertAccount(account);

            EntityIdentity identity = new EntityIdentity();
            identity.name = jaccount.optString("display_name", account.user);
            identity.email = jaccount.getString("primary_address");
            identity.account = account.id;
            identity.host = jaccount.getString("jmap_host");
            identity.encryption = encryption(jaccount, "jmap_security");
            identity.port = jaccount.optInt("jmap_port", 443);
            identity.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
            identity.user = jaccount.getString("user");
            identity.password = ""; // entered later
            identity.synchronize = true;
            identity.primary = account.primary;
            db.identity().insertIdentity(identity);
        }
    }

    private static void importRss(DB db, JSONArray jfeeds) throws JSONException {
        if (jfeeds == null || jfeeds.length() == 0)
            return;

        EntityAccount rss = db.account().getAccountByUUID(RSS_ACCOUNT_UUID);
        if (rss == null) {
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
            rss.synchronize = false;
            rss.primary = false;
            rss.poll_interval = EntityAccount.DEFAULT_POLL_INTERVAL;
            rss.created = System.currentTimeMillis();
            rss.id = db.account().insertAccount(rss);
        }

        List<EntityFolder> existing = db.folder().getFolders(rss.id, false, false);

        for (int i = 0; i < jfeeds.length(); i++) {
            JSONObject jfeed = jfeeds.getJSONObject(i);

            String url = jfeed.getString("url");

            boolean duplicate = false;
            if (existing != null)
                for (EntityFolder f : existing)
                    if (url.equals(f.feed_url)) {
                        duplicate = true;
                        break;
                    }
            if (duplicate)
                continue;

            String name = jfeed.optString("folder", null);
            if (TextUtils.isEmpty(name))
                name = jfeed.getString("title");

            EntityFolder folder = new EntityFolder();
            folder.account = rss.id;
            folder.name = name;
            folder.type = EntityFolder.USER;
            folder.feed_url = url;
            folder.local = true;
            folder.synchronize = false;
            folder.subscribed = true;
            folder.selectable = true;
            folder.sync_days = EntityFolder.DEFAULT_SYNC;
            folder.keep_days = EntityFolder.DEFAULT_KEEP;
            db.folder().insertFolder(folder);
        }
    }
}
