package com.diegonmarcos.superapp.mail

import android.os.Bundle
import androidx.fragment.app.Fragment

/**
 * Page fragments for the Mail section, surfaced as the second drawer-tab row.
 * Each is the placeholder for the FairEmail-equivalent screen that will
 * cherry-pick into the slot.
 */
class MailFoldersFragment  : Fragment(R.layout.fragment_mail_folders)
class MailSettingsFragment : Fragment(R.layout.fragment_mail_settings)

/** Page id constants for libs:mail. Keep in lock-step with build.json. */
object MailPages {
    const val MENU     = "menu"
    const val FOLDERS  = "folders"
    const val SETTINGS = "settings"

    /** Factory for the section's pages. MainActivity routes through this. */
    fun fragmentFor(pageId: String): Fragment = when (pageId) {
        MENU     -> MailDrawerFragment.newInstance()
        FOLDERS  -> MailFoldersFragment()
        SETTINGS -> MailSettingsFragment()
        else     -> MailDrawerFragment.newInstance()
    }
}
