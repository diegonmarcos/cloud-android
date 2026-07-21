# Fork: mail (FairEmail)

- **Upstream**: https://github.com/M66B/FairEmail.git (GPL-3.0, Java)
- **App id**: `com.diegonmarcos.comms.mail`
- **Tracker**: `ea_mail-fairmail/` (shared with ea_cloud-superapp's cherry-pick;
  `materialize-fork` checks out the pin, never trusts tracker HEAD)
- **Server**: Maddy + Stalwart on oci-mail — `imap.diegonmarcos.com:443` +
  `smtps.diegonmarcos.com:443` (implicit TLS, SNI demux on :443), JMAP on
  `jmap.diegonmarcos.com:443`. See `../../data/comms-endpoints.json::mail`.
- **Priority**: 1 — build this fork first (pure Java, no RN/Rust, server is live).

## Status

Scaffold only. Next steps for the dev agent:

1. Set `build.json::forks.mail.pinned_tag` to the latest FairEmail release tag.
2. `./build.sh materialize-fork mail`.
3. Author the four patches (branding / provisioning / exporter / switcher — see
   `../README.md`). FairEmail supports custom host:port with implicit TLS, so the
   `:443` SNI endpoints work without upstream changes.
4. Exporter: `MailExportProvider` under `com.diegonmarcos.comms.mail.provider`
   implementing the `accounts/summary/threads/messages` tables from
   `../../contract/comms-ipc-v1.json`, reading FairEmail's Room DB.

## Tester

- CI: clean clone at pin + `git am` of the series applies with zero fuzz.
- Instrumented: provider `summary.unread_count` == IMAP STATUS unread for the
  test account (cross-check via the `services_email_imap_*` MCP tooling).
- e2e: `ICommsService.send()` → message lands in Maddy.
