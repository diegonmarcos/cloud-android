# Fork: dialer (Fossify Phone)

- **Upstream**: https://github.com/FossifyOrg/Phone.git (GPL-3.0, Kotlin) — the
  maintained Fossify fork of Simple Dialer.
- **App id**: `com.diegonmarcos.comms.dialer` · label **Phone**
- **Tracker**: `ea_upstreams-sources/dialer-fossify/`
- **Pinned**: 1.11.1
- **Role**: the "Dialer" entry under Cloud-SuperApp **Suite ▸ Cloud ▸
  Communications** — SuperApp links to it; Cloud-Comms owns/ships it like the
  other forks (registry-driven: tiles, nav bar, fleet updater, bundle).
- **No server / no exporter**: it's the device dialer — no comms-data tables to
  export; no endpoint in `data/comms-endpoints.json`. It joins the constellation
  for ownership (our signing key, our nav bar, our updater), not for IPC data.

## Status (2026-08-18)

14-patch series on pin 1.11.1 (build.json::pinned_tag — was wrongly 1.7.0
after the per-app split, which kept CI red on patch 0003). Spam/call
filtering is full-featured: tri-mode screening (0012), offline spam DB v2
(0007/0013), screened-call history + per-number allowlist (0013), in-call
suspected-spam warning + spam-flagged recents (0014).

## Original scaffold notes

Registry entry + pin landed. Next steps for the dev agent:

1. `./build.sh materialize-fork dialer`.
2. Patches (same concerns as the other forks, minus exporter): branding
   (applicationId → `com.diegonmarcos.comms.dialer`), OPEN action,
   constellation top bar. **EXCEPTION to the one-icon model: the dialer KEEPS
   its LAUNCHER icon** — Android's default-dialer role and the SuperApp Suite
   grid (LauncherApps resolution) both need a launchable activity.
3. **System roles (owner requirement)** — the fork must request BOTH defaults
   on first run via RoleManager:
   - `ROLE_DIALER` — default Phone app (Fossify Phone already requests this
     upstream; verify the flow survives the applicationId rename — the role
     grant is per-package).
   - `ROLE_CALL_SCREENING` — default spam filter / caller-ID screening. Fossify
     Phone does NOT hold this upstream — the patch adds a `CallScreeningService`
     (manifest: `android.permission.BIND_SCREENING_SERVICE` service +
     `RoleManager.createRequestRoleIntent(ROLE_CALL_SCREENING)` in onboarding)
     so spam filtering defaults to our fork too.
     - **DEFAULT POLICY = contacts-only (owner directive).** The
       `CallScreeningService` MUST default to `contacts_only`: in
       `onScreenCall(Call.Details)`, look up the incoming `handle` number in the
       device contacts (ContactsContract `PhoneLookup`); if there is NO match,
       respond with `CallResponse.Builder().setDisallowCall(true)
       .setRejectCall(true).setSkipNotification(true)` (silent reject, no missed
       call). Saved contacts ring normally. The first-run default lives in a
       SharedPreference seeded to `contacts_only` and the in-app settings let the
       user switch to `allow_all` / `block_known_spam`. Contract is
       `build.json::forks.dialer.call_screening` — keep the code default ==
       `default_mode` there. Needs `READ_CONTACTS` (request in onboarding).
3. First CI build via `workflow_dispatch` (fork=dialer) — `build.gradle_task`
   (`assembleFossRelease`) and `apk_glob` in build.json are a best-guess against
   Fossify's flavor setup; the CI run is the tester, adjust on first red.
   Fossify's signing mechanism differs from FairEmail's keystore.properties —
   verify and extend the engine's `signing` modes if needed.

## Tester

- CI: patch series applies clean on the pin; `build-fork dialer` produces a
  signed APK; installed fork answers the OPEN action.
