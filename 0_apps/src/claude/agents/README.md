# Claude Code agents — fleet design

Source of truth for `~/.claude/agents/` (deployed by the flakes'
`home.activation.claudeAssets`) **and** for every repo's `.claude/agents/`.

The repo-scoped copies exist because `~/.claude/agents/` only exists where
home-manager has run. A fresh clone — CI, a container, Claude Code on the web,
a second machine, anyone else's checkout — has no user config at all and would
otherwise have no agents.

Those copies are **generated, not hand-mirrored**. cloud-infra's
`9_others/src/deploy-dotfiles.sh` refreshes `0_apps/src/claude/` from this
directory before deploying, so this is the only place to edit. The instruction
it replaced — "edit there, then re-copy here" — is exactly what let this
README drift between the two copies.

Project agents take precedence over `~/.claude/agents/` of the same name, so on
a home-manager machine the repo copies shadow their own upstream. Harmless
while they are generated from here; it is why the flow is one-way.

## Design rules
1. **Model policy: every agent pins `model: sonnet`.** Agents are workers —
   the orchestrating session picks the expensive brain; workers stay cheap,
   fast, and predictable. Never `opus`/inherit in an agent definition.
2. **Effort policy: every agent pins `effort: medium`.** Same reasoning as
   the model pin, and it has to live HERE: the Agent tool exposes a `model`
   override per spawn but no `effort` override, so the definition is the
   only place effort can be set. Valid values are `low | medium | high |
   xhigh | max` — `mid` is not one of them. Raise a single agent to `high`
   only if it is doing genuine judgement work (e.g. `review` if adversarial
   verification starts missing things); leave the workers at medium.
3. **One agent = one job.** Small description, tight tool list. No
   god-agents.
4. **Read-only by default.** Only `build` and `ops` get write/exec tools.
5. Format: markdown + YAML frontmatter (`name`, `description`, `tools`,
   `model`) — the standard Claude Code agent manifest.

## Roster
| agent   | job                                   | tools        |
|---------|---------------------------------------|--------------|
| explore | find code/files/facts, report back    | read-only    |
| build   | implement a scoped change             | full         |
| review  | adversarially verify a claim or diff  | read-only    |
| ops     | CI/CD, gh runs, docker, deploy checks | bash + read  |
