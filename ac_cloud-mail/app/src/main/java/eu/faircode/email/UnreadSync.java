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

import androidx.annotation.NonNull;

import com.sun.mail.imap.IMAPFolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.mail.Flags;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.search.FlagTerm;

// comms: the unread lane's server-side read-state probe.
//
// Kept in its own class, NOT folded into Core.onSynchronizeMessages. That
// method owns uid reconciliation for every account on the device, and a bug
// there does not show a wrong list -- it loses or duplicates mail. Nothing
// here touches that path: it reads the server's idea of "unread", compares it
// to rows that already exist, and adjusts ui_seen only where that is provably
// safe.
//
// It never creates or deletes messages. A server id with no local row is a sync
// gap, which is the normal sync's job to close -- reported here, not invented.
public class UnreadSync {
    // A folder's unread set is small next to the folder itself, but a runaway
    // mailbox should not be able to pull an unbounded id list into memory.
    static final int MAX_UNREAD = 5000;

    // ── Server queries ───────────────────────────────────────────────────────

    // IMAP: a standalone SEARCH UNSEEN.
    //
    // Core builds FlagTerm(SEEN, false) too, but OR'd into a date term to WIDEN
    // a bounded sync. This asks the different question the unread lane needs:
    // everything the server currently considers unseen, unbounded by date.
    //
    // Core falls back to a date-only search when a server rejects its compound
    // term, which is evidence that some servers are fragile about SEARCH. A
    // bare UNSEEN is about as simple as IMAP search gets, but a server that
    // still refuses it must not take the sync down with it -- so the caller
    // treats a throw here as "no server opinion", not as an error.
    @NonNull
    static List<Long> fetchUnreadUids(IMAPFolder ifolder) throws MessagingException {
        List<Long> uids = new ArrayList<>();

        Message[] imessages = ifolder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        if (imessages == null)
            return uids;

        for (Message imessage : imessages) {
            if (uids.size() >= MAX_UNREAD) {
                Log.w("UnreadSync: capped at " + MAX_UNREAD + " uids");
                break;
            }
            long uid = ifolder.getUID(imessage);
            if (uid > 0)
                uids.add(uid);
        }

        return uids;
    }

    // JMAP: Email/query with notKeyword $seen. See JmapService.getFolderUnreadIds.
    @NonNull
    static List<String> fetchUnreadIds(JmapService jmap, String mailboxId) throws MessagingException {
        return jmap.getFolderUnreadIds(mailboxId, MAX_UNREAD);
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    // Apply the server's read state to local rows of one folder, in both
    // directions.
    //
    // THE RULE, and why:
    //
    //   Direction 1 (seen -> unread): a message the server calls unseen, that
    //   the device has as seen, is normally the device being stale -- read
    //   state changed in another client. Server wins, and the row goes back
    //   to unread.
    //
    //   EXCEPT when a SEEN operation for that message is still queued. That
    //   means the user read it HERE and the flag has not been pushed yet. The
    //   server is not stale in that case, it is merely behind, and taking its
    //   answer would resurrect mail the user already read -- then the queued
    //   operation would push it read again, so the message would flicker back
    //   and forth. Local wins, and the pending operation settles it.
    //
    //   Direction 2 (unread -> seen), the opposite case: a message the device
    //   still shows unread that the server no longer lists as unseen was read
    //   in another client. Same pending-SEEN guard applies (local wins over a
    //   queued-but-not-yet-pushed change either way).
    //
    //   Direction 2 additionally requires [complete] -- the caller's promise
    //   that the server query returned every unseen id, not a result capped at
    //   MAX_UNREAD (see fetchUnreadUids/fetchUnreadIds). "This id was not in
    //   the list" is only proof of "read" when the list was exhaustive; against
    //   a capped result it is equally consistent with "past the cap", and
    //   direction 2 would then wrongly mark still-unread mail read. Direction 1
    //   needs no such guard: every id it acts on was positively returned by the
    //   server, cap or not.
    //
    // Returns the number of rows changed.
    static int reconcile(Context context, EntityFolder folder, @NonNull Set<Long> serverUnreadIds) {
        // ponytail: back-compat overload for the IMAP caller (Core.java), which
        // this task does not touch -- keeps direction 2 off there, same as
        // before this change.
        return reconcile(context, folder, serverUnreadIds, false);
    }

    static int reconcile(Context context, EntityFolder folder, @NonNull Set<Long> serverUnreadIds, boolean complete) {
        DB db = DB.getInstance(context);

        Set<Long> pendingSeen = getPendingSeen(db, folder.id);

        int changed = 0;
        int skipped = 0;

        try {
            db.beginTransaction();

            for (Long id : serverUnreadIds) {
                EntityMessage message = db.message().getMessage(id);
                if (message == null)
                    continue;

                // Already unread locally: nothing to do.
                if (!message.ui_seen)
                    continue;

                if (pendingSeen.contains(message.id)) {
                    skipped++;
                    continue;
                }

                db.message().setMessageUiSeen(message.id, false);
                changed++;
            }

            if (complete)
                for (TupleUidl u : db.message().getUidls(folder.id)) {
                    if (serverUnreadIds.contains(u.id))
                        continue; // server still calls it unread

                    if (pendingSeen.contains(u.id)) {
                        skipped++;
                        continue;
                    }

                    EntityMessage message = db.message().getMessage(u.id);
                    if (message == null || message.ui_seen)
                        continue; // already seen locally: nothing to do

                    db.message().setMessageSeen(message.id, true);
                    db.message().setMessageUiSeen(message.id, true);
                    changed++;
                }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (changed > 0 || skipped > 0)
            EntityLog.log(context, EntityLog.Type.General, folder.name +
                    " unread reconcile changed=" + changed + " kept_local=" + skipped +
                    " complete=" + complete);

        return changed;
    }

    // Message ids in this folder with a SEEN operation still queued.
    @NonNull
    private static Set<Long> getPendingSeen(DB db, long folder) {
        Set<Long> ids = new HashSet<>();
        List<EntityOperation> ops =
                db.operation().getOperationsByFolder(folder, EntityOperation.SEEN);
        if (ops != null)
            for (EntityOperation op : ops)
                if (op.message != null)
                    ids.add(op.message);
        return ids;
    }
}
