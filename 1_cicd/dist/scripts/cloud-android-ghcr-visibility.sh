# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ghcr-visibility.sh ───
#!/usr/bin/env sh
# Say whether a GHCR package can actually be pulled by a stranger.
#
# WHY THIS IS NOT AN API CALL
# "Is it public" has to be asked from OUTSIDE. With a token that can read the
# package, a private image looks exactly like a public one — the authenticated
# answer is the same either way, which is why this went unnoticed. GHCR issues
# an anonymous pull token for public packages and refuses for private ones, so
# the token request IS the visibility test: no scope, no PAT, and it behaves
# the same for a user or an org, where PATCH /user/packages returns 404 anyway.
#
# WHY IT MATTERS AT ALL
# A package's visibility is decided ONCE, when it is created, and silently
# inherited by every push after. Created by a PAT it belongs to the account;
# created by a workflow's GITHUB_TOKEN it belongs to the repository and takes
# the repository's visibility. A public repo publishing a package nobody can
# pull is invisible from the inside — green build, right digest — and only
# wrong for the person trying to install it.
#
#   usage: cloud-android-ghcr-visibility.sh <namespace> <image> [tag]
#   exit 0 public · 1 not publicly pullable · 2 usage
set -eu

ns="${1:-}"; img="${2:-}"; tag="${3:-latest}"
[ -n "$ns" ] && [ -n "$img" ] || { echo "usage: $0 <namespace> <image> [tag]" >&2; exit 2; }

tok="$(curl -fsS "https://ghcr.io/token?scope=repository:${ns}/${img}:pull" 2>/dev/null \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p' || true)"

code=000
if [ -n "$tok" ]; then
    code="$(curl -s -o /dev/null -w '%{http_code}' \
              -H "Authorization: Bearer $tok" \
              -H 'Accept: application/vnd.oci.image.index.v1+json' \
              -H 'Accept: application/vnd.oci.image.manifest.v1+json' \
              "https://ghcr.io/v2/${ns}/${img}/manifests/${tag}" || true)"
fi

if [ "$code" = "200" ]; then
    echo "ghcr.io/${ns}/${img}:${tag} is PUBLIC"
    exit 0
fi

# A warning rather than a hard stop for the APK path: the artifact is also on
# the GH release, which is public by construction, so a private mirror degrades
# the install route without breaking it. What it must not do is stay silent.
echo "ghcr.io/${ns}/${img}:${tag} is NOT publicly pullable (HTTP ${code})" >&2
echo "Visibility is set when the package is CREATED and cannot be patched." >&2
echo "To fix once: gh api -X DELETE user/packages/container/${img}" >&2
echo "then re-run — the next GITHUB_TOKEN push recreates it for this repository." >&2
exit 1
