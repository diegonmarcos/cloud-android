package com.diegonmarcos.superapp

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
        val module: String?,
        val bottomNav: Boolean,
        val isMasterIndex: Boolean,
        val pages: List<Page>,
        val defaultChildren: List<String>,
        /** Aggregator sections live ONLY in the bottom nav (Communication,
         *  Infos, Suite, Tools today). Their `tiles*` lists are deep-link
         *  pointers into real content sections, not pages of their own. */
        val isAggregator: Boolean = false,
        val tilesShared: List<AggTile> = emptyList(),
        val tilesApps:   List<AggTile> = emptyList(),
        val tilesAdmin:  List<AggTile> = emptyList(),
        /** Per-mode stack panels — vertical scroll of collapsable cards.
         *  When a stack_* list is non-empty for the active mode, the
         *  aggregator renders [AggregatorStackFragment] instead of the
         *  default tile grid. Both can coexist (tiles_apps + stack_admin). */
        val stackShared: List<StackPanel> = emptyList(),
        val stackApps:   List<StackPanel> = emptyList(),
        val stackAdmin:  List<StackPanel> = emptyList(),
    )

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
        /** Used by kind=link / openExternal pointers. */
        val url: String = "",
        val iconName: String = "",
    )

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

    /** One slide from data/linktree.json (mirror of personal-tools.json). */
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

    data class Page(
        val id: String,
        val label: String,
        val iconName: String?,
        /** Second-level children. Two sources in build.json::pages[X]:
         *  `sub_pages` (e.g. mail Settings → 11 FragmentOptions* tabs) or
         *  `menu` (e.g. mail More → 8 overflow items). Both flatten into
         *  this single list — the drawer treats them identically. */
        val subPages: List<Page> = emptyList(),
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
     *  same format MainActivity.onTileClicked already understands. */
    data class HomeTile(val id: String, val label: String, val iconName: String)
    data class HomeGroup(val title: String, val tiles: List<HomeTile>)

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
                    pages.add(Page(po.getString("id"), po.getString("label"), pIcon, subs))
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
                    out.add(StackPanel(
                        kind      = p.optString("kind", "placeholder"),
                        title     = p.optString("title", ""),
                        subtitle  = p.optString("subtitle", ""),
                        collapsed = p.optBoolean("collapsed", false),
                        links     = parseLinks(p.optJSONArray("links")),
                        columns   = parseColumns(p.optJSONArray("columns")),
                        tiles     = parseTilesInline(p.optJSONArray("tiles")),
                        slideId   = p.optString("slide_id", ""),
                        url       = p.optString("url", ""),
                        iconName  = p.optString("icon", ""),
                    ))
                }
                return out
            }

            parsed.add(
                Section(
                    id              = o.getString("id"),
                    label           = o.getString("label"),
                    iconName        = o.optString("icon", "ic_settings"),
                    module          = module,
                    bottomNav       = o.optBoolean("bottom_nav", false),
                    isMasterIndex   = o.optBoolean("is_master_index", false),
                    pages           = pages,
                    defaultChildren = kids,
                    isAggregator    = o.optBoolean("is_aggregator", false),
                    tilesShared     = parseTiles("tiles_shared"),
                    tilesApps       = parseTiles("tiles_apps"),
                    tilesAdmin      = parseTiles("tiles_admin"),
                    stackShared     = parseStack("stack_shared"),
                    stackApps       = parseStack("stack_apps"),
                    stackAdmin      = parseStack("stack_admin"),
                )
            )
        }
        cached = parsed
        return parsed
    }

    fun byId(id: String): Section? = all().firstOrNull { it.id == id }

    fun defaultSectionId(): String = BuildConfig.UI_DEFAULT_SECTION

    /** Apps/Admin global toggle default — overridden by ModePrefs at runtime. */
    fun defaultMode(): String = BuildConfig.UI_DEFAULT_MODE

    /** Aggregator's tiles for the given mode. `tiles_shared` always wins
     *  if present; otherwise apps/admin-specific list. */
    fun aggregatorTilesFor(sec: Section, mode: String): List<AggTile> = when {
        sec.tilesShared.isNotEmpty() -> sec.tilesShared
        mode == "admin"              -> sec.tilesAdmin
        else                         -> sec.tilesApps
    }

    /** Aggregator's stack panels for the given mode. `stack_shared` wins
     *  if present; otherwise apps/admin-specific list. */
    fun aggregatorStackFor(sec: Section, mode: String): List<StackPanel> = when {
        sec.stackShared.isNotEmpty() -> sec.stackShared
        mode == "admin"              -> sec.stackAdmin
        else                         -> sec.stackApps
    }

    /** True iff this aggregator has a non-empty stack for the given mode —
     *  i.e. render [AggregatorStackFragment] instead of the default tile grid. */
    fun aggregatorIsStack(sec: Section, mode: String): Boolean =
        aggregatorStackFor(sec, mode).isNotEmpty()

    fun homeActions(): List<Action> {
        cachedActions?.let { return it }
        val json = String(Base64.decode(BuildConfig.UI_HOME_ACTIONS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        val parsed = mutableListOf<Action>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            parsed.add(
                Action(
                    id         = o.getString("id"),
                    label      = o.getString("label"),
                    iconName   = o.optString("icon", "ic_settings"),
                    actionType = o.getString("action_type"),
                )
            )
        }
        cachedActions = parsed
        return parsed
    }

    /** Resolve `icon` name from build.json to a drawable res id; 0 if missing. */
    fun iconResFor(ctx: Context, name: String): Int =
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName)

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
                        id       = t.getString("id"),
                        label    = t.getString("label"),
                        iconName = t.optString("icon", "ic_settings"),
                    )
                )
            }
            parsed.add(HomeGroup(o.getString("title"), tiles))
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

    /** data/linktree.json — mirror of front/a-Portals/linktree/src/data/
     *  personal-tools.json. Lookup is by `slide.id` (suite | lab-tools |
     *  circus | cloud). Used by [StackPanel.kind] = "linktree_slide". */
    fun linktreeSlide(id: String): LinktreeSlide? {
        loadLinktree()
        return cachedLinktree?.get(id)
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
