package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.rss.RssFeedFragment
import com.diegonmarcos.superapp.cloud.NewsFeedFragment

import android.content.Context
import android.util.Base64
import org.json.JSONArray

/**
 * Runtime view of `build.json::ui.sections` — the single source of truth
 * for the navigation taxonomy. Baked into BuildConfig.UI_SECTIONS_JSON_B64
 * at gradle eval time, parsed lazily at first access.
 *
 * Adding / reordering sections, pages, or flipping `bottom_nav` is a
 * build.json edit — never a Kotlin edit.
 */
object Sections {

    data class Section(
        val id: String,
        val label: String,
        val iconName: String,
        /** Optional per-mode icon overrides — when non-null, [iconForMode]
         *  picks them over [iconName]. Lets Infos/Tools (and any other
         *  mode-aware section) swap glyphs between Apps and Admin in the
         *  bottom nav, drawer and Home Apps grid simultaneously.
         *  Source: build.json::sections[*].icon_apps / icon_admin. */
        val iconApps: String? = null,
        val iconAdmin: String? = null,
        val module: String?,
        val bottomNav: Boolean,
        /** Optional parent section id. When set, the toolbar/system Back at
         *  this section's root returns to the parent section instead of Home —
         *  e.g. WireGuard (its own bottom-nav section) declares parent="config"
         *  so Back from it lands on Configs, matching how it's surfaced there.
         *  Source: build.json::sections[*].parent. */
        val parent: String? = null,
        val isMasterIndex: Boolean,
        val pages: List<Page>,
        val defaultChildren: List<String>,
        /** When true AND the section has exactly one non-action page, opening
         *  the section lands directly on that page instead of a 1-tile grid —
         *  the section IS its page (e.g. WireGuard). Source:
         *  build.json::sections[*].single_page. */
        val singlePage: Boolean = false,
        /** Aggregator sections live ONLY in the bottom nav (Communication,
         *  Infos, Suite, Tools today). Their `tiles*` lists are deep-link
         *  pointers into real content sections, not pages of their own. */
        val isAggregator: Boolean = false,
        val tilesShared: List<AggTile> = emptyList(),
        val tilesApps:   List<AggTile> = emptyList(),
        val tilesAdmin:  List<AggTile> = emptyList(),
        /** Optional sub-grouping for sections like Suite / Labs that
         *  organise their tiles by theme. When non-empty:
         *   • [aggregatorTilesFor] flattens them so the bottom-nav grid
         *     still works without code changes.
         *   • [GroupedTilesFragment] renders one titled row per group on
         *     the bottom-nav SECTION page.
         *   • [homeGroups] FLATTENS them into a single HomeGroup card
         *     by default (matches Suite's "all in one horizontal row"
         *     spec). Set `group_explode: true` on the home_groups entry
         *     to EXPAND instead — one HomeGroup per TileGroup (Labs uses
         *     this so each sub-title gets its own card). */
        val tileGroups:  List<TileGroup> = emptyList(),
        /** Per-mode stack panels — vertical scroll of collapsable cards.
         *  When a stack_* list is non-empty for the active mode, the
         *  aggregator renders [AggregatorStackFragment] instead of the
         *  default tile grid. Both can coexist (tiles_apps + stack_admin). */
        val stackShared: List<StackPanel> = emptyList(),
        val stackApps:   List<StackPanel> = emptyList(),
        val stackAdmin:  List<StackPanel> = emptyList(),
        // long_press is gone: the fan menu now renders [pages] directly, so a
        // section declares its children ONCE. See LauncherToolbarFx.
    ) {
        /** Pick the right icon for the user's current mode.
         *  Apps vs Admin fall back to [iconName] when no override exists. */
        fun iconForMode(mode: String): String = when (mode) {
            "admin" -> iconAdmin ?: iconName
            else    -> iconApps  ?: iconName
        }
    }

    /** One collapsable card in an [AggregatorStackFragment].
     *  `kind` dispatches to a body builder in that fragment. Unknown
     *  kinds fall through to a placeholder card. */
    data class StackPanel(
        val kind: String,
        val title: String,
        val subtitle: String = "",
        val collapsed: Boolean = false,
        /** Used by kind=link_grid (flat) — single column of links. */
        val links:   List<LinkItem>   = emptyList(),
        /** Used by kind=link_grid (grouped) — multi-column with header. */
        val columns: List<LinkColumn> = emptyList(),
        /** Used by kind=tile_row — nested mini-tiles. */
        val tiles:   List<AggTile>    = emptyList(),
        /** Used by kind=linktree_slide — id of slide in data/linktree.json. */
        val slideId: String = "",
        /** Used by kind=linktree_slide — when true, each column of the
         *  referenced slide becomes its OWN top-level collapsible panel
         *  (header = column name, body = the column's 5-col icon grid)
         *  instead of nesting all columns inside one parent card. */
        val flattenColumns: Boolean = false,
        /** Used by kind=link / openExternal pointers. */
        val url: String = "",
        val iconName: String = "",
        /** Used by kind=repos / gha_runs — list of GitHub repos to
         *  surface commits / workflow runs from. Each entry resolves to
         *  api.github.com/repos/<owner>/<repo>/... at render time. */
        val repos: List<RepoRef> = emptyList(),
        /** Used by kind=stats — static label/value rows. A placeholder
         *  dashboard surface: mock numbers declared in build.json today,
         *  swapped for a live fetch once the card's module is plumbed. */
        val rows: List<StatRow> = emptyList(),
        /** Used by kind=cloud_dashboard — which cloud_services.json group(s)
         *  THIS card renders (one card per group → `group`; the Others card
         *  → `groups: [providers, mcpapi]`). */
        val dashGroupIds: List<String> = emptyList(),
    )

    /** One label/value line in a kind=stats dashboard card. */
    data class StatRow(val label: String, val value: String)

    // ── Cloud dashboard model (data/cloud_services.json) ─────────────────
    data class CloudDash(val groups: List<CloudGroup>)
    data class CloudGroup(
        val id: String, val label: String, val icon: String,
        val subgroups: List<CloudSub>, val providers: List<DashProvider>,
    )
    data class CloudSub(val label: String, val icon: String, val containers: List<CloudContainer>)
    /** A dashboard entry. When [external] the url is a full link opened
     *  directly with no status light (S3 / Google Workspace / consoles);
     *  otherwise url is a {name}.app host pinged on [port] + opened as https. */
    data class CloudContainer(
        val name: String, val label: String, val url: String,
        val port: Int, val external: Boolean,
        /** Optional explicit open-URL (e.g. a VM dashboard http://wg_ip:7680).
         *  When set, tap opens this verbatim while the status light still pings
         *  [url]:[port]. Empty → tap opens https://[url] (or [url] if external). */
        val link: String,
    )
    /** One external provider console in the Cloud dashboard Providers group. */
    data class DashProvider(val label: String, val url: String)

    /** One row in a kind=repos / gha_runs panel. `label` is the short
     *  display name; `owner`/`repo` build the API URL. */
    data class RepoRef(val owner: String, val repo: String, val label: String)

    data class LinkItem(
        val label: String,
        val url:   String,
        val icon:  String = "",
    )

    data class LinkColumn(
        val header:    String,
        val headerUrl: String = "",
        val links:     List<LinkItem>,
    )

    /** One slide from data/linktree.json (mirror of projects.json). */
    data class LinktreeSlide(
        val id:    String,
        val title: String,
        val columns: List<LinkColumn>,
    )

    /** One tile in an aggregator section. `target` follows the existing
     *  onTileClicked grammar — section:X | page:X/Y | action:X. */
    data class AggTile(
        val id: String,
        val label: String,
        val iconName: String,
        val target: String,
    )

    /** A themed sub-group of an aggregator section's tile list. */
    data class TileGroup(val title: String, val tiles: List<AggTile>)

    data class Page(
        val id: String,
        val label: String,
        val iconName: String?,
        /** Second-level children. Two sources in build.json::pages[X]:
         *  `sub_pages` (e.g. mail Settings → 11 FragmentOptions* tabs) or
         *  `menu` (e.g. mail More → 8 overflow items). Both flatten into
         *  this single list — the drawer treats them identically. */
        val subPages: List<Page> = emptyList(),
        /** Optional click target — when set, tapping this page DOES NOT
         *  open a sub-fragment but instead dispatches via the same
         *  grammar [MainActivity.onTileClicked] uses: section: / page: /
         *  action: / http: / intent:// / stub:. Used by the Config
         *  section (Update / Import / Linktree are pages with an
         *  `action` so the section's tile grid behaves like the old
         *  home_actions array). */
        val action: String = "",
        /** True ⇒ this page is a FACET of its aggregator section: it renders
         *  the section's own `tiles_<id>` / `stack_<id>` / `tile_groups` data
         *  ([LauncherNavController.aggregatorPage]) instead of a
         *  [SectionPages] factory. Declared in build.json so no page id is
         *  hardcoded in Kotlin — Tools' `apps`/`admin` are facets while its
         *  `c3`, `quant`, … siblings are ordinary content pages. */
        val facet: Boolean = false,
    )

    /** App-level action tile shown in the Home master TileGrid below the
     *  section tiles. `actionType` is the dispatcher key in MainActivity. */
    data class Action(
        val id: String,
        val label: String,
        val iconName: String,
        val actionType: String,
    )

    data class Sample(val title: String, val subtitle: String)

    /** A grouped Home tile — `id` is "section:<X>" or "page:<sec>/<page>",
     *  same format MainActivity.onTileClicked already understands.
     *  iconApps / iconAdmin are optional per-mode overrides; null falls
     *  back to iconName. Resolve via [HomeTile.iconForMode] at render time. */
    data class HomeTile(
        val id: String,
        val label: String,
        val iconName: String,
        val iconApps: String? = null,
        val iconAdmin: String? = null,
        /** Optional positional hint used ONLY on the explicit `tiles`
         *  entries of a home_groups row that ALSO declares
         *  `tiles_from_section`. When non-blank, the tile is inserted
         *  right after the derived tile whose id matches. Blank =
         *  appended at the end of the group (legacy behaviour). */
        val insertAfterId: String = "",
    ) {
        /** Pick the right icon for the user's current mode. Apps vs Admin
         *  fall back to the unconditional iconName when no override exists. */
        fun iconForMode(mode: String): String = when (mode) {
            "admin" -> iconAdmin ?: iconName
            else    -> iconApps  ?: iconName  // "apps" or anything else
        }
    }
    /** A Home Apps group. [scroll] is "horizontal" when the row should
     *  scroll horizontally instead of wrapping at the 5-col grid limit
     *  — typical use case is a long Suite row that doesn't deserve its
     *  own multi-row block on the Home screen. Null = normal grid wrap. */
    data class HomeGroup(
        val title: String,
        val tiles: List<HomeTile>,
        val scroll: String? = null,
    )

    /** wg-mesh/v1 node — one row in the WG mesh status table. */
    data class MeshNode(
        val name: String,
        val role: String,           // hub | spoke | client
        val alias: String,
        val publicIp: String,
        val wgIp: String,
        val region: String,
        val provider: String,
        val os: String,
        val publicKeyFp: String,
        val portsPublic: List<String>,
        val wstunnelServer: Boolean,
        val wstunnelClient: Boolean,
    )
    /** A peering relationship between two mesh nodes. */
    data class MeshPeer(
        val from: String,
        val to: String,
        val allowedIps: List<String>,
        val keepalive: Int,
    )
    /** WireGuard transport (wg0 direct UDP, wg0-tcp wstunnel fallback). */
    data class MeshTransport(
        val name: String,
        val label: String,
        val protocol: String,
        val port: Int,
        val endpoint: String,
        val primary: Boolean,
        val fallback: Boolean,
        val activePeers: Int,
        val useCase: String,
    )
    /** Whole wg-mesh/v1 snapshot. */
    data class Mesh(
        val nodes: List<MeshNode>,
        val peers: List<MeshPeer>,
        val transports: List<MeshTransport>,
    )

    /** One Drive · Connections row — external/internal storage backend. */
    data class DriveConnection(
        val name: String,
        val kind: String,        // s3 | google-drive | rclone | borg | http | …
        val endpoint: String,
        val auth: String,
        val vm: String,
        val status: String,      // ok | warn | down | unknown
        val scope: String,       // public | private
        val notes: String,
    )

    /** One row of the C3/Health public-services table. */
    data class PublicService(
        val name: String,
        val service: String,
        val vm: String,
        val publicUrl: String,
        val auth: String,
        val privateDns: String,
        val category: String,
    )
    /** One declared news / open-RSS feed for the Apps mode of Infos. */
    data class NewsFeed(
        val id:       String,
        val name:     String,
        val url:      String,   // RSS / Atom feed URL
        val siteUrl:  String,   // Optional landing page; if blank, falls back to url
        val category: String,   // World | Tech | Finance | Sport | …
        val icon:     String,
    )

    /** One mail account declared in build.json::ui.mail_accounts.
     *  `kind` picks the transport (jmap | imap | imaps | exchange).
     *  Today the libs:mail JMAP slice is the only one with full
     *  transport support; IMAP accounts surface as "open inbox" rows
     *  until the IMAP slice lands. */
    data class MailAccount(
        val id:      String,
        val label:   String,
        val kind:    String,    // jmap | imap | imaps | exchange
        val server:  String,    // JMAP base / IMAP host
        val user:    String,
        val imapPort: Int = 0,
        val smtpPort: Int = 0,
        val icon:    String = "",
    )

    /** One companion Android app the launcher opens INSTEAD of an in-app
     *  fragment (build.json::ui.external_apps). A tile target of
     *  `extapp:<id>/<forkKey>` resolves to one of these: [MainActivity.
     *  launchExternalApp] tries `forks[forkKey]`, then [hubPackage], then —
     *  if neither is installed — installs [installApkUrl] (a direct APK URL,
     *  e.g. a GitHub release asset) targeting [installPackage] via libs:updater.
     *  Cloud-Comms is the constellation hub + 3 fork APKs. */
    data class ExternalApp(
        val id: String,
        val label: String,
        val hubPackage: String,
        val installApkUrl: String,
        val installPackage: String,
        /** forkKey → fork applicationId (mail/chat/matrix). */
        val forks: Map<String, String>,
    )

    /** One row of the C3/Health private-services table. */
    data class PrivateService(
        val name: String,
        val service: String,
        val vm: String,
        val privateDns: String,
        val protocol: String,
        val category: String,
        val dbEngine: String,
    )

    @Volatile private var cached:         List<Section>?           = null
    @Volatile private var cachedActions:  List<Action>?            = null
    @Volatile private var cachedSamples:  Map<String, List<Sample>>? = null
    @Volatile private var cachedGroups:   List<HomeGroup>?         = null
    @Volatile private var cachedMesh:     Mesh?                    = null
    @Volatile private var cachedSvcPub:   List<PublicService>?     = null
    @Volatile private var cachedSvcPriv:  List<PrivateService>?    = null
    @Volatile private var cachedDrive:    List<DriveConnection>?   = null
    @Volatile private var cachedLinktree: Map<String, LinktreeSlide>? = null
    @Volatile private var cachedNews:     List<NewsFeed>?              = null
    @Volatile private var cachedMail:     List<MailAccount>?           = null
    @Volatile private var cachedExtApps:  List<ExternalApp>?           = null

    fun all(): List<Section> {
        cached?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_SECTIONS_JSON_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val parsed = mutableListOf<Section>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val pages = mutableListOf<Page>()
            o.optJSONArray("pages")?.let { pa ->
                for (j in 0 until pa.length()) {
                    val po = pa.getJSONObject(j)
                    val pIcon = po.optString("icon", "").takeIf { it.isNotEmpty() }
                    val subs = mutableListOf<Page>()
                    // Two equivalent shapes — sub_pages OR menu.
                    val subArr = po.optJSONArray("sub_pages") ?: po.optJSONArray("menu")
                    if (subArr != null) {
                        for (k in 0 until subArr.length()) {
                            val so = subArr.getJSONObject(k)
                            val sIcon = so.optString("icon", "").takeIf { it.isNotEmpty() }
                            subs.add(Page(so.getString("id"), so.getString("label"), sIcon))
                        }
                    }
                    pages.add(Page(
                        id       = po.getString("id"),
                        label    = po.getString("label"),
                        iconName = pIcon,
                        subPages = subs,
                        action   = po.optString("action", ""),
                        facet    = po.optBoolean("facet", false),
                    ))
                }
            }

            val kids = mutableListOf<String>()
            o.optJSONArray("drawer_default_children")?.let { ka ->
                for (j in 0 until ka.length()) kids.add(ka.getString(j))
            }

            // `module` may be JSON null (e.g. the master Home index). org.json
            // surfaces it as the string "null" via optString → normalize.
            val rawModule = o.optString("module", "")
            val module = rawModule.takeIf { it.isNotEmpty() && it != "null" }

            fun parseTilesInline(ta: org.json.JSONArray?): List<AggTile> {
                ta ?: return emptyList()
                val out = mutableListOf<AggTile>()
                for (j in 0 until ta.length()) {
                    val t = ta.getJSONObject(j)
                    out.add(
                        AggTile(
                            id       = t.optString("id", t.optString("label", "")),
                            label    = t.getString("label"),
                            iconName = t.optString("icon", "ic_settings"),
                            target   = t.optString("target", ""),
                        )
                    )
                }
                return out
            }

            fun parseTiles(arrName: String): List<AggTile> =
                parseTilesInline(o.optJSONArray(arrName))

            /** sections[].tile_groups[*] = {title, tiles:[…]} */
            fun parseTileGroups(arrName: String): List<TileGroup> {
                val arr = o.optJSONArray(arrName) ?: return emptyList()
                val out = mutableListOf<TileGroup>()
                for (j in 0 until arr.length()) {
                    val g = arr.getJSONObject(j)
                    out.add(TileGroup(
                        title = g.optString("title", ""),
                        tiles = parseTilesInline(g.optJSONArray("tiles")),
                    ))
                }
                return out
            }

            fun parseLinks(arr: org.json.JSONArray?): List<LinkItem> {
                arr ?: return emptyList()
                val out = mutableListOf<LinkItem>()
                for (j in 0 until arr.length()) {
                    val l = arr.getJSONObject(j)
                    out.add(LinkItem(
                        label = l.optString("label", ""),
                        url   = l.optString("url", ""),
                        icon  = l.optString("icon", ""),
                    ))
                }
                return out
            }

            fun parseColumns(arr: org.json.JSONArray?): List<LinkColumn> {
                arr ?: return emptyList()
                val out = mutableListOf<LinkColumn>()
                for (j in 0 until arr.length()) {
                    val c = arr.getJSONObject(j)
                    out.add(LinkColumn(
                        header    = c.optString("header", ""),
                        headerUrl = c.optString("header_url", ""),
                        links     = parseLinks(c.optJSONArray("links")),
                    ))
                }
                return out
            }

            fun parseStack(arrName: String): List<StackPanel> {
                val sa = o.optJSONArray(arrName) ?: return emptyList()
                val out = mutableListOf<StackPanel>()
                for (j in 0 until sa.length()) {
                    val p = sa.getJSONObject(j)
                    // Optional repos array for kind=repos / gha_runs.
                    val reposJson = p.optJSONArray("repos")
                    val reposList = mutableListOf<RepoRef>()
                    if (reposJson != null) {
                        for (k in 0 until reposJson.length()) {
                            val r = reposJson.getJSONObject(k)
                            reposList += RepoRef(
                                owner = r.optString("owner", ""),
                                repo  = r.optString("repo", ""),
                                label = r.optString("label", r.optString("repo", "")),
                            )
                        }
                    }
                    // Optional rows array for kind=stats (label/value lines).
                    val rowsJson = p.optJSONArray("rows")
                    val rowsList = mutableListOf<StatRow>()
                    if (rowsJson != null) {
                        for (k in 0 until rowsJson.length()) {
                            val r = rowsJson.optJSONObject(k) ?: continue
                            val lbl = r.optString("label"); val v = r.optString("value")
                            if (lbl.isNotBlank()) rowsList += StatRow(lbl, v)
                        }
                    }
                    // kind=cloud_dashboard — which cloud_services.json group(s)
                    // this card renders: `group` (string) or `groups` (array).
                    val dashGroupIds = mutableListOf<String>()
                    p.optString("group", "").takeIf { it.isNotBlank() }?.let(dashGroupIds::add)
                    p.optJSONArray("groups")?.let { ga ->
                        for (m in 0 until ga.length()) ga.optString(m).takeIf { it.isNotBlank() }?.let(dashGroupIds::add)
                    }
                    out.add(StackPanel(
                        kind            = p.optString("kind", "placeholder"),
                        title           = p.optString("title", ""),
                        subtitle        = p.optString("subtitle", ""),
                        collapsed       = p.optBoolean("collapsed", false),
                        links           = parseLinks(p.optJSONArray("links")),
                        columns         = parseColumns(p.optJSONArray("columns")),
                        tiles           = parseTilesInline(p.optJSONArray("tiles")),
                        slideId         = p.optString("slide_id", ""),
                        flattenColumns  = p.optBoolean("flatten_columns", false),
                        url             = p.optString("url", ""),
                        iconName        = p.optString("icon", ""),
                        repos           = reposList,
                        rows            = rowsList,
                        dashGroupIds    = dashGroupIds,
                    ))
                }
                return out
            }

            parsed.add(
                Section(
                    id              = o.getString("id"),
                    label           = o.getString("label"),
                    iconName        = o.optString("icon", "ic_settings"),
                    iconApps        = o.optString("icon_apps", "").takeIf { it.isNotBlank() },
                    iconAdmin       = o.optString("icon_admin", "").takeIf { it.isNotBlank() },
                    module          = module,
                    bottomNav       = o.optBoolean("bottom_nav", false),
                    parent          = o.optString("parent", "").takeIf { it.isNotBlank() },
                    isMasterIndex   = o.optBoolean("is_master_index", false),
                    pages           = pages,
                    defaultChildren = kids,
                    singlePage      = o.optBoolean("single_page", false),
                    isAggregator    = o.optBoolean("is_aggregator", false),
                    tilesShared     = parseTiles("tiles_shared"),
                    tilesApps       = parseTiles("tiles_apps"),
                    tilesAdmin      = parseTiles("tiles_admin"),
                    tileGroups      = parseTileGroups("tile_groups"),
                    stackShared     = parseStack("stack_shared"),
                    stackApps       = parseStack("stack_apps"),
                    stackAdmin      = parseStack("stack_admin"),
                )
            )
        }
        cached = parsed
        return parsed
    }

    /** One stop in the horizontal-swipe walk-list (build.json::ui.swipe_walk).
     *  [section] is a ui.sections id, or "home" for the Home-Apps overlay.
     *  Exactly one refinement is set per stop:
     *   • [mode]  apps|admin — Tabbed sections (Infos, Labs).
     *   • [tab]   cloud|phone — Suite's Cloud|Phone tab strip.
     *   • [sheet] cloud|phone — open the Home-Apps AppDrawerSheet on that tab.
     *  All null = land on the section's default page (e.g. Comms). */
    data class WalkStop(
        val id: String,
        val section: String,
        /** Page of [section] this stop lands on (build.json::swipe_walk[].page).
         *  Replaces the old `tab` (Suite) / `mode` (Infos, Labs) split — both
         *  were always just "which child of the section", i.e. a page id. */
        val page: String? = null,
        val sheet: String? = null,
        val label: String = "",
    )

    @Volatile private var cachedWalk: List<WalkStop>? = null

    /** build.json::ui.swipe_walk — the circular nav-stop order the
     *  bottom-nav swipe-cycle walks. Empty list ⇒ swipe-cycle is a no-op. */
    fun swipeWalk(): List<WalkStop> {
        cachedWalk?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_SWIPE_WALK_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableListOf<WalkStop>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(WalkStop(
                id      = o.optString("id", ""),
                section = o.getString("section"),
                page    = o.optString("page", "").takeIf { it.isNotBlank() },
                sheet   = o.optString("sheet", "").takeIf { it.isNotBlank() },
                label   = o.optString("label", ""),
            ))
        }
        cachedWalk = out
        return out
    }

    fun byId(id: String): Section? = all().firstOrNull { it.id == id }

    fun defaultSectionId(): String = BuildConfig.UI_DEFAULT_SECTION

    /** Apps/Admin global toggle default — overridden by ModePrefs at runtime. */
    fun defaultMode(): String = BuildConfig.UI_DEFAULT_MODE

    /** Aggregator's tiles for the given mode. tile_groups wins (flattened
     *  into a single list); then tiles_shared; then per-mode lists. */
    fun aggregatorTilesFor(sec: Section, mode: String): List<AggTile> = when {
        sec.tileGroups.isNotEmpty()  -> sec.tileGroups.flatMap { it.tiles }
        sec.tilesShared.isNotEmpty() -> sec.tilesShared
        mode == "admin"              -> sec.tilesAdmin
        else                         -> sec.tilesApps
    }

    /** Aggregator's stack panels for the given mode. `stack_shared` wins
     *  if present; otherwise apps/admin-specific list. Any panel with
     *  kind=linktree_slide + flatten_columns=true is expanded here into
     *  one synthetic link_grid panel per slide column, so each column
     *  reads as a top-level collapsible card. */
    fun aggregatorStackFor(sec: Section, mode: String): List<StackPanel> {
        val raw = when {
            sec.stackShared.isNotEmpty() -> sec.stackShared
            mode == "admin"              -> sec.stackAdmin
            else                         -> sec.stackApps
        }
        return raw.flatMap { panel ->
            if (panel.kind == "linktree_slide" && panel.flattenColumns) {
                val slide = linktreeSlide(panel.slideId) ?: return@flatMap listOf(panel)
                slide.columns.map { col ->
                    StackPanel(
                        kind     = "link_grid",
                        title    = col.header.ifBlank { panel.title },
                        subtitle = "",
                        // Body is a flat list of links — renderLinkGrid will
                        // emit them as a 5-col icon grid with no sub-header.
                        links    = col.links,
                    )
                }
            } else listOf(panel)
        }
    }

    /** True iff this aggregator has a non-empty stack for the given mode —
     *  i.e. render [AggregatorStackFragment] instead of the default tile grid. */
    fun aggregatorIsStack(sec: Section, mode: String): Boolean =
        aggregatorStackFor(sec, mode).isNotEmpty()

    fun homeActions(): List<Action> {
        cachedActions?.let { return it }
        cachedActions = parseActionsB64(BuildConfig.UI_HOME_ACTIONS_B64)
        return cachedActions!!
    }

    /** Drawer prepend list — rendered ABOVE the first section in the
     *  home drawer (build.json::ui.home_drawer_prepend). Same shape as
     *  home_actions; the actions dispatch through the same path. */
    fun homeDrawerPrepend(): List<Action> {
        cachedPrepend?.let { return it }
        cachedPrepend = parseActionsB64(BuildConfig.UI_HOME_DRAWER_PREPEND_B64)
        return cachedPrepend!!
    }

    private fun parseActionsB64(b64: String): List<Action> {
        val json = String(Base64.decode(b64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val parsed = mutableListOf<Action>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            parsed.add(
                Action(
                    // home_drawer_prepend entries don't have an `id` field
                    // (action_type is unique enough); fall back to that.
                    id         = o.optString("id", o.getString("action_type")),
                    label      = o.getString("label"),
                    iconName   = o.optString("icon", "ic_settings"),
                    actionType = o.getString("action_type"),
                )
            )
        }
        return parsed
    }
    private var cachedPrepend: List<Action>? = null

    /** Resolve `icon` name from build.json / linktree.json to a drawable
     *  res id. Handles both flavours:
     *   - Direct drawable name from build.json — `ic_settings`, `ic_p_c3_health`
     *   - Tabler/Remix-style svg from linktree.json — `map-pin-2.svg`,
     *     `device-desktop.svg` → tries `ic_map_pin_2`, then `ic_map_pin`,
     *     then the generic `ic_link_tile`.
     *
     *  Resolution order:
     *    1. literal name as-is
     *    2. stripped `.svg`, dashes → underscores, prefixed `ic_`
     *    3. progressively trimmed trailing `_<n>` suffixes (so
     *       `ic_map_pin_2` → `ic_map_pin`)
     *    4. `ic_link_tile` generic fallback
     *    5. 0 if even the fallback is missing
     */
    fun iconResFor(ctx: Context, name: String): Int {
        // Hot path on every aggregator render — a slide with 50 links calls
        // this 50× per panel rebuild, so cache by name. Cleared implicitly
        // on process death; Android resource ids are stable within a process.
        iconResCache[name]?.let { return it }
        val res = ctx.resources
        val pkg = ctx.packageName
        val fallback by lazy { res.getIdentifier("ic_link_tile", "drawable", pkg) }

        val resolved: Int = when {
            name.isBlank() -> fallback
            else -> {
                // 1. direct
                var hit = res.getIdentifier(name, "drawable", pkg)
                if (hit == 0) {
                    // 2. svg → ic_<slug>
                    val slug = name.removeSuffix(".svg").replace('-', '_').lowercase()
                    hit = res.getIdentifier("ic_$slug", "drawable", pkg)
                    // 3. progressively trim trailing _<n>
                    var trimmed = slug
                    while (hit == 0 && trailingIndexRe.containsMatchIn(trimmed)) {
                        trimmed = trailingIndexRe.replace(trimmed, "")
                        hit = res.getIdentifier("ic_$trimmed", "drawable", pkg)
                    }
                }
                if (hit != 0) hit else fallback
            }
        }
        iconResCache[name] = resolved
        return resolved
    }
    private val iconResCache = mutableMapOf<String, Int>()
    private val trailingIndexRe = Regex("_\\d+$")

    /** Per-page sample content from build.json::ui.page_samples. Keyed by
     *  "<section>/<page>". Returns empty list if no samples for that key. */
    fun pageSamples(key: String): List<Sample> {
        loadSamples()
        return cachedSamples?.get(key).orEmpty()
    }

    /** build.json::ui.home_groups — themed Home master view. Empty list
     *  means the legacy flat all-sections grid is used. */
    fun homeGroups(): List<HomeGroup> {
        cachedGroups?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_HOME_GROUPS_B64, Base64.NO_WRAP))
        val arr  = JSONArray(json)
        val parsed = mutableListOf<HomeGroup>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tilesArr = o.optJSONArray("tiles") ?: continue
            val tiles = mutableListOf<HomeTile>()
            for (j in 0 until tilesArr.length()) {
                val t = tilesArr.getJSONObject(j)
                tiles.add(
                    HomeTile(
                        id            = t.getString("id"),
                        label         = t.getString("label"),
                        iconName      = t.optString("icon", "ic_settings"),
                        iconApps      = t.optString("icon_apps", "").takeIf { it.isNotBlank() },
                        iconAdmin     = t.optString("icon_admin", "").takeIf { it.isNotBlank() },
                        insertAfterId = t.optString("insert_after_id", ""),
                    )
                )
            }
            val parentTitle = o.getString("title")
            val scrollMode  = o.optString("scroll", "").takeIf { it.isNotBlank() }

            // Optional `tiles_from_section: <id>` — pulls tiles from a
            // canonical section's list so the home_groups JSON stays a
            // single reference, never a duplicate. Behaviour depends on
            // the referenced section's shape AND the explicit
            // `group_explode` flag on this entry:
            //   • tile_groups + group_explode:true  → EXPAND into one
            //     HomeGroup card per TileGroup (Labs uses this).
            //   • tile_groups + group_explode:false → FLATTEN all
            //     groups' tiles into a single HomeGroup (Suite — all
            //     18 tiles in one horizontal scroll strip).
            //   • flat tiles_shared                 → single HomeGroup,
            //     tiles PREPENDED to whatever explicit `tiles` the entry
            //     declares.
            val groupExplode = o.optBoolean("group_explode", false)
            val fromSection  = o.optString("tiles_from_section", "").takeIf { it.isNotBlank() }
            val referenced   = fromSection?.let { byId(it) }

            if (referenced != null && referenced.tileGroups.isNotEmpty() && groupExplode) {
                // Each TileGroup becomes its own HomeGroup card. Title
                // is the group's own — no "parent · " prefix — so the
                // surface reads like the user's spec ("C3", "Data&ML",
                // …) rather than "Labs · C3".
                for (grp in referenced.tileGroups) {
                    parsed.add(HomeGroup(
                        title  = grp.title,
                        tiles  = grp.tiles.map { agg ->
                            HomeTile(id = agg.target, label = agg.label, iconName = agg.iconName)
                        },
                        scroll = scrollMode,
                    ))
                }
                // Tail-bucket for any explicit `tiles` declared after
                // the groups — labelled "Other" so it's clear they're
                // group-less extras.
                if (tiles.isNotEmpty()) {
                    parsed.add(HomeGroup(
                        title  = "Other",
                        tiles  = tiles,
                        scroll = scrollMode,
                    ))
                }
                continue
            }

            val derivedTiles = mutableListOf<HomeTile>()
            if (referenced != null) {
                when {
                    // tiles_shared = an explicit "home index" (one icon per
                    // category). Checked BEFORE tile_groups so a section that
                    // declares both (Suite) shows its category icons on the
                    // Home-Apps strip rather than flattening every app — the
                    // grouped apps still live on the bottom-nav section page.
                    referenced.tilesShared.isNotEmpty() -> {
                        referenced.tilesShared.forEach { agg ->
                            derivedTiles += HomeTile(
                                id       = agg.target,
                                label    = agg.label,
                                iconName = agg.iconName,
                            )
                        }
                    }
                    referenced.tileGroups.isNotEmpty() -> {
                        // FLATTEN: all tile_groups' tiles in declaration
                        // order become ONE HomeGroup card (sections with
                        // tile_groups but no tiles_shared index).
                        referenced.tileGroups.forEach { grp ->
                            grp.tiles.forEach { agg ->
                                derivedTiles += HomeTile(
                                    id = agg.target, label = agg.label, iconName = agg.iconName,
                                )
                            }
                        }
                    }
                    referenced.pages.isNotEmpty() -> {
                        // PAGES-DERIVED: synthesise one tile per declared
                        // sub-page of the referenced section. Single
                        // source of truth — sections[x].pages drives both
                        // the section page-grid AND the Home Apps row,
                        // so adding a page (e.g. Configs/Launcher) shows
                        // up everywhere automatically. Pages with an
                        // `action:` field route to that action directly;
                        // ones without resolve to page:<sectionId>/<pageId>.
                        referenced.pages.forEach { p ->
                            val target = if (p.action.isNotBlank()) p.action
                                         else "page:${referenced.id}/${p.id}"
                            derivedTiles += HomeTile(
                                id       = target,
                                label    = p.label,
                                iconName = p.iconName.orEmpty(),
                            )
                        }
                    }
                }
            }
            // Merge explicit `tiles` entries into the derived list.
            // Each extra tile is either positional (insertAfterId !=
            // "") — slotted right after the matching derived tile —
            // or appended (legacy behaviour). Lets a home_groups row
            // declare cross-section shortcuts (e.g. section:wg under
            // Configs) AND control where they land, without giving up
            // the derivation from sections.<x>.pages.
            for (extra in tiles) {
                if (extra.insertAfterId.isBlank()) {
                    derivedTiles += extra
                } else {
                    val idx = derivedTiles.indexOfFirst { it.id == extra.insertAfterId }
                    if (idx >= 0) derivedTiles.add(idx + 1, extra) else derivedTiles += extra
                }
            }
            // Optional `tiles_from_linktree: <slideId>` — pulls every link
            // from the named linktree slide and creates URL-target tiles.
            val fromLinktree = o.optString("tiles_from_linktree", "").takeIf { it.isNotBlank() }
            if (fromLinktree != null) {
                val slide = linktreeSlide(fromLinktree)
                if (slide != null) {
                    val linkTiles = slide.columns.flatMap { col ->
                        col.links.map { lnk ->
                            HomeTile(id = lnk.url, label = lnk.label, iconName = lnk.icon.ifBlank { "ic_link" })
                        }
                    }
                    parsed.add(HomeGroup(title = parentTitle, tiles = linkTiles, scroll = scrollMode))
                    continue
                }
            }

            parsed.add(HomeGroup(
                title  = parentTitle,
                tiles  = derivedTiles,
                scroll = scrollMode,
            ))
        }
        cachedGroups = parsed
        return parsed
    }

    /** data/mesh.json (wg-mesh/v1 schema) — nodes + peers + transports.
     *  Snapshot of cloud/a_solutions/bb-net_wireguard-mesh/src/data/mesh.json;
     *  regenerated via data/regen.sh. */
    fun mesh(): Mesh {
        cachedMesh?.let { return it }
        val json = String(Base64.decode(BuildConfig.MESH_JSON_B64, Base64.NO_WRAP))
        val root = org.json.JSONObject(json)

        val nodes = mutableListOf<MeshNode>()
        val nodesArr = root.optJSONArray("nodes") ?: org.json.JSONArray()
        for (i in 0 until nodesArr.length()) {
            val n = nodesArr.getJSONObject(i)
            val ports = mutableListOf<String>()
            n.optJSONArray("ports_public")?.let { pa ->
                for (j in 0 until pa.length()) ports.add(pa.getString(j))
            }
            nodes.add(
                MeshNode(
                    name           = n.getString("name"),
                    role           = n.optString("role", "spoke"),
                    alias          = n.optString("alias", ""),
                    publicIp       = n.optString("public_ip", ""),
                    wgIp           = n.optString("wg_ip", ""),
                    region         = n.optString("region", ""),
                    provider       = n.optString("provider", ""),
                    os             = n.optString("os", ""),
                    publicKeyFp    = n.optString("public_key_fp", ""),
                    portsPublic    = ports,
                    wstunnelServer = n.optBoolean("wstunnel_server", false),
                    wstunnelClient = n.optBoolean("wstunnel_client", false),
                )
            )
        }

        val peers = mutableListOf<MeshPeer>()
        val peersArr = root.optJSONArray("peers") ?: org.json.JSONArray()
        for (i in 0 until peersArr.length()) {
            val p = peersArr.getJSONObject(i)
            val allowed = mutableListOf<String>()
            p.optJSONArray("allowed_ips")?.let { aa ->
                for (j in 0 until aa.length()) allowed.add(aa.getString(j))
            }
            peers.add(
                MeshPeer(
                    from       = p.optString("from", ""),
                    to         = p.optString("to", ""),
                    allowedIps = allowed,
                    keepalive  = p.optInt("persistent_keepalive", 0),
                )
            )
        }

        val transports = mutableListOf<MeshTransport>()
        val tArr = root.optJSONArray("transports") ?: org.json.JSONArray()
        for (i in 0 until tArr.length()) {
            val t = tArr.getJSONObject(i)
            transports.add(
                MeshTransport(
                    name        = t.getString("name"),
                    label       = t.optString("label", t.getString("name")),
                    protocol    = t.optString("protocol", "udp"),
                    port        = t.optInt("port", 51820),
                    endpoint    = t.optString("endpoint", ""),
                    primary     = t.optBoolean("primary", false),
                    fallback    = t.optBoolean("fallback", false),
                    activePeers = t.optInt("active_peers", 0),
                    useCase     = t.optString("use_case", ""),
                )
            )
        }

        val out = Mesh(nodes, peers, transports)
        cachedMesh = out
        return out
    }

    /** data/services_public.json — containers with caddy proxy.domain. */
    fun publicServices(): List<PublicService> {
        cachedSvcPub?.let { return it }
        val json = String(Base64.decode(BuildConfig.SERVICES_PUBLIC_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableListOf<PublicService>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PublicService(
                    name       = o.optString("name", ""),
                    service    = o.optString("service", ""),
                    vm         = o.optString("vm", ""),
                    publicUrl  = o.optString("public_url", ""),
                    auth       = o.optString("auth", ""),
                    privateDns = o.optString("private_dns", ""),
                    category   = o.optString("category", ""),
                )
            )
        }
        cachedSvcPub = out.sortedBy { it.name }
        return cachedSvcPub!!
    }

    /** data/services_private.json — containers without a public proxy
     *  (databases, queues, internal MCPs, side-cars, sysadmin tooling). */
    fun privateServices(): List<PrivateService> {
        cachedSvcPriv?.let { return it }
        val json = String(Base64.decode(BuildConfig.SERVICES_PRIVATE_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableListOf<PrivateService>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                PrivateService(
                    name       = o.optString("name", ""),
                    service    = o.optString("service", ""),
                    vm         = o.optString("vm", ""),
                    privateDns = o.optString("private_dns", ""),
                    protocol   = o.optString("protocol", "tcp"),
                    category   = o.optString("category", ""),
                    dbEngine   = o.optString("db_engine", ""),
                )
            )
        }
        cachedSvcPriv = out.sortedBy { it.name }
        return cachedSvcPriv!!
    }

    /** build.json::ui.drive_connections — declarative list of storage
     *  backends shown under Drive · Connections. */
    fun driveConnections(): List<DriveConnection> {
        cachedDrive?.let { return it }
        val json = String(Base64.decode(BuildConfig.DRIVE_CONNECTIONS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val out = mutableListOf<DriveConnection>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                DriveConnection(
                    name     = o.optString("name", ""),
                    kind     = o.optString("kind", ""),
                    endpoint = o.optString("endpoint", ""),
                    auth     = o.optString("auth", ""),
                    vm       = o.optString("vm", "—"),
                    status   = o.optString("status", "unknown"),
                    scope    = o.optString("scope", "private"),
                    notes    = o.optString("notes", ""),
                )
            )
        }
        cachedDrive = out
        return out
    }

    /** build.json::ui.news_feeds — curated external RSS / news channels
     *  rendered by [NewsFeedFragment] under Infos · Apps. Different
     *  surface from the ntfy channels (those live in [RssFeedFragment]). */
    fun newsFeeds(): List<NewsFeed> {
        cachedNews?.let { return it }
        val json = String(Base64.decode(BuildConfig.NEWS_FEEDS_B64, Base64.NO_WRAP))
        val arr  = JSONArray(json)
        val out  = mutableListOf<NewsFeed>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(NewsFeed(
                id       = o.optString("id", ""),
                name     = o.optString("name", ""),
                url      = o.optString("url", ""),
                siteUrl  = o.optString("site_url", ""),
                category = o.optString("category", ""),
                icon     = o.optString("icon", ""),
            ))
        }
        cachedNews = out
        return out
    }

    /** build.json::ui.mail_accounts — declared seed accounts (JMAP / IMAP /
     *  IMAPS / Exchange). The libs:mail JMAP slice is currently the only
     *  transport with full read/send support; IMAP accounts render as a
     *  tappable row until the IMAP slice lands. */
    fun mailAccounts(): List<MailAccount> {
        cachedMail?.let { return it }
        val json = String(Base64.decode(BuildConfig.MAIL_ACCOUNTS_B64, Base64.NO_WRAP))
        val arr  = JSONArray(json)
        val out  = mutableListOf<MailAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ports = o.optJSONObject("ports")
            out.add(MailAccount(
                id       = o.optString("id", ""),
                label    = o.optString("label", ""),
                kind     = o.optString("kind", "jmap"),
                server   = o.optString("server", ""),
                user     = o.optString("user", ""),
                imapPort = ports?.optInt("imap", 0) ?: 0,
                smtpPort = ports?.optInt("smtp", 0) ?: 0,
                icon     = o.optString("icon", ""),
            ))
        }
        cachedMail = out
        return out
    }

    /** build.json::ui.external_apps — companion Android apps the launcher
     *  hands off to (Cloud-Comms hub + forks). Empty when the key is absent. */
    fun externalApps(): List<ExternalApp> {
        cachedExtApps?.let { return it }
        val json = String(Base64.decode(BuildConfig.EXTERNAL_APPS_B64, Base64.NO_WRAP))
        val arr  = JSONArray(json)
        val out  = mutableListOf<ExternalApp>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val forks = mutableMapOf<String, String>()
            o.optJSONObject("forks")?.let { fo ->
                val keys = fo.keys()
                while (keys.hasNext()) { val k = keys.next(); forks[k] = fo.getString(k) }
            }
            out.add(ExternalApp(
                id             = o.getString("id"),
                label          = o.optString("label", o.getString("id")),
                hubPackage     = o.optString("hub_package", ""),
                installApkUrl  = o.optString("install_apk_url", ""),
                installPackage = o.optString("install_package", o.optString("hub_package", "")),
                forks          = forks,
            ))
        }
        cachedExtApps = out
        return out
    }

    fun externalApp(id: String): ExternalApp? = externalApps().firstOrNull { it.id == id }

    /** data/linktree.json — mirror of front/a-Portals/linktree/src/data/
     *  projects.json. Lookup is by `slide.id` (suite | lab-tools |
     *  circus | cloud). Used by [StackPanel.kind] = "linktree_slide". */
    fun linktreeSlide(id: String): LinktreeSlide? {
        loadLinktree()
        return cachedLinktree?.get(id)
    }

    // ── Cloud dashboard inventory (data/cloud_services.json) ─────────────
    private var cachedCloudDash: CloudDash? = null
    /** Decode the curated container inventory baked into
     *  BuildConfig.CLOUD_SERVICES_B64. Cached after first parse. */
    fun cloudServices(): CloudDash {
        cachedCloudDash?.let { return it }
        val raw = runCatching {
            String(Base64.decode(BuildConfig.CLOUD_SERVICES_B64, Base64.DEFAULT))
        }.getOrDefault("{}")
        val o = runCatching { org.json.JSONObject(raw) }.getOrDefault(org.json.JSONObject())
        val groups = mutableListOf<CloudGroup>()
        o.optJSONArray("groups")?.let { ga ->
            for (i in 0 until ga.length()) {
                val g = ga.optJSONObject(i) ?: continue
                val subs = mutableListOf<CloudSub>()
                g.optJSONArray("subgroups")?.let { sa ->
                    for (j in 0 until sa.length()) {
                        val s = sa.optJSONObject(j) ?: continue
                        val cs = mutableListOf<CloudContainer>()
                        s.optJSONArray("containers")?.let { ca ->
                            for (k in 0 until ca.length()) {
                                val c = ca.optJSONObject(k) ?: continue
                                val nm = c.optString("name"); if (nm.isBlank()) continue
                                cs += CloudContainer(nm, c.optString("label", nm), c.optString("url"),
                                    c.optInt("port", -1), c.optBoolean("external", false),
                                    c.optString("link", ""))
                            }
                        }
                        subs += CloudSub(s.optString("label"), s.optString("icon"), cs)
                    }
                }
                val provs = mutableListOf<DashProvider>()
                g.optJSONArray("providers")?.let { pa ->
                    for (j in 0 until pa.length()) {
                        val pr = pa.optJSONObject(j) ?: continue
                        val l = pr.optString("label"); if (l.isNotBlank()) provs += DashProvider(l, pr.optString("url"))
                    }
                }
                groups += CloudGroup(g.optString("id"), g.optString("label"), g.optString("icon"), subs, provs)
            }
        }
        return CloudDash(groups).also { cachedCloudDash = it }
    }

    private fun loadLinktree() {
        if (cachedLinktree != null) return
        val json = String(Base64.decode(BuildConfig.LINKTREE_JSON_B64, Base64.NO_WRAP))
        val root = org.json.JSONObject(json)
        val slides = root.optJSONArray("slides") ?: org.json.JSONArray()
        val map = mutableMapOf<String, LinktreeSlide>()
        for (i in 0 until slides.length()) {
            val s = slides.getJSONObject(i)
            val cols = mutableListOf<LinkColumn>()
            val ca = s.optJSONArray("columns") ?: org.json.JSONArray()
            for (j in 0 until ca.length()) {
                val c = ca.getJSONObject(j)
                val links = mutableListOf<LinkItem>()
                val la = c.optJSONArray("links") ?: org.json.JSONArray()
                for (k in 0 until la.length()) {
                    val l = la.getJSONObject(k)
                    links.add(LinkItem(
                        label = l.optString("label", ""),
                        url   = l.optString("url", ""),
                        icon  = l.optString("icon", ""),
                    ))
                }
                cols.add(LinkColumn(
                    header    = c.optString("header", ""),
                    headerUrl = c.optString("header_url", ""),
                    links     = links,
                ))
            }
            val sid = s.optString("id", "")
            if (sid.isNotEmpty()) {
                map[sid] = LinktreeSlide(
                    id      = sid,
                    title   = s.optString("title", sid),
                    columns = cols,
                )
            }
        }
        cachedLinktree = map
    }

    private fun loadSamples() {
        if (cachedSamples != null) return
        val json = String(Base64.decode(BuildConfig.UI_PAGE_SAMPLES_B64, Base64.NO_WRAP))
        val obj  = org.json.JSONObject(json)
        val parsed = mutableMapOf<String, List<Sample>>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val arr = obj.optJSONArray(k) ?: continue
            val items = mutableListOf<Sample>()
            for (i in 0 until arr.length()) {
                val po = arr.getJSONObject(i)
                items.add(Sample(po.getString("title"), po.optString("subtitle", "")))
            }
            parsed[k] = items
        }
        cachedSamples = parsed
    }
}
