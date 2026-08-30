package com.diegonmarcos.superapp.mail

import androidx.fragment.app.Fragment

/**
 * Factory for the Mail section's pages — mirrors build.json::ui.sections[mail]
 * .pages[]. Each id resolves to a Fragment placeholder pre-filled with the
 * page title + the upstream FairEmail class it'll be replaced by.
 *
 * Settings / More embed their child list inline so the user can navigate
 * down into a sub-page from this single placeholder pattern.
 */
object MailPages {

    // ── Level-1 page ids (must match build.json) ───────────────────────
    const val INBOX      = "inbox"
    const val FOLDERS    = "folders"
    const val ACCOUNTS   = "accounts"
    const val IDENTITIES = "identities"
    const val RULES      = "rules"
    const val ANSWERS    = "answers"
    const val CONTACTS   = "contacts"
    const val SETTINGS   = "settings"
    const val MORE       = "more"
    const val MENU       = "menu"      // alias used by drawer's section menu
    const val MESSAGES   = "messages"  // per-folder list; opened from Folders
    const val MESSAGE    = "message"   // single-message body; opened from Messages

    // Settings sub-pages (11). Encoded as "label|upstream" so the placeholder
    // can render and route them in one pass.
    private val SETTINGS_CHILDREN = arrayOf(
        "Behavior|FragmentOptionsBehavior",
        "Display|FragmentOptionsDisplay",
        "Sync|FragmentOptionsSynchronize",
        "Notifications|FragmentOptionsNotifications",
        "Send|FragmentOptionsSend",
        "Connection|FragmentOptionsConnection",
        "Encryption|FragmentOptionsEncryption",
        "Privacy|FragmentOptionsPrivacy",
        "Integrations|FragmentOptionsIntegrations",
        "Backup|FragmentOptionsBackup",
        "Misc|FragmentOptionsMisc",
    )

    // More overflow (8 diagnostic / power-user pages).
    private val MORE_CHILDREN = arrayOf(
        "Order|FragmentOrder",
        "Operations|FragmentOperations",
        "Logs|FragmentLogs",
        "Certificates|FragmentCertificates",
        "Pro|FragmentPro",
        "Legend|FragmentLegend",
        "About|FragmentAbout",
        "EULA|FragmentEula",
    )

    fun fragmentFor(pageId: String): Fragment = fragmentFor(pageId, null)

    /** Variant with optional args (e.g. MESSAGES carries folder_id/name). */
    fun fragmentFor(pageId: String, args: android.os.Bundle?): Fragment = when (pageId) {
        INBOX      -> MailPagePlaceholder.newInstance("Inbox",      "FragmentMessages")
        FOLDERS    -> MailFoldersFragment.newInstance()
        // JMAP login form lives under Accounts now — the section index is a
        // TileGrid (see app/MainActivity), not a single screen.
        ACCOUNTS   -> MailFragment.newInstance()
        IDENTITIES -> MailPagePlaceholder.newInstance("Identities", "FragmentIdentities")
        RULES      -> MailPagePlaceholder.newInstance("Rules",      "FragmentRules")
        ANSWERS    -> MailPagePlaceholder.newInstance("Answers",    "FragmentAnswers")
        CONTACTS   -> MailPagePlaceholder.newInstance("Contacts",   "FragmentContacts")
        SETTINGS   -> MailPagePlaceholder.newInstance("Settings",   "FragmentOptions", SETTINGS_CHILDREN)
        MORE       -> MailPagePlaceholder.newInstance("More",       "(overflow)",     MORE_CHILDREN)
        MENU       -> MailDrawerFragment.newInstance()
        MESSAGES   -> MailMessagesFragment.newInstance(
            folderId   = args?.getString(MailMessagesFragment.ARG_FOLDER_ID)   ?: "",
            folderName = args?.getString(MailMessagesFragment.ARG_FOLDER_NAME) ?: "(folder)",
        )
        MESSAGE    -> MailMessageBodyFragment.newInstance(
            emailId = args?.getString(MailMessageBodyFragment.ARG_EMAIL_ID) ?: "",
            subject = args?.getString(MailMessageBodyFragment.ARG_SUBJECT)  ?: "",
            from    = args?.getString(MailMessageBodyFragment.ARG_FROM)     ?: "",
            date    = args?.getString(MailMessageBodyFragment.ARG_DATE)     ?: "",
        )
        else       -> MailPagePlaceholder.newInstance(pageId,       "?")
    }
}
