#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-publish-gate — publish only when the source moved  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Runs as the first step of every ship job, right after checkout, and answers
# one question: has anything that can change this APK changed since the bytes
# currently on the release were published?
#
#   check <app-dir> [variant-id]
#       sets step output skip=true|false (plus identity/asset/tag).
#       skip=true  → the whole rest of the job is if:'d out. The run is GREEN,
#                    takes ~30s instead of ~30min, and the release asset's
#                    updated_at does not move — so no phone sees an update.
#   stamp <app-dir> [variant-id]
#       last step of a job that DID publish: uploads <asset>.source carrying
#       the identity those bytes were built from.
#
# The identity lives as a small sidecar asset beside the APK, in the same
# place and with the same lifetime as the .sha256 sidecar. It is not derived
# from the APK, so a non-reproducible rebuild cannot perturb it, and it is
# not kept in a separate store that could drift away from the asset it
# describes.
#
# WHEN THE GATE DOES NOT APPLY (fail-open, always publish):
#   * a tag push — cutting a tag is an explicit intent to publish
#   * PUBLISH_GATE_FORCE=1
#   * the release/sidecar/sha256 is missing or unreadable — an incomplete
#     publish must be repaired, never skipped
#   * build.json declares no gh_release asset name
#
# Requires: gh (authenticated via GH_TOKEN), jq, git.
set -eu

ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"

CMD="${1:-}"
APP="${2:-}"; APP="${APP%/}"
VARIANT="${3:-}"

[ -n "$CMD" ] && [ -n "$APP" ] || {
    echo "usage: $(basename "$0") check|stamp <app-dir> [variant-id]" >&2; exit 2; }

BJ="$ROOT/$APP/build.json"
log() { printf '[publish-gate] %s\n' "$1"; }

# Emit a step output AND echo it, so the same script is readable in a local
# shell and consumable by `if: steps.gate.outputs.skip != 'true'`.
out() {
    [ -n "${GITHUB_OUTPUT:-}" ] && printf '%s\n' "$1" >> "$GITHUB_OUTPUT"
    log "$1"
    return 0
}

# Public asset name for the active variant — the SAME resolution every
# build.sh uses (release.variants[].gh_asset, else release.gh_release
# .asset_name), so the sidecar always lands beside the asset it describes and
# each ABI variant is gated independently.
_asset() {
    n=""
    if [ -n "$VARIANT" ] && [ -f "$BJ" ]; then
        n="$(jq -r --arg v "$VARIANT" \
             '(.release.variants[]? | select(.id==$v) | .gh_asset) // empty' "$BJ")"
    fi
    [ -n "$n" ] || n="$(jq -r '.release.gh_release.asset_name // empty' "$BJ" 2>/dev/null)"
    printf '%s' "$n"
}
_tag() {
    t="$(jq -r '.release.gh_release.rolling_tag // empty' "$BJ" 2>/dev/null)"
    printf '%s' "${t:-latest}"
}

IDENTITY="$(sh "$SELF_DIR/cloud-android-source-identity.sh" compute "$APP")"
ASSET="$(_asset)"
TAG="$(_tag)"

case "$CMD" in

check)
    out "identity=$IDENTITY"
    out "asset=$ASSET"
    out "tag=$TAG"

    publish() { out "skip=false"; log "$1"; exit 0; }

    case "${GITHUB_REF:-}" in
        refs/tags/*) publish "tag push — gate bypassed, publishing" ;;
    esac
    if [ "${PUBLISH_GATE_FORCE:-0}" = "1" ]; then
        publish "PUBLISH_GATE_FORCE=1 — gate bypassed, publishing"
    fi
    if [ -z "$ASSET" ]; then
        publish "$APP declares no gh_release asset name — gate not applicable"
    fi

    names="$(gh release view "$TAG" --json assets --jq '.assets[].name' 2>/dev/null || true)"
    has() { printf '%s\n' "$names" | grep -qxF "$1"; }
    if ! has "$ASSET"; then       publish "$ASSET absent from release $TAG — publishing"; fi
    if ! has "$ASSET.sha256"; then publish "$ASSET.sha256 sidecar missing on $TAG — publishing"; fi
    if ! has "$ASSET.source"; then publish "$ASSET.source identity sidecar missing on $TAG — publishing"; fi

    tmp="$(mktemp -d)"
    if ! gh release download "$TAG" --pattern "$ASSET.source" --dir "$tmp" --clobber >/dev/null 2>&1; then
        rm -rf "$tmp"; publish "could not read $ASSET.source from $TAG — publishing"
    fi
    prev="$(tr -d '[:space:]' < "$tmp/$ASSET.source")"
    rm -rf "$tmp"

    if [ "$prev" = "$IDENTITY" ]; then
        out "skip=true"
        log "unchanged since $(git -C "$ROOT" rev-parse --short HEAD) — publish skipped"
        log "  $ASSET on release $TAG already carries source identity $IDENTITY"
        log "  inputs hashed:"
        sh "$SELF_DIR/cloud-android-source-identity.sh" explain "$APP" | sed 's/^/    /'
        exit 0
    fi
    out "skip=false"
    log "source identity moved ${prev} → ${IDENTITY} — publishing"
    ;;

stamp)
    if [ -z "$ASSET" ]; then log "no gh_release asset name for $APP — nothing to stamp"; exit 0; fi
    # A manual dispatch can build WITHOUT publishing (create_release=false).
    # Stamping there would claim the release carries bytes built from this
    # identity when it still holds the old ones, and every later push would
    # then skip against a lie. Only the push path — which always publishes —
    # stamps by default.
    if [ "${GITHUB_EVENT_NAME:-}" = "workflow_dispatch" ] && [ "${PUBLISH_GATE_STAMP:-0}" != "1" ]; then
        log "workflow_dispatch: not stamping (set PUBLISH_GATE_STAMP=1 to force)"; exit 0
    fi
    tmp="$(mktemp -d)"
    printf '%s\n' "$IDENTITY" > "$tmp/$ASSET.source"
    gh release upload "$TAG" "$tmp/$ASSET.source" --clobber
    rm -rf "$tmp"
    log "stamped $ASSET.source on $TAG = $IDENTITY"
    ;;

*)  echo "unknown command: $CMD" >&2; exit 2 ;;
esac
