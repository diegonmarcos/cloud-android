# Cloud-Comms Chat (Mattermost) — Mobile Push Notifications

Status: **SCAFFOLD / BLOCKED on owner-provided Firebase (FCM) project.**
Decision (owner): self-host Mattermost Push Proxy + FCM. Build now, deploy when creds ready.

Mattermost mobile has NO UnifiedPush/ntfy support — a self-compiled app REQUIRES
your own Mattermost Push Proxy backed by your own Firebase Cloud Messaging project.
The existing `ntfy-bridge.py` is inbound-only (ntfy alerts → MM channels); it is NOT
mobile push and is unrelated.

## Delivery chain

```
MM server (oci-apps, chat-mattermost)
  ── EmailSettings.SendPushNotifications=true
  ── EmailSettings.PushNotificationServer=http://mattermost-push-proxy:8066
        │
        ▼
mattermost-push-proxy  (NEW cloud service, oci-apps, :8066, internal/WG-only)
  ── AndroidPushSettings[].Type = "android_rn"
  ── AndroidPushSettings[].ServiceFileLocation = <Firebase service-account JSON>  (SOPS)
        │  FCM v1 API
        ▼
Firebase Cloud Messaging  (YOUR project)  ──►  device
        ▲
app (com.diegonmarcos.comms.chat) baked with YOUR google-services.json
  registers its FCM token on login → MM server stores it → proxy delivers.
```

## ⚠️ Owner action — create in the Firebase / Google Cloud console (only you can)

1. Create a Firebase project (e.g. `cloud-comms`).
2. Add an **Android app**. Package name → see the OPEN QUESTION below (rnbeta vs chat).
3. Download **`google-services.json`** → hand to me (goes into the app build).
4. In Google Cloud → IAM → Service Accounts: create one with the **Firebase Cloud
   Messaging API** enabled, create a **JSON key**, download it. This is the push
   proxy's `ServiceFileLocation` → stored in vault + `src/secrets.yaml` (sops).
5. Enable the **Firebase Cloud Messaging API (v1)** for the project.

Hand me: `google-services.json` + the service-account `*.json`. Both via vault/sops —
never committed plaintext.

### OPEN QUESTION to resolve against the real project — package mismatch
The app's `applicationId` = `com.diegonmarcos.comms.chat` (repackage patch 0001) but its
`namespace` = `com.mattermost.rnbeta` (unchanged). The `com.google.gms.google-services`
gradle plugin matches `google-services.json` against the **namespace** at BUILD time,
while FCM delivers by the **applicationId** at RUNTIME. Options, to pick when the project
exists (needs a real token round-trip to confirm delivery):
  (a) Register the Firebase Android app as `com.diegonmarcos.comms.chat` AND add a second
      Android app `com.mattermost.rnbeta` so both the build-match and runtime work; or
  (b) Register as `com.mattermost.rnbeta` only (build passes) and verify FCM still
      delivers to applicationId `com.diegonmarcos.comms.chat`; or
  (c) Also repackage `namespace` → `com.diegonmarcos.comms.chat` (heavier patch; touches
      native/BuildConfig refs) so namespace == applicationId and there is no mismatch.
Recommendation: try (a) first.

## Build steps once creds land

### 1. NEW cloud service `a_solutions/user-comm_mattermost-push-proxy/`
VERIFIED networking (oci-apps): every container runs `network_mode: host`, and the MM
server's `MM_SERVICESETTINGS_ALLOWEDUNTRUSTEDINTERNALCONNECTIONS` already includes
`localhost`. So the proxy binds `127.0.0.1:8066` and the MM server reaches it at
`http://localhost:8066` — NO docker network, NO Caddy route (FCM is outbound only).
- `build.json`: mirror `infra-db_redis` (Type-B upstream wrap). `deploy.host=oci-apps`,
  `ports.app=8066`, `category=app`, `build.include_cloud_data=true`, `compose.custom=true`,
  `containers.app` (container_name `mattermost-push-proxy`, `public=false`, `proxy=null`),
  `docker.{registry=ghcr.io/diegonmarcos, image=mattermost-push-proxy, dockerfile=Dockerfile}`
  so it publishes to GHCR (no Docker-Hub-direct). `build.sh` → symlink `../_engine.sh`.
  `include_cloud_data:true` makes `2_configs` derive `build-mattermost-push-proxy.json`
  (the `./build-<name>.json` symlink the flake reads) at ship time.
- `src/code/Dockerfile`: `FROM mattermost/mattermost-push-proxy:<pin>` (Type-B wrap → GHCR).
- `src/flake.nix` (mirror redis) + `src/compose.nix`: `network_mode=host`, `env_file=[".secrets"]`,
  command wrapper writes the service-account env → file then execs the proxy:
  `sh -c 'printf "%s" "$$FCM_SERVICE_ACCOUNT_JSON" > /config/fcm-sa.json && exec /mattermost-push-proxy -config /config/mattermost-push-proxy.json'`
  (env→file is the standard way to land a JSON secret from the KEY=VALUE `.secrets`).
- `src/mattermost-push-proxy.json` (config, data-driven): `ListenAddress="127.0.0.1:8066"`,
  `AndroidPushSettings=[{ "Type":"android_rn", "ServiceFileLocation":"/config/fcm-sa.json" }]`.
- `src/secrets.yaml` (sops): add `FCM_SERVICE_ACCOUNT_JSON` via `sops edit` (or `build.sh
  secrets`) ONCE the owner provides the service-account key — cannot be created earlier
  (a valid sops file needs the real value + age key; no plaintext placeholder is allowed).

### 2. MM server config — `user-comm_chat-mattermost/src/compose.nix`
Add (staged; auto-deploys to LIVE oci-apps on push — only push when proxy is up):
```
MM_EMAILSETTINGS_SENDPUSHNOTIFICATIONS = "true";
MM_EMAILSETTINGS_PUSHNOTIFICATIONSERVER = "http://mattermost-push-proxy:8066";
MM_EMAILSETTINGS_PUSHNOTIFICATIONCONTENTS = "full";   # or "id_loaded" for privacy
```

### 3. App (this fork) — `google-services.json`
Add `forks/chat/google-services.json` (YOUR project's file) + a `build.prepare` step in
`ea_cloud-comms/build.json::forks.chat.build.prepare` that copies it over
`android/app/google-services.json` in the tracker before the build. Resolve the package
mismatch per the OPEN QUESTION.

## Rollout order (avoid breaking live push)
1. Firebase project + both JSON artifacts (owner).
2. Build + deploy `mattermost-push-proxy` (verify `/api/v1/send_push` reachable in-cluster).
3. Rebuild the app with `google-services.json`; install; confirm it obtains an FCM token.
4. ONLY THEN flip the MM server push settings (step 2 above) + `build.sh ship` chat-mattermost.
5. Tester: send a DM to the device while backgrounded → notification arrives. Proxy logs
   show a successful FCM v1 send (200). MM System Console → push test.

## Not doing
- No live-infra changes committed/deployed yet (creds-blocked).
- Push stays OFF on the server until the proxy is verified — flipping it early makes every
  mobile client's push registration fail against a dead proxy.
