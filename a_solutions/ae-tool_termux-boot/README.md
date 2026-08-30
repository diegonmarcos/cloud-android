# Cloud Unix Termux Boot

Runs `~/.termux/boot/*.sh` inside Nix-on-Droid at device boot.

## Why this exists instead of Termux:Boot

`com.termux.nix.boot` **does not exist** on F-Droid. The addon package name is
compiled in as `TERMUX_PACKAGE_NAME + ".boot"`, so the published
`com.termux.boot` targets `com.termux` — an app that is not installed here.
Nix-on-Droid's own `BOOT_COMPLETED` receiver only resets shell counters; it
never reads the boot directory.

## Why it is not a fork of termux-boot

Termux:Boot declares `android:sharedUserId="com.termux"`, so it runs as the
*same uid* as the host. That buys it the two things it needs:

1. `listFiles()` on the boot directory, which is `0700` and owned by that uid.
2. Reaching `TermuxService`, which is `android:exported="false"`.

Android grants a shared uid only to apps signed with the **same certificate**.
Nix-on-Droid ships signed by F-Droid; we do not have that key. A forked
termux-boot would fail to install (`INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`),
and could do neither (1) nor (2) if it somehow did.

## The door that is open

`RunCommandService` is `exported="true"`, guarded by a RUN_COMMAND permission
declared `protectionLevel="dangerous"` — not `signature`. A foreign-signed app
may hold a dangerous permission with user consent. The host also requires
`allow-external-apps=true` in `termux.properties`, deployed by the flake
(`bb_flakes_termux/src/modules/cloud-ide-sshd/default.nix`).

Because the APK still cannot enumerate the `0700` boot directory, it launches
exactly one path — `~/.termux/boot-runner.sh` — and *that* script does the
enumeration as the correct uid. The boot-script set stays data on the device:
adding or changing scripts never requires rebuilding this APK.

## Why it goes through `usr/bin/login`

`TermuxService` runs the command with `ProcessBuilder` in the plain Android app
context — **outside proot**. Out there `/nix` does not exist (it is a proot bind
of `files/usr/nix`), so every symlink in `files/usr/bin` dangles. Exactly two
real files live in that directory: `proot-static` and `login`.

Pointing `RUN_COMMAND_PATH` at the boot script directly therefore fails with

```
Cannot run program ".../files/usr/bin/env": error=2, No such file or directory
```

— the script's own `#!/usr/bin/env sh` shebang cannot resolve. So the APK runs
`login` and passes the script as an argument; `login` enters proot and does
`exec /usr/bin/env "$@"` with the Nix session environment sourced.

## Install (once)

1. GHA builds and signs it with the shared constellation key
   (`ship-cloud-unix-termux-boot.yml`); download the APK artifact.
2. Install it on the phone (unknown sources).
3. **Open it once.** Two separate reasons, both mandatory: RUN_COMMAND is a
   runtime permission, and Android keeps a never-launched app in the "stopped"
   state where it receives no `BOOT_COMPLETED` at all.

The paths shared with the flake are asserted by
`bb_flakes_termux/src/modules/cloud-ide-sshd/cloud-ide-sshd.test.sh` — the two
halves deploy separately and every mismatch between them is silent.
