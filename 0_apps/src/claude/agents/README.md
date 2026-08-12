# Claude Code agents — fleet roster

Mirrored from `unix/da_my-ai/src/data/claude/agents/`, which is the source of
truth. Edit there, then re-copy here — not the other way round.

Committed into every repo's `.claude/agents/` because `~/.claude/agents/` only
exists where unix's home-manager has run. A fresh clone — CI, a container,
Claude Code on the web, a second machine, anyone else's checkout — has no user
config at all, and would otherwise have no agents.

Project agents take precedence over `~/.claude/agents/` of the same name, so on
a machine where home-manager has run these shadow their own upstream. That is
harmless while they are identical copies, and the reason the SoT is one-way:
edit unix, re-copy, never diverge.

## Design rules
1. **Model policy: every agent pins `model: sonnet`.** Agents are workers —
   the orchestrating session picks the expensive brain; workers stay cheap,
   fast, and predictable. Never `opus`/inherit in an agent definition.
2. **Effort policy: every agent pins `effort: medium`.** Same reasoning, and it
   has to live here: the Agent tool exposes a `model` override per spawn but no
   `effort` override, so the definition is the only place effort can be set.
   Valid values are `low | medium | high | xhigh | max`.
3. **One agent = one job.** Small description, tight tool list. No god-agents.
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
