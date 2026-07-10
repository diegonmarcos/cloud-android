#!/usr/bin/env bash
# Regenerate the three data snapshots the APK ships with:
#
#   mesh.json             — wg0 mesh nodes / peers / transports
#                            (source: cloud/a_solutions/bb-net_wireguard-mesh/
#                             src/data/mesh.json, schema wg-mesh/v1)
#
#   services_public.json  — containers WITH proxy.domain (caddy edge)
#   services_private.json — containers WITHOUT proxy (internal DBs,
#                            queues, MCPs, dev tooling, …)
#                            (source: cloud-data's
#                             _cloud-data-consolidated.json)
#
# Both upstream files are gitignored, so we commit the derived snapshots
# here. Re-run this script any time the upstream changes; the gradle
# build picks the new bytes up automatically on the next APK assembly.
#
# Usage:
#   ./regen.sh                       # auto-discover upstream paths
#   ./regen.sh <consolidated.json> <mesh.json>

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# ── constellation-fleet.json — the Constellation AppStore's app registry.
# Auto-scanned from each sibling app's OWN build.json (self-registering, DRY):
# top-level hubs (superapp/comms-hub/nav/ide-hub) from android.application_id +
# release.ghcr, plus the cloud-comms forks (mail/chat/matrix/dialer) from
# ea_cloud-comms/build.json::forks.*. No hand-maintained app list (FIRE 4/6).
# Baked into BuildConfig.CONSTELLATION_FLEET_B64 by app/build.gradle. Depends
# ONLY on the sibling repos (always present), so it runs before the cloud-data
# snapshot resolution below.
UNIX="$(cd "$HERE/../.." && pwd)"
regen_constellation() {
    command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; return 1; }
    # owner/repo = the monorepo these apps ship from (invariant identity).
    local rel="https://github.com/diegonmarcos/unix/releases"

    # ── Top-level apps: TRULY auto-discovered (FIRE 4/6) ──────────────────
    # Scan every sibling ea_cloud-*/build.json and keep the ones that declare
    # BOTH a GHCR image and an Android application_id — the shippable-app
    # marker. A new ea_cloud-<x> app self-registers into the AppStore the
    # instant its build.json has release.ghcr + android.application_id; nothing
    # here is hand-listed. id = the dir basename sans the ea_cloud- prefix
    # (browser/vault/wallet/superapp/comms/ide/nav). The cloud-comms FORKS
    # (mail/chat/matrix/dialer) are NOT separate dirs — they live under
    # ea_cloud-comms/build.json::forks.* and are appended below.
    local tops="[]" bj id
    for bj in "$UNIX"/ea_cloud-*/build.json; do
        [ -f "$bj" ] || continue
        jq -e '.release.ghcr.image and .android.application_id' "$bj" >/dev/null 2>&1 || continue
        id="$(basename "$(dirname "$bj")")"; id="${id#ea_cloud-}"
        tops="$(jq --argjson acc "$tops" --arg id "$id" --arg rel "$rel" '
            ($rel + "/latest/download/" + .release.gh_release.asset_name) as $url
            | $acc + [ { id: $id,
                         label: (.name // $id),
                         package: .android.application_id,
                         registry: .release.ghcr.registry,
                         namespace: .release.ghcr.namespace,
                         image: .release.ghcr.image,
                         tag: (.release.auto_update.tag // "latest"),
                         alt_id: null,
                         asset: .release.gh_release.asset_name,
                         # top-level apps publish a rolling `latest` release →
                         # stable direct-download URL.
                         release_url: $url,
                         blocked: false } ]' "$bj")"
    done

    jq -n \
        --argjson tops "$tops" \
        --slurpfile co "$UNIX/ea_cloud-comms/build.json" \
        --arg rel "$rel" '
        ($co[0].release.ghcr) as $cg
        | { version: 1,
            apps: (
                $tops
              + ( $co[0].forks | to_entries
                  | map(select(.value | type == "object"))
                  | map({ id: ("comms-" + .key),
                          label: .value.label,
                          package: .value.app_id,
                          alt_id: (.value.alt_id // null),
                          registry: $cg.registry,
                          namespace: $cg.namespace,
                          image: .value.image,
                          tag: "latest",
                          asset: (.value.image + ".apk"),
                          # forks publish --latest=false tagged releases → no
                          # stable /latest/download; link the releases page.
                          release_url: $rel,
                          blocked: (.value.blocked_on != null) }) ) ) }
        ' > "$HERE/constellation-fleet.json"
    echo "constellation apps: $(jq '.apps | length' "$HERE/constellation-fleet.json")"
}
regen_constellation

CONSOLIDATED="${1:-}"
MESH="${2:-}"
LINKTREE="${3:-}"

if [ -z "$CONSOLIDATED" ]; then
    for cand in \
        "$HOME/git/cloud/I_cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/unix/cloud-data/_cloud-data-consolidated.json"; do
        [ -f "$cand" ] && { CONSOLIDATED="$cand"; break; }
    done
fi
if [ -z "$MESH" ]; then
    for cand in \
        "$HOME/git/cloud/a_solutions/bb-net_wireguard-mesh/src/data/mesh.json" \
        "$HOME/git/cloud-data/cloud-data-wg-mesh-snapshot.json"; do
        [ -f "$cand" ] && { MESH="$cand"; break; }
    done
fi
if [ -z "$LINKTREE" ]; then
    for cand in \
        "$HOME/git/front/a-Portals/linktree/src/data/personal-tools.json"; do
        [ -f "$cand" ] && { LINKTREE="$cand"; break; }
    done
fi

: "${CONSOLIDATED:?consolidated.json not found; pass as arg 1}"
: "${MESH:?mesh.json not found; pass as arg 2}"
: "${LINKTREE:?personal-tools.json not found; pass as arg 3}"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 1; }

echo "→ mesh:         $MESH"
echo "→ consolidated: $CONSOLIDATED"
echo "→ linktree:     $LINKTREE"
echo "→ writing into: $HERE/"

# Mesh is a verbatim snapshot (wg-mesh/v1 schema; the APK parser owns
# the field-set). No transform.
cp "$MESH" "$HERE/mesh.json"

# Linktree personal-tools.json is the source of truth for what shows
# under the Tools aggregator (Apps mode = SUITE+LAB+CIRCUS slides;
# Admin mode = CLOUD slide). Verbatim copy — the APK parser picks
# which slide to render based on build.json::ui.sections[tools].stack_*.
cp "$LINKTREE" "$HERE/linktree.json"

# Public services = containers WITH proxy.domain or proxy.parent_domain.
jq '
[.services | to_entries[] as $s
 | ($s.value.containers // []) | .[]
 | select(.proxy.domain or .proxy.parent_domain)
 | {
     name: .container_name,
     service: $s.key,
     vm: ($s.value.vm // "—"),
     public_url: (if .proxy.domain then .proxy.domain else (.proxy.parent_domain + (.proxy.base_path // "")) end),
     auth: (.proxy.auth // "none"),
     private_dns: ((.dns // .container_name) + (if .port then (":" + (.port|tostring)) else "" end)),
     port: (.port // null),
     category: ($s.value.category // null)
   }]
' "$CONSOLIDATED" > "$HERE/services_public.json"

# Private services = containers WITHOUT proxy.
jq '
[.services | to_entries[] as $s
 | ($s.value.containers // []) | .[]
 | select(.proxy.domain or .proxy.parent_domain | not)
 | select(.container_name)
 | {
     name: .container_name,
     service: $s.key,
     vm: ($s.value.vm // "—"),
     private_dns: ((.dns // .container_name) + (if .port then (":" + (.port|tostring)) else "" end)),
     port: (.port // null),
     protocol: (.protocol // "tcp"),
     category: ($s.value.category // null),
     db_engine: (.db_engine // null)
   }]
' "$CONSOLIDATED" > "$HERE/services_private.json"

echo "mesh nodes:       $(jq '.nodes | length' "$HERE/mesh.json")"
echo "mesh peers:       $(jq '.peers | length' "$HERE/mesh.json")"
echo "public services:  $(jq length    "$HERE/services_public.json")"
echo "private services: $(jq length    "$HERE/services_private.json")"
echo "linktree slides:  $(jq '.slides | length' "$HERE/linktree.json")"
