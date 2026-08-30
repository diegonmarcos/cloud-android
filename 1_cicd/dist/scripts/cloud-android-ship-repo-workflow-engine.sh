# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-ship-repo-workflow-engine.sh ───
#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-ship-repo-workflow-engine                          ║
# ║                                                                  ║
# ║ Compiles each config tier's src/ → that tier's dist/ and deploys ║
# ║ generated artifacts to .github/, .gitmodules, gitconfig, hooks.   ║
# ║                                                                  ║
# ║ Invoked as: ./9_others/build.sh (via symlink)                    ║
# ╚══════════════════════════════════════════════════════════════════╝
set -eu

# Repo root by upward search, not a fixed ../ count. This script has moved
# twice now (1_configs/src/scripts → 1_configs/src/gha/scripts →
# 1_cicd/src/scripts) and each time a literal ../../.. would have resolved one
# level off, silently, with every path built from it landing in the wrong place.
CLOUD_ANDROID_ROOT="${CLOUD_ANDROID_ROOT:-$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")}"

# Config tiers. Each owns its own dist/, so a source and its compiled form sit
# together instead of every artifact landing in one flat 1_configs/dist.
GIT_SRC="$CLOUD_ANDROID_ROOT/0_git/src";     GIT_DIST="$CLOUD_ANDROID_ROOT/0_git/dist"
APPS_SRC="$CLOUD_ANDROID_ROOT/0_apps/src";   APPS_DIST="$CLOUD_ANDROID_ROOT/0_apps/dist"
CICD_SRC="$CLOUD_ANDROID_ROOT/1_cicd/src";   CICD_DIST="$CLOUD_ANDROID_ROOT/1_cicd/dist"
LIB_SRC="$CLOUD_ANDROID_ROOT/9_others/src"

. "$CICD_SRC/scripts/cloud-android-ship-lib.sh"

mkdir -p "$CICD_DIST/scripts" "$CICD_DIST/cicd" "$CICD_DIST/actions" \
         "$GIT_DIST/hooks" "$GIT_DIST/modules"

# ── scripts: copy source → dist with read-only header ──────────────
log_step "dist/scripts"
for f in "$CICD_SRC/scripts/"*.sh; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    {
        echo "# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/$base ───"
        cat "$f"
    } > "$CICD_DIST/scripts/$base"
    chmod +x "$CICD_DIST/scripts/$base"
done

# ── cicd: verify trigger paths against the data that declares them ──
#
# on:push:paths was hand-maintained beside build.json, so it drifted silently
# and in the worst way: a workflow watching nothing real never runs and nobody
# gets an error. Three had already drifted when this check was added -- two
# watching their own generated copy (self-triggering on unrelated commits),
# one watching a sibling repo a path filter can never see.
log_step "verify workflow triggers"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYEOF' || exit 1
import glob, json, os, re, sys

root = sys.argv[1]
apps = {d for d in os.listdir(root)
        if os.path.exists(os.path.join(root, d, "build.json"))}
bad = []

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/*.yml"))):
    name = os.path.basename(wf)
    lines = open(wf).read().split("\n")
    start = next((i for i, l in enumerate(lines) if l.strip() == "paths:"), None)
    if start is None:
        continue

    entries = []
    for l in lines[start + 1:]:
        m = re.match(r'^      - "([^"]+)"', l)
        if m:
            entries.append(m.group(1))
        elif l.strip() and not l.strip().startswith("#") and not l.startswith("      "):
            break

    for e in entries:
        if e.startswith(".github/workflows/"):
            bad.append(f"{name}: watches its own generated copy: {e}")
        base = e.split("*")[0].rstrip("/")
        if base and not os.path.exists(os.path.join(root, base)):
            bad.append(f"{name}: watches a path not in this repo: {e}")

    slug = re.sub(r"^(ship|test)-|\.yml$", "", name)
    app = next((a for a in apps if a.split("_", 1)[-1] == slug), None)
    if not app:
        continue
    if f"{app}/**" not in entries:
        bad.append(f"{name}: does not watch its own app directory {app}/**")
    modules = json.load(open(os.path.join(root, app, "build.json"))).get("modules", {})
    if isinstance(modules, dict):
        for mod in modules.values():
            if not isinstance(mod, dict) or not mod.get("dir"):
                continue
            shared = os.path.normpath(os.path.join(app, mod["dir"]))
            if shared.startswith(app + os.sep):
                continue
            if not any(e.startswith(shared) for e in entries):
                bad.append(f"{name}: build.json uses {shared} but never rebuilds on it")

for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PYEOF

# ── cicd: copy YAMLs → dist/cicd then into .github/workflows ───────
log_step "dist/cicd → .github/workflows"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/workflows"
for f in "$CICD_SRC/cicd/"*.yml; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$CICD_DIST/cicd/$base"
    cp "$f" "$CLOUD_ANDROID_ROOT/.github/workflows/$base"
done

# scripts folder for GHA (symlink inside .github/workflows)
[ -e "$CLOUD_ANDROID_ROOT/.github/workflows/scripts" ] || \
    ln -sf ../../1_cicd/dist/scripts "$CLOUD_ANDROID_ROOT/.github/workflows/scripts"

# ── actions: composite actions ─────────────────────────────────────
log_step "dist/actions → .github/actions"
mkdir -p "$CLOUD_ANDROID_ROOT/.github/actions"
if [ -d "$CICD_SRC/actions" ]; then
    cp -r "$CICD_SRC/actions/"* "$CICD_DIST/actions/" 2>/dev/null || true
    cp -r "$CICD_SRC/actions/"* "$CLOUD_ANDROID_ROOT/.github/actions/" 2>/dev/null || true
fi

# ── hooks: copied + chmodded ───────────────────────────────────────
log_step "dist/hooks"
for f in "$GIT_SRC/hooks/"*; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    cp "$f" "$GIT_DIST/hooks/$base"
    chmod +x "$GIT_DIST/hooks/$base"
done

# ── root dotfiles: gitmodules / gitignore / gitattributes / LICENSE ─
# git reads these three out of the WORKING TREE, so each is written to the git
# tier's dist and then copied to the repo root. gitconfig below is the one
# exception — it is included from .git/config instead, and a .gitconfig at a
# repo root would mean nothing to git at all.
log_step "0_git/dist & repo-root dotfiles"
for _f in gitmodules gitignore gitattributes; do
    [ -f "$GIT_SRC/$_f" ] || continue
    cp "$GIT_SRC/$_f" "$GIT_DIST/.$_f"
    cp "$GIT_SRC/$_f" "$CLOUD_ANDROID_ROOT/.$_f"
done
# gitmodules also keeps its legacy dist/modules/ copy: the ship workflows read
# it from there.
[ -f "$GIT_SRC/gitmodules" ] && cp "$GIT_SRC/gitmodules" "$GIT_DIST/modules/gitmodules"
# LICENSE is copied VERBATIM — GitHub's licence detector and SPDX scanners
# match on the text, and a generated-file banner breaks them.
if [ -f "$GIT_SRC/LICENSE" ]; then
    cp "$GIT_SRC/LICENSE" "$GIT_DIST/LICENSE"
    cp "$GIT_SRC/LICENSE" "$CLOUD_ANDROID_ROOT/LICENSE"
fi
unset _f
if [ -f "$GIT_SRC/gitconfig" ]; then
    cp "$GIT_SRC/gitconfig" "$GIT_DIST/gitconfig"
    # Deploy via .git/config [include] + reconcile shadow keys.
    _gc_section=""
    while IFS= read -r line; do
        case "$line" in
            \[*\])
                _gc_section=$(printf '%s' "$line" | sed 's/^\[\([^]]*\)\]$/\1/' | tr '[:upper:]' '[:lower:]')
                ;;
            *=*)
                [ -z "$_gc_section" ] && continue
                _gc_key=$(printf '%s' "$line" | sed -n 's/^[[:space:]]*\([a-zA-Z][a-zA-Z0-9]*\)[[:space:]]*=.*/\1/p' | tr '[:upper:]' '[:lower:]')
                [ -n "$_gc_key" ] && git -C "$CLOUD_ANDROID_ROOT" config --local --unset "${_gc_section}.${_gc_key}" 2>/dev/null || true
                ;;
        esac
    done < "$GIT_DIST/gitconfig"
    unset _gc_section _gc_key
    git -C "$CLOUD_ANDROID_ROOT" config --local include.path ../0_git/dist/gitconfig 2>/dev/null || true
fi

log_info "Workflow build complete. Tier dists under 0_git/, 0_apps/, 1_cicd/"

# ── dotfiles ────────────────────────────────────────────────────────────────
# src/apps/<tool>/ → dist/dotfiles/<tool>/ → <repo>/<target>/, plus
# root_targets for single files at the repo root (.mcp.json). Same module every
# repo under cloud carries.
if [ -d "$APPS_SRC" ]; then
    sh "$LIB_SRC/deploy-dotfiles.sh" "$APPS_SRC" "$APPS_DIST/dotfiles" "$CLOUD_ANDROID_ROOT"
fi
