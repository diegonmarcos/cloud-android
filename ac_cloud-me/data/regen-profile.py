#!/usr/bin/env python3
"""front-diegonmarcos/b-Media/mySocials → data/ui/me/{professional,personal}.json

Profile's two halves, each rendered with Cloud Me's own blocks rather than a
second copy of the content:

  Professional  ← data-linkedin.json.js        (the LinkedIn profile)
  Personal      ← data-ig0-diegocnmarcos_.json.js  (the Instagram profile)

Both are the same files the web pages hydrate from, so refreshing the profile
means re-running this, not editing prose twice.

  ./data/regen-profile.py            rewrite data/ui/me/me.json
  ./data/regen-profile.py --check    exit 1 if it would change (drift)

The source lives in a sibling repo, so it is a dev-time refresher: the output
is committed and CI never needs the source. Same shape as regen-wallet-json.py.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.normpath(os.path.join(
    HERE, "..", "..", "..", "front-diegonmarcos", "b-Media", "mySocials", "dist"))
PAGES = {
    "professional": ("data-linkedin.json.js", "linkedin"),
    "personal": ("data-ig0-diegocnmarcos_.json.js", "ig0-diegocnmarcos_"),
}

# One accent per section, so a long scroll still parses as sections at a glance.
WORK = "#42A5F5"
STUDY = "#FFA726"
MAKE = "#7E57C2"
PERSONAL = "#EC407A"


def source(filename, key):
    """The JSON object out of the generated JS wrapper."""
    s = open(os.path.join(DIST, filename), encoding="utf-8").read()
    head = f'g.PORTAL_DATA["{key}"] = '
    raw = s[s.index(head) + len(head): s.rindex("}") + 1]
    # The wrapper closes with its own braces; shrink until it parses.
    for end in range(len(raw), 0, -1):
        try:
            return json.loads(raw[:end])
        except ValueError:
            continue
    sys.exit(f"could not parse the profile object out of {filename}")


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


def professional():
    d = source(*PAGES["professional"])
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
    return blocks


def personal():
    """The Instagram half. Photos first — it is a picture feed, and a page of
    captions with the pictures left out is not the same profile."""
    d = source(*PAGES["personal"])
    p = d["profile"]
    posts = d["posts"]
    captioned = [x for x in posts if (x.get("caption") or "").strip()]
    return [
        {"kind": "stats", "title": p.get("name") or p["username"],
         "subtitle": f"@{p['username']}", "rows": [
            {"label": "Posts", "value": str(p.get("posts", len(posts)))},
            {"label": "Following", "value": str(p.get("following", ""))},
            {"label": "Followers", "value": str(p.get("followers", ""))},
        ] + ([{"label": "Bio", "value": p["bio"]}] if p.get("bio") else [])},
        {"kind": "image_grid", "title": "Posts",
         "subtitle": f"{len(posts)} photos",
         "images": [x["media"] for x in posts if x.get("media")]},
        # The captions are place write-ups, so they read as a travel log rather
        # than as photo captions stripped of their photos.
        {"kind": "cards", "title": "Places",
         "subtitle": f"{len(captioned)} of {len(posts)} posts carry a note",
         "items": [
             {"title": tidy(x["caption"], 60).split("\n")[0].split(". ")[0],
              "body": tidy(x["caption"], 700), "accent": PERSONAL}
             for x in captioned
         ]},
    ]


def build(name):
    return json.dumps(
        professional() if name == "professional" else personal(),
        indent=2, ensure_ascii=False) + "\n"


def main():
    for name in PAGES:
        out = os.path.join(HERE, "ui", "me", f"{name}.json")
        text = build(name)
        if "--check" in sys.argv:
            if open(out, encoding="utf-8").read() != text:
                sys.exit(f"DRIFT: {out} does not match its source — run {sys.argv[0]}")
        else:
            open(out, "w", encoding="utf-8").write(text)
    print("profile pages " + ("match their sources" if "--check" in sys.argv else "written"))


main()
