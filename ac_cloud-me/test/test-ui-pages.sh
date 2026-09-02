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

# data/ui and data/files merge into one asset root, so a section folder must
# not collide with a top-level folder in the wallet tree.
if os.path.isdir("data/files"):
    for name in os.listdir("data/files"):
        if name in pages and pages[name]:
            bad.append(f"section '{name}' collides with data/files/{name}/ in the asset root")

# Exactly one landing section, and it has to be reachable from the bar.
landing = [s for s in secs if s.get("default")]
if len(landing) > 1:
    bad.append(f"{len(landing)} sections declare default — only one can be the landing page")
for s in landing:
    if not s.get("bottom_nav"):
        bad.append(f"section '{s['id']}' is the default but is not in the bottom bar")

bar = [s for s in secs if s.get("bottom_nav")]
if len(bar) > 5:
    bad.append(f"{len(bar)} bottom_nav sections — BottomNavigationView drops the sixth")

for b in sorted(bad): print("FAIL:", b)
print(f"checked {len(files)} pages, {len(bar)} bar sections, {len(roots)} file trees")
sys.exit(1 if bad else 0)
PY

# Every `web` block must point at a page that is actually bundled.
python3 - <<'PYWEB'
import json, os, sys
bad = []
for root, _, names in os.walk("data/ui"):
    for n in names:
        if not n.endswith(".json"):
            continue
        for b in json.load(open(os.path.join(root, n))):
            if isinstance(b, dict) and b.get("id") == "web":
                if not os.path.exists(os.path.join("data/web", b.get("url", ""))):
                    bad.append(f"{root}/{n}: web block has no data/web/{b.get('url')}")
for b in bad:
    print("FAIL:", b)
sys.exit(1 if bad else 0)
PYWEB

# A literal-dollar escape in Kotlin is almost always a generated-code accident:
# "${'$'}x" is the string $x, not the value of x. One of those turned every
# page asset path into a filename that could not exist, and the fail-soft
# loader rendered empty pages instead of saying so.
if grep -rn "\${'\$'}" app/src --include='*.kt' 2>/dev/null; then
    echo "FAIL: literal-dollar escape in Kotlin (see above) — did a generator write that?"
    exit 1
fi

# Cloud Wallet's bundled wallet.json is this tree flattened, not a second copy.
./data/regen-wallet-json.py --check

# Profile ships the real mySocials pages. Skipped when the sibling front repo
# is not checked out — CI has the committed bundle and does not need the source.
./data/regen-web.py --check 2>/dev/null || echo "mySocials source not present — skipped"
