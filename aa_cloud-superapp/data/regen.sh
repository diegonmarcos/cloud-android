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

CONSOLIDATED="${1:-}"
MESH="${2:-}"

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

: "${CONSOLIDATED:?consolidated.json not found; pass as arg 1}"
: "${MESH:?mesh.json not found; pass as arg 2}"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 1; }

echo "→ mesh:        $MESH"
echo "→ consolidated: $CONSOLIDATED"
echo "→ writing into: $HERE/"

# Mesh is a verbatim snapshot (wg-mesh/v1 schema; the APK parser owns
# the field-set). No transform.
cp "$MESH" "$HERE/mesh.json"

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

echo "mesh nodes:      $(jq '.nodes | length' "$HERE/mesh.json")"
echo "mesh peers:      $(jq '.peers | length' "$HERE/mesh.json")"
echo "public services: $(jq length    "$HERE/services_public.json")"
echo "private services: $(jq length   "$HERE/services_private.json")"
