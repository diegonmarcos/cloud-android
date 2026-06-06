# mail-fairmail — FairEmail

| Field | Value |
|---|---|
| Upstream | https://github.com/M66B/FairEmail.git |
| License | GPL-3.0-or-later |
| Local clone path | `../../ea_mail-fairmail/` |
| Last pinned commit | `3ead493` (2026-05-28) |
| Cherry-pick target | `ea_cloud-superapp/libs/mail/` |
| Status | **Active** — 10 files cherry-picked, plus an in-house JMAP layer (`JmapClient`, `JmapPrefs`) layered on top |

## Re-clone

```bash
git clone https://github.com/M66B/FairEmail.git \
  ~/git/unix/ea_mail-fairmail
git -C ~/git/unix/ea_mail-fairmail checkout 3ead493
```

## What we cherry-picked

Body-rendering helpers + IMAP/SMTP fallback for non-JMAP accounts.
The Fragment surfaces (`MailFragment`, `MailFoldersFragment`,
`MailMessagesFragment`, `MailMessageBodyFragment`, `MailDrawerFragment`)
are written in-house in superapp style; they re-use FairEmail's body
parsers under the hood.
