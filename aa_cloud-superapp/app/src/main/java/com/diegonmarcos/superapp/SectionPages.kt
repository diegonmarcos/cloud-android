package com.diegonmarcos.superapp

import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.mail.MailPages

/**
 * Per-section page registry — mirrors build.json::ui.sections[].pages[].
 *
 * Adding a new section's pages:
 *   1. `pages: [{id, label, upstream}]` in build.json::ui.sections[X].
 *   2. Implement page Fragments in libs:<x>/.
 *   3. Add the entry below.
 */
object SectionPages {

    data class Page(val id: String, val label: String, val factory: () -> Fragment)

    fun pagesFor(sectionId: String): List<Page> = when (sectionId) {
        "mail" -> listOf(
            Page(MailPages.INBOX,      "Inbox")      { MailPages.fragmentFor(MailPages.INBOX) },
            Page(MailPages.FOLDERS,    "Folders")    { MailPages.fragmentFor(MailPages.FOLDERS) },
            Page(MailPages.ACCOUNTS,   "Accounts")   { MailPages.fragmentFor(MailPages.ACCOUNTS) },
            Page(MailPages.IDENTITIES, "Identities") { MailPages.fragmentFor(MailPages.IDENTITIES) },
            Page(MailPages.RULES,      "Rules")      { MailPages.fragmentFor(MailPages.RULES) },
            Page(MailPages.ANSWERS,    "Answers")    { MailPages.fragmentFor(MailPages.ANSWERS) },
            Page(MailPages.CONTACTS,   "Contacts")   { MailPages.fragmentFor(MailPages.CONTACTS) },
            Page(MailPages.SETTINGS,   "Settings")   { MailPages.fragmentFor(MailPages.SETTINGS) },
            Page(MailPages.MORE,       "More")       { MailPages.fragmentFor(MailPages.MORE) },
        )
        else -> emptyList()
    }
}
