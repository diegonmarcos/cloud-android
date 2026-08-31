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

import java.util.Objects;

public class TupleRuleEx extends EntityRule {
    public long account;
    public String folderName;
    public String accountName;
    // comms: the folder/tag this rule FILES INTO, resolved from action.target.
    // folderName above is the folder the rule LIVES IN (almost always Inbox),
    // which is why sorting by it grouped every rule under one heading and told
    // you nothing about which tag a rule feeds. Null when the action does not
    // move (flag, keyword, snooze, ...). Not a column: it comes from JSON in
    // `action`, annotated off the main thread in FragmentRules.
    public String targetName;

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TupleRuleEx) {
            TupleRuleEx other = (TupleRuleEx) obj;
            return (super.equals(obj) &&
                    Objects.equals(this.targetName, other.targetName) &&
                    this.account == other.account &&
                    Objects.equals(this.folderName, other.folderName) &&
                    Objects.equals(this.accountName, other.accountName));
        } else
            return false;
    }
}
