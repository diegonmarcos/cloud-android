# patches/ — historical record

This series used to be applied live via `git am` at every `materialize-fork` run
(local and CI), on top of a fresh network clone of upstream ReFra (Gallery).

As of 2026-08-19, `materialize-fork` no longer clones or applies these — the fully
patched source is vendored directly in `ea_cloud-media-center/upstream/` (committed,
no `.git` inside it). These `.patch` files stay as the historical record of what
changed vs. upstream and why (see each patch's commit message).

Note: `ea_cloud-media-center/upstream/ml-models/src/` (the `WithML`-flavor model
binaries, ~322M) is deliberately not vendored — our build uses the `NoML` gradle
flavor (`assembleArm64-v8aNoMLRelease`), which never references that asset pack.
The `ml-models/` gradle module itself (`build.gradle.kts`, `README.md`) is kept so
the module still configures; its tasks no-op cleanly with no manifest/`src` present.

Making a new change: edit `ea_cloud-media-center/upstream/` directly, then (optional
but preferred for history) regenerate a patch documenting the change — e.g. commit
the edit in a scratch clone of upstream at the pinned tag and `git format-patch -1`
it into this directory with the next number in sequence. Nothing re-applies these
patches at build time; they're documentation, not a build step.
