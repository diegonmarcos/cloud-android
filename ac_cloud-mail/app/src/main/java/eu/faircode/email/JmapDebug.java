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

import com.diegonmarcos.superapp.devtools.AppDebugServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * comms: GET /api/jmap/sync -- the sync engine's own state, read-only.
 *
 * Two questions could previously only be answered by staring at a notification
 * string or by guessing, which burned most of a day of "is it fixed yet?":
 *
 *   1. Is the operation queue draining? The foreground notification shows only a
 *      total ("400 operaciones pendientes"), which cannot tell "wedged" from
 *      "busy". `operations` breaks the queue down by folder, name and tries, and
 *      reports the two flags that made ops unreachable -- folderMissing and
 *      folderSynchronize -- so a pile stranded on a de-synced folder is obvious.
 *   2. Do category folders populate, and do their badges agree with their
 *      contents? `folders` reports `messages` and `unseen` straight out of
 *      DaoFolder.getFoldersEx, the same counts the folder list renders, so a
 *      folder with messages > 0 and unseen == 0 is a badge bug that can be seen
 *      without a screenshot.
 *
 * Read-only: it runs queries and returns them. Callers should cross-check
 * versionCode from /api/system/info, because the expensive mistake this exists
 * to prevent is diagnosing a build that predates the fix.
 */
public class JmapDebug {
    private JmapDebug() {
    }

    static void register(Context context) {
        final Context app = context.getApplicationContext();
        AppDebugServer.INSTANCE.route(
                "jmap",
                Collections.singletonList(new AppDebugServer.Op(
                        "sync",
                        "",
                        "JMAP operation queue grouped by folder plus per-folder message/unseen counts, read-only")),
                (op, query) -> "sync".equals(op) ? dump(app) : null);
    }

    static String dump(Context context) {
        try {
            DB db = DB.getInstance(context);
            JSONObject result = new JSONObject();

            JSONArray jaccounts = new JSONArray();
            List<EntityAccount> accounts = db.account().getAccounts();
            if (accounts != null)
                for (EntityAccount account : accounts) {
                    JSONObject jaccount = new JSONObject();
                    jaccount.put("id", account.id);
                    jaccount.put("name", account.name);
                    jaccount.put("protocol", account.protocol);
                    jaccount.put("jmap", account.protocol == EntityAccount.TYPE_JMAP);
                    jaccount.put("synchronize", account.synchronize);
                    jaccount.put("state", account.state);
                    jaccount.put("error", account.error);

                    JSONArray jops = new JSONArray();
                    int total = 0;
                    List<Long> opFolders = db.operation().getOperationFolders(account.id);
                    if (opFolders != null)
                        for (Long fid : opFolders) {
                            List<EntityOperation> ops = db.operation().getOperationsByFolder(fid);
                            if (ops == null || ops.size() == 0)
                                continue;
                            total += ops.size();

                            EntityFolder folder = db.folder().getFolder(fid);
                            JSONObject jgroup = new JSONObject();
                            jgroup.put("folder", fid);
                            jgroup.put("folderName", folder == null ? null : folder.name);
                            jgroup.put("folderType", folder == null ? null : folder.type);
                            jgroup.put("folderMissing", folder == null);
                            jgroup.put("folderSynchronize",
                                    folder != null && Boolean.TRUE.equals(folder.synchronize));
                            jgroup.put("count", ops.size());

                            JSONObject jnames = new JSONObject();
                            int errored = 0;
                            int maxTries = 0;
                            Long oldest = null;
                            for (EntityOperation o : ops) {
                                jnames.put(o.name, jnames.optInt(o.name, 0) + 1);
                                if (o.error != null)
                                    errored++;
                                if (o.tries > maxTries)
                                    maxTries = o.tries;
                                if (o.created != null && (oldest == null || o.created < oldest))
                                    oldest = o.created;
                            }
                            jgroup.put("byName", jnames);
                            jgroup.put("errored", errored);
                            jgroup.put("maxTries", maxTries);
                            jgroup.put("oldestCreated", oldest);
                            jops.put(jgroup);
                        }
                    jaccount.put("operationsPending", total);
                    jaccount.put("operations", jops);

                    JSONArray jfolders = new JSONArray();
                    List<TupleFolderEx> folders = db.folder().getFoldersEx(account.id);
                    if (folders != null)
                        for (TupleFolderEx f : folders) {
                            JSONObject jfolder = new JSONObject();
                            jfolder.put("id", f.id);
                            jfolder.put("name", f.name);
                            jfolder.put("type", f.type);
                            jfolder.put("synchronize", f.synchronize);
                            jfolder.put("messages", f.messages);
                            jfolder.put("unseen", f.unseen);
                            jfolders.put(jfolder);
                        }
                    jaccount.put("folders", jfolders);
                    jaccounts.put(jaccount);
                }

            result.put("accounts", jaccounts);
            return result.toString();
        } catch (Throwable ex) {
            Log.e(ex);
            return null;
        }
    }
}
