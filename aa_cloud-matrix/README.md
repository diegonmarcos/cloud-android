# Fork: matrix (Element X Android)

- **Upstream**: https://github.com/element-hq/element-x-android.git (AGPL-3.0, Kotlin + Rust matrix-sdk FFI)
- **App id**: `com.diegonmarcos.comms.matrix`
- **Tracker**: `ea_chat-element/`
- **Server**: continuwuity homeserver + mautrix-whatsapp bridge. See
  `../../data/comms-endpoints.json::matrix`.
- **Priority**: 3.

## 🚫 BLOCKED

`build.json::forks.matrix.blocked_on = "matrix-chat-bridge-stack-ship"`.

The continuwuity + mautrix-whatsapp + element stack is **merged to cloud main
(2026-06-10) but NOT shipped**. `./build.sh materialize-fork matrix` refuses
while blocked. Ship the Matrix stack first (coordinate — no out-of-scope
deploys), confirm the real homeserver base URL + well-known into
`comms-endpoints.json`, then clear `blocked_on` to null.

## Status

Scaffold only. Next steps (once unblocked):

1. Pin `build.json::forks.matrix.pinned_tag` to an Element X Android release tag
   (Element X, not the maintenance-mode classic Element Android).
2. `./build.sh materialize-fork matrix`.
3. Patches: branding / provisioning (default homeserver) / exporter / switcher.
4. Exporter reads via the Rust-SDK **Kotlin bindings**, never raw store files
   (store format is SDK-private). `MatrixExportProvider` under
   `com.diegonmarcos.comms.matrix.provider`.
5. Push via **UnifiedPush** with our ntfy (`rss.diegonmarcos.com`) as distributor —
   Element X supports it natively; provisioning patch sets the default distributor.

## Tester

- CI: patch series applies clean.
- Instrumented: provider room list + unread parity vs client-server `/sync`.
