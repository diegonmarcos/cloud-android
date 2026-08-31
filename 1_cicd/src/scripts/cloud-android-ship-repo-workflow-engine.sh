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

# ── cicd: engine ↔ vendored build.sh parity ────────────────────────
#
# Every app ships a VENDORED COPY of its engine at <app>/build.sh, and the ship
# workflow runs THAT file — 1_cicd/src/scripts/*-engine.sh is only its source.
# Editing the engine alone changes nothing at ship time, which is exactly how
# the ABI gate and the GHCR auto-delete gate came to exist in the engine and
# not in the file GitHub executed. That drift then produced two wrong outage
# diagnoses in one day, because the code being read was not the code running.
#
# So the copy relationship is now DECLARED (build.json::vendored_engine) and
# ENFORCED here: byte-identical or the generator goes red. It is also why the
# publish gate hashes the vendored build.sh rather than the engine — a
# parity-fixing sync lands in the app tree and correctly republishes that app.
log_step "verify engine ↔ vendored build.sh parity"
_parity_bad=0
for _bj in "$CLOUD_ANDROID_ROOT"/*/build.json; do
    [ -e "$_bj" ] || continue
    _app="$(basename "$(dirname "$_bj")")"
    _eng="$(jq -r '.vendored_engine // empty' "$_bj")"
    [ -n "$_eng" ] || continue
    _src="$CICD_SRC/scripts/$_eng"
    if [ ! -f "$_src" ]; then
        log_error "$_app/build.json declares vendored_engine=$_eng — no such engine in 1_cicd/src/scripts/"
        _parity_bad=1; continue
    fi
    if ! cmp -s "$_src" "$CLOUD_ANDROID_ROOT/$_app/build.sh"; then
        log_error "$_app/build.sh has DRIFTED from its engine $_eng — the vendored copy is what CI runs"
        log_error "  resync with: cp 1_cicd/src/scripts/$_eng $_app/build.sh"
        _parity_bad=1
    fi
done
unset _bj _app _eng _src
[ "$_parity_bad" = "0" ] || exit 1
unset _parity_bad

# ── cicd: derive trigger paths from the data that declares them ─────
#
# on:push:paths was hand-maintained beside build.json, so it drifted silently
# and in the worst way: a workflow watching nothing real never runs and nobody
# gets an error. Three had already drifted when a check was added here -- two
# watching their own generated copy (self-triggering on unrelated commits),
# one watching a sibling repo a path filter can never see.
#
# Checking was not enough: the check told you a module-map dir was unwatched
# and then you hand-added it, which is the drift it was meant to end. Every
# dir the module map declares is now ADDED here automatically, and dead
# entries (paths no longer in the repo, and the workflow's own generated copy,
# which self-triggers on every unrelated commit that runs this generator) are
# dropped.
#
# It UNIONS rather than replaces, deliberately. A module map is a floor, not a
# ceiling: the fork apps have no `modules` block at all and re-point
# projectDir from settings.gradle.kts, and ab_cloud-libs-shared/lib-apks
# legitimately builds EVERY shared module — an input set no map expresses. A
# derivation that replaced the block silently deleted five real triggers from
# media-center on its first run, which is the exact failure mode (a workflow
# watching nothing real never runs and reports no error) this section exists
# to prevent.
#
# The publish gate then hashes THIS list rather than a second, parallel one —
# see cloud-android-source-identity.sh. Anything that can start a build is
# therefore also something the gate weighs, so the gate can never skip a
# rebuild that a real change triggered.
log_step "sync workflow triggers"
python3 - "$CLOUD_ANDROID_ROOT" <<'PYEOF' || exit 1
import glob, json, os, re, sys

root = sys.argv[1]
apps = {d for d in os.listdir(root)
        if os.path.exists(os.path.join(root, d, "build.json"))}
bad = []

for wf in sorted(glob.glob(os.path.join(root, "1_cicd/src/cicd/*.yml"))):
    name = os.path.basename(wf)
    text = open(wf).read()
    lines = text.split("\n")
    start = next((i for i, l in enumerate(lines) if l.strip() == "paths:"), None)
    if start is None:
        continue
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].strip() and not lines[i].startswith("      ")), len(lines))

    entries = [m.group(1) for m in
               (re.match(r'^      - "([^"]+)"', l) for l in lines[start + 1:end]) if m]

    # The app is the workflow's declared WORK_DIR, not its filename. Two lib
    # aggregators (ship-cloud-libs, ship-cloud-keyboard-libs) build a
    # directory under ab_cloud-libs-shared/ whose name does not match their
    # slug, and a slug match sent the derivation at the wrong build.json.
    m = re.search(r'^  WORK_DIR:\s*(\S+)\s*$', text, re.M)
    app = m.group(1) if m and os.path.exists(
        os.path.join(root, m.group(1), "build.json")) else None
    if not app:
        slug = re.sub(r"^(ship|test)-|\.yml$", "", name)
        app = next((a for a in apps if a.split("_", 1)[-1] == slug), None)
    if not app:
        # Not an app workflow (ship.yml, health.yml, ...) -- only validate.
        for e in entries:
            base = e.split("*")[0].rstrip("/")
            if base and not os.path.exists(os.path.join(root, base)):
                bad.append(f"{name}: watches a path not in this repo: {e}")
        continue

    derived = [f"{app}/**"]
    modules = json.load(open(os.path.join(root, app, "build.json"))).get("modules", {})
    if isinstance(modules, dict):
        for mod in modules.values():
            if not isinstance(mod, dict) or not mod.get("dir"):
                continue
            shared = os.path.normpath(os.path.join(app, mod["dir"]))
            if shared.startswith(app + os.sep):
                continue
            derived.append(shared + "/**")
    derived.append(f"1_cicd/src/cicd/{name}")

    # Hand-written entries survive unless they are dead (a path a filter can
    # never match) or the workflow's own generated copy.
    kept = [e for e in entries
            if not e.startswith(".github/workflows/")
            and os.path.exists(os.path.join(root, e.split("*")[0].rstrip("/") or "."))]
    for e in entries:
        if e not in kept:
            print(f"  dropped {name}: {e}")

    final = sorted(set(derived) | set(kept))
    block = ["    paths:",
             "      # MANAGED by cloud-android-ship-repo-workflow-engine.sh: every dir",
             f"      # {app}/build.json::modules declares is added automatically, dead",
             "      # entries are dropped, and cloud-android-source-identity.sh hashes",
             "      # exactly this list — so nothing can trigger a build the publish",
             "      # gate does not weigh. Extra entries no module map can express",
             "      # (settings.gradle.kts references, scan roots) are kept: add them",
             "      # here and they stay.",
             ] + [f'      - "{e}"' for e in final]
    new = "\n".join(lines[:start] + block + lines[end:])
    if new != text:
        open(wf, "w").write(new)
        print(f"  synced {name}: {len(entries)} → {len(final)} trigger paths")

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
