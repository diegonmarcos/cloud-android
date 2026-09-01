package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// comms: heuristic summarizer for the Sieve script Stalwart generates from
// cloud-u-containers' _shared/lib/mail-rules.nix (toSieve). Shape (see
// user-comm_tools-stalwart/dist/configs/default.sieve) is always:
//
//   # <rule.id>
//   if <sieve test> {
//     <addflag "..."|fileinto :copy :create "..."|stop;>*
//   }
//
// one rule-id comment + `if { }` block per TAG/ROUTE rule, generated
// verbatim by sieveTagBlock/sieveRouteBlock — never hand-edited (the file
// itself says "DO NOT EDIT"), so this shape is stable to parse against.
// Anything that doesn't match (section banners, the require[] line, the
// fallback block) is skipped rather than guessed at.
class SieveRules {
    static class Row {
        final String title;    // rule.id, e.g. "route.profile.tax_authorities"
        final String subtitle; // "<condition> → <action>"

        Row(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    // A rule-id comment line: lowercase dotted token, no spaces (excludes the
    // banner/section comments, which all contain spaces or box-drawing chars).
    private static final Pattern RULE_ID = Pattern.compile("^#\\s*([a-z0-9_]+(?:\\.[a-z0-9_]+)+)\\s*$");
    private static final Pattern IF_LINE = Pattern.compile("^if\\s+(.*?)\\s*\\{\\s*$");

    static List<Row> parse(String sieve) {
        List<Row> rows = new ArrayList<>();
        if (TextUtils.isEmpty(sieve))
            return rows;

        String[] lines = sieve.replace("\r\n", "\n").split("\n");

        String id = null;
        String condition = null;
        List<String> actions = null;

        for (String raw : lines) {
            String line = raw.trim();

            Matcher mid = RULE_ID.matcher(line);
            if (mid.matches()) {
                id = mid.group(1);
                condition = null;
                actions = null;
                continue;
            }

            if (id == null)
                continue; // not inside a recognized rule block

            Matcher mif = IF_LINE.matcher(line);
            if (condition == null && mif.matches()) {
                condition = mif.group(1);
                actions = new ArrayList<>();
                continue;
            }

            if (actions == null)
                continue;

            if ("}".equals(line)) {
                rows.add(new Row(id, summarize(condition, actions)));
                id = null;
                condition = null;
                actions = null;
                continue;
            }

            String action = describeAction(line);
            if (action != null)
                actions.add(action);
        }

        return rows;
    }

    private static String describeAction(String line) {
        if (line.startsWith("addflag")) {
            String flag = quoted(line);
            return flag == null ? "tag" : ("tag: " + flag);
        }
        if (line.startsWith("fileinto")) {
            String folder = quoted(line);
            return folder == null ? "file into folder" : ("file into: " + folder);
        }
        if (line.startsWith("stop"))
            return null; // implied by every route block, not worth a line
        return null;
    }

    // First "quoted string" on the line — good enough for addflag "X" and
    // fileinto :copy :create "Y" (multi-value header tests use their own
    // condition-side summary, not this action-side helper).
    private static String quoted(String line) {
        int a = line.indexOf('"');
        int b = (a < 0 ? -1 : line.indexOf('"', a + 1));
        return (a < 0 || b < 0) ? null : line.substring(a + 1, b);
    }

    private static String summarize(String condition, List<String> actions) {
        String cond = shorten(condition == null ? "?" : condition, 160);
        String action = actions.isEmpty() ? "(no visible action)" : TextUtils.join(", ", actions);
        return cond + " → " + action;
    }

    private static String shorten(String s, int max) {
        return (s.length() <= max) ? s : (s.substring(0, max - 1) + "…");
    }
}
