package com.diegonmarcos.superapp.chat

import androidx.fragment.app.Fragment

/**
 * Page-factory entry point for the Chat section, called from
 * [com.diegonmarcos.superapp.SectionPages] when sectionId="chat".
 *
 * Mirrors libs/mail's `MailPages` pattern — keeps the chat section's
 * page → Fragment mapping inside the lib so [SectionPages] only owns
 * the cross-section routing layer. Adding a new chat sub-page = add a
 * branch here + the corresponding entry in
 * build.json::ui.sections[chat].pages[].
 */
object ChatPages {
    fun fragmentFor(pageId: String): Fragment = when (pageId) {
        "mattermost" -> MattermostFragment.newInstance()
        else         -> MattermostFragment.newInstance()  // fallback: only one page today
    }
}
