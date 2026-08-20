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
#   ./regen.sh --constellation-only  # just constellation-fleet.json (no
#                                     # gitignored upstream files needed —
#                                     # this is what app/build.gradle runs
#                                     # automatically on every build)

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# ── constellation-fleet.json — the Constellation AppStore's app registry.
# Auto-scanned from each sibling ea_cloud-*/build.json (self-registering, DRY),
# in TWO shapes, no hand-maintained list (FIRE 4/6):
#   • top-level apps (browser/vault/wallet/superapp/nav/ide) — identity at
#     .android.application_id + .release.ghcr.image.
#   • fork-apps (dialer/chat/mail/matrix) — the promoted ex-cloud-comms forks,
#     each now its OWN ea_cloud-<id> dir + ship-cloud-<id>.yml CI; identity
#     lives under .forks.<key>. Scanned ONLY for non-top-level dirs, so the IDE
#     hub's own nested forks (editor/files/utils) are never pulled in.
# The cloud-comms HUB is decommissioned (archived to z_archive/ea_cloud-comms),
# so it is no longer scanned and drops out of the fleet automatically.
# Baked into BuildConfig.CONSTELLATION_FLEET_B64 by app/build.gradle. Depends
# ONLY on the sibling repos (always present), so it runs before the cloud-data
# snapshot resolution below.
UNIX="$(cd "$HERE/../.." && pwd)"
regen_constellation() {
    command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; return 1; }
    # owner/repo = the monorepo these apps ship from (invariant identity).
    local rel="https://github.com/diegonmarcos/cloud-unix/releases"
    local tree="https://github.com/diegonmarcos/cloud-unix/tree/main"
    local pkg="https://github.com/diegonmarcos/cloud-unix/pkgs/container"

    # Scan every sibling ea_cloud-*/build.json. A dir self-registers as EITHER a
    # top-level app (has .android.application_id + .release.ghcr.image) OR a
    # fork-app (no top-level marker, but a real .forks.<key> entry). id = the dir
    # basename sans the ea_cloud- prefix. Nothing is hand-listed; archiving a dir
    # (e.g. ea_cloud-comms → z_archive/) drops it from the fleet automatically.
    local apps="[]" bj id dir
    for bj in "$UNIX"/ea_cloud-*/build.json; do
        [ -f "$bj" ] || continue
        dir="$(basename "$(dirname "$bj")")"; id="${dir#ea_cloud-}"
        if jq -e '.lib_apks.scan' "$bj" >/dev/null 2>&1; then
            # ── Multi-lib repo (ea_cloud-libs) ────────────────────────────────
            # One repo, one APK per library module, so it expands into MANY fleet
            # entries instead of one. The module set is not listed anywhere: it is
            # the same scan+exclude that settings.gradle and build.sh apply, so a
            # new module under ea_cloud-superapp/libs/ appears in the Libs tab
            # automatically. Everything else here is derived from the dir name.
            local lscan lexcl lprefix laprefix limgprefix lmod lasset
            lscan="$(dirname "$bj")/$(jq -r '.lib_apks.scan' "$bj")"
            lexcl="$(jq -r '(.lib_apks.exclude // {}) | keys[]' "$bj" 2>/dev/null | tr '\n' ' ')"
            lprefix="$(jq -r '.lib_apks.application_id_prefix' "$bj")"
            laprefix="$(jq -r '.lib_apks.asset_prefix' "$bj")"
            limgprefix="$(jq -r '.release.ghcr.image_prefix' "$bj")"
            for lmod in $(ls -1 "$lscan" 2>/dev/null | sort); do
                [ -f "$lscan/$lmod/build.gradle" ] || continue
                case " $lexcl " in *" $lmod "*) continue ;; esac
                # Cloud-Lib-Translate-Mlkit.apk — each '-' segment capitalised,
                # matching build.sh's asset naming exactly.
                lasset="$laprefix$(echo "$lmod" | awk -F- '{for(i=1;i<=NF;i++){$i=toupper(substr($i,1,1)) substr($i,2)}}1' OFS=-).apk"
                apps="$(jq --argjson acc "$apps" --arg id "lib-$lmod" --arg dir "$dir" \
                           --arg mod "$lmod" --arg asset "$lasset" \
                           --arg pkgid "$lprefix.$(echo "$lmod" | tr -d '-')" \
                           --arg img "$limgprefix$lmod" \
                           --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
                    ($rel + "/latest/download/" + $asset) as $url
                    | $acc + [ { id: $id,
                                 label: ("Lib: " + $mod),
                                 package: $pkgid,
                                 alt_id: null,
                                 registry: .release.ghcr.registry,
                                 namespace: .release.ghcr.namespace,
                                 image: $img,
                                 tag: (.release.auto_update.tag // "latest"),
                                 asset: $asset,
                                 release_url: $url,
                                 repo_url: ($tree + "/ea_cloud-superapp/libs/" + $mod),
                                 ghcr_page: ($pkg + "/" + $img),
                                 blocked: false,
                                 kind: "lib" } ]' "$bj")"
            done
        elif jq -e '.release.ghcr.image and .android.application_id' "$bj" >/dev/null 2>&1; then
            # ── Top-level app (browser/vault/wallet/superapp/nav/ide) ─────────
            # Publishes a rolling `latest` release → stable direct-download URL.
            apps="$(jq --argjson acc "$apps" --arg id "$id" --arg dir "$dir" \
                       --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
                ($rel + "/latest/download/" + .release.gh_release.asset_name) as $url
                | $acc + [ { id: $id,
                             label: (.name // $id),
                             package: .android.application_id,
                             alt_id: null,
                             registry: .release.ghcr.registry,
                             namespace: .release.ghcr.namespace,
                             image: .release.ghcr.image,
                             tag: (.release.auto_update.tag // "latest"),
                             asset: .release.gh_release.asset_name,
                             release_url: $url,
                             repo_url: ($tree + "/" + $dir),
                             ghcr_page: ($pkg + "/" + .release.ghcr.image),
                             blocked: false,
                             kind: (.release.kind // "app") } ]' "$bj")"
        elif jq -e '(.forks // {}) | to_entries
                    | map(select(.key != "_doc" and (.value|type=="object")))
                    | length > 0' "$bj" >/dev/null 2>&1; then
            # ── Fork-app (dialer/chat/mail/matrix) ───────────────────────────
            # A standalone ea_cloud-<id> promoted from an ex-cloud-comms fork:
            # identity under .forks.<key> (one real fork per dir). Forks publish
            # --latest=false tagged releases → link the releases page, not a
            # /latest/download. keystore-signed package id is preserved.
            apps="$(jq --argjson acc "$apps" --arg id "$id" --arg dir "$dir" \
                       --arg rel "$rel" --arg tree "$tree" --arg pkg "$pkg" '
                (.release.ghcr) as $cg
                | ( .forks | to_entries
                    | map(select(.key != "_doc" and (.value|type=="object")))
                    | .[0].value ) as $f
                | $acc + [ { id: $id,
                             label: ($f.label // $id),
                             package: $f.app_id,
                             alt_id: ($f.alt_id // null),
                             registry: $cg.registry,
                             namespace: $cg.namespace,
                             image: $f.image,
                             tag: "latest",
                             asset: ($f.image + ".apk"),
                             release_url: $rel,
                             repo_url: ($tree + "/" + $dir),
                             ghcr_page: ($pkg + "/" + $f.image),
                             blocked: ($f.blocked_on != null),
                             kind: (.release.kind // "app") } ]' "$bj")"
        fi
    done

    jq -n --argjson apps "$apps" '{ version: 1, apps: $apps }' \
        > "$HERE/constellation-fleet.json"
    echo "constellation apps: $(jq '.apps | length' "$HERE/constellation-fleet.json")"
}
regen_constellation

[ "${1:-}" = "--constellation-only" ] && exit 0

CONSOLIDATED="${1:-}"
MESH="${2:-}"
LINKTREE="${3:-}"

if [ -z "$CONSOLIDATED" ]; then
    for cand in \
        "$HOME/git/cloud-infra/2_configs/dist/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-infra/I_cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-data/_cloud-data-consolidated.json" \
        "$HOME/git/cloud-unix/cloud-data/_cloud-data-consolidated.json"; do
        [ -f "$cand" ] && { CONSOLIDATED="$cand"; break; }
    done
fi
if [ -z "$MESH" ]; then
    for cand in \
        "$HOME/git/cloud-infra/2_configs/dist/mesh-snapshot.json" \
        "$HOME/git/cloud-infra/a_solutions/bb-net_wireguard-mesh/src/data/mesh.json" \
        "$HOME/git/cloud-data/cloud-data-wg-mesh-snapshot.json"; do
        [ -f "$cand" ] && { MESH="$cand"; break; }
    done
fi
if [ -z "$LINKTREE" ]; then
    for cand in \
        "$HOME/git/front/a-Portals/linktree/src/data/projects.json"; do
        [ -f "$cand" ] && { LINKTREE="$cand"; break; }
    done
fi

: "${CONSOLIDATED:?consolidated.json not found; pass as arg 1}"
: "${MESH:?mesh.json not found; pass as arg 2}"
: "${LINKTREE:?projects.json not found; pass as arg 3}"

# Auto-discover cloud-fleet-containers-declared.json (lives next to consolidated).
FLEET="${FLEET:-$(dirname "$CONSOLIDATED")/cloud-fleet-containers-declared.json}"
if [ ! -f "$FLEET" ]; then
  echo "WARN: cloud-fleet-containers-declared.json not found alongside consolidated — cloud_services.json will not be regenerated" >&2
  FLEET=""
fi

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 1; }

echo "→ mesh:         $MESH"
echo "→ consolidated: $CONSOLIDATED"
echo "→ linktree:     $LINKTREE"
echo "→ writing into: $HERE/"

# Mesh is a verbatim snapshot (wg-mesh/v1 schema; the APK parser owns
# the field-set). No transform.
cp "$MESH" "$HERE/mesh.json"

# Linktree projects.json is the source of truth for what shows
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

# Derive cloud_services.json from cloud-fleet-containers-declared.json.
# Populates the groups/subgroups/services arrays that the CloudDash data model reads.
if [ -n "$FLEET" ]; then
  jq '
    def make_group(group_id; group_label; group_key):
      { id: group_id, label: group_label, subgroups:
        [ .fleet.containers[group_key]
          | group_by(.subgroup)[]
          | { label: .[0].subgroup, services: map({
                id:         .id,
                name:       .name,
                vm:         .vm,
                port:       .port,
                public_url: .public_url
              })
          }
        ]
      };
    {
      version: 2,
      groups: [
        make_group("infra"; "Infra Apps"; "infra-apps"),
        make_group("user";  "User Apps";  "user-apps"),
        { id: "providers", label: "Providers (VPS)", subgroups: [
            { label: "VMs",     services: ( .fleet.vms["vm-x86"] + .fleet.vms["vm-arm"] | map({ id: .id, name: (.alias // .id), arch: .arch }) ) },
            { label: "Runners", services: .categories.runners | map({ id: .id, name: .name }) }
          ]
        },
        { id: "dbs", label: "DBs (storage)", subgroups: [
            { label: "DBs", services: .categories["db-hd"] | map({ id: .id, engine: .engine, service: .service }) },
            { label: "S3",  services: .categories["db-s3"] | map({ id: .id, name: .name, region: .region }) }
          ]
        },
        { id: "mcpapi", label: "MCP & API", subgroups: [
            { label: "MCPs", services: .categories.mcps | map({ id: .id, name: .name, port: .port, public_url: .public_url }) },
            { label: "APIs", services: .categories.apis | map({ id: .id, name: .name, port: .port, public_url: .public_url }) }
          ]
        }
      ]
    }
  ' "$FLEET" > "$HERE/cloud_services.json"
  echo "cloud_services:   $(jq '[.groups[].subgroups[].services | length] | add' "$HERE/cloud_services.json") services across $(jq '.groups | length' "$HERE/cloud_services.json") groups"
fi
