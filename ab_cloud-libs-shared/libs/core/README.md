# libs:core — telemetry default

Every app that links `libs:core` gets `Telemetry` (`com.diegonmarcos.superapp.core.Telemetry`)
for free. There is no per-app config required — the point of this module is
that an app with zero telemetry wiring still posts somewhere sensible.

## What you get automatically

- `Telemetry.endpoint(context)` resolves to
  `https://api.diegonmarcos.com/c3-infra-api/public/events/{app}`, where
  `{app}` is derived from `context.packageName`: the segment(s) after
  `com.diegonmarcos.`, dots turned into `-`
  (`com.diegonmarcos.comms.mail` → `comms-mail`).
- The server forwards a summary of every event to the ntfy topic
  `infra-{app}` — so once an app links `libs:core`, its posts show up on
  that topic with no server-side config change.
- 2 MB body cap, unauthenticated (deliberately — see the KDoc on `Telemetry`
  and on `LogUpload` for why an unauthenticated reporting path is correct
  here, not an oversight).

## What an app does to opt in further

Nothing is required beyond the dependency. Two things are available if you
want them:

1. **Send an event:**
   ```kotlin
   Telemetry.post(context, kind = "action", title = "…", message = "…")
   ```
   `kind` is one of `"log" | "debug" | "action" | "probe" | "crash"`
   (unvalidated server-side — anything else still gets sent). Fire-and-forget,
   runs on its own thread, never throws into the caller.

2. **Install the default crash handler**, once, early in
   `Application.onCreate()`:
   ```kotlin
   Telemetry.installCrashHandler(context)
   ```
   Wraps `Thread.defaultUncaughtExceptionHandler`, posts `kind="crash"` with
   the redacted stack trace as `log`, then always delegates to whatever
   handler was previously installed (so this never suppresses the system
   crash dialog or another crash reporter).

`Telemetry.postLogcat(context, kind = "log", title = "…")` captures this
process's own logcat, redacts secrets, caps it to the newest ~1.5 MB, and
posts it as `log` — the same dump+redact+cap path `LogUpload` uses for its
own upload, exposed as `LogUpload.captureAndRedactLogcat()` internally so
neither copy drifts from the other.

## Overriding the endpoint

An app that wants a non-default ingest URL (or wants to opt OUT entirely)
declares, in its own `app/build.gradle`:

```groovy
buildConfigField "String", "TELEMETRY_INGEST_URL", "\"...\""   // or "\"\"" to disable
```

`Telemetry` reads this reflectively (`Class.forName("<packageName>.BuildConfig")`)
because `libs:core` has no compile-time visibility into the consuming app's
generated `BuildConfig`. This only works when the app's `namespace` matches
its `packageName`/`applicationId` — some forks (e.g. `ac_cloud-mail`, whose
namespace is `eu.faircode.email` but whose applicationId is
`com.diegonmarcos.comms.mail`) have no `<packageName>.BuildConfig` class at
all, so the override is silently skipped and the derived default is used
instead. That is the intended degradation, not a bug: the derived default is
always a reachable, correct endpoint.
