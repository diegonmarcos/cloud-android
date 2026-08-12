#!/bin/sh
# Emit MCP connection headers as JSON, for .mcp.json `headersHelper`.
#
# c3-infra-mcp is the one MCP endpoint reachable from off the WireGuard mesh,
# and Caddy gates it on an Authelia-issued bearer token (@wg → @bearer → 403).
# Claude Code therefore needs to present that token, and .mcp.json is committed
# verbatim into every repo under cloud — so the token cannot live in it.
#
# This reads the token at connection time instead. Nothing secret is committed;
# the only thing in .mcp.json is the path to this script.
#
# Claude Code re-runs the helper on a 401/403 and retries once, so rotating the
# token in vault is picked up without restarting the session.
#
# Output contract: a JSON object of string key/value pairs on stdout. `{}` means
# "no headers" — never an error, because a machine without the vault checkout
# should still load the server and get an honest 403 from the gate rather than a
# broken config.
set -eu

# Source 1: the token straight from the environment.
#
# For hosts with no vault checkout — a cloud container, CI, Claude Code on the
# web. Those get a fresh filesystem every session, so the file below never
# exists there and the endpoint stays unreachable no matter what is committed.
# Set AUTHELIA_BEARER_TOKEN in the environment instead and this works anywhere.
#
# Prefer a NARROW client for that: the environment is readable by every session
# in it, and claude-admin is full-admin for 10 years. monitoring-read (or a
# per-purpose client) is the better thing to expose that way.
if [ -n "${AUTHELIA_BEARER_TOKEN:-}" ]; then
    node -e 'process.stdout.write(JSON.stringify({Authorization:"Bearer "+process.argv[1]}))' "$AUTHELIA_BEARER_TOKEN" 2>/dev/null \
        || printf '%s\n' '{}'
    exit 0
fi

# Source 2+: a vault checkout on disk. Tried in order; first readable wins.
#
# This script lives at <repo>/.claude/mcp-auth-headers.sh, so the repo root is
# two levels up from $0 — derived rather than assumed, because the helper is
# invoked with an absolute path from .mcp.json and the cwd is not guaranteed.
SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(dirname -- "$SELF_DIR")
REL="A0_keys/providers/authelia/signed-bearer_jwt/tokens/claude-admin.json"

# The order matters:
#   a) $AUTHELIA_OIDC_TOKENS_DIR — explicit, wins over any guess. Exported by
#      unix's settings.base.json.
#   b) <repo>/IV_vault — vault is a SUBMODULE of cloud (submodule.vault.path =
#      IV_vault). In any cloud checkout with it initialised, that is where the
#      token actually is. Missing this is why cloning vault "into the repo" left
#      the helper emitting {} — it only ever looked at ~/git/vault.
#   c) ~/git/vault — the standalone layout, when the repos are siblings.
for _candidate in \
    "${AUTHELIA_OIDC_TOKENS_DIR:+$AUTHELIA_OIDC_TOKENS_DIR/claude-admin.json}" \
    "$REPO_ROOT/IV_vault/$REL" \
    "$HOME/git/vault/$REL"
do
    [ -n "$_candidate" ] || continue
    if [ -r "$_candidate" ]; then TOKEN_FILE="$_candidate"; break; fi
done

if [ -z "${TOKEN_FILE:-}" ] || [ ! -r "$TOKEN_FILE" ]; then
    # Say WHY on stderr. stdout must stay pure JSON (Claude Code parses it), but
    # stderr is free, and a bare {} is near-undiagnosable: the endpoint just
    # 403s and the server shows as "requires authentication", which reads like
    # an OAuth problem and is not one. Two separate sessions had to reason this
    # out from first principles before the reason was written down anywhere.
    {
        echo "mcp-auth-headers: no token found — c3-infra-mcp will get 403."
        echo "  tried \$AUTHELIA_BEARER_TOKEN         : ${AUTHELIA_BEARER_TOKEN:+set}${AUTHELIA_BEARER_TOKEN:-unset}"
        echo "  tried \$AUTHELIA_OIDC_TOKENS_DIR      : ${AUTHELIA_OIDC_TOKENS_DIR:-unset}"
        echo "  tried \$REPO_ROOT/IV_vault             : $REPO_ROOT/IV_vault/$REL"
        echo "  tried ~/git/vault                     : $HOME/git/vault/$REL"
        echo "  On a host with no vault checkout (cloud container, CI, web),"
        echo "  set AUTHELIA_BEARER_TOKEN in the environment. Prefer a narrow"
        echo "  client (monitoring-read) over claude-admin — an environment"
        echo "  variable is readable by every session in that environment."
    } >&2
    printf '%s\n' '{}'
    exit 0
fi

# node, not jq: node is already required by every build.sh in the fleet, jq is
# not guaranteed present. Prints ONLY the header object — the token never
# reaches a log, and a malformed/expired file degrades to {} rather than
# emitting a broken header.
node -e '
const fs = require("fs");
try {
  const t = JSON.parse(fs.readFileSync(process.argv[1], "utf8")).access_token;
  process.stdout.write(t ? JSON.stringify({ Authorization: "Bearer " + t }) : "{}");
} catch { process.stdout.write("{}"); }
' "$TOKEN_FILE" 2>/dev/null || printf '%s\n' '{}'
