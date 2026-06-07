package com.diegonmarcos.superapp

/**
 * Pure function: given an installed Android app's `packageName` + user-
 * facing `label`, decide which [PhoneFolders.Folder] it belongs to.
 *
 * Algorithm — deterministic + side-effect-free:
 *   1. Lowercase the haystack (`"$packageName $label"`).
 *   2. Walk folders in declared order (already sorted by
 *      [PhoneFolders.loadFromBuildConfig]).
 *   3. First folder whose `matchKeywords` contains any substring of the
 *      haystack wins. Empty-keyword folders (the `_Misc` sink) are
 *      skipped during matching.
 *   4. Unmatched apps fall through to the sink folder.
 *
 * This is a one-pass O(folders × keywords) match per app — fine for
 * even 500 installed apps × 30 folders × ~10 keywords on a phone.
 */
object PhoneAppClassifier {

    fun classify(packageName: String, label: String, folders: List<PhoneFolders.Folder>): String {
        val haystack = "$packageName $label".lowercase()
        for (folder in folders) {
            if (folder.matchKeywords.isEmpty()) continue
            for (kw in folder.matchKeywords) {
                if (kw.isNotEmpty() && haystack.contains(kw)) return folder.id
            }
        }
        return PhoneFolders.sinkFolderId(folders)
    }

    /** Group a list of installed apps into a folderId → apps map.
     *  Apps inside each folder are sorted alphabetically by label. */
    fun groupByFolder(
        apps: List<PhoneApp>,
        folders: List<PhoneFolders.Folder>,
    ): Map<String, List<PhoneApp>> {
        val grouped = LinkedHashMap<String, MutableList<PhoneApp>>()
        for (f in folders) grouped[f.id] = mutableListOf()
        for (app in apps) {
            val folderId = classify(app.packageName, app.label, folders)
            (grouped[folderId] ?: grouped.getOrPut(PhoneFolders.sinkFolderId(folders)) { mutableListOf() })
                .add(app)
        }
        return grouped.mapValues { (_, v) -> v.sortedBy { it.label.lowercase() } }
    }
}

/** Lightweight DTO for an installed launchable Android app — populated
 *  by [PhoneAppsFragment] from `LauncherApps.getActivityList(...)`. */
data class PhoneApp(
    val packageName: String,
    val activityComponent: android.content.ComponentName,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val user: android.os.UserHandle,
)
