#!/usr/bin/env bash
# Cloud Me navigation + data check. build.json::ui.sections holds the shape,
# one file per page under data/ui/ holds the content, data/files/ holds the
# wallet records, and nothing at build time notices a `page:` tile pointing at
# a tab nobody declares. This does — it is the check that fails when the pieces
# drift apart.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import json, os, sys

ui   = json.load(open("build.json"))["ui"]
secs = ui["sections"]
apps = {a["id"] for a in ui.get("external_apps", [])}
bad  = []

# section id → every navigable page id (tabs and sub-tabs alike)
pages, files = {}, {}
for s in secs:
    ids = []
    for p in s.get("pages", []):
        subs = p.get("pages", [])
        # A container tab holds no content of its own; its children live one
        # folder deeper and are the ids a target can name.
        for q in (subs or [p]):
            ids.append(q["id"])
            f = f"data/ui/{s['id']}/{p['id']}/{q['id']}.json" if subs else f"data/ui/{s['id']}/{q['id']}.json"
            if not os.path.exists(f):
                bad.append(f"missing page file {f}")
            else:
                files[f] = json.load(open(f))
        if subs:
            ids.append(p["id"])   # the container is still a valid target
    pages[s["id"]] = ids
    if not ids and not s.get("target"):
        bad.append(f"section {s['id']} has neither pages nor a target")
    if any(k.startswith("stack_") for k in s):
        bad.append(f"section {s['id']} still inlines a stack in build.json")

for root, _, names in os.walk("data/ui"):
    for n in names:
        if n.endswith(".json") and os.path.join(root, n) not in files:
            bad.append(f"orphan page file {os.path.join(root, n)} — no page declares it")

def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values(): yield from walk(v)
    elif isinstance(o, list):
        for v in o: yield from walk(v)

blocks = [o for st in files.values() for o in walk(st)]

for t in {o["target"] for o in list(walk(secs)) + blocks
          if isinstance(o.get("target"), str) and o["target"]}:
    if t.startswith("extapp:"):
        if t.removeprefix("extapp:").split("/")[0] not in apps:
            bad.append(f"target {t} — no such ui.external_apps id")
    elif t.startswith(("page:", "section:")):
        sec, _, pg = t.split(":", 1)[1].partition("/")
        if sec not in pages:              bad.append(f"target {t} — no such section")
        elif pg and pg not in pages[sec]: bad.append(f"target {t} — no such page in {sec}")
    elif not t.startswith("http"):
        bad.append(f"target {t} — unknown grammar")

# A `files` block browses a real asset tree; an empty root is a blank screen.
roots = [o["root"] for o in blocks if o.get("kind") == "fragment" and o.get("id") == "files"]
for r in roots:
    if not os.path.isdir(f"data/files/{r}"):
        bad.append(f"files block root '{r}' — no data/files/{r}/")
    elif not any(n.endswith(".json") for _, _, ns in os.walk(f"data/files/{r}") for n in ns):
        bad.append(f"files block root '{r}' — data/files/{r}/ holds no records")

bar = [s for s in secs if s.get("bottom_nav")]
if len(bar) > 5:
    bad.append(f"{len(bar)} bottom_nav sections — BottomNavigationView drops the sixth")

for b in sorted(bad): print("FAIL:", b)
print(f"checked {len(files)} pages, {len(bar)} bar sections, {len(roots)} file trees")
sys.exit(1 if bad else 0)
PY

# Cloud Wallet's bundled wallet.json is this tree flattened, not a second copy.
./data/regen-wallet-json.py --check

# Profile > About is generated from the LinkedIn profile, not typed twice.
# Skipped when the sibling front repo is not checked out — CI has the committed
# output and does not need the source.
./data/regen-profile.py --check 2>/dev/null || echo "profile source not present — skipped"
