package com.diegonmarcos.superapp

import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.mail.MailPages

/**
 * Per-section page registry — mirrors build.json::ui.sections[].pages[].
 * Each section that has multiple pages (Menu/Folders/Settings/…) appears
 * here with its page list + a fragment factory. Sections without entries
 * fall through to the single PlaceholderDrawerFragment slot (no page row).
 *
 * Adding a new section's pages = three steps:
 *   1. Add `pages: [{id, label}]` to build.json::ui.sections[X].
 *   2. Implement the page Fragments in libs:<x>/.
 *   3. Add the entry below.
 */
object SectionPages {

    data class Page(val id: String, val label: String, val factory: () -> Fragment)

    /** Empty list = section has no page row in the drawer. */
    fun pagesFor(sectionId: String): List<Page> = when (sectionId) {
        "mail" -> listOf(
            Page(MailPages.MENU,     "Menu")     { MailPages.fragmentFor(MailPages.MENU) },
            Page(MailPages.FOLDERS,  "Folders")  { MailPages.fragmentFor(MailPages.FOLDERS) },
            Page(MailPages.SETTINGS, "Settings") { MailPages.fragmentFor(MailPages.SETTINGS) },
        )
        else -> emptyList()
    }
}
