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
file beside its siblings. The folder is the section, so Buro's five tabs, the
Agenda's two and the Projects' three each live together instead of in one
1100-line blob where a page was a key prefix and nothing else.

A section with a `target` and no `pages` — Wallet — has no folder here. It
launches another app and hosts nothing.

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
