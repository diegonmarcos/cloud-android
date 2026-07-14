package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.rss.RssFeedFragment
import com.diegonmarcos.superapp.settings.LauncherConfigFragment
import com.diegonmarcos.superapp.cloud.DriveConnectionsFragment
import com.diegonmarcos.superapp.cloud.C3MeshFragment
import com.diegonmarcos.superapp.cloud.C3HealthFragment
import com.diegonmarcos.superapp.cloud.CalendarMonthFragment
import com.diegonmarcos.superapp.cloud.CalendarAgendaFragment
import com.diegonmarcos.superapp.network.WireGuardFragment
import com.diegonmarcos.superapp.profile.ProfileFragment

import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.ai.AiFragment
import com.diegonmarcos.superapp.health.HealthFragment
import com.diegonmarcos.superapp.chat.ChatPages
import com.diegonmarcos.superapp.mail.MailPages
import com.diegonmarcos.superapp.ops.OpsPages
import com.diegonmarcos.superapp.fin.MyFinDashboardFragment
// libs:wallet MOVED to ea_cloud-wallet (constellation APK). No WalletFragment here.

/**
 * Per-section page registry — now driven by [Sections] (which mirrors
 * `build.json::ui.sections[].pages[]`). The Kotlin side only owns the
 * **factory mapping** from a section's `pageId` to the actual Fragment
 * subclass; the *list* of pages and their order come from build.json.
 *
 * Adding a new section's pages:
 *   1. `pages: [{id, label, upstream}]` in build.json::ui.sections[X].
 *   2. Implement page Fragments in libs:<x>/.
 *   3. Add a `when` branch in [factoryFor] below for the new section id.
 */
object SectionPages {

    data class Page(val id: String, val label: String, val action: String = "", val factory: () -> Fragment)

    fun pagesFor(sectionId: String): List<Page> {
        val section = Sections.byId(sectionId) ?: return emptyList()
        return section.pages.map { p ->
            Page(p.id, p.label, p.action) { factoryFor(sectionId, p.id, p.label) }
        }
    }

    private fun factoryFor(sectionId: String, pageId: String, label: String): Fragment = when {
        sectionId == "mail"  -> MailPages.fragmentFor(pageId)
        sectionId == "chat"  -> ChatPages.fragmentFor(pageId)
        sectionId == "c3"    && pageId == "health"      -> C3HealthFragment.newInstance()
        sectionId == "c3"    && pageId == "dagu"        -> OpsPages.fragmentForDagu()
        sectionId == "wg"    && pageId == "status"      -> C3MeshFragment.newInstance()
        sectionId == "feed"  && pageId == "all"         -> RssFeedFragment.newInstance()
        sectionId == "drive" && pageId == "connections" -> DriveConnectionsFragment.newInstance()
        sectionId == "config" && pageId == "profile"   -> ProfileFragment.newInstance()
        sectionId == "config" && pageId == "ai"        -> AiFragment.newInstance()
        sectionId == "config" && pageId == "launcher" -> LauncherConfigFragment.newInstance()
        sectionId == "config" && pageId == "kde"            -> com.diegonmarcos.superapp.kdeconnect.KdeConnectFragment.newInstance()
        sectionId == "config" && pageId == "constellation"  -> com.diegonmarcos.superapp.configs.ConstellationFragment()
        sectionId == "config" && pageId == "wg"             -> WireGuardFragment.newInstance()
        sectionId == "myfin"   && pageId == "dashboard"     -> MyFinDashboardFragment.newInstance()
        sectionId == "cal"     && pageId == "month"         -> CalendarMonthFragment.newInstance()
        sectionId == "cal"     && pageId == "agenda"        -> CalendarAgendaFragment.newInstance()
        // "wallet" section is dead — tile target extapp:cloud-wallet bypasses openSectionPage.
        sectionId == "health"                               -> HealthFragment.newInstance(pageId)
        sectionId == "wg"     && pageId == "config"         -> WireGuardFragment.newInstance()
        sectionId == "config" && pageId == "onehand" ->
            com.diegonmarcos.superapp.configs.OneHandFragment.newInstance()
        sectionId == "config" && pageId == "perms" ->
            com.diegonmarcos.superapp.configs.PermissionsFragment.newInstance()
        sectionId == "config" && (pageId == "about" || pageId == "dev") ->
            com.diegonmarcos.superapp.devcontrol.DevControlFragment.newInstance()
        // "browser" section is dead — tile target extapp:cloud-browser bypasses openSectionPage.
        sectionId == "apptabs"                              ->
            com.diegonmarcos.superapp.apptabs.AppTabsFragment.newInstance()
        else -> PageContentFragment.newInstance(sectionId, pageId, label)
    }
}
