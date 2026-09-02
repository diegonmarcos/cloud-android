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
    // ponytail: raised from 200 to a generous ceiling so getFolderMessages()
    // realistically returns the folder's FULL membership (a paged transport
    // can now return more than the old cap) -- syncMessages' removal pass
    // below only fires when the result comes back under this limit, i.e. is
    // known-complete, so a too-low ceiling silently disabled removals on any
    // folder bigger than it.
    private static final int SYNC_LIMIT = 5000; // newest N per mailbox per pass

    // op.tries cap for processOperations -- Core.java's LOCAL_RETRY_MAX/
    // TOTAL_RETRY_MAX are private to that class, so this is a local constant
    // rather than a shared one. One poisoned op retries this many passes
    // before it is dropped instead of wedging the folder's queue forever.
    private static final int OP_RETRY_MAX = 10;

    // Entry from ServiceSynchronize.monitorAccount's TYPE_JMAP branch. JMAP has
    // no IMAP IDLE, so this is a PERSISTENT POLL LOOP (mirroring the IMAP/POP
    // monitorAccount lifetime, NOT a one-pass return): connect → one sync pass →
    // sleep poll_interval via state.acquire(), repeat until the service stops
    // the account (state.stop()/error() wakes/interrupts the wait). Queuing a
    // new operation does NOT wake this wait -- nothing calls state.release()
    // for that -- so a user action (SEEN/FLAG/MOVE/...) waits for the next poll
    // to actually reach the server, up to poll_interval (longer under the
    // backoff below). Wiring an early wake is a separate, more invasive change.
    static void monitor(Context context, EntityAccount account, Core.State state, boolean sync) {
        DB db = DB.getInstance(context);

        // A queued operation (user marked read, flagged, moved...) releases the
        // poll wait below, mirroring what the IMAP monitor's liveOperations
        // observer does -- without this, a tap waits up to poll_interval before
        // reaching the server (the "huge delay to mark read" complaint), and a
        // backoff wait cannot be interrupted by user action at all.
        final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        // TwoStateOwner's CONSTRUCTOR registers a lifecycle observer, and
        // androidx enforces addObserver on the main thread -- constructing it
        // here on the account thread threw IllegalStateException and killed the
        // monitor in a restart loop (measured on-device 13:40:01, build
        // 3352044). Everything lifecycle-touching happens inside the post.
        final TwoStateOwner[] cowner = new TwoStateOwner[1];
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cowner[0] = new TwoStateOwner(account.name + "/jmap-ops");
                cowner[0].start();
                db.operation().liveOperations(account.id).observe(cowner[0], new androidx.lifecycle.Observer<java.util.List<TupleOperationEx>>() {
                    private int last = -1;
                    @Override
                    public void onChanged(java.util.List<TupleOperationEx> ops) {
                        int now = (ops == null ? 0 : ops.size());
                        if (last >= 0 && now > last)
                            state.release();
                        last = now;
                    }
                });
            }
        });
        try {

        // ponytail: consecutive-failure counter for the backoff below; reset to
        // 0 by any successful pass.
        int consecutiveFailures = 0;
        while (state.isRunning()) {
            EmailService iservice = null;
            boolean connected = false;
            try {
                // Mirrors the IMAP path's setAccountState("connecting")/
                // setAccountState("connected") pair (ServiceSynchronize
                // monitorAccount) -- without it a JMAP account never reports
                // "connected" and never counts toward "Monitoring N accounts".
                db.account().setAccountState(account.id, "connecting");
                iservice = new EmailService(context, account, EmailService.PURPOSE_USE, false);
                iservice.connect(account);
                db.account().setAccountConnected(account.id, new Date().getTime());
                db.account().setAccountError(account.id, null);
                if (sync)
                    run(context, account, iservice);
                db.account().setAccountState(account.id, "connected");
                connected = true;
            } catch (Throwable ex) {
                Log.e(account.name + " JMAP", ex);
                // Non-sanitized (false): FairEmail's default formatter can drop
                // whole classes of connection errors to null, which is exactly
                // the "Failed with no detail" the user hit. Surface the real chain.
                String detail = JmapService.describe(JmapService.unwrap(ex));
                EntityLog.log(context, EntityLog.Type.Account, account, "JMAP " + account.name + " " + detail);
                db.account().setAccountError(account.id, detail);
                db.account().setAccountState(account.id, null);
            } finally {
                if (iservice != null)
                    try {
                        iservice.close();
                    } catch (Throwable ignored) {
                    }
            }

            consecutiveFailures = (connected ? 0 : consecutiveFailures + 1);

            if (!state.isRunning())
                break;
            try {
                // Wait poll_interval minutes (floored to 1 to avoid a busy loop
                // when unset/0) until the next pass, woken early on account stop.
                int mins = (account.poll_interval == null ? 0 : account.poll_interval);
                long base = Math.max(1, mins) * 60L * 1000L;
                // ponytail: failure backoff GROWS FROM ONE MINUTE (60s, 2m, 4m,
                // ...) capped at 2x poll_interval. The first cut grew from
                // poll_interval itself, which turned a 90-second edge restart
                // into a 60-minute dead account (measured 2026-09-02: caddy
                // bounce at 13:05, next retry would have been 14:06). A blip
                // must retry in a minute; only a sustained outage backs off.
                long wait = (consecutiveFailures <= 0 ? base
                        : Math.min(base * 2, 60_000L << Math.min(consecutiveFailures - 1, 6)));
                state.acquire(wait, false);
            } catch (InterruptedException ex) {
                break; // account stopped / network change
            }
        }

        } finally {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (cowner[0] != null)
                        cowner[0].destroy();
                }
            });
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
        String backfillKey = "comms_jmap_backfill_0064";
        boolean backfilled = prefs.getBoolean(backfillKey, false);
        // PHASE 1a: reconcile-only sweep over EVERY folder before anything
        // heavy: keyword sync + stale-mirror removals on existing rows. This
        // is the whole visible read/unread fix, and it must never wait behind
        // an insert flood or an op backlog.
        for (Map.Entry<Long, String> e : folderToMailbox.entrySet()) {
            EntityFolder folder = db.folder().getFolder(e.getKey());
            if (folder == null || !folder.synchronize)
                continue;
            syncMessages(context, account, folder, e.getValue(), jmap, false);
        }

        for (Map.Entry<Long, String> e : folderToMailbox.entrySet()) {
            EntityFolder folder = db.folder().getFolder(e.getKey());
            if (folder == null || !folder.synchronize)
                continue;
            syncMessages(context, account, folder, e.getValue(), jmap);
            // Operations DELIBERATELY not drained here -- see phase 2 below.
            // The interleaved drain let one folder's deep post-backfill BODY
            // backlog starve every later folder's reconciliation (measured
            // 2026-09-02: Inbox held the pass 50+ minutes while the stale
            // view mirrors -- the visible read/unread bug -- were never
            // reached). Reconciliation is cheap and user-visible; ops are
            // heavy and invisible: visible work first.
            // 4) Retention.
            prune(context, db, folder);
            // 5) Repair rows written before the hash/preview fixes. One-shot:
            // this walks every message in the folder, far too heavy to repeat
            // on each sync.
            if (!backfilled)
                backfill(context, db, folder);
        }
        if (!backfilled)
            prefs.edit().putBoolean(backfillKey, true).apply();

        // 3) PHASE 2: drain pending operations, one folder at a time, only
        // after every folder above has reconciled. A deep backlog here can no
        // longer starve the visible read/unread state.
        for (Map.Entry<Long, String> e : folderToMailbox.entrySet()) {
            EntityFolder folder = db.folder().getFolder(e.getKey());
            if (folder == null || !folder.synchronize)
                continue;
            processOperations(context, account, folder, e.getValue(), mailboxToFolder, jmap);
        }
    }

    /**
     * comms: repair JMAP rows stored by earlier builds.
     *
     * Two fields were wrong at write time and nothing rewrites them, because
     * bodies are fetched once and kept: `hash` was never set (so duplicate
     * collapsing never ran and one email filed into N folders showed N times)
     * and `preview` held raw markup (so the message list showed "<html><head>"
     * instead of the text).
     *
     * Only messages whose body was actually fetched can carry a bad preview,
     * which keeps this to the handful the user has opened rather than the whole
     * mailbox -- and the body is re-read from the local file, never the network.
     */
    private static void backfill(Context context, DB db, EntityFolder folder) {
        List<Long> ids = db.message().getMessageByFolder(folder.id);
        if (ids == null)
            return;

        for (Long id : ids) {
            EntityMessage message = db.message().getMessage(id);
            if (message == null)
                continue;

            if (message.hash == null && message.uidl != null)
                db.message().setMessageHash(message.id, "jmap:" + message.uidl);

            // A preview starting with '<' is markup: getFullText() was skipped.
            if (!message.content || message.preview == null ||
                    !message.preview.trim().startsWith("<"))
                continue;

            try {
                File file = message.getFile(context);
                if (!file.exists())
                    continue;
                String text = HtmlHelper.getFullText(context, Helper.readText(file));
                db.message().setMessageContent(message.id, true,
                        HtmlHelper.getLanguage(context, message.subject, text),
                        null,
                        HtmlHelper.getPreview(text),
                        null);
            } catch (Throwable ex) {
                Log.w(ex);
            }
        }
    }

    /**
     * comms: enforce keep_days / auto_delete on a JMAP folder.
     *
     * keep_days was assigned at folder creation (DEFAULT_KEEP) and shown in the
     * UI, but nothing enforced it: retention lives in Core's
     * onSynchronizeMessages, the IMAP path a JMAP folder never enters. JMAP
     * folders therefore grew without bound, exactly as feed folders did before
     * WorkerFeedSync.pruneFeed.
     *
     * Same DAO and same exemptions mail uses: flagged, still-unread and snoozed
     * messages are never reaped, and unread gets twice the window.
     */
    private static void prune(Context context, DB db, EntityFolder folder) {
        Integer keep = folder.keep_days;
        if (keep == null || keep == Integer.MAX_VALUE || keep <= 0)
            return;

        long now = System.currentTimeMillis();
        long keepTime = now - keep * 24L * 3600_000L;
        long keepUnreadTime = now - keep * 2L * 24L * 3600_000L;

        try {
            if (Boolean.TRUE.equals(folder.auto_delete)) {
                int n = db.message().deleteMessagesBefore(folder.id, now, keepTime, keepUnreadTime);
                if (n > 0)
                    EntityLog.log(context, "JMAP pruned=" + n + " folder=" + folder.name + " keep_days=" + keep);
            } else {
                List<Long> ids = db.message().getMessagesBefore(folder.id, now, keepTime, keepUnreadTime);
                if (ids != null && !ids.isEmpty()) {
                    for (Long id : ids)
                        db.message().setMessageUiHide(id, true);
                    EntityLog.log(context, "JMAP hidden=" + ids.size() + " folder=" + folder.name + " keep_days=" + keep);
                }
            }
        } catch (Throwable ex) {
            // Retention must never break a sync that already succeeded.
            Log.w(folder.name + " JMAP prune failed", ex);
        }
    }

    private static void syncMessages(Context context, EntityAccount account,
                                     EntityFolder folder, String mailboxId, JmapService jmap) throws Exception {
        syncMessages(context, account, folder, mailboxId, jmap, true);
    }

    // [insertNew]=false is the reconcile-only mode: keywords + removals on
    // EXISTING rows, no inserts. Phase 1a below runs it across every folder
    // first because that is the entirety of what the user SEES (stale unread
    // mirrors dying, read state converging); inserting a state-axis view's
    // thousands of new mirror rows (Cb Read after the 2026-09-02 view
    // widening) is invisible bulk work that was starving it at ~30s/row under
    // list-query contention.
    private static void syncMessages(Context context, EntityAccount account,
                                     EntityFolder folder, String mailboxId, JmapService jmap,
                                     boolean insertNew) throws Exception {
        DB db = DB.getInstance(context);

        // Existing server ids already stored (uidl = JMAP Email.id) → local row id.
        Map<String, Long> have = new HashMap<>();
        for (TupleUidl u : db.message().getUidls(folder.id))
            if (u.uidl != null)
                have.put(u.uidl, u.id);

        List<Email> emails = jmap.getFolderMessages(mailboxId, SYNC_LIMIT);
        EntityLog.log(context, "JMAP " + folder.name + ": server=" + emails.size() + " stored=" + have.size());

        // A Stalwart JMAP mailbox is a server-recomputed VIEW, not a mutable
        // IMAP folder -- this pass must mirror both directions: new mail
        // inserted, removed mail deleted, and already-stored keyword changes
        // (read elsewhere) applied. The IMAP path does the same three things
        // in Core.onSynchronizeMessages; JMAP had only ever done the first.
        Set<String> serverIds = new HashSet<>();
        for (Email email : emails) {
            if (email.getId() == null)
                continue;
            serverIds.add(email.getId());

            Long localId = have.get(email.getId());
            if (localId != null) {
                // 1b) Intersection: pull the server's keyword state onto the
                // already-stored row (see reconcileKeywords).
                reconcileKeywords(db, folder, localId, email);
                continue;
            }

            if (!insertNew)
                continue; // reconcile-only pass: existing rows only

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

        // 1a) stored − server: the message left this view. Only trustworthy
        // when the server call returned the folder's COMPLETE membership --
        // getFolderMessages() is capped at SYNC_LIMIT, so a full folder would
        // otherwise look like every message past the cap "left" the folder.
        // This deletes the row for THIS folder only; the row is a view mirror,
        // the underlying mail still exists via its other folder row(s).
        boolean complete = emails.size() < SYNC_LIMIT;
        if (complete) {
            int removed = 0;
            for (Map.Entry<String, Long> e : have.entrySet())
                if (!serverIds.contains(e.getKey())) {
                    db.message().deleteMessage(e.getValue());
                    removed++;
                }
            if (removed > 0)
                EntityLog.log(context, "JMAP " + folder.name + ": removed=" + removed + " (left view)");
        } else {
            EntityLog.log(context, "JMAP " + folder.name +
                    ": server result capped at SYNC_LIMIT=" + SYNC_LIMIT + ", skipping removal reconcile");
        }
    }

    // 1b) Apply the server's $seen/$flagged keywords to an already-stored row,
    // but only when no pending local operation would fight it -- same guard
    // shape as the IMAP sync (Core.java ~5207 for SEEN, ~5227 for FLAG): a
    // queued SEEN/FLAG op means the user changed it HERE and the change has
    // not reached the server yet, so the server's answer is stale, not wrong.
    private static void reconcileKeywords(DB db, EntityFolder folder, long messageId, Email email) {
        EntityMessage message = db.message().getMessage(messageId);
        if (message == null)
            return;

        Map<String, Boolean> kw = email.getKeywords();
        boolean seen = hasKeyword(kw, Keyword.SEEN);
        boolean flagged = hasKeyword(kw, Keyword.FLAGGED);

        if (!message.seen.equals(seen) &&
                db.operation().getOperationCount(folder.id, message.id, EntityOperation.SEEN) == 0) {
            db.message().setMessageSeen(message.id, seen);
            db.message().setMessageUiSeen(message.id, seen);
        }

        if (!message.flagged.equals(flagged) &&
                db.operation().getOperationCount(folder.id, message.id, EntityOperation.FLAG) == 0) {
            db.message().setMessageFlagged(message.id, flagged);
            db.message().setMessageUiFlagged(message.id, flagged, flagged ? message.color : null);
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
        // A JMAP Email is ONE object with a set of mailboxIds -- a Sieve
        // `fileinto :copy` adds a mailbox to that set, it does not copy the
        // mail. But this sync stores one row per (folder, email), so an email
        // filed into 6 folders becomes 6 rows and the conversation view showed
        // it 6 times.
        //
        // FragmentMessages.markDuplicates keys on message.hash (msgid only
        // when the dup_msgids pref is on), and this path never set hash --
        // null key means "skip", so nothing was ever collapsed. Email.id is
        // identical across every mailbox holding the email and unique per
        // email, which is exactly what that key wants.
        m.hash = "jmap:" + email.getId();
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
        // getPreview() only collapses whitespace and truncates -- it does NOT
        // strip markup. Feeding it raw HTML made the message-list preview show
        // "<html><head><style>..." instead of the message text. getFullText()
        // is the HTML->text step; Core.onBody (IMAP) and WorkerFeedSync (RSS)
        // both do it first, this path was the only one that skipped it.
        String text = HtmlHelper.getFullText(context, html);
        db.message().setMessageContent(message.id, true,
                HtmlHelper.getLanguage(context, message.subject, text),
                null,
                HtmlHelper.getPreview(text),
                null);
    }

    // comms: the unread lane's server read-state probe (JMAP side).
    //
    // A separate path from syncMessages on purpose -- see UnreadSync. A server
    // rejecting the Email/query must not fail this operation, so it is caught
    // and logged here rather than left to the caller's catch, which would mark
    // the operation as errored and retry it forever.
    private static void onUnread(Context context, EntityFolder folder, String mailboxId, JmapService jmap) {
        List<String> serverIds;
        try {
            serverIds = UnreadSync.fetchUnreadIds(jmap, mailboxId);
        } catch (MessagingException ex) {
            Log.w(ex);
            return;
        }

        DB db = DB.getInstance(context);

        // uidl = JMAP Email.id, same dedup key syncMessages uses.
        Map<String, Long> uidlToId = new HashMap<>();
        for (TupleUidl u : db.message().getUidls(folder.id))
            if (u.uidl != null)
                uidlToId.put(u.uidl, u.id);

        Set<Long> ids = new HashSet<>();
        for (String serverId : serverIds) {
            Long id = uidlToId.get(serverId);
            if (id != null)
                ids.add(id);
        }

        // Complete iff the query came back under the cap -- see
        // UnreadSync.MAX_UNREAD and UnreadSync.reconcile's completeness guard.
        boolean complete = serverIds.size() < UnreadSync.MAX_UNREAD;
        UnreadSync.reconcile(context, folder, ids, complete);
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
                    case EntityOperation.UNREAD:
                        onUnread(context, folder, mailboxId, jmap);
                        break;
                    case EntityOperation.SYNC:
                        // A queued SYNC just means "sync this folder now" instead
                        // of waiting for the next poll -- run the same pass run()
                        // does per folder.
                        syncMessages(context, account, folder, mailboxId, jmap);
                        break;
                    case EntityOperation.KEYWORD:
                        if (ids != null) {
                            org.json.JSONArray jargs = new org.json.JSONArray(op.args);
                            jmap.setKeyword(ids, jargs.getString(0), jargs.getBoolean(1));
                        }
                        break;
                    case EntityOperation.ADD:
                        // IMAP-only: attaches an uploaded file before send. JMAP
                        // send (batch 5) submits the already-built MIME in one
                        // shot, so there is nothing for this op to do here.
                        break;
                    case EntityOperation.EXISTS:
                        // IMAP-only: stale-UID existence probe. JMAP dedups by
                        // Email.id, which this sync already checks, so this op
                        // never needs to run here.
                        break;
                    default:
                        Log.w("JMAP unhandled op=" + op.name + " id=" + op.id);
                        break;
                }
                db.operation().deleteOperation(op.id);
            } catch (Throwable ex) {
                // One poisoned op must not wedge every other op behind it in
                // this folder -- isolate per-op instead of throwing out of the
                // loop. Retried up to OP_RETRY_MAX passes, then dropped (mirrors
                // EntityOperation.cleanup so ui state is re-asserted instead of
                // left stuck mid-operation).
                Log.e(folder.name + " JMAP op=" + op.name + " id=" + op.id, ex);
                db.operation().setOperationError(op.id, Log.formatThrowable(ex));
                int tries = op.tries + 1;
                db.operation().setOperationTries(op.id, tries);
                if (tries >= OP_RETRY_MAX) {
                    EntityLog.log(context, "JMAP op=" + op.name + " id=" + op.id +
                            " tries=" + tries + " exceeded retry max, dropping");
                    op.cleanup(context, false);
                    db.operation().deleteOperation(op.id);
                }
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
