package com.diegonmarcos.superapp

/**
 * Pure function: given an installed Android app's `packageName` + user-
 * facing `label`, decide which [PhoneFolders.Folder] it belongs to.
 *
 * Algorithm — deterministic + side-effect-free:
 *   1. Walk folders in declared order (already sorted by
 *      [PhoneFolders.loadFromBuildConfig]).
 *   2. For each folder, walk its `matchKeywords`. First keyword that
 *      matches the app wins; first matching folder wins overall.
 *   3. Empty-keyword folders (the `_Misc` / `_New Apps` sinks) are
 *      skipped during matching.
 *   4. Unmatched apps fall through to the sink folder.
 *
 * Keyword syntax — supports five forms, chosen on first character(s):
 *
 *   pkg:com.foo.bar     exact packageName match (case-insensitive)
 *   pkg^com.foo.        packageName.startsWith() match
 *   lbl:Exact Label     label exact match (case-insensitive)
 *   lbl~substring       label contains substring (min 3 chars after `~`)
 *   <plain string>      legacy substring match on `packageName + " " + label`
 *                       — REQUIRES min length 4 to suppress catastrophic
 *                       false-positives ("bb" matching "bbva" + every package
 *                       containing the letters "bb" anywhere, etc.).
 *
 * Anchored forms (pkg:, pkg^, lbl:, lbl~) are the only way to express a
 * precise match. Plain strings are a fallback for sweeping common app
 * names ("santander", "wireguard") where collision risk is low — never
 * use plain strings shorter than 4 chars.
 */
object PhoneAppClassifier {

    private const val MIN_PLAIN_KEYWORD_LEN = 4
    private const val MIN_LBL_TILDE_LEN = 3

    fun classify(packageName: String, label: String, folders: List<PhoneFolders.Folder>): String {
        val pkg = packageName.lowercase()
        val lbl = label.lowercase()
        val haystack = "$pkg $lbl"
        for (folder in folders) {
            if (folder.matchKeywords.isEmpty()) continue
            for (kw in folder.matchKeywords) {
                if (matches(kw, pkg, lbl, haystack)) return folder.id
            }
        }
        return PhoneFolders.sinkFolderId(folders)
    }

    private fun matches(kw: String, pkg: String, lbl: String, haystack: String): Boolean {
        if (kw.isEmpty()) return false
        val k = kw.lowercase()
        return when {
            k.startsWith("pkg:") -> {
                val t = k.removePrefix("pkg:")
                t.isNotEmpty() && pkg == t
            }
            k.startsWith("pkg^") -> {
                val t = k.removePrefix("pkg^")
                t.isNotEmpty() && pkg.startsWith(t)
            }
            k.startsWith("lbl:") -> {
                val t = k.removePrefix("lbl:")
                t.isNotEmpty() && lbl == t
            }
            k.startsWith("lbl~") -> {
                val t = k.removePrefix("lbl~")
                t.length >= MIN_LBL_TILDE_LEN && lbl.contains(t)
            }
            else -> k.length >= MIN_PLAIN_KEYWORD_LEN && haystack.contains(k)
        }
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
