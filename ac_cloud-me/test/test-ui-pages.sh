#!/usr/bin/env bash
# Cloud Me navigation check. build.json::ui.sections holds the shape, one file
# per page under data/ui/<section>/<page>.json holds the content, and nothing
# at build time notices a `page:` tile that points at a tab nobody declares.
# This does — it is the check that fails when the two halves drift apart.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import json, os, sys

ui   = json.load(open("build.json"))["ui"]
secs = ui["sections"]
apps = {a["id"] for a in ui.get("external_apps", [])}
bad  = []

pages = {s["id"]: [p["id"] for p in s.get("pages", [])] for s in secs}
files = {}
for s in secs:
    for p in pages[s["id"]]:
        f = f"data/ui/{s['id']}/{p}.json"
        if not os.path.exists(f):
            bad.append(f"missing page file {f}")
            continue
        files[(s["id"], p)] = json.load(open(f))
    # A section with no pages must be a launcher, or it is a dead bar item.
    if not pages[s["id"]] and not s.get("target"):
        bad.append(f"section {s['id']} has neither pages nor a target")
    if "stack_" + (pages[s["id"]] or [""])[0] in s:
        bad.append(f"section {s['id']} still inlines a stack in build.json")

for root, _, names in os.walk("data/ui"):
    for n in names:
        sec = os.path.basename(root)
        if n.endswith(".json") and (sec, n[:-5]) not in files:
            bad.append(f"orphan page file data/ui/{sec}/{n} — no page declares it")

# Every navigable target, from the section list and from every page file.
def targets(o):
    if isinstance(o, dict):
        if isinstance(o.get("target"), str): yield o["target"]
        for v in o.values(): yield from targets(v)
    elif isinstance(o, list):
        for v in o: yield from targets(v)

for t in set(targets(secs)) | {t for st in files.values() for t in targets(st)}:
    if t.startswith("extapp:"):
        if t.removeprefix("extapp:").split("/")[0] not in apps:
            bad.append(f"target {t} — no such ui.external_apps id")
    elif t.startswith(("page:", "section:")):
        sec, _, pg = t.split(":", 1)[1].partition("/")
        if sec not in pages:            bad.append(f"target {t} — no such section")
        elif pg and pg not in pages[sec]: bad.append(f"target {t} — no such page in {sec}")
    elif not t.startswith("http"):
        bad.append(f"target {t} — unknown grammar")

bar = [s for s in secs if s.get("bottom_nav")]
if len(bar) > 5:
    bad.append(f"{len(bar)} bottom_nav sections — BottomNavigationView drops the sixth")

for b in sorted(bad): print("FAIL:", b)
print(f"checked {len(files)} pages, {len(bar)} bar sections")
sys.exit(1 if bad else 0)
PY
