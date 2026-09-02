# data/

## `ui/` — one file per page

`ui/<section>/<page>.json` is the content of that page: the `stack_<page id>`
array the renderer walks. `build.json::ui.sections` keeps only the navigation
shape — id, label, icon, bar/drawer placement, and the `pages` list — and
`app/build.gradle` folds each file back in as `stack_<page id>` at build time,
which is where `Sections.kt` already looks. Nothing in the Kotlin changed.

Two rules the loader enforces so the split cannot rot:

* a page declared in `build.json` with no file here **fails the build**;
* `test/test-ui-pages.sh` fails on the reverse — a file no page declares — and
  on any `page:`/`extapp:` target that points at something that is not there.

Adding a page is still a data edit: one entry in that section's `pages`, one
file beside its siblings. The folder is the section, so Buro's tabs, the
Agenda's two and the Projects' three each live together instead of in one
1100-line blob where a page was a key prefix and nothing else.

A tab that declares `pages` of its own is a container: it holds no file, its
children live one folder deeper (`ui/buro/fin/acct.json`) and the app draws a
second, quieter strip beneath the first. Buro › Fin is the only one — Acct,
Budget and Portfolio are three views of one question.

A section with a `target` and no `pages` — Wallet, in the bottom bar — has no
folder here. It launches Cloud Wallet and hosts nothing.

## `files/` — the wallet's source of truth

`files/wallet/{pay,ids,vcards,events}/…` is where the wallet records actually
live: one JSON file per record, in folders, browsed as folders by Buro ›
Wallet. `app/build.gradle` ships the tree verbatim as assets, so the browser
walks it with `AssetManager.list()` and needs no manifest.

Cloud Wallet reads a single bundled `assets/wallet.json` because its decks want
the whole set in memory at once. That file is **this tree flattened** — not a
second copy to edit:

```
./data/regen-wallet-json.py            # tree → ac_cloud-wallet/…/wallet.json
./data/regen-wallet-json.py --check    # drift check, run by test/
```

Edit a record here, run the script, commit both. `test/test-ui-pages.sh` fails
if they disagree, which is what stops the two apps from telling different
stories about the same card.

## `calendars.json`

`calendars.json` is a **symlink** into `ac_cloud-calendar/data/`, not a copy.

`libs:cal` bakes it from `${rootDir}/data/calendars.json`, which resolves per
consuming app — so Cloud Me needed a file of its own here. A copy would be a
second subscription list to keep in step with the calendar app's, and the two
would silently disagree the first time one was edited. The symlink makes them
one list with one owner.

One consequence worth knowing: `ship-cloud-me.yml`'s `paths:` filter watches
`ac_cloud-me/**`, and a git symlink's content is the *path*, not the target.
Editing the subscriptions therefore rebuilds Cloud Calendar but not Cloud Me,
which picks the change up on its next build for any other reason.
