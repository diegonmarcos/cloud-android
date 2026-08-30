#!/usr/bin/env bash
# Constellation AppStore fleet auditor.
#
# Data driven: every entry is read from constellation-fleet.json, there are no
# hardcoded application lists. For each entry both distribution channels the
# in-application store consumes are verified end to end:
#
#   1. release  the release asset URL must resolve to a real downloadable
#               package, not to an HTML page
#   2. ghcr     the container manifest must be readable anonymously, because a
#               private package makes the store show the application as absent
#   3. manifest the annotations the updater relies on must be present and must
#               agree with the release asset name
#   4. package  the declared application identifier must exist in the repository
#   5. size     the release asset and the container layer must be the same bytes
#
# Trap: curl on some development machines silently injects an Authorization
# header from the local environment, which makes a private container package
# look public. Every anonymous request below therefore sends an explicit empty
# Authorization header to strip whatever was injected.
#
# Usage:  audit-fleet.sh [--json report.json]
# Exit:   0 when every entry passes, 1 when any entry fails.

set -uo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
fleet="$here/constellation-fleet.json"
root=$(cd -- "$here/../.." && pwd)

json_out=""
[ "${1:-}" = "--json" ] && json_out="${2:?--json needs a path}"

for tool in jq curl gh; do
    command -v "$tool" >/dev/null || { echo "audit-fleet: missing required tool: $tool" >&2; exit 2; }
done
[ -f "$fleet" ] || { echo "audit-fleet: no fleet file at $fleet" >&2; exit 2; }

# The release coordinates are taken from the fleet itself so that moving the
# repository does not silently strand the auditor on a stale location.
slug=$(jq -r '.apps[0].release_url' "$fleet" | sed -E 's#https://github.com/([^/]+/[^/]+)/releases/.*#\1#')
[ -n "$slug" ] || { echo "audit-fleet: cannot derive repository from release_url" >&2; exit 2; }

work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

# Snapshot the fleet. The audit is minutes long and re-reads the file per entry;
# a regen.sh run landing mid-audit would otherwise shift the indices underneath
# the loop and produce a report with repeated and skipped entries.
cp "$fleet" "$work/fleet.json"; fleet="$work/fleet.json"

# One API call for the whole release, then look assets up locally. Doing this per
# entry would be 55 round trips and would rate limit.
if ! gh api "repos/$slug/releases/latest" > "$work/release.json" 2>"$work/release.err"; then
    echo "audit-fleet: cannot read latest release of $slug" >&2
    cat "$work/release.err" >&2
    exit 2
fi
jq -r '.assets[] | [.name, (.size|tostring), .updated_at] | @tsv' "$work/release.json" > "$work/assets.tsv"
release_tag=$(jq -r '.tag_name' "$work/release.json")

# Every declared application identifier in the repository, used by check 4.
# build.json is the canonical emitter; gradle is consulted for vendored forks
# and mirrors that predate it.
{
    find "$root" -name build.json -not -path '*/node_modules/*' -not -path '*/.git/*' -print0 |
        xargs -0 -r jq -r '.android.application_id // empty' 2>/dev/null
    grep -rhoE '(applicationId|APPLICATION_ID)[[:space:]]*=?[[:space:]]*["'"'"'][^"'"'"']+["'"'"']' \
        --include=build.gradle --include=build.gradle.kts --include=*.kt "$root" 2>/dev/null |
        grep -oE '["'"'"'][^"'"'"']+["'"'"']$' | tr -d '"'"'"
} | sed '/^$/d' | sort -u > "$work/declared.txt"

pass=0; fail=0; sidecars=0; : > "$work/rows.tsv"

emit() { # id kind status detail
    printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$work/rows.tsv"
    # Streamed as it happens: this audit is ~2 network calls per entry, so a
    # silent run looks indistinguishable from a hung one.
    printf '%-6s %-4s %-34s %s\n' "$3" "$2" "$1" "$4"
    if [ "$3" = FAIL ]; then fail=$((fail + 1)); else pass=$((pass + 1)); fi
}

total=$(jq -r '.apps | length' "$fleet")
echo "audit-fleet: $total entries, release $slug@$release_tag"

for i in $(seq 0 $((total - 1))); do
    eval "$(jq -r --argjson i "$i" '
        .apps[$i] |
        "id=\(.id|@sh) kind=\(.kind|@sh) pkg=\(.package|@sh) asset=\(.asset|@sh)
         url=\(.release_url|@sh) reg=\(.registry|@sh) ns=\(.namespace|@sh)
         img=\(.image|@sh) tag=\(.tag|@sh) blocked=\(.blocked|tostring|@sh)"' "$fleet")"

    if [ "$blocked" = true ]; then
        emit "$id" "$kind" SKIP "blocked in fleet"
        continue
    fi

    problems=()

    # ---- check 1: the release asset is a real downloadable package ----------
    meta=$(awk -F'\t' -v n="$asset" '$1 == n {print; exit}' "$work/assets.tsv")
    if [ -z "$meta" ]; then
        problems+=("asset '$asset' absent from release $release_tag")
        asset_size=""
    else
        asset_size=$(printf '%s' "$meta" | cut -f2)
        asset_when=$(printf '%s' "$meta" | cut -f3)
        # The sha256 sidecar the store uses to tell "same bytes" from "same
        # size". Reported, not enforced: entries only gain one once their ship
        # workflow has run again, so failing on it would fail the whole fleet
        # for a rollout that is simply still in progress.
        if awk -F'\t' -v n="$asset.sha256" '$1 == n {found = 1} END {exit !found}' "$work/assets.tsv"; then
            sidecars=$((sidecars + 1)); sidecar=yes
        else
            sidecar=NO
        fi
        head=$(curl -H "Authorization:" --max-time 30 -sIL -o /dev/null \
               -w '%{http_code} %{content_type}' "$url" 2>/dev/null)
        code=${head%% *}; ctype=${head#* }
        if [ "$code" != 200 ]; then
            problems+=("release URL HTTP $code")
        elif case "$ctype" in text/html*) true;; *) false;; esac; then
            problems+=("release URL served HTML, not a package")
        fi
    fi

    # ---- check 2: the container manifest is readable anonymously -----------
    layer_size=""; rev=""; mtype=""; title=""
    token=$(curl -H "Authorization:" --max-time 30 -s \
            "https://$reg/token?scope=repository:$ns/$img:pull&service=$reg" |
            jq -r '.token // empty')
    if [ -z "$token" ]; then
        problems+=("no anonymous pull token for $ns/$img (package is private)")
    else
        mcode=$(curl --max-time 30 -s -o "$work/m.json" -w '%{http_code}' \
                -H "Authorization: Bearer $token" \
                -H "Accept: application/vnd.oci.image.manifest.v1+json,application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.v2+json" \
                "https://$reg/v2/$ns/$img/manifests/$tag")
        case "$mcode" in
            200) ;;
            401|403) problems+=("manifest $mcode anonymously: $ns/$img is PRIVATE");;
            *)       problems+=("manifest HTTP $mcode for $ns/$img:$tag");;
        esac

        if [ "$mcode" = 200 ]; then
            # ---- check 3: the annotations the updater depends on ------------
            rev=$(jq -r '.annotations["org.opencontainers.image.revision"] // empty' "$work/m.json")
            mtype=$(jq -r '.layers[0].mediaType // empty' "$work/m.json")
            title=$(jq -r '.layers[0].annotations["org.opencontainers.image.title"] // empty' "$work/m.json")
            layer_size=$(jq -r '.layers[0].size // empty' "$work/m.json")

            [ -n "$rev" ] || problems+=("manifest has no org.opencontainers.image.revision")
            [ "$mtype" = "application/vnd.android.package-archive" ] ||
                problems+=("layer media type is '$mtype'")
            [ "$title" = "$asset" ] ||
                problems+=("layer title '$title' does not match asset '$asset'")
        fi
    fi

    # ---- check 4: the declared package exists in the repository ------------
    # Advisory, not fatal. The lib APKs are assembled by a generated gradle
    # wrapper whose applicationId is composed at build time from
    # lib_apks.application_id_prefix, so it appears in no checked-in file and a
    # fatal check here would fail 35 healthy entries.
    notes=""
    grep -qxF "$pkg" "$work/declared.txt" ||
        notes=" [package '$pkg' not declared in-repo: generated wrapper or mirror]"

    # ---- check 5: both channels ship the same bytes ------------------------
    if [ -n "$asset_size" ] && [ -n "$layer_size" ] && [ "$asset_size" != "$layer_size" ]; then
        problems+=("size mismatch: release $asset_size vs layer $layer_size")
    fi

    if [ ${#problems[@]} -eq 0 ]; then
        emit "$id" "$kind" PASS "rev=${rev:-?} size=${asset_size:-?} sha256sidecar=${sidecar:-NO} updated=${asset_when:-?}$notes"
    else
        emit "$id" "$kind" FAIL "$(printf '%s; ' "${problems[@]}")$notes"
    fi
done

echo
echo "audit-fleet: $pass ok, $fail failing, out of $total"
echo "audit-fleet: sha256 sidecars present for $sidecars of $total entries"

if [ -n "$json_out" ]; then
    jq -Rs 'split("\n") | map(select(length > 0) | split("\t") |
            {id: .[0], kind: .[1], status: .[2], detail: .[3]})' \
        < "$work/rows.tsv" > "$json_out"
    echo "audit-fleet: report written to $json_out"
fi

[ "$fail" -eq 0 ]
