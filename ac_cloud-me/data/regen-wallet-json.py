#!/usr/bin/env python3
"""data/files/wallet/** → ac_cloud-wallet/app/src/main/assets/wallet.json

The file tree is the source. Cloud Wallet reads one bundled JSON because its
decks want the whole set in memory at once, so that file is this tree flattened
— not a second copy to edit. Edit a record here, run this, commit both.

  ./data/regen-wallet-json.py           rewrite wallet.json from the tree
  ./data/regen-wallet-json.py --check   exit 1 if it would change (drift)

ponytail: a script plus a drift check, not a Gradle generator in the wallet
app — same guarantee, and a bug here cannot break that app's build. Move it
into ac_cloud-wallet/app/build.gradle if the tree ever outgrows one commit.
"""
import collections, json, os, sys

HERE   = os.path.dirname(os.path.abspath(__file__))
TREE   = os.path.join(HERE, "files", "wallet")
TARGET = os.path.normpath(os.path.join(
    HERE, "..", "..", "ac_cloud-wallet", "app", "src", "main", "assets", "wallet.json"))

# wallet.json array → the folder holding it. `{country}` is one level of
# per-record fan-out; the key order here is the key order of the output.
MAP = collections.OrderedDict([
    ("vcards",   "vcards"),
    ("cards",    "pay"),
    ("passes",   "events/passes"),
    ("tickets",  "events/tickets"),
    ("ids",      "ids/id/{country}"),
    ("docs",     "ids/doc/{country}"),
    ("bookings", "events/bookings"),
])


def collect(folder):
    """Every .json under folder, ordered by id — which is the order the decks
    show, and the only order a directory listing can promise."""
    root = os.path.join(TREE, folder.split("{")[0].rstrip("/"))
    out = []
    for dirpath, _, names in os.walk(root):
        for n in sorted(names):
            if n.endswith(".json"):
                out.append(json.load(open(os.path.join(dirpath, n))))
    return sorted(out, key=lambda r: r["id"])


def build():
    # ids/ holds id/ and doc/ side by side; each pattern takes only its own.
    return collections.OrderedDict(
        (key, collect(folder)) for key, folder in MAP.items()
    )


def main():
    doc = build()
    if not any(doc.values()):
        sys.exit(f"refusing to write an empty wallet.json — is {TREE} there?")
    text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    if "--check" in sys.argv:
        current = open(TARGET).read()
        if current != text:
            sys.exit(f"DRIFT: {TARGET} does not match data/files/wallet/ — run {sys.argv[0]}")
        print(f"wallet.json matches the tree ({sum(len(v) for v in doc.values())} records)")
        return
    open(TARGET, "w").write(text)
    print(f"wrote {TARGET} ({sum(len(v) for v in doc.values())} records)")


main()
