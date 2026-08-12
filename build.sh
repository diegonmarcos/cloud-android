#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud-Android Orchestrator — pure dispatcher                     ║
# ║                                                                  ║
# ║ Zero logic here. All commands delegate to scripts in             ║
# ║ 1_cicd/src/scripts/cloud-android-*.sh                       ║
# ╚══════════════════════════════════════════════════════════════════╝
set -e

CLOUD_ANDROID_ROOT="$(cd "$(dirname "$0")" && pwd)"
export CLOUD_ANDROID_ROOT
SCRIPTS="$CLOUD_ANDROID_ROOT/1_cicd/src/scripts"

# Source shared library (config, helpers, deps check)
. "$SCRIPTS/cloud-android-ship-lib.sh"

# ── Parse global flags ─────────────────────────────────────────────
while [ $# -gt 0 ]; do
    case "$1" in
        -n|--dry-run) export DRY_RUN=1; shift ;;
        -v|--verbose) set -x; shift ;;
        -k|--key)     SOPS_AGE_KEY_FILE="$2"; export SOPS_AGE_KEY_FILE; shift 2 ;;
        -h|--help)    command="help"; shift ;;
        -*)           log_error "Unknown option: $1"; exit 1 ;;
        *)            break ;;
    esac
done

command="${1:-}"; shift 2>/dev/null || true

# Check deps at startup (skip for 'deps' command)
[ "$command" != "deps" ] && check_deps

# ── Dispatch ───────────────────────────────────────────────────────
case "$command" in
    # ── PIPELINE ──
    ship)              sh "$SCRIPTS/cloud-android-ship-orchestrate-ship.sh" "$@" ;;
    ship-device)       sh "$SCRIPTS/cloud-android-ship-orchestrate-device.sh" "$@" ;;
    ship-all)          sh "$SCRIPTS/cloud-android-ship-orchestrate-ship.sh" --all ;;
    # ── BUILD ──
    build)             sh "$SCRIPTS/cloud-android-ship-orchestrate-build.sh" "$@" ;;
    build-apk)         sh "$SCRIPTS/cloud-android-build-apk-engine.sh" "$@" ;;
    # ── CONFIG ──
    config)            sh "$SCRIPTS/cloud-android-ship-repo-config-gen.sh" ;;
    workflow)          sh "$SCRIPTS/cloud-android-ship-repo-workflow-gen.sh" ;;
    secrets)           sh "$SCRIPTS/cloud-android-ship-repo-secrets.sh" "$@" ;;
    # ── CICD (inside android-builder container) ──
    cicd-ship)         sh "$SCRIPTS/cloud-android-ship-ci-builder-dispatch.sh" ship "$@" ;;
    cicd-health)       sh "$SCRIPTS/cloud-android-health-full.sh" "$@" ;;
    # ── OPS ──
    adb)               sh "$SCRIPTS/cloud-android-ship-repo-adb.sh" "$@" ;;
    status)            sh "$SCRIPTS/cloud-android-ship-repo-status.sh" "$@" ;;
    health)            sh "$SCRIPTS/cloud-android-health-full.sh" "$@" ;;
    clean)             sh "$SCRIPTS/cloud-android-ship-repo-clean.sh" ;;
    deps)              sh "$SCRIPTS/cloud-android-ship-repo-deps.sh" "$@" ;;
    ""|help)
        cat <<'EOF'
Cloud-Android Orchestrator — repo-level CLI for cloud-android/ infrastructure

USAGE:  ./build.sh <command> [args]

PIPELINE:
    ship [solution|device]     Ship Android artifact (build + secrets + deploy)
    ship-device [device]       Ship all solutions to one device
    ship-all                   Ship everything

BUILD:
    build [solution]           Build single solution → dist/
    build-apk <solution>       Build APK (Gradle/Nix → dist/)

CONFIG:
    config                     Generate cloud-data configs from sources
    workflow                   Generate GHA workflows from templates
    secrets [sol] [action]     Manage sops secrets (show|edit|encrypt|decrypt)

CICD (inside android-builder container):
    cicd-ship [device|all]     Orchestrate Android ship
    cicd-health                Orchestrate health checks

OPS:
    adb <device>               ADB shell into device
    status <device>            Package status on device
    health                     Run health checks across devices
    clean                      Remove all dist/
    deps                       Install dependencies from config.json

OPTIONS:
    -n, --dry-run              Show what would be done
    -v, --verbose              Enable verbose output
    -k, --key <path>           Override SOPS age key path

EXAMPLES:
    ./build.sh ship termux-env          Build + deploy termux-env solution
    ./build.sh build-apk my-app         Build an APK to dist/
    ./build.sh config                   Regenerate config from sources
    ./build.sh adb waydroid             ADB shell into waydroid device
    ./build.sh secrets my-app           List secret keys for solution
EOF
        exit 0
        ;;
    *)  log_error "Unknown: $command"; exit 1 ;;
esac
