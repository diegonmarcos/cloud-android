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
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import rs.ltt.jmap.common.entity.Email;
import rs.ltt.jmap.common.entity.EmailAddress;
import rs.ltt.jmap.common.entity.EmailBodyPart;
import rs.ltt.jmap.common.entity.EmailBodyValue;
import rs.ltt.jmap.common.entity.Keyword;
import rs.ltt.jmap.common.entity.Mailbox;

// comms: JMAP sync engine (batch 4/6). Self-contained so the invasive edit to
// the giant ServiceSynchronize/Core files is a single hook (monitorAccount's
// TYPE_JMAP guard → JmapSync.run). One pass per service poll:
//   connect → upsert folders (Mailbox → EntityFolder) → per folder sync new
//   messages (dedup by uidl = JMAP Email.id, mirroring the POP uidl pattern)
//   → drain pending EntityOperations (SEEN/FLAG/MOVE/DELETE/BODY) via the
//   JmapService data-plane → send outbox (batch 5).
//
// Message headers are built directly from the JMAP Email entity (no MIME
// parse); bodies are fetched lazily on BODY operation. Dedup by uidl means a
// re-poll never duplicates or drops a message.
public class JmapSync {
    private static final int SYNC_LIMIT = 200; // newest N per mailbox per pass

    // Entry from ServiceSynchronize.monitorAccount's TYPE_JMAP branch. JMAP has
    // no IMAP IDLE, so this is a PERSISTENT POLL LOOP (mirroring the IMAP/POP
    // monitorAccount lifetime, NOT a one-pass return): connect → one sync pass →
    // sleep poll_interval via state.acquire(), repeat until the service stops
    // the account (state.stop()/error() wakes/interrupts the wait). A new queued
    // operation wakes the wait early via state.release(), so user actions sync
    // promptly instead of waiting a full interval.
    static void monitor(Context context, EntityAccount account, Core.State state, boolean sync) {
        DB db = DB.getInstance(context);
        while (state.isRunning()) {
            EmailService iservice = null;
            try {
                iservice = new EmailService(context, account, EmailService.PURPOSE_USE, false);
                iservice.connect(account);
                db.account().setAccountConnected(account.id, new Date().getTime());
                db.account().setAccountError(account.id, null);
                if (sync)
                    run(context, account, iservice);
            } catch (Throwable ex) {
                Log.e(account.name + " JMAP", ex);
                // Non-sanitized (false): FairEmail's default formatter can drop
                // whole classes of connection errors to null, which is exactly
                // the "Failed with no detail" the user hit. Surface the real chain.
                String detail = JmapService.describe(JmapService.unwrap(ex));
                EntityLog.log(context, EntityLog.Type.Account, account, "JMAP " + account.name + " " + detail);
                db.account().setAccountError(account.id, detail);
            } finally {
                if (iservice != null)
                    try {
                        iservice.close();
                    } catch (Throwable ignored) {
                    }
            }

            if (!state.isRunning())
                break;
            try {
                // Wait poll_interval minutes (floored to 1 to avoid a busy loop
                // when unset/0) until the next pass, woken early on new work or
                // account stop.
                int mins = (account.poll_interval == null ? 0 : account.poll_interval);
                state.acquire(Math.max(1, mins) * 60L * 1000L, false);
            } catch (InterruptedException ex) {
                break; // account stopped / network change
            }
        }
    }

    // One sync pass for a JMAP account. [iservice] is an already-connected
    // EmailService (its JmapService carries the resolved account id).
    static void run(Context context, EntityAccount account, EmailService iservice) throws Exception {
        DB db = DB.getInstance(context);
        JmapService jmap = iservice.getJmapService();
        if (jmap == null)
            throw new IllegalStateException("JMAP service not connected");

        // 1) Folders — upsert every mailbox and keep the id↔folder mapping for
        //    message + move operations (EntityFolder has no server-id column).
        Mailbox[] mailboxes = jmap.fetchMailboxes();
        EntityLog.log(context, "JMAP " + account.name + " sync: " + mailboxes.length + " mailboxes");
        Map<Long, String> folderToMailbox = new HashMap<>();  // EntityFolder.id → mailbox id
        Map<String, Long> mailboxToFolder = new HashMap<>();  // mailbox id → EntityFolder.id
        Map<String, Mailbox> byId = new HashMap<>();
        for (Mailbox mb : mailboxes)
            byId.put(mb.getId(), mb);

        // Pre-0048 builds left setProperties()'s default synchronize=false on
        // USER-type folders, so sieve/filter folders were provisioned but the
        // message loop below skipped them forever (all empty in the app). New
        // folders now sync; existing rows get a ONE-TIME repair (pref-gated per
        // account so a user disabling a folder later is respected).
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String repairKey = "jmap_folder_sync_repaired." + account.id;
        boolean repaired = prefs.getBoolean(repairKey, false);

        for (Mailbox mb : mailboxes) {
            String name = fullName(mb, byId);
            String type = JmapService.roleToType(mb.getRole());
            EntityFolder folder = db.folder().getFolderByName(account.id, name);
            if (folder == null) {
                folder = new EntityFolder(name, type);
                folder.account = account.id;
                folder.synchronize = true; // JMAP folders are the user's own taxonomy — sync all
                folder.subscribed = true;
                folder.poll = true; // JMAP is polled, never IDLE
                folder.download = true;
                folder.sync_days = EntityFolder.DEFAULT_SYNC;
                folder.keep_days = EntityFolder.DEFAULT_KEEP;
                folder.selectable = true;
                folder.id = db.folder().insertFolder(folder);
                EntityLog.log(context, "JMAP created folder=" + name + " type=" + type);
            } else {
                if (!repaired && !Boolean.TRUE.equals(folder.synchronize)) {
                    db.folder().setFolderSynchronize(folder.id, true);
                    folder.synchronize = true;
                    EntityLog.log(context, "JMAP repaired sync folder=" + name);
                }
                // Repair the special-folder TYPE from the server role. Folders
                // synced by pre-role builds were stored as generic USER folders,
                // so the account showed "no sent folder / drafts required" and
                // sending could not stash a copy. Upgrade only when the server
                // assigns a concrete role (non-USER) that differs — never
                // downgrade a user's manual assignment to USER on a null role.
                if (!EntityFolder.USER.equals(type) && !type.equals(folder.type)) {
                    db.folder().setFolderType(folder.id, type);
                    EntityLog.log(context, "JMAP repaired type folder=" + name
                            + " " + folder.type + "→" + type);
                    folder.type = type;
                }
            }
            folderToMailbox.put(folder.id, mb.getId());
            mailboxToFolder.put(mb.getId(), folder.id);
        }
        if (!repaired)
            prefs.edit().putBoolean(repairKey, true).apply();

        // 1b) Remove orphan placeholder folders. CommsAccounts.ensureJmapFolders
        //     bootstraps generic special folders (Sent/Trash/Junk) so the account
        //     monitor starts before the first sync, but the real server folders
        //     are role-typed with different names (Sent Items / Deleted Items /
        //     Junk Mail). Once real folders are synced, any account folder with no
        //     current server mailbox that is still empty is a dead placeholder —
        //     delete it so there is exactly one folder per role. Guarded on a
        //     non-empty mailbox list so a transient empty fetch never wipes
        //     folders; empty-only so a folder holding mail is never removed;
        //     feed folders (RSS) are local by design and always kept.
        if (mailboxes.length > 0) {
            for (EntityFolder f : db.folder().getFolders(account.id, false, false)) {
                if (folderToMailbox.containsKey(f.id) || f.feed_url != null)
                    continue;
                List<TupleUidl> stored = db.message().getUidls(f.id);
                if (stored != null && !stored.isEmpty())
                    continue;
                db.folder().deleteFolder(f.id);
                EntityLog.log(context, "JMAP removed orphan placeholder folder="
                        + f.name + " type=" + f.type);
            }
        }

        // 2) Messages — per synchronized folder, insert any not already stored.
        for (Map.Entry<Long, String> e : folderToMailbox.entrySet()) {
            EntityFolder folder = db.folder().getFolder(e.getKey());
            if (folder == null || !folder.synchronize)
                continue;
            syncMessages(context, account, folder, e.getValue(), jmap);
            // 3) Drain pending operations queued against this folder.
            processOperations(context, account, folder, e.getValue(), mailboxToFolder, jmap);
        }
    }

    private static void syncMessages(Context context, EntityAccount account,
                                     EntityFolder folder, String mailboxId, JmapService jmap) throws Exception {
        DB db = DB.getInstance(context);

        // Existing server ids already stored (uidl = JMAP Email.id).
        Set<String> have = new HashSet<>();
        for (TupleUidl u : db.message().getUidls(folder.id))
            if (u.uidl != null)
                have.add(u.uidl);

        List<Email> emails = jmap.getFolderMessages(mailboxId, SYNC_LIMIT);
        EntityLog.log(context, "JMAP " + folder.name + ": server=" + emails.size() + " stored=" + have.size());
        for (Email email : emails) {
            if (email.getId() == null || have.contains(email.getId()))
                continue;
            EntityMessage message = buildMessage(account, folder, email);
            try {
                db.beginTransaction();
                message.notifying = EntityMessage.NOTIFYING_IGNORE;
                message.id = db.message().insertMessage(message);
                db.setTransactionSuccessful();
                EntityLog.log(context, "JMAP added " + folder.name + " id=" + message.id + " uidl=" + message.uidl);
            } finally {
                db.endTransaction();
            }
            // Defer body to a BODY operation so the list populates fast.
            EntityOperation.queue(context, message, EntityOperation.BODY);
        }
    }

    // Build an EntityMessage from a JMAP Email header. Server-id → uidl (POP
    // analog); keywords → seen/flagged/answered; threadId is used verbatim.
    private static EntityMessage buildMessage(EntityAccount account, EntityFolder folder, Email email) {
        EntityMessage m = new EntityMessage();
        m.account = account.id;
        m.folder = folder.id;
        m.uid = null;                 // JMAP has no IMAP UID
        m.uidl = email.getId();       // stable server id for dedup
        m.msgid = firstOrGen(email.getMessageId());
        m.references = join(email.getReferences());
        m.inreplyto = firstOrNull(email.getInReplyTo());
        m.thread = (email.getThreadId() != null ? email.getThreadId() : m.msgid);

        m.from = addrs(email.getFrom());
        m.to = addrs(email.getTo());
        m.cc = addrs(email.getCc());
        m.bcc = addrs(email.getBcc());
        m.reply = addrs(email.getReplyTo());
        m.subject = email.getSubject();
        m.size = email.getSize();
        m.total = email.getSize();
        m.received = toMillis(email.getReceivedAt());
        m.sent = toMillis(email.getSentAt());
        if (m.received == null)
            m.received = (m.sent == null ? 0L : m.sent);

        Map<String, Boolean> kw = email.getKeywords();
        boolean seen = hasKeyword(kw, Keyword.SEEN);
        boolean flagged = hasKeyword(kw, Keyword.FLAGGED);
        boolean answered = hasKeyword(kw, Keyword.ANSWERED);
        m.seen = seen;
        m.answered = answered;
        m.flagged = flagged;
        m.ui_seen = seen;
        m.ui_answered = answered;
        m.ui_flagged = flagged;
        m.ui_hide = false;
        m.ui_found = false;
        m.ui_ignored = seen;
        m.ui_browsed = false;
        m.content = false; // body fetched by the BODY operation
        m.sender = MessageHelper.getSortKey(EntityFolder.isOutgoing(folder.type) ? m.to : m.from);
        return m;
    }

    // Fetch + persist one message body (driven by EntityOperation.BODY).
    static void onBody(Context context, EntityMessage message, JmapService jmap) throws Exception {
        DB db = DB.getInstance(context);
        Email email = jmap.getMessageBody(message.uidl);
        if (email == null)
            throw new IllegalArgumentException("JMAP body missing uidl=" + message.uidl);
        String html = bodyHtml(email);
        File file = message.getFile(context);
        Helper.writeText(file, html);
        db.message().setMessageContent(message.id, true, null, null,
                (html == null ? null : HtmlHelper.getPreview(html)), null);
    }

    // ── Operations (batch 6): map queued EntityOperations to Email/set ────────
    private static void processOperations(Context context, EntityAccount account, EntityFolder folder,
                                          String mailboxId, Map<String, Long> mailboxToFolder,
                                          JmapService jmap) throws Exception {
        DB db = DB.getInstance(context);
        List<EntityOperation> ops = db.operation().getOperationsByFolder(folder.id);
        if (ops == null)
            return;
        for (EntityOperation op : ops) {
            try {
                EntityMessage message = (op.message == null ? null : db.message().getMessage(op.message));
                List<String> ids = (message == null || message.uidl == null
                        ? null : Arrays.asList(message.uidl));
                switch (op.name) {
                    case EntityOperation.BODY:
                        if (message != null)
                            onBody(context, message, jmap);
                        break;
                    case EntityOperation.SEEN:
                        if (ids != null)
                            jmap.setSeen(ids, message.ui_seen);
                        break;
                    case EntityOperation.FLAG:
                        if (ids != null)
                            jmap.setFlagged(ids, message.ui_flagged);
                        break;
                    case EntityOperation.MOVE:
                        if (ids != null) {
                            long target = new org.json.JSONArray(op.args).getLong(0);
                            String targetMailbox = mailboxForFolder(mailboxToFolder, target);
                            if (targetMailbox != null)
                                jmap.moveToMailbox(ids, targetMailbox);
                        }
                        break;
                    case EntityOperation.DELETE:
                        if (ids != null)
                            jmap.deleteMessages(ids);
                        break;
                    default:
                        // ADD/EXISTS/KEYWORD etc. — no-op for JMAP for now.
                        break;
                }
                db.operation().deleteOperation(op.id);
            } catch (Throwable ex) {
                db.operation().setOperationError(op.id, Log.formatThrowable(ex));
                throw ex;
            }
        }
    }

    // ── Send (batch 5) ────────────────────────────────────────────────────────
    // Called from ServiceSend.onSend's JMAP branch (mirrors the MicrosoftGraph
    // branch). Opens a JMAP connection and submits the already-built RFC822 MIME
    // via upload → Email/import (Sent) → EmailSubmission/set. FairEmail's own
    // pre/post-send bookkeeping (sent-folder copy, outbox delete, mark replied)
    // runs unchanged around this call.
    static void onSend(Context context, EntityAccount account, EntityMessage message,
                       EntityIdentity ident, MimeMessage imessage) throws MessagingException, IOException {
        EmailService iservice = new EmailService(context, account, EmailService.PURPOSE_USE, false);
        try {
            iservice.connect(account);
            JmapService jmap = iservice.getJmapService();
            if (jmap == null)
                throw new IllegalStateException("JMAP not connected for send");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            imessage.writeTo(bos);
            jmap.sendMime(bos.toByteArray(), ident.email);
        } finally {
            try {
                iservice.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String fullName(Mailbox mb, Map<String, Mailbox> byId) {
        StringBuilder sb = new StringBuilder(mb.getName() == null ? mb.getId() : mb.getName());
        String parentId = mb.getParentId();
        int guard = 0;
        while (!TextUtils.isEmpty(parentId) && guard++ < 32) {
            Mailbox parent = byId.get(parentId);
            if (parent == null)
                break;
            sb.insert(0, (parent.getName() == null ? parent.getId() : parent.getName()) + "/");
            parentId = parent.getParentId();
        }
        return sb.toString();
    }

    private static String mailboxForFolder(Map<String, Long> mailboxToFolder, long folderId) {
        for (Map.Entry<String, Long> e : mailboxToFolder.entrySet())
            if (e.getValue() != null && e.getValue() == folderId)
                return e.getKey();
        return null;
    }

    private static boolean hasKeyword(Map<String, Boolean> kw, String k) {
        return kw != null && Boolean.TRUE.equals(kw.get(k));
    }

    private static Address[] addrs(List<EmailAddress> list) {
        if (list == null || list.isEmpty())
            return null;
        List<Address> out = new ArrayList<>();
        for (EmailAddress a : list)
            try {
                out.add(new InternetAddress(a.getEmail(), a.getName()));
            } catch (Throwable ignored) {
            }
        return out.isEmpty() ? null : out.toArray(new Address[0]);
    }

    private static Long toMillis(Instant i) {
        return (i == null ? null : i.toEpochMilli());
    }

    private static Long toMillis(OffsetDateTime t) {
        return (t == null ? null : t.toInstant().toEpochMilli());
    }

    private static String firstOrNull(List<String> l) {
        return (l == null || l.isEmpty() ? null : l.get(0));
    }

    private static String firstOrGen(List<String> l) {
        String s = firstOrNull(l);
        return (s != null ? s : EntityMessage.generateMessageId());
    }

    private static String join(List<String> l) {
        return (l == null || l.isEmpty() ? null : TextUtils.join(" ", l));
    }

    // Extract the HTML body string from the JMAP Email body parts + values.
    private static String bodyHtml(Email email) {
        Map<String, EmailBodyValue> values = email.getBodyValues();
        if (values == null)
            return null;
        List<EmailBodyPart> parts = (email.getHtmlBody() != null && !email.getHtmlBody().isEmpty()
                ? email.getHtmlBody() : email.getTextBody());
        if (parts == null)
            return null;
        StringBuilder sb = new StringBuilder();
        for (EmailBodyPart p : parts) {
            EmailBodyValue v = (p.getPartId() == null ? null : values.get(p.getPartId()));
            if (v != null && v.getValue() != null)
                sb.append(v.getValue());
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
