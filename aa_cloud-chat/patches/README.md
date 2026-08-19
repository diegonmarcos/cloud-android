# patches/ — historical record

This series used to be applied live via `git am` at every `materialize-fork` run
(local and CI), on top of a fresh network clone of upstream Mattermost mobile.

As of 2026-08-19, `materialize-fork` no longer clones or applies these — the fully
patched source is vendored directly in `ea_cloud-chat/upstream/` (committed, no
`.git` inside it). These `.patch` files stay as the historical record of what
changed vs. upstream and why (see each patch's commit message).

Making a new change: edit `ea_cloud-chat/upstream/` directly, then (optional but
preferred for history) regenerate a patch documenting the change — e.g. commit the
edit in a scratch clone of upstream at the pinned tag and `git format-patch -1` it
into this directory with the next number in sequence. Nothing re-applies these
patches at build time; they're documentation, not a build step.
