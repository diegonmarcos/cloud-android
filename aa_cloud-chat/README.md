# Fork: chat (Mattermost mobile)

- **Upstream**: https://github.com/mattermost/mattermost-mobile.git (Apache-2.0, React Native)
- **App id**: `com.diegonmarcos.comms.chat`
- **Tracker**: `ea_chat-mattermost/`
- **Server**: `https://chat.diegonmarcos.com` on oci-apps. See
  `../../data/comms-endpoints.json::chat`.
- **Priority**: 2.

## ⚠ Open decision before building (owner sign-off required)

Push notifications. Upstream relies on Mattermost's hosted push proxy. Options:
- self-host `mattermost-push-proxy` as a new cloud service (`aa-sui_` prefix), or
- accept sync-on-open + ntfy.

Do not build the exporter until this is decided — it changes the manifest +
provisioning patch.

## Status

Scaffold only. Next steps:

1. Pin `build.json::forks.chat.pinned_tag` to a Mattermost mobile release tag.
2. `./build.sh materialize-fork chat` (needs the node/yarn devShell for Metro).
3. Patches: branding (applicationId) / provisioning (locked server URL) /
   exporter / switcher.
4. Exporter is a **native Kotlin module** reading WatermelonDB via a small
   native query layer — keep the React-Native/JS side untouched. `ChatExportProvider`
   under `com.diegonmarcos.comms.chat.provider`.

## Tester

- CI: patch series applies clean.
- Instrumented: provider unread parity vs Mattermost REST
  `/api/v4/users/me/teams/unread` for the test account.
