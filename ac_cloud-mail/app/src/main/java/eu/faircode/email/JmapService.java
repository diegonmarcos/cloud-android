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

import androidx.annotation.NonNull;

import com.google.common.net.MediaType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.mail.MessagingException;

import okhttp3.HttpUrl;
import rs.ltt.jmap.client.JmapClient;
import rs.ltt.jmap.client.JmapRequest;
import rs.ltt.jmap.client.MethodResponses;
import rs.ltt.jmap.client.blob.Uploadable;
import rs.ltt.jmap.client.session.Session;
import rs.ltt.jmap.common.entity.Email;
import rs.ltt.jmap.common.entity.EmailImport;
import rs.ltt.jmap.common.entity.EmailSubmission;
import rs.ltt.jmap.common.entity.Identity;
import rs.ltt.jmap.common.entity.Keyword;
import rs.ltt.jmap.common.entity.Mailbox;
import rs.ltt.jmap.common.entity.Role;
import rs.ltt.jmap.common.entity.Upload;
import rs.ltt.jmap.common.entity.Comparator;
import rs.ltt.jmap.common.entity.capability.MailAccountCapability;
import rs.ltt.jmap.common.entity.filter.EmailFilterCondition;
import rs.ltt.jmap.common.method.call.email.GetEmailMethodCall;
import rs.ltt.jmap.common.method.call.email.ImportEmailMethodCall;
import rs.ltt.jmap.common.method.call.email.QueryEmailMethodCall;
import rs.ltt.jmap.common.method.call.email.SetEmailMethodCall;
import rs.ltt.jmap.common.method.call.identity.GetIdentityMethodCall;
import rs.ltt.jmap.common.method.call.mailbox.GetMailboxMethodCall;
import rs.ltt.jmap.common.method.call.mailbox.SetMailboxMethodCall;
import rs.ltt.jmap.common.method.call.submission.SetEmailSubmissionMethodCall;
import rs.ltt.jmap.common.method.response.email.GetEmailMethodResponse;
import rs.ltt.jmap.common.method.response.email.QueryEmailMethodResponse;
import rs.ltt.jmap.common.method.response.email.ImportEmailMethodResponse;
import rs.ltt.jmap.common.method.response.identity.GetIdentityMethodResponse;
import rs.ltt.jmap.common.method.response.mailbox.GetMailboxMethodResponse;

// comms: JMAP connection + data-plane helper wrapping rs.ltt.jmap.client.JmapClient.
//
// Batch 1 (0006) — connection + session validation.
// Batch 2 (0007/0008) — wired into EmailService.connect() as a parallel field.
// Batch 3 (this) — the DATA-PLANE: resolve the primary mail account, fetch
//   mailboxes (→ EntityFolder), fetch message headers (Query+Get via a single
//   MultiCall back-reference), lazy body fetch, and Email/set mutations
//   (keywords seen/flagged, move, destroy). This is what Core's JMAP sync
//   branch (batch 4, ServiceSynchronize:~1626) drives; send (EmailSubmission)
//   lands in batch 5.
//
// All JMAP method calls surface failures as javax.mail.MessagingException so
// callers treat them uniformly with the IMAP/POP paths.
public class JmapService {
    // Email/get properties fetched for the message list — headers only; the
    // body is fetched lazily per-message (getMessageBody) to keep sync cheap.
    private static final String[] EMAIL_HEADER_PROPERTIES = new String[]{
            "id", "blobId", "threadId", "mailboxIds", "keywords",
            "from", "to", "cc", "bcc", "replyTo", "subject",
            "receivedAt", "sentAt", "size", "preview", "hasAttachment",
            "messageId", "inReplyTo", "references"
    };

    // Body fetch properties. textBody / htmlBody / bodyValues are NOT in the
    // RFC 8621 default Email property set — so an Email/get with
    // fetch{Text,HTML}BodyValues(true) but no explicit `properties` returns a
    // response missing the body-part references the fetch flags point at. That
    // malformed shape NPEs deep inside rs.ltt.jmap deserialization
    // ("getClass() on a null object reference"). Requesting the body properties
    // explicitly is the RFC-correct fix (mirrors EMAIL_HEADER_PROPERTIES).
    private static final String[] EMAIL_BODY_PROPERTIES = new String[]{
            "id", "textBody", "htmlBody", "bodyValues"
    };

    private final Context context;
    private final String host;
    private final int port;
    private final String user;

    private JmapClient client;
    private String accountId; // resolved MailAccountCapability primary account
    private String password; // comms: kept only to sign the raw Sieve HTTP calls below (no typed lib support)

    JmapService(Context context, EntityAccount account) {
        this(context, account.host, account.port, account.user);
    }

    JmapService(Context context, String host, Integer port, String user) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.port = (port == null ? 443 : port);
        this.user = user;
    }

    // How many times to (re)try resolving the JMAP session. rs.ltt.jmap 0.8.10
    // exposes no timeout knob, so it uses okhttp's ~10s read timeout — but the
    // public WG/TLS edge can take 14-26s COLD (warm = <1s). The first attempt
    // warms the path (even if it times out); a retry then succeeds. This is the
    // clean resilience for a slow-cold edge, not a hack.
    private static final int CONNECT_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_DELAY_MS = 3000;

    // Raw-call timeouts. Same cold-edge reality the retry loop above exists
    // for: measured 2026-09-02 from a phone on hostel wifi -> WG -> mesh, the
    // IDENTICAL session request took 1.1s, 5.9s and 20.4s on three consecutive
    // tries, with the variance entirely in connect+TLS (raw TCP to
    // oci-mail:2443 was 0.3s, so the server is not the problem -- a lossy link
    // retransmitting handshakes is). At a flat 15s the sync failed with
    // SocketTimeoutException against a perfectly healthy server, which reads to
    // the user as "mail is broken".
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    // Open a JMAP client and validate it by resolving the session resource +
    // the primary mail account id (RFC 8620 §2). Verbose: every step + the FULL
    // root-cause chain is logged to EntityLog (visible in the app's log viewer)
    // so failures are diagnosable, not a bare "Failed".
    void connect(String password) throws MessagingException {
        this.password = password;
        String sessionResourceUrl = "https://" + host + ":" + port + "/.well-known/jmap";
        HttpUrl sessionResource = HttpUrl.get(sessionResourceUrl);
        EntityLog.log(context, "JMAP connecting user=" + user + " → " + sessionResourceUrl);

        Throwable last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            try {
                client = new JmapClient(user, password, sessionResource);
                Session session = client.getSession().get();
                if (session == null)
                    throw new MessagingException("JMAP session resolved null");
                accountId = session.getPrimaryAccount(MailAccountCapability.class);
                if (TextUtils.isEmpty(accountId))
                    throw new MessagingException("JMAP session has no mail account (urn:...:jmap:mail) for " + user);
                EntityLog.log(context, "JMAP connected user=" + user + " account=" + accountId +
                        " attempt=" + attempt + "/" + CONNECT_ATTEMPTS);
                return;
            } catch (Throwable ex) {
                last = unwrap(ex);
                close();
                EntityLog.log(context, "JMAP connect attempt " + attempt + "/" + CONNECT_ATTEMPTS +
                        " FAILED: " + describe(last) + " (" + sessionResourceUrl + ")");
                Log.w("JMAP connect attempt " + attempt + " " + sessionResourceUrl, ex);
                if (attempt < CONNECT_ATTEMPTS)
                    try {
                        Thread.sleep(CONNECT_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
            }
        }
        throw new MessagingException("JMAP connect failed after " + CONNECT_ATTEMPTS +
                " attempts: " + sessionResourceUrl + " — " + describe(last), asException(last));
    }

    // Unwrap Future/Completion wrappers to the real cause (SocketTimeout, auth, TLS…).
    static Throwable unwrap(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof java.util.concurrent.ExecutionException
                || t instanceof java.util.concurrent.CompletionException)
                && t.getCause() != null && t.getCause() != t)
            t = t.getCause();
        return t;
    }

    // Human cause chain, e.g. "SocketTimeoutException: timeout ← ...".
    static String describe(Throwable ex) {
        if (ex == null)
            return "unknown error";
        StringBuilder sb = new StringBuilder();
        Throwable t = ex;
        int guard = 0;
        while (t != null && guard++ < 8 && sb.length() < 500) {
            if (sb.length() > 0)
                sb.append(" ← ");
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null)
                sb.append(": ").append(t.getMessage());
            if (t.getCause() == t)
                break;
            t = t.getCause();
        }
        return sb.toString();
    }

    // javax.mail.MessagingException's cause ctor takes an Exception, not a
    // Throwable — adapt (JMAP causes are Exceptions in practice).
    static Exception asException(Throwable t) {
        if (t instanceof Exception)
            return (Exception) t;
        return new Exception(t == null ? "unknown" : t.toString(), t);
    }

    String getAccountId() {
        return accountId;
    }

    // ── Folders ──────────────────────────────────────────────────────────────

    // Fetch all mailboxes and map them to EntityFolder rows, mirroring
    // EmailService.getFolders() for the IMAP path. Parent/child hierarchy is
    // flattened to "/"-joined full names using the mailbox id→name map.
    @NonNull
    List<EntityFolder> getFolders(String host) throws MessagingException {
        Mailbox[] mailboxes = fetchMailboxes();
        Map<String, Mailbox> byId = new HashMap<>();
        for (Mailbox mb : mailboxes)
            byId.put(mb.getId(), mb);

        List<EntityFolder> folders = new ArrayList<>();
        boolean inbox = false;
        for (Mailbox mb : mailboxes) {
            String type = roleToType(mb.getRole());
            EntityFolder folder = new EntityFolder(fullName(mb, byId), type);
            if (EntityFolder.INBOX.equals(type))
                inbox = true;
            folders.add(folder);
        }
        // Keep parity with the IMAP path which always guarantees an Inbox.
        if (!inbox)
            folders.add(new EntityFolder("Inbox", EntityFolder.INBOX));

        return folders;
    }

    Mailbox[] fetchMailboxes() throws MessagingException {
        requireAccount();
        try {
            MethodResponses r = client.call(
                    GetMailboxMethodCall.builder().accountId(accountId).build()).get();
            Mailbox[] list = requireMain(r, GetMailboxMethodResponse.class, "Mailbox/get").getList();
            return (list == null ? new Mailbox[0] : list);
        } catch (MessagingException ex) {
            throw ex;              // already named — do not re-wrap
        } catch (Exception ex) {
            throw wrap("Mailbox/get", ex);
        }
    }

    // Map a JMAP mailbox role to a FairEmail EntityFolder type.
    static String roleToType(Role role) {
        if (role == null)
            return EntityFolder.USER;
        switch (role) {
            case INBOX:
                return EntityFolder.INBOX;
            case ARCHIVE:
                return EntityFolder.ARCHIVE;
            case DRAFTS:
                return EntityFolder.DRAFTS;
            case SENT:
                return EntityFolder.SENT;
            case TRASH:
                return EntityFolder.TRASH;
            case JUNK:
                return EntityFolder.JUNK;
            default:
                return EntityFolder.USER;
        }
    }

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

    // ── Messages ─────────────────────────────────────────────────────────────

    // JMAP page size for Email/query paging loops below. Stalwart's
    // maxObjectsInGet is 500; 200 keeps a decent margin.
    private static final int JMAP_PAGE_SIZE = 200;

    // ponytail: a fixed inter-page delay instead of a real token bucket — the
    // server edge allows 600 req/min for this vhost, so a small fixed gap
    // between the (at most 2-call) pages is plenty of headroom without any
    // extra rate-tracking state.
    private static final long JMAP_PAGE_DELAY_MS = 50;

    // Query a mailbox and fetch matching message headers, paging through
    // Email/query with `position` offsets so callers see the COMPLETE
    // membership up to [limit], not just the server's first page. Each page
    // is still one round-trip via a JMAP back-reference (Query/Email →
    // Get/Email #/ids).
    @NonNull
    List<Email> getFolderMessages(String mailboxId, int limit) throws MessagingException {
        requireAccount();
        List<Email> result = new ArrayList<>();
        try {
            long position = 0;
            while (result.size() < limit) {
                long pageLimit = Math.min(JMAP_PAGE_SIZE, limit - result.size());
                JmapClient.MultiCall multiCall = client.newMultiCall();
                JmapRequest.Call queryCall = multiCall.call(
                        QueryEmailMethodCall.builder()
                                .accountId(accountId)
                                .filter(EmailFilterCondition.builder().inMailbox(mailboxId).build())
                                // Explicit newest-first sort. RFC 8621 leaves unsorted
                                // query order server-defined — Stalwart returns oldest
                                // first, so limit N without a sort pins the window to
                                // the N oldest messages and new mail never appears.
                                .sort(new Comparator[]{new Comparator("receivedAt", false)})
                                .position(position)
                                .limit(pageLimit)
                                .build());
                JmapRequest.Call getCall = multiCall.call(
                        GetEmailMethodCall.builder()
                                .accountId(accountId)
                                // result-reference to the Query/Email response "/ids"
                                .idsReference(queryCall.createResultReference("/ids"))
                                .properties(EMAIL_HEADER_PROPERTIES)
                                .build());
                multiCall.execute();
                // Say WHICH link of the chain came back empty. When the host is
                // unreachable (2026-08-31: imap/jmap.diegonmarcos.com resolve to
                // fd0c:1d00::1 and the phone's mesh IPv6 was a black hole), the
                // response object is null and the old code dereferenced it — the
                // user saw a bare NullPointerException on getClass(), which names
                // neither the server nor the network. A dead connection must read
                // as a dead connection.
                GetEmailMethodResponse got = requireMain(
                        getCall.getMethodResponses().get(), GetEmailMethodResponse.class, "Email/query+get");
                Email[] list = got.getList();
                int received = (list == null ? 0 : list.length);
                if (list != null)
                    for (Email e : list)
                        result.add(e);
                if (received < pageLimit)
                    break; // short page — this was the last one
                position += received;
                sleepBetweenPages();
            }
            return result;
        } catch (MessagingException ex) {
            throw ex;              // already named — do not re-wrap
        } catch (Exception ex) {
            throw wrap("Email/query+get", ex);
        }
    }

    // ponytail: shared by both paging loops below.
    private static void sleepBetweenPages() {
        try {
            Thread.sleep(JMAP_PAGE_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // comms: the unread lane's server-side query. Same one round-trip shape as
    // getFolderMessages, but the filter also carries notKeyword $seen, so the
    // SERVER decides what is unread rather than the device inferring it from
    // whatever it has synced.
    //
    // Returns only ids: this is a read-state probe, not a sync. Callers
    // reconcile against the local rows (see UnreadSync) and never create
    // messages from the result -- an id here that is not in the database is a
    // sync gap to report, not a message to invent.
    @NonNull
    List<String> getFolderUnreadIds(String mailboxId, int limit) throws MessagingException {
        requireAccount();
        List<String> result = new ArrayList<>();
        try {
            long position = 0;
            while (result.size() < limit) {
                long pageLimit = Math.min(JMAP_PAGE_SIZE, limit - result.size());
                JmapClient.MultiCall multiCall = client.newMultiCall();
                JmapRequest.Call queryCall = multiCall.call(
                        QueryEmailMethodCall.builder()
                                .accountId(accountId)
                                .filter(EmailFilterCondition.builder()
                                        .inMailbox(mailboxId)
                                        .notKeyword(Keyword.SEEN)
                                        .build())
                                // Same explicit newest-first sort as getFolderMessages:
                                // RFC 8621 leaves unsorted query order server-defined.
                                .sort(new Comparator[]{new Comparator("receivedAt", false)})
                                .position(position)
                                .limit(pageLimit)
                                .build());
                multiCall.execute();
                String[] ids = requireMain(
                        queryCall.getMethodResponses().get(), QueryEmailMethodResponse.class, "Email/query unseen")
                        .getIds();
                int received = (ids == null ? 0 : ids.length);
                if (ids != null)
                    for (String id : ids)
                        result.add(id);
                if (received < pageLimit)
                    break; // short page — this was the last one
                position += received;
                sleepBetweenPages();
            }
            return result;
        } catch (MessagingException ex) {
            throw ex;              // already named — do not re-wrap
        } catch (Exception ex) {
            throw wrap("Email/query unseen", ex);
        }
    }

    // Fetch one email with its text + html body values for on-demand body load.
    Email getMessageBody(String emailId) throws MessagingException {
        requireAccount();
        try {
            MethodResponses r = client.call(
                    GetEmailMethodCall.builder()
                            .accountId(accountId)
                            .ids(new String[]{emailId})
                            .properties(EMAIL_BODY_PROPERTIES)
                            .fetchHTMLBodyValues(true)
                            .fetchTextBodyValues(true)
                            .build()).get();
            Email[] list = requireMain(r, GetEmailMethodResponse.class, "Email/get body").getList();
            return (list == null || list.length == 0 ? null : list[0]);
        } catch (MessagingException ex) {
            throw ex;              // already named — do not re-wrap
        } catch (Exception ex) {
            throw wrap("Email/get body", ex);
        }
    }

    // ── Mutations (drive EntityOperation SEEN/FLAG/MOVE/DELETE from Core) ─────

    // Set or clear a JMAP keyword (e.g. Keyword.SEEN, Keyword.FLAGGED) on emails.
    void setKeyword(List<String> emailIds, String keyword, boolean value) throws MessagingException {
        requireAccount();
        try {
            Map<String, Map<String, Object>> update = new HashMap<>();
            for (String id : emailIds) {
                Map<String, Object> patch = new HashMap<>();
                // JMAP patch path: keywords/<name> = true | null (clear)
                patch.put("keywords/" + keyword, value ? Boolean.TRUE : null);
                update.put(id, patch);
            }
            client.call(SetEmailMethodCall.builder()
                    .accountId(accountId).update(update).build()).get();
        } catch (Exception ex) {
            throw wrap("Email/set keyword", ex);
        }
    }

    void setSeen(List<String> emailIds, boolean seen) throws MessagingException {
        setKeyword(emailIds, Keyword.SEEN, seen);
    }

    void setFlagged(List<String> emailIds, boolean flagged) throws MessagingException {
        setKeyword(emailIds, Keyword.FLAGGED, flagged);
    }

    // Move emails to a single target mailbox (replaces mailboxIds wholesale).
    void moveToMailbox(List<String> emailIds, String mailboxId) throws MessagingException {
        requireAccount();
        try {
            Map<String, Boolean> mailboxIds = new HashMap<>();
            mailboxIds.put(mailboxId, Boolean.TRUE);
            Map<String, Map<String, Object>> update = new HashMap<>();
            for (String id : emailIds) {
                Map<String, Object> patch = new HashMap<>();
                patch.put("mailboxIds", mailboxIds);
                update.put(id, patch);
            }
            client.call(SetEmailMethodCall.builder()
                    .accountId(accountId).update(update).build()).get();
        } catch (Exception ex) {
            throw wrap("Email/set mailboxIds", ex);
        }
    }

    // Permanent delete via Email/set destroy.
    void deleteMessages(List<String> emailIds) throws MessagingException {
        requireAccount();
        try {
            client.call(SetEmailMethodCall.builder()
                    .accountId(accountId)
                    .destroy(emailIds.toArray(new String[0]))
                    .build()).get();
        } catch (Exception ex) {
            throw wrap("Email/set destroy", ex);
        }
    }

    // ── Send (batch 5) ─────────────────────────────────────────────────────
    // JMAP send reuses FairEmail's fully-built RFC822 MIME: upload the bytes as
    // a blob → Email/import into Sent (seen) → EmailSubmission/set with the
    // identity whose email matches the From. This preserves attachments + exact
    // MIME (unlike a structured Email/set create).
    void sendMime(byte[] rfc822, String fromEmail) throws MessagingException {
        requireAccount();
        try {
            String identityId = resolveIdentityId(fromEmail);
            if (identityId == null)
                throw new MessagingException("JMAP: no identity for " + fromEmail);

            // 1) upload the raw message as a blob
            final byte[] bytes = rfc822;
            Uploadable uploadable = new Uploadable() {
                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }

                @Override
                public MediaType getMediaType() {
                    return MediaType.parse("message/rfc822");
                }

                @Override
                public long getContentLength() {
                    return bytes.length;
                }
            };
            Upload upload = client.upload(accountId, uploadable, null).get();
            String blobId = upload.getBlobId();

            // 2) import the blob into the Sent mailbox, marked seen
            Mailbox sentBox = findMailbox(Role.SENT);
            Map<String, Boolean> mailboxIds = new HashMap<>();
            mailboxIds.put(sentBox != null ? sentBox.getId() : anyMailboxId(), Boolean.TRUE);
            Map<String, Boolean> keywords = new HashMap<>();
            keywords.put(Keyword.SEEN, Boolean.TRUE);

            EmailImport ei = EmailImport.builder()
                    .blobId(blobId)
                    .mailboxIds(mailboxIds)
                    .keywords(keywords)
                    .receivedAt(Instant.now())
                    .build();
            Map<String, EmailImport> imports = new HashMap<>();
            imports.put("i0", ei);
            MethodResponses ir = client.call(ImportEmailMethodCall.builder()
                    .accountId(accountId).emails(imports).build()).get();
            Map<String, Email> created = requireMain(ir, ImportEmailMethodResponse.class, "Email/import").getCreated();
            if (created == null || created.get("i0") == null)
                throw new MessagingException("JMAP import produced no email");
            String emailId = created.get("i0").getId();

            // 3) submit the imported email through the identity
            Map<String, EmailSubmission> submit = new HashMap<>();
            submit.put("s0", EmailSubmission.builder()
                    .emailId(emailId).identityId(identityId).build());
            client.call(SetEmailSubmissionMethodCall.builder()
                    .accountId(accountId).create(submit).build()).get();
        } catch (Exception ex) {
            throw wrap("EmailSubmission/set", ex);
        }
    }

    private String resolveIdentityId(String email) throws Exception {
        MethodResponses r = client.call(
                GetIdentityMethodCall.builder().accountId(accountId).build()).get();
        Identity[] ids = requireMain(r, GetIdentityMethodResponse.class, "Identity/get").getList();
        if (ids == null)
            return null;
        String first = null;
        for (Identity id : ids) {
            if (first == null)
                first = id.getId();
            if (email != null && email.equalsIgnoreCase(id.getEmail()))
                return id.getId();
        }
        return first; // fall back to the primary identity
    }

    private Mailbox findMailbox(Role role) throws MessagingException {
        for (Mailbox mb : fetchMailboxes())
            if (role == mb.getRole())
                return mb;
        return null;
    }

    private String anyMailboxId() throws MessagingException {
        Mailbox[] all = fetchMailboxes();
        return (all.length == 0 ? null : all[0].getId());
    }

    // ── Server rules (cloud) ─────────────────────────────────────────────────
    //
    // The real rules the SuperApp Rules page must show live server-side:
    // mail-rules.nix compiles a canonical rule set into a per-account Stalwart
    // Sieve script (cloud-u-containers: _shared/lib/mail-rules.nix::toSieve,
    // uploaded per-account by user-comm_tools-stalwart/dist/configs/activate.sh
    // Step C via SieveScript/set). rs.ltt.jmap 0.8.10 has no
    // urn:ietf:params:jmap:sieve support at all, so both calls below are raw
    // JMAP-over-HTTPS using the session's own apiUrl/downloadUrl and the same
    // Basic-auth credentials the app already holds for this account (mirrors
    // FragmentJmapAccount's connection check).

    /**
     * Fetch the account's active Sieve script source, or null if none is set.
     */
    String fetchSieveScript() throws MessagingException {
        requireAccount();
        try {
            Session session = client.getSession().get();

            String body = "{\"using\":[\"urn:ietf:params:jmap:core\",\"urn:ietf:params:jmap:sieve\"]," +
                    "\"methodCalls\":[[\"SieveScript/get\",{\"accountId\":\"" + accountId + "\"},\"0\"]]}";
            String response = rawJmapCall(session.getApiUrl(), body);

            JSONObject jroot = new JSONObject(response);
            JSONObject jargs = jroot.getJSONArray("methodResponses").getJSONArray(0).getJSONObject(1);
            JSONArray jlist = jargs.optJSONArray("list");
            if (jlist == null || jlist.length() == 0)
                return null;

            // Stalwart marks exactly one script isActive:true (the one
            // activate.sh just installed); fall back to the first if none is
            // flagged (a server that predates that convention).
            JSONObject jscript = null;
            for (int i = 0; i < jlist.length(); i++) {
                JSONObject j = jlist.getJSONObject(i);
                if (j.optBoolean("isActive", false)) {
                    jscript = j;
                    break;
                }
            }
            if (jscript == null)
                jscript = jlist.getJSONObject(0);

            String blobId = jscript.optString("blobId", null);
            if (TextUtils.isEmpty(blobId))
                return null;

            HttpUrl downloadUrl = session.getDownloadUrl(accountId, blobId,
                    jscript.optString("name", "default") + ".sieve", "application/sieve");
            return rawJmapDownload(downloadUrl);
        } catch (MessagingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw wrap("SieveScript/get", ex);
        }
    }

    /**
     * Name of the sentinel mailbox the sorter (cloud-u-containers:
     * user-comm_tools-stalwart/src/crate/src/main.rs — REAPPLY_SENTINEL) polls
     * for every ~10s: create it and the sorter destroys it and immediately
     * re-runs its routing pass over ALL mail, not just new deliveries. A
     * mailbox rather than a keyword or HTTP endpoint, so the app needs no new
     * credential or port — just the JMAP connection it already has.
     */
    private static final String REAPPLY_SENTINEL = ".reapply";

    /** Ask the server-side sorter to re-apply routing rules to all mail now. */
    void requestReapply() throws MessagingException {
        requireAccount();
        try {
            client.call(SetMailboxMethodCall.builder()
                    .accountId(accountId)
                    .create(java.util.Collections.singletonMap("r",
                            Mailbox.builder().name(REAPPLY_SENTINEL).build()))
                    .build()).get();
        } catch (Exception ex) {
            throw wrap("Mailbox/set(.reapply)", ex);
        }
    }

    private String rawJmapCall(HttpUrl url, String jsonBody) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url.toString()).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", basicAuth());
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(jsonBody.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            String response = Helper.readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300)
                throw new MessagingException("JMAP HTTP " + code + ": " + response);
            return response;
        } finally {
            c.disconnect();
        }
    }

    private String rawJmapDownload(HttpUrl url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url.toString()).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Authorization", basicAuth());
            int code = c.getResponseCode();
            String response = Helper.readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300)
                throw new MessagingException("Sieve blob download HTTP " + code);
            return response;
        } finally {
            c.disconnect();
        }
    }

    private String basicAuth() throws java.io.UnsupportedEncodingException {
        return "Basic " + android.util.Base64.encodeToString(
                (user + ":" + password).getBytes("UTF-8"), android.util.Base64.NO_WRAP);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    JmapClient getClient() {
        return client;
    }

    private void requireAccount() throws MessagingException {
        if (client == null || TextUtils.isEmpty(accountId))
            throw new MessagingException("JMAP not connected");
    }

    // Every read call below does response.getMain(SomeMethodResponse.class)
    // and dereferences the result. On a dead connection (2026-08-31:
    // imap/jmap.diegonmarcos.com resolved to a mesh IPv6 black hole) that
    // comes back null and the bare dereference NPEs with a message like
    // "Attempt to invoke virtual method ...getClass()" — useless: it names
    // neither the JMAP method nor the actual failure (unreachable host vs.
    // the method simply erroring server-side). Route every getMain call site
    // through here so the exception always names both.
    private static <T extends rs.ltt.jmap.common.method.MethodResponse> T requireMain(
            MethodResponses response, Class<T> clazz, String what) throws MessagingException {
        T main = (response == null ? null : response.getMain(clazz));
        if (main == null)
            throw new MessagingException(
                    "JMAP " + what + ": no response from the JMAP server " +
                    "(is the host reachable? both A and AAAA are published)");
        return main;
    }

    private static MessagingException wrap(String op, Exception ex) {
        if (ex instanceof MessagingException)
            return (MessagingException) ex;
        Throwable cause = unwrap(ex);
        // describe() already renders "ExceptionClass: message" (or just the
        // class name when the cause has no message, e.g. a bare NPE) — keep
        // using it here so callers get the real failure, not just a class name.
        return new MessagingException("JMAP " + op + " failed: " + describe(cause), asException(cause));
    }

    void close() {
        try {
            if (client != null)
                client.close();
        } catch (Throwable ignored) {
        } finally {
            client = null;
            accountId = null;
            password = null;
        }
    }
}
