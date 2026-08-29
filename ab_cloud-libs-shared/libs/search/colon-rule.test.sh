#!/usr/bin/env bash
# The one rule libs:search lives or dies by: a query is a COMMAND only when its
# very first character is a colon. Everything else — including a colon in the
# middle of a sentence — is a search. Pure string logic, so it is checked here
# rather than on an emulator; the Kotlin it mirrors is SearchSheet.isCommandMode
# / commandQuery, which are two one-liners for exactly this reason.
set -u
fails=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; fails=$((fails+1)); }

# Mirror of the Kotlin: startsWith(":") on the RAW, un-trimmed query.
is_command() { case "$1" in :*) return 0;; *) return 1;; esac; }
command_query() { printf '%s' "${1#:}" | command sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | command tr 'A-Z' 'a-z'; }

check_search()  { if is_command "$1"; then fail "«$1» must be a SEARCH"; else pass "search: «$1»"; fi; }
check_command() { if is_command "$1"; then pass "command: «$1»"; else fail "«$1» must be a COMMAND"; fi; }

# The user's own examples, verbatim.
check_search  "Bars in Berlin:Mitte"
check_search  " Bars in Berlin:Mitte"
check_command ":update-all"

# A leading space means prose, not a command — this is why the check does not
# trim first, and it is the easiest thing to break by "tidying up".
check_search  " :update-all"
check_search  ""
check_search  "ratio 16:9"
check_search  "https://example.com"
check_command ":"
check_command ":check-updates"

# The alias is what follows the colon, trimmed and lowercased.
got=$(command_query ":  Update-All  ")
[ "$got" = "update-all" ] && pass "alias parse: «:  Update-All  » → $got" \
                          || fail "alias parse gave «$got», want «update-all»"
got=$(command_query ":")
[ -z "$got" ] && pass "bare «:» lists every command" || fail "bare «:» gave «$got»"

[ "$fails" = "0" ] && { echo "PASS — colon rule holds."; exit 0; }
echo "FAIL — $fails case(s)."; exit 1
