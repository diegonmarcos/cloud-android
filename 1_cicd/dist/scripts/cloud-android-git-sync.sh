# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-git-sync.sh ───
#!/bin/sh
# cloud-android-git-sync — safer pull/push wrapper (auto-stash + rebase + submodules)
set -eu

mode="${1:-remote}"
case "$mode" in
    remote) strat="theirs" ;;
    local)  strat="ours" ;;
    *) echo "usage: git sync {remote|local}" >&2; exit 1 ;;
esac

root=$(git rev-parse --show-toplevel)
cd "$root"

stash_name="git-sync-$(date +%s)"
dirty=""
if ! git diff --quiet || ! git diff --cached --quiet; then
    dirty=1
    git stash push -u -m "$stash_name"
fi

git fetch --all --prune
branch=$(git rev-parse --abbrev-ref HEAD)
git rebase -X "$strat" "origin/$branch" || {
    echo "rebase failed — resolve conflicts and run: git rebase --continue" >&2
    exit 1
}

git submodule update --init --recursive --rebase || true

if [ -n "$dirty" ]; then
    git stash pop || {
        echo "stash pop had conflicts — inspect with: git stash list" >&2
    }
fi

echo "sync complete ($mode strategy)"
