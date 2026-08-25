package com.diegonmarcos.superapp.ops

import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.ops.dagu.DaguFragment

/**
 * Page-factory entry point for the C3/Infos section's ops pages.
 * Called from [com.diegonmarcos.superapp.SectionPages] when
 * sectionId="c3" + pageId="dagu" (and future "gha" once that
 * fragment lands too).
 *
 * Mirrors libs/chat's [com.diegonmarcos.superapp.chat.ChatPages]
 * and libs/mail's MailPages — keeps the section's page → Fragment
 * mapping inside the lib so [SectionPages] only owns the cross-
 * section routing layer.
 */
object OpsPages {
    fun fragmentForDagu(): Fragment = DaguFragment.newInstance()
}
