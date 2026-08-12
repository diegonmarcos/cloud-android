# cloud-android-ship-lib.sh — Shared library for cloud-android-ship scripts
# Sourced, not executed directly. No shebang.

CLOUD_ANDROID_ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/../../.." && pwd)}"

# =============================================================================
# Configuration
# =============================================================================

SOLUTIONS_DIR="$CLOUD_ANDROID_ROOT/a_solutions"
INFRA_DIR="$CLOUD_ANDROID_ROOT/b_infra"
DEVICES_DIR="$CLOUD_ANDROID_ROOT/c_devices"
CONFIG_FILE="$CLOUD_ANDROID_ROOT/config.json"

export NODE_PATH="${NODE_PATH:-$HOME/.node_modules/node_modules}"
export BUILDSH_GUARDRAIL=1

# =============================================================================
# Logging
# =============================================================================

_log()       { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
log_info()   { _log "INFO  $*"; }
log_warn()   { _log "WARN  $*" >&2; }
log_error()  { _log "ERROR $*" >&2; }
log_step()   { printf '\n━━━ %s ━━━\n' "$*"; }

# =============================================================================
# Dependency Engine — reads from config.json .deps section
# =============================================================================

DEPS_AUTO_YES=$(jq -r '.deps.install.auto_yes // false' "$CONFIG_FILE" 2>/dev/null || echo "false")
[ ! -t 0 ] && DEPS_AUTO_YES=true
[ -n "${CI:-}" ] && DEPS_AUTO_YES=true
[ -n "${GITHUB_ACTIONS:-}" ] && DEPS_AUTO_YES=true

detect_pm() {
    if command -v nix >/dev/null 2>&1; then
        echo "nix"
    elif command -v apt-get >/dev/null 2>&1; then
        echo "apt"
    else
        echo "none"
    fi
}

deps_binaries()      { jq -r '.deps.system | keys[]' "$CONFIG_FILE" | tr '\n' ' '; }
deps_pkg_name()      { jq -r ".deps.system[\"$1\"][\"$2\"] // empty" "$CONFIG_FILE"; }

confirm() {
    [ "$DEPS_AUTO_YES" = "true" ] && return 0
    printf "  %s [y/N] " "$1"
    read -r answer
    [ "$answer" = "y" ] || [ "$answer" = "Y" ]
}

check_deps() {
    missing=""
    for bin in $(deps_binaries); do
        command -v "$bin" >/dev/null 2>&1 || missing="$missing $bin"
    done
    if [ -n "$missing" ]; then
        log_warn "Missing dependencies:$missing"
        log_warn "Run: ./build.sh deps"
        return 0  # non-fatal; let individual commands fail if they need a specific tool
    fi
}

# =============================================================================
# Solution / Device resolution
# =============================================================================

# Resolve a solution directory: argument can be the short name or prefixed dir
resolve_solution_dir() {
    name="$1"
    [ -z "$name" ] && return 1
    # Exact match first
    [ -d "$SOLUTIONS_DIR/$name" ] && { echo "$SOLUTIONS_DIR/$name"; return 0; }
    # Match by suffix (e.g. "hooking-core" → "aa-eng_hooking-core")
    match=$(find "$SOLUTIONS_DIR" -maxdepth 1 -type d -name "*_${name}" | head -1)
    [ -n "$match" ] && { echo "$match"; return 0; }
    return 1
}

list_solutions() {
    find "$SOLUTIONS_DIR" -maxdepth 1 -type d -name '[a-z]*_*' | sort
}

list_devices() {
    jq -r '.devices | keys[]' "$CONFIG_FILE" 2>/dev/null
}

device_field() {
    jq -r ".devices[\"$1\"][\"$2\"] // empty" "$CONFIG_FILE"
}

# =============================================================================
# Build info
# =============================================================================

solution_field() {
    dir="$1"; field="$2"
    jq -r ".$field // empty" "$dir/build.json" 2>/dev/null
}
