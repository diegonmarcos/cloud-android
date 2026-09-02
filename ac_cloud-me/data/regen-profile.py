#!/usr/bin/env python3
"""front-diegonmarcos/b-Media/mySocials → data/ui/me/me.json

Profile > About is the LinkedIn profile, rendered with Cloud Me's own blocks
rather than a second copy of the text. The source is the same
`data-linkedin.json.js` the web page hydrates from, so refreshing the profile
means re-running this, not editing prose twice.

  ./data/regen-profile.py            rewrite data/ui/me/me.json
  ./data/regen-profile.py --check    exit 1 if it would change (drift)

The source lives in a sibling repo, so it is a dev-time refresher: the output
is committed and CI never needs the source. Same shape as regen-wallet-json.py.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(
    HERE, "..", "..", "..", "front-diegonmarcos", "b-Media", "mySocials",
    "dist", "data-linkedin.json.js"))
OUT = os.path.join(HERE, "ui", "me", "me.json")

# One accent per section, so a long scroll still parses as sections at a glance.
WORK = "#42A5F5"
STUDY = "#FFA726"
MAKE = "#7E57C2"


def source():
    """The JSON object out of the generated JS wrapper."""
    s = open(SRC, encoding="utf-8").read()
    head = 'g.PORTAL_DATA["linkedin"] = '
    raw = s[s.index(head) + len(head): s.rindex("}") + 1]
    # The wrapper closes with its own braces; shrink until it parses.
    for end in range(len(raw), 0, -1):
        try:
            return json.loads(raw[:end])
        except ValueError:
            continue
    sys.exit(f"could not parse the profile object out of {SRC}")


def tidy(text, limit=1400):
    """LinkedIn's own line breaks, minus the runs of blank lines that read as
    holes on a phone. Truncated with a marker rather than silently."""
    t = re.sub(r"\n{3,}", "\n\n", (text or "").strip())
    return t if len(t) <= limit else t[:limit].rsplit("\n", 1)[0] + "\n…"


def entries(items, accent, title_key, sub_key, meta_keys):
    out = []
    for it in items:
        card = {"title": it.get(title_key, ""), "accent": accent}
        if it.get(sub_key):
            card["subtitle"] = it[sub_key]
        meta = [{"label": k.capitalize(), "value": it[k]} for k in meta_keys if it.get(k)]
        if meta:
            card["meta"] = meta
        if it.get("description"):
            card["body"] = tidy(it["description"])
        out.append(card)
    return out


def build():
    d = source()
    p = d["profile"]
    blocks = [
        # Header. `stats` already draws a titled card with label/value rows —
        # exactly a profile header, so it needs no new block kind.
        {"kind": "stats", "title": p["name"], "subtitle": p["headline"], "rows": [
            {"label": "Location", "value": p.get("location", "")},
            {"label": "Current", "value": p.get("current", "")},
            {"label": "Connections", "value": str(p.get("connections", ""))},
            {"label": "Followers", "value": str(p.get("followers", ""))},
            {"label": "Open to", "value": p.get("open_to_work", "")},
            {"label": "Profile", "value": p.get("url", "")},
        ]},
        {"kind": "note", "title": "About", "body": tidy(d["about"], 4000)},
        {"kind": "link_grid", "title": "Featured", "links": [
            {"label": f["title"], "icon": "ic_world", "target": f["url"]}
            for f in d["featured"]
        ]},
        {"kind": "cards", "title": "Experience",
         "items": entries(d["experience"], WORK, "title", "company", ["dates", "location"])},
        {"kind": "cards", "title": "Education",
         "items": entries(d["education"], STUDY, "school", "degree", ["dates"])},
        {"kind": "cards", "title": "Projects",
         "items": entries(d["projects"], MAKE, "title", "subtitle", ["dates"])},
        {"kind": "stats", "title": "Languages", "rows": [
            {"label": l["name"], "value": l["proficiency"]} for l in d["languages"]
        ]},
        {"kind": "note", "title": "Skills", "body": " · ".join(d["skills"])},
    ]
    return json.dumps(blocks, indent=2, ensure_ascii=False) + "\n"


def main():
    text = build()
    if "--check" in sys.argv:
        if open(OUT, encoding="utf-8").read() != text:
            sys.exit(f"DRIFT: {OUT} does not match the profile source — run {sys.argv[0]}")
        print("me.json matches the LinkedIn profile source")
        return
    open(OUT, "w", encoding="utf-8").write(text)
    print(f"wrote {OUT}")


main()
