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
import java.util.Collections;
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
    // Inserts per folder per pass. Phase 1b walks folders in map order and a
    // handful of giant filter views (Dd/Ac/Inbox, thousands under-synced since
    // the SYNC_LIMIT bump) ate the entire pass: 12,622 inserts logged, ZERO
    // for 24 House (server=473 stored=200 across two passes an hour apart).
    // The pass is cancelled before small folders get a turn. Cap so every
    // folder progresses each pass; the big views backfill over several.
    private static final int INSERT_CAP_PER_PASS = 100;

    // Folders that must never sync to a phone. Cloud-Infra/Health/* holds the
    // ntfy->mail mirror of VM health PROBES (health_resources, health_dns,
    // health_containers, ...): 2699 rows of "memPSI=42%" in one folder on
    // 2026-09-02, every one unread, all paid for on every unread-count query
    // and re-inserted as view mirrors each pass. Probes belong in ntfy; the
    // app stays fast. Existing rows are purged once when the folder is seen.
    static boolean noPhoneSync(String name) {
        return name != null && name.startsWith("Cloud-Infra/Health/");
    }

    // op.tries cap for processOperations -- Core.java's LOCAL_RETRY_MAX/
    // TOTAL_RETRY_MAX are private to that class, so this is a local constant
    // rather than a shared one. One poisoned op retries this many passes
    // before it is dropped instead of wedging the folder's queue forever.
    private static final int OP_RETRY_MAX = 10;

    // Max ids folded into ONE coalesced Email/set (see processOperations).
    // Stalwart's maxObjectsInSet defaults to 500; 200 mirrors JmapService's
    // JMAP_PAGE_SIZE and keeps a wide margin under it.
    private static final int SET_BATCH_MAX = 200;

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
                // Cap HARD at 5 minutes. The old cap was min(base*2, 60s<<min(f-1,6)):
                // with a poll_interval > ~32 min that resolves to 64 MINUTES, so a
                // burst of failures against a briefly-slow server (oci-mail JMAP
                // swings 1.8-7.4s under load) backed the account off for over an
                // hour and mail simply stopped fetching (measured 2026-09-02: zero
                // JMAP connects for 65 min after 22:05). A phone must retry within
                // minutes; a truly-down server just gets polled every 5 min, cheap.
                long BACKOFF_CAP_MS = 300_000L; // 5 min
                long wait = (consecutiveFailures <= 0 ? Math.min(base, BACKOFF_CAP_MS)
                        : Math.min(BACKOFF_CAP_MS, 60_000L << Math.min(consecutiveFailures - 1, 6)));
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

        // "Labels, not duplicate folders": a JMAP Email is ONE object with a
        // SET of mailboxIds. Only concrete ROLE folders (Inbox/Archive/Sent/
        // Drafts/Trash/Junk) drive message rows now; every USER mailbox is a
        // Sieve LABEL whose membership becomes EntityMessage.labels[] on the
        // single primary-folder row (isSyncingType below). This is the flip of
        // the old model, where every mailbox synced and one email filed into N
        // mailboxes became N rows (the invalidation-storm slowness). Existing
        // duplicate rows are collapsed once by collapseDuplicates().
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        for (Mailbox mb : mailboxes) {
            String name = fullName(mb, byId);
            String type = JmapService.roleToType(mb.getRole());
            EntityFolder folder = db.folder().getFolderByName(account.id, name);
            if (folder == null) {
                folder = new EntityFolder(name, type);
                folder.account = account.id;
                // Only role folders sync; USER (label) + health mailboxes never.
                folder.synchronize = isSyncingType(type) && !noPhoneSync(name);
                folder.subscribed = true;
                folder.poll = true; // JMAP is polled, never IDLE
                folder.download = true;
                folder.sync_days = EntityFolder.DEFAULT_SYNC;
                folder.keep_days = EntityFolder.DEFAULT_KEEP;
                folder.selectable = true;
                folder.id = db.folder().insertFolder(folder);
                EntityLog.log(context, "JMAP created folder=" + name + " type=" + type);
            } else if (noPhoneSync(name)) {
                // Existing probe folder: make sure sync is off AND drop its local
                // mirror rows. Not gated on the sync flag — on-device the folder
                // was already synchronize=false yet still carried 2699 stale rows
                // (2026-09-02), and those rows are the cost, not the flag.
                if (Boolean.TRUE.equals(folder.synchronize)) {
                    db.folder().setFolderSynchronize(folder.id, false);
                    folder.synchronize = false;
                }
                int purged = db.message().deleteMessagesKeep(folder.id, 0);
                if (purged > 0)
                    EntityLog.log(context, "JMAP no-phone-sync folder=" + name + " purged=" + purged);
            } else {
                // Never strand a role folder unsynced (a primary folder must
                // always sync). USER/label folders are switched OFF once by
                // collapseDuplicates() and then left to the user's choice.
                if (isSyncingType(type) && !noPhoneSync(name)
                        && !Boolean.TRUE.equals(folder.synchronize)) {
                    db.folder().setFolderSynchronize(folder.id, true);
                    folder.synchronize = true;
                    EntityLog.log(context, "JMAP enabled sync role folder=" + name);
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
        // Probe folders that are NO LONGER on the server never enter the mailbox
        // loop above, so their rows would outlive the folder forever. On-device
        // 2026-09-02: Cloud-Infra/Health/Checks/health_resources had left the
        // server yet still held 2699 local rows — every unread-count query paid
        // for them and no discovery pass could ever purge them. Walk the LOCAL
        // folder list too; idempotent, logs only when rows were removed.
        for (EntityFolder lf : db.folder().getFolders(account.id, false, false)) {
            if (lf == null || !noPhoneSync(lf.name))
                continue;
            if (Boolean.TRUE.equals(lf.synchronize))
                db.folder().setFolderSynchronize(lf.id, false);
            int before = db.message().countTotal(lf.id);
            int purged = db.message().deleteMessagesKeep(lf.id, 0);
            // deleteMessagesKeep excludes ui_flagged rows; the health-probe mails
            // came in flagged, so keep=0 deleted 0 and the folder stayed at 2699
            // (2026-09-03). Hard-delete everything in a no-phone-sync folder.
            if (purged < before)
                purged += db.message().deleteFolderMessages(lf.id);
            // Log UNCONDITIONALLY so a 0-purge is visible, not silent.
            EntityLog.log(context, "JMAP no-phone-sync (local) folder=" + lf.name
                    + " id=" + lf.id + " before=" + before + " purged=" + purged);
        }

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

        // Folder cache (id → EntityFolder) for primary/label resolution below.
        Map<Long, EntityFolder> folderById = buildFolderById(db, account.id);

        // One-time collapse of pre-rework duplicate rows into the labels model.
        // Idempotent + not fatal: on failure the pref stays unset so the next
        // pass retries, and the label-aware sync still runs. Parent takes a DB
        // volume backup before deploy regardless.
        String reworkKey = "jmap_label_rework_0303." + account.id;
        if (!prefs.getBoolean(reworkKey, false)) {
            try {
                collapseDuplicates(context, db, account, folderById);
                prefs.edit().putBoolean(reworkKey, true).apply();
            } catch (Throwable ex) {
                Log.e(account.name + " JMAP label rework", ex);
                EntityLog.log(context, "JMAP label rework failed: " + ex);
            }
        }

        // Account-wide dedup map (uidl = JMAP Email.id → local row id), built
        // AFTER the collapse so it reflects the single-row state. Shared and
        // mutated across the folder passes below so an insert made while syncing
        // one role folder is seen as "already stored" by the next.
        Map<String, Long> accountHave = buildAccountHave(db, account.id);

        // 2) Messages — per synchronized (role) folder, insert any not stored.
        String backfillKey = "comms_jmap_backfill_0064";
        boolean backfilled = prefs.getBoolean(backfillKey, false);
        // PHASE 1a: reconcile-only sweep over EVERY folder before anything
        // heavy: keyword sync + stale-mirror removals on existing rows. This
        // is the whole visible read/unread fix, and it must never wait behind
        // an insert flood or an op backlog.
        //
        // Ordered by LOCAL unseen count descending: on a starved edge a pass
        // gets only a few seconds of live connection before it drops, so the
        // folders that most need reconcile (the view mirrors hoarding stale
        // unread -- Ca Unread at 477) must spend that window FIRST, ahead of a
        // huge Inbox whose paged query would otherwise consume the whole
        // connection lifetime before the loop ever reached them. countUnseen
        // is a cheap local COUNT; the network cost per view folder is one
        // small query (server=7), so neediest-first clears the visible symptom
        // in a single short window even when the pass never completes.
        List<Map.Entry<Long, String>> sweepOrder = new ArrayList<>(folderToMailbox.entrySet());
        Map<Long, Integer> unseenByFolder = new HashMap<>();
        for (Map.Entry<Long, String> e : sweepOrder)
            unseenByFolder.put(e.getKey(), db.message().countUnseen(e.getKey()));
        Collections.sort(sweepOrder, (a, b) ->
                Integer.compare(unseenByFolder.getOrDefault(b.getKey(), 0),
                                unseenByFolder.getOrDefault(a.getKey(), 0)));
        for (Map.Entry<Long, String> e : sweepOrder) {
            EntityFolder folder = db.folder().getFolder(e.getKey());
            if (folder == null || !folder.synchronize)
                continue;
            try {
                syncMessages(context, account, folder, e.getValue(), jmap,
                        accountHave, mailboxToFolder, folderById, false);
            } catch (Throwable ex) {
                // One folder's timeout must not abort the sweep: on a starved
                // edge (rs.ltt.jmap's fixed ~10s call timeout, load-21 proxy)
                // a fatal per-folder failure meant folders late in HashMap
                // order NEVER got their reconcile — the same starvation the
                // sweep exists to end, reintroduced by error handling.
                Log.w(folder.name + " reconcile", ex);
                EntityLog.log(context, "JMAP " + folder.name + " reconcile skipped: "
                        + ex.getClass().getSimpleName());
            }
        }

        // Phase 1b (inserts) walks SMALLEST folder first. Measured 2026-09-02
        // on-device: HashMap order put the giant filter views (Ac 3482 rows,
        // Dd, Inbox) ahead, each insert costs ~2s (buildMessage + tx + BODY
        // op), so 24 House (200 rows, 273 missing) sat 30+ min behind them.
        // Ascending stored-count: small folders complete in the first minutes,
        // the giants backfill INSERT_CAP_PER_PASS per pass.
        List<Map.Entry<Long, String>> insertOrder = new ArrayList<>(folderToMailbox.entrySet());
        Map<Long, Integer> storedByFolder = new HashMap<>();
        for (Map.Entry<Long, String> e : insertOrder)
            storedByFolder.put(e.getKey(), db.message().countTotal(e.getKey()));
        Collections.sort(insertOrder, (a, b) -> Integer.compare(
                storedByFolder.getOrDefault(a.getKey(), 0), storedByFolder.getOrDefault(b.getKey(), 0)));
        for (Map.Entry<Long, String> e : insertOrder) {
            EntityFolder folder = db.folder().getFolder(e.getKey());
            if (folder == null || !folder.synchronize)
                continue;
            try {
                syncMessages(context, account, folder, e.getValue(), jmap,
                        accountHave, mailboxToFolder, folderById, true);
            } catch (Throwable ex) {
                Log.w(folder.name + " sync", ex);
                EntityLog.log(context, "JMAP " + folder.name + " sync skipped: "
                        + ex.getClass().getSimpleName());
                continue;
            }
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
            processOperations(context, account, folder, e.getValue(), mailboxToFolder, folderById, jmap);
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

    // [insertNew]=false is the reconcile-only mode: keywords + labels + removals
    // on EXISTING rows, no inserts. Phase 1a below runs it across every role
    // folder first because that is the entirety of what the user SEES (read
    // state converging, label chips updating); inserting new rows is invisible
    // bulk work that was starving it under list-query contention.
    //
    // "Labels, not duplicate folders": [accountHave] is the ACCOUNT-WIDE
    // uidl→row map (shared/mutated across folders this pass), so an email filed
    // into several mailboxes is stored ONCE. Its row lives in the PRIMARY
    // mailbox (highest role priority via primaryFolder); every other mailbox is
    // a label. [folder]/[mailboxId] is only the mailbox being read this call.
    private static void syncMessages(Context context, EntityAccount account,
                                     EntityFolder folder, String mailboxId, JmapService jmap,
                                     Map<String, Long> accountHave,
                                     Map<String, Long> mailboxToFolder,
                                     Map<Long, EntityFolder> folderById,
                                     boolean insertNew) throws Exception {
        DB db = DB.getInstance(context);

        // Rows whose HOME folder is this one -- used only by the removal pass.
        Map<String, Long> haveFolder = new HashMap<>();
        for (TupleUidl u : db.message().getUidls(folder.id))
            if (u.uidl != null)
                haveFolder.put(u.uidl, u.id);

        List<Email> emails = jmap.getFolderMessages(mailboxId, SYNC_LIMIT);
        EntityLog.log(context, "JMAP " + folder.name + ": server=" + emails.size()
                + " home=" + haveFolder.size() + " acct=" + accountHave.size());

        // A Stalwart JMAP mailbox is a server-recomputed VIEW, not a mutable
        // IMAP folder -- this pass mirrors both directions: new mail inserted,
        // removed mail deleted, already-stored keyword/label changes applied.
        Set<String> serverIds = new HashSet<>();
        int inserted = 0;
        boolean capLogged = false;
        for (Email email : emails) {
            if (email.getId() == null)
                continue;
            // Position-paged Email/query against a mailbox that is receiving
            // mail can return overlapping pages (positions shift between
            // requests), so the same id may appear twice in [emails] — seen on
            // device 2026-09-02 as double inserts of one uidl. add() returning
            // false = already handled this pass.
            if (!serverIds.add(email.getId()))
                continue;

            Long localId = accountHave.get(email.getId());
            if (localId != null) {
                // Intersection: pull the server's keyword + mailbox (label)
                // state onto the already-stored row (see reconcileKeywords).
                reconcileKeywords(db, localId, email, mailboxToFolder, folderById);
                continue;
            }

            if (!insertNew)
                continue; // reconcile-only pass: existing rows only

            if (inserted >= INSERT_CAP_PER_PASS) {
                if (!capLogged) {
                    capLogged = true;
                    EntityLog.log(context, "JMAP " + folder.name + " insert capped at "
                            + INSERT_CAP_PER_PASS + "/pass, remainder next pass");
                }
                continue;
            }
            inserted++;

            // The row homes to the email's PRIMARY mailbox, not necessarily the
            // folder being read (an email in Inbox+Archive discovered while
            // syncing Archive still homes to Inbox); the rest become labels.
            Long primaryId = primaryFolder(email, mailboxToFolder, folderById);
            EntityFolder primary = (primaryId == null ? null : folderById.get(primaryId));
            if (primary == null)
                primary = folder; // fallback: this mailbox is the only home
            Labels labels = labelsFor(email, primary.name, mailboxToFolder, folderById);

            EntityMessage message = buildMessage(account, primary, email, labels);
            try {
                db.beginTransaction();
                message.notifying = EntityMessage.NOTIFYING_IGNORE;
                message.id = db.message().insertMessage(message);
                db.setTransactionSuccessful();
                EntityLog.log(context, "JMAP added " + primary.name + " id=" + message.id
                        + " uidl=" + message.uidl
                        + (labels == null ? "" : " labels=" + TextUtils.join(",", labels)));
            } finally {
                db.endTransaction();
            }
            // Seen by the next role folder this pass so it dedups instead of
            // inserting a second row.
            accountHave.put(email.getId(), message.id);
            // Defer body to a BODY operation so the list populates fast.
            EntityOperation.queue(context, message, EntityOperation.BODY);
        }

        // home − server: a row homed in THIS folder that left this mailbox. Only
        // trustworthy on a COMPLETE result (< SYNC_LIMIT). Because dedup is
        // account-wide we only ever hold rows homed here, so a message that
        // merely dropped a LABEL (its home mailbox still lists it) is untouched;
        // a message that truly left (moved to another role folder, or deleted)
        // is removed here and, if it moved, re-inserted under its new home.
        boolean complete = emails.size() < SYNC_LIMIT;
        if (complete) {
            int removed = 0;
            for (Map.Entry<String, Long> e : haveFolder.entrySet())
                if (!serverIds.contains(e.getKey())) {
                    db.message().deleteMessage(e.getValue());
                    accountHave.remove(e.getKey());
                    removed++;
                }
            if (removed > 0)
                EntityLog.log(context, "JMAP " + folder.name + ": removed=" + removed + " (left mailbox)");
        } else {
            EntityLog.log(context, "JMAP " + folder.name +
                    ": server result capped at SYNC_LIMIT=" + SYNC_LIMIT + ", skipping removal reconcile");
        }
    }

    // Apply the server's $seen/$flagged keywords AND its mailbox membership
    // (labels) to an already-stored row. Seen/flagged are guarded by any pending
    // local operation that would fight them -- same guard shape as the IMAP sync
    // (Core.java ~5207 for SEEN, ~5227 for FLAG): a queued SEEN/FLAG op means the
    // user changed it HERE and the change has not reached the server yet, so the
    // server's answer is stale, not wrong. The guard uses the row's HOME folder
    // (message.folder), where such ops are queued. Labels are server-
    // authoritative (a Sieve rule adds/removes a mailbox at any time; the app has
    // no per-label pending op), so they are mirrored unconditionally.
    private static void reconcileKeywords(DB db, long messageId, Email email,
                                          Map<String, Long> mailboxToFolder,
                                          Map<Long, EntityFolder> folderById) {
        EntityMessage message = db.message().getMessage(messageId);
        if (message == null)
            return;

        Map<String, Boolean> kw = email.getKeywords();
        boolean seen = hasKeyword(kw, Keyword.SEEN);
        boolean flagged = hasKeyword(kw, Keyword.FLAGGED);

        if (!message.seen.equals(seen) &&
                db.operation().getOperationCount(message.folder, message.id, EntityOperation.SEEN) == 0) {
            db.message().setMessageSeen(message.id, seen);
            db.message().setMessageUiSeen(message.id, seen);
        }

        if (!message.flagged.equals(flagged) &&
                db.operation().getOperationCount(message.folder, message.id, EntityOperation.FLAG) == 0) {
            db.message().setMessageFlagged(message.id, flagged);
            db.message().setMessageUiFlagged(message.id, flagged, flagged ? message.color : null);
        }

        // Labels = the email's OTHER mailbox memberships (Gmail labels[] reuse).
        EntityFolder home = folderById.get(message.folder);
        Labels labels = labelsFor(email, (home == null ? null : home.name),
                mailboxToFolder, folderById);
        String[] names = (labels == null ? null : labels.names);
        String[] ids = (labels == null ? null : labels.ids);
        if (!Helper.equal(message.labels, names))
            db.message().setMessageLabels(message.id, DB.Converters.fromStringArray(names));
        // Backfills label_ids for rows that predate the column, and is what
        // makes a USER category mailbox list this message (DaoMessage.in_folder).
        if (!Helper.equal(message.label_ids, ids))
            db.message().setMessageLabelIds(message.id, DB.Converters.fromStringArray(ids));
    }

    // Build an EntityMessage from a JMAP Email header. Server-id → uidl (POP
    // analog); keywords → seen/flagged/answered; threadId is used verbatim.
    // [folder] is the email's PRIMARY mailbox; [labels] its other memberships.
    private static EntityMessage buildMessage(EntityAccount account, EntityFolder folder,
                                              Email email, Labels labels) {
        EntityMessage m = new EntityMessage();
        m.account = account.id;
        m.folder = folder.id;
        m.uid = null;                 // JMAP has no IMAP UID
        m.uidl = email.getId();       // stable server id for dedup
        // A JMAP Email is ONE object with a set of mailboxIds -- a Sieve
        // `fileinto :copy` adds a mailbox to that set, it does not copy the
        // mail. This row lives in the PRIMARY mailbox; the other memberships
        // ride along as labels[] (the Gmail label machinery), so an email filed
        // into N mailboxes is ONE row with N-1 label chips, not N rows.
        m.labels = (labels == null ? null : labels.names);
        m.label_ids = (labels == null ? null : labels.ids);
        // hash stays keyed on Email.id (identical across every mailbox holding
        // the email) so any residual duplicate collapses in the conversation
        // view via FragmentMessages.markDuplicates.
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
        storeBody(context, db, message, email);
    }

    // Persist one fetched body. Split out of onBody so the coalesced fetch
    // (flushBodies) shares the identical local write path.
    private static void storeBody(Context context, DB db, EntityMessage message, Email email)
            throws Exception {
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
                                          Map<Long, EntityFolder> folderById,
                                          JmapService jmap) throws Exception {
        DB db = DB.getInstance(context);
        List<EntityOperation> ops = db.operation().getOperationsByFolder(folder.id);
        if (ops == null)
            return;

        // Coalescing buffer. Each queued flag/move/delete used to be its own
        // POST -- ~600ms per round trip from a phone in DE to the store in
        // Marseille via the Iowa hub, so marking 50 messages read cost ~30s.
        // A RUN OF CONSECUTIVE ops sharing a batch signature is now sent as ONE
        // Email/set (see describeBatch for why that cannot change the outcome).
        SetBatch batch = null;

        for (EntityOperation op : ops) {
            EntityMessage message = (op.message == null ? null : db.message().getMessage(op.message));
            SetBatch shape = describeBatch(op, message, mailboxToFolder);

            if (shape != null && batch != null && shape.key.equals(batch.key)
                    && batch.ops.size() < batch.max) {
                batch.ops.add(op);
                batch.ids.add(message.uidl);
                continue;
            }

            // Signature changed (or the op is not coalescable): everything
            // buffered must reach the server BEFORE this op does, or ordering
            // would be violated.
            flushBatch(context, db, folder, jmap, batch);
            batch = null;

            if (shape != null) {
                shape.ops.add(op);
                shape.ids.add(message.uidl);
                batch = shape;
                continue;
            }

            try {
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
                        // does per folder. Account-wide dedup map rebuilt here
                        // (an ad-hoc SYNC is rare, off the poll's shared map).
                        syncMessages(context, account, folder, mailboxId, jmap,
                                buildAccountHave(db, account.id), mailboxToFolder, folderById, true);
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
                failOperation(context, db, folder, op, ex);
            }
        }

        flushBatch(context, db, folder, jmap, batch);
    }

    // A run of consecutive same-shaped operations sent as ONE Email/set.
    private static class SetBatch {
        String key;      // coalescing signature; equal keys mean an identical patch
        String name;     // EntityOperation name
        boolean value;   // SEEN / FLAG / KEYWORD boolean
        String keyword;  // KEYWORD name
        String mailbox;  // MOVE target mailbox id
        int max = SET_BATCH_MAX;
        final List<EntityOperation> ops = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
    }

    // The coalescing signature of one operation, or null when it must be run on
    // its own.
    //
    // WHY THIS CANNOT LOSE OR MISLABEL MAIL: two operations only ever merge when
    // they are ADJACENT in the queue AND carry the SAME key, i.e. the exact same
    // Email/set patch ($seen=true, or mailboxIds={X:true}, or destroy). Applying
    // one identical patch to N ids in a single call and applying it to each id in
    // N calls produce the same server state: the patch is per-id, independent of
    // the other ids, and idempotent if an id repeats. Because a differing key
    // (or a non-coalescable op) FLUSHES the buffer first, relative order is never
    // disturbed -- SEEN(a) → MOVE(a,Trash) → SEEN(a,false) stays three calls in
    // that order, and a MOVE to Trash is never reordered past a MOVE to Archive.
    // null is returned for anything ambiguous (missing message, missing uidl,
    // malformed args, unresolvable move target) so the single-op path below
    // handles it exactly as before.
    private static SetBatch describeBatch(EntityOperation op, EntityMessage message,
                                          Map<String, Long> mailboxToFolder) {
        if (message == null || message.uidl == null || op.name == null)
            return null;
        SetBatch b = new SetBatch();
        b.name = op.name;
        try {
            switch (op.name) {
                case EntityOperation.SEEN:
                    b.value = Boolean.TRUE.equals(message.ui_seen);
                    b.key = "SEEN:" + b.value;
                    return b;
                case EntityOperation.FLAG:
                    b.value = Boolean.TRUE.equals(message.ui_flagged);
                    b.key = "FLAG:" + b.value;
                    return b;
                case EntityOperation.KEYWORD: {
                    org.json.JSONArray jargs = new org.json.JSONArray(op.args);
                    b.keyword = jargs.getString(0);
                    b.value = jargs.getBoolean(1);
                    b.key = "KEYWORD:" + b.value + ":" + b.keyword;
                    return b;
                }
                case EntityOperation.MOVE: {
                    long target = new org.json.JSONArray(op.args).getLong(0);
                    b.mailbox = mailboxForFolder(mailboxToFolder, target);
                    if (b.mailbox == null)
                        return null;
                    b.key = "MOVE:" + b.mailbox;
                    return b;
                }
                case EntityOperation.DELETE:
                    b.key = "DELETE";
                    return b;
                case EntityOperation.BODY:
                    // A pure read: no server state is touched, so batching it
                    // cannot lose or mislabel anything. Bounded by COUNT (bodies
                    // have no size ceiling) rather than by maxBodyValueBytes,
                    // because a truncated body would be silently mangled mail.
                    b.key = "BODY";
                    b.max = JmapService.BODY_BATCH_MAX;
                    return b;
                default:
                    return null;
            }
        } catch (Throwable ex) {
            return null; // malformed args: the single-op path records the error
        }
    }

    // Send the buffered run as one Email/set and settle every operation in it.
    private static void flushBatch(Context context, DB db, EntityFolder folder,
                                   JmapService jmap, SetBatch batch) {
        if (batch == null || batch.ops.isEmpty())
            return;
        if (EntityOperation.BODY.equals(batch.name)) {
            flushBodies(context, db, folder, jmap, batch);
            return;
        }
        try {
            switch (batch.name) {
                case EntityOperation.SEEN:
                    jmap.setSeen(batch.ids, batch.value);
                    break;
                case EntityOperation.FLAG:
                    jmap.setFlagged(batch.ids, batch.value);
                    break;
                case EntityOperation.KEYWORD:
                    jmap.setKeyword(batch.ids, batch.keyword, batch.value);
                    break;
                case EntityOperation.MOVE:
                    jmap.moveToMailbox(batch.ids, batch.mailbox);
                    break;
                case EntityOperation.DELETE:
                    jmap.deleteMessages(batch.ids);
                    break;
                default:
                    throw new IllegalStateException("JMAP unbatchable op=" + batch.name);
            }
            for (EntityOperation op : batch.ops)
                db.operation().deleteOperation(op.id);
            if (batch.ops.size() > 1)
                EntityLog.log(context, "JMAP " + folder.name + " coalesced "
                        + batch.ops.size() + " ops into one Email/set (" + batch.key + ")");
        } catch (Throwable ex) {
            // The batch is ONE call: it either applied to every id or to none,
            // so on failure NO operation is deleted. Each is errored and retried
            // exactly as the single-op path does, and the local ui_* state still
            // holds the user's intent until it lands. Nothing is lost.
            for (EntityOperation op : batch.ops)
                failOperation(context, db, folder, op, ex);
        }
    }

    // Fetch a run of queued bodies in ONE Email/get, then persist them locally.
    //
    // WHY THIS CANNOT LOSE MAIL: Email/get is a read. If the request fails, no
    // operation is deleted and every one retries. If it succeeds but the server
    // omits an id, only THAT operation fails -- exactly what the per-message
    // path did when getMessageBody returned null -- and it retries on the next
    // pass. A body is never marked stored unless its own bytes were written.
    private static void flushBodies(Context context, DB db, EntityFolder folder,
                                    JmapService jmap, SetBatch batch) {
        Map<String, Email> bodies;
        try {
            bodies = jmap.getMessageBodies(batch.ids);
        } catch (Throwable ex) {
            for (EntityOperation op : batch.ops)
                failOperation(context, db, folder, op, ex);
            return;
        }
        for (EntityOperation op : batch.ops)
            try {
                EntityMessage message = (op.message == null ? null : db.message().getMessage(op.message));
                if (message == null) {
                    db.operation().deleteOperation(op.id);
                    continue;
                }
                Email email = bodies.get(message.uidl);
                if (email == null)
                    throw new IllegalArgumentException("JMAP body missing uidl=" + message.uidl);
                storeBody(context, db, message, email);
                db.operation().deleteOperation(op.id);
            } catch (Throwable ex) {
                failOperation(context, db, folder, op, ex);
            }
        if (batch.ops.size() > 1)
            EntityLog.log(context, "JMAP " + folder.name + " coalesced "
                    + batch.ops.size() + " bodies into one Email/get");
    }

    // One poisoned op must not wedge every other op behind it in this folder --
    // isolate per-op instead of throwing out of the loop. Retried up to
    // OP_RETRY_MAX passes, then dropped (mirrors EntityOperation.cleanup so ui
    // state is re-asserted instead of left stuck mid-operation).
    private static void failOperation(Context context, DB db, EntityFolder folder,
                                      EntityOperation op, Throwable ex) {
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

    // ── labels model helpers ────────────────────────────────────────────────
    // A mailbox drives message rows only when it is a concrete ROLE folder.
    // Every USER (label) + SYSTEM mailbox becomes labels[] on the primary row.
    private static boolean isSyncingType(String type) {
        return EntityFolder.INBOX.equals(type)
                || EntityFolder.ARCHIVE.equals(type)
                || EntityFolder.SENT.equals(type)
                || EntityFolder.DRAFTS.equals(type)
                || EntityFolder.JUNK.equals(type)
                || EntityFolder.TRASH.equals(type);
    }

    // Role priority for choosing an email's PRIMARY folder among the mailboxes
    // it is filed into (lower = higher priority): INBOX > ARCHIVE(All) > SENT >
    // DRAFTS > TRASH > JUNK > USER > other.
    private static int folderPriority(String type) {
        if (EntityFolder.INBOX.equals(type)) return 0;
        if (EntityFolder.ARCHIVE.equals(type)) return 1;
        if (EntityFolder.SENT.equals(type)) return 2;
        if (EntityFolder.DRAFTS.equals(type)) return 3;
        if (EntityFolder.TRASH.equals(type)) return 4;
        if (EntityFolder.JUNK.equals(type)) return 5;
        if (EntityFolder.USER.equals(type)) return 6;
        return 7;
    }

    // The folder id of the email's primary mailbox (highest role priority among
    // the mailboxes it is filed into that map to a local, non-health folder).
    // null when none qualify (caller falls back to the mailbox being read).
    private static Long primaryFolder(Email email, Map<String, Long> mailboxToFolder,
                                      Map<Long, EntityFolder> folderById) {
        Map<String, Boolean> mids = email.getMailboxIds();
        if (mids == null)
            return null;
        Long best = null;
        int bestP = Integer.MAX_VALUE;
        for (Map.Entry<String, Boolean> e : mids.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue()))
                continue;
            Long fid = mailboxToFolder.get(e.getKey());
            if (fid == null)
                continue;
            EntityFolder f = folderById.get(fid);
            if (f == null || noPhoneSync(f.name))
                continue;
            // Only a SYNCING folder may be a home. A message filed ONLY into a
            // category (no INBOX membership -- what a Sieve rule that MOVES
            // rather than copies produces) used to home to a USER mailbox that
            // never syncs, so the removal pass never saw it and the message was
            // invisible everywhere. Returning null here instead makes the caller
            // fall back to the folder actually being swept, which is a syncing
            // role folder by construction, and the category still shows the
            // message via label_ids. Nothing can home somewhere unreachable.
            if (!isSyncingType(f.type))
                continue;
            int p = folderPriority(f.type);
            // Tie-break on folder id for a deterministic, stable choice.
            if (p < bestP || (p == bestP && best != null && fid < best)) {
                bestP = p;
                best = fid;
            }
        }
        return best;
    }

    // The email's OTHER mailbox memberships as label display-names (the Gmail
    // labels[] reuse: AdapterMessage.getLabels renders these as chips,
    // TupleMessageEx.resolveLabelColors colours them from label.color.<name>).
    // The primary folder's own name is excluded (that is the row's home, not a
    // label), as are health-probe mailboxes. Sorted so the order-sensitive
    // Helper.equal reconcile is a no-op when membership is unchanged.
    // The display names and the folder ids of every NON-primary mailbox this
    // email belongs to, built together from ONE walk of the server's mailboxIds
    // so the chips the user sees and the membership DaoMessage.in_folder lists
    // on can never disagree. names[i] and ids[i] describe the same mailbox.
    private static class Labels {
        final String[] names;
        final String[] ids;

        Labels(String[] names, String[] ids) {
            this.names = names;
            this.ids = ids;
        }
    }

    private static Labels labelsFor(Email email, String primaryName,
                                    Map<String, Long> mailboxToFolder,
                                    Map<Long, EntityFolder> folderById) {
        Map<String, Boolean> mids = email.getMailboxIds();
        if (mids == null)
            return null;
        // Sorted + deduplicated by name, preserving the previous ordering.
        Map<String, Long> out = new java.util.TreeMap<>();
        for (Map.Entry<String, Boolean> e : mids.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue()))
                continue;
            Long fid = mailboxToFolder.get(e.getKey());
            if (fid == null)
                continue;
            EntityFolder f = folderById.get(fid);
            if (f == null || f.name == null || noPhoneSync(f.name))
                continue;
            if (f.name.equals(primaryName))
                continue;
            if (!out.containsKey(f.name))
                out.put(f.name, fid);
        }
        if (out.isEmpty())
            return null;
        String[] names = new String[out.size()];
        String[] ids = new String[out.size()];
        int i = 0;
        for (Map.Entry<String, Long> e : out.entrySet()) {
            names[i] = e.getKey();
            ids[i] = Long.toString(e.getValue());
            i++;
        }
        return new Labels(names, ids);
    }

    // id → EntityFolder for every folder of the account.
    private static Map<Long, EntityFolder> buildFolderById(DB db, long account) {
        Map<Long, EntityFolder> m = new HashMap<>();
        for (EntityFolder f : db.folder().getFolders(account, false, false))
            if (f != null)
                m.put(f.id, f);
        return m;
    }

    // Account-wide uidl (JMAP Email.id) → local row id. First writer wins so a
    // (post-collapse) residual duplicate does not overwrite the kept row.
    private static Map<String, Long> buildAccountHave(DB db, long account) {
        Map<String, Long> have = new HashMap<>();
        for (EntityFolder f : db.folder().getFolders(account, false, false))
            for (TupleUidl u : db.message().getUidls(f.id))
                if (u.uidl != null && !have.containsKey(u.uidl))
                    have.put(u.uidl, u.id);
        return have;
    }

    // One-time migration to the "labels, not duplicate folders" model. Before
    // this build a JMAP email filed into N mailboxes was stored as N rows (one
    // per folder) -- 51,955 rows for 17,632 threads on device, the root of the
    // RoomTrackingLiveData invalidation storm ("extremely slow"). For each set
    // of duplicate rows keep the single PRIMARY-folder row, fold the other rows'
    // folder names into its labels[], and delete the duplicates; switch every
    // USER/label + SYSTEM/health mailbox to synchronize=false so nothing
    // re-inflates them.
    //
    // Safe + idempotent: Stalwart stays the source of truth, the kept row keeps
    // its ui_seen/ui_flagged, deleteMessage() cascades the dup rows'
    // operations/attachments (FK ON DELETE CASCADE), and after a run every uidl
    // has one row so a re-run (or a reset pref, or a crash-resumed partial run)
    // is a no-op. Per-row deletes (not one giant transaction) so a mid-run crash
    // keeps its progress. Parent takes a DB volume backup before deploy anyway.
    private static void collapseDuplicates(Context context, DB db, EntityAccount account,
                                           Map<Long, EntityFolder> folderById) {
        // 1) De-sync everything that is not a role folder.
        for (EntityFolder f : folderById.values()) {
            boolean shouldSync = isSyncingType(f.type) && !noPhoneSync(f.name);
            if (!shouldSync && Boolean.TRUE.equals(f.synchronize)) {
                db.folder().setFolderSynchronize(f.id, false);
                f.synchronize = false;
                EntityLog.log(context, "JMAP label rework: desync folder=" + f.name + " type=" + f.type);
            }
        }

        // 2) Group every stored row by uidl (account-wide) → list of {msgId, folderId}.
        Map<String, List<long[]>> byUidl = new HashMap<>();
        for (EntityFolder f : folderById.values())
            for (TupleUidl u : db.message().getUidls(f.id))
                if (u.uidl != null) {
                    List<long[]> l = byUidl.get(u.uidl);
                    if (l == null) {
                        l = new ArrayList<>();
                        byUidl.put(u.uidl, l);
                    }
                    l.add(new long[]{u.id, f.id});
                }

        int collapsed = 0, dropped = 0;
        for (Map.Entry<String, List<long[]>> e : byUidl.entrySet()) {
            List<long[]> rows = e.getValue();
            if (rows.size() < 2)
                continue;

            // Primary = row in the highest-priority folder; tie-break lowest
            // msgId for a deterministic (idempotent) choice across re-runs.
            long[] primary = null;
            int bestP = Integer.MAX_VALUE;
            for (long[] r : rows) {
                EntityFolder f = folderById.get(r[1]);
                int p = folderPriority(f == null ? null : f.type);
                if (p < bestP || (p == bestP && (primary == null || r[0] < primary[0]))) {
                    bestP = p;
                    primary = r;
                }
            }
            EntityFolder primaryFolder = folderById.get(primary[1]);
            String primaryName = (primaryFolder == null ? null : primaryFolder.name);

            EntityMessage pm = db.message().getMessage(primary[0]);
            if (pm == null)
                continue;

            // Fold the other rows' folder names into labels[] (union with any
            // labels already present) then delete the duplicate rows.
            List<String> labels = new ArrayList<>();
            if (pm.labels != null)
                for (String s : pm.labels)
                    if (s != null && !labels.contains(s))
                        labels.add(s);
            for (long[] r : rows) {
                if (r == primary)
                    continue;
                EntityFolder f = folderById.get(r[1]);
                if (f != null && f.name != null && !noPhoneSync(f.name)
                        && !f.name.equals(primaryName) && !labels.contains(f.name))
                    labels.add(f.name);
                db.message().deleteMessage(r[0]);
                dropped++;
            }
            Collections.sort(labels);
            String[] arr = (labels.isEmpty() ? null : labels.toArray(new String[0]));
            if (!Helper.equal(pm.labels, arr))
                db.message().setMessageLabels(pm.id, DB.Converters.fromStringArray(arr));
            collapsed++;
        }
        EntityLog.log(context, "JMAP label rework: collapsed=" + collapsed + " dropped=" + dropped
                + " account=" + account.name);
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
