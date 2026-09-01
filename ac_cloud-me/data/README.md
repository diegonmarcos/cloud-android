# data/

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
