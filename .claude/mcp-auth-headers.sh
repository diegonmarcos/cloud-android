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

# Source 2: the vault checkout. AUTHELIA_OIDC_TOKENS_DIR is already exported by
# unix's Claude settings (da_my-ai/src/data/claude/settings.base.json). The
# fallback path is the standard ~/git layout.
TOKENS_DIR="${AUTHELIA_OIDC_TOKENS_DIR:-$HOME/git/vault/A0_keys/providers/authelia/signed-bearer_jwt/tokens}"
TOKEN_FILE="$TOKENS_DIR/claude-admin.json"

[ -r "$TOKEN_FILE" ] || { printf '%s\n' '{}'; exit 0; }

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
