#!/usr/bin/env python3
"""front-diegonmarcos/b-Media/mySocials/dist → data/web/mysocials/

Profile does not reimplement the LinkedIn and Instagram layouts — it ships the
real pages and renders them. Anything else is an approximation that drifts the
moment the web design changes.

This copies exactly what those two pages reference: the HTML, the stylesheets
they link, and every data/script file in their <script src> tags. Nothing is
guessed and nothing unreferenced is carried.

  ./data/regen-web.py           refresh the bundle
  ./data/regen-web.py --check   exit 1 if it would change (drift)
"""
import filecmp, os, re, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.normpath(os.path.join(
    HERE, "..", "..", "..", "front-diegonmarcos", "b-Media", "mySocials", "dist"))
OUT = os.path.join(HERE, "web", "mysocials")

# The two pages Profile shows. Their dependencies are read out of the HTML, so
# adding a third page here is a one-line change.
PAGES = ["linkedin.html", "instagram-diegocnmarcos_.html"]

LOCAL_REF = re.compile(r'(?:src|href)="(?!https?://|#|mailto:)([^"]+)"')


def wanted():
    """Every page plus every local file those pages reference."""
    files = set(PAGES)
    for page in PAGES:
        html = open(os.path.join(DIST, page), encoding="utf-8").read()
        for ref in LOCAL_REF.findall(html):
            # index.html is the socials hub — a link out, not a dependency.
            if ref == "index.html":
                continue
            if os.path.isfile(os.path.join(DIST, ref)):
                files.add(ref)
    return sorted(files)


def main():
    if not os.path.isdir(DIST):
        print(f"mySocials dist not present at {DIST} — skipped")
        return
    files = wanted()
    check = "--check" in sys.argv
    if check:
        stale = [f for f in files
                 if not os.path.exists(os.path.join(OUT, f))
                 or not filecmp.cmp(os.path.join(DIST, f), os.path.join(OUT, f), shallow=False)]
        extra = [f for f in os.listdir(OUT) if f not in files] if os.path.isdir(OUT) else []
        if stale or extra:
            sys.exit(f"DRIFT: {len(stale)} stale, {len(extra)} unreferenced in {OUT} — run {sys.argv[0]}")
        print(f"web bundle matches mySocials dist ({len(files)} files)")
        return
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        if f not in files:
            os.remove(os.path.join(OUT, f))
    for f in files:
        shutil.copy2(os.path.join(DIST, f), os.path.join(OUT, f))
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in files)
    print(f"wrote {len(files)} files, {total // 1024}KB to {OUT}")


main()
