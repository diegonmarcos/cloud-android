#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud SuperApp — Universal Build Dispatcher                      ║
# ║                                                                  ║
# ║ Modularized monolith Android app. Single APK, gradle multi-module. ║
# ║ All toolchain (AGP, gradle, kotlin, JDK, android-sdk) comes from   ║
# ║ flake.nix — never assume host has them.                            ║
# ║                                                                  ║
# ║ Commands:                                                        ║
# ║   build       gradle assembleDebug → dist/<release.artifact.debug> ║
# ║   release     gradle assembleRelease (signed if keystore present) ║
# ║   dev         open IDE / run on connected device (adb)            ║
# ║   test        gradle test (JVM unit tests)                        ║
# ║   instrument  gradle connectedAndroidTest (needs device)          ║
# ║   lint        gradle lint                                         ║
# ║   clean       gradle clean + rm -rf dist/                         ║
# ║   shell       enter Nix devShell (gradle + sdk + jdk)             ║
# ║   ship        build + side-load via adb (USB-connected device)    ║
# ║   oras-push   push APK as OCI artifact → ghcr (release.ghcr block)║
# ║   oras-pull   pull APK from ghcr → dist/  [tag=latest]            ║
# ║   phone-install pull + copy to Android shared storage Download     ║
# ║   gh-release  attach APK to GitHub Release (release.gh_release)   ║
# ║   sync-qrcodes  pull qrcodes.json from front/linktree → assets/    ║
# ║   sync-net      cherry-pick wireguard-android tunnel/ → libs/net/  ║
# ║                                                                  ║
# ║ NEVER bypass this script for build operations.                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
CMD="${1:-help}"

log()    { printf "[%s] %s\n" "$(date '+%H:%M:%S')" "$1"; }
errlog() { printf "\033[0;31m[%s] ERROR: %s\033[0m\n" "$(date '+%H:%M:%S')" "$1" >&2; }

# Nix-wrapped invocation: every gradle call goes through `nix develop` so the
# JDK / AGP / Android SDK are reproducible per the flake. Set BYPASS_NIX=1 to
# use host tools (only for IDE / dev — never CI).
in_nix() {
  if [ "${BYPASS_NIX:-0}" = "1" ]; then
    "$@"
  else
    command -v nix >/dev/null 2>&1 || { errlog "nix not on PATH; install nix or set BYPASS_NIX=1"; exit 1; }
    nix develop "$SCRIPT_DIR" --command "$@"
  fi
}

# Lightweight metadata commands (jq for build.json, git for HEAD sha, etc.)
# don't need the Android devShell. On hosts where the devShell isn't
# usable (e.g. aarch64 Termux — the Android SDK derivation is x86-only),
# prefer the host binary when it's on PATH. Falls back to nix only if
# the tool genuinely isn't installed.
prefer_host() {
  if command -v "$1" >/dev/null 2>&1; then
    "$@"
  else
    in_nix "$@"
  fi
}

step_build() {
  log "Build: $(_release_var '.name') (debug APK)"
  in_nix gradle :app:assembleDebug
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_release_var '.release.artifact.debug')"
  cp "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$out"
  log "→ $out"
}

step_release() {
  log "Build: $(_release_var '.name') (release APK)"
  in_nix gradle :app:assembleRelease
  mkdir -p "$DIST_DIR"
  local out="$DIST_DIR/$(_release_var '.release.artifact.release')"
  cp "$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk" "$out" 2>/dev/null \
    || cp "$SCRIPT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" "${out%.apk}-unsigned.apk"
  log "→ $DIST_DIR/"
}

step_dev() {
  log "Dev: launching on connected device (adb)"
  command -v adb >/dev/null || in_nix adb devices
  in_nix gradle :app:installDebug
  in_nix adb shell am start -n "$(_release_var '.android.application_id')/com.diegonmarcos.superapp.MainActivity"
}

step_test()       { log "Test: JVM unit tests"; in_nix gradle test; }
step_instrument() { log "Test: instrumented (needs device)"; in_nix gradle connectedAndroidTest; }
step_lint()       { log "Lint"; in_nix gradle lint; }
step_clean()      { log "Clean"; in_nix gradle clean; rm -rf "$DIST_DIR"; }
step_shell()      { log "Entering Nix devShell"; exec nix develop "$SCRIPT_DIR"; }

step_ship() {
  step_build
  log "Ship: side-loading via adb"
  in_nix adb install -r "$DIST_DIR/$(_release_var '.release.artifact.debug')"
}

# ── data-driven release helpers ────────────────────────────────────────
# All registry / tag / asset config lives in build.json::release. Nothing
# hardcoded here — pure interpolation. {sha} and {version_name} are the
# only template variables.
_release_var() {
  prefer_host jq -r "$1 // empty" "$SCRIPT_DIR/build.json"
}

_resolve_template() {
  # Expand {sha} → GITHUB_SHA[:8] (or git rev-parse --short=8), {version_name} → build.json
  local tmpl="$1"
  local sha="${GITHUB_SHA:-$(prefer_host git -C "$SCRIPT_DIR" rev-parse --short=8 HEAD 2>/dev/null || echo unknown)}"
  local ver="$(_release_var '.android.version_name')"
  echo "${tmpl//\{sha\}/${sha:0:8}}" | sed "s|{version_name}|$ver|g"
}

step_oras_push() {
  local enabled registry namespace image media_type artifact
  enabled="$(_release_var '.release.ghcr.enabled')"
  [ "$enabled" = "true" ] || { log "oras-push: release.ghcr.enabled=false — skip"; return 0; }

  registry="$(_release_var '.release.ghcr.registry')"
  namespace="$(_release_var '.release.ghcr.namespace')"
  image="$(_release_var '.release.ghcr.image')"
  media_type="$(_release_var '.release.ghcr.media_type')"

  # Prefer release apk if it exists, else debug.
  if   [ -f "$DIST_DIR/$(_release_var '.release.artifact.release')" ]; then
    artifact="$DIST_DIR/$(_release_var '.release.artifact.release')"
  elif [ -f "$DIST_DIR/$(_release_var '.release.artifact.debug')" ]; then
    artifact="$DIST_DIR/$(_release_var '.release.artifact.debug')"
  else
    errlog "oras-push: no APK found in $DIST_DIR — run build/release first"; exit 1
  fi

  # ORAS rejects absolute file paths (artifact name = path → leaks host
  # filesystem). Push from the artifact's dir using only the basename.
  local artifact_dir artifact_name
  artifact_dir="$(dirname "$artifact")"
  artifact_name="$(basename "$artifact")"

  # Iterate templated tags from build.json (data-driven, NO hardcoded list).
  local tags
  tags="$(prefer_host jq -r '.release.ghcr.tags[]' "$SCRIPT_DIR/build.json")"
  while IFS= read -r tmpl; do
    [ -z "$tmpl" ] && continue
    local tag ref
    tag="$(_resolve_template "$tmpl")"
    ref="$registry/$namespace/$image:$tag"
    log "oras push $ref ← $artifact_name"
    ( cd "$artifact_dir" && in_nix oras push "$ref" "$artifact_name:$media_type" \
        --artifact-type "$media_type" )
  done <<< "$tags"
}

step_oras_pull() {
  # Pull APK from GHCR via curl + OCI HTTP API (no oras binary needed).
  # Public packages: anonymous bearer token from ghcr.io/token works.
  # All registry/namespace/image come from build.json::release.ghcr.
  # Optional 2nd arg: tag (default from release.phone_install.default_tag).
  local registry namespace image tag
  registry="$(_release_var '.release.ghcr.registry')"
  namespace="$(_release_var '.release.ghcr.namespace')"
  image="$(_release_var '.release.ghcr.image')"
  tag="${2:-$(_release_var '.release.phone_install.default_tag')}"
  tag="${tag:-latest}"

  local repo="$namespace/$image"
  local token manifest digest size asset_title
  log "oras-pull: $registry/$repo:$tag (via OCI HTTP API)"

  token="$(curl -sf "https://$registry/token?service=$registry&scope=repository:$repo:pull" | jq -r .token)"
  [ -n "$token" ] && [ "$token" != "null" ] || { errlog "no bearer token"; exit 1; }

  mkdir -p "$DIST_DIR"
  manifest="$(curl -sfL \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json" \
    "https://$registry/v2/$repo/manifests/$tag")"
  digest="$(jq -r '.layers[0].digest' <<<"$manifest")"
  size="$(jq -r '.layers[0].size' <<<"$manifest")"
  asset_title="$(jq -r '.layers[0].annotations["org.opencontainers.image.title"] // "superapp.apk"' <<<"$manifest")"
  [ -n "$digest" ] && [ "$digest" != "null" ] || { errlog "manifest has no layers"; exit 1; }

  local out="$DIST_DIR/$asset_title"
  log "  pulling $digest ($size bytes) → $out"
  curl -sfL -H "Authorization: Bearer $token" \
    "https://$registry/v2/$repo/blobs/$digest" -o "$out"

  # Verify digest matches what the manifest claimed.
  local got_sha
  got_sha="$(sha256sum "$out" | cut -d' ' -f1)"
  if [ "sha256:$got_sha" != "$digest" ]; then
    errlog "digest mismatch — got sha256:$got_sha, expected $digest"
    exit 1
  fi
  log "  ✓ $out (sha256:$got_sha)"
}

step_phone_install() {
  # Pull APK + copy to a target dir an Android file manager can see.
  # target_dir from build.json::release.phone_install; override via PHONE_TARGET env.
  step_oras_pull "$@"

  local target_dir asset_name src
  target_dir="${PHONE_TARGET:-$(_release_var '.release.phone_install.target_dir')}"
  # Expand ~ manually — jq returns the literal "~/...".
  target_dir="${target_dir/#\~/$HOME}"
  asset_name="$(_release_var '.release.phone_install.asset_name')"

  src="$(ls -1t "$DIST_DIR"/*.apk 2>/dev/null | head -1)"
  [ -f "$src" ] || { errlog "no APK in $DIST_DIR — oras-pull failed silently"; exit 1; }

  if [ ! -d "$target_dir" ]; then
    errlog "phone-install: $target_dir does not exist"
    errlog "  Termux: run 'termux-setup-storage' on the phone and accept the prompt"
    errlog "  Other:  set PHONE_TARGET=/path/to/dir env var"
    exit 1
  fi

  cp "$src" "$target_dir/$asset_name"
  log "✓ $target_dir/$asset_name"
  log "  Open Files app → Download → tap APK → install"
}

step_gh_release() {
  local enabled draft prerelease notes asset_tmpl asset rolling_tag
  enabled="$(_release_var '.release.gh_release.enabled')"
  [ "$enabled" = "true" ] || { log "gh-release: enabled=false — skip"; return 0; }

  draft="$(_release_var '.release.gh_release.draft')"
  prerelease="$(_release_var '.release.gh_release.prerelease')"
  notes="$(_release_var '.release.gh_release.generate_release_notes')"
  asset_tmpl="$(_release_var '.release.gh_release.asset_name')"
  asset="$(_resolve_template "$asset_tmpl")"
  rolling_tag="$(_release_var '.release.gh_release.rolling_tag')"

  # Stage asset with the requested filename. Prefer the release-variant
  # APK when present; fall back to the debug variant (CI's main-push
  # build is debug). Skip the cp when source == destination — happens
  # when asset_name equals one of the artifact filenames (e.g. rolling
  # mode where we set asset_name="Cloud-SuperApp.apk" matching the debug
  # artifact name directly).
  local src_release="$DIST_DIR/$(_release_var '.release.artifact.release')"
  local src_debug="$DIST_DIR/$(_release_var '.release.artifact.debug')"
  local dst="$DIST_DIR/$asset"
  if   [ -f "$src_release" ] && [ "$src_release" != "$dst" ]; then cp "$src_release" "$dst"
  elif [ -f "$src_debug"   ] && [ "$src_debug"   != "$dst" ]; then cp "$src_debug"   "$dst"
  fi
  [ -f "$dst" ] || { errlog "gh-release: staged asset $dst missing — no APK in $DIST_DIR?"; exit 1; }

  # ─── Rolling release (mode B) ───────────────────────────────────────
  # When release.gh_release.rolling_tag is set, the engine publishes
  # the APK to a SINGLE GitHub Release with that tag, overwriting any
  # existing asset (--clobber). This is what the ship workflow runs on
  # every main push so /releases/latest/download/<asset_name> is a
  # permanent download URL the linktree footer can link to.
  if [ -n "$rolling_tag" ] && [ "$rolling_tag" != "null" ]; then
    log "gh-release: rolling mode — tag=$rolling_tag ← $asset"
    # Create the release iff it doesn't exist yet (idempotent). Note
    # `gh release view` exits 1 when missing — that's our signal.
    if ! in_nix gh release view "$rolling_tag" >/dev/null 2>&1; then
      local create_flags=("$rolling_tag" --title "$rolling_tag" --target "${GITHUB_SHA:-main}" --notes "Rolling release — overwritten on every main push." --latest)
      [ "$draft" = "true" ]      && create_flags+=(--draft)
      [ "$prerelease" = "true" ] && create_flags+=(--prerelease)
      in_nix gh release create "${create_flags[@]}"
    fi
    # Overwrite the asset on every run.
    in_nix gh release upload "$rolling_tag" "$DIST_DIR/$asset" --clobber
    # Re-affirm the Latest flag every run — GH unmarks it when another
    # release is published (or sometimes after a sibling release is
    # deleted), and /releases/latest/ depends on it. Idempotent.
    in_nix gh release edit "$rolling_tag" --latest >/dev/null 2>&1 || true
  fi

  # ─── Per-tag immutable release (legacy mode) ────────────────────────
  # Still publish a tag-named release when invoked under a tag push, so
  # the legacy ea_cloud-superapp-vN.N.N tags keep producing pinned
  # releases. Rolling mode above runs in addition to this, not instead.
  #
  # Gate on GITHUB_REF (full "refs/tags/..." or "refs/heads/...") rather
  # than GITHUB_REF_NAME — GitHub Actions sets GITHUB_REF_NAME as a
  # default-env var the runner re-injects even when a step's env block
  # tries to clear it with an empty string, so it would always read
  # "main" on a main-branch push and falsely trigger this branch. Once
  # we know it's a tag push from GITHUB_REF, GITHUB_REF_NAME holds the
  # short tag name we want to use.
  local is_tag_push=0
  case "${GITHUB_REF:-}" in refs/tags/*) is_tag_push=1 ;; esac
  if [ "$is_tag_push" = "1" ] && [ -n "${GITHUB_REF_NAME:-}" ]; then
    local flags=("$GITHUB_REF_NAME" "$DIST_DIR/$asset" --title "$GITHUB_REF_NAME")
    [ "$draft" = "true" ]      && flags+=(--draft)
    [ "$prerelease" = "true" ] && flags+=(--prerelease)
    [ "$notes" = "true" ]      && flags+=(--generate-notes)
    log "gh release create $GITHUB_REF_NAME ← $asset"
    in_nix gh release create "${flags[@]}"
  elif [ -z "$rolling_tag" ] || [ "$rolling_tag" = "null" ]; then
    errlog "gh-release: neither rolling_tag set nor under a tag push — nothing to publish"
    exit 1
  fi
}

# ─── Sync the bundled QR manifest from the front/linktree project ──
# `assets/qrcodes/qrcodes.json` is the SOLE asset the in-app QR gallery
# reads (parsed by QrGalleryDialog.kt). It is the same JSON the linktree
# web project owns, treated as canonical source-of-truth. This command
# refreshes the bundled copy from the local front clone — declarative
# (one knob: FRONT_REPO env / default sibling path), idempotent, and
# leaves a clear diff in `git status` for review before committing.
step_sync_qrcodes() {
  local src="${FRONT_REPO:-$HOME/git/front}/a-Portals/linktree/src/typescript/qrcode/qrcodes.json"
  local dst="$SCRIPT_DIR/app/src/main/assets/qrcodes/qrcodes.json"
  [ -f "$src" ] || { errlog "sync-qrcodes: source not found: $src (set FRONT_REPO if your front clone lives elsewhere)"; exit 1; }
  mkdir -p "$(dirname "$dst")"
  cp "$src" "$dst"
  log "sync-qrcodes: $(basename "$dst") ← $(realpath --relative-to="$SCRIPT_DIR" "$src" 2>/dev/null || echo "$src") ($(wc -c < "$dst") B)"
  log "  review with: git -C $SCRIPT_DIR diff -- app/src/main/assets/qrcodes/qrcodes.json"
}

# ─── Sync the WireGuard tunnel engine from ea_net-wireguard ─────────
# Cherry-picks the embeddable `tunnel/` module of upstream
# wireguard-android (https://github.com/WireGuard/wireguard-android,
# Apache-2.0) into libs/net/. The upstream clone lives at
# ${UNIX_REPO:-$HOME/git/unix}/ea_net-wireguard/ (gitignored per the
# ea_*-*/ workspace-clone convention); this command copies a fixed
# include-list into the in-tree gradle module so CI can build the
# native libwg-go.so + libwg.so + libwg-quick.so without the sibling
# clone being present.
#
# Idempotent — rsync --delete on the destinations, so a fresh upstream
# pull → one `./build.sh sync-net` → clear `git diff` for review.
step_sync_net() {
  local upstream="${UNIX_REPO:-$HOME/git/unix}/ea_net-wireguard/tunnel"
  local dst="$SCRIPT_DIR/libs/net"
  [ -d "$upstream" ] || { errlog "sync-net: upstream not found: $upstream (clone https://github.com/WireGuard/wireguard-android.git there, or set UNIX_REPO=)"; exit 1; }
  command -v rsync >/dev/null 2>&1 || { errlog "sync-net: rsync required (in nix-shell: nix shell nixpkgs#rsync)"; exit 1; }

  mkdir -p "$dst/src/main/java" "$dst/src/main/cpp"

  # Java sources: package roots com.wireguard.{android.backend,
  # android.util, config, crypto, util}. Mirror in full EXCEPT the
  # rooted-Android backend trio:
  #   • WgQuickBackend.java — depends on the missing wireguard-tools
  #     submodule + a libwg.so we don't build.
  #   • RootShell.java      — invokes `su`; only used by WgQuickBackend.
  #   • ToolsInstaller.java — installs wg/wg-quick binaries to /system;
  #     also rooted-only.
  # SuperApp targets unrooted Android via GoBackend only.
  # Exclude rules MUST come before include rules (rsync uses
  # first-matching-rule precedence). --delete-excluded so stale copies
  # of excluded files in the destination get removed (default --delete
  # treats excluded files as protected on both sides). No local-only
  # java is kept under this tree, so wiping excluded dest paths is safe.
  rsync -a --delete --delete-excluded \
    --exclude="WgQuickBackend.java" \
    --exclude="RootShell.java" \
    --exclude="ToolsInstaller.java" \
    --include="*/" --include="*.java" --exclude="*" \
    "$upstream/src/main/java/" "$dst/src/main/java/"

  # AndroidManifest declares GoBackend\$VpnService + BIND_VPN_SERVICE.
  # Manifest-merger pulls it into the app at build time.
  cp "$upstream/src/main/AndroidManifest.xml" "$dst/src/main/AndroidManifest.xml"

  # Native build chain — only what libwg-go.so needs:
  #   libwg-go/  (Go userspace + Makefile + go.mod/go.sum)
  #   ndk-compat (compat shim the Makefile-built Go wrapper links to)
  # Excludes:
  #   wireguard-tools/ (empty upstream submodule; rooted-backend only)
  #   elf-cleaner/     (empty; post-process for libwg.so/libwg-quick.so)
  #   CMakeLists.txt   (upstream's references the missing dirs — we
  #                     ship our own slim version, kept verbatim across
  #                     resyncs, that only builds libwg-go.so).
  rsync -a --delete \
    --include="libwg-go/" --include="libwg-go/*" \
    --include="ndk-compat/" --include="ndk-compat/*" \
    --exclude="*" \
    "$upstream/tools/" "$dst/src/main/cpp/"

  log "sync-net: libs/net populated from $(realpath --relative-to="$SCRIPT_DIR" "$upstream" 2>/dev/null || echo "$upstream")"
  log "  java     : $(find "$dst/src/main/java" -name '*.java' | wc -l) file(s)"
  log "  cpp/tools: $(find "$dst/src/main/cpp" -type f | wc -l) file(s)"
  log "  review with: git -C $SCRIPT_DIR status -s -- libs/net/"
}

case "$CMD" in
  build)      step_build ;;
  release)    step_release ;;
  dev)        step_dev ;;
  test)       step_test ;;
  instrument) step_instrument ;;
  lint)       step_lint ;;
  clean)      step_clean ;;
  shell)      step_shell ;;
  ship)       step_ship ;;
  oras-push)    step_oras_push ;;
  oras-pull)    step_oras_pull "$@" ;;
  phone-install) step_phone_install "$@" ;;
  gh-release)   step_gh_release ;;
  sync-qrcodes) step_sync_qrcodes ;;
  sync-net)     step_sync_net ;;
  help|*)
    sed -n '2,/^set -euo/p' "$0" | sed 's/^# *//; /^set/d; /^$/d'
    ;;
esac
