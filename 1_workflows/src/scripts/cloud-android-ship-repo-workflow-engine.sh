#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-ship-repo-workflow-engine                          ║
# ║                                                                  ║
# ║ Compiles 1_workflows/src/ → 1_workflows/dist/ and deploys         ║
# ║ generated artifacts to .github/, .gitmodules, .gitconfig, hooks.  ║
# ║                                                                  ║
# ║ Invoked as: ./1_workflows/build.sh (via symlink)                  ║
# ╚══════════════════════════════════════════════════════════════════╝
set -eu

CLOUD_ANDROID_ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}"
SRC="$CLOUD_ANDROID_ROOT/1_workflows/src"
DIST="$CLOUD_ANDROID_ROOT/1_workflows/dist"

. "$CLOUD_ANDROID_ROOT/1_workflows/src/scripts/cloud-android-ship-lib.sh"

mkdir -p "$DIST/scripts" "$DIST/cicd" "$DIST/actions" "$DIST/hooks" "$DIST/modules"

# ── scripts: copy source → dist with read-only header ──────────────
log_step "dist/scripts"
for f in "$SRC/scripts/"*.sh; do
    base=$(basename "$f")
    {
        echo "# ─── GENERATED: do not edit — edit 1_workflows/src/scripts/$base ───"
        cat "$f"
    } > "$DIST/scripts/$base"
    chmod +x "$DIST/scripts/$base"
done

# ── cicd: copy YAMLs → dist/cicd then into .github/workflows ───────
log_step "dist/cicd → .github/workflows"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/workflows"
for f in "$SRC/cicd/"*.yml; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$DIST/cicd/$base"
    cp "$f" "$CLOUD_ANDROID_ROOT/.github/workflows/$base"
done

# scripts folder for GHA (symlink inside .github/workflows)
[ -e "$CLOUD_ANDROID_ROOT/.github/workflows/scripts" ] || \
    ln -sf ../../1_workflows/dist/scripts "$CLOUD_ANDROID_ROOT/.github/workflows/scripts"

# ── actions: composite actions ─────────────────────────────────────
log_step "dist/actions → .github/actions"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/actions"
if [ -d "$SRC/actions" ]; then
    cp -r "$SRC/actions/"* "$DIST/actions/" 2>/dev/null || true
    cp -r "$SRC/actions/"* "$CLOUD_ANDROID_ROOT/.github/actions/" 2>/dev/null || true
fi

# ── hooks: copied + chmodded ───────────────────────────────────────
log_step "dist/hooks"
for f in "$SRC/hooks/"*; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$DIST/hooks/$base"
    chmod +x "$DIST/hooks/$base"
done

# ── gitmodules / gitconfig ─────────────────────────────────────────
log_step "dist/modules & .gitmodules"
if [ -f "$SRC/modules/gitmodules" ]; then
    cp "$SRC/modules/gitmodules" "$DIST/modules/gitmodules"
    cp "$SRC/modules/gitmodules" "$CLOUD_ANDROID_ROOT/.gitmodules"
fi
if [ -f "$SRC/gitconfig" ]; then
    cp "$SRC/gitconfig" "$DIST/gitconfig"
    cp "$SRC/gitconfig" "$CLOUD_ANDROID_ROOT/.gitconfig"
fi

log_info "Workflow build complete. Dist at $DIST"
