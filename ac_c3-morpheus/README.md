# Cloud-Morpheus

`c3-morpheus` on a phone. **v1 — four panels, and every one of them is honest
about what it is.**

```
c3-watchdog   keeps what is running ALIVE   — reactive, one machine
c3-morpheus   decides what RUNS             — intentional, the fleet
```

applicationId: **`com.diegonmarcos.morpheus`** — the mirror of
`com.diegonmarcos.watchdog`, which the rebrand deliberately kept. This is a new
app so there is no update chain to break, but the fleet updater keys on package
id, so it is fixed from the first publish and does not change later.

## The four panels

| Panel | What it is |
|---|---|
| **Workflows** | The Dagu web UI, with Dagu's own Start button. The same server `Cloud/C3/Observability` reads through `DaguClient`. |
| **Health** | The probe registry — **declarations**, and it says so at the top. |
| **Fleet** | Hands off to the SuperApp's Constellation page by launch intent. |
| **Boards** | Paca, rendered inline. |

## Why mostly WebViews, and why that is not a cop-out

Dagu and Paca are live, authenticated surfaces that already draw themselves.
Paca in particular has **no board API** — `/api/health`, `/api/v1/boards` and
`/api/boards` all 404, and every route 302s to `auth.diegonmarcos.com` behind
Authelia forward_auth — so the board *is* the Paca app. The SuperApp reached the
same conclusion and renders it inline. Reimplementing either would be inventing
a second truth. `ac_c3-watchdog` is a WebView on exactly this reasoning.

## No dead buttons

Every panel either shows a real surface or states, on screen, what is not wired
and why. A page that fails to load renders an explanation, never a blank
rectangle.

- **Boards** — Paca is **MESH-ONLY**. There is no public edge certificate for
  `paca.diegonmarcos.com`: it measures `000` from the public edge while the
  service is perfectly healthy on `10.0.0.6:8095`. An off-mesh phone is told
  that this is almost certainly the phone being off the WireGuard mesh and is
  *not* evidence Paca is down.
- **Health** — says `DECLARATIONS ONLY` at the top. A green tile drawn from a
  static file would be a colour with nothing behind it. Live results come from
  the `cloud-health-*.sh` family, scheduled by Dagu, published to ntfy.
- **Fleet** — when the SuperApp is not installed it says so, rather than doing
  nothing. Morpheus ships **no second fleet updater**: two updaters keyed on the
  same package ids would fight each other.

## What is not wired

**Triggering.** Nothing in this APK can start a workflow or a probe, and the
Health tab names the exact call that is missing. It is being built once, in
`ab_cloud-libs-shared/libs/ops`: Dagu `POST /api/v1/dags/{name}/start` with a
fresh `client_credentials` token per run, plus a server-side GHA dispatch proxy.
Morpheus will call that client rather than grow a second one to keep in sync
with Authelia. Meanwhile the Workflows tab shows Dagu's own Start button, which
does work.

## Data, not constants

The surfaces live in `build.json::surfaces` and become BuildConfig fields. Same
rule `libs/ops` follows for `DAGU_DEFAULT_SERVER`: a URL hardcoded in a class is
a URL that gets copied and then diverges from the one the rest of the fleet
uses.

`app/src/main/assets/probes.json` is a committed **mirror** of
`cloud-u-linux/da_morpheus/data/probes.json`, which is the source of truth (the
file names this in its own `_mirror_of` key). Mirrored rather than fetched
because the APK must render the registry with no network. If they disagree, the
CLI copy wins — it is the one `c3-morpheus probes validate` checks against the
ntfy topic registry.

No shared library module: v1 needs nothing from `libs/`, and depending on
`ab_cloud-libs-shared` now would put its whole build on this app's critical
path for nothing. That changes the day `run` takes the trigger client.

## Building

Never locally — GHA only, `1_cicd/src/cicd/ship-c3-morpheus.yml` (canonical;
`.github/workflows/` holds the emitted copy). Publishes
`ghcr.io/diegonmarcos/c3-morpheus` and `C3-Morpheus.apk` on the rolling
`latest` release, per ABI.
